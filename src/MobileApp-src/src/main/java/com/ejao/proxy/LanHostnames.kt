package com.ejao.proxy

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Best-effort hostname detection for devices behind the hotspot.
 *
 * WifiP2pGroup.clientList only carries a deviceAddress for clients that
 * joined as plain Wi-Fi STAs (the PC case), so the panel used to fall back
 * to "Unknown device" forever. Ask the client directly instead:
 *
 *  1. NetBIOS node status (UDP 137) - Windows PCs answer with their
 *     workstation name.
 *  2. mDNS reverse PTR (224.0.0.251:5353) - macOS/iOS/Linux (Avahi) and
 *     other Bonjour responders answer with "<host>.local".
 *  3. DNS PTR against the gateway - dnsmasq-based tethering exposes DHCP
 *     lease names there.
 *
 * Lookups are cached per MAC (so a new DHCP lease keeps the old name) and
 * run on one background thread with throttle/cooldown; they never block the
 * proxy or the status poll.
 */
object LanHostnames {
    private const val TTL_MS = 15 * 60_000L
    private const val RETRY_MS = 60_000L
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val NETBIOS_PORT = 137
    private const val MAX_NAME = 32

    private data class Entry(val name: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val worker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ejao-hostname").apply { isDaemon = true }
    }

    fun cached(mac: String, ip: String): String? {
        val key = keyFor(mac, ip)
        if (key.isNotEmpty()) cache[key]?.let { return it.name }
        // A name resolved before ARP exposed the MAC is stored under the IP.
        val ipKey = if (ip.isNotBlank()) "ip:$ip" else ""
        return if (ipKey.isNotEmpty()) cache[ipKey]?.name else null
    }

    fun isFresh(mac: String, ip: String): Boolean {
        val key = keyFor(mac, ip)
        val entry = cache[key] ?: cache["ip:$ip"] ?: return false
        return System.currentTimeMillis() - entry.at < TTL_MS
    }

    /**
     * Schedules a lookup when no fresh name is cached. Safe to call on every
     * status tick: in-flight keys and a 60s failure cooldown prevent storms.
     */
    fun requestLookup(mac: String, ip: String, sourceIp: String) {
        if (ip.isBlank() || ip.contains(':')) return
        val key = keyFor(mac, ip)
        if (key.isEmpty()) return
        val entry = cache[key] ?: cache["ip:$ip"]
        if (entry != null && System.currentTimeMillis() - entry.at < TTL_MS) return
        val now = System.currentTimeMillis()
        val prev = lastAttempt[key]
        if (prev != null && now - prev < RETRY_MS) return
        if (!inFlight.add(key)) return
        lastAttempt[key] = now
        try {
            worker.execute {
                try {
                    val name = resolveBlocking(ip, sourceIp)
                    if (name != null) cache[key] = Entry(name, System.currentTimeMillis())
                } catch (_: Exception) {
                } finally {
                    inFlight.remove(key)
                }
            }
        } catch (_: Exception) {
            inFlight.remove(key)
        }
    }

    private fun keyFor(mac: String, ip: String): String {
        val m = mac.trim().lowercase()
        if (m.isNotEmpty() && m.contains(":")) return "mac:$m"
        val clean = ip.trim()
        return if (clean.isNotEmpty()) "ip:$clean" else ""
    }

    private fun resolveBlocking(ip: String, sourceIp: String): String? {
        netbiosName(ip, sourceIp, 450)?.let { return it }
        mdnsReverseName(ip, sourceIp, 500)?.let { return it }
        if (sourceIp.isNotBlank() && sourceIp.contains('.')) {
            dnsPtrName(sourceIp, 53, reverseName(ip), 300)?.let { return it }
        }
        return null
    }

    private fun reverseName(ip: String): String =
        ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

    // ---- NetBIOS node status -------------------------------------------------

    private fun netbiosName(ip: String, sourceIp: String, timeoutMs: Int): String? {
        val query = ByteArrayOutputStream().apply {
            write(0x00); write(0x00) // transaction id
            write(0x00); write(0x00) // flags
            write(0x00); write(0x01) // QDCOUNT
            write(0x00); write(0x00) // ANCOUNT
            write(0x00); write(0x00) // NSCOUNT
            write(0x00); write(0x00) // ARCOUNT
            write(0x20)             // NetBIOS name length (32)
            write(ByteArray(32).also { it[0] = '*'.code.toByte() })
            write(0x00); write(0x21) // NBSTAT
            write(0x00); write(0x01) // IN
        }.toByteArray()
        val socket = udpSocket(sourceIp) ?: return null
        try {
            val target = InetAddress.getByName(ip)
            socket.send(DatagramPacket(query, query.size, target, NETBIOS_PORT))
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    return null
                }
                if (pkt.address?.hostAddress != ip) continue
                parseNbstat(buf, pkt.length)?.let { return it }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        return null
    }

