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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

// Shared egress: synchronized network pick + DNS + dead-cellular pinning.
object EgressManager {
    private const val TAG = "EgressManager"
    // Serve from cache for 3 minutes, then keep answering from the last good
    // result while a background refresh runs (stale-while-revalidate). The old
    // 60s hard TTL put a blocking DNS round-trip in front of the first request
    // to any host idle for a minute - the biggest avoidable latency spike in
    // normal browsing through the proxy.
    private const val DNS_TTL_MS = 3 * 60_000L
    private const val DNS_STALE_GRACE_MS = 10 * 60_000L
    private const val DNS_RETRY_MS = 10_000L
    private const val DNS_TIMEOUT_MS = 5_000L
    // pickNet() runs on every new connection; within this window the last
    // validated network is returned without any binder round-trip.
    private const val FAST_NET_MS = 1_000L
    private const val PIN_MS = 5 * 60_000L
    private const val HOST_FAIL_THRESHOLD = 2
    private const val PROBE_INTERVAL_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 2000

    private val lock = Any()
    @Volatile private var cachedNet: Network? = null
    @Volatile private var cachedNetTime = 0L
    @Volatile private var fastNet: Network? = null
    @Volatile private var fastNetAt = 0L

    private var cmRef: ConnectivityManager? = null
    private var proberJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Recreatable so shutdown() is honest: the old code kept one pool for the
    // process lifetime (bounded one-time cost, but shutdown() claimed to reset
    // everything while the pool + its threads survived). Now shutdown drains
    // it and the next resolve() lazily builds a fresh one under lock.
    private var dnsExecutor = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "ejao-dns").apply { isDaemon = true }
    }

    private class DnsEntry(
        @Volatile var addrs: List<InetAddress>,
        @Volatile var expiresAt: Long,
        @Volatile var refreshing: Boolean = false
    )

    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()
    // Bumped by clearDns(): an in-flight background refresh must not write
    // addresses resolved via the previous network back into the fresh cache.
    private val dnsGeneration = AtomicInteger(0)

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
            if (NetworkUtils.isValidEgress(cm, active)) {
                rememberNet(active)
                return active
            }
            val c = fastNet
            if (c != null && NetworkUtils.isValidEgress(cm, c)) return c
            return active
        }
        if (host != null && shouldPreferDefault(host)) {
            val active = runCatching { cm.activeNetwork }.getOrNull()
            if (NetworkUtils.isValidEgress(cm, active)) {
                rememberNet(active)
                return active
            }
        }
        val fast = fastNet
        if (fast != null && now - fastNetAt < FAST_NET_MS) return fast

        // Binder calls stay outside the lock: holding a global monitor across
        // ConnectivityManager round-trips serialized every concurrent
        // connection setup in the whole process.
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (NetworkUtils.isValidEgress(cm, active)) {
            rememberNet(active)
            return active
        }
        val cached = cachedNet
        if (cached != null && NetworkUtils.isValidEgress(cm, cached)) {
            rememberNet(cached)
            return cached
        }
        val picked = NetworkUtils.pickCellular(cm, null) ?: cached
        rememberNet(picked)
        return picked
    }

    fun noteNetwork(net: Network) {
        rememberNet(net)
    }

    private fun rememberNet(n: Network?) {
        fastNet = n
        fastNetAt = System.currentTimeMillis()
        cachedNet = n
        cachedNetTime = fastNetAt
    }

    fun resolve(host: String, net: Network?): List<InetAddress> {
        // Numeric hosts never need DNS or the cache: parse and go.
        parseLiteral(host)?.let { return listOf(it) }
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null) {
            if (now < cached.expiresAt) return cached.addrs
            // Expired: hand the client the last known addresses now and
            // refresh in the background. Only the very first lookup for a
            // host ever blocks on DNS.
            refreshAsync(host, net, cached)
            return cached.addrs
        }
        val ordered = orderV4First(resolveBlocking(host, net))
        dnsCache[host] = DnsEntry(ordered, System.currentTimeMillis() + DNS_TTL_MS)
        return ordered
    }

    /**
     * Strict literal parser: IPv4 dotted-quad or IPv6 text. Returns null for
     * anything that needs a real lookup, so callers never put a hostname
     * through InetAddress.getByName on a hot thread.
     */
    fun parseLiteral(host: String): InetAddress? {
        if (host.isEmpty()) return null
        if (host.indexOf(':') >= 0) {
            // Bracketed or bare IPv6: InetAddress parses it without touching
            // DNS; a malformed value simply fails here.
            return runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull()
        }
        var parts = 0
        var value = 0
        var digits = 0
        val out = ByteArray(4)
        for (i in host.indices) {
            val c = host[i]
            if (c in '0'..'9') {
                value = value * 10 + (c - '0')
                if (value > 255) return null
                digits++
                if (digits > 3) return null
            } else if (c == '.') {
                if (digits == 0 || parts >= 3) return null
                out[parts++] = value.toByte()
                value = 0
                digits = 0
            } else {
                return null
            }
        }
        if (parts != 3 || digits == 0) return null
        out[3] = value.toByte()
        return runCatching { InetAddress.getByAddress(out) }.getOrNull()
    }

    private fun dnsExecutorRef(): ExecutorService = synchronized(lock) {
        if (dnsExecutor.isShutdown || dnsExecutor.isTerminated) {
            dnsExecutor = Executors.newFixedThreadPool(16) { r ->
                Thread(r, "ejao-dns").apply { isDaemon = true }
            }
        }
        dnsExecutor
    }

    private fun resolveBlocking(host: String, net: Network?): List<InetAddress> {
        val future = try {
            dnsExecutorRef().submit<List<InetAddress>> {
                getAllByName(host, net)
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
        return addrs
    }

    private fun refreshAsync(host: String, net: Network?, entry: DnsEntry) {
        synchronized(entry) {
            if (entry.refreshing) return
            entry.refreshing = true
        }
        val gen = dnsGeneration.get()
        try {
            dnsExecutorRef().execute {
                try {
                    val addrs = orderV4First(getAllByName(host, net))
                    if (addrs.isNotEmpty() && gen == dnsGeneration.get()) {
                        dnsCache[host] = DnsEntry(addrs, System.currentTimeMillis() + DNS_TTL_MS)
                    }
                } catch (_: Exception) {
                    if (gen == dnsGeneration.get()) {
                        // Keep serving the stale answer; retry later instead
                        // of stampeding a dead resolver on every connection.
                        entry.expiresAt = System.currentTimeMillis() + DNS_RETRY_MS
                    }
                } finally {
                    entry.refreshing = false
                }
            }
        } catch (_: Exception) {
            entry.refreshing = false
        }
    }

    private fun getAllByName(host: String, net: Network?): List<InetAddress> =
        (if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host))
            ?.toList().orEmpty()

    private fun orderV4First(addrs: List<InetAddress>): List<InetAddress> {
        if (addrs.isEmpty()) return addrs
        val v4 = addrs.filter { it.address.size == 4 }
        if (v4.size == addrs.size) return addrs
        return v4 + addrs.filter { it.address.size != 4 }
    }

    private fun purgeDns(now: Long) {
        dnsCache.entries.removeIf { now > it.value.expiresAt + DNS_STALE_GRACE_MS }
    }

    fun clearDns() {
        dnsGeneration.incrementAndGet()
        dnsCache.clear()
    }

    fun clearDnsHost(host: String) {
        runCatching { dnsCache.remove(host) }
    }

    fun invalidateCache() {
        cachedNet = null
        cachedNetTime = 0L
        fastNet = null
        fastNetAt = 0L
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
                // old IP can be dead, and replaying it until the TTL lapses
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
        purgeDns(System.currentTimeMillis())
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
            fastNet = null
            fastNetAt = 0L
            initialized = false
            cmRef = null
            // Drain the DNS pool so restart cycles don't accumulate threads.
            // resolve() recreates it lazily on next use.
            runCatching { dnsExecutor.shutdownNow() }
        }
        hostStats.clear()
        dnsGeneration.incrementAndGet()
        dnsCache.clear()
        pinnedDefaultUntil.set(0L)
        cellularFailStreak.set(0)
    }
}
