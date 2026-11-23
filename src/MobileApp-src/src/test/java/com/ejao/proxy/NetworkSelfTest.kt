package com.ejao.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private object NetworkSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testBodies()
        testTunnel()
        testOrigin()
        println("NetworkSelfTest: PASS")
    }

    private fun testBodies() {
        val chunked = "4\r\nWiki\r\n5;ext=yes\r\npedia\r\n0\r\nX-End: yes\r\n\r\n"
        val chunkedOut = ByteArrayOutputStream()
        var chunkBytes = 0
        val chunkedInput = ByteArrayInputStream(chunked.toByteArray())
        HttpBody.copyChunked(chunkedInput, chunkedOut, { readLine(chunkedInput) }) { chunkBytes += it }
        check(chunkedOut.toString("ISO-8859-1") == chunked)
        check(chunkBytes == 9)

        val fixedOut = ByteArrayOutputStream()
        HttpBody.copyFixed(ByteArrayInputStream(byteArrayOf(1, 2, 3)), fixedOut, 3, false)
        check(fixedOut.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
        check(runCatching {
            HttpBody.copyFixed(ByteArrayInputStream(byteArrayOf(1)), fixedOut, 2, false)
        }.exceptionOrNull() is EOFException)
        check(runCatching {
            HttpBody.copyChunked(
                ByteArrayInputStream("1\r\naX\r\n".toByteArray()), ByteArrayOutputStream(),
                { "1" }
            )
        }.isFailure)
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

    private fun testTunnel() = runBlocking {
        val origin = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val originJob = launch(Dispatchers.IO) {
            origin.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val request = input.readNBytes(4)
                check(String(request, Charsets.UTF_8) == "ping")
                check(input.read() == -1) // upload EOF must become upstream EOF
                delay(150) // no arbitrary tunnel idle timeout
                output.write("pong".toByteArray())
            }
        }
        val (app, proxyClient) = socketPair()
        val upstream = Socket(InetAddress.getLoopbackAddress(), origin.localPort)
        val relay = launch(Dispatchers.IO) {
            TcpTunnel.relay(proxyClient, upstream, proxyClient.getInputStream(), proxyClient.getOutputStream(), { true })
        }
        app.getOutputStream().write("ping".toByteArray())
        app.shutdownOutput()
        app.soTimeout = 2_000
        check(String(app.getInputStream().readNBytes(4), Charsets.UTF_8) == "pong")
        withTimeout(2_000) { relay.join(); originJob.join() }
        origin.close()
        app.close()

        // Cancellation must unblock both readers and close both sockets.
        val (app2, proxy2) = socketPair()
        val up2 = socketPair()
        app2.soTimeout = 2_000
        val cancelled = launch(Dispatchers.IO) {
            TcpTunnel.relay(proxy2, up2.first, proxy2.getInputStream(), proxy2.getOutputStream(), { true })
        }
        delay(50)
        cancelled.cancel()
        withTimeout(2_000) { cancelled.join() }
        check(proxy2.isClosed && up2.first.isClosed)
        check(app2.getInputStream().read() == -1)
        app2.close(); up2.second.close()
    }

    private fun socketPair(): Pair<Socket, Socket> {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val first = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val second = listener.accept()
        listener.close()
        return first to second
    }

    private fun testOrigin() {
        val local = setOf("192.168.49.1")
        check(PanelSecurity.allows(listOf("Host: 192.168.49.1:8283"), local))
        check(PanelSecurity.allows(
            listOf("Host: 192.168.49.1:8283", "Origin: http://192.168.49.1:8283"), local
        ))
        check(!PanelSecurity.allows(
            listOf("Host: 192.168.49.1:8283", "Origin: https://evil.example"), local
        ))
        check(!PanelSecurity.allows(listOf("Host: evil.example"), local))
        check(!PanelSecurity.allows(emptyList(), local))
    }
}
