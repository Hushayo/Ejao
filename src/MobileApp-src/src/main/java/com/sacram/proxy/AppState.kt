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
}
