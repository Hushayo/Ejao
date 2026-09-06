# Sacram

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Sacram launcher icon">
</p>


![Created](https://img.shields.io/badge/created-2026--08--11-blue)
![Platform](https://img.shields.io/badge/platform-Android%20%7C%20WiFi%20Direct-3DDC84)
![Made with](https://img.shields.io/badge/made%20with-Kotlin-7F52FF)
![Latest release](https://img.shields.io/github/v/release/SynacNipo/Sacram)
![Release date](https://img.shields.io/github/release-date/SynacNipo/Sacram)
![Downloads](https://img.shields.io/github/downloads/SynacNipo/Sacram/total)
![Last commit](https://img.shields.io/github/last-commit/SynacNipo/Sacram)
![Commit activity](https://img.shields.io/github/commit-activity/m/SynacNipo/Sacram)
![CI build](https://img.shields.io/github/actions/workflow/status/SynacNipo/Sacram/build.yml?label=CI%20build)
![Issues](https://img.shields.io/github/issues/SynacNipo/Sacram)
![Pull requests](https://img.shields.io/github/issues-pr/SynacNipo/Sacram)
![Stars](https://img.shields.io/github/stars/SynacNipo/Sacram)
![Forks](https://img.shields.io/github/forks/SynacNipo/Sacram)
![Repo size](https://img.shields.io/github/repo-size/SynacNipo/Sacram)
![Top language](https://img.shields.io/github/languages/top/SynacNipo/Sacram)
![License](https://img.shields.io/github/license/SynacNipo/Sacram)

> [!NOTE]
> **STABLE**
>
> Sacram is **stable** for its core use case: a systemwide TCP+UDP proxy over a
> WiFi Direct hotspot. The Android app, HTTP (TCP), SOCKS5 (TCP) and SOCKS5 UDP
> (UDP ASSOCIATE) proxies are all validated end-to-end. Pair it with any SOCKS5
> client on the PC (e.g. **Proxifier**) — verify on your setup before relying on
> it for anything critical.

## Status

| Component        | Status        | Notes                                                              |
|------------------|---------------|--------------------------------------------------------------------|
| Android app      | Stable        | Auto mode (HTTP+SOCKS5 TCP+UDP) validated over WiFi Direct |
| Connectivity     | Working       | PC reaches the internet via the phone proxy over WiFi Direct       |
| Desktop client   | Working       | Use a SOCKS5 client like **Proxifier** pointed at the phone (port `1080`) |
| HTTP (TCP) proxy | Working       | Plain HTTP + CONNECT tunnels, full-duplex                          |
| SOCKS5 (TCP)     | Working       | Runs in Auto mode alongside HTTP; validated in hybrid use          |
| SOCKS5 UDP       | Working*      | UDP ASSOCIATE works (RFC 1928) but is **best-effort** — see limitations below |

---

Android app (Kotlin) that turns any spare Android phone into a **WiFi Direct hotspot + proxy** so a PC can reach the internet through the phone's data connection — UDP included.

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a proxy on the phone in **Auto mode** — both **SOCKS5** (TCP CONNECT + UDP
   ASSOCIATE, RFC 1928, port `1080`) and **HTTP** (plain HTTP + CONNECT tunnels,
   port `8282`) start together, so clients can use whichever they support. The previous
   SOCKS5-only / HTTP-only / Hybrid picker was replaced by this single Auto mode.
3. A built-in **control panel**:
   - Shows live status, the app version, and a Restart button
   - Config is stored so it survives app reinstalls
   - Runs on its **own dedicated port** (default `http_port + 1`, i.e. `8283`) via a
     separate `PanelServer` — stays responsive even when the proxy is saturated by a
     busy page
   - Reach it directly at:
     ```
     http://<phone-ip>:<panel_port>/
     ```
     e.g. `http://192.168.49.1:8283/`
   - Since it's on its own port, a browser sending *all* traffic through the proxy
     must open it directly (or add the phone IP to the browser's proxy bypass list).
     A SOCKS5 client like Proxifier can be set to bypass the phone subnet, so direct
     access to the panel works there.
4. Lets a connected PC / device reach the internet through the phone's data connection
5. Runs as an **aggressive foreground service** (wakelocks, WiFi lock, `START_STICKY`, battery-exemption + autostart shortcuts)

Full setup guide, config reference, control panel and keep-alive
details live in the **[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## OEM quirks (some sites may not load)

Sacram binds every upstream socket to the phone's cellular data interface.
On some OEMs — notably **HONOR / Huawei / Xiaomi** — Android reports the
cellular network as having `NET_CAPABILITY_INTERNET` even though binding
sockets to it does not actually route. While the phone is the WiFi‑Direct
Group Owner the OS default route also points at the (internet‑less) P2P link,
so when that bogus cellular binding fails there is no working fallback and
**some websites fail to load** through that device while another phone
works fine.

This is a platform/firmware limitation, not a proxy bug. If a specific phone
exhibits it, prefer a device whose cellular egress binds and routes normally,
or check that model's behavior before relying on it.

## SOCKS5 UDP — limitations

UDP ASSOCIATE (RFC 1928) is implemented and validated end-to-end, but UDP
relay is inherently more fragile than TCP. Some programs will still "suck"
over it, and that is expected — not a bug:

- **The client must actually use UDP ASSOCIATE.** UDP only flows if the SOCKS5
  client supports it. Clients that funnel UDP over a TCP CONNECT, or expect a
  TUN/VPN-style interface, will not get UDP through Sacram at all.
- **Unroutable targets are refused by design.** Addresses that resolve to
  loopback (`127.0.0.0/8`) or private RFC1918 space (`10.x`, `172.16.x`,
  `192.168.x`) cannot be reached from the phone's cellular egress, so the proxy
  refuses them. Most apps tolerate a handful of these; a few (e.g. games with
  strict UDP voice/RTC health-checks) may surface a connection error.
- **No UDP fragmentation.** SOCKS5 fragmentation (RFC 1928 §7) is unsupported;
  fragmented or oversized datagrams are dropped.
- **No IPv6 UDP targets.** IPv6 destination addresses in the UDP request are
  dropped.
- **Carrier UDP restrictions.** Some mobile carriers throttle or block outbound
  UDP on high/ephemeral ports. When that happens, relayed sends fail — watch the
  app logcat for `UDP send fail …` lines to confirm.
- **DNS is resolved on the egress network.** If the upstream network can't
  resolve a relayed host, the datagram is dropped (logged as
  `UDP packet handling error`).
- **WebRTC apps (Discord voice, video calls) generally don't use SOCKS5 UDP at
  all.** Confirmed with Discord: even with Proxifier set to route both TCP and
  UDP through the proxy, voice connections fail with "No Route" while zero
  `UDP :` entries for the app ever appear in Proxifier's connection log — only
  its ordinary HTTPS/API traffic goes through the proxy. This is a limitation
  of the client, not Sacram: most WebRTC stacks bind UDP sockets directly to
  the network interface for ICE/STUN candidate gathering and never consult the
  configured SOCKS5 proxy for that traffic. Sacram's UDP ASSOCIATE has nothing
  to relay in this case because the datagrams never arrive.

If a program's UDP still misbehaves, fall back to the TCP path (HTTP / SOCKS5
TCP) — Sacram's UDP is best-effort, not a substitute for a full TUN VPN.

## SOCKS4 / SOCKS4a (legacy clients)

Sacram also serves a SOCKS4/SOCKS4a TCP server on port `1081` for legacy
clients. **Plain SOCKS4 cannot resolve hostnames remotely** — you must enable
the client's SOCKS4a (remote hostname resolving) extension or it fails with
`could not resolve a hostname through SOCKS 4 proxy server`. Full explanation
and client steps are on the wiki:
[SOCKS4 / SOCKS4a reference](https://github.com/SynacNipo/Sacram/wiki/PC-Client#socks4--socks4a-legacy-clients).

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.
