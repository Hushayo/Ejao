package com.ejao.proxy

import android.net.Network
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
    private const val TOTAL_TIMEOUT_MS = 12_000L

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
        val failed = AtomicInteger(0)
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
                    failed.incrementAndGet()
                } finally {
                    signal.release()
                }
            }
        }

        launchNext()
        val startedAt = System.currentTimeMillis()
        var nextLaunchAt = startedAt + HEDGE_MS
        while (true) {
            winner.get()?.let {
                // Do not return a socket created after this attempt was
                // interrupted: otherwise its only owner has disappeared.
                if (Thread.currentThread().isInterrupted && winner.compareAndSet(it, null)) {
                    runCatching { it.close() }
                    throw InterruptedIOException("dial interrupted")
                }
                return it
            }
            if (launched.get() >= total && failed.get() >= total) break
            val now = System.currentTimeMillis()
            if (now - startedAt >= TOTAL_TIMEOUT_MS) {
                throw SocketTimeoutException("all $total address(es) timed out")
            }
            if (launched.get() < total && (failed.get() >= launched.get() || now >= nextLaunchAt)) {
                launchNext()
                nextLaunchAt = now + HEDGE_MS
                continue
            }
            signal.tryAcquire(
                if (launched.get() < total) 50L else 200L,
                TimeUnit.MILLISECONDS
            )
        }
        // Race with a just-completed winner before reporting total failure.
        winner.getAndSet(null)?.let {
            if (!Thread.currentThread().isInterrupted) return it
            runCatching { it.close() }
            throw InterruptedIOException("dial interrupted")
        }
        throw IOException("all $total address(es) failed: ${lastErr.get()?.message}")
    }
}
