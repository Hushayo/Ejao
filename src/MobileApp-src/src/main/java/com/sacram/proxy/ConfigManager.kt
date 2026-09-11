package com.sacram.proxy

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

data class AppConfig(
    val ssid: String,
    val password: String,
    val port: Int,
    val band: String = "5",
    val disableBandSelector: Boolean = false,
    val proxyMode: String = "socks5",
    val proxyType: Int = 0, // 0=Auto (SOCKS5+HTTP) [default], 1=SOCKS5, 2=HTTP, 3=Hybrid (SOCKS5+HTTP)
    val httpPort: Int = 8282,
    // Dedicated SOCKS4 backward-compatibility port. Runs its own server
    // (Socks4Server) alongside SOCKS5 so legacy SOCKS4/SOCKS4a clients work.
    // Defaults to the SOCKS5 port + 1.
    val socks4Port: Int = 1081,
    val keepaliveUrl: String = "https://www.google.com/generate_204",
    val keepaliveIntervalMs: Long = 60_000L,
    val panelEnabled: Boolean = true,
    val requireApprovalRestart: Boolean = false,
    // Never give up recreating the WiFi Direct group when Android drops it on
    // inactivity - keep hammering recreateGroup until it comes back instead of
    // marking the AP dead after the retry cap.
    val keepRetryingReform: Boolean = false,
    // If WiFi is off at startup, wait for it to come back and auto-bring the
    // proxy up on its own - instead of stopping the service and forcing the
    // user to toggle the proxy off and on in the app.
    val autoRestartOnWifiReturn: Boolean = false,
    // Dedicated control-panel port. Runs its own server (PanelServer) so the
    // panel stays responsive even when the proxy worker pool is saturated.
    // Defaults to httpPort + 1 when not explicitly set.
    val panelPort: Int = 8283,
    // Emergency backup dashboard port. Runs BackupPanelServer, which is
    // started right after the WiFi Direct group forms and is NEVER stopped
    // by restartProxy() - so it stays reachable at http://<goIp>:<backup>
    // even when the main proxy/panel dies but WiFi Direct is still up.
    // Defaults to panelPort + 1 (8284) when not explicitly set.
    val backupPanelPort: Int = 8284,
    // Hours between background update checks; 0 = disabled. Default 6h.
    val updateCheckIntervalHours: Int = 6
) {
    fun effectiveMode(): String {
        return when (proxyType) {
            1 -> "socks5"
            2 -> "http"
            0, 3 -> "hybrid"   // 0 = Auto: run SOCKS5 + HTTP together, no manual pick
            else -> proxyMode
        }
    }

    fun isHybrid(): Boolean = proxyType == 0 || proxyType == 3
}

object ConfigManager {

    private const val DIR_NAME = "Sacram"
    private const val FILE_NAME = "config.txt"

    val defaultConfig = AppConfig("SacramAP", "", 1080)

