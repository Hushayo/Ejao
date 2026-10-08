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
    // Daemon beats every POLL (30s); stale after half an interval of silence.
    const val STALE_AFTER_MS = 15 * 60 * 1000L

    fun guardDir(context: Context): File {
        return runCatching { ConfigManager.externalConfigFile(context).parentFile!! }
            .getOrDefault(File(context.getExternalFilesDir(null), "Ejao"))
    }

    fun heartbeatAgeMs(context: Context): Long? {
        return try {
            val f = File(guardDir(context), HEARTBEAT_FILE)
            if (!f.exists()) return null
            val ts = f.readText().trim().toLongOrNull() ?: return null
            (System.currentTimeMillis() - ts).coerceAtLeast(0L)
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
        if (age > STALE_AFTER_MS) {
            return "Guard: DOWN (last beat ${age / 1000}s ago) - re-run the ADB command."
        }
        return "Guard: alive (last beat ${age / 1000}s ago)."
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
    fun adbCommand(context: Context): String {
        val apk = context.applicationInfo.sourceDir
        val dir = guardDir(context).absolutePath
        return "adb shell \"CLASSPATH=$apk app_process / com.ejao.proxy.WifiGuardDaemon --dir $dir --interval $DEFAULT_INTERVAL_SEC > /dev/null 2>&1 &\""
    }
}
