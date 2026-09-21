package com.ejao.proxy

import java.util.concurrent.atomic.AtomicLong

object TrafficStats {
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    // UDP datagrams dropped because the target is IPv6 (egress is IPv4-only).
    // Surfaced in the panel so "Edge keeps spinning" has a visible cause.
    val ipv6UdpDrops = AtomicLong(0L)
    @Volatile var rxBps = 0L
    @Volatile var txBps = 0L
    @Volatile var maxBps = 0L
    @Volatile var minActiveBps = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L

    fun addRx(n: Long) { if (n > 0) rxBytes.addAndGet(n) }
    fun addTx(n: Long) { if (n > 0) txBytes.addAndGet(n) }

    /** Returns the new total. */
    fun countIpv6Drop(): Long = ipv6UdpDrops.incrementAndGet()

    @Synchronized fun sampleNow(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val rx = rxBytes.get()
        val tx = txBytes.get()
        if (lastT == 0L) {
            lastRx = rx
            lastTx = tx
            lastT = now
            return rxBps to txBps
        }
        val dt = (now - lastT).coerceAtLeast(1) / 1000.0
        rxBps = ((rx - lastRx).coerceAtLeast(0) * 8 / dt).toLong()
        txBps = ((tx - lastTx).coerceAtLeast(0) * 8 / dt).toLong()
        lastRx = rx
        lastTx = tx
        lastT = now
        recordSample(rxBps + txBps)
        return rxBps to txBps
    }

    @Synchronized fun recordSample(totalBps: Long) {
        if (totalBps > maxBps) maxBps = totalBps
        if (totalBps > 0) {
            if (minActiveBps == 0L || totalBps < minActiveBps) minActiveBps = totalBps
        }
    }

    fun reset() {
        rxBytes.set(0)
        txBytes.set(0)
        ipv6UdpDrops.set(0)
        rxBps = 0
        txBps = 0
        maxBps = 0
        minActiveBps = 0
        lastRx = 0
        lastTx = 0
        lastT = 0
    }
}
