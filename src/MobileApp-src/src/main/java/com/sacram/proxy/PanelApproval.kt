package com.sacram.proxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Gates control-panel setting changes behind owner approval on the phone.
 *
 * A browser submitting the panel form does NOT change config directly - it
 * submits a pending [Request]. The phone shows a 10s approval notification; the
 * owner approves (config applied) or ignores/denies (request dropped, nothing
 * changes). This stops anyone on the WiFi network from silently reconfiguring
 * the proxy (e.g. changing the hotspot password) without the owner's consent.
 */
object PanelApproval {
    data class Request(
        val id: Long,
        val fields: Map<String, String>,
        val submittedAt: Long
    )

    const val APPROVE_WINDOW_MS = 10_000L

    private val counter = AtomicLong(0)
    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()

    /**
     * Set by [ProxyService] so an approved *restart* request can actually
     * restart the proxy. Approved config changes use [applyFields] instead.
     */
    var onRestart: (() -> Unit)? = null

    fun submit(fields: Map<String, String>): Request {
        val r = Request(counter.incrementAndGet(), fields, System.currentTimeMillis())
        _pending.value = r
        return r
    }

    fun current(): Request? = _pending.value

    fun approve(context: Context): Boolean {
        return try {
            val r = _pending.value ?: return false
            if (r.fields["action"] == "restart") {
                runCatching { onRestart?.invoke() }
                _pending.value = null
                return true
            }
            applyFields(context, r.fields)
            _pending.value = null
            true
        } catch (_: Exception) {
            runCatching { _pending.value = null }
            false
        }
    }

    fun deny() {
        runCatching { _pending.value = null }
    }

    fun applyFields(context: Context, fields: Map<String, String>) {
        try {
            val prev = ConfigManager.load(context)
            val newCfg = prev.copy(
                keepaliveUrl = fields["keepalive_url"]?.trim()?.ifBlank { prev.keepaliveUrl }
                    ?: prev.keepaliveUrl,
                keepaliveIntervalMs = (fields["keepalive_interval"]?.toLongOrNull()?.coerceAtLeast(15)
                    ?: (prev.keepaliveIntervalMs / 1000)) * 1000L,
                panelEnabled = fields["panel_enabled"] == "on",
                band = fields["band"]?.trim()?.takeIf { it in setOf("2.4", "5", "auto") }
                    ?: prev.band
            )
            ConfigManager.save(context, newCfg)
        } catch (_: Exception) {
        }
    }
}
