package com.ejao.proxy

import android.os.SystemClock
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
    // Idle mode: hotspot + service stay up, data plane (SOCKS/HTTP) paused.
    // Panel + backup panel stay reachable so Resume is one tap away.
    val isIdle = MutableStateFlow(false)
    var serviceStartedAt: Long = 0L
    @Volatile var netDownBps: Long = 0L
    @Volatile var netUpBps: Long = 0L
    @Volatile var netMaxBps: Long = 0L
    @Volatile var netMinBps: Long = 0L
    val lanClients = MutableStateFlow<List<LanClient>>(emptyList())
}

data class LanClient(
    val name: String = "",
    val ip: String = "",
    val mb: Double = 0.0,
    val mac: String = "",
    val id: String = ""
)

object ClientUsage {
    private const val RETENTION_MS = 30 * 60_000L

    private val bytes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val lastActive = java.util.concurrent.ConcurrentHashMap<String, Long>()

    data class Usage(val mb: Double, val lastActiveMs: Long)

    fun add(ip: String, n: Long) {
        if (ip.isBlank() || n <= 0) return
        bytes.computeIfAbsent(ip) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(n)
        lastActive[ip] = android.os.SystemClock.elapsedRealtime()
    }

    fun snapshot(): Map<String, Usage> {
        val now = android.os.SystemClock.elapsedRealtime()
        return bytes.mapValues { (ip, v) ->
            Usage(v.get() / 1048576.0, lastActive[ip] ?: now)
        }
    }

    /**
     * Drops long-dead IPs so a device that left cannot keep a row alive
     * forever. [keep] protects ips still present in the ARP table.
     */
    fun prune(keep: (String) -> Boolean) {
        val cutoff = android.os.SystemClock.elapsedRealtime() - RETENTION_MS
        for (ip in bytes.keys.toList()) {
            if ((lastActive[ip] ?: 0L) >= cutoff || keep(ip)) continue
            bytes.remove(ip)
            lastActive.remove(ip)
        }
    }

    fun reset() {
        bytes.clear()
        lastActive.clear()
    }
}
