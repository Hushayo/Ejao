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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
    private val concurrencySem = Semaphore(128)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val running = AtomicBoolean(true)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
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
    private val poolMaxPerHost = 8
    private val poolMaxTotal = 64
    // Longer than the old 60s: browsers reuse origins across user think time,
    // and a warm pooled socket skips dial + TLS setup entirely.
    private val poolIdleMs = 120_000L
    private val readTimeoutMs = 20_000
    private val clientRcvBuf = 1 * 1024 * 1024
    private val clientSndBuf = 1 * 1024 * 1024
    private val upstreamRcvBuf = 4 * 1024 * 1024
    private val upstreamSndBuf = 1 * 1024 * 1024
    private val clientBufSize = 64 * 1024
    private val upstreamBufSize = 64 * 1024
    private val tunnelCount = AtomicInteger(0)

    // Panel-only mode for proxy Idle: serve self-host (panel) requests,
    // 503 everything else. Lets browsers that reach the panel THROUGH the
    // proxy keep loading it while internet is paused.
    private val idleMode = AtomicBoolean(false)

    fun setIdleMode(v: Boolean) {
        idleMode.set(v)
        if (v) {
            // Freeze the stale-egress watchdog: local panel hits don't report,
            // so without a reset the pre-idle counters would age into a
            // phantom "no success" trip and auto-restart out of idle.
            val now = System.currentTimeMillis()
            lastSuccessMs.set(now)
            lastFailureMs.set(now)
        }
    }

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
                EgressManager.noteNetwork(network)
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
        clients.forEach { runCatching { it.close() } }
        clients.clear()
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
        runCatching { sock.setReceiveBufferSize(clientRcvBuf) }
        runCatching { sock.setSendBufferSize(clientSndBuf) }
    }

    private suspend fun runServer() {
        var fails = 0
        while (running.get()) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                runCatching { ss.setReceiveBufferSize(clientRcvBuf) }
                ss.bind(InetSocketAddress(goIp, port), 1024)
                serverSocket = ss
                fails = 0
                while (running.get()) {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    tuneSocket(client)
                    client.soTimeout = 30_000
                    if (!concurrencySem.tryAcquire()) {
                        runCatching {
                            client.getOutputStream().write(
                                "HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.ISO_8859_1)
                            )
                            client.close()
                        }
                        continue
                    }
                    clients.add(client)
                    scope.launch {
                        try {
                            handleClient(client)
                        } finally {
                            clients.remove(client)
                            concurrencySem.release()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running.get()) return
                fails++
                onLog("HTTP listen failed: ${e.message} (retry $fails)")
            } finally {
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
            delay(minOf(5_000L, 250L * fails))
        }
    }

    private suspend fun handleClient(client: Socket) {
        val clientIp = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: ""
        val meteredIn = CountingInputStream(client.getInputStream())
        val meteredOut = CountingOutputStream(client.getOutputStream())
        val reader = StreamReader(meteredIn)
        val output = BufferedOutputStream(meteredOut, clientBufSize)
        try {
            client.soTimeout = 30_000
            runCatching { client.keepAlive = true }
            while (running.get()) {
                val requestLine = readLine(reader) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size != 3 || parts.any { it.isEmpty() }) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    break
                }
                if (parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
                    writeSimpleResponse(output, 505, "HTTP Version Not Supported")
                    break
                }
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = readHeaders(reader) ?: break

                // Idle (panel-only) mode: the only thing served is the panel
                // itself; everything else gets a 503 with a resume pointer.
                // CONNECT never addresses the panel, so it always 503s here.
                if (idleMode.get() && !isSelfHostRequest(method, target, headers)) {
                    writeIdleResponse(output)
                    break
                }

                if (method == "CONNECT") {
                    handleConnect(client, reader, output, target, clientIp)
                    break
                }

                if (isSelfHostRequest(method, target, headers)) {
                    val keepAlive = requestKeepAlive(parts[2], headers)
                    val (_, _, selfPath) = parseAbsoluteUri(target, headers)
                    if (!forwardPlain(reader, output, method, goIp, panelPort, selfPath.ifEmpty { "/" }, headers, keepAlive, local = true, clientIp = clientIp)) break
                    continue
                }

                val (host, port, path) = parseAbsoluteUri(target, headers)
                if (host == null) {
                    writeSimpleResponse(output, 400, "Bad Request - absolute URI required")
                    break
                }
                if (port == panelPort && host.equals(goIp, true)) {
                    writeSimpleResponse(output, 403, "Forbidden")
                    break
                }
                val keepAlive = requestKeepAlive(parts[2], headers)
                if (!forwardPlain(reader, output, method, host, port, path, headers, keepAlive, clientIp = clientIp)) break
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
    ): Boolean {
        val transferCoding = headerValues(headers, "Transfer-Encoding")
            .flatMap { it.split(',') }.map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }
        if (transferCoding.isNotEmpty() && transferCoding != listOf("chunked")) {
            writeSimpleResponse(output, 501, "Transfer Encoding Not Supported")
            return false
        }
        val chunkedRequest = transferCoding == listOf("chunked")
        val lengths = headerValues(headers, "Content-Length")
            .flatMap { it.split(',') }.map { it.trim().toLongOrNull() }
        if (lengths.any { it == null || it < 0 } || lengths.distinct().size > 1 ||
            (chunkedRequest && lengths.isNotEmpty())
        ) {
            writeSimpleResponse(output, 400, "Bad Request Framing")
            return false
        }
        val contentLength = lengths.firstOrNull()
        val hasBody = chunkedRequest || (contentLength ?: 0L) > 0L
        val canRetry = !hasBody && (method == "GET" || method == "HEAD" ||
            method == "OPTIONS" || method == "TRACE")
        val hopHeaders = hopHeaders(headers)
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
                    if (h.substringBefore(':').trim().lowercase(Locale.US) in hopHeaders) continue
                    if (h.startsWith("Expect:", true)) continue
                    sb.append(h).append("\r\n")
                }
                val printableHost = if (':' in host) "[$host]" else host
                sb.append("Host: $printableHost").append(if (port == 80) "" else ":$port").append("\r\n")
                sb.append("Connection: keep-alive\r\n\r\n")
                upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                upOut.flush()
                if (!local) {
                    val hlen = sb.length.toLong()
                    TrafficStats.addTx(hlen)
                    ClientUsage.add(clientIp, hlen)
                }

                if (hasBody && headers.any {
                        it.startsWith("Expect:", true) && it.substringAfter(':').contains("100-continue", true)
                    }
                ) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                val countUpload: (Int) -> Unit = { n ->
                    if (!local) {
                        TrafficStats.addTx(n.toLong()); ClientUsage.add(clientIp, n.toLong())
                    }
                }
                when {
                    chunkedRequest -> HttpBody.copyChunked(input, upOut, { readLine(input) }, countUpload)
                    contentLength != null -> HttpBody.copyFixed(input, upOut, contentLength, flushEach = false, onBytes = countUpload)
                }
                upOut.flush()
                if (!local) onLog("HTTP $method $host:$port$path")

                val upIn = StreamReader(upstream.getInputStream())
                var statusLine: String
                var respHeaders: List<String>
                var statusCode: Int
                do {
                    statusLine = readLine(upIn) ?: throw IOException("no response from upstream")
                    respHeaders = readHeaders(upIn) ?: throw IOException("no response headers")
                    val statusParts = statusLine.split(' ', limit = 3)
                    statusCode = statusParts.getOrNull(1)?.toIntOrNull()
                        ?: throw IOException("bad upstream status")
                    if (statusParts[0] != "HTTP/1.1" && statusParts[0] != "HTTP/1.0") {
                        throw IOException("bad upstream HTTP version")
                    }
                    if (statusCode in 100..199) {
                        writeResponseHeaders(output, statusLine, respHeaders, clientKeepAlive)
                        committed = true
                    }
                } while (statusCode in 100..199 && statusCode != 101)
                if (!local) reportRequest(host, port, method, statusCode.toString(), System.currentTimeMillis() - t0)

                val chunked = respHeaders.any {
                    it.startsWith("Transfer-Encoding:", true) &&
                        it.substringAfter(':').split(',').last().trim().equals("chunked", true)
                }
                val responseCoding = headerValues(respHeaders, "Transfer-Encoding")
                    .flatMap { it.split(',') }.map { it.trim().lowercase(Locale.US) }
                    .filter { it.isNotEmpty() }
                if (responseCoding.isNotEmpty() && responseCoding.last() != "chunked") {
                    throw IOException("unsupported upstream transfer encoding")
                }
                val responseLengths = headerValues(respHeaders, "Content-Length")
                    .flatMap { it.split(',') }.map { it.trim().toLongOrNull() }
                if (responseLengths.any { it == null || it < 0 } || responseLengths.distinct().size > 1) {
                    throw IOException("bad upstream Content-Length")
                }
                val respLength = responseLengths.firstOrNull()
                val bodyless = method == "HEAD" || statusCode in 100..199 || statusCode == 204 || statusCode == 304
                val upstreamClose = respHeaders.any {
                    it.startsWith("Connection:", true) && headerTokens(it).contains("close")
                }
                val upstreamPersistent = statusLine.startsWith("HTTP/1.1") || respHeaders.any {
                    it.startsWith("Connection:", true) && headerTokens(it).contains("keep-alive")
                }
                // Do not pool HEAD/204/304: a buggy origin may still put bytes
                // on the wire, and the next request would parse them as status.
                val upstreamKeepAlive = !bodyless && statusCode != 101 && !upstreamClose &&
                    upstreamPersistent && (chunked || respLength != null)
                val closeDelimited = statusCode == 101 || (!bodyless && !chunked && respLength == null)
                val clientKa = clientKeepAlive && !closeDelimited

                if (statusCode !in 100..199) writeResponseHeaders(output, statusLine, respHeaders, clientKa)
                committed = true

                when {
                    bodyless -> Unit
                    chunked -> HttpBody.copyChunked(upIn, output, { readLine(upIn) }) { n ->
                        if (!local) {
                            TrafficStats.addRx(n.toLong())
                            ClientUsage.add(clientIp, n.toLong())
                        }
                    }
                    respLength != null -> HttpBody.copyFixed(upIn, output, respLength, flushEach = true) { n ->
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
                return clientKa
            } catch (e: Exception) {
                runCatching { upstream?.close() }
                if (e is kotlinx.coroutines.CancellationException || committed) throw e
                if (!local) evictHost(host, port)
                if (attempt == 0 && canRetry && !committed) continue
                onLog("HTTP fail $host:$port: ${e.message}")
                if (!local) reportRequest(host, port, method, "fail", System.currentTimeMillis() - t0)
                writeSimpleResponse(output, 502, "Bad Gateway")
                return false
            }
        }
        return false
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

    private fun dial(host: String, port: Int, net: Network?): Socket? {
        return try {
            val up = Dialer.connect(host, port, net, upstreamRcvBuf, upstreamSndBuf)
            up.soTimeout = readTimeoutMs
            up
        } catch (e: Exception) {
            onLog("HTTP upstream connect failed: $host:$port via $net : ${e.message}")
            null
        }
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
                    pool.removeAll { dropPooled(it, now) }
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
        val pool = connPool.computeIfAbsent(key) { mutableListOf() }
        val now = System.currentTimeMillis()
        synchronized(pool) {
            pool.removeAll { closeExpired(it, now) }
            if (pool.size < poolMaxPerHost) pool.add(sock to now) else runCatching { sock.close() }
        }
        if (connPool.values.sumOf { it.size } <= poolMaxTotal) return
        val oldest = connPool.entries.flatMap { entry ->
            synchronized(entry.value) { entry.value.map { entry.key to it } }
        }.minByOrNull { it.second.second } ?: return
        connPool[oldest.first]?.let { list ->
            synchronized(list) {
                if (list.remove(oldest.second)) runCatching { oldest.second.first.close() }
            }
        }
    }

    private fun closeExpired(entry: Pair<Socket, Long>, now: Long): Boolean {
        return dropPooled(entry, now, close = true)
    }

    private fun dropPooled(entry: Pair<Socket, Long>, now: Long, close: Boolean = true): Boolean {
        val ok = entry.second + poolIdleMs >= now && !entry.first.isClosed &&
            entry.first.isConnected && !entry.first.isInputShutdown && !entry.first.isOutputShutdown
        if (ok) return false
        if (close) runCatching { entry.first.close() }
        return true
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
        val hasTransferEncoding = respHeaders.any { it.startsWith("Transfer-Encoding:", true) }
        val hop = hopHeaders(respHeaders)
        for (h in respHeaders) {
            val name = h.substringBefore(':').trim().lowercase(Locale.US)
            if (name in hop || (hasTransferEncoding && name == "content-length")) continue
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
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
        tunnelCount.incrementAndGet()
        AppState.tcpTunnels.value = tunnelCount.get()
        var established = false
        try {
            val (host, port) = parseConnectTarget(target)
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
            up.soTimeout = 0
            up.keepAlive = true
            runCatching { client.soTimeout = 0 }
            upstream = up
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            established = true
            onLog("CONNECT $host:$port")
            reportRequest(host, port, "CONNECT", "200", System.currentTimeMillis() - t0)
            val openAt = System.currentTimeMillis()
            val firstByteMs = AtomicLong(-1L)
            val (upBytes, dnBytes) = TcpTunnel.relay(
                client, up, input, client.getOutputStream(), running::get,
                onUpload = { n ->
                    TrafficStats.addTx(n.toLong()); ClientUsage.add(clientIp, n.toLong())
                },
                onDownload = { n ->
                    firstByteMs.compareAndSet(-1L, System.currentTimeMillis() - openAt)
                    TrafficStats.addRx(n.toLong()); ClientUsage.add(clientIp, n.toLong())
                }
            )
            reportTunnel(host, port, System.currentTimeMillis() - openAt, upBytes, dnBytes, firstByteMs.get())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onLog("CONNECT fail $target: ${e.message}")
            if (!established) {
                reportRequest(target, 0, "CONNECT", "fail", System.currentTimeMillis() - t0)
                writeSimpleResponse(output, 502, "Bad Gateway")
            }
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
    private fun parseConnectTarget(target: String): Pair<String, Int> =
        splitHostPort(target.trim(), 443) ?: throw IOException("invalid CONNECT authority")

    private fun parseAbsoluteUri(target: String, headers: List<String>): Triple<String?, Int, String> {
        val hostHeader = headerValues(headers, "Host").singleOrNull()?.trim()
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val uri = runCatching { URI(target) }.getOrNull() ?: return Triple(null, 0, target)
            if (!uri.scheme.equals("http", true) || uri.userInfo != null || uri.rawFragment != null) {
                return Triple(null, 0, target)
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
            val port = if (uri.port == -1) 80 else uri.port
            if (host == null || port !in 1..65535) return Triple(null, 0, target)
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            return Triple(host, port, path)
        }
        if (hostHeader != null) {
            val (h, p) = splitHostPort(hostHeader, 80) ?: return Triple(null, 0, target)
            return Triple(h, p, if (target.startsWith("/") || target == "*") target else return Triple(null, 0, target))
        }
        return Triple(null, 0, target)
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int>? {
        val value = s.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it == '/' || it == '@' || it == '#' }) return null
        val host: String
        val port: Int
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close <= 1) return null
            host = value.substring(1, close)
            port = when {
                close == value.lastIndex -> defaultPort
                value[close + 1] == ':' -> value.substring(close + 2).toIntOrNull() ?: return null
                else -> return null
            }
        } else if (value.count { it == ':' } > 1) {
            return null // CONNECT/Host IPv6 literals must use brackets.
        } else {
            val idx = value.lastIndexOf(':')
            host = if (idx >= 0) value.substring(0, idx) else value
            port = if (idx >= 0) value.substring(idx + 1).toIntOrNull() ?: return null else defaultPort
        }
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    private fun readLine(reader: StreamReader): String? = reader.readLine()

    private fun readHeaders(reader: StreamReader): List<String>? = reader.readHeaders()

    private fun headerValues(headers: List<String>, name: String): List<String> = headers
        .filter { it.substringBefore(':').trim().equals(name, true) }
        .map { it.substringAfter(':').trim() }

    private fun headerTokens(header: String): Set<String> = header.substringAfter(':')
        .split(',').map { it.trim().lowercase(Locale.US) }.filterTo(mutableSetOf()) { it.isNotEmpty() }

    private fun hopHeaders(headers: List<String>): Set<String> {
        val names = mutableSetOf(
            "connection", "proxy-connection", "keep-alive", "te",
            "upgrade", "proxy-authenticate", "proxy-authorization", "host"
        )
        headers.filter { it.startsWith("Connection:", true) }.forEach { names += headerTokens(it) }
        return names
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, text: String) {
        runCatching {
            output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private fun writeIdleResponse(output: OutputStream) {
        runCatching {
            val body =
                "<html><body style=\"font-family:sans-serif\">" +
                    "<h1>Ejao is idle</h1>" +
                    "<p>Internet is paused. " +
                    "<a href=\"http://$goIp:$panelPort/\">Open the panel to resume</a>.</p>" +
                    "</body></html>"
            val bytes = body.toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 503 Paused\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(bytes)
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
                dst.flush()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return total
    }


    private val selfHosts = setOf(goIp.lowercase(), "127.0.0.1", "localhost", "::1")

    /**
     * Persistent-connection decision. HTTP/1.1 keeps connections alive by
     * default, and browsers signal proxy keep-alive with `Proxy-Connection:`
     * rather than `Connection:` - the old check ignored both, closing the
     * client socket after every response and paying a fresh local handshake
     * on each request.
     */
    private fun requestKeepAlive(version: String, headers: List<String>): Boolean {
        var close = false
        var keepAlive = false
        for (h in headers) {
            if (!h.startsWith("Connection:", true) && !h.startsWith("Proxy-Connection:", true)) continue
            val tokens = headerTokens(h)
            if ("close" in tokens) close = true
            if ("keep-alive" in tokens) keepAlive = true
        }
        if (close) return false
        if (keepAlive) return true
        return version.equals("HTTP/1.1", true)
    }

    private fun isSelfHostRequest(method: String, target: String, headers: List<String>): Boolean {
        if (method == "CONNECT") return false
        val (host, _, _) = parseAbsoluteUri(target, headers)
        return host?.lowercase(Locale.US) in selfHosts
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
                if (b == '\n'.code.toByte()) {
                    return String(lineBuf, 0, len, Charsets.ISO_8859_1)
                }
                if (b != '\r'.code.toByte()) {
                    if (len >= lineBuf.size) growLine(len)
                    lineBuf[len++] = b
                }
            }
        }
    }

    fun readHeaders(): List<String>? {
        val headers = mutableListOf<String>()
        var bytes = 0
        repeat(101) { n ->
            if (n == 100) throw IOException("too many headers")
            val line = readLine() ?: return null
            if (line.isEmpty()) return headers
            bytes += line.length + 2
            if (bytes > 64 * 1024) throw IOException("header block too large")
            if (line[0].isWhitespace() || ':' !in line) throw IOException("malformed header")
            headers.add(line)
        }
        return null
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
