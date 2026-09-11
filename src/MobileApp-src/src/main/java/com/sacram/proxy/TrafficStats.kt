package com.sacram.proxy

import java.util.concurrent.atomic.AtomicLong

object TrafficStats {
    val rxBytes = AtomicLong(0L)
    val txBytes = AtomicLong(0L)
    @Volatile var rxBps = 0L
    @Volatile var txBps = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L

    fun addRx(n: Long) { if (n > 0) rxBytes.addAndGet(n) }
    fun addTx(n: Long) { if (n > 0) txBytes.addAndGet(n) }

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
        return rxBps to txBps
    }

    fun reset() {
        rxBytes.set(0)
        txBytes.set(0)
        rxBps = 0
        txBps = 0
        lastRx = 0
        lastTx = 0
        lastT = 0
    }
}
