package com.ejao.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class NetworkSelfTest {
    @Test
    fun chunkedBodyIsForwardedVerbatim() {
        val chunked = "4\r\nWiki\r\n5;ext=yes\r\npedia\r\n0\r\nX-End: yes\r\n\r\n"
        val out = ByteArrayOutputStream()
        var counted = 0
        val input = ByteArrayInputStream(chunked.toByteArray())
        HttpBody.copyChunked(input, out, { readLine(input) }) { counted += it }
        assertEquals(chunked, out.toString("ISO-8859-1"))
        assertEquals(9, counted)
    }

    @Test
    fun fixedBodyCopiesExactlyAndRejectsTruncation() {
        val out = ByteArrayOutputStream()
        HttpBody.copyFixed(ByteArrayInputStream(byteArrayOf(1, 2, 3)), out, 3, false)
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
        assertThrows(EOFException::class.java) {
            HttpBody.copyFixed(ByteArrayInputStream(byteArrayOf(1)), out, 2, false)
        }
    }

    @Test
    fun malformedChunkTerminatorIsRejected() {
        val input = ByteArrayInputStream("1\r\naX\r\n".toByteArray())
        assertThrows(Exception::class.java) {
            HttpBody.copyChunked(input, ByteArrayOutputStream(), { readLine(input) })
        }
    }

    @Test
    fun halfCloseAndIdleTunnelStillDeliverResponse() = runBlocking {
        val origin = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val originJob = launch(Dispatchers.IO) {
            origin.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val request = input.readNBytes(4)
                assertEquals("ping", String(request, Charsets.UTF_8))
                assertEquals(-1, input.read()) // upload EOF becomes upstream EOF
                delay(150) // no arbitrary tunnel idle timeout
                socket.getOutputStream().write("pong".toByteArray())
            }
        }
        val (app, proxyClient) = socketPair()
        val upstream = Socket(InetAddress.getLoopbackAddress(), origin.localPort)
        val relay = launch(Dispatchers.IO) {
            TcpTunnel.relay(
                proxyClient, upstream, proxyClient.getInputStream(), proxyClient.getOutputStream(), { true }
            )
        }
        app.getOutputStream().write("ping".toByteArray())
        app.shutdownOutput()
        app.soTimeout = 2_000
        assertEquals("pong", String(app.getInputStream().readNBytes(4), Charsets.UTF_8))
        withTimeout(2_000) { relay.join(); originJob.join() }
        origin.close()
        app.close()
    }

    @Test
    fun cancellationClosesBothSockets() = runBlocking {
        val (app, proxy) = socketPair()
        val (upClient, upPeer) = socketPair()
        app.soTimeout = 2_000
        val relay = launch(Dispatchers.IO) {
            TcpTunnel.relay(proxy, upClient, proxy.getInputStream(), proxy.getOutputStream(), { true })
        }
        delay(50)
        relay.cancel()
        withTimeout(2_000) { relay.join() }
        assertTrue(proxy.isClosed)
        assertTrue(upClient.isClosed)
        assertEquals(-1, app.getInputStream().read())
        app.close()
        upPeer.close()
    }

    @Test
    fun panelRejectsForeignHostAndOrigin() {
        val local = setOf("192.168.49.1")
        assertTrue(PanelSecurity.allows(listOf("Host: 192.168.49.1:8283"), local))
        assertTrue(
            PanelSecurity.allows(
                listOf("Host: 192.168.49.1:8283", "Origin: http://192.168.49.1:8283"), local
            )
        )
        assertFalse(
            PanelSecurity.allows(
                listOf("Host: 192.168.49.1:8283", "Origin: https://evil.example"), local
            )
        )
        assertFalse(PanelSecurity.allows(listOf("Host: evil.example"), local))
        assertFalse(PanelSecurity.allows(emptyList(), local))
    }

    private fun socketPair(): Pair<Socket, Socket> {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val first = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val second = listener.accept()
        listener.close()
        return first to second
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) return line.toString("ISO-8859-1").removeSuffix("\r")
            line.write(b)
        }
    }
}
