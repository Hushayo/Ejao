# Ejao

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Ejao launcher icon">
</p>

![Latest release](https://img.shields.io/github/v/release/mjoohos23/Ejao)
![CI build](https://img.shields.io/github/actions/workflow/status/mjoohos23/Ejao/build.yml?label=CI%20build)
![License](https://img.shields.io/github/license/mjoohos23/Ejao)

> [!NOTE]
> **STABLE** for its core use case: a systemwide TCP+UDP proxy over a WiFi
> Direct hotspot. Validated end-to-end with Proxifier — verify on your setup
> before relying on it for anything critical.

Android app (Kotlin) that turns a spare Android phone into a **WiFi Direct
hotspot + proxy** so a PC can reach the internet through the phone's data
connection — UDP included.

1. Starts a **WiFi Direct** access point with a custom SSID + password
2. Runs **Auto mode** — **SOCKS5** (`1080`, TCP + UDP ASSOCIATE) and **HTTP**
   (`8282`, plain + CONNECT) together, plus legacy **SOCKS4a** (`1081`)
3. Built-in **control panel** at `http://192.168.49.1:8283/` — live status,
   per-client usage, restart (backup panel on `8284` survives crashes)
4. Runs as an aggressive foreground service (wakelocks, `START_STICKY`,
   battery-exemption + autostart shortcuts)

Full setup guide, config reference and keep-alive details live in the
**[wiki](https://github.com/mjoohos23/Ejao/wiki)**.

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
  [wiki](https://github.com/mjoohos23/Ejao/wiki/PC-Client#socks4--socks4a-legacy-clients).

## Building

CI only — pushing to `main` with `[Trigger]` in the commit message builds
and publishes the next versioned release (`ejao.apk`). Plain commits are
gated and never build.
