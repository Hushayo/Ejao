package com.ejao.proxy

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
import java.util.concurrent.atomic.AtomicInteger

class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "ejao_proxy"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.ejao.proxy.START"
        const val ACTION_STOP = "com.ejao.proxy.STOP"
        // Restart asked from outside the pipeline (backup panel, notification
        // action): full restartProxy() handoff, serialized by restartGuard.
        const val ACTION_RESTART = "com.ejao.proxy.RESTART"
        private const val TAG = "EjaoService"
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
    @Volatile private var socks: Socks5Server? = null
    @Volatile private var socks4: Socks4Server? = null
    @Volatile private var http: HttpProxyServer? = null
    @Volatile private var panel: PanelServer? = null
    // Mirror of BackupPanelService.backupPort (the independent backup panel
    // owns its own socket - this is only a cache for status/notification
    // text, synced via syncBackupPort()). Never bind/stop it from here.
    @Volatile private var backupPanelPortActual: Int = 0
    private var proxyDownNotified = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fileObserver: FileObserver? = null
    private var restartJob: Job? = null
    private var keepAliveJob: Job? = null
    private var startedAt: Long = 0L
    private var pipelineJob: Job? = null
    private val pipelineGen = AtomicInteger(0)
    // Serializes panel restarts: double-taps + file-watcher + stale-heal must
    // never run overlapping restarts (overlapping removeGroup/createGroup =
    // BUSY failures that used to kill the service after long uptime).
    private val restartGuard = AtomicBoolean(false)
    // Guards the data-plane server pointers (socks/socks4/http/panel).
    // setIdle() runs on PanelServer worker threads while startDataPlane()
    // and restartProxy() run on the IO scope: without this, concurrent
    // Resume + Restart could both see null and double-bind one port (second
    // start throws) or null a reference whose stop() the other side skipped
    // (leaked bound server). Kotlin synchronized() is reentrant so nested
    // startDataPlane() -> startSocks4() on the same thread is safe.
    private val dataPlaneLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        runCatching { createChannel() }
        runCatching { EgressManager.init(this) }
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        // Backup panel FIRST, before any WiFi work: it lives in its own
        // service and must already be bound even if the group below fails.
        runCatching { BackupPanelService.start(this) }
        runCatching { acquireLocks() }
        runCatching { startFileWatcher() }
        runCatching { PanelApproval.onRestart = { restartProxy() } }
        runCatching {
            UpdateChecker.scheduleCheck(this, ConfigManager.ensureConfig(this).updateCheckIntervalHours)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == ACTION_STOP) {
                runCatching { ProxyState.setShouldRun(this, false) }
                runCatching { cancelWatchdog(this) }
                stopSelf()
                return START_NOT_STICKY
            }
            if (intent?.action == ACTION_RESTART) {
                runCatching { ProxyState.setShouldRun(this, true) }
                runCatching { BackupPanelService.start(this) }
                runCatching { syncBackupPort() }
                if (started.get()) {
                    restartProxy()
                    return START_STICKY
                }
                // Proxy fully stopped: fall through to the normal START flow
                // below for a fresh boot (restartProxy() would no-op when
                // the service was never started).
            }
            // Keep the independent backup panel reconciled on every start
            // (picks up backup_panel_port edits without a reinstall).
            runCatching { BackupPanelService.start(this) }
            runCatching { syncBackupPort() }
            if (started.compareAndSet(false, true)) {
                startedAt = System.currentTimeMillis()
                AppState.serviceStartedAt = startedAt
                runCatching { ProxyState.setShouldRun(this, true) }
                runCatching { scheduleWatchdog(this) }
                keepAliveJob = runCatching { KeepAlive.launch(scope, this) }.getOrNull()
                val gen = pipelineGen.incrementAndGet()
                pipelineJob = scope.launch { runPipeline(gen) }
            } else {
                // Service already started (e.g. user tapped START again): if
                // the pipeline died but the service object lives, the tap was
                // a silent no-op. Re-launch unless a restart owns the handoff.
                runCatching { ProxyState.setShouldRun(this, true) }
                if (pipelineJob?.isActive != true && !restartGuard.get()) {
                    Log.i(TAG, "START while pipeline dead - re-launching")
                    runCatching { scheduleWatchdog(this) }
                    if (keepAliveJob?.isActive != true) {
                        keepAliveJob = runCatching { KeepAlive.launch(scope, this) }.getOrNull()
                    }
                    startedAt = System.currentTimeMillis()
                    AppState.serviceStartedAt = startedAt
                    val gen = pipelineGen.incrementAndGet()
                    pipelineJob = scope.launch { runPipeline(gen) }
                }
            }
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
            return START_NOT_STICKY
        }
    }

    private suspend fun runPipeline(myGen: Int = pipelineGen.get()) {
        Log.i(TAG, "runPipeline start, sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}")
        updateStatus("Starting...")
        try {
            val config = ConfigManager.ensureConfig(this)
            if (!started.get()) return

            // Fail fast on port collisions (e.g. hand-edited config.txt set
            // two services on one port): binding would half-fail and lie
            // about RUNNING. Park with a clear message instead of dying.
            val ports = listOf(
                config.port, config.httpPort, config.socks4Port,
                config.panelPort, config.backupPanelPort
            )
            if (ports.size != ports.toSet().size) {
                Log.e(TAG, "proxy_error: port collision in config: $ports")
                updateStatus(
                    "ERROR: ports must all differ " +
                        "(socks=${config.port} http=${config.httpPort} " +
                        "socks4=${config.socks4Port} panel=${config.panelPort} " +
                        "backup=${config.backupPanelPort}) - fix config.txt, then Restart"
                )
                runCatching { scheduleWatchdog(this) }
                while (started.get() && pipelineGen.get() == myGen) delay(5000)
                return
            }

            AppState.running.value = true
            TrafficStats.reset()
            AppState.netMaxBps = 0L
            AppState.netMinBps = 0L
            updateStatus("Checking WiFi...")
            val p2p = WifiDirectManager(this)
            var wifiOk = p2p.ensureWifiOn()
            Log.i(TAG, "ensureWifiOn result=$wifiOk")
            if (!wifiOk) {
                if (config.autoRestartOnWifiReturn) {
                    while (!wifiOk && started.get()) {
                        if (!ConfigManager.load(this@ProxyService).autoRestartOnWifiReturn) break
                        // AprilFool WiFi guard: no-op file when the ADB daemon
                        // isn't running; picked up within ~30s when it is.
                        runCatching { WifiGuard.requestWifiOn(this@ProxyService) }
                        updateStatus("WiFi is off - waiting for it to return (ADB guard requested)...")
                        delay(5000)
                        wifiOk = p2p.ensureWifiOn()
                    }
                }
                if (!wifiOk) {
                    updateStatus("ERROR [01]: WiFi is off - turn on WiFi to start the hotspot")
                    stopSelf()
                    return
                }
            }
            Log.i(TAG, "proxy starting wifiOk=$wifiOk port=${config.port}")

            // Group creation is flaky after long uptime (stale P2P channel,
            // slow removeGroup, BUSY from a previous attempt). Retry several
            // times with backoff instead of stopSelf() on the first failure -
            // stopSelf() used to kill the service + cancel the watchdog, so
            // one slow callback meant manual restart at the phone.
            updateStatus("Creating WiFi Direct group...")
            var groupSsid = ""
            var groupPass = ""
            var groupReady = false
            var lastCreateMsg = ""
            val maxGroupAttempts = 5
            var attempt = 0
            // Config-only restart (ports, mode, panel tweaks, file-watch
            // bounce): if a group is already up with these exact credentials,
            // keep it - tearing down WiFi on every restart is what kicks
            // clients off. Only (re)create when missing or creds differ.
            runCatching {
                val reuseGot = AtomicBoolean(false)
                var seenSsid = ""
                var seenPass = ""
                p2p.requestGroupInfo { g ->
                    reuseGot.set(true)
                    if (g != null) {
                        seenSsid = g.networkName ?: ""
                        seenPass = g.passphrase ?: ""
                    }
                }
                var waitedMs = 0
                while (!reuseGot.get() && waitedMs < 3000) { delay(200); waitedMs += 200 }
                val wantSsid = runCatching { p2p.normalizeSsid(config.ssid) }.getOrDefault(config.ssid)
                if (reuseGot.get() && seenSsid.isNotEmpty() && seenSsid == wantSsid && seenPass == config.password) {
                    Log.i(TAG, "reusing live group $seenSsid (config-only restart, WiFi untouched)")
                    groupSsid = seenSsid
                    groupPass = seenPass
                    groupReady = true
                }
            }
            if (!groupReady) {
                // Fresh pipeline (process restart, update, crash): the dead
                // process may still hold a P2P group that requestGroupInfo can
                // no longer see, so the conditional remove inside the loop
                // skips and every create comes back ERROR until a manual
                // STOP/START clears it. Unconditional remove + settle first.
                Log.i(TAG, "pipeline start: clearing any stale P2P group before first create")
                runCatching { p2p.removeGroup { } }
                delay(4000)
                if (!started.get() || pipelineGen.get() != myGen) return
            }
            while (!groupReady && attempt < maxGroupAttempts) {
                if (!started.get() || pipelineGen.get() != myGen) return
                attempt++
                // Fresh manager per attempt: the old channel can go stale
                // after hours in Doze; a new initialize() recovers it.
                val attemptP2p = if (attempt == 1) p2p else WifiDirectManager(this)
                val createOk = AtomicBoolean(false)
                var createMsg = ""
                val removeDone = AtomicBoolean(false)
                runCatching {
                    attemptP2p.removeExistingGroup {
                        removeDone.set(true)
                        val band = if (config.disableBandSelector) "2.4" else config.band
                        attemptP2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                            createMsg = msg
                            createOk.set(ok)
                            Log.i(TAG, "createGroup attempt=$attempt result ok=$ok msg=$msg band=$band")
                        }
                    }
                }
                // If removeExistingGroup's callback never arrives (dead
                // channel), don't hang forever: fall through and try create
                // directly after a timeout.
                var removeWaited = 0
                while (!removeDone.get() && !createOk.get() && removeWaited < 5000) {
                    delay(200); removeWaited += 200
                }
                if (!removeDone.get() && !createOk.get()) {
                    Log.w(TAG, "removeExistingGroup callback timed out (attempt $attempt) - trying createGroup directly")
                    runCatching {
                        val band = if (config.disableBandSelector) "2.4" else config.band
                        attemptP2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                            createMsg = msg
                            createOk.set(ok)
                            Log.i(TAG, "createGroup direct attempt=$attempt ok=$ok msg=$msg")
                        }
                    }
                }
                var waited = 0
                while (!createOk.get() && waited < 15000) {
                    delay(200); waited += 200
                }
                Log.i(TAG, "createGroup attempt=$attempt waited=${waited}ms ok=${createOk.get()} msg=$createMsg")
                lastCreateMsg = createMsg
                if (!createOk.get()) {
                    updateStatus("Group create failed (attempt $attempt/$maxGroupAttempts) - retrying...")
                    delay(3000L * attempt)
                    continue
                }

                val formed = AtomicBoolean(false)
                var tmpSsid = ""
                var tmpPass = ""
                for (i in 0 until 90) {
                    delay(500)
                    if (!started.get() || pipelineGen.get() != myGen) return
                    val got = AtomicBoolean(false)
                    runCatching {
                        attemptP2p.requestGroupInfo { g ->
                            got.set(true)
                            if (g != null) {
                                formed.set(true)
                                tmpSsid = g.networkName
                                tmpPass = g.passphrase
                            }
                        }
                    }
                    var loop = 0
                    while (!got.get() && loop < 20) { delay(50); loop++ }
                    if (formed.get()) break
                }
                if (!formed.get()) {
                    Log.w(TAG, "group did not form (attempt $attempt/$maxGroupAttempts) - retrying")
                    updateStatus("Group did not form (attempt $attempt/$maxGroupAttempts) - retrying...")
                    // Tear down the half-created group before the next try,
                    // otherwise the next create gets BUSY.
                    runCatching { attemptP2p.removeGroup { } }
                    delay(3000L * attempt)
                    continue
                }
                groupSsid = tmpSsid
                groupPass = tmpPass
                groupReady = true
            }
            if (!groupReady) {
                // The AP never came up, so NO panel (main or backup) is
                // reachable over WiFi right now - the old text ("backup panel
                // still up, tap Restart again") sent users to a hotspot URL
                // their PC could never load. The backup panel IS still bound
                // on the phone (independent BackupPanelService, all local
                // interfaces) and comes back over WiFi the moment the group
                // reforms. Never stopSelf() here - that used to cancel the
                // watchdog + shouldRun, requiring a manual trip to the host.
                Log.e(TAG, "proxy_error: group failed after $maxGroupAttempts attempts: $lastCreateMsg")
                updateStatus("ERROR: $lastCreateMsg (retries exhausted) - AP is DOWN, so no panel is reachable over WiFi. Auto-retrying shortly - or tap Restart in the app on this phone.")
                runCatching { scheduleWatchdog(this) }
                runCatching { BackupPanelService.start(this) }
                runCatching { syncBackupPort() }
                // Auto-retry after a cooldown: the P2P driver often recovers
                // from BUSY/ERROR on its own. Previously this parked forever
                // until a manual tap. A manual Restart bumps pipelineGen and
                // supersedes this generation immediately.
                scope.launch {
                    delay(45_000)
                    if (started.get() && pipelineGen.get() == myGen) {
                        Log.i(TAG, "auto-retrying group creation after cooldown")
                        restartProxy()
                    }
                }
                // Park the pipeline without killing the service; a new
                // restart (panel button, file change) bumps pipelineGen and
                // supersedes this generation.
                while (started.get() && pipelineGen.get() == myGen) delay(5000)
                return
            }

            val goIp = p2p.getGroupOwnerIp()
            val actualSsid = groupSsid.ifEmpty { config.ssid }
            val actualPass = groupPass.ifEmpty { config.password }
            Log.i(TAG, "group formed ssid=$actualSsid goIp=$goIp")

            // Backup panel is owned by BackupPanelService (independent of
            // this pipeline): just make sure it is up and mirror its port.
            // It was already started in onCreate, so normally this is a no-op.
            runCatching { BackupPanelService.start(this) }
            runCatching { syncBackupPort() }
            proxyDownNotified = false

            startDataPlane(config, goIp, actualSsid, actualPass)
            if (config.panelEnabled) {
                val ps = PanelServer(
                    port = config.panelPort,
                    context = this,
                    enabled = true,
                    onLog = { updateStatus("  $it") },
                    onRestartRequest = { handlePanelRestart() },
                    onIdleRequest = { idle -> setIdle(idle) }
                )
                panel = ps
                ps.start()
                Log.i(TAG, "Control panel started on $goIp:${config.panelPort}")
                updateStatus("Panel: http://$goIp:${config.panelPort}/")
            }
            if (backupPanelPortActual > 0) {
                updateStatus("Backup panel (independent service): http://$goIp:$backupPanelPortActual/")
                AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = backupPanelPortActual)
                val hybridNotif = config.isHybrid()
                val httpModeNotif = config.effectiveMode() == "http"
                updateNotification(
                    AppState.apInfo.value.ssid.ifEmpty { actualSsid },
                    AppState.apInfo.value.passphrase.ifEmpty { actualPass },
                    goIp,
                    if (httpModeNotif && !hybridNotif) config.httpPort else config.port,
                    if (hybridNotif) config.httpPort else 0,
                    hybridNotif,
                    config.panelPort,
                    backupPanelPortActual
                )
            }

            val groupRecreateGuard = AtomicBoolean(false)
            var groupRecreateCount = 0
            val groupRecreateMax = 21
            var groupMissStreak = 0
            // Consecutive "group null but p2p interface still addressed"
            // verdicts. Past this we stop trusting the interface and reform
            // anyway, in case an address lingers on a dead interface.
            var ifaceHoldStreak = 0
            var downStreak = 0
            var lastClientCount = -1
            var lastRx = readP2pBytes()?.first ?: -1L
            var lastTx = readP2pBytes()?.second ?: -1L
            var lastNetT = System.currentTimeMillis()
            var tick = 0
            while (currentCoroutineContext().isActive && started.get() && pipelineGen.get() == myGen) {
                delay(1000)
                if (pipelineGen.get() != myGen || !started.get()) return
                tick++
                try {
                    val (rxBps, txBps) = TrafficStats.sampleNow()
                    if (rxBps > 0 || txBps > 0) {
                        AppState.netDownBps = rxBps
                        AppState.netUpBps = txBps
                    } else {
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
                    }
                    val total = AppState.netDownBps + AppState.netUpBps
                    TrafficStats.recordSample(total)
                    AppState.netMaxBps = TrafficStats.maxBps
                    AppState.netMinBps = TrafficStats.minActiveBps
                } catch (_: Exception) {
                }
                // Heavy work stays on ~5s cadence; throughput above is realtime 1s.
                if (tick % 5 != 0) continue
                // Backup panel health: owned by BackupPanelService. If it ever
                // reports unbound, ask it to rebind and re-mirror the port.
                if (BackupPanelService.backupPort > 0) {
                    runCatching { syncBackupPort() }
                } else if (started.get()) {
                    runCatching { BackupPanelService.start(this) }
                    runCatching { syncBackupPort() }
                }
                // Idle pauses the data plane on purpose: closed proxy ports
                // are expected, so skip the PROXY DOWN detector entirely.
                if (!AppState.isIdle.value && isMainProxyDown(config)) {
                    downStreak++
                    if (downStreak >= 2) {
                        val bPort = backupPanelPortActual
                        val bUrl = if (bPort > 0) "Backup restart: http://${AppState.apInfo.value.goIp.ifEmpty { goIp }}:$bPort/" else "Backup panel unavailable - toggle proxy off/on in app"
                        if (!proxyDownNotified) {
                            proxyDownNotified = true
                            Log.w(TAG, "Main proxy ports closed but service alive - backup panel at $bUrl")
                        }
                        AppState.status.value = "PROXY DOWN - WiFi Direct still up. $bUrl"
                    }
                } else {
                    downStreak = 0
                    if (proxyDownNotified) {
                        proxyDownNotified = false
                        Log.i(TAG, "Main proxy recovered - clearing PROXY DOWN state")
                    }
                }
                p2p.requestGroupInfo { g ->
                    if (g == null) {
                        groupMissStreak++
                        if (groupMissStreak < 3) {
                            AppState.status.value =
                                "AP signal lost - holding connections ($groupMissStreak/3)..."
                            return@requestGroupInfo
                        }
                        // requestGroupInfo can lie transiently on Vivo (null
                        // while clients stay associated and the AP is fine).
                        // If a P2P interface still holds an address, the group
                        // is alive - destroying it here is what causes the
                        // BUSY storm, so hold instead of recreating.
                        if (p2p.hasP2pAddress()) {
                            ifaceHoldStreak++
                            if (ifaceHoldStreak <= 6) {
                                Log.w(TAG, "group info null but p2p iface still up ($ifaceHoldStreak/6) - holding, not recreating")
                                groupMissStreak = 0
                                AppState.status.value = "AP signal flicker - holding connections..."
                                return@requestGroupInfo
                            }
                            Log.w(TAG, "group info null for a while despite p2p addr - reforming anyway")
                        }
                        ifaceHoldStreak = 0
                        AppState.isReforming.value = true
                        AppState.status.value = "AP re-forming - draining..."
                        if (started.get() && groupRecreateGuard.compareAndSet(false, true)) {
                            if (!config.keepRetryingReform && groupRecreateCount >= groupRecreateMax) {
                                groupRecreateGuard.set(false)
                                AppState.status.value = "RUNNING - AP gave up re-forming (max $groupRecreateMax retries)"
                                return@requestGroupInfo
                            }
                            groupRecreateCount++
                            Log.w(TAG, "WiFi Direct group lost (inactivity) - recreating to keep AP alive (retry $groupRecreateCount/$groupRecreateMax)")
                            scope.launch {
                                try {
                                    recreateGroup(p2p, config)
                                } finally {
                                    groupRecreateGuard.set(false)
                                }
                            }
                        }
                        AppState.apInfo.value = AppState.apInfo.value.copy(clients = 0)
                        AppState.lanClients.value = emptyList()
                        ClientUsage.reset()
                        AppState.status.value = "RUNNING - AP re-forming..."
                    } else {
                        groupMissStreak = 0
                        ifaceHoldStreak = 0
                        AppState.isReforming.value = false
                        try {
                            val goIpNow = AppState.apInfo.value.goIp.ifEmpty { goIp }
                            val usage = ClientUsage.snapshotMb()
                            val arp = readArpTable()
                            val p2pDevices = (g.clientList ?: emptyList()).mapNotNull { d ->
                                val mac = (d?.deviceAddress ?: "").trim()
                                if (mac.isEmpty()) null
                                else mac to (d?.deviceName?.trim() ?: "")
                            }
                            val p2pMacs = p2pDevices.map { it.first.lowercase() }.toSet()
                            val p2pNameByMac = p2pDevices.associate { it.first.lowercase() to it.second }
                            // MAC -> IP from ARP (preferred: p2p/wlan interfaces, fallback any).
                            val ipByMac = mutableMapOf<String, String>()
                            val macByIp = mutableMapOf<String, String>()
                            for ((ip, mac, _) in arp) {
                                if (ip.isEmpty() || mac.isEmpty()) continue
                                if (ip == goIpNow || ip == "192.168.49.1") continue
                                val ml = mac.lowercase()
                                if (ipByMac[ml].isNullOrEmpty()) ipByMac[ml] = ip
                                if (macByIp[ip].isNullOrEmpty()) macByIp[ip] = mac
                            }
                            class Merged(var mac: String, var ip: String, var name: String, var mb: Double)
                            val merged = linkedMapOf<String, Merged>()
                            fun keyForEntry(mac: String, ip: String): String {
                                val ml = mac.lowercase()
                                if (ml.isNotEmpty() && ml.contains(":")) return "mac:$ml"
                                if (ip.isNotEmpty()) return "ip:$ip"
                                return ""
                            }
                            // 1) P2P members first (authoritative membership).
                            for ((mac, rawName) in p2pDevices) {
                                val ml = mac.lowercase()
                                val ip = ipByMac[ml] ?: ""
                                val custom = runCatching { DeviceNames.get(this@ProxyService, mac, ip) }.getOrNull()
                                val display = custom ?: rawName.ifEmpty { "Unknown device" }
                                val mb = if (ip.isNotEmpty()) usage[ip] ?: 0.0 else 0.0
                                merged["mac:$ml"] = Merged(mac, ip, display, mb)
                            }
                            // 2) ARP entries with traffic or P2P membership (real devices, even if P2P list lags).
                            for ((ip, mac, _) in arp) {
                                if (ip == goIpNow || ip == "192.168.49.1") continue
                                val ml = mac.lowercase()
                                val k = if (ml.contains(":")) "mac:$ml" else "ip:$ip"
                                val existing = merged[k]
                                if (existing != null) {
                                    if (existing.ip.isEmpty()) existing.ip = ip
                                    if (existing.mac.isEmpty()) existing.mac = mac
                                    val u = usage[ip]
                                    if (u != null && u > existing.mb) existing.mb = u
                                } else {
                                    // Only surface ARP rows that are P2P members or have real usage.
                                    val u = usage[ip] ?: 0.0
                                    val isP2p = ml in p2pMacs
                                    if (!isP2p && u <= 0.0) continue
                                    val custom = runCatching { DeviceNames.get(this@ProxyService, mac, ip) }.getOrNull()
                                    val p2pName = p2pNameByMac[ml] ?: ""
                                    val display = custom ?: p2pName.ifEmpty { "Unknown device" }
                                    merged[k] = Merged(mac, ip, display, u)
                                }
                            }
                            // 3) Usage orphans (traffic from an IP ARP hasn't mapped yet) — never the gateway.
                            for ((ip, mb) in usage) {
                                if (mb <= 0.0) continue
                                if (ip == goIpNow || ip == "192.168.49.1") continue
                                val mac = macByIp[ip] ?: ""
                                val k = keyForEntry(mac, ip)
                                if (k.isEmpty() || merged.containsKey(k)) {
                                    // Attribute usage to existing MAC entry if IP now resolves to it.
                                    if (k.isNotEmpty()) {
                                        val e = merged[k]
                                        if (e != null && mb > e.mb) e.mb = mb
                                    }
                                    continue
                                }
                                // If this IP belongs to an already-listed MAC via another IP, skip (one row per device).
                                if (mac.isNotEmpty() && merged.containsKey("mac:${mac.lowercase()}")) continue
                                val custom = runCatching { DeviceNames.get(this@ProxyService, mac, ip) }.getOrNull()
                                val p2pName = if (mac.isNotEmpty()) p2pNameByMac[mac.lowercase()] ?: "" else ""
                                merged[k] = Merged(mac, ip, custom ?: p2pName.ifEmpty { "Unknown device" }, mb)
                            }
                            val list = merged.values.map { m ->
                                val id = DeviceNames.keyFor(m.mac, m.ip).ifEmpty { m.ip.ifEmpty { m.mac } }
                                LanClient(name = m.name, ip = m.ip.ifEmpty { m.mac.ifEmpty { "?" } }, mb = m.mb, mac = m.mac, id = id)
                            }.sortedByDescending { it.mb }
                            val realCount = list.size
                            AppState.apInfo.value = AppState.apInfo.value.copy(clients = realCount)
                            AppState.lanClients.value = list
                            // P2P keepalive: Vivo kills idle GO groups within
                            // minutes even with clients associated (phone-side
                            // keepalive ping goes over cellular, so the P2P
                            // link looks dead). Ping clients + our GO IP every
                            // ~20s so the link always carries traffic - same
                            // trick as PC-side ping -t, but self-sufficient.
                            if (tick % 20 == 0) {
                                val targets = (list.mapNotNull {
                                    it.ip.takeIf { ip -> ip.contains(".") }
                                } + goIpNow).filter { it.isNotEmpty() }
                                    .distinct().take(8)
                                if (targets.isNotEmpty()) {
                                    scope.launch {
                                        runCatching {
                                            kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO
                                            ) {
                                                for (ip in targets) {
                                                    runCatching {
                                                        java.net.InetAddress.getByName(ip)
                                                            .isReachable(1500)
                                                    }
                                                }
                                            }
                                        }
                                        Log.i(TAG, "p2p keepalive pinged ${targets.size} target(s)")
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                        if (proxyDownNotified && backupPanelPortActual > 0) {
                            val bIp = AppState.apInfo.value.goIp
                            val c = AppState.apInfo.value.clients
                            val msg = "PROXY DOWN - WiFi Direct still up (clients: $c). Restart: http://$bIp:$backupPanelPortActual/"
                            if (AppState.status.value != msg) AppState.status.value = msg
                            lastClientCount = c
                        } else {
                            val c = AppState.apInfo.value.clients
                            // Don't overwrite the IDLE banner when the head
                            // count shifts while paused; just track it.
                            if (AppState.isIdle.value) {
                                lastClientCount = c
                            } else if (c != lastClientCount || proxyDownNotified) {
                                lastClientCount = c
                                AppState.status.value = "RUNNING - clients connected: $c"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) return
            // Never stopSelf() here: this catches transient slips in the
            // monitoring loop (bad ARP line, stale client snapshot). Killing
            // the service over those cancelled the watchdog + shouldRun and
            // stranded the user. Stay alive on the backup panel instead; a
            // panel restart (new pipelineGen) supersedes this generation.
            Log.e(TAG, "pipeline error (staying alive)", e)
            updateStatus("ERROR: ${e.message} - tap Restart in the app to recover")
            runCatching { scheduleWatchdog(this) }
            while (started.get() && pipelineGen.get() == myGen) delay(5000)
            return
        }
    }

    private fun startFileWatcher() {
        try {
            val watched = ConfigManager.externalConfigFile(this)
            if (!watched.exists()) runCatching { ConfigManager.mirrorToExternal(this) }
            // NOTE: never mirrorToExternal() here - that writes the very file
            // being watched and self-triggers an endless restart loop (each
            // restart re-mirrors -> CLOSE_WRITE -> restart ...), which kept
            // kicking WiFi off every few minutes all night. load() reads the
            // internal file first, so no mirror is needed to pick up changes.
            var lastHandledSig = ""
            fun sigOf(f: java.io.File): String =
                runCatching { "${f.lastModified()}:${f.length()}" }.getOrDefault("")
            lastHandledSig = sigOf(watched)
            fileObserver = object : FileObserver(watched.absolutePath) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and FileObserver.CLOSE_WRITE != 0 && started.get()) {
                        restartJob?.cancel()
                        restartJob = scope.launch {
                            delay(1200)
                            val sig = sigOf(watched)
                            // Unchanged since last handled event: our own echo
                            // or a duplicate notification - ignore, don't bounce WiFi.
                            if (sig == lastHandledSig) return@launch
                            lastHandledSig = sig
                            runCatching { restartProxy() }
                        }
                    }
                }
            }.apply { runCatching { startWatching() } }
        } catch (e: Exception) {
            Log.w(TAG, "file watcher unavailable: ${e.message}")
        }
    }

    private fun startSocks4(goIp: String, config: AppConfig) {
        // Belt-and-braces against double-start (e.g. racing Resume taps):
        // binding an occupied port would kill the fresh server.
        synchronized(dataPlaneLock) {
            if (socks4 != null) return
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
    }

    /**
     * Data plane only: SOCKS5 + HTTP + SOCKS4. Extracted from runPipeline so
     * Idle resume can bring exactly this back without touching the hotspot,
     * panels, locks, or service lifetime.
     */
    private fun startDataPlane(config: AppConfig, goIp: String, actualSsid: String, actualPass: String) {
        val hybrid = config.isHybrid()
        val httpMode = config.effectiveMode() == "http"
        AppState.httpMode.value = httpMode || hybrid

        synchronized(dataPlaneLock) {
        when {
            httpMode -> {
                // Reuse the kept-alive HTTP server on idle resume (it was
                // flagged panel-only, already unflagged by the caller).
                if (http == null) {
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
                }
                startSocks4(goIp, config)
                AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                updateStatus("RUNNING (HTTP) - connect to '$actualSsid' then HTTP proxy $goIp:${config.httpPort} and SOCKS4 $goIp:${config.socks4Port}")
                updateNotification(actualSsid, actualPass, goIp, config.httpPort, 0, false, config.panelPort, backupPanelPortActual)
                Log.i(TAG, "proxy_started mode=http port=${config.httpPort}")
            }
            hybrid -> {
                if (socks == null) {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                }
                if (http == null) {
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
                }
                startSocks4(goIp, config)
                AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                updateStatus("RUNNING (HYBRID) - connect to '$actualSsid' then SOCKS5 $goIp:${config.port} and HTTP $goIp:${config.httpPort} and SOCKS4 $goIp:${config.socks4Port}")
                updateNotification(actualSsid, actualPass, goIp, config.port, config.httpPort, true, config.panelPort, backupPanelPortActual)
                Log.i(TAG, "proxy_started mode=hybrid port=${config.port} http_port=${config.httpPort}")
            }
            else -> {
                if (socks == null) {
                    updateStatus("Starting SOCKS5 proxy on $goIp:${config.port}...")
                    socks = Socks5Server(
                        port = config.port,
                        advertiseIp = goIp,
                        context = this,
                        onLog = { updateStatus("  $it") }
                    ).also { it.start() }
                    Log.i(TAG, "SOCKS5 started on $goIp:${config.port}")
                }
                startSocks4(goIp, config)
                AppState.apInfo.value = ApInfo(actualSsid, actualPass, goIp, 0, config.panelPort, backupPanelPortActual)
                updateStatus("RUNNING - connect to '$actualSsid' then SOCKS5 $goIp:${config.port} and SOCKS4 $goIp:${config.socks4Port}")
                updateNotification(actualSsid, actualPass, goIp, config.port, 0, false, config.panelPort, backupPanelPortActual)
                Log.i(TAG, "proxy_started mode=socks5 port=${config.port}")
            }
        }
        }
    }

    /**
     * Idle toggle from the panel. Runs on panel worker threads (not a
     * coroutine), so data-plane pointer changes are guarded by
     * dataPlaneLock (same lock as startDataPlane/restartProxy/onDestroy).
     *
     * Idle(true): hotspot, panels, locks, watchdog, keepalive all stay up.
     * SOCKS is stopped, but the HTTP server is KEPT running in panel-only
     * mode - browsers that reach the panel through the proxy (Windows manual
     * proxy / self-host forwarding) would otherwise lose the panel too and
     * Resume would become unreachable. Idle(false): data plane back, no P2P
     * dance, instant.
     */
    fun setIdle(idle: Boolean) {
        if (!started.get()) return
        if (idle == AppState.isIdle.value) return
        if (restartGuard.get()) {
            updateStatus("Restart in progress - try Idle again in a few seconds")
            return
        }
        if (idle) {
            if (AppState.apInfo.value.goIp.isEmpty()) {
                updateStatus("Not running yet - nothing to idle")
                return
            }
            // Stop SOCKS entirely; HTTP stays up but panel-only so the
            // panel (including panel-via-proxy) keeps loading for Resume.
            // Locked: Resume/Restart race this path on other threads.
            synchronized(dataPlaneLock) {
                runCatching { socks?.stop() }
                runCatching { socks4?.stop() }
                socks = null
                socks4 = null
                http?.setIdleMode(true)
            }
            AppState.isIdle.value = true
            proxyDownNotified = false
            updateStatus("IDLE - internet paused, hotspot still up. Tap Resume in the panel.")
            refreshNotification()
            Log.i(TAG, "idle on - SOCKS stopped, HTTP panel-only, service alive")
        } else {
            val goIp = AppState.apInfo.value.goIp
            if (goIp.isEmpty()) {
                AppState.isIdle.value = false
                updateStatus("Nothing to resume - start the proxy first")
                return
            }
            val cfg = ConfigManager.load(this)
            val info = AppState.apInfo.value
            // HTTP was kept alive (just flagged): unflag it; startDataPlane
            // fills back whatever is missing and reuses what survived.
            // Locked: a concurrent Restart must not null the pointers mid-resume.
            synchronized(dataPlaneLock) {
                http?.setIdleMode(false)
                startDataPlane(cfg, goIp, info.ssid, info.passphrase)
            }
            AppState.isIdle.value = false
            proxyDownNotified = false
            Log.i(TAG, "idle off - data plane resumed")
        }
    }

    private fun refreshNotification() {
        runCatching {
            val info = AppState.apInfo.value
            val cfg = ConfigManager.load(this)
            val hybrid = cfg.isHybrid()
            val httpMode = cfg.effectiveMode() == "http"
            updateNotification(
                info.ssid, info.passphrase, info.goIp,
                if (httpMode && !hybrid) cfg.httpPort else cfg.port,
                if (hybrid) cfg.httpPort else 0,
                hybrid,
                cfg.panelPort,
                backupPanelPortActual,
                idle = AppState.isIdle.value
            )
        }
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
     * Mirrors BackupPanelService.backupPort into the local cache + AppState.
     * The backup socket itself is owned by BackupPanelService - this never
     * binds or stops anything, so proxy restarts can't strand it.
     */
    private fun syncBackupPort() {
        val b = BackupPanelService.backupPort
        if (b > 0) {
            backupPanelPortActual = b
            runCatching {
                AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = b)
            }
        }
    }

    private fun isMainProxyDown(config: AppConfig): Boolean {
        return try {
            val mode = config.effectiveMode()
            val ports = when (mode) {
                "http" -> listOf(config.httpPort, config.socks4Port)
                "socks5" -> listOf(config.port, config.socks4Port)
                else -> listOf(config.port, config.httpPort, config.socks4Port)
            }
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

    private fun readArpTable(): List<Triple<String, String, String>> {
        return try {
            java.io.File("/proc/net/arp").readLines().asSequence()
                .drop(1)
                .mapNotNull { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size < 6) null
                    else Triple(p[0], p[3].lowercase(), p[5])
                }
                .filter { (_, mac, _) -> mac.contains(":") && mac != "00:00:00:00:00:00" }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun arpLookup(mac: String): String {
        val want = mac.lowercase()
        if (want.isEmpty()) return ""
        val rows = readArpTable()
        // Prefer direct P2P/WLAN interfaces, fall back to any complete entry.
        rows.firstOrNull { (_, m, dev) ->
            m == want && (dev.contains("p2p") || dev.contains("wlan"))
        }?.let { return it.first }
        return rows.firstOrNull { (_, m, _) -> m == want }?.first ?: ""
    }

    private suspend fun recreateGroup(p2p: WifiDirectManager, config: AppConfig) {
        try {
            if (!started.get()) return
            try {
                val band = if (config.disableBandSelector) "2.4" else config.band
                // The P2P stack is usually still tearing down the dead group
                // when we get here, so the first createGroup often comes back
                // BUSY. Clear stale state and back off between attempts
                // instead of hammering a busy stack.
                val backoffsMs = longArrayOf(0L, 5000L, 10000L, 20000L)
                for (attempt in backoffsMs.indices) {
                    if (!started.get()) return
                    if (attempt > 0) delay(backoffsMs[attempt])
                    if (!started.get()) return
                    val accepted = AtomicBoolean(false)
                    var reply = ""
                    p2p.removeExistingGroup {
                        runCatching {
                            p2p.createGroup(config.ssid, config.password, band) { ok, msg ->
                                accepted.set(ok)
                                reply = msg
                                Log.i(TAG, "recreateGroup createGroup ok=$ok msg=$msg band=$band attempt=${attempt + 1}")
                            }
                        }
                    }
                    delay(2000) // let the async callbacks land
                    if (accepted.get()) break
                    // Only P2P_UNSUPPORTED is permanent (device has no WiFi
                    // Direct). BUSY and generic ERROR are both transient -
                    // ERROR clears once the old group teardown finishes, so
                    // back off and retry those too instead of giving up.
                    if (reply.contains("no WiFi Direct support", ignoreCase = true)) break
                    Log.w(TAG, "recreateGroup rejected ($reply) on attempt ${attempt + 1} - backing off")
                }
            } catch (e: Exception) {
                Log.w(TAG, "recreateGroup remove/create failed: ${e.message}")
                return
            }
        val formed = AtomicBoolean(false)
        for (i in 0 until 30) {
            delay(500)
            if (!started.get()) return
            val got = AtomicBoolean(false)
            try {
                p2p.requestGroupInfo { g -> got.set(true); if (g != null) formed.set(true) }
            } catch (e: Exception) {
                Log.w(TAG, "recreateGroup poll failed: ${e.message}")
                return
            }
            var loop = 0
            while (!got.get() && loop < 20) { delay(50); loop++ }
            if (formed.get()) break
        }
        if (formed.get()) {
            try {
                p2p.requestGroupInfo { g ->
                    runCatching {
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
                }
            } catch (e: Exception) {
                Log.w(TAG, "recreateGroup info failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "recreateGroup failed to reform group in time")
        }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "recreateGroup failed: ${e.message}")
        }
    }

    private fun restartProxy() {
        if (!started.get()) return
        // Drop overlapping restarts: panel double-tap / file-watcher /
        // stale-heal racing removeGroup+createGroup was the main source of
        // BUSY -> stopSelf -> dead-until-manual after long sessions.
        if (!restartGuard.compareAndSet(false, true)) {
            Log.i(TAG, "restart already in progress - ignoring duplicate request")
            return
        }
        scope.launch {
            val myGen = pipelineGen.incrementAndGet()
            try {
                Log.i(TAG, "proxy_restart reason=config_changed")
                updateStatus("Restarting proxy...")
                runCatching { ProxyState.setShouldRun(this@ProxyService, true) }
                runCatching { scheduleWatchdog(this@ProxyService) }
                // Re-assert locks: after hours the OS may have dropped them.
                runCatching { acquireLocks() }
                runCatching {
                    pipelineJob?.cancel()
                    pipelineJob?.join()
                }
                if (pipelineGen.get() != myGen || !started.get()) return@launch
                synchronized(dataPlaneLock) {
                    runCatching { socks?.stop() }
                    runCatching { socks4?.stop() }
                    runCatching { http?.stop() }
                    runCatching { panel?.stop() }
                    // NOTE: the backup panel is NOT touched here - it is owned
                    // by the independent BackupPanelService and stays bound
                    // throughout the restart, so a failed restart never
                    // strands the user with no way back.
                    socks = null
                    socks4 = null
                    http = null
                    panel = null
                }
                proxyDownNotified = false
                // A restart means fresh forwarding: never come back stuck idle.
                AppState.isIdle.value = false
                AppState.isReforming.value = false
                runCatching {
                    val p2p = WifiDirectManager(this@ProxyService)
                    p2p.removeGroup { }
                }
                // Give the driver + sockets time to release after a long
                // session (1.5s was too short -> BUSY / bind conflicts).
                delay(3000)
                if (pipelineGen.get() != myGen || !started.get()) return@launch
                // New pipeline = new uptime: the clock previously ran from
                // first service start, so panel uptime never reset on restart.
                startedAt = System.currentTimeMillis()
                AppState.serviceStartedAt = startedAt
                pipelineJob = scope.launch { runPipeline(myGen) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "restart failed", e)
                runCatching { updateStatus("ERROR: restart failed (${e.message}) - tap Restart in the app again") }
                runCatching { scheduleWatchdog(this@ProxyService) }
            } finally {
                restartGuard.set(false)
            }
        }
    }

    override fun onDestroy() {
        // Explicit user STOP clears shouldRun BEFORE stopSelf()/stopService(),
        // so a false here means the user asked to stop everything. A true
        // here means the system destroyed us (kill/restart) while we should
        // still run - the independent backup panel must survive that.
        val explicitStop = !runCatching { ProxyState.shouldRun(this) }.getOrDefault(true)
        started.set(false)
        pipelineGen.incrementAndGet()
        runCatching { ProxyState.setShouldRun(this, false) }
        runCatching { cancelWatchdog(this) }
        Log.i(TAG, "proxy_stopped explicit=$explicitStop")
        restartJob?.cancel()
        pipelineJob?.cancel()
        keepAliveJob?.cancel()
        runCatching { fileObserver?.stopWatching() }
        synchronized(dataPlaneLock) {
            runCatching { socks?.stop() }
            runCatching { socks4?.stop() }
            runCatching { http?.stop() }
            runCatching { panel?.stop() }
            socks = null
            socks4 = null
            http = null
            panel = null
        }
        proxyDownNotified = false
        AppState.isIdle.value = false
        AppState.isReforming.value = false
        runCatching { WifiDirectManager(this).removeGroup() }
        runCatching { EgressManager.shutdown() }
        releaseLocks()
        scope.cancel()
        AppState.running.value = false
        AppState.httpMode.value = false
        if (explicitStop) {
            // Full stop: take the backup panel down too and wipe its display.
            runCatching { BackupPanelService.stop(this) }
            backupPanelPortActual = 0
            AppState.status.value = "Stopped"
            AppState.apInfo.value = ApInfo()
        } else {
            // System-side destroy with shouldRun intact: leave the backup
            // service (and its port display) alone; the watchdog brings the
            // proxy pipeline back.
            runCatching { syncBackupPort() }
            AppState.status.value = "Restarting..."
        }
        super.onDestroy()
    }

    private fun acquireLocks() {
        // Never orphan the previous locks: overwriting them without release
        // lets GC finalize them while held (see "WakeLock finalized while
        // still held" in logcat), which hands the CPU back to Doze and the
        // P2P group dies minutes after the app leaves the foreground.
        // acquireLocks runs on every pipeline (re)start, so release first.
        runCatching { releaseLocks() }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = runCatching {
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ejao:proxy").apply {
                    setReferenceCounted(false)
                    // Indefinite hold (no timeout): the old 10h timeout silently
                    // released the lock on long sessions, letting the CPU sleep
                    // so P2P callbacks stalled and panel restarts died.
                    // Released explicitly in releaseLocks()/onDestroy().
                    acquire()
                }
            }.getOrNull()
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = runCatching {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ejao:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "locks unavailable: ${e.message}")
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
            CHANNEL_ID, "Ejao Proxy", NotificationManager.IMPORTANCE_MIN
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
            .setContentTitle("Ejao UDP Proxy")
            .setContentText("Running - see app for connection details")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    private fun updateNotification(ssid: String, pass: String, ip: String, socksPort: Int, httpPort: Int, hybrid: Boolean, panelPort: Int = 0, backupPort: Int = 0, idle: Boolean = false) {
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
        val title = if (idle) "Ejao UDP Proxy - IDLE" else "Ejao UDP Proxy - RUNNING"
        val fullDetail = (if (idle) "IDLE - internet paused, hotspot still up.\n" else "") + detail
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullDetail))
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