    fun internalConfigFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun externalConfigFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun ensureConfig(context: Context): AppConfig {
        return try {
            val cfg = load(context)
            mirrorToExternal(context)
            cfg
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun mirrorToExternal(context: Context) {
        try {
            val internal = internalConfigFile(context)
            val external = externalConfigFile(context)
            if (internal.exists()) internal.copyTo(external, overwrite = true)
        } catch (_: Exception) {
        }
    }

    /**
     * A copy of the config kept in the public Documents folder (NOT in any
     * app-specific directory) so it survives app uninstall/reinstall. Android
     * wipes getFilesDir()/getExternalFilesDir() on uninstall, which is why the
     * previous config was lost. We mirror here via MediaStore (no extra
     * permission needed on API 29+) and restore from it on first launch after a
     * reinstall. Pre-API 29 is skipped (scoped-storage/permission constraints).
     */
    private fun globalConfigUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relative = Environment.DIRECTORY_DOCUMENTS + "/Sacram/"
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            val args = arrayOf(relative, FILE_NAME)
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relative)
            }
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        }
    }

    private fun readGlobalConfig(context: Context): String? {
        return try {
            val uri = globalConfigUri(context) ?: return null
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeGlobalConfig(context: Context, text: String) {
        try {
            val uri = globalConfigUri(context) ?: return
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { it.write(text.toByteArray()) }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun load(context: Context): AppConfig {
        return try {
            val internal = internalConfigFile(context)
            val external = externalConfigFile(context)
            val source = when {
                internal.exists() -> internal
                external.exists() -> external
                else -> {
                    val g = readGlobalConfig(context)
                    if (g != null) {
                        runCatching {
                            internal.writeText(g)
                            mirrorToExternal(context)
                        }
                        return parse(internal)
                    }
                    runCatching { save(context, defaultConfig) }
                    return defaultConfig
                }
            }
            parse(source)
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun parse(file: File): AppConfig {
        val p = Properties()
        return try {
            file.inputStream().use { p.load(it) }
            AppConfig(
                ssid = p.getProperty("ssid", defaultConfig.ssid),
                password = p.getProperty("password", defaultConfig.password),
                port = p.getProperty("port", defaultConfig.port.toString()).toIntOrNull()?.coerceIn(1, 65535) ?: defaultConfig.port,
                band = p.getProperty("band", defaultConfig.band)
                    .ifBlank { defaultConfig.band }
                    .let { if (it in setOf("2.4", "5", "auto")) it else defaultConfig.band },
                disableBandSelector = p.getProperty("disable_band_selector", defaultConfig.disableBandSelector.toString()).toBoolean(),
                proxyMode = p.getProperty("proxy_mode", defaultConfig.proxyMode)
                    .ifBlank { defaultConfig.proxyMode },
                proxyType = p.getProperty("proxy_type", "0").toIntOrNull() ?: 0,
                httpPort = p.getProperty("http_port", defaultConfig.httpPort.toString()).toIntOrNull()
                    ?.coerceIn(1, 65535) ?: defaultConfig.httpPort,
                socks4Port = p.getProperty("socks4_port", defaultConfig.socks4Port.toString()).toIntOrNull()
                    ?.coerceIn(1, 65535) ?: defaultConfig.socks4Port,
                keepaliveUrl = p.getProperty("keepalive_url", defaultConfig.keepaliveUrl)
                    .ifBlank { defaultConfig.keepaliveUrl }
                    .let { if ("workers.dev" in it) defaultConfig.keepaliveUrl else it },
                keepaliveIntervalMs = p.getProperty("keepalive_interval_ms", defaultConfig.keepaliveIntervalMs.toString())
                    .toLongOrNull()?.coerceAtLeast(15_000L) ?: defaultConfig.keepaliveIntervalMs,
                panelEnabled = p.getProperty("panel_enabled", defaultConfig.panelEnabled.toString()).toBoolean(),
                requireApprovalRestart = p.getProperty("require_approval_restart", defaultConfig.requireApprovalRestart.toString()).toBoolean(),
                keepRetryingReform = p.getProperty("keep_retrying_reform", defaultConfig.keepRetryingReform.toString()).toBoolean(),
                autoRestartOnWifiReturn = p.getProperty("auto_restart_on_wifi_return", defaultConfig.autoRestartOnWifiReturn.toString()).toBoolean(),
                panelPort = p.getProperty("panel_port", (defaultConfig.httpPort + 1).toString()).toIntOrNull()?.coerceIn(1, 65535)
                    ?: (defaultConfig.httpPort + 1),
                backupPanelPort = run {
                    val panel = p.getProperty("panel_port", (defaultConfig.httpPort + 1).toString()).toIntOrNull()
                        ?.coerceIn(1, 65535) ?: (defaultConfig.httpPort + 1)
                    p.getProperty("backup_panel_port", (panel + 1).toString()).toIntOrNull()?.coerceIn(1, 65535)
                        ?: (panel + 1).coerceIn(1, 65535)
                },
                updateCheckIntervalHours = p.getProperty("update_check_interval_hours", defaultConfig.updateCheckIntervalHours.toString()).toIntOrNull()?.coerceIn(0, 24)
                    ?: defaultConfig.updateCheckIntervalHours
            )
        } catch (_: Exception) {
            defaultConfig
        }
    }

    fun save(context: Context, config: AppConfig) {
        synchronized(ConfigManager) {
        try {
            val file = internalConfigFile(context)
            val lines = listOf(
            "# Sacram config",
            "# Edit and restart the proxy to apply.",
            "ssid=${config.ssid}",
            "password=${config.password}",
            "port=${config.port}",
            "band=${config.band}",
            "disable_band_selector=${config.disableBandSelector}",
            "proxy_mode=${config.proxyMode}",
            "proxy_type=${config.proxyType}",
            "http_port=${config.httpPort}",
            "socks4_port=${config.socks4Port}",
            "keepalive_url=${config.keepaliveUrl}",
            "keepalive_interval_ms=${config.keepaliveIntervalMs}",
            "panel_enabled=${config.panelEnabled}",
            "require_approval_restart=${config.requireApprovalRestart}",
            "keep_retrying_reform=${config.keepRetryingReform}",
            "auto_restart_on_wifi_return=${config.autoRestartOnWifiReturn}",
            "panel_port=${config.panelPort}",
            "backup_panel_port=${config.backupPanelPort}",
            "update_check_interval_hours=${config.updateCheckIntervalHours}"
        )
        val text = lines.joinToString("\n") + "\n"
        // Atomic write: tmp + rename avoids torn reads on crash/kill.
        val tmp = File(file.parent, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            runCatching { tmp.delete() }
        }
        mirrorToExternal(context)
        writeGlobalConfig(context, text)
        } catch (_: Exception) {
        }
        }
    }
}
