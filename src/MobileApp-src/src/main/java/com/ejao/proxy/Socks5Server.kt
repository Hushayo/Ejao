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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
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

class Socks5Server(
    private val port: Int,
    private val advertiseIp: String,
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    private val workerExecutor = Executors.newCachedThreadPool()
    private val tcpSem = Semaphore(512)
    private val udpSem = Semaphore(1024)
    private val proxyDispatcher = workerExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + proxyDispatcher)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpJob: Job? = null
    private var udpJob: Job? = null
    private var udpSweepJob: Job? = null
    @Volatile private var cellularNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val maxUdpSessions = 1024

    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024

    private val tunnelIdleTimeoutMs = 100_000
    private val tunnelCount = AtomicInteger(0)

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun bindToCellular() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                onLog("Bound to cellular network: $network")
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
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
            if (cellularNetwork == null) {
                onLog("WARNING: no cellular network available - outbound sockets use the default route")
            }
        }
    }

    private fun tuneSocket(sock: Socket) {
        runCatching { sock.setReceiveBufferSize(socketRcvBuf) }
        runCatching { sock.setSendBufferSize(socketSndBuf) }
    }

    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private class UdpSession(val socket: DatagramSocket) {
        @Volatile var lastActivity = System.currentTimeMillis()
        val tx = AtomicLong(0L)
        val rx = AtomicLong(0L)
    }

    fun start() {
        running.set(true)
        EgressManager.init(context)
        bindToCellular()
        tcpJob = scope.launch { runTcpServer() }
        udpJob = scope.launch { runUdpServer() }
        udpSweepJob = scope.launch { runUdpSweep() }
        onLog("SOCKS5 listening tcp/udp on port $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        udpSessions.values.forEach { runCatching { it.socket.close() } }
        udpSessions.clear()
        netCallback?.let {
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        tcpJob?.cancel()
        udpJob?.cancel()
        udpSweepJob?.cancel()
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
                scope.launch { handleTcpClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("TCP server error: $e")
        }
    }

    private suspend fun runUdpServer() {
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress("0.0.0.0", port))
            udpSocket = sock
            val buf = ByteArray(65535)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: Exception) {
                    break
                }
                val clientAddr = pkt.address
                val clientPort = pkt.port
                val dataCopy = buf.copyOf(pkt.length)
                if (clientAddr == null) continue
                scope.launch { handleUdpPacket(sock, clientAddr, clientPort, dataCopy) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("UDP server error: $e")
        }
    }

    private suspend fun handleTcpClient(client: Socket) {
        val clientIp = runCatching { client.inetAddress?.hostAddress }.getOrNull() ?: ""
        val meteredIn = CountingInputStream(client.getInputStream())
        val meteredOut = CountingOutputStream(client.getOutputStream())
        try {
            client.soTimeout = 300000
            val input = DataInputStream(meteredIn)
            val output = DataOutputStream(meteredOut)

            val version = input.readUnsignedByte()
            if (version != 0x05) {
                client.close(); return
            }
            val nmethods = input.readUnsignedByte()
            if (nmethods <= 0) {
                client.close(); return
            }
            val methods = ByteArray(nmethods)
            input.readFully(methods) // throws EOF if the client lied about nmethods
            if (0x00.toByte() !in methods) {
                output.writeByte(0x05); output.writeByte(0xff); output.flush()
                client.close(); return
            }
            output.writeByte(0x05); output.writeByte(0x00); output.flush()

            val ver2 = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            if (ver2 != 0x05) {
                client.close(); return
            }
            val (target, targetPort) = readTarget(input)

            when (cmd) {
                0x01 -> handleConnect(client, input, output, target, targetPort, clientIp)
                0x03 -> handleUdpAssociate(client, input, output)
                else -> {
                    replyError(output, 0x07); client.close()
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun resolve(host: String, net: Network?): List<InetAddress> {
        return EgressManager.resolve(host, net)
    }

    private fun pickNet(host: String? = null): Network? = EgressManager.pickNet(cm, host)

    private fun isValidCellular(n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        target: String,
        targetPort: Int,
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
                replySuccess(output)
                onLog("TCP $target:$targetPort")
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
                onLog("TCP fail $target:$targetPort: ${e.message}")
                runCatching { replyError(output, 0x05) }
            } finally {
                runCatching { client.close() }
                runCatching { upstream?.close() }
                val left = tunnelCount.decrementAndGet()
                AppState.tcpTunnels.value = if (left < 0) 0 else left
            }
        }
    }

    private suspend fun handleUdpAssociate(client: Socket, input: DataInputStream, output: DataOutputStream) {
        val relayAddr = InetAddress.getByName(advertiseIp)
        output.writeByte(0x05); output.writeByte(0x00); output.writeByte(0x00)
        output.writeByte(0x01) // IPv4
        output.write(relayAddr.address)
        output.writeShort(port)
        output.flush()
        onLog("UDP associate from ${client.inetAddress.hostAddress}:${client.port}")
        try {
            val b = ByteArray(1)
            input.read(b) // blocks until client closes
        } catch (_: Exception) {
        }
        runCatching { client.close() }
    }

    private suspend fun handleUdpPacket(
        sock: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        data: ByteArray
    ) {
        try {
            if (data.size < 10) return
            val rsv0 = data[0]; val rsv1 = data[1]; val frag = data[2].toInt() and 0xff
            if (rsv0 != 0.toByte() || rsv1 != 0.toByte()) return
            if (frag != 0) return // fragmentation not supported, drop
            val atyp = data[3].toInt() and 0xff
            var idx = 4
            val dstHost: String
            when (atyp) {
                0x01 -> {
                    if (data.size < idx + 4 + 2) return
                    dstHost = "${data[idx].toInt() and 0xff}.${data[idx + 1].toInt() and 0xff}." +
                        "${data[idx + 2].toInt() and 0xff}.${data[idx + 3].toInt() and 0xff}"
                    idx += 4
                }
                0x03 -> {
                    val len = data[idx].toInt() and 0xff
                    idx += 1
                    if (data.size < idx + len + 2) return
                    dstHost = String(data, idx, len)
                    idx += len
                }
                0x04 -> return // IPv6 targets unsupported
                else -> return
            }
            val dstPort = ((data[idx].toInt() and 0xff) shl 8) or (data[idx + 1].toInt() and 0xff)
            val payload = data.copyOfRange(idx + 2, data.size)

            val clientKey = "${clientAddr.hostAddress}:${clientPort}"
            val net = pickNet(dstHost)
            var sessionNow = udpSessions[clientKey]
            var isNew = false
            if (sessionNow == null) {
                enforceUdpSessionCap()
                val fwd = try {
                    DatagramSocket().also {
                        net?.bindSocket(it)
                        it.soTimeout = 1000
                    }
                } catch (e: Exception) {
                    onLog("UDP session create fail: ${e.message}")
                    return
                }
                val newSession = UdpSession(fwd)
                val prev = udpSessions.putIfAbsent(clientKey, newSession)
                if (prev == null) {
                    sessionNow = newSession
                    isNew = true
                    if (udpSessions.size > maxUdpSessions) enforceUdpSessionCap()
                } else {
                    runCatching { fwd.close() }
                    sessionNow = prev
                }
            }
            val session = sessionNow ?: return
            if (isNew) {
                scope.launch {
                    udpSem.acquire()
                    try {
                        runUdpReplyLoop(session, sock, clientAddr, clientPort)
                    } finally {
                        udpSem.release()
                    }
                }
            }
            session.lastActivity = System.currentTimeMillis()
            try {
                val dstAddr = resolve(dstHost, net).first()
                try {
                    session.socket.send(DatagramPacket(payload, payload.size, dstAddr, dstPort))
                    session.tx.addAndGet(payload.size.toLong())
                    TrafficStats.addTx(payload.size.toLong())
                    EgressManager.reportSuccess(dstHost)
                    ClientUsage.add(clientAddr.hostAddress ?: "", payload.size.toLong())
                } catch (e: Exception) {
                    EgressManager.reportFailure(dstHost)
                    onLog("UDP send fail $dstHost:$dstPort via $net: ${e.message}")
                }
            } catch (e: Exception) {
                onLog("UDP packet handling error: ${e.message}")
            }
        } catch (e: Exception) {
            onLog("UDP packet handling error: ${e.message}")
        }
    }

    private fun enforceUdpSessionCap() {
        if (udpSessions.size < maxUdpSessions) return
        val oldest = udpSessions.entries.minByOrNull { it.value.lastActivity } ?: return
        if (udpSessions.remove(oldest.key, oldest.value)) runCatching { oldest.value.socket.close() }
    }

    private suspend fun runUdpSweep() {
        while (running.get()) {
            delay(60_000)
            val now = System.currentTimeMillis()
            for (key in udpSessions.keys()) {
                val s = udpSessions[key] ?: continue
                if (now - s.lastActivity > 300_000) {
                    if (udpSessions.remove(key, s)) {
                        runCatching { s.socket.close() }
                        reportUdp(s)
                    }
                }
            }
        }
    }

    private suspend fun runUdpReplyLoop(
        session: UdpSession,
        relaySocket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int
    ) {
        try {
            val buf = ByteArray(65535)
            val header = ByteArray(10)
            header[3] = 0x01 // IPv4
            val response = ByteArray(65535 + 10)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    session.socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - session.lastActivity > 300000) break
                    continue
                } catch (e: Exception) {
                    break
                }
                session.lastActivity = System.currentTimeMillis()
                val src = pkt.address
                val srcPort = pkt.port
                val dataLen = pkt.length
                session.rx.addAndGet(dataLen.toLong())
                val ipBytes = src.address
                if (ipBytes.size != 4) continue
                System.arraycopy(ipBytes, 0, header, 4, 4)
                header[8] = ((srcPort shr 8) and 0xff).toByte()
                header[9] = (srcPort and 0xff).toByte()
                val total = 10 + dataLen
                if (total > response.size) continue
                System.arraycopy(header, 0, response, 0, 10)
                System.arraycopy(buf, 0, response, 10, dataLen)
                relaySocket.send(DatagramPacket(response, total, clientAddr, clientPort))
                TrafficStats.addRx(total.toLong())
                ClientUsage.add(clientAddr.hostAddress ?: "", total.toLong())
            }
        } finally {
            runCatching { session.socket.close() }
            reportUdp(session)
            udpSessions.entries.removeIf { it.value === session }
        }
    }

    private fun readTarget(input: DataInputStream): Pair<String, Int> {
        val atyp = input.readUnsignedByte()
        return when (atyp) {
            0x01 -> {
                val b = ByteArray(4); input.readFully(b)
                "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}" to input.readUnsignedShort()
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len); input.readFully(b)
                String(b) to input.readUnsignedShort()
            }
            0x04 -> {
                val b = ByteArray(16); input.readFully(b)
                InetAddress.getByAddress(b).hostAddress to input.readUnsignedShort()
            }
            else -> throw IllegalStateException("bad atyp")
        }
    }

    private fun replySuccess(output: DataOutputStream) {
        output.writeByte(0x05); output.writeByte(0x00); output.writeByte(0x00)
        output.writeByte(0x01); output.write(byteArrayOf(0, 0, 0, 0)); output.writeShort(0)
        output.flush()
    }

    private fun replyError(output: DataOutputStream, code: Int) {
        runCatching {
            output.writeByte(0x05); output.writeByte(code); output.writeByte(0x00)
            output.writeByte(0x01); output.write(byteArrayOf(0, 0, 0, 0)); output.writeShort(0)
            output.flush()
        }
    }

    private fun reportTunnel(target: String, targetPort: Int, dms: Long, tx: Long, rx: Long) {
    }

    private fun reportUdp(session: UdpSession) {
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
