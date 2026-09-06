package com.sacram.proxy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emergency backup dashboard.
 *
 * Problem it solves: the main proxy (SOCKS5/HTTP) can die while the WiFi
 * Direct group is still up. The main PanelServer is stopped together with
 * the proxy on every restartProxy(), and if the proxy bind fails the panel
 * may never come back - leaving the PC connected to WiFi with no way to
 * restart the phone side.
 *
 * This server is deliberately tiny and independent:
 * - own ServerSocket + own thread pool + own CoroutineScope, never shares
 *   the proxy/panel executors, so a saturated or dead proxy cannot starve it.
 * - started right after the WiFi Direct group forms, BEFORE the main
 *   proxies, so it is already reachable even if the main bind fails.
 * - NOT stopped by restartProxy() - only stopped on service onDestroy().
 *   It survives proxy crashes and restarts, keeping a restart button alive.
 * - never touches the cellular egress network, only localhost probes +
 *   AppState/Config reads wrapped in try/catch, so it cannot crash because
 *   of a bad config or dead radio.
 *
 * Reach it at http://<goIp>:<backupPort>/ from any WiFi Direct client.
 * Default port is panelPort + 1 (8284); if occupied it walks up a few ports.
 */
class BackupPanelServer(
    private val requestedPort: Int,
    private val context: Context,
    private val onRestartRequest: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private val workerExecutor = Executors.newFixedThreadPool(8)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null
    @Volatile var actualPort: Int = requestedPort
        private set

    fun isRunning(): Boolean = running.get() && serverSocket?.isBound == true

    /** Binds, falling back to requestedPort+1..+5 if occupied. Returns bound port or -1. */
    fun start(): Int {
        running.set(true)
        var bound = -1
        var ss: ServerSocket? = null
        for (p in requestedPort..requestedPort + 5) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", p), 32)
                ss = s
                bound = p
                break
            } catch (_: Exception) {
                runCatching { ss?.close() }
                ss = null
            }
        }
        if (ss == null || bound < 0) {
            running.set(false)
            onLog("Backup panel failed to bind (tried $requestedPort..${requestedPort + 5})")
            return -1
        }
        serverSocket = ss
        actualPort = bound
        tcpJob = scope.launch { runServer(ss) }
        onLog("Backup panel listening on port $bound")
        return bound
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        tcpJob?.cancel()
        scope.cancel()
        runCatching { workerExecutor.shutdownNow() }
    }

    private suspend fun runServer(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            try {
                client.tcpNoDelay = true
                runCatching { client.setReceiveBufferSize(32 * 1024) }
                runCatching { client.setSendBufferSize(32 * 1024) }
            } catch (_: Exception) { }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream(), 32 * 1024)
        try {
            val requestLine = readLine(input) ?: return
            if (requestLine.isEmpty()) return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1].substringBefore("?")
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }
            // Drain POST body (we don't need its contents - restart is unconditional).
            if (method == "POST") {
                val cl = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                if (cl in 1..1_000_000) {
                    var left = cl
                    val trash = ByteArray(4096)
                    while (left > 0) {
                        val m = input.read(trash, 0, minOf(trash.size, left))
                        if (m <= 0) break
                        left -= m
                    }
                }
                if (target == "/restart") {
                    // Reply BEFORE restarting: the restart tears down sockets,
                    // so triggering first would hang the browser with no reply.
                    writeHtml(output, restartRequestedHtml())
                    try {
                        onRestartRequest()
                    } catch (_: Exception) { }
                    return
                }
                // Unknown POST -> show main page.
                writeHtml(output, buildHtml())
                return
            }
            if (target == "/api/health") {
                writeHealthJson(output)
                return
            }
            writeHtml(output, buildHtml())
        } catch (_: Exception) {
        } finally {
            runCatching { output.flush() }
            runCatching { client.close() }
        }
    }

    // ---- health probing (self-contained, never throws) ----

    private fun isPortOpen(port: Int): Boolean {
        if (port <= 0 || port > 65535) return false
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class Snapshot(
        val ssid: String,
        val goIp: String,
        val clients: Int,
        val socksPort: Int,
        val socks4Port: Int,
        val httpPort: Int,
        val panelPort: Int,
        val mode: String,
        val proxyAlive: Boolean,
        val running: Boolean,
        val status: String
    )

    private fun snapshot(): Snapshot {
        var socksPort = 1080
        var socks4Port = 1081
        var httpPort = 8282
        var panelPort = 8283
        var mode = "hybrid"
        try {
            val cfg = ConfigManager.load(context)
            socksPort = cfg.port
            socks4Port = cfg.socks4Port
            httpPort = cfg.httpPort
            panelPort = cfg.panelPort
            mode = cfg.effectiveMode()
        } catch (_: Exception) { }
        var ssid = ""
        var goIp = ""
        var clients = 0
        var runningFlag = false
        var status = ""
        try {
            val info = AppState.apInfo.value
            ssid = info.ssid
            goIp = info.goIp
            clients = info.clients
            runningFlag = AppState.running.value
            status = AppState.status.value
        } catch (_: Exception) { }
        val checkPorts = when (mode) {
            "http" -> listOf(httpPort, socks4Port)
            "socks5" -> listOf(socksPort, socks4Port)
            else -> listOf(socksPort, httpPort, socks4Port)
        }
        var alive = false
        for (p in checkPorts) {
            if (isPortOpen(p)) { alive = true; break }
        }
        // Panel itself counts as "reachable control plane" but proxyAlive
        // strictly means proxy traffic ports accept connections.
        return Snapshot(ssid, goIp, clients, socksPort, socks4Port, httpPort, panelPort, mode, alive, runningFlag, status)
    }

    private fun writeHealthJson(output: BufferedOutputStream) {
        val s = try { snapshot() } catch (_: Exception) {
            Snapshot("", "", 0, 1080, 1081, 8282, 8283, "hybrid", false, false, "unknown")
        }
        val uptime = try {
            if (AppState.serviceStartedAt > 0) (System.currentTimeMillis() - AppState.serviceStartedAt) / 1000 else 0
        } catch (_: Exception) { 0L }
        val json = buildString {
            append('{')
            append("\"backup\":true,")
            append("\"proxyAlive\":").append(s.proxyAlive).append(',')
            append("\"wifiDirectUp\":").append(s.goIp.isNotEmpty() && s.ssid.isNotEmpty()).append(',')
            append("\"running\":").append(s.running).append(',')
            append("\"mode\":\"").append(escapeJson(s.mode)).append("\",")
            append("\"ssid\":\"").append(escapeJson(s.ssid)).append("\",")
            append("\"goIp\":\"").append(escapeJson(s.goIp)).append("\",")
            append("\"clients\":").append(s.clients).append(',')
            append("\"socksPort\":").append(s.socksPort).append(',')
            append("\"httpPort\":").append(s.httpPort).append(',')
            append("\"panelPort\":").append(s.panelPort).append(',')
            append("\"backupPort\":").append(actualPort).append(',')
            append("\"uptime\":").append(uptime).append(',')
            append("\"status\":\"").append(escapeJson(s.status)).append("\",")
            append("\"serverNow\":").append(System.currentTimeMillis())
            append('}')
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    // ---- HTML ----

    private fun restartRequestedHtml(): String {
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Backup Panel</title>
        ${style()}
        </head><body><div class="wrap">
        <section class="card" style="text-align:center;padding:32px 16px">
            <div class="card-head" style="margin-bottom:8px">Backup panel &mdash; restart</div>
            <h1 style="font-size:18px;margin:0 0 10px">Restart requested</h1>
            <p class="note">Restarting the proxy now. WiFi Direct stays up; this backup page will still be here. The main proxy + panel come back in a few seconds.</p>
            <a href="/" class="btn" style="display:block;text-decoration:none;text-align:center;box-sizing:border-box">Back</a>
        </section>
        </div></body></html>
        """.trimIndent()
    }

    private fun buildHtml(): String {
        val s = try { snapshot() } catch (_: Exception) {
            Snapshot("", "", 0, 1080, 1081, 8282, 8283, "hybrid", false, false, "unknown")
        }
        val proxyBadge = if (s.proxyAlive)
            "<span class=\"pill pill-ok\">PROXY UP</span>"
        else
            "<span class=\"pill pill-bad\">PROXY DOWN</span>"
        val wifiBadge = if (s.goIp.isNotEmpty() && s.ssid.isNotEmpty())
            "<span class=\"pill pill-ok\">WIFI DIRECT UP</span>"
        else
            "<span class=\"pill pill-bad\">WIFI DIRECT DOWN</span>"
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Backup Panel</title>
        ${style()}
        </head><body>
        <div class="wrap">
        <header class="topbar">
            <div class="brand"><span class="dot" id="v-dot"></span><span class="brand-name">SACRAM</span><span class="brand-sub">backup panel</span></div>
            <div class="ver">:${actualPort}</div>
        </header>

        <section class="card">
            <div class="card-head">Emergency status</div>
            <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">$proxyBadge $wifiBadge</div>
            <div class="list">
                <div class="li"><span class="li-k">Proxy</span><span class="li-v mono" id="v-proxy">${if (s.proxyAlive) "UP" else "DOWN"}</span></div>
                <div class="li"><span class="li-k">SSID</span><span class="li-v mono" id="v-ssid">${escapeHtml(s.ssid.ifEmpty { "--" })}</span></div>
                <div class="li"><span class="li-k">Group IP</span><span class="li-v mono" id="v-goip">${escapeHtml(s.goIp.ifEmpty { "--" })}</span></div>
                <div class="li"><span class="li-k">Clients</span><span class="li-v mono" id="v-clients">${s.clients}</span></div>
                <div class="li"><span class="li-k">Mode</span><span class="li-v mono">${escapeHtml(s.mode)}</span></div>
                <div class="li"><span class="li-k">SOCKS5</span><span class="li-v mono">${escapeHtml(s.goIp.ifEmpty { "192.168.49.1" })}:${s.socksPort}</span></div>
                <div class="li"><span class="li-k">HTTP</span><span class="li-v mono">${escapeHtml(s.goIp.ifEmpty { "192.168.49.1" })}:${s.httpPort}</span></div>
                <div class="li"><span class="li-k">Main panel</span><span class="li-v mono">http://${escapeHtml(s.goIp.ifEmpty { "192.168.49.1" })}:${s.panelPort}/</span></div>
            </div>
            <p class="note">You are seeing this page because WiFi Direct is still up. If the main proxy or main panel is dead, use the button below to restart the proxy without touching WiFi.</p>
        </section>

        <form method="post" action="/restart">
            <section class="card">
                <div class="card-head">Restart</div>
                <button type="submit" class="btn btn-accent">Restart proxy now</button>
                <p class="note">Restarts SOCKS5/HTTP + main panel. This backup panel stays up the whole time.</p>
            </section>
        </form>

        <section class="card">
            <div class="card-head">Activity</div>
            <div class="log" id="v-log"><div class="log-line">${escapeHtml(s.status.ifEmpty { "starting..." })}</div></div>
        </section>

        <footer class="foot">Sacram &mdash; backup panel, stays up when the proxy goes down</footer>
        </div>
        <script>
        async function sacramRefresh(){
          try{
            var r=await fetch('/api/health',{cache:'no-store'});
            var d=await r.json();
            var set=function(id,v){var e=document.getElementById(id);if(e)e.textContent=v;};
            set('v-proxy',d.proxyAlive?'UP':'DOWN');
            set('v-ssid',d.ssid||'--');set('v-goip',d.goIp||'--');set('v-clients',d.clients);
            var dot=document.getElementById('v-dot');
            if(dot) dot.className='dot'+(d.proxyAlive?' on':' off');
            var log=document.getElementById('v-log');
            if(log&&d.status){log.innerHTML='<div class="log-line">'+d.status.replace(/</g,'&lt;')+'</div>';}
          }catch(e){}
        }
        sacramRefresh();
        setInterval(sacramRefresh,5000);
        </script>
        </body></html>
        """.trimIndent()
    }

    private fun writeHtml(output: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = ins.read()
            if (b == -1) {
                if (sb.isEmpty()) return null
                break
            }
            if (b == '\n'.code) {
                if (prev == '\r'.code) sb.setLength(sb.length - 1)
                break
            }
            sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun style(): String = """
        <style>
        :root{--bg:#0a0c10;--bg-raised:#12151b;--line:#232830;--text:#e8ecf1;--text-dim:#8891a0;--text-faint:#5b6373;--accent:#3ddc84;--blue:#4f8ef7;--red:#f0654f;--mono:ui-monospace,Consolas,monospace;--sans:system-ui,'Segoe UI',Roboto,sans-serif}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans)}
        .wrap{max-width:640px;margin:0 auto;padding:20px 16px 40px}
        .mono{font-family:var(--mono)}
        .topbar{display:flex;align-items:baseline;justify-content:space-between;padding:4px 2px 18px;border-bottom:1px solid var(--line);margin-bottom:18px}
        .brand{display:flex;align-items:baseline;gap:8px}
        .brand-name{font-weight:700;font-size:17px;letter-spacing:0.06em}
        .brand-sub{color:var(--text-faint);font-size:12px}
        .ver{color:var(--text-faint);font-size:12px;font-family:var(--mono)}
        .dot{width:8px;height:8px;border-radius:50%;background:var(--text-faint);display:inline-block;margin-right:2px}
        .dot.on{background:var(--accent);box-shadow:0 0 0 3px rgba(61,220,132,0.15)}
        .dot.off{background:var(--red);box-shadow:0 0 0 3px rgba(240,101,79,0.15)}
        .card{background:var(--bg-raised);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:14px}
        .card-head{font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:var(--text-faint);margin-bottom:12px}
        .pill{display:inline-block;font-size:12px;font-weight:700;padding:2px 9px;border-radius:999px;border:1px solid}
        .pill-ok{background:rgba(61,220,132,0.12);color:var(--accent);border-color:rgba(61,220,132,0.25)}
        .pill-bad{background:rgba(240,101,79,0.1);color:var(--red);border-color:rgba(240,101,79,0.22)}
        .list{display:flex;flex-direction:column}
        .li{display:flex;justify-content:space-between;gap:10px;padding:9px 0;border-bottom:1px solid var(--line)}
        .li:last-child{border-bottom:0}
        .li-k{color:var(--text-dim);font-size:13px}
        .li-v{font-size:13px;font-weight:600;text-align:right;word-break:break-all}
        .btn{width:100%;padding:12px;border:0;border-radius:8px;background:var(--accent);color:#04140b;font-size:14px;font-weight:700;margin-top:14px;cursor:pointer}
        .btn-accent{background:var(--blue);color:#0a1220}
        .note{font-size:12px;line-height:1.5;color:var(--text-faint);margin:10px 0 0}
        .log{background:#05070a;border:1px solid var(--line);border-radius:8px;padding:10px 12px;max-height:160px;overflow-y:auto;font-family:var(--mono);font-size:12px}
        .log-line{white-space:pre-wrap;word-break:break-all}
        .foot{text-align:center;color:var(--text-faint);font-size:11px;margin-top:22px}
        </style>
    """.trimIndent()
}
