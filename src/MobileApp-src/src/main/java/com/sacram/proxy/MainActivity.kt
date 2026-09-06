package com.sacram.proxy

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
import com.google.android.material.tabs.TabLayout
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
        const val TAG = "SacramMain"
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

    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable { autosave() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        Log.i(TAG, "permission results: $it")
        if (it.values.all { granted -> granted }) {
            startProxy()
        } else {
            val denied = it.filterValues { !it }.keys.joinToString(",")
            Log.e(TAG, "permissions denied: $denied")
            Toast.makeText(this, "Some permissions denied - starting anyway, WiFi Direct may fail", Toast.LENGTH_LONG).show()
            startProxy()
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

        val config = ConfigManager.ensureConfig(this)
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
            tilUpdateCheckInterval.visibility = if (isChecked) View.VISIBLE else View.GONE
            UpdateChecker.scheduleCheck(this, chosenUpdateIntervalHours())
            autosave()
        }
        etKeepaliveUrl.setText(config.keepaliveUrl)
        etKeepaliveInterval.setText((config.keepaliveIntervalMs / 1000).toString())
        chkRequireApprovalRestart.isChecked = config.requireApprovalRestart
        chkRequireApprovalRestart.setOnCheckedChangeListener { _, _ -> autosave() }
        chkDisableBandSelector.isChecked = config.disableBandSelector
        chkDisableBandSelector.setOnCheckedChangeListener { _, _ ->
            applyBandSelectorVisibility(chkDisableBandSelector.isChecked)
            autosave()
        }
        chkKeepRetryingReform = findViewById(R.id.chkKeepRetryingReform)
        chkKeepRetryingReform.isChecked = config.keepRetryingReform
        chkKeepRetryingReform.setOnCheckedChangeListener { _, _ -> autosave() }
        chkAutoRestartOnWifiReturn = findViewById(R.id.chkAutoRestartOnWifiReturn)
        chkAutoRestartOnWifiReturn.isChecked = config.autoRestartOnWifiReturn
        chkAutoRestartOnWifiReturn.setOnCheckedChangeListener { _, _ -> autosave() }
        tilPort = findViewById(R.id.tilPort)
        tilHttpPort = findViewById(R.id.tilHttpPort)
        setupProxyTypeDropdown(config.proxyType)
        updatePortVisibility(config.proxyType)
        setupBandDropdown(config.band)
        applyBandSelectorVisibility(config.disableBandSelector)
        setupUpdateIntervalDropdown(config.updateCheckIntervalHours)
        findViewById<TextView>(R.id.tvConfigPath).text =
            "config.txt: ${ConfigManager.externalConfigFile(this).absolutePath}"

        setupTabs()
        setupAutosave()
        setupEasterEgg()
        observePanelApproval()

        // Notify if no valid password is set, but don't block anything
        if (config.password.length !in 8..63) {
            Log.w(TAG, "no valid password set yet - will error on start until set")
            tvStatus.text = "Stopped - set a WiFi password (8-63 chars) first"
        }

        btnToggle.setOnClickListener {
            if (AppState.running.value) {
                ProxyState.setShouldRun(this, false)
                stopService(Intent(this, ProxyService::class.java))
            } else {
                startSelectedProxy()
            }
        }

        findViewById<Button>(R.id.btnWiki).setOnClickListener { openWiki() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openAutostartSettings() }

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvUpdateStatus.text = "You're running ${BuildConfig.VERSION_NAME} - tap to check for updates."
        btnCheckUpdate.setOnClickListener {
            val ready = AppState.updateAvailable.value
            val file = UpdateChecker.downloadedApkFile(this)
            if (ready != null && file.exists()) {
                launchInstaller(file)
            } else {
                checkForUpdate()
            }
        }
        UpdateChecker.scheduleCheck(this, config.updateCheckIntervalHours)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppState.status.collect { tvStatus.text = it } }
                launch { AppState.apInfo.collect { renderInfo(it) } }
                launch { AppState.running.collect { renderRunning(it) } }
                launch {
                    AppState.updateAvailable.collect { tag ->
                        if (tag != null && UpdateChecker.downloadedApkFile(this@MainActivity).exists()) {
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

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart - passwordLength=${etPass.text.length}, running=${AppState.running.value}")
    }

    override fun onDestroy() {
        saveHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val tabProxy = findViewById<LinearLayout>(R.id.tabProxy)
        val tabKeepalive = findViewById<LinearLayout>(R.id.tabKeepalive)
        val tabCount = 2
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabProxy.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                tabKeepalive.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        // Swipe left/right anywhere on the screen to switch tabs (the app's tabs
        // are plain LinearLayouts, so we detect the horizontal swipe ourselves
        // instead of using a ViewPager). direction -1 = next tab, +1 = previous.
        findViewById<SwipeScrollView>(R.id.mainScroll).onSwipe = { dir ->
            val cur = tabLayout.selectedTabPosition
            val target = (cur + dir).coerceIn(0, tabCount - 1)
            if (target != cur) tabLayout.selectTab(tabLayout.getTabAt(target))
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
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(
                    if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
                    else ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                )
                return v
            }
        }
        etProxyType.setAdapter(adapter)
        etProxyType.setText(PROXY_TYPE_LABELS.getOrElse(selected) { PROXY_TYPE_LABELS[0] }, false)
        applyProxyTypeColor(selected)
        etProxyType.setOnItemClickListener { _, _, position, _ ->
            etProxyType.setText(PROXY_TYPE_LABELS[position], false)
            applyProxyTypeColor(position)
            updatePortVisibility(position)
            autosave()
        }
    }

    private fun applyProxyTypeColor(position: Int) {
        etProxyType.setTextColor(
            if (position in EXPERIMENTAL_TYPES) 0xFFC62828.toInt()
            else ContextCompat.getColor(this, R.color.text_primary)
        )
    }

    /**
     * When the band selector is disabled we hide the band dropdown entirely so
     * the user can't pick a band that won't be applied. The proxy then falls
     * back to the default band (see ProxyService).
     */
    private fun applyBandSelectorVisibility(disabled: Boolean) {
        tilBand.visibility = if (disabled) View.GONE else View.VISIBLE
    }

    private fun setupBandDropdown(selected: String) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, BAND_LABELS)
        etBand.setAdapter(adapter)
        val idx = BAND_VALUES.indexOf(selected).let { if (it < 0) 0 else it }
        etBand.setText(BAND_LABELS[idx], false)
        etBand.setOnItemClickListener { _, _, position, _ ->
            etBand.setText(BAND_LABELS[position], false)
            autosave()
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
            etUpdateCheckInterval.setText(UPDATE_INTERVAL_LABELS[position], false)
            UpdateChecker.scheduleCheck(this, UPDATE_INTERVAL_VALUES[position])
            autosave()
        }
    }

    /**
     * Effective background update-check interval: 0 (disabled) when the toggle
     * is off, otherwise the chosen frequency from the dropdown.
     */
    private fun chosenUpdateIntervalHours(): Int {
        if (!swAutoUpdate.isChecked) return 0
        return UPDATE_INTERVAL_VALUES.getOrElse(UPDATE_INTERVAL_LABELS.indexOf(etUpdateCheckInterval.text.toString())) { 2 }
    }

    /**
     * Show only the relevant port field(s) for the chosen proxy type so the
     * form doesn't waste vertical space. Auto (0) and SOCKS5 (1) -> SOCKS5 port;
     * HTTP (2) -> HTTP port; Hybrid (3) -> both side-by-side in the row.
     * A single visible port expands to full width.
     */
    private fun updatePortVisibility(proxyType: Int) {
        val showSocks = proxyType != 2
        val showHttp = proxyType == 0 || proxyType == 2 || proxyType == 3
        tilPort.visibility = if (showSocks) View.VISIBLE else View.GONE
        tilHttpPort.visibility = if (showHttp) View.VISIBLE else View.GONE
        (tilPort.layoutParams as LinearLayout.LayoutParams).weight = if (showSocks && !showHttp) 2f else 1f
        (tilHttpPort.layoutParams as LinearLayout.LayoutParams).weight = if (showHttp && !showSocks) 2f else 1f
    }

    private var eggTaps = 0
    private var approvalDialog: androidx.appcompat.app.AlertDialog? = null


    private fun setupEasterEgg() {
        tvStatus.setOnClickListener {
            if (++eggTaps >= 7) {
                eggTaps = 0
                Toast.makeText(
                    this,
                    "\uD83D\uDEF0 You found the Sacram easter egg - stay proxy, my friend.",
                    Toast.LENGTH_LONG
                ).show()
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
                    if (req == null) {
                        approvalDialog?.dismiss()
                        approvalDialog = null
                        return@collect
                    }
                    showApprovalDialog(req)
                }
            }
        }
    }

    private fun showApprovalDialog(req: PanelApproval.Request) {
        approvalDialog?.takeIf { it.isShowing }?.dismiss()
        val isRestart = req.fields["action"] == "restart"
        val summary = if (isRestart) {
            "Restart the proxy + hotspot."
        } else {
            req.fields.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Approve panel change?")
            .setMessage(
                "A device on the WiFi requested these setting changes:\n\n$summary\n\n" +
                    "Approve within 10 seconds, otherwise the request is dropped."
            )
            .setCancelable(false)
            .setPositiveButton("Approve") { _, _ -> PanelApproval.approve(this) }
            .setNegativeButton("Deny") { _, _ -> PanelApproval.deny() }
            .create()
        dialog.setOnDismissListener {
            if (PanelApproval.current()?.id == req.id) PanelApproval.deny()
            if (approvalDialog === dialog) approvalDialog = null
        }
        approvalDialog = dialog
        dialog.show()
        lifecycleScope.launch {
            delay(PanelApproval.APPROVE_WINDOW_MS)
            if (PanelApproval.current()?.id == req.id) {
                PanelApproval.deny()
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun autosave() {
        val pass = etPass.text.toString()
        val ssid = etSsid.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        val httpPort = etHttpPort.text.toString().toIntOrNull()
        val proxyType = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let {
            if (it < 0) 0 else it
        }
        val band = BAND_VALUES.getOrElse(BAND_LABELS.indexOf(etBand.text.toString())) { "2.4" }
                val updateCheckIntervalHours = chosenUpdateIntervalHours()
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
                updateCheckIntervalHours = updateCheckIntervalHours
            )
        )
        tvSaved.setTextColor(0xFF2E7D32.toInt())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvSaved.text = "Saved to config.txt \u2713 $time"
    }

    private fun renderRunning(running: Boolean) {
        btnToggle.text = if (running) "STOP PROXY" else "START PROXY"
    }

    private fun renderInfo(info: ApInfo) {
        if (info.ssid.isEmpty()) {
            tvInfo.text = "--"
            tvPanelUrl.text = ""
            return
        }
        val infoLines = mutableListOf(
            "SSID:      ${info.ssid}",
            "Password:  ${info.passphrase}",
            "SOCKS5:    ${info.goIp}:${etPort.text.ifEmpty { "1080" }}",
            "HTTP:      ${info.goIp}:${etHttpPort.text.ifEmpty { "8282" }}"
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

    private fun startSelectedProxy() {
        val pass = etPass.text.toString()
        Log.i(TAG, "START clicked - passLen=${pass.length}")
        if (pass.length < 8 || pass.length > 63) {
            Log.w(TAG, "password invalid -> refusing to start")
            tvStatus.text = "ERROR: set a WiFi password (8-63 chars) first"
            Toast.makeText(this, "Set a WiFi password (8-63 chars) before starting", Toast.LENGTH_LONG).show()
            return
        }
        val idx = PROXY_TYPE_LABELS.indexOf(etProxyType.text.toString()).let { if (it < 0) 0 else it }
        etProxyType.setText(PROXY_TYPE_LABELS[idx], false)
        autosave()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "permissions needed=$needed missing=$missing")
        if (missing.isEmpty()) {
            startProxy()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startProxy() {
        val intent = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        AppState.running.value = true
    }

    private fun checkForUpdate() {
        if (updateInProgress) return
        updateInProgress = true
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "Checking for updates..."
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { UpdateChecker.fetchLatestTag() }
                when {
                    latest == null -> {
                        tvUpdateStatus.text = "Couldn't reach the update server. Try again later."
                    }
                    !UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME) -> {
                        tvUpdateStatus.text = "You're on the latest version (${BuildConfig.VERSION_NAME})."
                        AppState.updateAvailable.value = null
                    }
                    else -> {
                        tvUpdateStatus.text = "Update available: $latest - downloading..."
                        val file = withContext(Dispatchers.IO) {
                            UpdateChecker.downloadApk(this@MainActivity, latest) { pct ->
                                runOnUiThread { tvUpdateStatus.text = "Downloading $latest... $pct%" }
                            }
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
                tvUpdateStatus.text = "Update check failed: ${e.message}"
            } finally {
                updateInProgress = false
                btnCheckUpdate.isEnabled = true
            }
        }
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            tvUpdateStatus.text = "Couldn't open installer: ${e.message}"
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openWiki() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SynacNipo/Sacram/wiki")))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAutostartSettings() {
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
        Toast.makeText(this, "Open Settings > Apps > Sacram and enable Autostart", Toast.LENGTH_LONG).show()
    }
}
