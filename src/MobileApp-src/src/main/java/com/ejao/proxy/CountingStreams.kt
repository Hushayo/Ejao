package com.ejao.proxy

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Byte-counting stream wrappers for per-client usage accounting. They sit
 * directly on the accepted client socket's streams, so every relayed byte
 * (SOCKS CONNECT tunnels, plain HTTP forwarding, handshakes) is metered
 * exactly once however the upper layers buffer. Totals feed
 * [ClientUsage] when the connection closes.
 */
class CountingInputStream(src: InputStream) : FilterInputStream(src) {
    private val total = AtomicLong(0L)

    override fun read(): Int {
        val b = super.read()
        if (b != -1) total.incrementAndGet()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) total.addAndGet(n.toLong())
        return n
    }

    fun bytes(): Long = total.get()
}

class CountingOutputStream(dst: OutputStream) : FilterOutputStream(dst) {
    private val total = AtomicLong(0L)

    override fun write(b: Int) {
        out.write(b)
        total.incrementAndGet()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        total.addAndGet(len.toLong())
    }

    fun bytes(): Long = total.get()
}
