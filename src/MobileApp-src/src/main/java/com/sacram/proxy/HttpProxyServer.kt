package com.sacram.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP proxy (RFC 7230 style): handles plain HTTP requests with absolute-form
 * URIs and CONNECT tunnels (HTTPS). No UDP - this is TCP-only by design.
 */
class HttpProxyServer(
    private val port: Int,
    private val context: Context,
    private val goIp: String = "192.168.49.1",
    // Dedicated control-panel port. Requests that hit THIS proxy for one of our
    // own addresses (the old panel URL) are redirected/told to use the separate
    // PanelServer instead - the panel no longer runs on the shared proxy pool.
    private val panelPort: Int = -1,
    private val onLog: (String) -> Unit = {},
    private val onStaleDetected: () -> Unit = {}
) {

    // Dedicated worker pool. DNS resolution and TCP connect() are blocking, and
    // a busy page opens dozens of parallel connections. On the shared
    // Dispatchers.IO (capped at ~64 threads) those blocks would queue, so a
    // request kicked off right after closing a heavy tab would wait for a free
    // thread -> the whole proxy "hangs for a few seconds". A private pool lets
    // many connections establish concurrently so the proxy keeps responding.
    // Elastic pool: threads are created on demand (one per live connection) and
    // reclaimed after 60s idle instead of sitting parked - no wasted standby
    // threads. [concurrencySem] hard-caps concurrent connections at 512.
    private val workerExecutor = Executors.newCachedThreadPool()
    private val concurrencySem = Semaphore(512)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val DNS_POOL_SIZE = 16
    private val dnsExecutor = Executors.newFixedThreadPool(DNS_POOL_SIZE)
    private val dnsTimeoutMs = 5_000
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    private var cellularNetwork: Network? = null
    // Last network we confirmed had cellular+internet. We keep this even after a
    // transient onLost so egress never silently falls back to the WiFi-Direct
    // interface (which has no internet) and kills every connection at once.
    private var lastGoodCellular: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Cached outbound (cellular) network selection, mirroring Socks5Server.
    // NetworkUtils.pickCellular() scans cm.allNetworks + calls
    // getNetworkCapabilities() per network - too expensive to run on every
    // new upstream connection (a busy page opens dozens in parallel). Cache
    // the resolved Network and only re-validate on a short timer.
    private var cachedNet: Network? = null
    private var cachedNetTime = 0L

    private fun pickNet(): Network? {
        val now = System.currentTimeMillis()
        // Some OEMs (Honor/Huawei/Xiaomi) report a cellular Network with the
        // INTERNET capability that nonetheless does not route - binding to it
        // burns the full connect timeout before dial() gives up and falls back.
        // The system's own active network is what the phone itself successfully
        // uses for its own traffic, so it is the most trustworthy signal and is
        // checked first on every call (cheap: one getNetworkCapabilities lookup).
        val active = cm.activeNetwork
        if (NetworkUtils.isValidEgress(cm, active)) {
            if (cachedNet != active) { cachedNet = active; cachedNetTime = now }
            return active
        }
        val cached = cachedNet
        if (cached != null && now - cachedNetTime < 8000 && NetworkUtils.isValidEgress(cm, cached)) {
            return cached
        }
        // Pass null as preferred so pickCellular applies its active-network-first
        // logic instead of blindly reusing the (possibly dead) cellular binding.
        val n = NetworkUtils.pickCellular(cm, null) ?: lastGoodCellular ?: cached
        cachedNet = n
        cachedNetTime = now
        return n
    }

    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val connPool = ConcurrentHashMap<String, MutableList<Pair<Socket, Long>>>()
    // Success/failure timestamps feeding the stale-egress auto-heal watchdog.
    // Failures without any success for STALE_TIMEOUT_MS trigger a restart.
    // Auto-heal: last time we saw a successful vs a failed upstream request.
    // If failures keep happening but nothing succeeds for STALE_TIMEOUT_MS, the
    // egress network (cellular) has almost certainly gone stale and we ask the
    // service to restart the proxy so it re-binds a fresh cellular network.
    private val lastSuccessMs = AtomicLong(0L)
    private val lastFailureMs = AtomicLong(0L)
    private val autoRestartGuard = AtomicBoolean(false)
    private var staleWatchdogJob: Job? = null
    private val STALE_TIMEOUT_MS = 2 * 60_000L
    private val dnsTtlMs = 60_000L
    private val poolMax = 48
    private val poolIdleMs = 60_000L
    private val connectTimeoutMs = 6_000
    private val readTimeoutMs = 20_000
    // Socket buffer sizes. Cellular (the egress path) is high-latency, so the
    // bandwidth-delay product is large; the platform-default receive/send
    // buffers (~8-64KB) cap a single stream's throughput far below what the
    // radio can do. Bumping these lets each tunnel actually saturate the link,
    // which is what makes "many heavy pages at once" feel fast instead of
    // serialised. Set before connect()/accept() so the sizes take effect.
    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024
    // Larger client-side output buffer so a fast upstream can drain into the
    // (slower, WiFi-Direct) client without stalling on tiny 8KB flushes.
    private val clientBufSize = 64 * 1024
    private val upstreamBufSize = 64 * 1024
    // Idle timeout for CONNECT tunnels. Per-tunnel and deliberately longer than
    // readTimeoutMs: a stalled upstream (server->client direction) with no
    // soTimeout would block the pumping coroutine forever, leaking the slot.
    // Multiple streams over cellular have naturally longer buffer-starved gaps,
    // so 100s idle (not 20s) keeps live-but-quiet tunnels alive. Triggers a
    // SocketTimeoutException on read, which pump() treats as EOF and closes.
    private val tunnelIdleTimeoutMs = 100_000
    // Live count of open CONNECT tunnels, surfaced via AppState for the panel.
    private val tunnelCount = AtomicInteger(0)

    private fun bindToCellular() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                lastGoodCellular = network
                clearPool()
                clearDns()
                onLog("Bound to cellular network: $network")
            }
            override fun onLost(network: Network) {
                if (cellularNetwork != network) return
                // A blip while we're the WiFi-Direct Group Owner is common. Don't
                // drop egress: try to find another live cellular network first,
                // otherwise keep the reference so callers can still bind to it.
                val alt = cm.allNetworks.firstOrNull { isValidCellular(it) }
                if (alt != null && alt != network) {
                    cellularNetwork = alt
                    lastGoodCellular = alt
                    clearPool()
                    clearDns()
                    onLog("Cellular network switched: $network -> $alt")
                } else {
                    // No replacement yet - drop any sockets bound to the now-dead
                    // network and forget the stale reference so the next request
                    // re-scans cm.allNetworks and binds to a fresh cellular network
                    // the instant Android restores it (instead of clinging to the
                    // dead binding for the whole outage).
                    cellularNetwork = null
                    lastGoodCellular = null
                    clearPool()
                    clearDns()
                    onLog("WARNING: cellular network $network lost; egress will re-scan for a live cellular network")
                }
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb)
        scope.launch {
            delay(3000)
            if (cellularNetwork == null && lastGoodCellular == null) {
                onLog("WARNING: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    fun start() {
        running.set(true)
        bindToCellular()
        tcpJob = scope.launch { runServer() }
        staleWatchdogJob = scope.launch { runStaleWatchdog() }
        onLog("HTTP proxy listening on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        staleWatchdogJob?.cancel()
        clearPool()
        netCallback?.let {
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        tcpJob?.cancel()
        scope.cancel()
        runCatching { workerExecutor.shutdownNow() }
        runCatching { dnsExecutor.shutdownNow() }
    }

    private fun tuneSocket(sock: Socket) {
        runCatching { sock.setReceiveBufferSize(socketRcvBuf) }
        runCatching { sock.setSendBufferSize(socketSndBuf) }
    }

    private suspend fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            runCatching { ss.setReceiveBufferSize(socketRcvBuf) }
            // Bigger accept backlog so a burst of parallel connections from a
            // busy page (dozens of sub-resource fetches) doesn't get dropped
            // while the acceptor coroutine is busy.
            ss.bind(InetSocketAddress("0.0.0.0", port), 1024)
            serverSocket = ss
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                tuneSocket(client)
                scope.launch {
                    concurrencySem.acquire()
                    try {
                        handleClient(client)
                    } finally {
                        concurrencySem.release()
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("HTTP server error: $e")
        }
    }

    private suspend fun handleClient(client: Socket) {
        val clientIp = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: ""
        val meteredIn = CountingInputStream(client.getInputStream())
        val meteredOut = CountingOutputStream(client.getOutputStream())
        val reader = StreamReader(meteredIn)
        val output = BufferedOutputStream(meteredOut, clientBufSize)
        try {
            client.soTimeout = 300000
            while (running.get()) {
                val requestLine = readLine(reader) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 3) break
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = readHeaders(reader) ?: break

                if (method == "CONNECT") {
                    handleConnect(client, reader, output, target)
                    break
                }

                // The control panel now lives on its own dedicated port. If a request
                // aimed at one of our own addresses reaches this proxy anyway (e.g. the
                // browser pushes all traffic through us), forward it straight to the
                // PanelServer over the local interface instead of bouncing the client
                // with a "moved" notice. See PanelServer.kt.
                if (isSelfHostRequest(method, target, headers)) {
                    val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                    val (_, _, selfPath) = parseAbsoluteUri(target, headers)
                    forwardPlain(reader, output, method, goIp, panelPort, selfPath.ifEmpty { "/" }, headers, keepAlive, local = true)
                    if (!keepAlive) break
                    continue
                }

                val (host, port, path) = parseAbsoluteUri(target, headers)
                if (host == null) {
                    writeSimpleResponse(output, 400, "Bad Request - absolute URI required")
                    break
                }
                val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                forwardPlain(reader, output, method, host, port, path, headers, keepAlive)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            ClientUsage.add(clientIp, meteredIn.bytes() + meteredOut.bytes())
            runCatching { client.close() }
        }
    }

    private suspend fun forwardPlain(
        input: StreamReader,
        output: BufferedOutputStream,
        method: String,
        host: String,
        port: Int,
        path: String,
        headers: List<String>,
        clientKeepAlive: Boolean,
        local: Boolean = false
    ) {
        // Retry once on a brand-new upstream socket. The first attempt may reuse a
        // pooled socket that went half-dead on the flaky cellular egress; if it
        // fails before we've sent a single byte to the client we transparently
        // retry with a fresh connection (re-resolving the egress network). This is
        // what stops "keeps timing out / can't load the site" on bad 5G: a stale
        // pooled socket no longer 502s a request that a fresh one would serve.
        // Only idempotent methods are retried, so we never replay a POST body.
        val canRetry = method == "GET" || method == "HEAD" ||
            method == "OPTIONS" || method == "TRACE"
        var committed = false
        for (attempt in 0..1) {
            var upstream: Socket? = null
            val t0 = System.currentTimeMillis()
            try {
                upstream = acquireUpstream(host, port, forceFresh = attempt == 1, local = local)
                val upOut = BufferedOutputStream(upstream.getOutputStream(), upstreamBufSize)

                val sb = StringBuilder()
                sb.append("$method $path HTTP/1.1\r\n")
                for (h in headers) {
                    if (h.startsWith("Proxy-", true)) continue
                    if (h.startsWith("Connection:", true)) continue
                    if (h.startsWith("Host:", true)) continue
                    if (h.startsWith("Proxy-Connection:", true)) continue
                    sb.append(h).append("\r\n")
                }
                sb.append("Host: $host:$port\r\n")
                sb.append("Connection: keep-alive\r\n\r\n")
                upOut.write(sb.toString().toByteArray())
                upOut.flush()

                // pipe request body if present (Content-Length or chunked)
                val contentLength = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
                if (method == "POST" || method == "PUT" || method == "PATCH") {
                    if (contentLength != null) {
                        val body = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(body, read, contentLength - read)
                            if (n <= 0) break
                            read += n
                        }
                        upOut.write(body, 0, read)
                        upOut.flush()
                    } else {
                        pumpChunked(input, upOut)
                    }
                }
                onLog("HTTP $method $host:$port$path")

                val upIn = StreamReader(upstream.getInputStream())
                val statusLine = readLine(upIn) ?: throw IOException("no response from upstream")
                val respHeaders = readHeaders(upIn) ?: throw IOException("no response headers")
                val statusCode = statusLine.split(" ")[1]
                if (!local) reportRequest(host, port, method, statusCode, System.currentTimeMillis() - t0)

                val chunked = respHeaders.any {
                    it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true)
                }
                val respLength = respHeaders.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toLongOrNull()
                val upstreamClose = respHeaders.any {
                    it.startsWith("Connection:", true) && it.contains("close", true)
                }
                val upstreamKeepAlive = !upstreamClose && (chunked || respLength != null)
                // A response with neither Content-Length nor chunked encoding is
                // close-delimited: the body ends only when the server closes the
                // connection. If we advertised keep-alive to the client, the browser
                // would never see a response boundary and the page would hang - this
                // is exactly the "plain http site just hangs" case. Force the client
                // connection closed so the EOF reliably signals end-of-body.
                val closeDelimited = !chunked && respLength == null
                val clientKa = clientKeepAlive && !closeDelimited

                writeResponseHeaders(output, statusLine, respHeaders, clientKa)
                committed = true

                when {
                    chunked -> forwardChunkedResponse(upIn, output)
                    respLength != null -> pumpFixed(upIn, output, respLength)
                    else -> pump(upIn, output)
                }
                output.flush()

                if (upstreamKeepAlive && !upIn.hasRemaining()) {
                    releaseUpstream(host, port, upstream)
                    upstream = null
                }
                runCatching { upstream?.close() }
                return
            } catch (e: Exception) {
                runCatching { upstream?.close() }
                // Retry only for safe methods and only if nothing reached the client.
                if (attempt == 0 && canRetry && !committed) continue
                onLog("HTTP fail $host:$port: ${e.message}")
                if (!local) reportRequest(host, port, method, "fail", System.currentTimeMillis() - t0)
                if (!committed) writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
                return
            }
        }
    }

    private fun isValidCellular(n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Tracks request success/failure timestamps for the stale-egress watchdog
     * below. Previously also sampled per-request stats to telemetry; now it
     * only feeds the auto-heal detector. [upBytes]/download accounting lives
     * at the call site and is intentionally not shipped anywhere.
     */
    private fun reportRequest(host: String, port: Int, method: String, status: String, dms: Long) {
        val now = System.currentTimeMillis()
        val code = status.toIntOrNull() ?: -1
        val isFailure = status == "fail" || code >= 400
        if (isFailure) lastFailureMs.set(now) else lastSuccessMs.set(now)
    }

    /**
     * Closed CONNECT tunnel accounting hook (no-op; telemetry removed).
     * [upBytes] is device upload (client -> upstream), [dnBytes] download.
     */
    private fun reportTunnel(host: String, port: Int, dms: Long, upBytes: Long, dnBytes: Long, firstByteMs: Long) {
    }

    /**
     * Watches for a "dead egress" condition: the proxy is up (panel still
     * reachable) but outbound requests keep failing and none succeed. That means
     * the bound cellular network went stale, so we ask the service to restart the
     * proxy and re-bind a fresh cellular network. Idle sessions (no traffic at
     * all) are left alone - only failure-without-success triggers a restart.
     */
    private suspend fun runStaleWatchdog() {
        while (running.get()) {
            delay(60_000)
            val success = lastSuccessMs.get()
            val fail = lastFailureMs.get()
            if (success == 0L && fail == 0L) continue
            val now = System.currentTimeMillis()
            val noSuccessFor = if (success == 0L) Long.MAX_VALUE else now - success
            val recentFail = fail != 0L && (now - fail) < STALE_TIMEOUT_MS
            if (noSuccessFor > STALE_TIMEOUT_MS && recentFail) {
                if (autoRestartGuard.compareAndSet(false, true)) {
                    onLog("Auto-heal: no successful request for ${noSuccessFor / 1000}s but recent failures - restarting proxy")
                    onStaleDetected()
                }
            }
        }
    }

    private fun resolve(host: String, net: Network?): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && cached.second > now) return cached.first
        // Resolve on the same network the upstream socket is bound to. Using the
        // default resolver would query the WiFi Direct interface (no DNS/internet)
        // when the phone is the group owner, so every request would fail to resolve.
        // However, when no explicit egress Network is available (or it is stale),
        // fall back to the system default resolver so a host we DO have internet
        // for still resolves. The system default route points at the phone's real
        // internet path (cellular / station WiFi), never the WiFi-Direct interface,
        // which has no DNS server of its own - so this is safe.
        val future = dnsExecutor.submit<List<InetAddress>> {
            (if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host))
                ?.toList().orEmpty()
        }
        val addrs = try {
            future.get(dnsTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("DNS resolution timed out for $host on egress network $net", e)
        } catch (e: Exception) {
            throw IOException("DNS resolution failed for $host on egress network $net", e)
        }
        if (addrs.isEmpty()) throw IOException("DNS resolution failed for $host on egress network $net")
        // Try IPv4 first. Many mobile carriers have broken/blackholed IPv6 egress,
        // so connecting to the first (often IPv6) address hangs for the full
        // timeout and then caches the dead address. Prefer IPv4 to avoid that.
        val ordered = addrs.filter { it.address.size == 4 } + addrs.filter { it.address.size != 4 }
        dnsCache[host] = ordered to (now + dnsTtlMs)
        return ordered
    }

    /**
     * Opens an upstream TCP socket to [host]:[port] bound to [net] (if non-null).
     * Returns null on any DNS/connect/bind failure instead of throwing, so callers
     * can try the next candidate. When [net] is null the system default route is
     * used - this is the critical last-resort path that lets browsing work
     * whenever the phone itself has working internet, even if the
     * ConnectivityManager Network APIs transiently report no usable egress.
     */
    private fun dial(host: String, port: Int, net: Network?): Socket? {
        val addrs = try {
            resolve(host, net)
        } catch (e: Exception) {
            onLog("HTTP DNS failed for $host via $net : ${e.message}")
            return null
        }
        var lastErr: String? = null
        for (addr in addrs) {
            val up = Socket()
            try {
                net?.bindSocket(up)
                up.soTimeout = readTimeoutMs
                up.tcpNoDelay = true
                tuneSocket(up)
                up.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                return up
            } catch (e: Exception) {
                runCatching { up.close() }
                lastErr = e.message
            }
        }
        onLog("HTTP upstream connect failed: $host:$port -> $addrs via $net : $lastErr")
        return null
    }

    private fun acquireUpstream(host: String, port: Int, forceFresh: Boolean = false, local: Boolean = false): Socket {
        if (local) {
            // Self-host (control panel) request: connect over the LAN interface
            // that owns our own IP rather than the egress network, so the panel is
            // reachable even when the client reaches us through the proxy.
            val up = dial(host, port, lanNetwork())
            if (up == null) throw IOException("could not reach panel $host:$port")
            return up
        }
        if (!forceFresh) {
            val key = "$host:$port"
            val pool = connPool[key]
            var reused: Socket? = null
            if (pool != null) {
                synchronized(pool) {
                    val now = System.currentTimeMillis()
                    pool.removeAll { it.second + poolIdleMs < now || it.first.isClosed }
                    val entry = pool.removeLastOrNull()
                    if (entry != null) reused = entry.first
                }
            }
            if (reused != null && !reused.isClosed && reused.isConnected) {
                return reused
            }
        }
        val net = pickNet()
        var up = dial(host, port, net)
        if (up == null) {
            // The chosen egress network may have gone stale (very common while the
            // phone is the WiFi-Direct Group Owner for a long session). Re-pick a
            // live egress network once instead of failing on a dead binding.
            val fresh = cm.allNetworks.firstOrNull { isValidCellular(it) }
                ?: cm.allNetworks.firstOrNull { NetworkUtils.isValidEgress(cm, it) }
            if (fresh != null && fresh != net) {
                up = dial(host, port, fresh)
                if (up != null) {
                    cachedNet = fresh
                    cellularNetwork = fresh
                    lastGoodCellular = fresh
                    clearPool()
                    clearDns()
                }
            }
        }
        if (up == null) {
            // Last resort: connect over the system default route. This succeeds
            // whenever the phone itself has working internet, even if every
            // ConnectivityManager-reported Network is stale/unavailable. Without
            // this, a transient gap in egress reporting 502s every request to
            // sites like google.com while the device clearly has connectivity.
            onLog("HTTP egress via chosen network failed for $host:$port; falling back to system default route")
            up = dial(host, port, null)
        }
        if (up == null) throw IOException("could not reach $host:$port (no egress network)")
        return up
    }

    private fun releaseUpstream(host: String, port: Int, sock: Socket) {
        if (sock.isClosed) return
        val key = "$host:$port"
        val pool = connPool.getOrPut(key) { mutableListOf() }
        synchronized(pool) {
            val now = System.currentTimeMillis()
            pool.removeAll { it.second + poolIdleMs < now || it.first.isClosed }
            if (pool.size < poolMax) {
                pool.add(sock to now)
            } else {
                runCatching { sock.close() }
            }
        }
    }

    private fun clearPool() {
        // A network switch leaves the cached upstream network stale; drop it so
        // egress doesn't serve a dead binding for up to the 3s cache window.
        cachedNet = null
        cachedNetTime = 0L
        for ((_, list) in connPool) {
            synchronized(list) {
                for ((sock, _) in list) runCatching { sock.close() }
                list.clear()
            }
        }
    }

    private fun clearDns() {
        dnsCache.clear()
    }

    private fun writeResponseHeaders(
        output: OutputStream,
        statusLine: String,
        respHeaders: List<String>,
        keepAlive: Boolean
    ) {
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        for (h in respHeaders) {
            if (h.startsWith("Proxy-", true)) continue
            if (h.startsWith("Connection:", true)) continue
            if (h.startsWith("Proxy-Connection:", true)) continue
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Forward a chunked response verbatim (preserving chunk framing) to the client. */
    private fun forwardChunkedResponse(input: StreamReader, output: OutputStream) {
        while (running.get()) {
            val sizeLine = readLine(input) ?: return
            output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: return
                    output.write(trailer.toByteArray(Charsets.ISO_8859_1))
                    output.write(CRLF)
                    if (trailer.isEmpty()) break
                }
                return
            }
            val body = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(body, read, size - read)
                if (n <= 0) return
                read += n
            }
            output.write(body, 0, size)
            output.write(CRLF)
            output.flush()
            readLine(input) // consume trailing CRLF after chunk
        }
    }

    private fun pumpFixed(input: InputStream, output: OutputStream, length: Long) {
        val buf = ByteArray(131072)
        var remaining = length
        try {
            while (remaining > 0 && running.get()) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                output.write(buf, 0, n)
                remaining -= n
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        input: StreamReader,
        output: BufferedOutputStream,
        target: String
    ) {
        var upstream: Socket? = null
        val t0 = System.currentTimeMillis()
        val hostPort = target.split(":")
        val host = hostPort[0]
        val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
        tunnelCount.incrementAndGet()
        AppState.tcpTunnels.value = tunnelCount.get()
        try {
            val net = pickNet()
            var up = dial(host, port, net)
            if (up == null) {
                val fresh = cm.allNetworks.firstOrNull { isValidCellular(it) }
                    ?: cm.allNetworks.firstOrNull { NetworkUtils.isValidEgress(cm, it) }
                if (fresh != null && fresh != net) up = dial(host, port, fresh)
            }
            if (up == null) up = dial(host, port, null)
            if (up == null) throw IOException("could not establish CONNECT tunnel to $host:$port")
            up.tcpNoDelay = true
            up.soTimeout = tunnelIdleTimeoutMs
            upstream = up
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            onLog("CONNECT $host:$port")
            reportRequest(host, port, "CONNECT", "200", System.currentTimeMillis() - t0)
            val openAt = System.currentTimeMillis()
            val firstByteMs = AtomicLong(-1L)
            val upBytes = AtomicLong(0L)
            val dnBytes = AtomicLong(0L)
            coroutineScope {
                // toServer: client -> upstream = the device's UPLOAD (bytes sent).
                // toClient: upstream -> client = the device's DOWNLOAD (bytes received).
                // NOTE: up_bytes must mean upload (device->server) and dn_bytes
                // download (server->device), matching how the collector labels them.
                val toServer = async {
                    pump(input, BufferedOutputStream(up.getOutputStream(), upstreamBufSize)).also { upBytes.set(it) }
                }
                val toClient = async {
                    val timed = FirstByteTimer(up.getInputStream()) {
                        firstByteMs.compareAndSet(-1L, System.currentTimeMillis() - openAt)
                    }
                    pump(StreamReader(timed), output).also { dnBytes.set(it) }
                }
                toServer.await()
                toClient.await()
            }
            reportTunnel(host, port, System.currentTimeMillis() - openAt, upBytes.get(), dnBytes.get(), firstByteMs.get())
        } catch (e: Exception) {
            onLog("CONNECT fail $target: ${e.message}")
            reportRequest(host, port, "CONNECT", "fail", System.currentTimeMillis() - t0)
            writeSimpleResponse(output, 502, "Bad Gateway - ${e.message}")
        } finally {
            runCatching { upstream?.close() }
            val left = tunnelCount.decrementAndGet()
            AppState.tcpTunnels.value = if (left < 0) 0 else left
        }
    }

    private fun parseAbsoluteUri(target: String, headers: List<String>): Triple<String?, Int, String> {
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(':')?.trim()
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val scheme = if (target.startsWith("https://", true)) "https" else "http"
            val rest = target.substringAfter("://")
            val slashIdx = rest.indexOf('/')
            val hostPart = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
            val path = if (slashIdx >= 0) rest.substring(slashIdx) else "/"
            val defaultPort = if (scheme == "https") 443 else 80
            val (h, p) = splitHostPort(hostPart, defaultPort)
            return Triple(h, p, path)
        }
        if (hostHeader != null) {
            val (h, p) = splitHostPort(hostHeader, 80)
            return Triple(h, p, target)
        }
        return Triple(null, 0, target)
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        val idx = s.lastIndexOf(':')
        return if (idx > 0) {
            s.substring(0, idx) to (s.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else {
            s to defaultPort
        }
    }

    private fun readLine(reader: StreamReader): String? = reader.readLine()

    private fun readHeaders(reader: StreamReader): List<String>? = reader.readHeaders()

    private fun pumpChunked(input: StreamReader, dst: OutputStream) {
        try {
            while (running.get()) {
                val sizeLine = readLine(input) ?: return
                val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
                dst.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
                dst.write(CRLF)
                if (size == 0) {
                    // trailers
                    while (true) {
                        val l = readLine(input) ?: return
                        dst.write(l.toByteArray(Charsets.ISO_8859_1))
                        dst.write(CRLF)
                        if (l.isEmpty()) return
                    }
                }
                val body = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(body, read, size - read)
                    if (n <= 0) return
                    read += n
                }
                dst.write(body, 0, size)
                dst.write(CRLF)
                dst.flush()
                readLine(input) // CRLF after chunk
            }
        } catch (_: Exception) {
        }
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, text: String) {
        runCatching {
            output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private suspend fun pump(src: InputStream, dst: OutputStream): Long {
        val buf = PUMP_BUF.get()
        var total = 0L
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n)
                total += n
                if (n < buf.size) dst.flush()
            }
        } catch (_: Exception) {
        }
        return total
    }

    // ---- Local control panel (served when a browser hits the proxy directly) ----

    private val selfHosts = setOf(goIp.lowercase(), "127.0.0.1", "localhost", "[::1]")

    /**
     * True for requests aimed at one of our own addresses (the old panel URL):
     * - origin-form (e.g. `GET /`), which only happens when a browser connects
     *   directly to us, or
     * - absolute-form whose authority is one of our own addresses.
     * These no longer serve the panel - they are redirected to the dedicated
     * PanelServer instead. Normal proxy traffic to other hosts is forwarded.
     */
    private fun isSelfHostRequest(method: String, target: String, headers: List<String>): Boolean {
        if (method == "CONNECT") return false
        if (target.startsWith("/")) return true
        val authority = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://([^/?#]+)""")
            .find(target)?.groupValues?.get(1)?.substringBefore('@')?.lowercase() ?: return false
        return authority.substringBefore(':') in selfHosts
    }

    /**
     * Returns the Network whose link owns [goIp] (the WiFi-Direct LAN), used to
     * reach the local control panel without going through the cellular egress.
     */
    private fun lanNetwork(): Network? {
        for (n in cm.allNetworks) {
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress == goIp }) return n
        }
        return null
    }

    companion object {
        // Reused across pump() calls on the same worker thread, so dozens of
        // concurrent tunnels don't each allocate a fresh buffer (GC churn).
        // 128KB halves the number of read/write syscalls per MB vs 64KB, which
        // matters when many heavy pages stream simultaneously.
        private val PUMP_BUF = ThreadLocal.withInitial { ByteArray(131072) }
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

/** InputStream wrapper that fires [onFirstByte] the first time data is read. */
private class FirstByteTimer(
    private val src: InputStream,
    private val onFirstByte: () -> Unit
) : InputStream() {
    private var reported = false

    override fun read(): Int {
        val b = src.read()
        if (b != -1 && !reported) {
            reported = true
            onFirstByte()
        }
        return b
    }

    override fun read(out: ByteArray, off: Int, len: Int): Int {
        val n = src.read(out, off, len)
        if (n > 0 && !reported) {
            reported = true
            onFirstByte()
        }
        return n
    }
}

/**
 * Buffered reader over an [InputStream] that reads lines in bulk instead of
 * one byte per [InputStream.read] call. Header/status/chunk-size lines used to
 * be parsed one byte at a time (20-40+ read calls per request) which murdered
 * throughput under many small requests. This drains 8KB chunks and scans for
 * the line terminator in-memory, and keeps the line buffer + bulk reads
 * consistent so callers can mix [readLine] with bulk [read] on the same stream.
 */
private class StreamReader(private val src: InputStream) : InputStream() {
    private val buf = ByteArray(8192)
    private var pos = 0
    private var end = 0
    private var lineBuf = ByteArray(256)

    override fun read(): Int {
        if (pos >= end && !fill()) return -1
        return buf[pos++].toInt() and 0xff
    }

    override fun read(out: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var copied = 0
        while (copied < len) {
            if (pos >= end) {
                if (!fill()) break
            }
            val avail = minOf(len - copied, end - pos)
            System.arraycopy(buf, pos, out, off + copied, avail)
            pos += avail
            copied += avail
            if (pos >= end) break
        }
        return if (copied == 0) -1 else copied
    }

    fun readLine(): String? {
        var len = 0
        while (true) {
            if (pos >= end && !fill()) return null
            while (pos < end) {
                val b = buf[pos++]
                if (b == '\n'.toByte()) {
                    return String(lineBuf, 0, len, Charsets.ISO_8859_1)
                }
                if (b != '\r'.toByte()) {
                    if (len >= lineBuf.size) growLine(len)
                    lineBuf[len++] = b
                }
            }
        }
    }

    fun readHeaders(): List<String>? {
        val headers = mutableListOf<String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) return headers
            headers.add(line)
        }
    }

    /** True when this reader holds bytes that were pulled past the logical end of a response. */
    fun hasRemaining(): Boolean = pos < end

    private fun growLine(len: Int) {
        val newBuf = ByteArray(maxOf(lineBuf.size * 2, len + 1))
        System.arraycopy(lineBuf, 0, newBuf, 0, len)
        lineBuf = newBuf
    }

    private fun fill(): Boolean {
        end = src.read(buf, 0, buf.size)
        pos = 0
        return end > 0
    }
}