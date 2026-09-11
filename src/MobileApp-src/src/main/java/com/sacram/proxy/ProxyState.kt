package com.sacram.proxy

import android.content.Context

object ProxyState {
    private const val PREFS = "sacram_state"
    private const val KEY_SHOULD_RUN = "should_run"

    fun shouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, value: Boolean) {
        // commit() (not apply()) so shouldRun survives an immediate process
        // kill after onStartCommand/onDestroy; single bool write is cheap.
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHOULD_RUN, value)
                .commit()
        }
    }
}
