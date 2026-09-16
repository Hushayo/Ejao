package com.ejao.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel =
        manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
    private var receiver: BroadcastReceiver? = null

    /**
     * Android 12 requires the P2P group network name to begin with "DIRECT-xy"
     * where x and y are alphanumeric. Enforce that here.
     */
    fun normalizeSsid(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) s = ConfigManager.defaultConfig.ssid
        if (s.startsWith("DIRECT-")) {
            val rest = s.removePrefix("DIRECT-")
            if (rest.length >= 2 && rest.take(2).all { it.isLetterOrDigit() }) {
                s = rest
            }
        }
        // sanitize the ENTIRE name to safe chars (letters, digits, dash);
        // anything else (spaces, symbols, emoji) becomes 'A' so the result
        // is always a valid WiFi Direct network name.
        val body = s.map { c ->
            if (c.isLetterOrDigit() || c == '-') c.uppercaseChar() else 'A'
        }.joinToString("").ifEmpty { "EjaoAP" }
        // Android requires "DIRECT-xy" where x,y are the first two alphanumeric
        // chars. Use the code exactly once, then the remainder of the body, so the
        // prefix never overlaps/duplicates the name. SSID is capped at 32 octets.
        val code = body.take(2).padEnd(2, 'A')
        return ("DIRECT-$code" + body.drop(2)).take(32)
    }

    fun registerReceiver(onChanged: (WifiP2pGroup?) -> Unit) {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val group = intent.getParcelableExtra<WifiP2pGroup>(
                            WifiP2pManager.EXTRA_WIFI_P2P_GROUP
                        )
                        onChanged(group)
                    }
                }
            }
        }
        context.applicationContext.registerReceiver(receiver, filter)
    }

    fun unregisterReceiver() {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * Try to auto-enable WiFi. On Android 13+ setWifiEnabled is blocked for apps,
     * so failure here is NOT fatal - we proceed and let createGroup decide.
     */
    fun ensureWifiOn(timeoutMs: Long = 15000): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.isWifiEnabled) return true
        try {
            wifi.isWifiEnabled = true
        } catch (_: Exception) {
            return false
        }
        val start = System.currentTimeMillis()
        while (!wifi.isWifiEnabled && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(300)
        }
        return wifi.isWifiEnabled
    }

    fun removeExistingGroup(onDone: () -> Unit) {
        manager.requestGroupInfo(channel) { group ->
            if (group != null) {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = onDone()
                    override fun onFailure(reason: Int) = onDone()
                })
            } else {
                onDone()
            }
        }
    }

    fun createGroup(ssid: String, password: String, band: String = "2.4", onResult: (Boolean, String) -> Unit) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onResult(true, "Group created")
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Device has no WiFi Direct support"
                    WifiP2pManager.BUSY -> "WiFi Direct busy - retry in a few seconds"
                    WifiP2pManager.ERROR -> "WiFi Direct error"
                    else -> "createGroup failed reason=$reason"
                }
                onResult(false, msg)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(normalizeSsid(ssid))
                    .setPassphrase(password)
                    .build()
                // Android's public WifiP2pConfig.Builder has no band method, but
                // the WifiP2pConfig object carries a hidden `groupOwnerBand` field
                // (0=AUTO, 1=2GHz, 2=5GHz). Set it via reflection so the Group
                // Owner (our hotspot) comes up on the chosen band where supported.
                // Best-effort: if the field/device doesn't honour it, the group
                // just forms on whatever band the firmware picks - never fatal.
                applyGroupOwnerBand(config, band)
                manager.createGroup(channel, config, listener)
                return
            } catch (e: Exception) {
                // fall through to plain createGroup
            }
        }
        manager.createGroup(channel, listener)
    }

    /**
     * Maps our [band] string ("2.4" / "5" / "auto") onto the hidden
     * WifiP2pConfig.groupOwnerBand field. Reflective because the constant and
     * field are @hide in the public SDK.
     */
    private fun applyGroupOwnerBand(config: WifiP2pConfig, band: String) {
        val constValue = when (band) {
            "5" -> 2   // GROUP_OWNER_BAND_5GHZ
            "2.4" -> 1 // GROUP_OWNER_BAND_2GHZ
            else -> 0  // GROUP_OWNER_BAND_AUTO
        }
        if (constValue == 0) return
        try {
            val field = WifiP2pConfig::class.java.getField("groupOwnerBand")
            field.setInt(config, constValue)
        } catch (_: Exception) {
            // Field absent on this Android version/device - ignore, band stays AUTO.
        }
    }

    fun removeGroup(onDone: () -> Unit = {}) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onDone()
            override fun onFailure(reason: Int) = onDone()
        })
    }

    fun requestGroupInfo(onInfo: (WifiP2pGroup?) -> Unit) {
        manager.requestGroupInfo(channel, onInfo)
    }

    fun getGroupOwnerIp(): String {
        val candidates = listOf("p2p0", "p2p-wlan0-0", "p2p-wlan0-1", "p2p-wlan0-2")
        for (name in candidates) {
            try {
                val nif = NetworkInterface.getByName(name) ?: continue
                val addr = nif.inetAddresses.asSequence()
                    .filter { it is InetAddress && it.address.size == 4 }
                    .map { it.hostAddress }
                    .firstOrNull()
                if (addr != null) return addr
            } catch (_: Exception) {
            }
        }
        return "192.168.49.1"
    }
}
