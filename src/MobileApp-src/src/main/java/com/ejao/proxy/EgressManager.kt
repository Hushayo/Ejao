package com.ejao.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

// Shared egress: synchronized network pick + DNS + dead-cellular pinning.
object EgressManager {
    private const val TAG = "EgressManager"
    private const val CACHE_MS = 8000L
    private const val DNS_TTL_MS = 60_000L
    private const val DNS_TIMEOUT_MS = 5_000L
    private const val PIN_MS = 5 * 60_000L
    private const val HOST_FAIL_THRESHOLD = 2
    private const val PROBE_INTERVAL_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 2000

    private val lock = Any()
    private var cachedNet: Network? = null
    private var cachedNetTime = 0L

    private var cmRef: ConnectivityManager? = null
    private var proberJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Recreatable so shutdown() is honest: the old code kept one pool for the
    // process lifetime (bounded one-time cost, but shutdown() claimed to reset
    // everything while the pool + its threads survived). Now shutdown drains
    // it and the next resolve() lazily builds a fresh one under lock.
    private var dnsExecutor = Executors.newFixedThreadPool(16)
    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    private data class HostStat(
        var consecutiveFails: Int = 0,
        var preferDefaultUntil: Long = 0L
    )
    private val hostStats = ConcurrentHashMap<String, HostStat>()

    private val pinnedDefaultUntil = AtomicLong(0L)
    private val cellularFailStreak = AtomicInteger(0)

