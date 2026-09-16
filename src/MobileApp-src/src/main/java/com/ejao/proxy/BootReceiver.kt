package com.ejao.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ProxyState.shouldRun(context)) return
        val start = Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, start)
        } catch (e: Exception) {
            // OS background-start limits (Android 8+/12+) may block this;
            // keep shouldRun=true so the next manual launch recovers.
            Log.w("EjaoBoot", "background start blocked: ${e.message}")
        }
    }
}
