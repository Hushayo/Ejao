# Ejao

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Ejao launcher icon">
</p>

![Latest release](https://img.shields.io/github/v/release/Hushayo/Ejao)
![CI build](https://img.shields.io/github/actions/workflow/status/Hushayo/Ejao/build.yml?label=CI%20build)
![License](https://img.shields.io/github/license/Hushayo/Ejao)

> [!NOTE]
> **STABLE** for its core use case: a systemwide TCP+UDP proxy over a WiFi
> Direct hotspot. Validated end-to-end with Proxifier — verify on your setup
> before relying on it for anything critical.

Android app (Kotlin) that turns a spare Android phone into a **WiFi Direct
hotspot + proxy** so a PC can reach the internet through the phone's data
connection — UDP included.

1. Starts a **WiFi Direct** access point with a custom SSID + password
2. Runs **SOCKS5** (`1080`, TCP + UDP ASSOCIATE) and **HTTP** (`8282`, plain +
   CONNECT) together, plus legacy **SOCKS4a** (`1081`)
3. Built-in **control panel** at `http://192.168.49.1:8283/` — live status,
   per-client usage, restart (backup panel on `8284` survives crashes)
4. Runs as an aggressive foreground service (wakelocks, `START_STICKY`,
   battery-exemption + autostart shortcuts)
5. **Self-updating**: checks GitHub for new releases, downloads `ejao.apk` in
   the background, one tap to install

Full setup guide, config reference and keep-alive details live in the
**[wiki](https://github.com/Hushayo/Ejao/wiki)**.

## Using it

1. Install `ejao.apk` (GitHub → Releases), grant the asked permissions, set a
   WiFi password (8–63 chars), tap **START PROXY**. The phone creates a
   hotspot like `DIRECT-XXEjaoAP` and runs the proxy on it.
2. Connect your PC to that hotspot with the shown password.
3. Run a SOCKS5 client like **Proxifier** so *every* program goes through the
   phone — TCP, UDP, DNS:
   - **Profile → Proxy Servers → Add**: address `192.168.49.1`, port `1080`,
     protocol `SOCKS5`, tick **UDP**
   - **Profile → Proxification Rules**: route everything through it; add
     `192.168.49.0/24` as **Direct** so the control panel stays reachable
4. Need browser-only TCP without extra software? Use the built-in Windows
   proxy instead: Settings → Network → Proxy → manual `192.168.49.1:8282`
   (HTTP). No UDP that way — details on the
   [wiki](https://github.com/Hushayo/Ejao/wiki/Windows-Connect).

## Endpoints

| Service | Address | Notes |
|---|---|---|
| SOCKS5 (TCP + UDP) | `192.168.49.1:1080` | Systemwide via a SOCKS5 client |
| HTTP proxy | `192.168.49.1:8282` | Plain + CONNECT, TCP only |
| SOCKS4a (legacy) | `192.168.49.1:1081` | TCP only, needs remote hostname resolving |
| Control panel | `http://192.168.49.1:8283/` | Status, clients, settings, restart |
| Backup panel | `http://192.168.49.1:8284/` | Survives proxy crashes, emergency restart |

`192.168.49.1` is the phone's WiFi Direct gateway. SOCKS5/HTTP ports are
configurable; panel ports default to `http_port + 1` / `+ 2`.

## Updating

No need to hunt for APKs after the first install:

1. App → **Keep-Alive** tab → **CHECK FOR UPDATES**. It compares your version
   against the latest GitHub release.
2. If newer, it downloads `ejao.apk` in the background (progress shown) and
   opens the system installer — you tap **Install** once.
3. The proxy auto-resumes afterwards (same signature, installs in place).

Background checks silently download new releases every 6 hours by default
(configurable in the same tab, `0` disables them) — installing is always your
tap. Every release is signed with the same key, so updates never need an
uninstall.

## Config file

Persisted on the phone in three places (the last one survives reinstalls):

- Internal: `/data/data/com.ejao.proxy/files/Ejao/config.txt` (app-private)
- Mirror: `Android/data/com.ejao.proxy/files/Ejao/config.txt` (file-manager
  visible, no root — editing it while running applies live)
- Uninstall-proof: `Documents/Ejao/config.txt` (public folder, restored
  automatically after reinstall)

```ini
ssid=EjaoAP
password=                     # 8-63 ASCII chars, required before first start
port=1080                     # SOCKS5 TCP+UDP
band=5                        # 5 = 5 GHz (default), 2.4 = 2.4 GHz, auto = phone decides
disable_band_selector=false   # true = force 2.4 GHz, hide the picker
proxy_mode=socks5             # legacy hint; proxy_type drives the mode
proxy_type=0                  # 0 = SOCKS5+HTTP (only mode in the UI), 1 = SOCKS5, 2 = HTTP, 3 = Hybrid
http_port=8282
socks4_port=1081
panel_port=8283               # defaults to http_port + 1
backup_panel_port=8284        # defaults to panel_port + 1
panel_enabled=true
require_approval_restart=false  # true = panel restarts/settings need in-app approval within 10s
keepalive_url=https://www.google.com/generate_204
keepalive_interval_ms=60000   # min 15000
keep_retrying_reform=false    # true = recreate a dropped hotspot forever
auto_restart_on_wifi_return=false
update_check_interval_hours=6 # 0 = disable background update checks
```

Android constraints: SSID gets the required `DIRECT-xy` prefix automatically,
password must be 8–63 ASCII chars, ports 1–65535.

## Keep-alive (do once after installing)

Aggressive ROMs kill background apps. In the app's Keep-Alive tab:

- **REQUEST BATTERY EXEMPTION** → allow ignoring optimizations
- **AUTOSTART SETTINGS** → enable autostart (Honor/Huawei/Xiaomi deep-links)
- Lock the app in recents so it can't be swiped away

The proxy also runs as a `START_STICKY` foreground service (wake + WiFi locks),
re-arms itself on boot/update, and pings `keepalive_url` on an interval so the
OS sees live traffic. See the [wiki](https://github.com/Hushayo/Ejao/wiki/Keep-Alive).

## Limitations

- **OEM quirks:** on some HONOR / Huawei / Xiaomi phones Android reports
  cellular as internet-capable but bound sockets don't route — some sites
  fail to load. Firmware limitation, not a proxy bug; prefer a phone whose
  cellular egress routes normally.
- **UDP is best-effort:** needs a client with real UDP ASSOCIATE support;
  no fragmentation, no IPv6 targets, some carriers throttle UDP. WebRTC apps
  (e.g. Discord voice) bypass SOCKS5 for UDP entirely — nothing arrives to
  relay. Fall back to TCP when UDP misbehaves.
- **SOCKS4 needs 4a:** enable remote hostname resolving (SOCKS4a) in the
  client or hostnames won't resolve. Details on the
  [wiki](https://github.com/Hushayo/Ejao/wiki/PC-Client#option-3--socks4--socks4a-legacy-clients-tcp-only).

## Building

CI only — pushing to `main` with `[Trigger]` in the commit message builds
and publishes the next versioned release (`ejao.apk`). `[DEBUG]` runs a
compile-only check. Plain commits build nothing. Never commit the signing
keystore or its passwords — they live only in GitHub Secrets.
