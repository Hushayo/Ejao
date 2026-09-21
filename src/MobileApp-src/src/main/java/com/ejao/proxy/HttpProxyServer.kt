package com.ejao.proxy

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

class HttpProxyServer(
    private val port: Int,
    private val context: Context,
    private val goIp: String = "192.168.49.1",
    private val panelPort: Int = -1,
    private val onLog: (String) -> Unit = {},
    private val onStaleDetected: () -> Unit = {}
) {

    private val workerExecutor = Executors.newCachedThreadPool()
    private val concurrencySem = Semaphore(512)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    private var cellularNetwork: Network? = null
    private var lastGoodCellular: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var cachedNet: Network? = null

    private fun pickNet(host: String? = null): Network? {
        val n = EgressManager.pickNet(cm, host)
        if (n != null) cachedNet = n
        return n ?: cachedNet
    }

    private val connPool = ConcurrentHashMap<String, MutableList<Pair<Socket, Long>>>()
    private val lastSuccessMs = AtomicLong(0L)
    private val lastFailureMs = AtomicLong(0L)
    private val autoRestartGuard = AtomicBoolean(false)
    private var staleWatchdogJob: Job? = null
    private val STALE_TIMEOUT_MS = 2 * 60_000L
    private val poolMax = 48
    private val poolIdleMs = 60_000L
    private val connectTimeoutMs = 6_000
    private val readTimeoutMs = 20_000
    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024
    private val clientBufSize = 64 * 1024
    private val upstreamBufSize = 64 * 1024
    private val tunnelIdleTimeoutMs = 100_000
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
                val alt = cm.allNetworks.firstOrNull { isValidCellular(it) }
                if (alt != null && alt != network) {
                    cellularNetwork = alt
                    lastGoodCellular = alt
                    clearPool()
                    clearDns()
                    onLog("Cellular network switched: $network -> $alt")
                } else {
                    cellularNetwork = null
                    lastGoodCellular = null
                    clearPool()
                    clearDns()
                    onLog("WARNING: cellular network $network lost; egress will re-scan for a live cellular network")
                }
            }
        }
        netCallback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            onLog("WARNING: cellular request failed (${e.message}) - using default route")
            netCallback = null
        }
        scope.launch {
            delay(3000)
            if (cellularNetwork == null && lastGoodCellular == null) {
                onLog("WARNING: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    fun start() {
        running.set(true)
        EgressManager.init(context)
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
                    handleConnect(client, reader, output, target, clientIp)
                    break
                }

                if (isSelfHostRequest(method, target, headers)) {
                    val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                    val (_, _, selfPath) = parseAbsoluteUri(target, headers)
                    forwardPlain(reader, output, method, goIp, panelPort, selfPath.ifEmpty { "/" }, headers, keepAlive, local = true, clientIp = clientIp)
                    if (!keepAlive) break
                    continue
                }

                val (host, port, path) = parseAbsoluteUri(target, headers)
                if (host == null) {
                    writeSimpleResponse(output, 400, "Bad Request - absolute URI required")
                    break
                }
                val keepAlive = headers.any { it.startsWith("Connection:", true) && it.contains("keep-alive", true) }
                forwardPlain(reader, output, method, host, port, path, headers, keepAlive, clientIp = clientIp)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
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
        local: Boolean = false,
        clientIp: String = ""
    ) {
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
                if (!local) {
                    val hlen = sb.length.toLong()
                    TrafficStats.addTx(hlen)
                    ClientUsage.add(clientIp, hlen)
                }

                val contentLength = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
                if (method == "POST" || method == "PUT" || method == "PATCH") {
                    if (contentLength != null) {
                        if (contentLength > 8 * 1024 * 1024 || contentLength < 0) {
                            writeSimpleResponse(output, 413, "Payload Too Large")
                            return
                        }
                        val body = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(body, read, contentLength - read)
                            if (n <= 0) break
                            read += n
                        }
                        upOut.write(body, 0, read)
                        upOut.flush()
                        if (!local) {
                            TrafficStats.addTx(read.toLong())
                            ClientUsage.add(clientIp, read.toLong())
                        }
                    } else {
                        pumpChunked(input, upOut) { n ->
                            if (!local) {
                                TrafficStats.addTx(n.toLong())
                                ClientUsage.add(clientIp, n.toLong())
                            }
                        }
                    }
                }
                if (!local) onLog("HTTP $method $host:$port$path")

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
                val closeDelimited = !chunked && respLength == null
                val clientKa = clientKeepAlive && !closeDelimited

                writeResponseHeaders(output, statusLine, respHeaders, clientKa)
                committed = true

                when {
                    chunked -> forwardChunkedResponse(upIn, output) { n ->
                        if (!local) {
                            TrafficStats.addRx(n.toLong())
                            ClientUsage.add(clientIp, n.toLong())
                        }
                    }
                    respLength != null -> pumpFixed(upIn, output, respLength) { n ->
                        if (!local) {
                            TrafficStats.addRx(n.toLong())
                            ClientUsage.add(clientIp, n.toLong())
                        }
                    }
                    else -> pump(upIn, output) { n ->
                        if (!local) {
                            TrafficStats.addRx(n.toLong())
                            ClientUsage.add(clientIp, n.toLong())
                        }
                    }
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
                if (!local) evictHost(host, port)
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

    private fun reportRequest(host: String, port: Int, method: String, status: String, dms: Long) {
        val now = System.currentTimeMillis()
        val code = status.toIntOrNull() ?: -1
        val isFailure = status == "fail" || code >= 400
        if (isFailure) {
            lastFailureMs.set(now)
            EgressManager.reportFailure(host)
        } else {
            lastSuccessMs.set(now)
            EgressManager.reportSuccess(host)
        }
    }

    private fun reportTunnel(host: String, port: Int, dms: Long, upBytes: Long, dnBytes: Long, firstByteMs: Long) {
    }

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
        return EgressManager.resolve(host, net)
    }

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
                    // Drop idle-expired, closed, or half-shutdown sockets: after
                    // hours the carrier/NAT silently kills idle upstreams and
                    // the next GET would fail once on a dead reuse before the
                    // retry. Stricter check here avoids that one failed hit.
                    pool.removeAll {
                        it.second + poolIdleMs < now || it.first.isClosed ||
                            !it.first.isConnected || it.first.isInputShutdown ||
                            it.first.isOutputShutdown
                    }
                    val entry = pool.removeLastOrNull()
                    if (entry != null) reused = entry.first
                }
            }
            if (reused != null && !reused.isClosed && reused.isConnected &&
                !reused.isInputShutdown && !reused.isOutputShutdown
            ) {
                return reused
            } else if (reused != null) {
                runCatching { reused.close() }
            }
        }
        val net = pickNet(host)
        var up = dial(host, port, net)
        if (up == null) {
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
        cachedNet = null
        EgressManager.invalidateCache()
        for ((_, list) in connPool) {
            synchronized(list) {
                for ((sock, _) in list) runCatching { sock.close() }
                list.clear()
            }
        }
    }

    private fun clearDns() {
        EgressManager.clearDns()
    }

    // Drop all pooled sockets for one host after it fails: keeps one dead
    // origin (e.g. after a cellular handover) from poisoning the next
    // requests while leaving other hosts' warm connections intact.
    private fun evictHost(host: String, port: Int) {
        runCatching {
            connPool["$host:$port"]?.let { list ->
                synchronized(list) {
                    for ((sock, _) in list) runCatching { sock.close() }
                    list.clear()
                }
            }
        }
        EgressManager.clearDnsHost(host)
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

    private fun forwardChunkedResponse(input: StreamReader, output: OutputStream, onBytes: ((Int) -> Unit)? = null) {
        while (running.get()) {
            val sizeLine = readLine(input) ?: return
            output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
            if (size < 0 || size > 8 * 1024 * 1024) return
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
            onBytes?.invoke(size)
            readLine(input)
        }
    }

    private fun pumpFixed(input: InputStream, output: OutputStream, length: Long, onBytes: ((Int) -> Unit)? = null) {
        val buf = ByteArray(131072)
        var remaining = length
        try {
            while (remaining > 0 && running.get()) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                output.write(buf, 0, n)
                remaining -= n
                onBytes?.invoke(n)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun handleConnect(
        client: Socket,
        input: StreamReader,
        output: BufferedOutputStream,
        target: String,
        clientIp: String = ""
    ) {
        var upstream: Socket? = null
        val t0 = System.currentTimeMillis()
        val (host, port) = parseConnectTarget(target)
        tunnelCount.incrementAndGet()
        AppState.tcpTunnels.value = tunnelCount.get()
        try {
            val net = pickNet(host)
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
                val toServer = async {
                    pump(input, BufferedOutputStream(up.getOutputStream(), upstreamBufSize)) { n ->
                        TrafficStats.addTx(n.toLong())
                        ClientUsage.add(clientIp, n.toLong())
                    }.also { upBytes.set(it) }
                }
                val toClient = async {
                    val timed = FirstByteTimer(up.getInputStream()) {
                        firstByteMs.compareAndSet(-1L, System.currentTimeMillis() - openAt)
                    }
                    pump(StreamReader(timed), output) { n ->
                        TrafficStats.addRx(n.toLong())
                        ClientUsage.add(clientIp, n.toLong())
                    }.also { dnBytes.set(it) }
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

    /**
     * Bracket-aware CONNECT authority parse. The old `split(":")` broke IPv6
     * targets (`[2603:...]:443` parsed as host=`[2603`, port=1020) so every
     * IPv6 CONNECT 502'd. Forms handled: `host:port`, `host`, `[v6]:port`,
     * `[v6]`, and bare `v6` (all-colon literal, default port).
     */
    private fun parseConnectTarget(target: String): Pair<String, Int> {
        val t = target.trim()
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close > 0) {
                val host = t.substring(1, close)
                val port = t.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
                return host to port
            }
            return t to 443
        }
        // Bare IPv6 literal (more than one colon, no brackets): the whole
        // thing is the host, port is default. Splitting would shred it.
        if (t.count { it == ':' } > 1) return t to 443
        val idx = t.lastIndexOf(':')
        return if (idx > 0) {
            t.substring(0, idx) to (t.substring(idx + 1).toIntOrNull() ?: 443)
        } else {
            t to 443
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

    private fun pumpChunked(input: StreamReader, dst: OutputStream, onBytes: ((Int) -> Unit)? = null) {
        try {
            while (running.get()) {
                val sizeLine = readLine(input) ?: return
                val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: return
                dst.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
                dst.write(CRLF)
                if (size == 0) {
                    while (true) {
                        val l = readLine(input) ?: return
                        dst.write(l.toByteArray(Charsets.ISO_8859_1))
                        dst.write(CRLF)
                        if (l.isEmpty()) return
                    }
                }
                if (size < 0 || size > 8 * 1024 * 1024) return
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
                onBytes?.invoke(size)
                readLine(input)
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

    private suspend fun pump(src: InputStream, dst: OutputStream, onChunk: ((Int) -> Unit)? = null): Long {
        val buf = PUMP_BUF.get()
        var total = 0L
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n <= 0) break
                dst.write(buf, 0, n)
                total += n
                onChunk?.invoke(n)
                if (n < buf.size) dst.flush()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return total
    }


    private val selfHosts = setOf(goIp.lowercase(), "127.0.0.1", "localhost", "[::1]")

    private fun isSelfHostRequest(method: String, target: String, headers: List<String>): Boolean {
        if (method == "CONNECT") return false
        if (target.startsWith("/")) return true
        val authority = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://([^/?#]+)""")
            .find(target)?.groupValues?.get(1)?.substringBefore('@')?.lowercase() ?: return false
        return authority.substringBefore(':') in selfHosts
    }

    private fun lanNetwork(): Network? {
        for (n in cm.allNetworks) {
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress == goIp }) return n
        }
        return null
    }

    companion object {
        private val PUMP_BUF = ThreadLocal.withInitial { ByteArray(131072) }
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

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

private class StreamReader(private val src: InputStream) : InputStream() {
    private val buf = ByteArray(8192)
    private var pos = 0
    private var end = 0
    private var lineBuf = ByteArray(256)
    private val MAX_LINE = 32 * 1024

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

    fun hasRemaining(): Boolean = pos < end

    private fun growLine(len: Int) {
        if (lineBuf.size * 2 > MAX_LINE || len + 1 > MAX_LINE) throw IOException("header too large")
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
