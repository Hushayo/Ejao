package com.sacram.proxy

import kotlinx.coroutines.flow.MutableStateFlow

data class ApInfo(
    val ssid: String = "",
    val passphrase: String = "",
    val goIp: String = "",
    val clients: Int = 0,
    val panelPort: Int = 0,
    val backupPanelPort: Int = 0
)

object AppState {
    val status = MutableStateFlow("Stopped")
    val apInfo = MutableStateFlow(ApInfo())
    val running = MutableStateFlow(false)
    val httpMode = MutableStateFlow(false)
    val tcpTunnels = MutableStateFlow(0)
    // Non-null once a background update check finds + finishes downloading a
    // newer release. Holds the version tag (e.g. "v1.80"); the app never
    // installs automatically, this only flips the UI into "ready to install".
    val updateAvailable = MutableStateFlow<String?>(null)
    var serviceStartedAt: Long = 0L
    // Wi-Fi Direct interface throughput, sampled by ProxyService's health
    // loop from /sys/class/net/<p2p-iface>/statistics. Bits/sec.
    // 0 = unknown (iface not found / counters unreadable).
    @Volatile var netDownBps: Long = 0L
    @Volatile var netUpBps: Long = 0L
    // LAN clients resolved by ProxyService (P2P device names + ARP IPs +
    // metered usage). Rendered by the panel's Connected clients card.
    val lanClients = MutableStateFlow<List<LanClient>>(emptyList())
}

data class LanClient(
    val name: String = "",
    val ip: String = "",
    val mb: Double = 0.0
)

/**
 * Per-client byte accounting. Proxy pumps call [add] with the relayed
 * client's LAN IP; the panel reads [snapshotMb]. Keyed by IP because that
 * is the only client identity visible at the socket layer.
 */
object ClientUsage {
    private val bytes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun add(ip: String, n: Long) {
        if (ip.isBlank() || n <= 0) return
        bytes.computeIfAbsent(ip) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(n)
    }

    fun snapshotMb(): Map<String, Double> =
        bytes.mapValues { it.value.get() / 1048576.0 }

    fun reset() = bytes.clear()
}
