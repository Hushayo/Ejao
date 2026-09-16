package com.ejao.proxy

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
    val isReforming = MutableStateFlow(false)
    val updateAvailable = MutableStateFlow<String?>(null)
    var serviceStartedAt: Long = 0L
    @Volatile var netDownBps: Long = 0L
    @Volatile var netUpBps: Long = 0L
    val lanClients = MutableStateFlow<List<LanClient>>(emptyList())
}

data class LanClient(
    val name: String = "",
    val ip: String = "",
    val mb: Double = 0.0
)

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