    private fun parseNbstat(buf: ByteArray, len: Int): String? {
        if (len < 12) return null
        val qd = u16(buf, 4)
        val an = u16(buf, 6)
        if (an <= 0) return null
        var off = 12
        repeat(qd) {
            off = readName(buf, off)?.second ?: return null
            if (off + 4 > len) return null
            off += 4
        }
        if (off + 10 > len) return null
        off = readName(buf, off)?.second ?: return null
        if (off + 10 > len) return null
        val type = u16(buf, off)
        val rdLen = u16(buf, off + 8)
        off += 10
        if (type != 0x21 || off + rdLen > len || rdLen < 1) return null
        val count = buf[off].toInt() and 0xFF
        var p = off + 1
        var unique: String? = null
        var fallback: String? = null
        for (i in 0 until count) {
            if (p + 18 > len) break
            val raw = String(buf, p, 15, Charsets.US_ASCII).replace("\u0000", "").trim()
            val suffix = buf[p + 15].toInt() and 0xFF
            val flags = u16(buf, p + 16)
            val group = (flags and 0x8000) != 0
            if (raw.isNotEmpty() && !group && raw != "*") {
                if (suffix == 0x00 && unique == null) unique = raw
                if (fallback == null && (suffix == 0x20 || suffix == 0x03)) fallback = raw
            }
            p += 18
        }
        return cleanName(unique ?: fallback)
    }

    // ---- mDNS reverse PTR ----------------------------------------------------

    private fun mdnsReverseName(ip: String, sourceIp: String, timeoutMs: Int): String? {
        val qname = reverseName(ip)
        val query = dnsQuery(0, qname, qtype = 12, qclass = 0x8001) // QU bit set
        var socket: MulticastSocket? = null
        try {
            val sock = if (sourceIp.isNotBlank() && sourceIp.contains('.'))
                MulticastSocket(InetSocketAddress(sourceIp, 0))
            else
                MulticastSocket()
            socket = sock
            runCatching { ifaceFor(sourceIp)?.let { sock.networkInterface = it } }
            runCatching { sock.timeToLive = 255 }
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT))
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                sock.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    break
                }
                ptrAnswer(buf, pkt.length, qname, 0)?.let { return it }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
        return null
    }

    // ---- DNS PTR against the gateway/DHCP DNS --------------------------------

    private fun dnsPtrName(server: String, port: Int, qname: String, timeoutMs: Int): String? {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val query = dnsQuery(id, qname, qtype = 12, qclass = 0x0001)
        val socket = udpSocket(server) ?: return null
        try {
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), port))
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    return null
                }
                ptrAnswer(buf, pkt.length, qname, id)?.let { return it }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        return null
    }

    // ---- DNS wire helpers ----------------------------------------------------

    private fun udpSocket(sourceIp: String): DatagramSocket? = try {
        if (sourceIp.isNotBlank() && sourceIp.contains('.'))
            DatagramSocket(InetSocketAddress(sourceIp, 0))
        else
            DatagramSocket()
    } catch (_: Exception) {
        null
    }

    private fun ifaceFor(sourceIp: String): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { nif ->
                nif.isUp && nif.inetAddresses.asSequence()
                    .any { it.hostAddress == sourceIp }
            }
    }.getOrNull()

    private fun dnsQuery(id: Int, name: String, qtype: Int, qclass: Int): ByteArray =
        ByteArrayOutputStream().apply {
            w16(id); w16(0x0000); w16(1); w16(0); w16(0); w16(0)
            for (label in name.split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                if (b.isEmpty() || b.size > 63) continue
                write(b.size); write(b)
            }
            write(0)
            w16(qtype); w16(qclass)
        }.toByteArray()

    /** Returns the target of the PTR record whose owner equals [wantOwner]. */
    private fun ptrAnswer(buf: ByteArray, len: Int, wantOwner: String, wantId: Int): String? {
        if (len < 12) return null
        if (wantId >= 0 && u16(buf, 0) != wantId) return null
        val qd = u16(buf, 4)
        val total = u16(buf, 6) + u16(buf, 8) + u16(buf, 10)
        var off = 12
        repeat(qd) {
            off = readName(buf, off)?.second ?: return null
            if (off + 4 > len) return null
            off += 4
        }
        repeat(total) {
            if (off + 10 > len) return null
            val (owner, afterName) = readName(buf, off) ?: return null
            var p = afterName
            if (p + 10 > len) return null
            val type = u16(buf, p)
            val rdLen = u16(buf, p + 8)
            p += 10
            if (p + rdLen > len) return null
            if (type == 12 && owner.equals(wantOwner, ignoreCase = true)) {
                val target = readName(buf, p)?.first ?: return null
                return cleanName(target)
            }
            off = p + rdLen
        }
        return null
    }

    /** Parses a (possibly compressed) DNS name starting at [off]. */
    private fun readName(buf: ByteArray, off: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = off
        var end = -1
        var jumps = 0
        while (true) {
            if (pos < 0 || pos >= buf.size) return null
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= buf.size) return null
                val ptr = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                if (end < 0) end = pos + 2
                pos = ptr
                if (++jumps > 8) return null
                continue
            }
            if (pos + 1 + len > buf.size) return null
            sb.append(String(buf, pos + 1, len, Charsets.ISO_8859_1)).append('.')
            pos += 1 + len
        }
        return sb.toString().trimEnd('.') to (if (end >= 0) end else pos)
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun ByteArrayOutputStream.w16(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun cleanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().replace("\u0000", "").trimEnd('.')
        for (suffix in listOf(".local", ".lan", ".home", ".localdomain")) {
            if (s.length > suffix.length + 1 && s.endsWith(suffix, ignoreCase = true)) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        s = s.trim().trimEnd('.')
        if (s.length < 2 || s.equals("localhost", ignoreCase = true)) return null
        if (s.all { it.isDigit() || it == '.' }) return null
        if (!s.any { it.isLetter() }) return null
        if (s.any { it.isISOControl() }) return null
        return s.take(MAX_NAME).trim().ifEmpty { null }
    }
}