    private val lastSuccessMs = AtomicLong(0L)
    private val lastFailureMs = AtomicLong(0L)

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            runCatching {
                cmRef = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            }
            initialized = true
        }
        startProber()
    }

    fun pickNet(cm: ConnectivityManager, host: String? = null): Network? {
        val now = System.currentTimeMillis()
        if (now < pinnedDefaultUntil.get()) {
            val active = runCatching { cm.activeNetwork }.getOrNull()
            if (NetworkUtils.isValidEgress(cm, active)) return active
            synchronized(lock) {
                val c = cachedNet
                if (c != null && NetworkUtils.isValidEgress(cm, c)) return c
            }
            return active
        }
        if (host != null && shouldPreferDefault(host)) {
            val active = runCatching { cm.activeNetwork }.getOrNull()
            if (NetworkUtils.isValidEgress(cm, active)) return active
        }
        synchronized(lock) {
            val active = runCatching { cm.activeNetwork }.getOrNull()
            if (NetworkUtils.isValidEgress(cm, active)) {
                if (cachedNet != active) {
                    cachedNet = active
                    cachedNetTime = now
                }
                return active
            }
            val cached = cachedNet
            if (cached != null && now - cachedNetTime < CACHE_MS &&
                NetworkUtils.isValidEgress(cm, cached)
            ) {
                return cached
            }
            val n = NetworkUtils.pickCellular(cm, null) ?: cached
            cachedNet = n
            cachedNetTime = now
            return n
        }
    }

    fun resolve(host: String, net: Network?): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (it.second > now) return it.first }
        val exec = synchronized(lock) {
            if (dnsExecutor.isShutdown || dnsExecutor.isTerminated) {
                dnsExecutor = Executors.newFixedThreadPool(16)
            }
            dnsExecutor
        }
        val future = try {
            exec.submit<List<InetAddress>> {
                (if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host))
                    ?.toList().orEmpty()
            }
        } catch (e: Exception) {
            throw IOException("DNS submit failed for $host via $net", e)
        }
        val addrs = try {
            future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IOException("DNS timeout for $host via $net", e)
        } catch (e: Exception) {
            future.cancel(true)
            throw IOException("DNS failed for $host via $net", e)
        }
        if (addrs.isEmpty()) throw IOException("DNS empty for $host via $net")
        val ordered = addrs.filter { it.address.size == 4 } + addrs.filter { it.address.size != 4 }
        dnsCache[host] = ordered to (now + DNS_TTL_MS)
        return ordered
    }

    fun clearDns() = dnsCache.clear()

    fun clearDnsHost(host: String) {
        runCatching { dnsCache.remove(host) }
    }

    fun invalidateCache() {
        synchronized(lock) {
            cachedNet = null
            cachedNetTime = 0L
        }
        clearDns()
    }

    fun reportSuccess(host: String? = null) {
        lastSuccessMs.set(System.currentTimeMillis())
        cellularFailStreak.set(0)
        if (host != null) {
            hostStats[host]?.let {
                it.consecutiveFails = 0
                it.preferDefaultUntil = 0L
            }
        }
    }

    fun reportFailure(host: String? = null) {
        lastFailureMs.set(System.currentTimeMillis())
        if (host != null) {
            val s = hostStats.computeIfAbsent(host) { HostStat() }
            s.consecutiveFails++
            if (s.consecutiveFails >= HOST_FAIL_THRESHOLD) {
                s.preferDefaultUntil = System.currentTimeMillis() + PIN_MS
                // Drop the cached DNS for this host: after a handover the
                // old IP can be dead, and replaying it for the full 60s TTL
                // turns one blip into a burst of Proxifier "close" + fake-IP
                // errors. Next attempt re-resolves on the fresh network.
                runCatching { dnsCache.remove(host) }
            }
        }
    }

    fun shouldPreferDefault(host: String): Boolean {
        val s = hostStats[host] ?: return false
        if (System.currentTimeMillis() > s.preferDefaultUntil) {
            s.consecutiveFails = 0
            s.preferDefaultUntil = 0L
            return false
        }
        return s.preferDefaultUntil > 0L
    }

    fun lastSuccess(): Long = lastSuccessMs.get()
    fun lastFailure(): Long = lastFailureMs.get()

    private fun startProber() {
        if (proberJob?.isActive == true) return
        proberJob = scope.launch {
            delay(10_000)
            while (isActive) {
                try {
                    probeOnce()
                } catch (_: Exception) {
                }
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private fun probeOnce() {
        val cm = cmRef ?: return
        val nets = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        val cellular = nets.firstOrNull {
            runCatching {
                val caps = cm.getNetworkCapabilities(it)
                caps != null && caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_CELLULAR
                ) && caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
            }.getOrDefault(false)
        }
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (cellular == null || active == null || cellular == active) {
            return
        }
        val cellOk = probeNetwork(cellular)
        val activeOk = probeNetwork(active)
        if (!cellOk && activeOk) {
            val streak = cellularFailStreak.incrementAndGet()
            if (streak >= 3) {
                pinnedDefaultUntil.set(System.currentTimeMillis() + PIN_MS)
                Log.w(TAG, "cellular dead but default works - pinning to default for 5min")
                invalidateCache()
                cellularFailStreak.set(0)
            }
        } else if (cellOk) {
            cellularFailStreak.set(0)
            if (System.currentTimeMillis() < pinnedDefaultUntil.get()) {
                pinnedDefaultUntil.set(0L)
                Log.i(TAG, "cellular recovered - pin lifted")
            }
        }
    }

    private fun probeNetwork(net: Network): Boolean {
        return try {
            val sock = Socket()
            try {
                net.bindSocket(sock)
                sock.connect(InetSocketAddress("8.8.8.8", 53), PROBE_TIMEOUT_MS)
                true
            } finally {
                runCatching { sock.close() }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shutdown() {
        runCatching { proberJob?.cancel() }
        proberJob = null
        synchronized(lock) {
            cachedNet = null
            cachedNetTime = 0L
            initialized = false
            cmRef = null
            // Drain the DNS pool so restart cycles don't accumulate threads.
            // resolve() recreates it lazily on next use.
            runCatching { dnsExecutor.shutdownNow() }
        }
        hostStats.clear()
        dnsCache.clear()
        pinnedDefaultUntil.set(0L)
        cellularFailStreak.set(0)
    }
}
