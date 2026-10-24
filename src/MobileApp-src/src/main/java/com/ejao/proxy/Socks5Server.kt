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

    // ---- Concurrency budget -------------------------------------------------
    // Every TCP tunnel pins two threads (one blocking read per direction) and
    // every UDP session pins one (blocking receive). The old caps (512 TCP /
    // 1024 UDP) allowed ~2500 live threads, far past Android's per-process
    // limit: once pthread_create starts failing, new SOCKS handshakes are never
    // served and clients report 10060 handshake timeouts. So:
    //   * control plane gets its own small fixed pool - accepting, handshaking
    //     and sweeping can never be starved by long-lived data sockets
    //   * data plane stays elastic (a bounded pool would deadlock once every
    //     thread sits in a blocking read) but the number of live tunnels and
    //     sessions is capped, which bounds the thread count
    private val ctlThreads = 16
    private val dnsThreads = 8
    private val datagramThreads = 16
    private val maxTcpTunnels = 64      // x2 blocking threads = 128
    private val maxUdpSessions = 64     // x1 blocking thread  = 64
    private val maxUdpAssociates = 32   // x1 blocking thread  = 32

    private fun namedPool(n: Int, prefix: String) = Executors.newFixedThreadPool(n) { r ->
        Thread(r, prefix).apply { isDaemon = true }
    }

    private val ctlExecutor = namedPool(ctlThreads, "ejao-ctl")
    private val dnsExecutor = namedPool(dnsThreads, "ejao-dns")
    private val datagramExecutor = namedPool(datagramThreads, "ejao-udp")
    private val dataExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ejao-io").apply { isDaemon = true }
    }
    private val ctlDispatcher = ctlExecutor.asCoroutineDispatcher()
    private val dnsDispatcher = dnsExecutor.asCoroutineDispatcher()
    private val datagramDispatcher = datagramExecutor.asCoroutineDispatcher()
    private val dataDispatcher = dataExecutor.asCoroutineDispatcher()

    private val tcpSem = Semaphore(maxTcpTunnels)
    private val udpSem = Semaphore(maxUdpSessions)
    private val udpAssocSem = Semaphore(maxUdpAssociates)
    // DNS is a blocking getaddrinfo: keep it off both the control threads and
    // the data threads that are holding a live socket.
    private val proxyDispatcher = dnsDispatcher
    private val scope = CoroutineScope(SupervisorJob() + ctlDispatcher)
    private val dataScope = CoroutineScope(SupervisorJob() + dataDispatcher)
    private val dgramScope = CoroutineScope(SupervisorJob() + datagramDispatcher)
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpJob: Job? = null
    private var udpJob: Job? = null
    private var udpSweepJob: Job? = null
    @Volatile private var cellularNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val socketRcvBuf = 512 * 1024
    private val socketSndBuf = 512 * 1024

    private val tunnelIdleTimeoutMs = 100_000
    private val tunnelCount = AtomicInteger(0)

    // UDP "Happy Eyeballs". A datagram can't fail loudly, so a target that
    // stays silent while we keep transmitting is treated as dead and we move
    // to the next resolved address. RakNet-style clients hold one destination
    // for the whole session (they never re-resolve), so this is the only
    // recovery path they can get from the relay.
    private val udpStallMs = 4_000L
    private val udpStallMinPackets = 8L
    private val udpReresolveMinMs = 30_000L

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
    private val lastIpv6DropLogMs = AtomicLong(0L)

    private class UdpSession(val socket: DatagramSocket) {
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var boundNet: Network? = null
        // Stall detection: when did upstream last answer us, and how many
        // packets have we sent since then without a single reply.
        @Volatile var lastReplyAt = System.currentTimeMillis()
        @Volatile var lastSendAt = 0L
        @Volatile var sentSinceReply = 0L
        @Volatile var lastStallAt = 0L
        // Fallback ladder: the resolved address list and the one in use.
        // dstHost is null for raw IPv4 targets (nothing to re-resolve).
        @Volatile var addrs: List<InetAddress> = emptyList()
        @Volatile var addrIdx = 0
        @Volatile var dstHost: String? = null
        @Volatile var resolvedAt = System.currentTimeMillis()
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
        dataScope.cancel()
        dgramScope.cancel()
        runCatching { ctlExecutor.shutdownNow() }
        runCatching { dnsExecutor.shutdownNow() }
        runCatching { datagramExecutor.shutdownNow() }
        runCatching { dataExecutor.shutdownNow() }
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
            runCatching { sock.receiveBufferSize = 2 * 1024 * 1024 }
            runCatching { sock.sendBufferSize = socketSndBuf }
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
                // Short-lived work with its own small pool: a datagram arrival
                // must never wait behind a long-lived blocking read.
                dgramScope.launch { handleUdpPacket(sock, clientAddr, clientPort, dataCopy) }
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
        // The tunnel's lifetime runs on the data pool, not the control pool:
        // the handshake thread is released as soon as this suspends.
        withContext(dataDispatcher) {
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
                val jobIn = dataScope.launch {
                    try { tx.set(pump(input, up.getOutputStream()) { n ->
                        TrafficStats.addTx(n.toLong())
                        ClientUsage.add(clientIp, n.toLong())
                    }) } finally { runCatching { up.shutdownOutput() } }
                }
                val jobOut = dataScope.launch {
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
    }

    private suspend fun handleUdpAssociate(client: Socket, input: DataInputStream, output: DataOutputStream) {
        // One thread is pinned for as long as the client holds this TCP open,
        // so it has to come out of the data budget - and be refused rather than
        // queued when the budget is gone, otherwise the client hangs on a
        // handshake it will never get an answer to.
        if (!udpAssocSem.tryAcquire()) {
            onLog("UDP associate refused: $maxUdpAssociates already active")
            runCatching { replyError(output, 0x01) }
            runCatching { client.close() }
            return
        }
        try {
            withContext(dataDispatcher) { udpAssociateLoop(client, input, output) }
        } finally {
            udpAssocSem.release()
        }
    }

    private suspend fun udpAssociateLoop(client: Socket, input: DataInputStream, output: DataOutputStream) {
        // Gaming fix: this TCP connection IS the UDP session lifetime for
        // ProxiFyre. The old 300s soTimeout (set in handleTcpClient) killed
        // it mid-game (~5min idle on TCP while UDP was active), tearing down
        // the whole UDP mapping. Infinite timeout + keepalive here.
        runCatching { client.soTimeout = 0 }
        runCatching { client.keepAlive = true }
        val relayAddr = InetAddress.getByName(advertiseIp)
        output.writeByte(0x05); output.writeByte(0x00); output.writeByte(0x00)
        output.writeByte(0x01) // IPv4
        output.write(relayAddr.address)
        output.writeShort(port)
        output.flush()
        onLog("UDP associate from ${client.inetAddress.hostAddress}:${client.port}")
        try {
            val b = ByteArray(1)
            // Loop, don't single-read: some clients send keepalive bytes on
            // the ASSOCIATE TCP. Old code closed the mapping on the first
            // such byte. Stay open until EOF/error (client really gone).
            while (running.get()) {
                val n = input.read(b)
                if (n < 0) break
            }
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
            // Gaming fast-path: ATYP IPv4 carries raw bytes - keep them as an
            // InetAddress so we skip DNS entirely for game traffic (which is
            // ~all raw IPs). Old code stringified then re-resolved every
            // packet via Network.getAllByName = binder + pool per datagram.
            var dstInet: InetAddress? = null
            when (atyp) {
                0x01 -> {
                    if (data.size < idx + 4 + 2) return
                    val ipBytes = byteArrayOf(data[idx], data[idx + 1], data[idx + 2], data[idx + 3])
                    dstHost = "${ipBytes[0].toInt() and 0xff}.${ipBytes[1].toInt() and 0xff}." +
                        "${ipBytes[2].toInt() and 0xff}.${ipBytes[3].toInt() and 0xff}"
                    dstInet = runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull()
                    idx += 4
                }
                0x03 -> {
                    val len = data[idx].toInt() and 0xff
                    idx += 1
                    if (data.size < idx + len + 2) return
                    dstHost = String(data, idx, len)
                    idx += len
                    // Domain that is actually an IP literal (ProxiFyre sends
                    // these a lot): parse locally, no DNS binder call.
                    dstInet = parseIpv4LiteralFast(dstHost)
                }
                0x04 -> {
                    // IPv6 targets unsupported (IPv4-only egress): count it per
                    // destination so the panel can name the offender, and log
                    // at most once a minute so long sessions don't spam.
                    if (data.size < idx + 16 + 2) return
                    val v6 = runCatching {
                        InetAddress.getByAddress(data.copyOfRange(idx, idx + 16)).hostAddress
                    }.getOrNull() ?: "ipv6"
                    val total = TrafficStats.countIpv6Drop(v6)
                    val now = System.currentTimeMillis()
                    val last = lastIpv6DropLogMs.get()
                    if (now - last > 60_000 && lastIpv6DropLogMs.compareAndSet(last, now)) {
                        onLog("UDP IPv6 target $v6 dropped (IPv4-only egress, total=$total) - disable IPv6 on the PC adapter to silence")
                    }
                    return
                }
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
                        runCatching { it.receiveBufferSize = socketRcvBuf }
                        runCatching { it.sendBufferSize = socketSndBuf }
                    }
                } catch (e: Exception) {
                    onLog("UDP session create fail: ${e.message}")
                    return
                }
                val newSession = UdpSession(fwd).also { it.boundNet = net }
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
                dataScope.launch {
                    udpSem.acquire()
                    try {
                        runUdpReplyLoop(session, sock, clientAddr, clientPort)
                    } finally {
                        udpSem.release()
                    }
                }
            }
            session.lastActivity = System.currentTimeMillis()
            // UDP Happy Eyeballs: if this target has gone silent while we keep
            // transmitting, move to the next resolved address before sending.
            advanceIfStalled(session, dstHost)
            try {
                // Fast-path: raw IP needs no DNS. Slow-path keeps the same
                // 3-step fallback as TCP CONNECT: picked net, then fresh
                // cellular, then system default.
                val dstAddr = pickUdpTarget(session, dstHost, dstInet, net)
                session.lastSendAt = System.currentTimeMillis()
                session.sentSinceReply++
                try {
                    session.socket.send(DatagramPacket(payload, payload.size, dstAddr, dstPort))
                    session.tx.addAndGet(payload.size.toLong())
                    TrafficStats.addTx(payload.size.toLong())
                    EgressManager.reportSuccess(dstHost)
                    ClientUsage.add(clientAddr.hostAddress ?: "", payload.size.toLong())
                } catch (e: Exception) {
                    EgressManager.reportFailure(dstHost)
                    // Egress socket was bound to the old Network at creation.
                    // After a cellular handover it blackholes until the 5min
                    // sweep. Drop it now so the next packet rebuilds on the
                    // fresh net (1-packet recovery). Reply loop exits on close.
                    if (udpSessions.remove(clientKey, session)) {
                        runCatching { session.socket.close() }
                    }
                    onLog("UDP send fail $dstHost:$dstPort via $net: ${e.message}")
                }
            } catch (e: Exception) {
                onLog("UDP packet handling error: ${e.message}")
            }
        } catch (e: Exception) {
            onLog("UDP packet handling error: ${e.message}")
        }
    }

    /**
     * Address to send the next datagram to, keeping a per-session fallback
     * ladder. Raw IPv4 targets have exactly one address and no name to
     * re-resolve; hostname targets keep their full address list so a silent
     * first address can be abandoned mid-session.
     */
    private suspend fun pickUdpTarget(
        session: UdpSession,
        dstHost: String,
        dstInet: InetAddress?,
        net: Network?
    ): InetAddress {
        if (dstInet != null) {
            session.addrs = listOf(dstInet)
            session.addrIdx = 0
            session.dstHost = null
            return dstInet
        }
        session.addrs.getOrNull(session.addrIdx)?.let { return it }
        // getaddrinfo blocks for up to 5s; do it on the DNS pool so a lookup
        // storm can't stall the datagram threads.
        val list = withContext(proxyDispatcher) { resolveList(dstHost, net) }
        session.addrs = list
        session.addrIdx = 0
        session.dstHost = dstHost
        session.resolvedAt = System.currentTimeMillis()
        return list.first()
    }

    /** Same 3-step network fallback the TCP path uses: picked net, fresh
     * cellular, then the system default. */
    private suspend fun resolveList(dstHost: String, net: Network?): List<InetAddress> {
        return try {
            resolve(dstHost, net)
        } catch (_: Exception) {
            val fresh = runCatching { NetworkUtils.pickCellular(cm, null) }.getOrNull()
                ?.takeIf { it != net }
            val freshAddrs = if (fresh != null) {
                runCatching { resolve(dstHost, fresh) }.getOrNull()
            } else null
            freshAddrs ?: resolve(dstHost, null)
        }
    }

    /**
     * Silent-target handling. Called on every outgoing datagram, so it costs
     * nothing when replies are flowing (sentSinceReply resets on each reply).
     */
    private fun advanceIfStalled(session: UdpSession, dstHost: String) {
        val sent = session.sentSinceReply
        if (sent < udpStallMinPackets) return
        val now = System.currentTimeMillis()
        if (now - session.lastReplyAt < udpStallMs) return
        if (now - session.lastStallAt < udpStallMs) return

        session.lastStallAt = now
        session.sentSinceReply = 0
        session.lastReplyAt = now
        TrafficStats.udpStalls.incrementAndGet()

        // Raw-IP target: one address, nothing to fall back to and no name to
        // re-resolve. Count it so the panel can show the dead game server.
        if (session.dstHost == null) {
            onLog("UDP stall: $sent packets to raw IP $dstHost with no reply in ${udpStallMs}ms")
            return
        }

        val cur = session.addrs.getOrNull(session.addrIdx)
        val next = session.addrIdx + 1
        if (next < session.addrs.size) {
            session.addrIdx = next
            TrafficStats.udpAddrAdvances.incrementAndGet()
            onLog(
                "UDP stall: ${cur?.hostAddress} silent for $sent packets -> trying " +
                    "${session.addrs[next].hostAddress} for $dstHost"
            )
            return
        }
        if (now - session.resolvedAt < udpReresolveMinMs) {
            onLog("UDP stall: last address ${cur?.hostAddress} for $dstHost silent ($sent packets) - re-resolve throttled")
            return
        }
        // Every known address is silent: force a fresh lookup next packet.
        session.addrs = emptyList()
        session.addrIdx = 0
        session.resolvedAt = now
        EgressManager.clearDnsHost(dstHost)
        onLog("UDP stall: all addresses for $dstHost silent - re-resolving")
    }

    private fun enforceUdpSessionCap() {
        if (udpSessions.size < maxUdpSessions) return
        val oldest = udpSessions.entries.minByOrNull { it.value.lastActivity } ?: return
        if (udpSessions.remove(oldest.key, oldest.value)) runCatching { oldest.value.socket.close() }
    }

    private fun parseIpv4LiteralFast(host: String): InetAddress? {
        // No regex, no DNS: strict a.b.c.d (0-255 each). Null = not a literal.
        var parts = 0
        var val_ = 0
        var digits = 0
        val out = ByteArray(4)
        for (i in host.indices) {
            val c = host[i]
            if (c in '0'..'9') {
                val_ = val_ * 10 + (c - '0')
                if (val_ > 255) return null
                digits++
                if (digits > 3) return null
            } else if (c == '.') {
                if (digits == 0 || parts >= 3) return null
                out[parts++] = val_.toByte()
                val_ = 0
                digits = 0
            } else {
                return null
            }
        }
        if (parts != 3 || digits == 0) return null
        out[3] = val_.toByte()
        return runCatching { InetAddress.getByAddress(out) }.getOrNull()
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
                session.lastReplyAt = session.lastActivity
                session.sentSinceReply = 0
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
                dst.flush()
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
