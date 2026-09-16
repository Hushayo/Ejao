package com.ejao.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight network keep-alive. While the proxy service runs we periodically
 * hit a pre-listed URL so the OS sees ongoing network activity and is less
 * likely to idle / Doze / kill the process.
 *
 * This is a *supplement* to the foreground service, wake lock and watchdog -
 * not a replacement. Aggressive OEM task-killers (Xiaomi/Huawei/Honor/...) only
 * truly respect a foreground service + the user granting battery-exemption /
 * autostart, which the Keep-Alive tab helps with.
 */
object KeepAlive {
    private const val TAG = "EjaoKeepAlive"
    private const val DEFAULT_URL = "https://www.google.com/generate_204"
    private const val MIN_INTERVAL_MS = 15_000L

    fun launch(scope: CoroutineScope, context: Context): Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                val cfg = runCatching { ConfigManager.load(context) }.getOrDefault(ConfigManager.defaultConfig)
                val url = cfg.keepaliveUrl.takeIf { it.isNotBlank() } ?: DEFAULT_URL
                val interval = cfg.keepaliveIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 8000
                        readTimeout = 8000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    conn.responseCode
                    conn.disconnect()
                    Log.d(TAG, "ping ok (${conn.responseCode}) -> $url")
                } catch (e: Throwable) {
                    // never let a failed ping crash or stop the loop
                    Log.d(TAG, "ping failed -> $url : ${e.message}")
                }
                delay(interval)
            }
        }
    }
}
