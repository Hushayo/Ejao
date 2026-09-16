package com.ejao.proxy

import android.Manifest
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
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import android.view.animation.DecelerateInterpolator
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
        val PROXY_TYPE_LABELS = listOf(
            "Auto (SOCKS5 + HTTP)"
        )
        val EXPERIMENTAL_TYPES = emptySet<Int>()
        // Band picker. Index order MUST match BAND_VALUES.
        val BAND_LABELS = listOf("2.4 GHz", "5 GHz (default)", "Auto")
        val BAND_VALUES = listOf("2.4", "5", "auto")
        // Update-check frequency picker (used when background checks are ON).
        // Index order MUST match UPDATE_INTERVAL_VALUES.
        val UPDATE_INTERVAL_LABELS = listOf("Every 1 hour", "Every 3 hours", "Every 6 hours (default)", "Every 12 hours", "Every 24 hours")
        val UPDATE_INTERVAL_VALUES = listOf(1, 3, 6, 12, 24)
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvSaved: TextView
    private lateinit var btnToggle: Button
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etBand: AutoCompleteTextView
    private lateinit var etPort: EditText
    private lateinit var etProxyType: AutoCompleteTextView
    private lateinit var etHttpPort: EditText
    private lateinit var etKeepaliveUrl: EditText
    private lateinit var etKeepaliveInterval: EditText
    private lateinit var chkRequireApprovalRestart: CheckBox
    private lateinit var chkDisableBandSelector: CheckBox
    private lateinit var chkKeepRetryingReform: CheckBox
    private lateinit var chkAutoRestartOnWifiReturn: CheckBox
    private lateinit var tvPanelUrl: TextView
    private lateinit var tilPort: com.google.android.material.textfield.TextInputLayout
    private lateinit var tilHttpPort: com.google.android.material.textfield.TextInputLayout
    private lateinit var btnCheckUpdate: Button
    private lateinit var tvUpdateStatus: TextView
    private var updateInProgress = false
    private lateinit var tilBand: com.google.android.material.textfield.TextInputLayout
    private lateinit var tilUpdateCheckInterval: com.google.android.material.textfield.TextInputLayout
    private lateinit var etUpdateCheckInterval: AutoCompleteTextView
    private lateinit var swAutoUpdate: SwitchMaterial
    private lateinit var swBetaUpdates: SwitchMaterial

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
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        tvSaved = findViewById(R.id.tvSaved)
        btnToggle = findViewById(R.id.btnToggle)
        etSsid = findViewById(R.id.etSsid)
        etPass = findViewById(R.id.etPass)
        etBand = findViewById(R.id.etBand)
        etPort = findViewById(R.id.etPort)
        etProxyType = findViewById(R.id.etProxyType)
        etHttpPort = findViewById(R.id.etHttpPort)

        val config = runCatching { ConfigManager.ensureConfig(this) }
            .getOrDefault(ConfigManager.defaultConfig)
        etSsid.setText(config.ssid)
        etPass.setText(config.password)
        etPort.setText(config.port.toString())
        etHttpPort.setText(config.httpPort.toString())
        etKeepaliveUrl = findViewById(R.id.etKeepaliveUrl)
        etKeepaliveInterval = findViewById(R.id.etKeepaliveInterval)
        chkRequireApprovalRestart = findViewById(R.id.chkRequireApprovalRestart)
        chkDisableBandSelector = findViewById(R.id.chkDisableBandSelector)
        tvPanelUrl = findViewById(R.id.tvPanelUrl)
        tilBand = findViewById(R.id.tilBand)
        etUpdateCheckInterval = findViewById(R.id.etUpdateCheckInterval)
        tilUpdateCheckInterval = findViewById(R.id.tilUpdateCheckInterval)
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
        val autoUpdateOn = config.updateCheckIntervalHours > 0
        swAutoUpdate.isChecked = autoUpdateOn
        tilUpdateCheckInterval.visibility = if (autoUpdateOn) View.VISIBLE else View.GONE
        swAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            try {
                tilUpdateCheckInterval.visibility = if (isChecked) View.VISIBLE else View.GONE
                runCatching { UpdateChecker.scheduleCheck(this, chosenUpdateIntervalHours()) }
                autosave()
            } catch (e: Exception) {
                Log.e(TAG, "auto-update toggle failed", e)
            }
        }
        swBetaUpdates = findViewById(R.id.swBetaUpdates)
        swBetaUpdates.isChecked = config.updateChannel == "beta"
        swBetaUpdates.setOnCheckedChangeListener { _, isChecked ->
            try {
                // Switching channels invalidates any pending update from the
                // other channel so it can never be installed by mistake.
                AppState.updateAvailable.value = null
                runCatching {
                    val f = UpdateChecker.downloadedApkFile(this)
                    if (f.exists()) f.delete()
                }
                autosave()
                runCatching {
                    tvUpdateStatus.text = if (isChecked) {
                        "Beta channel - you'll get networking test builds only (${BuildConfig.VERSION_NAME})."
                    } else {
                        "Stable channel - you'll get normal releases only (${BuildConfig.VERSION_NAME})."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "beta toggle failed", e)
            }
        }
        etKeepaliveUrl.setText(config.keepaliveUrl)
        etKeepaliveInterval.setText((config.keepaliveIntervalMs / 1000).toString())
        chkRequireApprovalRestart.isChecked = config.requireApprovalRestart
        chkRequireApprovalRestart.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        chkDisableBandSelector.isChecked = config.disableBandSelector
        chkDisableBandSelector.setOnCheckedChangeListener { _, _ ->
            runCatching {
                applyBandSelectorVisibility(chkDisableBandSelector.isChecked)
                autosave()
            }
        }
        chkKeepRetryingReform = findViewById(R.id.chkKeepRetryingReform)
        chkKeepRetryingReform.isChecked = config.keepRetryingReform
        chkKeepRetryingReform.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        chkAutoRestartOnWifiReturn = findViewById(R.id.chkAutoRestartOnWifiReturn)
        chkAutoRestartOnWifiReturn.isChecked = config.autoRestartOnWifiReturn
        chkAutoRestartOnWifiReturn.setOnCheckedChangeListener { _, _ -> runCatching { autosave() } }
        tilPort = findViewById(R.id.tilPort)
        tilHttpPort = findViewById(R.id.tilHttpPort)
        setupProxyTypeDropdown(config.proxyType)
        updatePortVisibility(config.proxyType)
        setupBandDropdown(config.band)
        applyBandSelectorVisibility(config.disableBandSelector)
        setupUpdateIntervalDropdown(config.updateCheckIntervalHours)
        runCatching {
            findViewById<TextView>(R.id.tvConfigPath).text =
                "config.txt: ${runCatching { ConfigManager.externalConfigFile(this).absolutePath }.getOrDefault("config.txt")}"
        }

        runCatching { setupTabs() }
        runCatching { setupAutosave() }
        runCatching { setupEasterEgg() }
        runCatching { observePanelApproval() }

        // Notify if no valid password is set, but don't block anything
        if (config.password.length !in 8..63) {
            Log.w(TAG, "no valid password set yet - will error on start until set")
            tvStatus.text = "Stopped - set a WiFi password (8-63 chars) first"
        }

        btnToggle.setOnClickListener {
            runCatching {
                if (AppState.running.value) {
                    ProxyState.setShouldRun(this, false)
                    stopService(Intent(this, ProxyService::class.java))
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

        findViewById<Button>(R.id.btnWiki).setOnClickListener { runCatching { openWiki() } }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { runCatching { requestBatteryExemption() } }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { runCatching { openAutostartSettings() } }

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvUpdateStatus.text = if (config.updateChannel == "beta") {
            "Beta channel - test builds only. You're running ${BuildConfig.VERSION_NAME}."
        } else {
            "Stable channel - normal releases only. You're running ${BuildConfig.VERSION_NAME}."
        }
        btnCheckUpdate.setOnClickListener {
            runCatching {
                val ready = AppState.updateAvailable.value
                val file = runCatching { UpdateChecker.downloadedApkFile(this) }.getOrNull()
                if (ready != null && file != null && file.exists()) {
                    launchInstaller(file)
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
                launch { AppState.status.collect { runCatching { tvStatus.text = it } } }
                launch { AppState.apInfo.collect { runCatching { renderInfo(it) } } }
                launch { AppState.running.collect { runCatching { renderRunning(it) } } }
                launch {
                    AppState.updateAvailable.collect { tag ->
                        runCatching {
                            val apkExists = runCatching { UpdateChecker.downloadedApkFile(this@MainActivity).exists() }.getOrDefault(false)
                            if (tag != null && apkExists) {
                                btnCheckUpdate.text = "Install update ($tag)"
                                tvUpdateStatus.text = "Update $tag downloaded in the background - tap to install."
                            } else {
                                btnCheckUpdate.text = "Check for updates"
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
        saveHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    private fun setupTabs() {
        val tabProxy = findViewById<LinearLayout>(R.id.tabProxy)
        val tabKeepalive = findViewById<LinearLayout>(R.id.tabKeepalive)
        val pill = findViewById<View>(R.id.bottomPill)
        val indicator = findViewById<View>(R.id.pillIndicator)
        val btnProxy = findViewById<TextView>(R.id.pillProxy)
        val btnKeep = findViewById<TextView>(R.id.pillKeep)
        var selected = 0
        fun paint() {
            btnProxy.setTextColor(if (selected == 0) 0xFF171412.toInt() else 0xFFB8A99F.toInt())
            btnKeep.setTextColor(if (selected == 1) 0xFF171412.toInt() else 0xFFB8A99F.toInt())
        }
        // Slides the ink capsule behind the active pill button. Both buttons
        // live in the same padded FrameLayout as the indicator, so the target
        // button's left edge is already the correct translationX.
        fun place(animate: Boolean) {
            val target = if (selected == 0) btnProxy else btnKeep
            if (target.width == 0 || target.height == 0) return
            val params = indicator.layoutParams
            // Only touch layoutParams when the size actually changed: assigning
            // them always triggers requestLayout, and this runs from pill's own
            // onLayoutChangeListener, so an unconditional assign loops forever.
            if (params.width != target.width || params.height != target.height) {
                params.width = target.width
                params.height = target.height
                indicator.layoutParams = params
            }
            if (animate) {
                indicator.animate().translationX(target.left.toFloat())
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                indicator.translationX = target.left.toFloat()
            }
        }
        fun select(i: Int, animate: Boolean = true) {
            val changed = i != selected
            selected = i
            tabProxy.visibility = if (i == 0) View.VISIBLE else View.GONE
            tabKeepalive.visibility = if (i == 1) View.VISIBLE else View.GONE
            paint()
            if (changed || !animate) place(animate)
        }
        btnProxy.setOnClickListener { select(0) }
        btnKeep.setOnClickListener { select(1) }
        btnProxy.post { select(0, animate = false) }
        // Re-glue the capsule after rotations/resizes change button widths.
        pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(false) }
        // Swipe left/right anywhere on the screen to switch tabs (the app's tabs
        // are plain LinearLayouts, so we detect the horizontal swipe ourselves
        // instead of using a ViewPager). direction -1 = next tab, +1 = previous.
        findViewById<SwipeScrollView>(R.id.mainScroll).onSwipe = { dir ->
            val target = (selected + dir).coerceIn(0, 1)
            if (target != selected) select(target)
        }
    }

    private fun setupAutosave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveHandler.removeCallbacks(autosaveRunnable)
                saveHandler.postDelayed(autosaveRunnable, 1200)
            }

            override fun afterTextChanged(s: Editable?) {}
        }
        etSsid.addTextChangedListener(watcher)
        etPass.addTextChangedListener(watcher)
        etBand.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveHandler.removeCallbacks(autosaveRunnable)
                saveHandler.postDelayed(autosaveRunnable, 1200)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etPort.addTextChangedListener(watcher)
        etHttpPort.addTextChangedListener(watcher)
        etKeepaliveUrl.addTextChangedListener(watcher)
        etKeepaliveInterval.addTextChangedListener(watcher)
    }

    private fun setupProxyTypeDropdown(selected: Int) {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            PROXY_TYPE_LABELS
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                runCatching {
                    val tv = v.findViewById<TextView>(android.R.id.text1)
                    tv?.setTextColor(
                        if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
                        else ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                    )
                }
                return v
            }
        }
        etProxyType.setAdapter(adapter)
        etProxyType.setText(PROXY_TYPE_LABELS.getOrElse(selected) { PROXY_TYPE_LABELS[0] }, false)
        applyProxyTypeColor(selected)
        etProxyType.setOnItemClickListener { _, _, position, _ ->
            runCatching {
                etProxyType.setText(PROXY_TYPE_LABELS[position], false)
                applyProxyTypeColor(position)
                updatePortVisibility(position)
                autosave()
            }
        }
    }

    private fun applyProxyTypeColor(position: Int) {
        runCatching {
            etProxyType.setTextColor(
                if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
                else ContextCompat.getColor(this, R.color.text_primary)
            )
        }
    }

    /**
     * When the band selector is disabled we hide the band dropdown entirely so
     * the user can't pick a band that won't be applied. The proxy then falls
     * back to the default band (see ProxyService).
     */
    private fun applyBandSelectorVisibility(disabled: Boolean) {
        runCatching {
            tilBand.visibility = if (disabled) View.GONE else View.VISIBLE
        }
    }

    private fun setupBandDropdown(selected: String) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, BAND_LABELS)
        etBand.setAdapter(adapter)
        val idx = BAND_VALUES.indexOf(selected).let { if (it < 0) 0 else it }
        etBand.setText(BAND_LABELS[idx], false)
        etBand.setOnItemClickListener { _, _, position, _ ->
            runCatching {
                etBand.setText(BAND_LABELS[position], false)
                autosave()
            }
        }
    }

    /**
     * Background update-check interval dropdown. Saving re-schedules (or
     * cancels) the WorkManager job immediately via UpdateChecker.scheduleCheck
     * - no proxy restart needed for this to take effect.
     */
    private fun setupUpdateIntervalDropdown(selectedHours: Int) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, UPDATE_INTERVAL_LABELS)
        etUpdateCheckInterval.setAdapter(adapter)
        val idx = UPDATE_INTERVAL_VALUES.indexOf(selectedHours).let { if (it < 0) 2 else it }
        etUpdateCheckInterval.setText(UPDATE_INTERVAL_LABELS[idx], false)
        etUpdateCheckInterval.setOnItemClickListener { _, _, position, _ ->
            runCatching {
                etUpdateCheckInterval.setText(UPDATE_INTERVAL_LABELS[position], false)
                UpdateChecker.scheduleCheck(this, UPDATE_INTERVAL_VALUES[position])
                autosave()
            }
        }
    }

    /**
     * Effective background update-check interval: 0 (disabled) when the toggle
     * is off, otherwise the chosen frequency from the dropdown.
     */
    private fun chosenUpdateIntervalHours(): Int {
        return try {
            if (!swAutoUpdate.isChecked) return 0
            UPDATE_INTERVAL_VALUES.getOrElse(UPDATE_INTERVAL_LABELS.indexOf(etUpdateCheckInterval.text.toString())) { 2 }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Show only the relevant port field(s) for the chosen proxy type so the
     * form doesn't waste vertical space. Auto (0) and SOCKS5 (1) -> SOCKS5 port;
     * HTTP (2) -> HTTP port; Hybrid (3) -> both side-by-side in the row.
     * A single visible port expands to full width.
     */
    private fun updatePortVisibility(proxyType: Int) {
        runCatching {
            val showSocks = proxyType != 2
            val showHttp = proxyType == 0 || proxyType == 2 || proxyType == 3
            tilPort.visibility = if (showSocks) View.VISIBLE else View.GONE
            tilHttpPort.visibility = if (showHttp) View.VISIBLE else View.GONE
            (tilPort.layoutParams as? LinearLayout.LayoutParams)?.weight =
                if (showSocks && !showHttp) 2f else 1f
            (tilHttpPort.layoutParams as? LinearLayout.LayoutParams)?.weight =
                if (showHttp && !showSocks) 2f else 1f
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

    private fun autosave() {
        try {
            val pass = etPass.text.toString()
            val ssid = etSsid.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull()
            val httpPort = etHttpPort.text.toString().toIntOrNull()
            val proxyType = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let {
                if (it < 0) 0 else it
            }
            val band = BAND_VALUES.getOrElse(BAND_LABELS.indexOf(etBand.text.toString())) { "2.4" }
            val updateCheckIntervalHours = chosenUpdateIntervalHours()
            val updateChannel = if (runCatching { swBetaUpdates.isChecked }.getOrDefault(false)) "beta" else "stable"
        if (pass.length !in 8..63) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Password must be 8-63 characters - not saved yet"
            return
        }
        if (port == null || port < 1 || port > 65535) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Invalid port - not saved yet"
            return
        }
        if (httpPort == null || httpPort < 1 || httpPort > 65535) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Invalid HTTP port - not saved yet"
            return
        }
        val keepaliveUrl = etKeepaliveUrl.text.toString().trim()
        val intervalSec = etKeepaliveInterval.text.toString().toLongOrNull()
        if (intervalSec != null && intervalSec < 15) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Keep-alive interval must be >= 15s - not saved yet"
            return
        }
        if (proxyType !in 0..3) {
            tvSaved.setTextColor(0xFFC62828.toInt())
            tvSaved.text = "Proxy type must be 0, 1, 2, or 3 - not saved yet"
            return
        }
        val prev = ConfigManager.load(this)
        ConfigManager.save(
            this,
            prev.copy(
                ssid = ssid.ifEmpty { ConfigManager.defaultConfig.ssid },
                password = pass,
                port = port,
                band = band,
                proxyType = proxyType,
                httpPort = httpPort,
                keepaliveUrl = keepaliveUrl,
                keepaliveIntervalMs = (intervalSec ?: (prev.keepaliveIntervalMs / 1000)) * 1000L,
                requireApprovalRestart = chkRequireApprovalRestart.isChecked,
                disableBandSelector = chkDisableBandSelector.isChecked,
                keepRetryingReform = chkKeepRetryingReform.isChecked,
                autoRestartOnWifiReturn = chkAutoRestartOnWifiReturn.isChecked,
                updateCheckIntervalHours = updateCheckIntervalHours,
                updateChannel = updateChannel
            )
        )
        tvSaved.setTextColor(0xFF2E7D32.toInt())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvSaved.text = "Saved to config.txt \u2713 $time"
        } catch (e: Exception) {
            Log.e(TAG, "autosave failed", e)
            runCatching {
                tvSaved.setTextColor(0xFFC62828.toInt())
                tvSaved.text = "Save failed: ${e.message}"
            }
        }
    }

    private fun renderRunning(running: Boolean) {
        runCatching {
            btnToggle.text = if (running) "STOP PROXY" else "START PROXY"
        }
    }

    private fun renderInfo(info: ApInfo) {
        runCatching {
            if (info.ssid.isEmpty()) {
                tvInfo.text = "--"
                tvPanelUrl.text = ""
                return
            }
            val socksPort = runCatching { etPort.text.ifEmpty { "1080" }.toString() }.getOrDefault("1080")
            val httpPort = runCatching { etHttpPort.text.ifEmpty { "8282" }.toString() }.getOrDefault("8282")
            val infoLines = mutableListOf(
                "SSID:      ${info.ssid}",
                "Password:  ${info.passphrase}",
                "SOCKS5:    ${info.goIp}:$socksPort",
                "HTTP:      ${info.goIp}:$httpPort"
            )
            if (info.panelPort > 0) infoLines.add("Panel:     http://${info.goIp}:${info.panelPort}/")
            if (info.backupPanelPort > 0) infoLines.add("Backup:    http://${info.goIp}:${info.backupPanelPort}/ (use if proxy down)")
            infoLines.add("Clients:   ${info.clients}")
            tvInfo.text = infoLines.joinToString("\n")
            tvPanelUrl.text = buildString {
                if (info.panelPort > 0) append("Control panel runs on its own port:\nhttp://${info.goIp}:${info.panelPort}/\n")
                if (info.backupPanelPort > 0) append("Backup panel (survives proxy crash):\nhttp://${info.goIp}:${info.backupPanelPort}/")
            }.trim().ifEmpty { "" }
        }
    }

    private fun startSelectedProxy() {
        try {
            val pass = etPass.text.toString()
            Log.i(TAG, "START clicked - passLen=${pass.length}")
            if (pass.length < 8 || pass.length > 63) {
                Log.w(TAG, "password invalid -> refusing to start")
                tvStatus.text = "ERROR: set a WiFi password (8-63 chars) first"
                runCatching {
                    Toast.makeText(this, "Set a WiFi password (8-63 chars) before starting", Toast.LENGTH_LONG).show()
                }
                return
            }
            val idx = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let { if (it < 0) 0 else it }
            runCatching { etProxyType.setText(PROXY_TYPE_LABELS[idx], false) }
            runCatching { autosave() }
            checkPermissionsAndStart()
        } catch (e: Exception) {
            Log.e(TAG, "start selected proxy failed", e)
            runCatching {
                tvStatus.text = "ERROR: couldn't start (${e.message})"
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
            val intent = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            AppState.running.value = true
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed", e)
            AppState.running.value = false
            runCatching {
                tvStatus.text = "ERROR: couldn't start service (${e.message})"
                Toast.makeText(this, "Couldn't start proxy service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkForUpdate() {
        if (updateInProgress) return
        updateInProgress = true
        val channel = runCatching { ConfigManager.load(this).updateChannel }.getOrDefault("stable")
        val channelLabel = if (channel == "beta") "beta" else "stable"
        runCatching { btnCheckUpdate.isEnabled = false }
        runCatching { tvUpdateStatus.text = "Checking for $channelLabel updates..." }
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatestTag(channel) }.getOrNull() }
                when {
                    latest == null -> {
                        tvUpdateStatus.text = if (channel == "beta") {
                            "No beta builds found yet. Try again later."
                        } else {
                            "Couldn't reach the update server. Try again later."
                        }
                    }
                    runCatching { !UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME, channel) }.getOrDefault(false) -> {
                        tvUpdateStatus.text = "You're on the latest $channelLabel version (${BuildConfig.VERSION_NAME})."
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
                            tvUpdateStatus.text = "Download failed. Check your connection and try again."
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mjoohos23/Ejao/wiki")))
        } catch (e: Exception) {
            Log.e(TAG, "open wiki failed", e)
            runCatching {
                Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAutostartSettings() {
        try {
            val intents = listOf(
                Intent().setClassName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            for (i in intents) {
                try {
                    startActivity(i)
                    return
                } catch (_: Exception) {
                }
            }
            Toast.makeText(this, "Open Settings > Apps > Ejao and enable Autostart", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "open autostart failed", e)
            runCatching {
                Toast.makeText(this, "Couldn't open settings on this device", Toast.LENGTH_LONG).show()
            }
        }
    }
}
