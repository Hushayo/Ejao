package com.ejao.proxy

/**
 * AprilFool experiment: unattended WiFi recovery without a helper app.
 *
 * This entry point never runs inside the app process. It is started once from
 * a PC over USB debugging, which makes it run as the shell UID (2000) instead
 * of the app UID - so `svc wifi enable` actually works, unlike
 * WifiManager.setWifiEnabled (no-op for apps on API 29+).
 *
 * Start it with (paths shown in-app via WifiGuard.adbCommand):
 *   adb shell "CLASSPATH=<base.apk> app_process / com.ejao.proxy.WifiGuardDaemon --dir <guard dir> --interval 600 > /dev/null 2>&1 &"
 *
 * It re-enables WiFi every --interval seconds and whenever the app drops a
 * `guard_request_wifi` file in the guard dir, then deletes the request. Every
 * pass writes `guard_heartbeat.txt` so the app can show alive/down. Re-run the
 * command after every reboot and every app update (the APK path changes).
 * Re-running while an old copy lives is harmless (idempotent, last wins).
 */
object WifiGuardDaemon {

    private const val DEFAULT_INTERVAL_SEC = 600L
    private const val POLL_MS = 30_000L
    private const val MIN_INTERVAL_SEC = 60L
    private const val HEARTBEAT_FILE = "guard_heartbeat.txt"
    private const val REQUEST_FILE = "guard_request_wifi"

    @JvmStatic
    fun main(args: Array<String>) {
        var dirArg: String? = null
        var intervalSec = DEFAULT_INTERVAL_SEC
        var once = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--dir" -> dirArg = args.getOrNull(i + 1).also { i++ }
                "--interval" -> {
                    intervalSec = args.getOrNull(i + 1)?.toLongOrNull()
                        ?.coerceAtLeast(MIN_INTERVAL_SEC) ?: DEFAULT_INTERVAL_SEC
                    i++
                }
                "--once" -> once = true
            }
            i++
        }
        if (dirArg.isNullOrBlank()) {
            println("usage: WifiGuardDaemon --dir <guard dir> [--interval <sec>] [--once]")
            return
        }
        val guardDir = java.io.File(dirArg)
        if (!guardDir.isDirectory && !guardDir.mkdirs()) {
            println("guard: cannot use dir $guardDir")
            return
        }
        println("guard: up dir=$guardDir interval=${intervalSec}s uid=${ownUid()}")
        if (once) {
            enableWifi()
            writeHeartbeat(guardDir)
            return
        }
        var lastPeriodic = 0L
        while (true) {
            try {
                val now = System.currentTimeMillis()
                val req = java.io.File(guardDir, REQUEST_FILE)
                if (req.exists() || now - lastPeriodic >= intervalSec * 1000L) {
                    if (req.exists()) println("guard: request file seen, enabling now")
                    enableWifi()
                    lastPeriodic = now
                    runCatching { if (req.exists()) req.delete() }
                }
                writeHeartbeat(guardDir)
            } catch (t: Throwable) {
                println("guard: loop slipped: ${t.message}")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                println("guard: interrupted, exiting")
                return
            }
        }
    }

    private fun ownUid(): String {
        return try {
            java.io.File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Uid:") }?.trim() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun enableWifi() {
        val svc = runCmd("svc", "wifi", "enable")
        println("guard: svc wifi enable -> exit=$svc")
        if (svc != 0) {
            val cmd = runCmd("cmd", "wifi", "set-wifi-enabled", "enabled")
            println("guard: cmd wifi set-wifi-enabled -> exit=$cmd")
        }
    }

    private fun runCmd(vararg cmd: String): Int {
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (out.isNotBlank()) println("guard: [${cmd.joinToString(" ")}] $out")
            code
        } catch (e: Exception) {
            println("guard: [${cmd.joinToString(" ")}] failed: ${e.message}")
            -1
        }
    }

    private fun writeHeartbeat(dir: java.io.File) {
        try {
            java.io.File(dir, HEARTBEAT_FILE).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            println("guard: heartbeat failed: ${e.message}")
        }
    }
}
