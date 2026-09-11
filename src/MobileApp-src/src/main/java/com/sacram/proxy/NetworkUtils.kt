package com.sacram.proxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Resilient selection of the outbound (cellular) network.
 *
 * The proxy must bind every upstream socket/DNS lookup to the phone's cellular
 * data interface, but the single [ConnectivityManager.NetworkCallback]-held
 * reference used elsewhere can be nulled out by a transient [android.net.ConnectivityManager.NetworkCallback.onLost]
 * (frequent while the device is a WiFi-Direct Group Owner). When that happens
 * all egress silently fell back to the WiFi-Direct interface (no internet),
 * taking the whole proxy down until the callback refired.
 *
 * This helper re-validates the preferred network on every use and, failing that,
 * scans [ConnectivityManager.getAllNetworks] for a cellular network that
 * actually has internet, only then falling back to the active network.
 */
object NetworkUtils {

    fun pickCellular(cm: ConnectivityManager, preferred: Network?): Network? {
        if (isValidCellular(cm, preferred)) return preferred
        val nets = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        // Prefer the system's ACTIVE network if it actually has internet. This is
        // the network the phone itself uses for its own traffic (e.g. keep-alive
        // probes and update checks reach the internet over it), so it is the
        // most reliable egress. Some devices (e.g. HONOR) report a cellular network
        // flagged with INTERNET that nonetheless does not route, while the active
        // network (WiFi/another cellular path) works fine - binding to the dead
        // cellular interface made every proxy request fail even though the phone
        // had working internet. The WiFi-Direct P2P interface lacks
        // NET_CAPABILITY_INTERNET, so it is never selected here.
        val active = runCatching { cm.activeNetwork }.getOrNull()
        // Prefer VALIDATED networks first, fall back to INTERNET-only (Honor
        // workaround: some OEMs report INTERNET without VALIDATED on a working
        // path, so VALIDATED must never be required).
        if (isValidatedEgress(cm, active)) return active
        for (n in nets) {
            if (isValidCellular(cm, n) && isValidatedEgress(cm, n)) return n
        }
        for (n in nets) {
            if (isValidatedEgress(cm, n)) return n
        }
        if (isValidEgress(cm, active)) return active
        for (n in nets) {
            if (isValidCellular(cm, n)) return n
        }
        // Fallback: any network that actually has internet (e.g. WiFi, or a
        // carrier/VPN path), not just cellular.
        for (n in nets) {
            if (isValidEgress(cm, n)) return n
        }
        return null
    }

    private fun isValidCellular(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isValidEgress(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isValidatedEgress(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
