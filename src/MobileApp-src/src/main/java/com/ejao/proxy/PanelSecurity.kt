package com.ejao.proxy

import java.net.URI

/** DNS-rebinding and cross-origin form protection for local control servers. */
internal object PanelSecurity {
    fun allows(headers: List<String>, localAddresses: Set<String>): Boolean {
        val host = headers.singleOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(':')?.trim() ?: return false
        val authority = runCatching { URI("http://$host") }.getOrNull() ?: return false
        val hostname = authority.host?.trim('[', ']') ?: return false
        val literal = EgressManager.parseLiteral(hostname)
        val trusted = hostname.equals("localhost", true) || literal?.isLoopbackAddress == true ||
            literal?.isSiteLocalAddress == true || literal?.isLinkLocalAddress == true ||
            localAddresses.any { it.equals(hostname, true) }
        if (!trusted || authority.port == 0 || authority.port < -1 || authority.userInfo != null) return false
        val origin = headers.singleOrNull { it.startsWith("Origin:", true) }
            ?.substringAfter(':')?.trim() ?: return true
        val source = runCatching { URI(origin) }.getOrNull() ?: return false
        if (source.host == null || source.userInfo != null) return false
        val actualPort = if (authority.port < 0) 80 else authority.port
        val originPort = if (source.port < 0) if (source.scheme == "https") 443 else 80 else source.port
        return source.scheme == "http" && source.host.equals(authority.host, true) && originPort == actualPort
    }
}

internal fun readBoundedContent(
    input: java.io.InputStream,
    headers: List<String>,
    limit: Int = 64 * 1024
): String {
    val values = headers.filter { it.startsWith("Content-Length:", true) }
    // HTML forms may legitimately be submitted with a zero-length body (a
    // button-only form). Treat a missing length as zero; reject ambiguity.
    if (values.isEmpty()) return ""
    require(values.size == 1) { "one Content-Length required" }
    val length = values[0].substringAfter(':').trim().toIntOrNull()
    require(length != null && length in 0..limit) { "invalid Content-Length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val n = input.read(bytes, offset, length - offset)
        if (n < 0) throw java.io.EOFException("truncated form body")
        offset += n
    }
    return String(bytes, Charsets.UTF_8)
}
