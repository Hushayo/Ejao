package com.ejao.proxy

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "EjaoMain"
        // Band picker. Index order MUST match BAND_VALUES and the segment order.
        val BAND_LABELS = listOf("2.4 GHz", "5 GHz (default)", "Auto")
        val BAND_VALUES = listOf("2.4", "5", "auto")
        // Update-check frequency chips. Index order MUST match UPDATE_INTERVAL_VALUES.
        val UPDATE_INTERVAL_LABELS = listOf("Every 1 hour", "Every 3 hours", "Every 6 hours (default)", "Every 12 hours", "Every 24 hours")
        val UPDATE_INTERVAL_VALUES = listOf(1, 3, 6, 12, 24)
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvHeroSub: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvSocksPort: TextView
    private lateinit var tvHttpPort: TextView
    private lateinit var tvAppStatus: TextView
    private lateinit var appStatusDot: View
    private lateinit var tvSaved: TextView
    private lateinit var btnToggle: TextView
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etPort: EditText
    private lateinit var etHttpPort: EditText
    private lateinit var etKeepaliveUrl: EditText
    private lateinit var etKeepaliveInterval: EditText
    private lateinit var swRequireApproval: SwitchCompat
    private lateinit var swAutoRestart: SwitchCompat
    private lateinit var swKeepRetrying: SwitchCompat
    private lateinit var swDisableBand: SwitchCompat
    private lateinit var swAutoUpdate: SwitchCompat
    private lateinit var btnCheckUpdate: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var statusDot: View
    private lateinit var statusHalo: View
    private var lampAnimator: ObjectAnimator? = null
    private var lastRunning: Boolean? = null
    private lateinit var connectionSection: View
    private lateinit var bandGroup: View
    private lateinit var updateChipsRow: View
    private lateinit var bandSegments: List<TextView>
    private lateinit var updateChips: List<TextView>
    private lateinit var rowPanel: View
    private lateinit var rowBackup: View
    private lateinit var dividerPanel: View
    private lateinit var dividerBackup: View
    private lateinit var tvSocksEndpoint: TextView
    private lateinit var tvHttpEndpoint: TextView
    private lateinit var tvPanelEndpoint: TextView
    private lateinit var tvBackupEndpoint: TextView
    private lateinit var tvFooterVersion: TextView
    private var updateInProgress = false

    // UI-only selection state mirrored into config.txt on save.
    private var selectedBandIdx = 0
    private var selectedUpdateIdx = 2
    private var copySocksText = ""
    private var copyHttpText = ""
    private var copyPanelUrl = ""
    private var copyBackupUrl = ""

    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable { autosave() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        Log.i(TAG, "permission results: $it")
        try {
            if (it.values.all { granted -> granted }) {
                startProxy()
            } else {
                val denied = it.filterValues { !it }.keys.joinToString(",")
                Log.e(TAG, "permissions denied: $denied")
                runCatching {
                    Toast.makeText(this, "Some permissions denied - starting anyway, WiFi Direct may fail", Toast.LENGTH_LONG).show()
                }
                startProxy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "permission handler failed", e)
            runCatching {
                Toast.makeText(this, "Couldn't start proxy: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvHeroSub = findViewById(R.id.tvHeroSub)
        tvClients = findViewById(R.id.tvClients)
        tvSocksPort = findViewById(R.id.tvSocksPort)
        tvHttpPort = findViewById(R.id.tvHttpPort)
        tvAppStatus = findViewById(R.id.tvAppStatus)
        appStatusDot = findViewById(R.id.appStatusDot)
        tvSaved = findViewById(R.id.tvSaved)
        btnToggle = findViewById(R.id.btnToggle)
        statusDot = findViewById(R.id.statusDot)
        statusHalo = findViewById(R.id.statusHalo)
        connectionSection = findViewById(R.id.connectionSection)
        etSsid = findViewById(R.id.etSsid)
        etPass = findViewById(R.id.etPass)
        etPort = findViewById(R.id.etPort)
        etHttpPort = findViewById(R.id.etHttpPort)
        etKeepaliveUrl = findViewById(R.id.etKeepaliveUrl)
        etKeepaliveInterval = findViewById(R.id.etKeepaliveInterval)
        swRequireApproval = findViewById(R.id.swRequireApproval)
        swAutoRestart = findViewById(R.id.swAutoRestart)
        swKeepRetrying = findViewById(R.id.swKeepRetrying)
        swDisableBand = findViewById(R.id.swDisableBand)
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvFooterVersion = findViewById(R.id.tvFooterVersion)
        bandGroup = findViewById(R.id.bandGroup)
        updateChipsRow = findViewById(R.id.updateChips)
        bandSegments = listOf(
            findViewById(R.id.bandSeg24),
            findViewById(R.id.bandSeg5),
            findViewById(R.id.bandSegAuto)
        )
        updateChips = listOf(
            findViewById(R.id.chip1h),
            findViewById(R.id.chip3h),
            findViewById(R.id.chip6h),
            findViewById(R.id.chip12h),
            findViewById(R.id.chip24h)
        )
        rowPanel = findViewById(R.id.rowPanel)
        rowBackup = findViewById(R.id.rowBackup)
        dividerPanel = findViewById(R.id.dividerPanel)
        dividerBackup = findViewById(R.id.dividerBackup)
        tvSocksEndpoint = findViewById(R.id.tvSocksEndpoint)
        tvHttpEndpoint = findViewById(R.id.tvHttpEndpoint)
        tvPanelEndpoint = findViewById(R.id.tvPanelEndpoint)
        tvBackupEndpoint = findViewById(R.id.tvBackupEndpoint)

        val config = runCatching { ConfigManager.ensureConfig(this) }
            .getOrDefault(ConfigManager.defaultConfig)
        etSsid.setText(config.ssid)
        etPass.setText(config.password)
        etPort.setText(config.port.toString())
        etHttpPort.setText(config.httpPort.toString())
        etKeepaliveUrl.setText(config.keepaliveUrl)
        etKeepaliveInterval.setText((config.keepaliveIntervalMs / 1000).toString())

        selectedBandIdx = BAND_VALUES.indexOf(config.band).let { if (it < 0) 0 else it }
        bindSegmentGroup(bandSegments, selectedBandIdx) { idx ->
            selectedBandIdx = idx
            autosave()
        }

        swRequireApproval.isChecked = config.requireApprovalRestart
        swRequireApproval.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        swAutoRestart.isChecked = config.autoRestartOnWifiReturn
        swAutoRestart.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        swKeepRetrying.isChecked = config.keepRetryingReform
        swKeepRetrying.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        swDisableBand.isChecked = config.disableBandSelector
        swDisableBand.setOnCheckedChangeListener { _, _ ->
            runCatching {
                applyBandSelectorVisibility(swDisableBand.isChecked)
                autosave()
            }
        }
        applyBandSelectorVisibility(config.disableBandSelector)

        selectedUpdateIdx = UPDATE_INTERVAL_VALUES.indexOf(config.updateCheckIntervalHours).let { if (it < 0) 2 else it }
        bindSegmentGroup(updateChips, selectedUpdateIdx) { idx ->
            selectedUpdateIdx = idx
            runCatching { UpdateChecker.scheduleCheck(this, chosenUpdateIntervalHours()) }
            autosave()
        }
        val autoUpdateOn = config.updateCheckIntervalHours > 0
        swAutoUpdate.isChecked = autoUpdateOn
        updateChipsRow.alpha = if (autoUpdateOn) 1f else 0.45f
        updateChips.forEach { it.isEnabled = autoUpdateOn }
        swAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            try {
                updateChipsRow.alpha = if (isChecked) 1f else 0.45f
                updateChips.forEach { it.isEnabled = isChecked }
                runCatching { UpdateChecker.scheduleCheck(this, chosenUpdateIntervalHours()) }
                autosave()
            } catch (e: Exception) {
                Log.e(TAG, "auto-update toggle failed", e)
            }
        }

        findViewById<View>(R.id.btnCopySocks).setOnClickListener {
            copyToClipboard("SOCKS5 endpoint", copySocksText)
        }
        findViewById<View>(R.id.btnCopyHttp).setOnClickListener {
            copyToClipboard("HTTP endpoint", copyHttpText)
        }
        findViewById<View>(R.id.btnCopyPanel).setOnClickListener {
            copyToClipboard("Control panel URL", copyPanelUrl)
        }
        findViewById<View>(R.id.btnOpenPanel).setOnClickListener {
            openExternalUrl(copyPanelUrl)
        }
        findViewById<View>(R.id.btnCopyBackup).setOnClickListener {
            copyToClipboard("Backup panel URL", copyBackupUrl)
        }
        findViewById<View>(R.id.btnOpenBackup).setOnClickListener {
            openExternalUrl(copyBackupUrl)
        }

        runCatching { setupWindowInsets() }
        runCatching { setupExpanders() }
        runCatching { setupAutosave() }
        runCatching { setupEasterEgg() }
        runCatching { observePanelApproval() }

        // Notify if no valid password is set, but don't block anything
        if (config.password.length !in 8..63) {
            Log.w(TAG, "no valid password set yet - will error on start until set")
            tvHeroSub.text = "Set a Wi-Fi password (8-63 characters) to start the hotspot."
            runCatching { expand(R.id.groupHotspot, R.id.chevronHotspot) }
        }

        btnToggle.setOnClickListener {
            runCatching {
                if (AppState.running.value) {
                    ProxyState.setShouldRun(this, false)
                    stopService(Intent(this, ProxyService::class.java))
                    // Backup is its own service: stop it explicitly too (it
                    // ignores ProxyService's death when shouldRun is true, so
                    // without this it would linger after a user STOP).
                    runCatching { BackupPanelService.stop(this) }
                } else {
                    startSelectedProxy()
                }
            }.onFailure { e ->
                Log.e(TAG, "toggle failed", e)
                runCatching {
                    Toast.makeText(this, "Couldn't toggle proxy: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<View>(R.id.btnWiki).setOnClickListener { runCatching { openWiki() } }
        findViewById<View>(R.id.btnBattery).setOnClickListener { runCatching { requestBatteryExemption() } }
        findViewById<View>(R.id.btnAutostart).setOnClickListener { runCatching { openAutostartSettings() } }

        tvUpdateStatus.text = "v${BuildConfig.VERSION_NAME} · GitHub releases"
        tvFooterVersion.text = "EJAO / 01 · V${BuildConfig.VERSION_NAME} · 192.168.49.1"
        btnCheckUpdate.setOnClickListener {
            runCatching {
                val ready = AppState.updateAvailable.value
                val file = runCatching { UpdateChecker.downloadedApkFile(this) }.getOrNull()
                if (ready != null && file != null && file.exists()) {
                    // Re-verify: the background download may be old or the
                    // file may have changed since. Never install on mismatch.
                    tvUpdateStatus.text = "Verifying update $ready..."
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { UpdateChecker.verifyApkHash(file, ready) }.getOrDefault(false)
                        }
                        if (ok) {
                            launchInstaller(file)
                        } else {
                            runCatching { file.delete() }
                            AppState.updateAvailable.value = null
                            tvUpdateStatus.text = "Update file failed its hash check - deleted. Tap to download again."
                            btnCheckUpdate.text = "Check for updates"
                        }
                    }
                } else {
                    checkForUpdate()
                }
            }.onFailure { e ->
                Log.e(TAG, "update button failed", e)
                runCatching { tvUpdateStatus.text = "Update check failed: ${e.message}" }
            }
        }
        runCatching { UpdateChecker.scheduleCheck(this, config.updateCheckIntervalHours) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppState.status.collect { raw ->
                        runCatching {
                            val detail = raw.trim()
                            // "RUNNING - clients connected: 2" -> "clients connected: 2";
                            // any extra nuance (re-forming, proxy down) stays visible.
                            val nuance = detail.removePrefix("RUNNING")
                                .trim().removePrefix("-").trim()
                            val show = AppState.running.value &&
                                detail.isNotEmpty() &&
                                detail != "Stopped" &&
                                nuance.isNotEmpty()
                            tvStatusDetail.text = nuance
                            tvStatusDetail.visibility = if (show) View.VISIBLE else View.GONE
                        }
                    }
                }
                launch { AppState.apInfo.collect { runCatching { renderInfo(it) } } }
                launch { AppState.running.collect { runCatching { renderRunning(it) } } }
                launch {
                    AppState.updateAvailable.collect { tag ->
                        runCatching {
                            val apkExists = runCatching { UpdateChecker.downloadedApkFile(this@MainActivity).exists() }.getOrDefault(false)
                            if (tag != null && apkExists) {
                                btnCheckUpdate.text = "Install"
                                tvUpdateStatus.text = "Update $tag downloaded in the background - tap Install."
                            } else {
                                btnCheckUpdate.text = "Check"
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart - passwordLength=${etPass.text.length}, running=${AppState.running.value}")
    }

    override fun onDestroy() {
        lampAnimator?.cancel()
        saveHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    /**
     * AprilFool: exiting (Home, recents, Back, opening Settings/browser)
     * destroys the Activity, so the next entry is always a cold launch
     * through the invisible gate. Rotation is exempt, otherwise every
     * rotate would eat the session. The proxy Service is unaffected.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            runCatching { finish() }
        }
    }

    /**
     * Sparse settings: each section is a plain row that opens in place.
     * Everything starts collapsed so the first screen stays down to the
     * status, the primary action and the connection endpoints.
     */
    private fun setupExpanders() {
        bindExpander(R.id.expandHotspot, R.id.groupHotspot, R.id.chevronHotspot)
        bindExpander(R.id.expandKeepalive, R.id.groupKeepalive, R.id.chevronKeepalive)
        bindExpander(R.id.expandBehavior, R.id.groupBehavior, R.id.chevronBehavior)
        bindExpander(R.id.expandUpdates, R.id.groupUpdates, R.id.chevronUpdates)
        bindExpander(R.id.expandDevice, R.id.groupDevice, R.id.chevronDevice)
    }

    private fun bindExpander(headerId: Int, contentId: Int, chevronId: Int) {
        // Chevron points down while closed, up while open.
        findViewById<View>(chevronId).rotation = 90f
        findViewById<View>(headerId).setOnClickListener {
            if (findViewById<View>(contentId).visibility == View.VISIBLE) {
                collapse(contentId, chevronId)
            } else {
                expand(contentId, chevronId)
            }
        }
    }

    private fun expand(contentId: Int, chevronId: Int) {
        val content = findViewById<View>(contentId)
        content.animate().cancel()
        content.visibility = View.VISIBLE
        content.alpha = 0f
        content.translationY = -6f
        content.animate().alpha(1f).translationY(0f).setDuration(180).start()
        findViewById<View>(chevronId).animate().rotation(270f).setDuration(180).start()
    }

    private fun collapse(contentId: Int, chevronId: Int) {
        val content = findViewById<View>(contentId)
        content.animate().cancel()
        content.animate().alpha(0f).translationY(-6f).setDuration(120)
            .withEndAction {
                content.visibility = View.GONE
                content.alpha = 1f
                content.translationY = 0f
            }.start()
        findViewById<View>(chevronId).animate().rotation(90f).setDuration(180).start()
    }

    /**
     * Edge-to-edge: the window draws behind the status/navigation bars.
     * The app bar takes the status-bar inset; the scroll viewport ends above
     * the gesture bar or the keyboard. The content keeps its own bottom
     * padding, so add the insets on top of it rather than replacing it.
     */
    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val appBar = findViewById<View>(R.id.appBar)
        val scroll = findViewById<View>(R.id.mainScroll)
        val baseTop = appBar.paddingTop
        val baseBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.updatePadding(top = baseTop + bars.top)
            scroll.updatePadding(bottom = baseBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to copy yet", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Log.e(TAG, "copy failed", e)
            runCatching { Toast.makeText(this, "Couldn't copy", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openExternalUrl(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "open url failed", e)
            runCatching { Toast.makeText(this, "No app can open $url", Toast.LENGTH_LONG).show() }
        }
    }

    private fun setupAutosave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                runCatching { updateHeroMetrics() }
                runCatching { updateHeroSub() }
                saveHandler.removeCallbacks(autosaveRunnable)
                saveHandler.postDelayed(autosaveRunnable, 1200)
            }

            override fun afterTextChanged(s: Editable?) {}
        }
        etSsid.addTextChangedListener(watcher)
        etPass.addTextChangedListener(watcher)
        etPort.addTextChangedListener(watcher)
        etHttpPort.addTextChangedListener(watcher)
        etKeepaliveUrl.addTextChangedListener(watcher)
        etKeepaliveInterval.addTextChangedListener(watcher)
    }

    /**
     * Segment/chip controls replace the old dropdowns: one selected tab, no
     * popup to mis-anchor or close on the wrong tap.
     */
    private fun bindSegmentGroup(views: List<TextView>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        fun paint(index: Int) {
            views.forEachIndexed { i, view -> view.isSelected = i == index }
        }
        paint(selectedIndex)
        views.forEachIndexed { i, view ->
            view.setOnClickListener {
                if (view.isEnabled) {
                    paint(i)
                    onSelect(i)
                }
            }
        }
    }

    /**
     * When the band selector is disabled we hide the band segment entirely so
     * the user can't pick a band that won't be applied. The proxy then falls
     * back to the default band (see ProxyService).
     */
    private fun applyBandSelectorVisibility(disabled: Boolean) {
        runCatching {
            bandGroup.visibility = if (disabled) View.GONE else View.VISIBLE
        }
    }

    /**
     * Effective background update-check interval: 0 (disabled) when the toggle
     * is off, otherwise the selected chip.
     */
    private fun chosenUpdateIntervalHours(): Int {
        return try {
            if (!swAutoUpdate.isChecked) return 0
            UPDATE_INTERVAL_VALUES.getOrElse(selectedUpdateIdx) { 6 }
        } catch (_: Exception) {
            6
        }
    }

    private var eggTaps = 0
    private var approvalDialog: androidx.appcompat.app.AlertDialog? = null


    private fun setupEasterEgg() {
        tvStatus.setOnClickListener {
            runCatching {
                if (++eggTaps >= 7) {
                    eggTaps = 0
                    Toast.makeText(
                        this,
                        "\uD83D\uDEF0 You found the Ejao easter egg - stay proxy, my friend.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Show an in-app approve/deny prompt whenever a device on the network submits
     * a panel change. The request is dropped if the owner ignores it for 10s.
     */
    private fun observePanelApproval() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PanelApproval.pending.collect { req ->
                    runCatching {
                        if (req == null) {
                            runCatching { approvalDialog?.dismiss() }
                            approvalDialog = null
                            return@collect
                        }
                        showApprovalDialog(req)
                    }
                }
            }
        }
    }

    private fun showApprovalDialog(req: PanelApproval.Request) {
        try {
            approvalDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (_: Exception) {
        }
        val isRestart = req.fields["action"] == "restart"
        val summary = if (isRestart) {
            "Restart the proxy + hotspot."
        } else {
            runCatching {
                req.fields.entries.joinToString("\n") { "${it.key} = ${it.value}" }
            }.getOrDefault("(unreadable change)")
        }
        val dialog = try {
            MaterialAlertDialogBuilder(this)
                .setTitle("Approve panel change?")
                .setMessage(
                    "A device on the WiFi requested these setting changes:\n\n$summary\n\n" +
                        "Approve within 10 seconds, otherwise the request is dropped."
                )
                .setCancelable(false)
                .setPositiveButton("Approve") { _, _ -> runCatching { PanelApproval.approve(this) } }
                .setNegativeButton("Deny") { _, _ -> runCatching { PanelApproval.deny() } }
                .create()
        } catch (e: Exception) {
            Log.e(TAG, "approval dialog build failed", e)
            PanelApproval.deny()
            return
        }
        dialog.setOnDismissListener {
            runCatching {
                if (PanelApproval.current()?.id == req.id) PanelApproval.deny()
                if (approvalDialog === dialog) approvalDialog = null
            }
        }
        approvalDialog = dialog
        try {
            if (!isFinishing && !isDestroyed) dialog.show()
            else PanelApproval.deny()
        } catch (e: Exception) {
            Log.e(TAG, "approval dialog show failed", e)
            PanelApproval.deny()
            return
        }
        lifecycleScope.launch {
            delay(PanelApproval.APPROVE_WINDOW_MS)
            runCatching {
                if (PanelApproval.current()?.id == req.id) {
                    PanelApproval.deny()
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

    /**
     * Reads widgets + validates. Returns the config to persist, or null after
     * showing why in tvSaved. Main thread only (touches views).
     */
    private fun buildSaveCopy(): AppConfig? {
        val pass = etPass.text.toString()
        val ssid = etSsid.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        val httpPort = etHttpPort.text.toString().toIntOrNull()
        // Single real mode (always 0 = Auto). The engine still honors
        // hand-edited 1/2/3 in config.txt.
        val proxyType = 0
        val band = BAND_VALUES.getOrElse(selectedBandIdx) { "2.4" }
        val updateCheckIntervalHours = chosenUpdateIntervalHours()
        fun reject(msg: String, colorRes: Int = R.color.danger): AppConfig? {
            setSavedMessage(msg, colorRes)
            return null
        }
        if (pass.length !in 8..63) {
            // Neutral, not an error: a fresh install simply has no password yet.
            return reject("Set a Wi-Fi password (8-63 characters) to save settings.", R.color.text_muted)
        }
        if (port == null || port < 1 || port > 65535) return reject("Invalid port - not saved yet")
        if (httpPort == null || httpPort < 1 || httpPort > 65535) return reject("Invalid HTTP port - not saved yet")
        // The visible fields must not collide with each other or with the
        // config-file-only ports (SOCKS4/panel/backup): a collision binds
        // half the servers and used to report a lying RUNNING state.
        val prev = ConfigManager.load(this)
        if (port == httpPort) return reject("SOCKS5 and HTTP ports must differ - not saved yet")
        val clash = mapOf(
            "SOCKS4" to prev.socks4Port,
            "panel" to prev.panelPort,
            "backup panel" to prev.backupPanelPort
        ).entries.firstOrNull { port == it.value || httpPort == it.value }
        if (clash != null) return reject("Port clashes with ${clash.key} (${clash.value}) - not saved yet")
        val keepaliveUrl = etKeepaliveUrl.text.toString().trim()
        val intervalSec = etKeepaliveInterval.text.toString().toLongOrNull()
        if (intervalSec != null && intervalSec < 15) return reject("Keep-alive interval must be >= 15s - not saved yet")
        return prev.copy(
            ssid = ssid.ifEmpty { ConfigManager.defaultConfig.ssid },
            password = pass,
            port = port,
            band = band,
            proxyType = proxyType,
            httpPort = httpPort,
            keepaliveUrl = keepaliveUrl,
            keepaliveIntervalMs = (intervalSec ?: (prev.keepaliveIntervalMs / 1000)) * 1000L,
            requireApprovalRestart = swRequireApproval.isChecked,
            disableBandSelector = swDisableBand.isChecked,
            keepRetryingReform = swKeepRetrying.isChecked,
            autoRestartOnWifiReturn = swAutoRestart.isChecked,
            updateCheckIntervalHours = updateCheckIntervalHours
        )
    }

    /**
     * File + MediaStore write off the main thread: ConfigManager.save() hits
     * app storage AND the Documents provider, which can stall the UI (ANR
     * risk) on slow storage when run inline. Main thread only (touches views).
     */
    private suspend fun persistCopy(copy: AppConfig): Boolean {
        val err = withContext(Dispatchers.IO) {
            runCatching { ConfigManager.save(this@MainActivity, copy) }
                .exceptionOrNull()?.message
        }
        if (err != null) {
            Log.e(TAG, "autosave failed: $err")
            setSavedMessage("Save failed: $err", R.color.danger)
            return false
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        setSavedMessage("Saved to config.txt \u2713 $time", R.color.saved_ok)
        return true
    }

    private fun autosave() {
        try {
            val copy = buildSaveCopy() ?: return
            setSavedMessage("Saving...", R.color.text_dim)
            lifecycleScope.launch { persistCopy(copy) }
        } catch (e: Exception) {
            Log.e(TAG, "autosave failed", e)
            runCatching {
                setSavedMessage("Save failed: ${e.message}", R.color.danger)
            }
        }
    }

    private fun setSavedMessage(text: String, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        tvSaved.setTextColor(color)
        tvSaved.text = text
    }

    // Ports the running proxy actually bound (snapshotted at start). The
    // info box used to read the editable fields live, so merely typing a new
    // port rewrote the displayed endpoint while the servers still ran on the
    // old one.
    private var snapshotSocksPort = ""
    private var snapshotHttpPort = ""

    private fun updateHeroMetrics() {
        tvSocksPort.text = snapshotSocksPort.ifEmpty { etPort.text.toString().ifEmpty { "1080" } }
        tvHttpPort.text = snapshotHttpPort.ifEmpty { etHttpPort.text.toString().ifEmpty { "8282" } }
    }

    /** Keeps the one-line prompt in sync while the user edits the password. */
    private fun updateHeroSub() {
        if (AppState.running.value) return
        tvHeroSub.text = if (etPass.text.length !in 8..63) {
            "Set a Wi-Fi password (8-63 characters) to start the hotspot."
        } else {
            "Start Ejao to create a private hotspot and route your PC's traffic."
        }
    }

    private fun renderRunning(running: Boolean) {
        runCatching {
            val previous = lastRunning
            lastRunning = running
            if (running) {
                if (snapshotSocksPort.isEmpty()) snapshotSocksPort = etPort.text.toString().ifEmpty { "1080" }
                if (snapshotHttpPort.isEmpty()) snapshotHttpPort = etHttpPort.text.toString().ifEmpty { "8282" }
            } else {
                snapshotSocksPort = ""
                snapshotHttpPort = ""
            }

            tvAppStatus.text = if (running) "LIVE" else "OFFLINE"
            tvAppStatus.setTextColor(
                ContextCompat.getColor(this, if (running) R.color.status_pill_on_text else R.color.status_pill_off_text)
            )
            appStatusDot.setBackgroundResource(if (running) R.drawable.dot_mint else R.drawable.dot_off)

            tvStatus.text = if (running) "RUNNING" else "STOPPED"
            tvStatus.setTextColor(
                ContextCompat.getColor(this, if (running) R.color.accent_text else R.color.text_primary)
            )
            statusDot.setBackgroundResource(if (running) R.drawable.status_dot_running else R.drawable.status_dot_stopped)
            setLamp(running)
            tvHeroSub.text = when {
                running -> "Hotspot is up. Point clients at the endpoints below."
                etPass.text.length !in 8..63 -> "Set a Wi-Fi password (8-63 characters) to start the hotspot."
                else -> "Start Ejao to create a private hotspot and route your PC's traffic."
            }

            btnToggle.text = if (running) "STOP PROXY" else "START PROXY"
            btnToggle.setBackgroundResource(if (running) R.drawable.bg_cta_stop else R.drawable.bg_cta_start)
            val ctaColor = ContextCompat.getColor(this, if (running) R.color.cta_stop_text else R.color.on_primary)
            btnToggle.setTextColor(ctaColor)

            when {
                previous == null -> if (running) showConnection(false) else hideConnection(false)
                previous != running -> if (running) showConnection(true) else hideConnection(true)
            }
            if (!running) {
                tvClients.text = "0"
                tvClients.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            updateHeroMetrics()
        }
    }

    /** Amber signal lamp: steady when stopped, slow pulse while live. */
    private fun setLamp(running: Boolean) {
        lampAnimator?.cancel()
        lampAnimator = null
        if (!running) {
            statusHalo.alpha = 0f
            return
        }
        lampAnimator = ObjectAnimator.ofFloat(statusHalo, View.ALPHA, 0.9f, 0.15f).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun showConnection(animate: Boolean) {
        connectionSection.animate().cancel()
        connectionSection.visibility = View.VISIBLE
        if (!animate) {
            connectionSection.alpha = 1f
            connectionSection.translationY = 0f
            return
        }
        connectionSection.alpha = 0f
        connectionSection.translationY = 12f
        connectionSection.animate().alpha(1f).translationY(0f).setDuration(240).start()
    }

    private fun hideConnection(animate: Boolean) {
        connectionSection.animate().cancel()
        if (!animate) {
            connectionSection.visibility = View.GONE
            connectionSection.alpha = 1f
            connectionSection.translationY = 0f
            return
        }
        connectionSection.animate().alpha(0f).translationY(8f).setDuration(140)
            .withEndAction {
                connectionSection.visibility = View.GONE
                connectionSection.alpha = 1f
                connectionSection.translationY = 0f
            }.start()
    }

    private fun renderInfo(info: ApInfo) {
        runCatching {
            if (!AppState.running.value) return
            tvClients.text = info.clients.toString()
            tvClients.setTextColor(
                ContextCompat.getColor(this, if (info.clients > 0) R.color.accent_text else R.color.text_primary)
            )
            if (info.ssid.isEmpty()) return
            // Prefer the start-time snapshot; fall back to live fields (e.g.
            // service auto-resumed after update without this Activity).
            val socksPort = snapshotSocksPort.ifEmpty {
                runCatching { etPort.text.ifEmpty { "1080" }.toString() }.getOrDefault("1080")
            }
            val httpPort = snapshotHttpPort.ifEmpty {
                runCatching { etHttpPort.text.ifEmpty { "8282" }.toString() }.getOrDefault("8282")
            }
            tvSocksPort.text = socksPort
            tvHttpPort.text = httpPort
            copySocksText = "${info.goIp}:$socksPort"
            copyHttpText = "${info.goIp}:$httpPort"
            tvSocksEndpoint.text = copySocksText
            tvHttpEndpoint.text = copyHttpText

            val panelUrl = if (info.panelPort > 0) "http://${info.goIp}:${info.panelPort}/" else ""
            copyPanelUrl = panelUrl
            tvPanelEndpoint.text = panelUrl.ifEmpty { "\u2014" }
            rowPanel.visibility = if (panelUrl.isEmpty()) View.GONE else View.VISIBLE
            dividerPanel.visibility = rowPanel.visibility

            val backupUrl = if (info.backupPanelPort > 0) "http://${info.goIp}:${info.backupPanelPort}/" else ""
            copyBackupUrl = backupUrl
            tvBackupEndpoint.text = backupUrl.ifEmpty { "\u2014" }
            rowBackup.visibility = if (backupUrl.isEmpty()) View.GONE else View.VISIBLE
            dividerBackup.visibility = rowBackup.visibility
        }
    }

    private fun startSelectedProxy() {
        try {
            val pass = etPass.text.toString()
            Log.i(TAG, "START clicked - passLen=${pass.length}")
            if (pass.length < 8 || pass.length > 63) {
                Log.w(TAG, "password invalid -> refusing to start")
                tvHeroSub.text = "Set a Wi-Fi password (8-63 characters) before starting."
                runCatching {
                    Toast.makeText(this, "Set a WiFi password (8-63 chars) before starting", Toast.LENGTH_LONG).show()
                }
                return
            }
            // Persist first and only start on success: autosave is async now,
            // so starting immediately would boot the proxy on stale config
            // (e.g. edited port not yet written).
            lifecycleScope.launch {
                val copy = try {
                    buildSaveCopy()
                } catch (e: Exception) {
                    Log.e(TAG, "start selected proxy failed", e)
                    null
                }
                if (copy == null) {
                    runCatching {
                        Toast.makeText(this@MainActivity, "Fix the highlighted setting before starting", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (!persistCopy(copy)) return@launch
                checkPermissionsAndStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start selected proxy failed", e)
            runCatching {
                tvHeroSub.text = "Couldn't start: ${e.message}"
                Toast.makeText(this, "Couldn't start proxy: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        try {
            val needed = mutableListOf<String>()
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            val missing = needed.filter {
                runCatching {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }.getOrDefault(true)
            }
            Log.i(TAG, "permissions needed=$needed missing=$missing")
            if (missing.isEmpty()) {
                startProxy()
            } else {
                try {
                    permLauncher.launch(missing.toTypedArray())
                } catch (e: Exception) {
                    Log.e(TAG, "permission request failed, starting anyway", e)
                    startProxy()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "permission check failed", e)
            runCatching { startProxy() }
        }
    }

    private fun startProxy() {
        try {
            // Backup panel first: independent service, binds before WiFi work.
            runCatching { BackupPanelService.start(this) }
            val intent = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            AppState.running.value = true
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed", e)
            AppState.running.value = false
            runCatching {
                tvHeroSub.text = "Couldn't start service (${e.message})"
                Toast.makeText(this, "Couldn't start proxy service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkForUpdate() {
        if (updateInProgress) return
        updateInProgress = true
        runCatching { btnCheckUpdate.isEnabled = false }
        runCatching { tvUpdateStatus.text = "Checking for updates..." }
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatestTag() }.getOrNull() }
                when {
                    latest == null -> {
                        tvUpdateStatus.text = "Couldn't reach the update server. Try again later."
                    }
                    runCatching { !UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME) }.getOrDefault(false) -> {
                        tvUpdateStatus.text = "You're on the latest version (${BuildConfig.VERSION_NAME})."
                        AppState.updateAvailable.value = null
                    }
                    else -> {
                        tvUpdateStatus.text = "Update available: $latest - downloading..."
                        val file = withContext(Dispatchers.IO) {
                            runCatching {
                                UpdateChecker.downloadApk(this@MainActivity, latest) { pct ->
                                    runOnUiThread { runCatching { tvUpdateStatus.text = "Downloading $latest... $pct%" } }
                                }
                            }.getOrNull()
                        }
                        if (file == null) {
                            tvUpdateStatus.text = "Download failed (or hash check failed). Check your connection and try again."
                        } else {
                            AppState.updateAvailable.value = latest
                            tvUpdateStatus.text = "Downloaded $latest - opening installer..."
                            launchInstaller(file)
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { tvUpdateStatus.text = "Update check failed: ${e.message}" }
            } finally {
                updateInProgress = false
                runCatching { btnCheckUpdate.isEnabled = true }
            }
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            if (!apk.exists()) {
                tvUpdateStatus.text = "Update file is missing - please check again."
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launch installer failed", e)
            runCatching { tvUpdateStatus.text = "Couldn't open installer: ${e.message}" }
        }
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "battery check failed", e)
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "battery exemption intent failed", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Log.e(TAG, "battery settings fallback failed", e2)
                runCatching {
                    Toast.makeText(this, "Couldn't open battery settings on this device", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openWiki() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Hushayo/Ejao/wiki")))
        } catch (e: Exception) {
            Log.e(TAG, "open wiki failed", e)
            runCatching {
                Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * OEM autostart managers move between ROMs and several of the old
     * hard-coded component names opened unrelated screens. The app's own
     * system page exists on every device; autostart is one tap from there.
     */
    private fun openAutostartSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "open app settings failed", e)
            runCatching {
                Toast.makeText(this, "Open Settings > Apps > Ejao and allow autostart", Toast.LENGTH_LONG).show()
            }
        }
    }
}
