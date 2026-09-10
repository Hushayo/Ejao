package com.sacram.proxy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "sacram_proxy"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.sacram.proxy.START"
        const val ACTION_STOP = "com.sacram.proxy.STOP"
        private const val TAG = "SacramService"
        private const val WATCHDOG_REQ = 7
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, WATCHDOG_REQ,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }

        private fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, WATCHDOG_REQ,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var socks: Socks5Server? = null
    private var socks4: Socks4Server? = null
    private var http: HttpProxyServer? = null
    private var panel: PanelServer? = null
    // Emergency backup dashboard: started right after the WiFi Direct group
    // forms and deliberately NOT stopped by restartProxy(), so it stays
    // reachable over WiFi Direct even when the main proxy/panel dies.
    // Only torn down in onDestroy().
    private var backupPanel: BackupPanelServer? = null
    private var backupPanelPortActual: Int = 0
    private var proxyDownNotified = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fileObserver: FileObserver? = null
    private var restartJob: Job? = null
    private var keepAliveJob: Job? = null
    private var startedAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createChannel()
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        acquireLocks()
        startFileWatcher()
        PanelApproval.onRestart = { restartProxy() }
        UpdateChecker.scheduleCheck(this, ConfigManager.ensureConfig(this).updateCheckIntervalHours)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProxyState.setShouldRun(this, false)
            cancelWatchdog(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started.compareAndSet(false, true)) {
            startedAt = System.currentTimeMillis()
            AppState.serviceStartedAt = startedAt
            ProxyState.setShouldRun(this, true)
            scheduleWatchdog(this)
            keepAliveJob = KeepAlive.launch(scope, this)
            scope.launch { runPipeline() }
        }
        return START_STICKY
    }

    private suspend fun runPipeline() {
        Log.i(TAG, "runPipeline start, sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}")
        updateStatus("Starting...")
        try {
            val config = ConfigManager.ensureConfig(this)
            if (!started.get()) return

            AppState.running.value = true
            updateStatus("Checking WiFi...")
            var wifiOk = WifiDirectManager(this@ProxyService).ensureWifiOn()
            Log.i(TAG, "ensureWifiOn result=$wifiOk")
            if (!wifiOk) {
                if (config.autoRestartOnWifiReturn) {
                    // Keep the service alive and wait for WiFi to come back, then
                    // continue the pipeline on its own (no manual off/on needed).
                    while (!wifiOk && started.get()) {
                        if (!ConfigManager.load(this@ProxyService).autoRestartOnWifiReturn) break
                        updateStatus("WiFi is off - waiting for it to return before (re)starting the proxy...")
                        delay(5000)
                        wifiOk = WifiDirectManager(this@ProxyService).ensureWifiOn()
                    }
                }
                if (!wifiOk) {
                    updateStatus("ERROR [01]: WiFi is off - turn on WiFi to start the hotspot")
                    stopSelf()
                    return
                }
            }
            Log.i(TAG, "proxy starting wifiOk=$wifiOk port=${config.port}")
            val p2p = WifiDirectManager(this)

            updateStatus("Creating WiFi Direct group...")
            var createOk = false
            var createMsg = ""
            p2p.removeExistingGroup {
                val band = if (config.disableBandSelector) "2.4" else config.band
                p2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                    createOk = ok
                    createMsg = msg
                    Log.i(TAG, "createGroup result ok=$ok msg=$msg band=$band")
                }
            }
            var waited = 0
            while (!createOk && waited < 5000) {
                delay(200); waited += 200
            }
            Log.i(TAG, "createGroup waited=${waited}ms ok=$createOk msg=$createMsg")
            if (!createOk) {
                Log.e(TAG, "proxy_error: $createMsg")
                updateStatus("ERROR: $createMsg")
                stopSelf()
                return
            }

            // wait until group info is available
            var groupSsid = ""
            var groupPass = ""
            var formed = false
            for (i in 0 until 60) {
                delay(500)
                var got = false
                p2p.requestGroupInfo { g ->
                    got = true
                    if (g != null) {
                        formed = true
                        groupSsid = g.networkName
                        groupPass = g.passphrase
                    }
                }
                var loop = 0
                while (!got && loop < 20) { delay(50); loop++ }
                if (formed) break
            }
            if (!formed) {
                Log.e(TAG, "proxy_error: group did not form")
                updateStatus("ERROR: group did not form")
                stopSelf()
                return
            }

            val goIp = p2p.getGroupOwnerIp()
            val actualSsid = groupSsid.ifEmpty { config.ssid }
            val actualPass = groupPass.ifEmpty { config.password }
            Log.i(TAG, "group formed ssid=$actualSsid goIp=$goIp")

            // Backup dashboard goes up FIRST, before the main proxies, so it
            // is already reachable even if a main proxy bind fails. It runs
            // on its own port/scope and survives proxy crashes + restarts.
            startBackupPanel(goIp, config)
            proxyDownNotified = false

            val hybrid = config.isHybrid()
            val httpMode = config.effectiveMode() == "http"
            AppState.httpMode.value = httpMode || hybrid

            when {
                httpMode -> {
                    updateStatus("Starting HTTP proxy on $goIp:${config.httpPort}...")
                    val server = HttpProxyServer(
                        port = config.httpPort,
                        context = this,
                        goIp = goIp,
                        panelPort = config.panelPort,
                        onLog = { updateStatus("  $it") },
                        onStaleDetected = { restartProxy() }
                    )
                    http = server
                    server.start()
                    Log.i(TAG, "HTTP proxy started on $goIp:${config.httpPort}")
                    startSocks4(goIp, config)
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                    updateStatus("RUNNING (HTTP) - connect to '$actualSsid' then HTTP proxy $goIp:${config.httpPort} and SOCKS4 $goIp:${config.socks4Port}")
                    updateNotification(actualSsid, actualPass, goIp, config.httpPort, 0, false, config.panelPort, backupPanelPortActual)
                    Log.i(TAG, "proxy_started mode=http port=${config.httpPort}")
                }
                hybrid -> {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                    updateStatus("Starting HTTP proxy on $goIp:${config.httpPort}...")
                    val server = HttpProxyServer(
                        port = config.httpPort,
                        context = this,
                        goIp = goIp,
                        panelPort = config.panelPort,
                        onLog = { updateStatus("  $it") },
                        onStaleDetected = { restartProxy() }
                    )
                    http = server
                    server.start()
                    Log.i(TAG, "HTTP proxy started on $goIp:${config.httpPort}")
                    startSocks4(goIp, config)
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                    updateStatus("RUNNING (HYBRID) - connect to '$actualSsid' then SOCKS5 $goIp:${config.port} and HTTP $goIp:${config.httpPort} and SOCKS4 $goIp:${config.socks4Port}")
                    updateNotification(actualSsid, actualPass, goIp, config.port, config.httpPort, true, config.panelPort, backupPanelPortActual)
                    Log.i(TAG, "proxy_started mode=hybrid port=${config.port} http_port=${config.httpPort}")
                }
                else -> {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                    startSocks4(goIp, config)
                    AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                    updateStatus("RUNNING - connect to '$actualSsid' then SOCKS5 $goIp:${config.port} and SOCKS4 $goIp:${config.socks4Port}")
                    updateNotification(actualSsid, actualPass, goIp, config.port, 0, false, config.panelPort, backupPanelPortActual)
                    Log.i(TAG, "proxy_started mode=socks5 port=${config.port}")
                }
            }
            // Control panel runs on its own port + own thread pool, independent of
            // the proxy traffic, so it stays responsive even when the proxy is
            // saturated by a heavy page. Started in every mode (it only serves
            // local content and never touches the egress network).
            if (config.panelEnabled) {
                val ps = PanelServer(
                    port = config.panelPort,
                    context = this,
                    enabled = true,
                    onLog = { updateStatus("  $it") },
                    onRestartRequest = { handlePanelRestart() }
                )
                panel = ps
                ps.start()
                Log.i(TAG, "Control panel started on $goIp:${config.panelPort}")
                updateStatus("Panel: http://$goIp:${config.panelPort}/")
            }
            if (backupPanelPortActual > 0) {
                updateStatus("Backup panel (survives proxy crash): http://$goIp:$backupPanelPortActual/")
                // Refresh apInfo/notification once more so both ports are visible.
                AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = backupPanelPortActual)
                updateNotification(
                    AppState.apInfo.value.ssid.ifEmpty { actualSsid },
                    AppState.apInfo.value.passphrase.ifEmpty { actualPass },
                    goIp,
                    if (httpMode && !hybrid) config.httpPort else config.port,
                    if (hybrid) config.httpPort else 0,
                    hybrid,
                    config.panelPort,
                    backupPanelPortActual
                )
            }

            // client count poller + group-keepalive + proxy health (every 5s)
            var groupRecreateGuard = false
            var groupRecreateCount = 0
            val groupRecreateMax = 21
            var lastRx = readP2pBytes()?.first ?: -1L
            var lastTx = readP2pBytes()?.second ?: -1L
            var lastNetT = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && started.get()) {
                delay(5000)
                // Hotspot interface throughput: delta of rx/tx byte counters
                // over the poll interval. Resets/wraps (new < old) yield 0.
                try {
                    val now = System.currentTimeMillis()
                    val sample = readP2pBytes()
                    if (sample != null && lastRx >= 0 && lastTx >= 0) {
                        val dt = (now - lastNetT).coerceAtLeast(1) / 1000.0
                        val dRx = (sample.first - lastRx).coerceAtLeast(0)
                        val dTx = (sample.second - lastTx).coerceAtLeast(0)
                        AppState.netDownBps = (dRx * 8 / dt).toLong()
                        AppState.netUpBps = (dTx * 8 / dt).toLong()
                    } else if (sample == null) {
                        AppState.netDownBps = 0L
                        AppState.netUpBps = 0L
                    }
                    if (sample != null) {
                        lastRx = sample.first
                        lastTx = sample.second
                        lastNetT = now
                    }
                } catch (_: Exception) {
                }
                // Self-heal the backup dashboard: if it died, bring it back so
                // there is always a restart path over WiFi Direct.
                if ((backupPanel == null || backupPanel?.isRunning() != true) && started.get()) {
                    val lastGoIp = AppState.apInfo.value.goIp.ifEmpty { goIp }
                    startBackupPanel(lastGoIp, config)
                }
                // Proxy health probe: if all expected proxy ports refuse
                // localhost connections, the proxy is down. The WiFi Direct
                // group may still be up (group != null below confirms it on
                // the next poll) - point the user at the backup panel which
                // is still serving on its own port/scope.
                if (isMainProxyDown(config)) {
                    val bPort = backupPanelPortActual
                    val bUrl = if (bPort > 0) "Backup restart: http://${AppState.apInfo.value.goIp.ifEmpty { goIp }}:$bPort/" else "Backup panel unavailable - toggle proxy off/on in app"
                    // Only overwrite the status line when we are not already
                    // reporting group re-forming; group state is resolved below.
                    if (!proxyDownNotified) {
                        proxyDownNotified = true
                        Log.w(TAG, "Main proxy ports closed but service alive - backup panel at $bUrl")
                    }
                    AppState.status.value = "PROXY DOWN - WiFi Direct still up. $bUrl"
                } else {
                    if (proxyDownNotified) {
                        proxyDownNotified = false
                        Log.i(TAG, "Main proxy recovered - clearing PROXY DOWN state")
                    }
                }
                p2p.requestGroupInfo { g ->
                    if (g == null) {
                        // Android silently tears down the P2P group on inactivity
                        // (no connected client / no traffic) even though the wifi
                        // radio stays on. Recreating it rebuilds the underlying
                        // network interface, which kills any TCP socket a client
                        // has open to us. Previously this only ran for socks5/hybrid
                        // because recreating drops in-flight HTTP connections - but
                        // leaving HTTP mode's group dead forever (status still says
                        // RUNNING while 192.168.49.1 is unreachable) is worse: the
                        // client's browser just redials on the next request anyway.
                        if (!groupRecreateGuard && started.get()) {
                            if (!config.keepRetryingReform && groupRecreateCount >= groupRecreateMax) {
                                // Already retried the cap number of times and the
                                // "keep retrying" toggle is off; stop spamming
                                // recreation and leave it dead.
                                AppState.status.value = "RUNNING - AP gave up re-forming (max $groupRecreateMax retries)"
                                return@requestGroupInfo
                            }
                            groupRecreateGuard = true
                            groupRecreateCount++
                            Log.w(TAG, "WiFi Direct group lost (inactivity) - recreating to keep AP alive (retry $groupRecreateCount/$groupRecreateMax)")
                            scope.launch {
                                recreateGroup(p2p, config)
                                groupRecreateGuard = false
                            }
                        }
                        AppState.apInfo.value = AppState.apInfo.value.copy(clients = 0)
                        AppState.lanClients.value = emptyList()
                        ClientUsage.reset()
                        AppState.status.value = "RUNNING - AP re-forming..."
                    } else {
                        val n = g.clientList?.size ?: 0
                        AppState.apInfo.value = AppState.apInfo.value.copy(clients = n)
                        try {
                            val usage = ClientUsage.snapshotMb()
                            AppState.lanClients.value = (g.clientList ?: emptyList()).map { d ->
                                val mac = d?.deviceAddress ?: ""
                                val ip = arpLookup(mac)
                                val rawName = d?.deviceName?.trim() ?: ""
                                LanClient(
                                    name = rawName.ifEmpty { "Unknown device" },
                                    ip = ip.ifEmpty { mac.ifEmpty { "?" } },
                                    mb = usage[ip] ?: 0.0
                                )
                            }.sortedByDescending { it.mb }
                        } catch (_: Exception) {
                        }
                        if (proxyDownNotified && backupPanelPortActual > 0) {
                            val bIp = AppState.apInfo.value.goIp
                            AppState.status.value = "PROXY DOWN - WiFi Direct still up (clients: $n). Restart: http://$bIp:$backupPanelPortActual/"
                        } else {
                            AppState.status.value = "RUNNING - clients connected: $n"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // A cancelled coroutine (normal shutdown / restart) is not an error.
            if (e is kotlinx.coroutines.CancellationException) return
            Log.e(TAG, "pipeline error", e)
            updateStatus("ERROR: ${e.message}")
            stopSelf()
        }
    }

    private fun startFileWatcher() {
        val watched = ConfigManager.externalConfigFile(this)
        if (!watched.exists()) ConfigManager.mirrorToExternal(this)
        fileObserver = object : FileObserver(watched.absolutePath) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FileObserver.CLOSE_WRITE != 0 && started.get()) {
                    restartJob?.cancel()
                    restartJob = scope.launch {
                        delay(1200)
                        ConfigManager.mirrorToExternal(this@ProxyService)
                        restartProxy()
                    }
                }
            }
        }.apply { startWatching() }
    }

    /**
     * Starts the SOCKS4 backward-compatibility server on its own port. Runs in
     * every mode (socks5/http/hybrid) so legacy SOCKS4/SOCKS4a clients can use
     * the proxy alongside SOCKS5 and HTTP.
     */
    private fun startSocks4(goIp: String, config: AppConfig) {
        updateStatus("Starting SOCKS4 proxy on $goIp:${config.socks4Port}...")
        val server = Socks4Server(
            port = config.socks4Port,
            context = this,
            onLog = { updateStatus("  $it") }
        )
        socks4 = server
        server.start()
        Log.i(TAG, "SOCKS4 started on $goIp:${config.socks4Port}")
    }

    private fun handlePanelRestart() {
        val cfg = ConfigManager.load(this)
        if (cfg.requireApprovalRestart) {
            PanelApproval.submit(mapOf("action" to "restart"))
        } else {
            restartProxy()
        }
    }

    /**
     * Starts the emergency backup dashboard on its own port/scope. Safe to
     * call twice: if one is already running it is kept as-is. The bound
     * port (with +1..+5 fallback) is stored in [backupPanelPortActual] and
     * mirrored into AppState so the main panel + notification can link it.
     */
    private fun startBackupPanel(goIp: String, config: AppConfig) {
        try {
            if (backupPanel?.isRunning() == true && backupPanelPortActual > 0) {
                AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = backupPanelPortActual)
                return
            }
            runCatching { backupPanel?.stop() }
            backupPanel = null
            val wanted = config.backupPanelPort.coerceIn(1, 65535)
            val server = BackupPanelServer(
                requestedPort = wanted,
                context = this,
                onRestartRequest = { handlePanelRestart() },
                onLog = { updateStatus("  $it") }
            )
            val bound = server.start()
            if (bound > 0) {
                backupPanel = server
                backupPanelPortActual = bound
                AppState.apInfo.value = AppState.apInfo.value.copy(
                    goIp = goIp.ifEmpty { AppState.apInfo.value.goIp },
                    backupPanelPort = bound
                )
                Log.i(TAG, "Backup panel started on $goIp:$bound (survives proxy crash)")
                updateStatus("Backup panel: http://$goIp:$bound/ (use if proxy goes down)")
            } else {
                backupPanelPortActual = 0
                Log.w(TAG, "Backup panel failed to bind (wanted $wanted)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "startBackupPanel failed: ${e.message}")
            backupPanelPortActual = 0
        }
    }

    /**
     * Localhost probe of the expected main-proxy traffic ports. Returns true
     * when NONE of them accept a connection (proxy down). The backup panel
     * port is deliberately excluded - it staying up is the whole point.
     */
    private fun isMainProxyDown(config: AppConfig): Boolean {
        return try {
            val mode = config.effectiveMode()
            val ports = when (mode) {
                "http" -> listOf(config.httpPort, config.socks4Port)
                "socks5" -> listOf(config.port, config.socks4Port)
                else -> listOf(config.port, config.httpPort, config.socks4Port)
            }
            // No proxy objects yet (still starting) -> not "down", just booting.
            if (socks == null && http == null && socks4 == null && panel == null) return false
            for (p in ports) {
                if (isLocalPortOpen(p)) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isLocalPortOpen(port: Int): Boolean {
        if (port <= 0 || port > 65535) return false
        return try {
            java.net.Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads (rxBytes, txBytes) for the Wi-Fi Direct group interface from
     * sysfs (world-readable, no root). Returns null when no P2P interface
     * exists yet or counters are unreadable.
     */
    private fun readP2pBytes(): Pair<Long, Long>? {
        for (iface in listOf("p2p0", "p2p-wlan0-0", "p2p-wlan0-1", "p2p-wlan0-2")) {
            try {
                val rx = java.io.File("/sys/class/net/$iface/statistics/rx_bytes").takeIf { it.exists() }
                    ?.readText()?.trim()?.toLongOrNull() ?: continue
                val tx = java.io.File("/sys/class/net/$iface/statistics/tx_bytes")
                    .readText().trim().toLongOrNull() ?: continue
                return rx to tx
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Resolves a P2P peer MAC to its LAN IP via /proc/net/arp
     * (world-readable). Matches only entries on a P2P interface with the
     * complete flag (0x2). Returns "" when unknown.
     */
    private fun arpLookup(mac: String): String {
        val want = mac.lowercase()
        if (want.isEmpty()) return ""
        return try {
            java.io.File("/proc/net/arp").readLines().asSequence()
                .drop(1)
                .mapNotNull { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size < 6) null
                    else Triple(p[0], p[2], p[3].lowercase() to p[5])
                }
                .firstOrNull { (_, flags, hwDev) ->
                    flags == "0x2" && hwDev.second.startsWith("p2p") && hwDev.first == want
                }?.first ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Re-create the WiFi Direct group in place (without tearing down the SOCKS5 /
     * HTTP proxy servers) after Android dropped it due to inactivity. Uses the
     * same SSID/passphrase so any reconnecting client just sees the AP come back.
     */
    private suspend fun recreateGroup(p2p: WifiDirectManager, config: AppConfig) {
        if (!started.get()) return
        p2p.removeExistingGroup {
            val band = if (config.disableBandSelector) "2.4" else config.band
            p2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                Log.i(TAG, "recreateGroup createGroup ok=$ok msg=$msg band=$band")
            }
        }
        var formed = false
        for (i in 0 until 30) {
            delay(500)
            if (!started.get()) return
            var got = false
            p2p.requestGroupInfo { g -> got = true; if (g != null) formed = true }
            var loop = 0
            while (!got && loop < 20) { delay(50); loop++ }
            if (formed) break
        }
        if (formed) {
            p2p.requestGroupInfo { g ->
                if (g != null) {
                    val goIp = p2p.getGroupOwnerIp()
                    val hybrid = config.isHybrid()
                    val httpMode = config.effectiveMode() == "http"
                    AppState.apInfo.value = AppState.apInfo.value.copy(
                        ssid = g.networkName,
                        passphrase = g.passphrase,
                        goIp = goIp
                    )
                    updateNotification(
                        g.networkName, g.passphrase, goIp,
                        if (httpMode) config.httpPort else config.port,
                        if (hybrid) config.httpPort else 0,
                        hybrid,
                        config.panelPort,
                        backupPanelPortActual
                    )
                    Log.i(TAG, "WiFi Direct group recreated (kept alive) ssid=${g.networkName} goIp=$goIp")
                }
            }
        } else {
            Log.w(TAG, "recreateGroup failed to reform group in time")
        }
    }

    private fun restartProxy() {
        if (!started.get()) return
        scope.launch {
            Log.i(TAG, "proxy_restart reason=config_changed")
            updateStatus("Config changed, restarting...")
            // NOTE: backupPanel is deliberately NOT stopped here - it stays up
            // over WiFi Direct so there is always a restart path even if the
            // new pipeline fails to bind the main proxy ports.
            runCatching { socks?.stop() }
            runCatching { socks4?.stop() }
            runCatching { http?.stop() }
            runCatching { panel?.stop() }
            socks = null
            socks4 = null
            http = null
            panel = null
            proxyDownNotified = false
            val p2p = WifiDirectManager(this@ProxyService)
            p2p.removeGroup { }
            delay(1500)
            runPipeline()
        }
    }

    override fun onDestroy() {
        started.set(false)
        ProxyState.setShouldRun(this, false)
        cancelWatchdog(this)
        Log.i(TAG, "proxy_stopped")
        restartJob?.cancel()
        keepAliveJob?.cancel()
        runCatching { fileObserver?.stopWatching() }
        runCatching { socks?.stop() }
        runCatching { socks4?.stop() }
        runCatching { http?.stop() }
        runCatching { panel?.stop() }
        runCatching { backupPanel?.stop() }
        socks = null
        socks4 = null
        http = null
        panel = null
        backupPanel = null
        backupPanelPortActual = 0
        proxyDownNotified = false
        runCatching { WifiDirectManager(this).removeGroup() }
        releaseLocks()
        scope.cancel()
        AppState.running.value = false
        AppState.httpMode.value = false
        AppState.status.value = "Stopped"
        AppState.apInfo.value = ApInfo()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sacram:proxy").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sacram:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Sacram Proxy", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the WiFi Direct UDP proxy alive"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Sacram UDP Proxy")
            .setContentText("Running - see app for connection details")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    private fun updateNotification(ssid: String, pass: String, ip: String, socksPort: Int, httpPort: Int, hybrid: Boolean, panelPort: Int = 0, backupPort: Int = 0) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val panelLine = if (panelPort > 0) "\nPanel: $ip:$panelPort" else ""
        val backupLine = if (backupPort > 0) "\nBackup: $ip:$backupPort" else ""
        val detail = if (hybrid) {
            "SSID: $ssid\nSOCKS5: $ip:$socksPort\nHTTP: $ip:$httpPort\nPassword: $pass$panelLine$backupLine"
        } else {
            "SSID: $ssid\nIP: $ip:$socksPort\nPassword: $pass$panelLine$backupLine"
        }
        val summary = if (hybrid) "$ssid | SOCKS5 $ip:$socksPort | HTTP $ip:$httpPort | pass: $pass"
        else "$ssid | $ip:$socksPort | pass: $pass"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Sacram UDP Proxy - RUNNING")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "STOP", PendingIntent.getService(
                this, 1,
                Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun updateStatus(msg: String) {
        AppState.status.value = msg
    }
}
