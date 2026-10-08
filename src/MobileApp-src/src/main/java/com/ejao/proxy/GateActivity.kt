package com.ejao.proxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * AprilFool invisible launcher gate. Theme.NoDisplay means zero pixels ever -
 * not even the system launch preview (which MainActivity's windowBackground
 * would paint before onCreate runs). First two cold taps finish silently
 * (dead SIM Toolkit look); 3rd tap within 5s opens the real app.
 * Fail-open on any error so the prank can never lock the owner out.
 */
class GateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val open = runCatching { consumeTap() }.getOrDefault(true)
        if (open) {
            runCatching { startActivity(Intent(this, MainActivity::class.java)) }
        }
        finish()
    }

    private fun consumeTap(): Boolean {
        return try {
            val prefs = getSharedPreferences("aprilfool_gate", MODE_PRIVATE)
            val now = SystemClock.uptimeMillis()
            val windowStart = prefs.getLong("window_start", 0L)
            var count = prefs.getInt("tap_count", 0)
            if (now - windowStart > 5_000L) count = 0
            count++
            if (count >= 3) {
                prefs.edit().putInt("tap_count", 0).putLong("window_start", 0L).apply()
                true
            } else {
                prefs.edit()
                    .putInt("tap_count", count)
                    .putLong("window_start", if (count == 1) now else windowStart)
                    .apply()
                false
            }
        } catch (_: Exception) {
            true
        }
    }
}
