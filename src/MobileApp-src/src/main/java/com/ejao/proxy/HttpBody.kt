package com.ejao.proxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Streaming HTTP/1.1 body framing; no whole-body allocations. */
internal object HttpBody {
    fun copyFixed(
        input: InputStream,
        output: OutputStream,
        length: Long,
        flushEach: Boolean,
        onBytes: (Int) -> Unit = {}
    ) {
        require(length >= 0) { "negative Content-Length" }
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("truncated HTTP body")
            if (n == 0) continue
            output.write(buf, 0, n)
            if (flushEach) output.flush()
            remaining -= n
            onBytes(n)
        }
    }

    fun copyChunked(
        input: InputStream,
        output: OutputStream,
        readLine: () -> String?,
        onBytes: (Int) -> Unit = {}
    ) {
        while (true) {
            val sizeLine = readLine() ?: throw EOFException("truncated chunk size")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("invalid chunk size")
            if (size < 0) throw IOException("invalid chunk size")
            output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
            output.write(CRLF)
            if (size == 0L) {
                var trailers = 0
                while (true) {
                    if (trailers++ >= 100) throw IOException("too many chunk trailers")
                    val trailer = readLine() ?: throw EOFException("truncated chunk trailers")
                    output.write(trailer.toByteArray(Charsets.ISO_8859_1))
                    output.write(CRLF)
                    if (trailer.isEmpty()) return
                }
            }
            copyFixed(input, output, size, flushEach = false, onBytes = onBytes)
            val separator = readLine() ?: throw EOFException("truncated chunk CRLF")
            if (separator.isNotEmpty()) throw IOException("missing chunk CRLF")
            output.write(CRLF)
            output.flush()
        }
    }

    private val CRLF = byteArrayOf(13, 10)
}
