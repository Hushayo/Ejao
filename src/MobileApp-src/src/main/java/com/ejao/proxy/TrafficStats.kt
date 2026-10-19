package com.ejao.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TrafficStats {
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    // UDP datagrams dropped because the target is IPv6 (egress is IPv4-only).
    // Surfaced in the panel so "Edge keeps spinning" has a visible cause.
    val ipv6UdpDrops = AtomicLong(0L)
    // UDP "Happy Eyeballs" health: how often a target went silent while we
    // kept transmitting, and how often we managed to move to another address.
    val udpStalls = AtomicLong(0L)
    val udpAddrAdvances = AtomicLong(0L)
    // Per-destination tally so a single app (rather than everything) can be
    // blamed for the IPv6 drops. Bounded: only the busiest MAX_IPV6_DSTS keys.
    private const val MAX_IPV6_DSTS = 24
    private val ipv6DropsByDst = ConcurrentHashMap<String, AtomicLong>()
    @Volatile var rxBps = 0L
    @Volatile var txBps = 0L
    @Volatile var maxBps = 0L
    @Volatile var minActiveBps = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L

    fun addRx(n: Long) { if (n > 0) rxBytes.addAndGet(n) }
    fun addTx(n: Long) { if (n > 0) txBytes.addAndGet(n) }

    /** Returns the new total. Pass the target address to also tally it. */
    fun countIpv6Drop(dst: String? = null): Long {
        if (dst != null) {
            ipv6DropsByDst.computeIfAbsent(dst) { AtomicLong(0L) }.incrementAndGet()
            if (ipv6DropsByDst.size > MAX_IPV6_DSTS) {
                // Evict the least-hit entry so a long session with many
                // rotating IPv6 targets can't grow this map without bound.
                ipv6DropsByDst.entries.minByOrNull { it.value.get() }
                    ?.let { ipv6DropsByDst.remove(it.key) }
            }
        }
        return ipv6UdpDrops.incrementAndGet()
    }

    /** Top IPv6 destinations by drop count, for the panel. */
    fun ipv6TopDestinations(limit: Int = 5): List<Pair<String, Long>> =
        ipv6DropsByDst.entries
            .map { it.key to it.value.get() }
            .sortedByDescending { it.second }
            .take(limit)

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
        udpStalls.set(0)
        udpAddrAdvances.set(0)
        ipv6DropsByDst.clear()
        rxBps = 0
        txBps = 0
        maxBps = 0
        minActiveBps = 0
        lastRx = 0
        lastTx = 0
        lastT = 0
    }
}
