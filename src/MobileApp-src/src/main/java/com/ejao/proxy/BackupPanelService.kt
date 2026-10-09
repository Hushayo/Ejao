package com.ejao.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

/**
 * Independent host for the emergency backup panel.
 *
 * Why this exists as its own Service: the backup panel used to be owned by
 * [ProxyService] and was only started AFTER the WiFi Direct group formed. So
 * when group creation failed with "WiFi Direct error", the status text told
 * the user to open the backup panel - but no access point existed, the backup
 * server was never even bound, and the URL was unreachable by definition.
 * Worse, anything that killed the proxy Service took the backup down with it.
 *
 * This service fixes both halves:
 * - It starts the [BackupPanelServer] immediately on START (no WiFi Direct
 *   dependency), binds 0.0.0.0 so it listens on every local interface, and
 *   keeps it up across proxy restarts/crashes. It is only stopped on an
 *   explicit user STOP (or uninstall) - never by restartProxy().
 * - Its restart button does not assume WiFi is up: it asks [ProxyService] to
 *   restart the whole pipeline (which re-forms the group), instead of
 *   assuming the caller can reach anything over a possibly-dead hotspot.
 *
 * Same process, separate lifecycle: a second foreground Service with its own
 * notification. (A separate `android:process` would isolate crashes harder
 * but would also split AppState/Config into two memory spaces, so the panel
 * could no longer read live status - not worth it.)
 */
class BackupPanelService : Service() {

    companion object {
        const val CHANNEL_ID = "ejao_backup"
        const val NOTIF_ID = 2
        const val ACTION_START = "com.ejao.proxy.backup.START"
        const val ACTION_STOP = "com.ejao.proxy.backup.STOP"
        private const val TAG = "EjaoBackupSvc"

        /** Last port the backup panel successfully bound, 0 = not bound. */
        @Volatile var backupPort: Int = 0
            private set

        fun start(context: Context) {
            try {
                val i = Intent(context, BackupPanelService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                Log.w(TAG, "start blocked: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BackupPanelService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
            backupPort = 0
        }
    }

    private val lock = Any()
    private var server: BackupPanelServer? = null
    // Configured port at the last (re)bind. Guards against rebind flapping:
    // if the bind fell back (e.g. 8285 because 8284 was taken), every START
    // must NOT tear it down retrying the wanted port - only a real config
    // change (wanted != lastWanted) or a dead server triggers a rebind.
    private var lastWanted: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { createChannel() }
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        // Bind immediately: no WiFi Direct, no proxy, no group needed.
        runCatching { ensureBackup() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            synchronized(lock) { stopServerLocked() }
            stopSelf()
            return START_NOT_STICKY
        }
        // START (or null intent after a system kill): (re)bind, picking up
        // config.txt changes to backup_panel_port without a process restart.
        runCatching { ensureBackup() }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) { stopServerLocked() }
        super.onDestroy()
    }

    /**
     * Binds the backup panel if it isn't already on the configured port.
     * Safe to call often (service start, proxy pipeline ticks): a healthy
     * server is left alone, a dead/port-changed one is rebound.
     */
    private fun ensureBackup() {
        synchronized(lock) {
            val wanted = runCatching { ConfigManager.load(this).backupPanelPort }
                .getOrDefault(ConfigManager.defaultConfig.backupPanelPort)
                .coerceIn(1, 65535)
            val cur = server
            if (cur?.isRunning() == true && backupPort > 0 && wanted == lastWanted) {
                publishPort(backupPort)
                return
            }
            stopServerLocked()
            val fresh = BackupPanelServer(
                requestedPort = wanted,
                context = this,
                onRestartRequest = { handleRestartRequest() },
                onLog = { Log.i(TAG, it) }
            )
            val bound = fresh.start()
            lastWanted = wanted
            if (bound > 0) {
                server = fresh
                backupPort = bound
                Log.i(TAG, "Backup panel bound on port $bound (independent of proxy)")
            } else {
                server = null
                backupPort = 0
                Log.w(TAG, "Backup panel failed to bind (wanted $wanted)")
            }
            publishPort(backupPort)
            runCatching { refreshNotification() }
        }
    }

    private fun stopServerLocked() {
        runCatching { server?.stop() }
        server = null
        backupPort = 0
        runCatching {
            AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = 0)
        }
    }

    private fun publishPort(port: Int) {
        if (port > 0) {
            runCatching {
                AppState.apInfo.value = AppState.apInfo.value.copy(backupPanelPort = port)
            }
        }
    }

    /**
     * Restart requested from the backup page. Goes through the normal
     * approval gate when enabled; otherwise asks ProxyService to restart its
     * pipeline (which re-forms the WiFi Direct group first). Never assumes
     * the caller can reach anything over WiFi - the phone-side app and the
     * watchdog provide the other recovery paths when the AP itself is down.
     */
    private fun handleRestartRequest() {
        try {
            val cfg = runCatching { ConfigManager.load(this) }
                .getOrDefault(ConfigManager.defaultConfig)
            if (cfg.requireApprovalRestart) {
                PanelApproval.submit(mapOf("action" to "restart"))
                return
            }
            runCatching { ProxyState.setShouldRun(this, true) }
            val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_RESTART)
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            Log.w(TAG, "restart request failed: ${e.message}")
        }
    }

    // ---- foreground plumbing (own channel, own notification) ----

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Ejao Backup Panel", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Independent emergency panel, stays up when the proxy goes down"
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun refreshNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val port = backupPort
        val ips = localIpv4s().take(3)
        val detail = if (port > 0 && ips.isNotEmpty()) {
            "Backup panel (own service):\n" + ips.joinToString("\n") { "http://$it:$port/" }
        } else if (port > 0) {
            "Backup panel listening on port $port"
        } else {
            "Backup panel failed to bind - restart the app"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartProxy = PendingIntent.getService(
            this, 11,
            Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s)
            .setContentTitle("Ejao Backup Panel - RUNNING")
            .setContentText(
                if (port > 0) "Independent panel on port $port - tap for details" else detail
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "RESTART PROXY", restartProxy)
            .build()
    }

    /** Usable local IPv4s (hotspot, LAN, P2P) so the panel works off any link. */
    private fun localIpv4s(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                ?.flatMap { nif -> nif.inetAddresses.asSequence().map { it to nif.name } }
                ?.filter { (addr, _) -> addr.address.size == 4 && !addr.isLoopbackAddress }
                ?.map { (addr, _) -> addr.hostAddress ?: "" }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.toList()
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
