# Ejao

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Ejao launcher icon">
</p>

![Latest release](https://img.shields.io/github/v/release/Hushayo/Ejao)
![CI build](https://img.shields.io/github/actions/workflow/status/Hushayo/Ejao/build.yml?label=CI%20build)
![License](https://img.shields.io/github/license/Hushayo/Ejao)

> [!NOTE]
> **Stable** Things are subject to change.

> dev note : btw this is like pdanet and tetherfusenet replacement 

Android app (Kotlin) makes **WiFi Direct have Internet access via proxy** so a PC can reach the internet through the phone proxy
connection

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

1. Install `ejao.apk` (GitHub → Releases), set a
   WiFi password (8–63 chars), tap **START PROXY**, then grant the permissions, The phone creates the Wifi direct
   with default SSID `DIRECT-EjaoAP` and runs the proxy on it.
3. Connect your PC to that WIFI with your set password.
4. Here you have two options,
-> Have HTTP only (browses web only, no external programs unless those support WinHTTP) just do Settings → Network → Proxy → manual `192.168.49.1:8282` here's image if you don't prefer text [wiki](https://github.com/Hushayo/Ejao/wiki/Windows-Connect).
-> or have the SOCKS5 (wiki tutor will be here soon)

## Constraints 

Android constraints: SSID sets `DIRECT-EjaoAP` prefix automatically, you can change it.
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

- **UDP is best-effort:** needs a client with real UDP ASSOCIATE support;
  no fragmentation, no IPv6 targets, some carriers throttle UDP. WebRTC apps
  (e.g. Discord voice) bypass SOCKS5 for UDP entirely — nothing arrives to
  relay. Fall back to TCP when UDP misbehaves.
 ------------------------------------------
- **SOCKS4 needs 4a:** enable remote hostname resolving (SOCKS4a) in the
  client or hostnames won't resolve. Details on the
  [wiki](https://github.com/Hushayo/Ejao/wiki/PC-Client#option-3--socks4--socks4a-legacy-clients-tcp-only).
