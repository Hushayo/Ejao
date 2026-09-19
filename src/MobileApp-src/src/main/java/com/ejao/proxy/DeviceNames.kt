package com.ejao.proxy

import android.content.Context

/**
 * Persistent custom device names, keyed by client MAC (primary) with IP
 * fallback. Stored in SharedPreferences so renames survive restarts and
 * updates. MAC is the stable key because the P2P group can reassign IPs;
 * randomized MACs (8e:2e:..) are still stable per-SSID on the client.
 */
object DeviceNames {
    private const val PREFS = "ejao_device_names"

    fun keyFor(mac: String, ip: String): String {
        val m = mac.trim().lowercase()
        if (m.isNotEmpty() && m.contains(":")) return "mac:$m"
        val cleanIp = ip.trim()
        if (cleanIp.isNotEmpty()) return "ip:$cleanIp"
        return ""
    }

    fun get(context: Context, mac: String, ip: String): String? {
        val key = keyFor(mac, ip)
        if (key.isEmpty()) return null
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun set(context: Context, mac: String, ip: String, name: String) {
        val key = keyFor(mac, ip)
        if (key.isEmpty()) return
        val clean = name.trim().take(32)
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (clean.isEmpty()) {
                prefs.edit().remove(key).commit()
            } else {
                prefs.edit().putString(key, clean).commit()
            }
        }
    }
}
