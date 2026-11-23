package com.ejao.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** Bidirectional socket relay with half-close and cancellation-safe cleanup. */
internal object TcpTunnel {
    suspend fun relay(
        client: Socket,
        upstream: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        running: () -> Boolean,
        onUpload: (Int) -> Unit = {},
        onDownload: (Int) -> Unit = {}
    ): Pair<Long, Long> {
        val uploaded = AtomicLong()
        val downloaded = AtomicLong()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            coroutineScope {
                val closeOnCancel = launch {
                    try { awaitCancellation() } finally { closeSockets(client, upstream) }
                }
                val up: Job = launch {
                    try {
                        uploaded.set(copy(clientInput, upstream.getOutputStream(), running, onUpload))
                    } catch (e: Exception) {
                        failed.set(true)
                        throw e
                    } finally {
                        runCatching { upstream.shutdownOutput() }
                    }
                }
                val down: Job = launch {
                    try {
                        downloaded.set(copy(upstream.getInputStream(), clientOutput, running, onDownload))
                    } catch (e: Exception) {
                        failed.set(true)
                        throw e
                    } finally {
                        runCatching { client.shutdownOutput() }
                    }
                }
                // EOF in one direction is a half-close: wait for the peer to
                // finish its side. An I/O error closes both sockets so the
                // opposite blocking read cannot hang for the tunnel lifetime.
                up.join()
                down.join()
                if (failed.get()) closeSockets(client, upstream)
                closeOnCancel.cancel()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            closeSockets(client, upstream)
        }
        return uploaded.get() to downloaded.get()
    }

    private fun closeSockets(client: Socket, upstream: Socket) {
        runCatching { client.close() }
        runCatching { upstream.close() }
    }

    private fun copy(
        src: InputStream,
        dst: OutputStream,
        running: () -> Boolean,
        onBytes: (Int) -> Unit
    ): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (running()) {
            val n = src.read(buf)
            if (n < 0) break
            if (n == 0) continue
            dst.write(buf, 0, n)
            total += n
            onBytes(n)
        }
        return total
    }
}
