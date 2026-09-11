package com.sacram.proxy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated control-panel HTTP server, fully independent of the SOCKS5 / HTTP
 * proxy traffic.
 *
 * Why a separate server + own thread pool: the panel used to be served inline
 * by [HttpProxyServer] on the SAME port and the SAME 256-thread worker pool as
 * the heavy CONNECT tunnels. When a tab hammered the proxy with dozens of
 * concurrent fetches, every worker could be busy pumping upstream sockets, so a
 * panel request queued behind them and the panel felt dead until the page
 * finished. This server runs on its own port with its own small pool, so the
 * panel always responds instantly regardless of how saturated the proxy is.
 *
 * It only ever serves local content (status JSON, the HTML page, restart +
 * settings forms) and never opens an upstream/egress socket, so it has zero
 * dependence on the cellular network being alive.
 */
class PanelServer(
    private val port: Int,
    private val context: Context,
    private val enabled: Boolean = true,
    private val onLog: (String) -> Unit = {},
    private val onRestartRequest: () -> Unit = {}
) {
    // Small dedicated pool. The panel is low-traffic (a few requests + an SSE
    // stream held open by one browser tab); it must never compete with the
    // proxy's worker pool, which is exactly why it gets its own executor here.
    // 32 (not 16): an open /api/stream connection parks one thread for as long
    // as the panel tab stays open, so the pool needs headroom for other panel
    // requests (settings saves, restarts) to still get served concurrently.
    private val workerExecutor = Executors.newFixedThreadPool(32)
    private val scope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var tcpJob: Job? = null

    fun start() {
        if (!enabled) {
            onLog("Control panel disabled")
            return
        }
        running.set(true)
        tcpJob = scope.launch { runServer() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        tcpJob?.cancel()
        scope.cancel()
        runCatching { workerExecutor.shutdownNow() }
    }

    private fun tuneSocket(sock: Socket) {
        runCatching { sock.setReceiveBufferSize(64 * 1024) }
        runCatching { sock.setSendBufferSize(64 * 1024) }
    }

    private suspend fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            runCatching { ss.setReceiveBufferSize(64 * 1024) }
            ss.bind(InetSocketAddress("0.0.0.0", port), 64)
            serverSocket = ss
            onLog("Control panel listening on port $port")
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                tuneSocket(client)
                scope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("Control panel server error: $e")
        }
    }

    private suspend fun handleClient(client: Socket) {
        // Single buffered stream for both headers and body. IMPORTANT: the POST
        // body is read from this same stream, so we must NOT mix a separate
        // BufferedReader (which would swallow bytes ahead of the body read).
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        try {
            val requestLine = readLine(input) ?: return
            if (requestLine.isEmpty()) return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            if (method == "POST") {
                if (target == "/restart") {
                    // Respond BEFORE restarting: restartProxy() stops this very
                    // PanelServer (closing the client socket), so if we triggered it
                    // first the browser would hang on "loading" forever with no reply.
                    writePanelPage(output, restartRequestedHtml())
                    onRestartRequest()
                    return
                }
                val cl = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
                val body = if (cl != null && cl in 1..1_000_000) readExact(input, cl) else ""
                applyPanelForm(body)
                val cfg = ConfigManager.load(context)
                writePanelPage(output, if (cfg.requireApprovalRestart) pendingPageHtml() else savedPageHtml())
                return
            }
            if (target == "/api/status") {
                writeStatusJson(output)
                return
            }
            if (target == "/api/stream") {
                streamStatusSse(client, output)
                return
            }
            writePanelPage(output, buildPanelHtml())
        } catch (_: Exception) {
        } finally {
            runCatching { output.flush() }
            runCatching { client.close() }
        }
    }

    /**
     * Server-Sent Events endpoint: pushes a new `data:` line the instant
     * [AppState.status] changes, instead of the browser polling /api/status on
     * a fixed interval. Holds the connection open until the client disconnects
     * (write fails) or the panel is stopped. Runs on this handler's own worker
     * coroutine, so one open SSE tab never blocks other panel requests.
     */
    private suspend fun streamStatusSse(client: Socket, output: BufferedOutputStream) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
        // Park cap: close the stream after 5 min or 300 messages so one open
        // tab never parks a pool thread forever. Browser auto-reconnects.
        runCatching { client.soTimeout = 310_000 }
        try {
            var n = 0
            kotlinx.coroutines.withTimeoutOrNull(5 * 60 * 1000L) {
                AppState.status.collect { value ->
                    if (!running.get() || client.isClosed) throw IOException("stream closed")
                    val line = "data: ${escapeSse(value)}\n\n"
                    output.write(line.toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (++n > 300) throw IOException("stream ttl")
                }
            }
        } catch (_: Exception) {
            // Client disconnected or panel stopping - normal end of stream.
        }
    }

    private fun escapeSse(s: String): String = s.replace("\n", " ").replace("\r", "")

    private fun writeStatusJson(output: BufferedOutputStream) {
        val cfg = ConfigManager.load(context)
        val info = AppState.apInfo.value
        val uptime = if (AppState.serviceStartedAt > 0)
            (System.currentTimeMillis() - AppState.serviceStartedAt) / 1000 else 0
        val uptimeStr = "${uptime / 3600}h ${(uptime % 3600) / 60}m ${uptime % 60}s"
        val mode = when {
            AppState.httpMode.value && info.clients >= 0 && cfg.effectiveMode() == "http" -> "HTTP"
            cfg.isHybrid() -> "Hybrid"
            cfg.effectiveMode() == "http" -> "HTTP"
            else -> "SOCKS5"
        }
        val json = buildString {
            append('{')
            append("\"status\":\"").append(escapeJson(AppState.status.value)).append("\",")
            append("\"running\":").append(AppState.running.value).append(',')
            append("\"uptime\":\"").append(uptimeStr).append("\",")
            append("\"mode\":\"").append(mode).append("\",")
            append("\"ssid\":\"").append(escapeJson(info.ssid)).append("\",")
            append("\"passphrase\":\"").append(escapeJson(info.passphrase)).append("\",")
            append("\"goIp\":\"").append(escapeJson(info.goIp)).append("\",")
            append("\"panelPort\":").append(port).append(',')
            append("\"clients\":").append(info.clients).append(',')
            append("\"tcpTunnels\":").append(AppState.tcpTunnels.value).append(',')
            append("\"downBps\":").append(AppState.netDownBps).append(',')
            append("\"upBps\":").append(AppState.netUpBps).append(',')
            append("\"lanClients\":[")
            AppState.lanClients.value.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append("{\"name\":\"").append(escapeJson(c.name)).append("\",")
                append("\"ip\":\"").append(escapeJson(c.ip)).append("\",")
                append("\"mb\":").append((Math.round(c.mb * 10) / 10.0)).append('}')
            }
            append("],")
            append("\"requireApprovalRestart\":").append(cfg.requireApprovalRestart).append(',')
            append("\"isReforming\":").append(AppState.isReforming.value).append(',')
            append("\"version\":\"").append(BuildConfig.VERSION_NAME).append("\",")
            append("\"startedAt\":").append(AppState.serviceStartedAt).append(',')
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

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun restartRequestedHtml(): String {
        val cfg = ConfigManager.load(context)
        val msg = if (cfg.requireApprovalRestart)
            "Restart is waiting for the phone owner to approve it <b>inside the Sacram app</b> (10 second window)."
        else
            "Restarting the proxy + hotspot now. The panel will come back online in a few seconds."
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        ${panelStyle()}
        </head><body><div class="wrap">
        <section class="card" style="text-align:center;padding:32px 16px">
            <div class="card-head" style="margin-bottom:8px">Restart</div>
            <h1 style="font-size:18px;margin:0 0 10px">Restart requested</h1>
            <p class="note" style="font-size:13px;color:var(--text-dim)">$msg</p>
            <a href="/" class="btn" style="display:block;text-decoration:none;text-align:center;box-sizing:border-box">Back to panel</a>
        </section>
        </div></body></html>
        """.trimIndent()
    }

    private fun writePanelPage(output: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun pendingPageHtml(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        ${panelStyle()}
        </head><body><div class="wrap">
        <section class="card" style="text-align:center;padding:32px 16px">
            <div class="card-head" style="margin-bottom:8px">Settings</div>
            <h1 style="font-size:18px;margin:0 0 10px">Change requested</h1>
            <p class="note" style="font-size:13px;color:var(--text-dim)">The requested settings change is waiting for the phone owner to approve it inside the Sacram app (10 second window).</p>
            <p class="note" style="font-size:13px;color:var(--text-dim)">If the owner ignores or denies it, nothing changes.</p>
            <a href="/" class="btn" style="display:block;text-decoration:none;text-align:center;box-sizing:border-box">Back to panel</a>
        </section>
        </div></body></html>
        """.trimIndent()

    private fun savedPageHtml(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        ${panelStyle()}
        </head><body><div class="wrap">
        <section class="card" style="text-align:center;padding:32px 16px">
            <div class="card-head" style="margin-bottom:8px">Settings</div>
            <h1 style="font-size:18px;margin:0 0 10px">Settings saved</h1>
            <p class="note" style="font-size:13px;color:var(--text-dim)">The changes were applied immediately (owner approval not required).</p>
            <a href="/" class="btn" style="display:block;text-decoration:none;text-align:center;box-sizing:border-box">Back to panel</a>
        </section>
        </div></body></html>
        """.trimIndent()

    private fun readExact(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var r = 0
        while (r < n) {
            val m = input.read(buf, r, n - r)
            if (m <= 0) break
            r += m
        }
        return String(buf, 0, r, Charsets.UTF_8)
    }

    /**
     * Reads a single CRLF/LF-terminated header line from [ins]. Reads one byte at
     * a time so it never overtakes the [BufferedInputStream] used for the body.
     */
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

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun applyPanelForm(body: String) {
        val map = mutableMapOf<String, String>()
        body.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) urlDecode(pair.substring(0, idx)) else urlDecode(pair)
            val v = if (idx >= 0) urlDecode(pair.substring(idx + 1)) else ""
            map[k] = v
        }
        val cfg = ConfigManager.load(context)
        if (cfg.requireApprovalRestart) {
            PanelApproval.submit(map)
            onLog("Panel change requested - awaiting in-app approval")
        } else {
            // Owner disabled approval: apply settings immediately, no prompt.
            PanelApproval.applyFields(context, map)
            onLog("Panel settings applied (no approval required)")
        }
    }

    private fun buildPanelHtml(): String {
        val cfg = ConfigManager.load(context)
        val info = AppState.apInfo.value
        val uptime = if (AppState.serviceStartedAt > 0)
            (System.currentTimeMillis() - AppState.serviceStartedAt) / 1000 else 0
        val uptimeStr = "${uptime / 3600}h ${(uptime % 3600) / 60}m ${uptime % 60}s"
        val band24 = if (cfg.band == "2.4") "checked" else ""
        val band5 = if (cfg.band == "5") "checked" else ""
        val bandAuto = if (cfg.band == "auto") "checked" else ""
        val backupPort = if (info.backupPanelPort > 0) info.backupPanelPort else cfg.backupPanelPort
        val restartNote = if (cfg.requireApprovalRestart)
            "Restarts the proxy + hotspot. Requires in-app owner approval (10s window)."
        else
            "Restarts the proxy + hotspot immediately (no approval). Enable \"Require approval for panel restart\" in the app's Keep-Alive tab to gate it."
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sacram Panel</title>
        ${panelStyle()}
        </head><body>
        <div class="wrap">
        <header class="topbar">
            <span class="mark">S</span>
            <div style="line-height:1.15"><div class="brand-name">SACRAM</div><div class="brand-sub">control panel</div></div>
            <span class="ver">v${BuildConfig.VERSION_NAME}</span>
            <span style="margin-left:auto;display:flex;gap:8px">
              <button class="icon-btn" id="theme-btn" type="button" onclick="sacramTheme()">Dark</button>
            </span>
        </header>

        <section class="card slim">
            <div class="grid5">
                <div class="stat"><div class="stat-k"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>Status</div><div class="stat-v stat-sm" id="v-running">${if (AppState.running.value) "Running" else "Stopped"}</div></div>
                <div class="stat"><div class="stat-k"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>Uptime</div><div class="stat-v stat-sm mono" id="v-uptime">$uptimeStr</div></div>
                <div class="stat"><div class="stat-k"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>Clients</div><div class="stat-v" id="v-clients">${info.clients}</div></div>
                <div class="stat"><div class="stat-k"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3 4 7l4 4"/><path d="M4 7h16"/><path d="m16 21 4-4-4-4"/><path d="M20 17H4"/></svg>TCP tunnels</div><div class="stat-v" id="v-tunnels">${AppState.tcpTunnels.value}</div></div>
                <div class="stat"><div class="stat-k"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/></svg>Throughput</div><div class="stat-v stat-sm mono" id="v-rate">—</div><canvas class="spark" id="v-spark" width="220" height="36"></canvas></div>
            </div>
        </section>

        <div class="grid2">
        <form method="post" action="/">
        <section class="card">
            <div class="card-head"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><path d="M12 20h.01"/></svg>Wi-Fi Direct</div>
            <div class="kv"><div><div class="kv-k">SSID</div><div class="kv-v" id="v-ssid">${escapeHtml(info.ssid)}</div></div><button class="mini-btn" type="button" data-copy="${escapeHtml(info.ssid)}" onclick="sacramCopy(this,this.getAttribute('data-copy'))">Copy</button></div>
            <div class="kv"><div><div class="kv-k">Password</div><div class="kv-v" id="v-pass" data-real="${escapeHtml(info.passphrase)}">&bull;&bull;&bull;&bull;&bull;&bull;&bull;&bull;</div></div><span style="display:flex;gap:6px"><button class="mini-btn" type="button" onclick="sacramPw()">Show</button><button class="mini-btn" type="button" onclick="sacramCopyPass(this)">Copy</button></span></div>
            <span class="field-label">Band</span>
            <div class="seg">
                <label><input type="radio" name="band" value="2.4" $band24><span>2.4 GHz</span></label>
                <label><input type="radio" name="band" value="5" $band5><span>5 GHz</span></label>
                <label><input type="radio" name="band" value="auto" $bandAuto><span>Auto</span></label>
            </div>
            <input type="hidden" name="panel_enabled" value="on">
            <button type="submit" class="btn">Save</button>
        </section>
        </form>

        <section class="card">
            <div class="card-head"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="2" y="3" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="7" rx="2"/><path d="M6 6.5h.01M6 17.5h.01"/></svg>Endpoints</div>
            <div class="ep"><span class="ep-tag">SOCKS5</span><span class="ep-val" id="v-socks">${escapeHtml(info.goIp)}:${cfg.port}</span></div>
            <div class="ep"><span class="ep-tag">HTTP</span><span class="ep-val" id="v-http">${escapeHtml(info.goIp)}:${cfg.httpPort}</span></div>
            <div class="ep"><span class="ep-tag">Panel</span><span class="ep-val" id="v-panelport">${escapeHtml(info.goIp)}:$port</span></div>
            <div class="ep"><span class="ep-tag">Backup</span><span class="ep-val">http://${escapeHtml(info.goIp)}:$backupPort/</span></div>
        </section>
        </div>

        <section class="card">
            <div class="card-head"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>Connected clients</div>
            <div id="v-clients-body">${clientRows()}</div>
        </section>

        <section class="card">
            <div class="card-head"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 17l6-6-6-6"/><path d="M12 19h8"/></svg>Activity <span class="right"><span class="live-text" id="v-live">connecting</span></span></div>
            <div class="log log-big" id="v-log"><div class="log-line" id="v-log-current">${escapeHtml(AppState.status.value)}</div></div>
        </section>

        <form method="post" action="/restart">
            <section class="card danger-card slim">
                <div class="danger-row">
                    <div><div class="card-head" style="margin:0"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg>Danger zone</div>
                    <div class="note" style="margin:4px 0 0">$restartNote</div></div>
                    <button type="submit" class="btn btn-danger btn-inline">Restart</button>
                </div>
            </section>
        </form>

        <footer class="foot">Sacram &mdash; local control panel, no external access</footer>
        </div>
        <script>
        try{var t=localStorage.getItem('sacram-theme');if(!t&&window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches)t='dark';if(t==='dark'){document.documentElement.setAttribute('data-theme','dark');}}catch(e){}
        function sacramTheme(){
          var el=document.documentElement;
          var dark=el.getAttribute('data-theme')!=='dark';
          if(dark){el.setAttribute('data-theme','dark');}else{el.removeAttribute('data-theme');}
          try{localStorage.setItem('sacram-theme',dark?'dark':'light');}catch(e){}
          var b=document.getElementById('theme-btn');if(b)b.textContent=dark?'Light':'Dark';
        }
        (function(){try{if(document.documentElement.getAttribute('data-theme')==='dark'){var b=document.getElementById('theme-btn');if(b)b.textContent='Light';}}catch(e){}})();
        function sacramCopy(btn,text){
          function done(){var o=btn.textContent;btn.textContent='Done';setTimeout(function(){btn.textContent=o;},900);}
          if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(text).then(done,done);}else{done();}
        }
        function sacramCopyPass(btn){
          var e=document.getElementById('v-pass');
          sacramCopy(btn,e?(e.getAttribute('data-real')||''):'');
        }
        var pwShown=false;
        function sacramPw(){
          var e=document.getElementById('v-pass');if(!e)return;
          pwShown=!pwShown;
          if(pwShown){e.textContent=e.getAttribute('data-real')||e.textContent;}
          else{e.textContent='\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';}
        }
        var sacramOffset=0, sacramStarted=0;
        var SACRAM_LOG_MAX=200;
        function sacramFmtUptime(sec){
          if(!isFinite(sec)||sec<0)sec=0;
          var h=Math.floor(sec/3600), m=Math.floor((sec%3600)/60), s=Math.floor(sec%60);
          return h+'h '+m+'m '+s+'s';
        }
        function sacramTick(){
          if(sacramStarted>0){
            var e=document.getElementById('v-uptime');
            if(e) e.textContent=sacramFmtUptime((Date.now()+sacramOffset-sacramStarted)/1000);
          }
        }
        function sacramFmtRate(bps){
          if(!isFinite(bps)||bps<1000) return '0 Kb/s';
          if(bps<1000000) return (bps/1000).toFixed(0)+' Kb/s';
          return (bps/1000000).toFixed(1)+' Mb/s';
        }
        var sacramHist=[];
        var SACRAM_COLS=['var(--text)','#c2410c','var(--text-faint)'];
        function sacramClients(arr){
          var body=document.getElementById('v-clients-body');
          if(!body) return;
          while(body.firstChild) body.removeChild(body.firstChild);
          if(!arr||!arr.length){
            var e=document.createElement('div');e.className='cli-empty';
            e.textContent='No clients connected.';body.appendChild(e);return;
          }
          var total=0,i;
          for(i=0;i<arr.length;i++) total+=arr[i].mb||0;
          if(total<=0) total=0.001;
          for(i=0;i<arr.length;i++){
            var c=arr[i];
            var row=document.createElement('div');row.className='cli';
            var av=document.createElement('span');av.className='avatar';
            var nm=String(c.name||'?');
            var ch='?';
            for(var k=0;k<nm.length;k++){var cc=nm[k];if(/[A-Za-z0-9]/.test(cc)){ch=cc.toUpperCase();break;}}
            av.textContent=ch;
            if(i%2===0){av.style.background='var(--text)';av.style.color='var(--bg)';}
            else{av.style.background='#c2410c';av.style.color='#fff';}
            var main=document.createElement('div');main.className='cli-main';
            var t=document.createElement('div');t.className='cli-name';t.textContent=nm;
            var s=document.createElement('div');s.className='cli-sub mono';
            s.textContent=String(c.ip||'?')+' · '+(Math.round((c.mb||0)*10)/10)+' MB';
            main.appendChild(t);main.appendChild(s);
            var act=document.createElement('span');act.className='cli-active';act.textContent='active';
            row.appendChild(av);row.appendChild(main);row.appendChild(act);
            body.appendChild(row);
          }
          var head=document.createElement('div');head.className='split-head';head.textContent='Data split';
          var bar=document.createElement('div');bar.className='split';
          var leg=document.createElement('div');leg.className='split-legend';
          for(i=0;i<arr.length;i++){
            var pct=Math.max(0,Math.min(100,(arr[i].mb||0)/total*100));
            var seg=document.createElement('div');
            seg.style.width=(Math.round(pct*10)/10)+'%';
            seg.style.background=SACRAM_COLS[i%SACRAM_COLS.length];
            bar.appendChild(seg);
            var li=document.createElement('span');
            li.textContent=String(arr[i].name||'?')+' '+Math.round(pct)+'%';
            leg.appendChild(li);
          }
          body.appendChild(head);body.appendChild(bar);body.appendChild(leg);
        }
        function sacramRate(total){          var e=document.getElementById('v-rate');
          if(e) e.textContent=sacramFmtRate(total);
          sacramHist.push(total||0);
          while(sacramHist.length>24) sacramHist.shift();
          var c=document.getElementById('v-spark');
          if(!c||!c.getContext) return;
          var x=c.getContext('2d');
          var W=c.width,H=c.height;
          x.clearRect(0,0,W,H);
          var max=1;
          for(var i=0;i<sacramHist.length;i++) if(sacramHist[i]>max) max=sacramHist[i];
          var col='#78716c';
          try{col=getComputedStyle(document.documentElement).getPropertyValue('--text-dim')||col;}catch(ignored){}
          x.strokeStyle=col;x.lineWidth=2;x.beginPath();
          for(var j=0;j<sacramHist.length;j++){
            var px=sacramHist.length>1?j/(sacramHist.length-1)*W:W;
            var py=H-3-(sacramHist[j]/max)*(H-6);
            if(j===0)x.moveTo(px,py);else x.lineTo(px,py);
          }
          x.stroke();
        }
        function sacramDot(running){return;}
        function sacramLive(state){
          var e=document.getElementById('v-live');
          if(!e) return;
          if(state==='on'){e.className='live-text';e.textContent='live';}
          else if(state==='off'){e.className='live-text off';e.textContent='offline';}
          else{e.className='live-text';e.textContent='connecting';}
        }
        function sacramNowLabel(){
          var d=new Date();
          var p=function(n){return n<10?'0'+n:''+n;};
          return p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds());
        }
        function sacramLogLine(text){
          var log=document.getElementById('v-log');
          if(!log) return;
          var cur=document.getElementById('v-log-current');
          if(cur){cur.removeAttribute('id');cur.className='log-line log-line-dim';}
          var line=document.createElement('div');
          line.id='v-log-current';
          line.className='log-line';
          line.textContent='['+sacramNowLabel()+'] '+text;
          log.appendChild(line);
          while(log.children.length>SACRAM_LOG_MAX) log.removeChild(log.firstChild);
          log.scrollTop=log.scrollHeight;
        }
        function sacramStartStream(){
          if(!window.EventSource){sacramLive('off');return;}
          var es=new EventSource('/api/stream');
          es.onopen=function(){sacramLive('on');};
          es.onmessage=function(ev){sacramLogLine(ev.data);};
          es.onerror=function(){
            sacramLive('off');
            // Browsers auto-retry EventSource, but our server closes the TCP
            // socket per-request rather than staying keep-alive-friendly across
            // reconnect storms, so force a clean reconnect after a short delay.
            es.close();
            setTimeout(sacramStartStream,2000);
          };
        }
        async function sacramRefresh(){
          try{
            var r=await fetch('/api/status',{cache:'no-store'});
            var d=await r.json();
            var set=function(id,v){var e=document.getElementById(id);if(e)e.textContent=v;};
            if(d.startedAt>0){sacramStarted=d.startedAt;sacramOffset=d.serverNow-Date.now();}
            set('v-running',d.running===true||d.running==='true'?'Running':'Stopped');set('v-uptime',d.uptime);
            set('v-ssid',d.ssid);
            var pe=document.getElementById('v-pass');
            if(pe){pe.setAttribute('data-real',d.passphrase);if(pwShown){pe.textContent=d.passphrase;}}
            set('v-goip',d.goIp);set('v-clients',d.clients);set('v-tunnels',d.tcpTunnels);
            sacramRate((d.downBps||0)+(d.upBps||0));
            sacramClients(d.lanClients||[]);
            sacramDot(d.running);
            sacramTick();
          }catch(e){}
        }
        sacramRefresh();
        sacramStartStream();
        setInterval(sacramRefresh,2000);
        setInterval(sacramTick,1000);
        </script>
        </body></html>
        """.trimIndent()
    }

    /**
     * Shared design tokens for the panel: warm paper light theme + dark theme
     * via [data-theme]. No external CSS (panel is offline). Kept as one
     * block so every page (main, restart, pending, saved) looks consistent.
     */
    private fun panelStyle(): String = """
        <style>
        :root{
          --bg:#f6f5f1; --card:#ffffff; --line:#e7e5e4;
          --text:#1c1917; --text-dim:#78716c; --text-faint:#a8a29e;
          --green:#15803d; --green-bg:#dcfce7; --green-line:#86efac;
          --red:#dc2626; --red-bg:#fef2f2; --red-line:#fecaca;
          --amber-bg:#fef3c7; --amber-tx:#b45309; --amber-line:#fde68a;
          --mono:'SF Mono',ui-monospace,'Roboto Mono',Consolas,monospace;
          --sans:-apple-system,system-ui,'Segoe UI',Roboto,Inter,sans-serif;
        }
        [data-theme="dark"]{
          --bg:#171412; --card:#201c1a; --line:#332d29;
          --text:#f5f2ee; --text-dim:#b8a99f; --text-faint:#78716c;
          --green:#4ade80; --green-bg:#14532d; --green-line:#166534;
          --red:#f87171; --red-bg:#3b1512; --red-line:#7f1d1d;
          --amber-bg:#3b2c0c; --amber-tx:#fbbf24; --amber-line:#71500e;
        }
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans);
          -webkit-font-smoothing:antialiased}
        .wrap{max-width:880px;margin:0 auto;padding:20px 16px 48px}
        .mono{font-family:var(--mono)}

        .topbar{display:flex;align-items:center;gap:12px;
          padding:4px 2px 16px;border-bottom:1px solid var(--line);margin-bottom:16px}
        .mark{width:34px;height:34px;border-radius:9px;background:var(--text);color:var(--bg);
          display:inline-grid;place-items:center;font-weight:800;font-size:16px;flex-shrink:0}
        [data-theme="dark"] .mark{background:#f5f2ee;color:#171412}
        .brand-name{font-weight:800;font-size:16px;letter-spacing:0.06em}
        .brand-sub{color:var(--text-faint);font-size:12px}
        .ver{color:var(--text-dim);font-size:12px;font-family:var(--mono);
          background:var(--card);border:1px solid var(--line);border-radius:999px;padding:4px 10px}
        .icon-btn{background:var(--card);border:1px solid var(--line);color:var(--text);
          border-radius:9px;padding:7px 10px;font-size:13px;font-weight:700;cursor:pointer;
          display:inline-flex;align-items:center;gap:6px}

        .card{background:var(--card);
          border:1px solid var(--line);border-radius:14px;
          padding:16px;margin-bottom:12px}
        .card-head{font-size:11px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;
          color:var(--text-faint);margin-bottom:12px;display:flex;align-items:center;gap:8px}
        .card-head .right{margin-left:auto;display:flex;align-items:center;gap:8px;text-transform:none;letter-spacing:0}

        .grid5{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
        .grid2{display:grid;grid-template-columns:1fr 1fr;gap:12px}
        .card.slim{padding:12px 16px}
        .danger-row{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
        .danger-row>div:first-child{flex:1 1 200px;min-width:0}
        .btn.btn-inline{width:auto;margin-top:0;padding:10px 22px;flex-shrink:0;align-self:center}
        .live-text{font-size:11px;font-weight:700;color:var(--text-faint);letter-spacing:.06em;text-transform:uppercase}
        .live-text.off{color:var(--red)}
        .stat-k{font-size:11px;color:var(--text-faint);margin-bottom:3px;font-weight:700;
          display:flex;align-items:center;gap:6px}
        .stat-v{font-size:20px;font-weight:800}
        .stat-sm{font-size:15px}
        .spark{width:100%;height:34px;display:block;margin-top:4px}
        .stat-sub{font-size:11px;color:var(--text-dim);margin-top:2px}

        .kv{background:var(--bg);border:1px solid var(--line);border-radius:10px;
          padding:10px 12px;display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px}
        .kv-k{font-size:11px;color:var(--text-dim);font-weight:700}
        .kv-v{font-size:13px;font-weight:700;font-family:var(--mono);word-break:break-all;text-align:right}
        .mini-btn{background:var(--card);border:1px solid var(--line);color:var(--text);
          border-radius:8px;padding:6px 9px;font-size:12px;font-weight:700;cursor:pointer;flex-shrink:0}
        .ep{border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin-bottom:8px;
          display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px}
        .ep-tag{font-size:11px;font-weight:800;color:var(--text-dim);min-width:64px}
        .ep-val{font-family:var(--mono);font-weight:600;word-break:break-all}
        .ep-backup{border-style:dashed}

        .cli{display:flex;align-items:center;gap:12px;border:1px solid var(--line);
          border-radius:12px;padding:10px 12px;margin-bottom:8px}
        .avatar{width:36px;height:36px;border-radius:50%;display:inline-grid;place-items:center;
          font-weight:800;font-size:15px;flex-shrink:0}
        .cli-main{flex:1;min-width:0}
        .cli-name{font-weight:700;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .cli-sub{font-size:12px;color:var(--text-dim)}
        .cli-active{font-size:12px;font-weight:700;color:var(--text-dim);flex-shrink:0}
        .cli-empty{font-size:13px;color:var(--text-faint);padding:6px 0}
        .split-head{font-size:11px;font-weight:800;letter-spacing:.06em;color:var(--text-faint);margin:12px 0 6px}
        .split{display:flex;height:8px;border-radius:999px;overflow:hidden;background:var(--bg);border:1px solid var(--line)}
        .split-legend{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap;
          font-size:11px;color:var(--text-dim);margin-top:6px}

        .log{background:#1c1917;border:1px solid var(--line);border-radius:10px;
          padding:10px 12px;max-height:220px;overflow-y:auto;font-family:var(--mono);
          font-size:12px;line-height:1.65;color:#e7e5e4}
        [data-theme="dark"] .log{background:#0c0a09}
        .log-line{white-space:pre-wrap;word-break:break-all}
        .log-line-dim{color:#a8a29e}
        .log-big{max-height:380px;min-height:200px}
        .log::-webkit-scrollbar{width:8px}
        .log::-webkit-scrollbar-thumb{background:#44403c;border-radius:4px}

        .field-label{display:block;font-size:12px;font-weight:700;color:var(--text-dim);margin:14px 0 6px}
        .field{width:100%;padding:10px 12px;border-radius:9px;border:1px solid var(--line);
          background:var(--bg);color:var(--text);font-size:14px;font-family:var(--sans)}
        .field:focus{outline:2px solid var(--green-line);border-color:var(--green)}
        .seg{display:grid;grid-template-columns:1fr 1fr 1fr;gap:4px;background:var(--bg);
          border:1px solid var(--line);border-radius:10px;padding:4px}
        .seg label{margin:0}
        .seg input{position:absolute;opacity:0;width:0;height:0}
        .seg span{display:block;text-align:center;padding:9px 4px;border-radius:7px;
          font-size:13px;font-weight:700;cursor:pointer;color:var(--text-dim)}
        .seg input:checked + span{background:var(--text);color:var(--bg)}
        .seg input:focus-visible + span{outline:2px solid var(--green);outline-offset:2px}

        .switch-row{display:flex;align-items:center;justify-content:space-between;
          padding:10px 0;font-size:13px;font-weight:600;cursor:pointer}
        .switch-row input{position:absolute;opacity:0;width:0;height:0}
        .switch{position:relative;width:42px;height:24px;border-radius:999px;background:var(--line);
          transition:background .15s;flex-shrink:0}
        .switch::after{content:'';position:absolute;top:2px;left:2px;width:20px;height:20px;
          border-radius:50%;background:#fff;transition:transform .15s;box-shadow:0 1px 2px rgba(0,0,0,.25)}
        .switch-row input:checked + .switch{background:var(--green)}
        .switch-row input:checked + .switch::after{transform:translateX(18px)}
        .switch-row input:focus-visible + .switch{outline:2px solid var(--green);outline-offset:2px}

        .btn{width:100%;padding:12px;border:1px solid var(--text);border-radius:9px;background:var(--text);
          color:var(--bg);font-size:14px;font-weight:700;margin-top:14px;cursor:pointer}
        [data-theme="dark"] .btn{background:#f5f2ee;border-color:#f5f2ee;color:#171412}
        .btn:hover{filter:brightness(1.1)}
        .btn-danger{background:var(--red);border-color:var(--red);color:#fff}
        [data-theme="dark"] .btn-danger{background:var(--red);border-color:var(--red);color:#1c0a0a}
        .danger-card{border-color:var(--red-line)}

        .note{font-size:12px;line-height:1.5;color:var(--text-faint);margin:10px 0 0}
        code{background:var(--bg);border:1px solid var(--line);padding:1px 6px;border-radius:4px;font-family:var(--mono);font-size:11px}

        .foot{text-align:center;color:var(--text-faint);font-size:11px;margin-top:22px}

        @media(max-width:640px){.grid2{grid-template-columns:1fr}.grid5{grid-template-columns:1fr 1fr}.danger-row{flex-direction:column;align-items:stretch}.btn.btn-inline{width:100%}}
        </style>
    """.trimIndent()

    private fun clientRows(): String {
        val list = AppState.lanClients.value
        if (list.isEmpty()) return "<div class=\"cli-empty\">No clients connected.</div>"
        val total = list.sumOf { it.mb }.coerceAtLeast(0.001)
        val sb = StringBuilder()
        list.forEachIndexed { i, c ->
            val initial = c.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
            val bg = if (i % 2 == 0) "background:var(--text);color:var(--bg)" else "background:#c2410c;color:#fff"
            sb.append("<div class=\"cli\"><span class=\"avatar\" style=\"").append(bg).append("\">").append(initial)
            sb.append("</span><div class=\"cli-main\"><div class=\"cli-name\">").append(escapeHtml(c.name))
            sb.append("</div><div class=\"cli-sub mono\">").append(escapeHtml(c.ip)).append(" · ")
            sb.append(Math.round(c.mb * 10) / 10.0).append(" MB</div></div>")
            sb.append("<span class=\"cli-active\">active</span></div>")
        }
        sb.append("<div class=\"split-head\">Data split</div><div class=\"split\">")
        val cols = listOf("var(--text)", "#c2410c", "var(--text-faint)")
        list.forEachIndexed { i, c ->
            val pct = (c.mb / total * 100).coerceIn(0.0, 100.0)
            sb.append("<div style=\"width:").append(Math.round(pct * 10) / 10.0)
            sb.append("%;background:").append(cols[i % cols.size]).append("\"></div>")
        }
        sb.append("</div><div class=\"split-legend\">")
        list.forEach { c ->
            val pct = Math.round(c.mb / total * 100)
            sb.append("<span>").append(escapeHtml(c.name)).append(" ").append(pct).append("%</span>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
