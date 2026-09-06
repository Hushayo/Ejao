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
        onLog("Control panel listening on port $port")
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
        try {
            AppState.status.collect { value ->
                if (!running.get() || client.isClosed) throw IOException("stream closed")
                val line = "data: ${escapeSse(value)}\n\n"
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
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
            append("\"requireApprovalRestart\":").append(cfg.requireApprovalRestart).append(',')
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
        val mode = when {
            AppState.httpMode.value && info.clients >= 0 && cfg.effectiveMode() == "http" -> "HTTP"
            cfg.isHybrid() -> "Hybrid"
            cfg.effectiveMode() == "http" -> "HTTP"
            else -> "SOCKS5"
        }
        val panelChecked = if (cfg.panelEnabled) "checked" else ""
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
            <div class="brand"><span class="dot" id="v-dot"></span><span class="brand-name">SACRAM</span><span class="brand-sub">control panel</span></div>
            <div class="ver">v${BuildConfig.VERSION_NAME}</div>
        </header>

        <section class="card">
            <div class="card-head">Status</div>
            <div class="grid">
                <div class="stat"><div class="stat-k">Running</div><div class="stat-v" id="v-running">${AppState.running.value}</div></div>
                <div class="stat"><div class="stat-k">Uptime</div><div class="stat-v mono" id="v-uptime">$uptimeStr</div></div>
                <div class="stat"><div class="stat-k">Mode</div><div class="stat-v"><span class="pill" id="v-mode">$mode</span></div></div>
                <div class="stat"><div class="stat-k">Clients</div><div class="stat-v" id="v-clients">${info.clients}</div></div>
                <div class="stat"><div class="stat-k">TCP tunnels</div><div class="stat-v" id="v-tunnels">${AppState.tcpTunnels.value}</div></div>
                <div class="stat"><div class="stat-k">Live feed</div><div class="stat-v"><span class="pill pill-live" id="v-live">connecting&hellip;</span></div></div>
            </div>
        </section>

        <section class="card">
            <div class="card-head">Activity <span class="card-head-note">live</span></div>
            <div class="log" id="v-log"><div class="log-line" id="v-log-current">${escapeHtml(AppState.status.value)}</div></div>
        </section>

        <section class="card">
            <div class="card-head">Network</div>
            <div class="list">
                <div class="li"><span class="li-k">SSID</span><span class="li-v mono" id="v-ssid">${escapeHtml(info.ssid)}</span></div>
                <div class="li"><span class="li-k">Password</span><span class="li-v mono" id="v-pass">${escapeHtml(info.passphrase)}</span></div>
                <div class="li"><span class="li-k">Group IP</span><span class="li-v mono" id="v-goip">${escapeHtml(info.goIp)}</span></div>
                <div class="li"><span class="li-k">Panel port</span><span class="li-v mono" id="v-panelport">$port</span></div>
                <div class="li"><span class="li-k">SOCKS5</span><span class="li-v mono">${escapeHtml(info.goIp)}:${cfg.port}</span></div>
                <div class="li"><span class="li-k">HTTP</span><span class="li-v mono">${escapeHtml(info.goIp)}:${cfg.httpPort}</span></div>
                <div class="li"><span class="li-k">Backup panel</span><span class="li-v mono">http://${escapeHtml(info.goIp)}:${if (info.backupPanelPort > 0) info.backupPanelPort else cfg.backupPanelPort}/</span></div>
            </div>
        </section>

        <form method="post" action="/restart">
            <section class="card">
                <div class="card-head">Restart</div>
                <button type="submit" class="btn btn-accent">Restart proxy</button>
                <p class="note">$restartNote</p>
            </section>
        </form>

        <form method="post" action="/">
            <section class="card">
                <div class="card-head">Settings</div>

                <label class="field-label" for="f-keepalive-url">Keep-alive URL</label>
                <input class="field" id="f-keepalive-url" type="text" name="keepalive_url" value="${escapeHtml(cfg.keepaliveUrl)}">

                <label class="field-label" for="f-keepalive-interval">Keep-alive interval &mdash; seconds, min 15</label>
                <input class="field" id="f-keepalive-interval" type="number" name="keepalive_interval" value="${cfg.keepaliveIntervalMs / 1000}" min="15">

                <label class="field-label" for="f-band">WiFi band</label>
                <select class="field" id="f-band" name="band">
                    <option value="2.4"${if (cfg.band == "2.4") " selected" else ""}>2.4 GHz</option>
                    <option value="5"${if (cfg.band == "5") " selected" else ""}>5 GHz (default)</option>
                    <option value="auto"${if (cfg.band == "auto") " selected" else ""}>Auto</option>
                </select>

                <label class="switch-row"><span>Control panel enabled</span><input type="checkbox" name="panel_enabled" value="on" $panelChecked><span class="switch"></span></label>

                <button type="submit" class="btn">Save settings</button>
                <p class="note">Changes apply live. The panel runs on its own port ($port) and is reachable by anyone on the WiFi Direct network. If your browser sends all traffic through the proxy, add <code>${escapeHtml(info.goIp)}</code> to its proxy bypass list to reach this panel directly.</p>
                <p class="note">Emergency: if the proxy goes down but WiFi Direct stays up, restart from the backup dashboard at <code>http://${escapeHtml(info.goIp)}:${if (info.backupPanelPort > 0) info.backupPanelPort else cfg.backupPanelPort}/</code> - it runs on its own port and survives proxy crashes.</p>
            </section>
        </form>

        <footer class="foot">Sacram &mdash; local control panel, no external access</footer>
        </div>
        <script>
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
        function sacramDot(running){
          var d=document.getElementById('v-dot');
          if(d) d.className='dot'+(running===true||running==='true'?' on':' off');
        }
        function sacramLive(state){
          var e=document.getElementById('v-live');
          if(!e) return;
          if(state==='on'){e.textContent='live';e.className='pill pill-live pill-live-on';}
          else if(state==='off'){e.textContent='disconnected';e.className='pill pill-live pill-live-off';}
          else{e.textContent='connecting\u2026';e.className='pill pill-live';}
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
            set('v-running',d.running);set('v-uptime',d.uptime);
            set('v-mode',d.mode);set('v-ssid',d.ssid);set('v-pass',d.passphrase);
            set('v-goip',d.goIp);set('v-clients',d.clients);set('v-tunnels',d.tcpTunnels);
            set('v-panelport',d.panelPort);
            sacramDot(d.running);
            sacramTick();
          }catch(e){}
        }
        sacramRefresh();
        sacramStartStream();
        setInterval(sacramRefresh,5000);
        setInterval(sacramTick,1000);
        </script>
        </body></html>
        """.trimIndent()
    }

    /**
     * Shared design tokens for the panel: dark technical theme, monospace
     * accents for network values, a signal-green status dot. Kept as one
     * block so every page (main, restart, pending, saved) looks consistent.
     */
    private fun panelStyle(): String = """
        <style>
        :root{
          --bg:#0a0c10; --bg-raised:#12151b; --line:#232830;
          --text:#e8ecf1; --text-dim:#8891a0; --text-faint:#5b6373;
          --accent:#3ddc84; --accent-dim:#1f7a4a;
          --blue:#4f8ef7;
          --mono:'SF Mono',ui-monospace,'Roboto Mono',Consolas,monospace;
          --sans:-apple-system,system-ui,'Segoe UI',Roboto,sans-serif;
        }
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans);
          -webkit-font-smoothing:antialiased}
        .wrap{max-width:640px;margin:0 auto;padding:20px 16px 40px}
        .mono{font-family:var(--mono)}

        .topbar{display:flex;align-items:baseline;justify-content:space-between;
          padding:4px 2px 18px;border-bottom:1px solid var(--line);margin-bottom:18px}
        .brand{display:flex;align-items:baseline;gap:8px}
        .brand-name{font-weight:700;font-size:17px;letter-spacing:0.06em}
        .brand-sub{color:var(--text-faint);font-size:12px}
        .ver{color:var(--text-faint);font-size:12px;font-family:var(--mono)}
        .dot{width:8px;height:8px;border-radius:50%;background:var(--text-faint);
          display:inline-block;position:relative;top:-1px;margin-right:2px}
        .dot.on{background:var(--accent);box-shadow:0 0 0 3px rgba(61,220,132,0.15)}
        .dot.off{background:#f0654f;box-shadow:0 0 0 3px rgba(240,101,79,0.15)}

        .card{background:linear-gradient(180deg,var(--bg-raised),rgba(18,21,27,0.6));
          border:1px solid var(--line);border-radius:12px;
          padding:16px;margin-bottom:14px;box-shadow:0 1px 0 rgba(255,255,255,0.02) inset}
        .card-head{font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;
          color:var(--text-faint);margin-bottom:12px;display:flex;align-items:center;gap:8px}
        .card-head-note{font-size:10px;font-weight:700;letter-spacing:0.04em;text-transform:none;
          color:var(--accent);background:rgba(61,220,132,0.1);border:1px solid rgba(61,220,132,0.2);
          border-radius:999px;padding:1px 7px}

        .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
        .stat-k{font-size:11px;color:var(--text-faint);margin-bottom:3px}
        .stat-v{font-size:15px;font-weight:600}

        .pill{display:inline-block;font-size:12px;font-weight:600;padding:2px 9px;
          border-radius:999px;background:rgba(61,220,132,0.12);color:var(--accent);
          border:1px solid rgba(61,220,132,0.25)}
        .pill-live{background:rgba(136,145,160,0.12);color:var(--text-dim);
          border:1px solid rgba(136,145,160,0.25)}
        .pill-live-on{background:rgba(61,220,132,0.12);color:var(--accent);
          border:1px solid rgba(61,220,132,0.25)}
        .pill-live-on::before{content:'';display:inline-block;width:6px;height:6px;border-radius:50%;
          background:var(--accent);margin-right:6px;position:relative;top:-1px;
          box-shadow:0 0 0 3px rgba(61,220,132,0.15)}
        .pill-live-off{background:rgba(240,101,79,0.1);color:#f0654f;
          border:1px solid rgba(240,101,79,0.22)}

        .log{background:#05070a;border:1px solid var(--line);border-radius:8px;
          padding:10px 12px;max-height:220px;overflow-y:auto;font-family:var(--mono);
          font-size:12px;line-height:1.65}
        .log-line{color:var(--text);white-space:pre-wrap;word-break:break-all}
        .log-line-dim{color:var(--text-dim)}
        .log::-webkit-scrollbar{width:8px}
        .log::-webkit-scrollbar-thumb{background:var(--line);border-radius:4px}

        .list{display:flex;flex-direction:column}
        .li{display:flex;justify-content:space-between;align-items:center;gap:10px;
          padding:9px 0;border-bottom:1px solid var(--line)}
        .li:last-child{border-bottom:0}
        .li-k{color:var(--text-dim);font-size:13px;flex-shrink:0}
        .li-v{font-size:13px;font-weight:600;text-align:right;word-break:break-all}

        .field-label{display:block;font-size:12px;color:var(--text-dim);margin:14px 0 6px}
        .field{width:100%;padding:10px 12px;border-radius:8px;border:1px solid var(--line);
          background:var(--bg);color:var(--text);font-size:14px;font-family:var(--sans)}
        .field:focus{outline:none;border-color:var(--accent-dim)}
        select.field{appearance:none;-webkit-appearance:none}

        .switch-row{display:flex;align-items:center;justify-content:space-between;
          padding:10px 0;font-size:13px;color:var(--text);cursor:pointer}
        .switch-row input{position:absolute;opacity:0;width:0;height:0}
        .switch{position:relative;width:38px;height:22px;border-radius:999px;background:var(--line);
          transition:background .15s;flex-shrink:0}
        .switch::after{content:'';position:absolute;top:2px;left:2px;width:18px;height:18px;
          border-radius:50%;background:var(--text-dim);transition:transform .15s,background .15s}
        .switch-row input:checked + .switch{background:var(--accent-dim)}
        .switch-row input:checked + .switch::after{transform:translateX(16px);background:var(--accent)}
        .switch-row input:focus-visible + .switch{outline:2px solid var(--accent);outline-offset:2px}

        .btn{width:100%;padding:12px;border:0;border-radius:8px;background:var(--accent);
          color:#04140b;font-size:14px;font-weight:700;margin-top:14px;cursor:pointer}
        .btn:hover{filter:brightness(1.08)}
        .btn-accent{background:var(--blue);color:#0a1220}

        .note{font-size:12px;line-height:1.5;color:var(--text-faint);margin:10px 0 0}
        code{background:var(--line);padding:1px 6px;border-radius:4px;font-family:var(--mono);font-size:11px}

        .foot{text-align:center;color:var(--text-faint);font-size:11px;margin-top:22px}

        @media(max-width:420px){.grid{grid-template-columns:repeat(2,1fr)}}
        </style>
    """.trimIndent()

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
