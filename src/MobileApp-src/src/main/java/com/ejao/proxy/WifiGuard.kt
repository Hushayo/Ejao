package com.ejao.proxy

import android.content.Context
import java.io.File

/**
 * App side of the AprilFool ADB WiFi guard (see WifiGuardDaemon).
 *
 * No sockets, no extra permissions, no helper app: the daemon (shell UID) and
 * the app share two files in the app's external dir, which scoped storage
 * already keeps other apps out of. The app writes requests + reads the
 * heartbeat; the daemon reads requests + writes the heartbeat.
 */
object WifiGuard {
    const val HEARTBEAT_FILE = "guard_heartbeat.txt"
    const val REQUEST_FILE = "guard_request_wifi"
    const val DEFAULT_INTERVAL_SEC = 600L
    const val MIN_INTERVAL_SEC = 60L
    // Daemon beats every POLL (30s); stale after half an interval of silence.
    const val STALE_AFTER_MS = 15 * 60 * 1000L
    private const val PREFS = "wifi_guard"
    private const val KEY_INTERVAL_SEC = "interval_sec"

    /** Interval the next ADB start will use (persisted UI state, seconds). */
    fun intervalSec(context: Context): Long {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SEC)
                .coerceAtLeast(MIN_INTERVAL_SEC)
        } catch (_: Exception) {
            DEFAULT_INTERVAL_SEC
        }
    }

    fun setIntervalSec(context: Context, sec: Long) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_INTERVAL_SEC, sec.coerceAtLeast(MIN_INTERVAL_SEC)).apply()
        }
    }

    fun formatInterval(sec: Long): String = when {
        sec % 3600L == 0L -> "${sec / 3600}h"
        sec % 60L == 0L -> "${sec / 60}m"
        else -> "${sec}s"
    }

    fun guardDir(context: Context): File {
        return runCatching { ConfigManager.externalConfigFile(context).parentFile!! }
            .getOrDefault(File(context.getExternalFilesDir(null), "Ejao"))
    }

    fun heartbeatAgeMs(context: Context): Long? {
        return try {
            val f = File(guardDir(context), HEARTBEAT_FILE)
            if (!f.exists()) return null
            val ts = f.readText().trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                ?: return null
            (System.currentTimeMillis() - ts).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    /** Cadence the running daemon reported in its heartbeat, if any. */
    fun daemonIntervalSec(context: Context): Long? {
        return try {
            val f = File(guardDir(context), HEARTBEAT_FILE)
            if (!f.exists()) return null
            f.readText().trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isAlive(context: Context): Boolean {
        val age = heartbeatAgeMs(context) ?: return false
        return age <= STALE_AFTER_MS
    }

    fun statusLine(context: Context): String {
        val age = heartbeatAgeMs(context)
            ?: return "Guard: DOWN (never started) - run the ADB command from a PC."
        val every = formatInterval(daemonIntervalSec(context) ?: intervalSec(context))
        if (age > STALE_AFTER_MS) {
            return "Guard: DOWN (last beat ${age / 1000}s ago, was every $every) - re-run the ADB command."
        }
        return "Guard: alive (last beat ${age / 1000}s ago, every $every)."
    }

    /** Drops a request file the daemon picks up within ~30s. Never throws. */
    fun requestWifiOn(context: Context): Boolean {
        return try {
            val d = guardDir(context)
            if (!d.exists()) d.mkdirs()
            File(d, REQUEST_FILE).writeText(System.currentTimeMillis().toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    /** One-liner the user pastes into a PC terminal (USB debugging on). */
    fun adbCommand(context: Context, intervalSec: Long): String {
        val apk = context.applicationInfo.sourceDir
        val dir = guardDir(context).absolutePath
        val every = intervalSec.coerceAtLeast(MIN_INTERVAL_SEC)
        return "adb shell \"CLASSPATH=$apk app_process / com.ejao.proxy.WifiGuardDaemon --dir $dir --interval $every > /dev/null 2>&1 &\""
    }
}
