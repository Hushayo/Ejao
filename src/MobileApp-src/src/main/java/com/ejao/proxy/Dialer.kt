package com.ejao.proxy

import android.net.Network
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared outbound TCP dialer for every proxy front-end (SOCKS5, SOCKS4, HTTP).
 *
 * The old dial loops walked the resolved address list one at a time with an
 * 8s timeout each, so a dead first address stalled the client for 8s before
 * the healthy second one was even attempted. Here the first address starts
 * immediately and the next one follows every [HEDGE_MS] until one connects
 * (Happy Eyeballs); an early failure advances the ladder immediately.
 *
 * Socket buffers are applied BEFORE connect: Linux fixes a connection's
 * window scale at SYN time from the socket's receive buffer, so tuning after
 * connect (the old behaviour) advertised a small window and capped
 * single-connection throughput over high-RTT cellular links.
 */
object Dialer {
    private const val HEDGE_MS = 300L
    private const val ATTEMPT_TIMEOUT_MS = 4_500

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ejao-dial").apply { isDaemon = true }
    }

    fun connect(
        host: String,
        port: Int,
        net: Network?,
        rcvBuf: Int,
        sndBuf: Int,
        trafficClass: Int = -1
    ): Socket {
        val addrs = EgressManager.parseLiteral(host)?.let { listOf(it) }
            ?: EgressManager.resolve(host, net)
        if (addrs.isEmpty()) throw IOException("no addresses for $host")
        if (addrs.size == 1) {
            return connectOne(addrs[0], port, net, rcvBuf, sndBuf, trafficClass)
        }
        return connectRaced(addrs, port, net, rcvBuf, sndBuf, trafficClass)
    }

    private fun connectOne(
        addr: InetAddress,
        port: Int,
        net: Network?,
        rcvBuf: Int,
        sndBuf: Int,
        trafficClass: Int
    ): Socket {
        val sock = Socket()
        try {
            if (net != null) net.bindSocket(sock)
            runCatching { sock.receiveBufferSize = rcvBuf }
            runCatching { sock.sendBufferSize = sndBuf }
            if (trafficClass >= 0) runCatching { sock.trafficClass = trafficClass }
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(addr, port), ATTEMPT_TIMEOUT_MS)
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun connectRaced(
        addrs: List<InetAddress>,
        port: Int,
        net: Network?,
        rcvBuf: Int,
        sndBuf: Int,
        trafficClass: Int
    ): Socket {
        val total = addrs.size
        val launched = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val winner = AtomicReference<Socket?>(null)
        val lastErr = AtomicReference<Exception?>(null)
        val signal = Semaphore(0)

        fun launchNext() {
            val idx = launched.getAndIncrement()
            if (idx >= total) return
            val addr = addrs[idx]
            pool.execute {
                try {
                    val s = connectOne(addr, port, net, rcvBuf, sndBuf, trafficClass)
                    if (!winner.compareAndSet(null, s)) runCatching { s.close() }
                } catch (e: Exception) {
                    lastErr.set(e)
                } finally {
                    finished.incrementAndGet()
                    signal.release()
                }
            }
        }

        launchNext()
        var lastLaunch = System.currentTimeMillis()
        while (true) {
            winner.get()?.let { return it }
            if (finished.get() >= total) break
            val now = System.currentTimeMillis()
            if (launched.get() < total && (finished.get() > 0 || now - lastLaunch >= HEDGE_MS)) {
                launchNext()
                lastLaunch = now
                continue
            }
            signal.tryAcquire(
                if (launched.get() < total) HEDGE_MS else 200L,
                TimeUnit.MILLISECONDS
            )
        }
        throw IOException("all $total address(es) failed: ${lastErr.get()?.message}")
    }
}
