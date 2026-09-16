package com.ejao.proxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

object NetworkUtils {

    fun pickCellular(cm: ConnectivityManager, preferred: Network?): Network? {
        if (isValidCellular(cm, preferred)) return preferred
        val nets = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        val active = runCatching { cm.activeNetwork }.getOrNull()
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
