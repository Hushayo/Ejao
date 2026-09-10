package com.sacram.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS4 / SOCKS4a backward-compatibility server (TCP CONNECT only).
 *
 * SOCKS4 has no UDP ASSOCIATE and no auth negotiation, so this server only
 * handles CONNECT. SOCKS4a (hostnames) is supported: when the client sends a
 * destination IP of 0.0.0.x (x != 0) the hostname follows the userid in the
 * request. SOCKS4 BIND is not supported and is rejected with status 0x5B.
 *
 * Egress DNS resolution and upstream socket binding use the exact same cellular
 * selection logic as [Socks5Server] so SOCKS4 clients route over the phone's
 * data connection just like SOCKS5 clients.
 */
class Socks4Server(
    private val port: Int,
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    private val workerExecutor = Executors.newCachedThreadPool()
    private val tcpSem = Semaphore(512)
    private val proxyDispatcher = workerExecutor.asCoroutineDispatcher()
    private val DNS_POOL_SIZE = 16
    private val dnsExecutor = Executors.newFixedThreadPool(DNS_POOL_SIZE)
    private val dnsTimeoutMs = 5_000
    private val scope = CoroutineScope(SupervisorJob() + proxyDispatcher)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    private var cellularNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // Match Socks5Server buffer sizing for the same high-latency cellular egress.
    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024
    private val tunnelIdleTimeoutMs = 100_000
    private val tunnelCount = AtomicInteger(0)

    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val dnsTtlMs = 60_000L
    private var cachedNet: Network? = null
    private var cachedNetTime = 0L

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun bindToCellular() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                onLog("SOCKS4 bound to cellular network: $network")
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
            }
        }
        netCallback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            onLog("WARNING [SOCKS4]: cellular request failed (${e.message}) - using default route")
            netCallback = null
        }
        scope.launch {
            kotlinx.coroutines.delay(3000)
            if (cellularNetwork == null) {
                onLog("WARNING [SOCKS4]: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    private fun tuneSocket(sock: Socket) {
        runCatching { sock.setReceiveBufferSize(socketRcvBuf) }
        runCatching { sock.setSendBufferSize(socketSndBuf) }
    }

    fun start() {
        running.set(true)
        bindToCellular()
        tcpJob = scope.launch { runTcpServer() }
        onLog("SOCKS4 listening tcp on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
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

    private suspend fun runTcpServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                scope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("SOCKS4 TCP server error: $e")
        }
    }

    private suspend fun handleClient(client: Socket) {
        val clientIp = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: ""
        val meteredIn = CountingInputStream(client.getInputStream())
        val meteredOut = CountingOutputStream(client.getOutputStream())
        try {
            client.soTimeout = 300000
            val input = DataInputStream(meteredIn)
            val output = DataOutputStream(meteredOut)

            val version = input.readUnsignedByte()
            if (version != 0x04) {
                // Not a SOCKS4 client; we have nothing to say on this protocol,
                // just close. (SOCKS5 clients hit Socks5Server on its own port.)
                client.close(); return
            }
            val cmd = input.readUnsignedByte()
            val targetPort = input.readUnsignedShort()
            // SOCKS4 destination IP (4 bytes, network order)
            val ip = ByteArray(4)
            input.readFully(ip)
            // SOCKS4a: IP is 0.0.0.x (x != 0) and the hostname follows the userid.
            val isSocks4a = (ip[0].toInt() and 0xff == 0) &&
                (ip[1].toInt() and 0xff == 0) &&
                (ip[2].toInt() and 0xff == 0) &&
                (ip[3].toInt() and 0xff != 0)

            // userid: null-terminated string (may be empty).
            val userid = readNullTerminatedString(input)

            val target: String
            if (isSocks4a) {
                // Hostname follows the userid as another null-terminated string.
                val host = readNullTerminatedString(input).trim()
                if (host.isEmpty()) {
                    reply(output, 0x5B); client.close(); return
                }
                target = host
            } else {
                target = "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}." +
                    "${ip[2].toInt() and 0xff}.${ip[3].toInt() and 0xff}"
            }

            when (cmd) {
                0x01 -> handleConnect(client, input, output, target, targetPort, isSocks4a)
                else -> {
                    // Only CONNECT is supported. BIND (0x02) -> rejected.
                    reply(output, 0x5B); client.close()
                }
            }
            // userid is unused for egress but kept for protocol completeness.
            if (userid.isEmpty()) Unit
        } catch (_: Exception) {
            runCatching { client.close() }
        } finally {
            ClientUsage.add(clientIp, meteredIn.bytes() + meteredOut.bytes())
        }
    }

    /** Reads a single NUL-terminated string from the stream. */
    private fun readNullTerminatedString(input: DataInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            out.write(b)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun resolve(host: String, net: Network?): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && cached.second > now) return cached.first
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
        val ordered = addrs.filter { it.address.size == 4 } + addrs.filter { it.address.size != 4 }
        dnsCache[host] = ordered to (now + dnsTtlMs)
        return ordered
    }

    private fun pickNet(): Network? {
        val now = System.currentTimeMillis()
        val active = cm.activeNetwork
        if (NetworkUtils.isValidEgress(cm, active)) {
            if (cachedNet != active) { cachedNet = active; cachedNetTime = now }
            return active
        }
        val cached = cachedNet
        if (cached != null && now - cachedNetTime < 8000 && NetworkUtils.isValidEgress(cm, cached)) {
            return cached
        }
        val n = NetworkUtils.pickCellular(cm, null) ?: cached
        cachedNet = n
        cachedNetTime = now
        return n
    }

    private suspend fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        target: String,
        targetPort: Int,
        isSocks4a: Boolean
    ) {
        tcpSem.withPermit {
            var upstream: Socket? = null
            tunnelCount.incrementAndGet()
            AppState.tcpTunnels.value = tunnelCount.get()
            val t0 = System.currentTimeMillis()
            try {
                val net = pickNet()
                val addrs = withContext(proxyDispatcher) { resolve(target, net) }
                var up: Socket? = null
                var lastErr: String? = null
                for (a in addrs) {
                    val sock = Socket()
                    try {
                        net?.bindSocket(sock)
                        sock.connect(InetSocketAddress(a, targetPort), 8000)
                        sock.tcpNoDelay = true
                        sock.soTimeout = tunnelIdleTimeoutMs
                        tuneSocket(sock)
                        up = sock
                        break
                    } catch (e: Exception) {
                        runCatching { sock.close() }
                        lastErr = e.message
                    }
                }
                if (up == null) throw IOException("could not connect to $target:$targetPort via $net : $lastErr")
                upstream = up
                reply(output, 0x5A) // granted
                onLog("SOCKS4 TCP ${if (isSocks4a) "[4a]" else ""} $target:$targetPort")
                val tx = AtomicLong(0L)
                val rx = AtomicLong(0L)
                val jobIn = scope.launch {
                    try { tx.set(pump(input, up.getOutputStream())) } finally { runCatching { up.shutdownOutput() } }
                }
                val jobOut = scope.launch {
                    try { rx.set(pump(up.getInputStream(), output)) } finally { runCatching { client.shutdownOutput() } }
                }
                jobIn.join(); jobOut.join()
                reportTunnel(target, targetPort, System.currentTimeMillis() - t0, tx.get(), rx.get())
            } catch (e: Exception) {
                onLog("SOCKS4 TCP fail $target:$targetPort: ${e.message}")
                runCatching { reply(output, 0x5B) }
            } finally {
                runCatching { client.close() }
                runCatching { upstream?.close() }
                val left = tunnelCount.decrementAndGet()
                AppState.tcpTunnels.value = if (left < 0) 0 else left
            }
        }
    }

    /**
     * SOCKS4 reply: byte 0 = 0x00 (version null), byte 1 = status,
     * bytes 2-3 = port (ignored, 0), bytes 4-7 = IP (ignored, 0).
     */
    private fun reply(output: DataOutputStream, status: Int) {
        runCatching {
            output.writeByte(0x00)
            output.writeByte(status)
            output.writeShort(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(0)
            output.flush()
        }
    }

    /** Closed SOCKS4 tunnel accounting hook (no-op; telemetry removed). */
    private fun reportTunnel(target: String, targetPort: Int, dms: Long, tx: Long, rx: Long) {
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

    companion object {
        private val PUMP_BUF = ThreadLocal.withInitial { ByteArray(131072) }
    }
}
