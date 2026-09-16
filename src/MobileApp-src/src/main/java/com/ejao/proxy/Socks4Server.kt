package com.ejao.proxy

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

class Socks4Server(
    private val port: Int,
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    private val workerExecutor = Executors.newCachedThreadPool()
    private val tcpSem = Semaphore(512)
    private val proxyDispatcher = workerExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + proxyDispatcher)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    @Volatile private var cellularNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024
    private val tunnelIdleTimeoutMs = 100_000
    private val tunnelCount = AtomicInteger(0)

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
        EgressManager.init(context)
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
                client.close(); return
            }
            val cmd = input.readUnsignedByte()
            val targetPort = input.readUnsignedShort()
            val ip = ByteArray(4)
            input.readFully(ip)
            val isSocks4a = (ip[0].toInt() and 0xff == 0) &&
                (ip[1].toInt() and 0xff == 0) &&
                (ip[2].toInt() and 0xff == 0) &&
                (ip[3].toInt() and 0xff != 0)

            val userid = readNullTerminatedString(input)

            val target: String
            if (isSocks4a) {
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
                0x01 -> handleConnect(client, input, output, target, targetPort, isSocks4a, clientIp)
                else -> {
                    reply(output, 0x5B); client.close()
                }
            }
            if (userid.isEmpty()) Unit
        } catch (_: Exception) {
            runCatching { client.close() }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readNullTerminatedString(input: DataInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            out.write(b)
            if (out.size() > 256) throw IOException("SOCKS4 string too long")
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun resolve(host: String, net: Network?): List<InetAddress> {
        return EgressManager.resolve(host, net)
    }

    private fun pickNet(host: String? = null): Network? = EgressManager.pickNet(cm, host)

    private suspend fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        target: String,
        targetPort: Int,
        isSocks4a: Boolean,
        clientIp: String
    ) {
        tcpSem.withPermit {
            var upstream: Socket? = null
            tunnelCount.incrementAndGet()
            AppState.tcpTunnels.value = tunnelCount.get()
            val t0 = System.currentTimeMillis()
            try {
                val net = pickNet(target)
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
                if (up == null) {
                    val fresh = NetworkUtils.pickCellular(cm, null)
                    if (fresh != null && fresh != net) {
                        val freshAddrs = runCatching { withContext(proxyDispatcher) { resolve(target, fresh) } }.getOrNull().orEmpty()
                        for (a in freshAddrs) {
                            val sock = Socket()
                            try {
                                fresh.bindSocket(sock)
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
                    }
                }
                if (up == null) {
                    val defAddrs = runCatching { withContext(proxyDispatcher) { resolve(target, null) } }.getOrNull().orEmpty()
                    for (a in defAddrs) {
                        val sock = Socket()
                        try {
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
                }
                if (up == null) throw IOException("could not connect to $target:$targetPort via $net : $lastErr")
                upstream = up
                EgressManager.reportSuccess(target)
                reply(output, 0x5A) // granted
                onLog("SOCKS4 TCP ${if (isSocks4a) "[4a]" else ""} $target:$targetPort")
                val tx = AtomicLong(0L)
                val rx = AtomicLong(0L)
                val jobIn = scope.launch {
                    try { tx.set(pump(input, up.getOutputStream()) { n ->
                        TrafficStats.addTx(n.toLong())
                        ClientUsage.add(clientIp, n.toLong())
                    }) } finally { runCatching { up.shutdownOutput() } }
                }
                val jobOut = scope.launch {
                    try { rx.set(pump(up.getInputStream(), output) { n ->
                        TrafficStats.addRx(n.toLong())
                        ClientUsage.add(clientIp, n.toLong())
                    }) } finally { runCatching { client.shutdownOutput() } }
                }
                jobIn.join(); jobOut.join()
                reportTunnel(target, targetPort, System.currentTimeMillis() - t0, tx.get(), rx.get())
            } catch (e: Exception) {
                EgressManager.reportFailure(target)
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

    private fun reply(output: DataOutputStream, status: Int) {
        runCatching {
            output.writeByte(0x00)
            output.writeByte(status)
            output.writeShort(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(0)
            output.flush()
        }
    }

    private fun reportTunnel(target: String, targetPort: Int, dms: Long, tx: Long, rx: Long) {
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

    companion object {
        private val PUMP_BUF = ThreadLocal.withInitial { ByteArray(131072) }
    }
}
