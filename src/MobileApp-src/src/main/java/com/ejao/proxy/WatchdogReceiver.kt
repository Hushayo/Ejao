package com.ejao.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!ProxyState.shouldRun(context)) return
        ProxyService.scheduleWatchdog(context)
        if (AppState.running.value) return
        val start = Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, start)
        } catch (e: Exception) {
            // OS background-start limits may block this; shouldRun stays true
            // so the next manual launch recovers.
            Log.w("EjaoWatchdog", "background start blocked: ${e.message}")
        }
    }
}
