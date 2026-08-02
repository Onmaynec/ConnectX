# Changelog

All notable changes to ConnectX are documented in this file.

## [Unreleased]

### Planned

- Source-built tun2socks/gVisor bridge from the Android TUN fd.
- Direct UDP relay and DNS passthrough.
- First verified DPI-obfuscation strategy.

## [0.2.0-alpha.1]

### Added

- Pure Kotlin local SOCKS5 `CONNECT` relay.
- Direct outbound TCP sockets protected through `VpnService.protect()`.
- Bounded concurrent connection count.
- Bidirectional byte forwarding and aggregate relay statistics.
- SOCKS5 parser unit tests.
- End-to-end relay test using a local echo server.
- Architecture decision record for the userspace engine.
- App version metadata and UI text for the engine alpha.

### Safety boundaries

- The Android TUN still captures only TEST-NET-1 (`192.0.2.0/24`).
- Ordinary application traffic is not connected to the relay yet.
- The source-built tun2socks/gVisor bridge is still required before enabling the default route.
- UDP, DNS passthrough, IPv6 and DPI obfuscation are not implemented in this alpha.
- The local SOCKS endpoint listens only on the device loopback interface.
- No remote ConnectX proxy or VPN server is used.

## [0.1.0]

### Added

- Android 10+ application foundation.
- Jetpack Compose and Material 3 home screen.
- Explicit Android `VpnService` permission flow.
- Foreground local tunnel service with a permanent notification.
- Safe, idempotent TUN creation and shutdown lifecycle.
- Process-local status broadcasts between the service and UI.
- Unit-tested connection state reducer.
- GitHub Actions workflow for tests, lint and debug APK assembly.

### Safety boundaries

- v0.1.0 captures only the documentation-only TEST-NET-1 range (`192.0.2.0/24`).
- Normal device traffic is not redirected through the unfinished tunnel.
- No traffic is sent to ConnectX servers.
- DPI obfuscation is not implemented in this version.
- HTTPS interception, custom certificate installation and MITM are not used.
