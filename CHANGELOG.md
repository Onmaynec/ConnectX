# Changelog

All notable changes to ConnectX are documented in this file.

## [Unreleased]

### Planned

- Source-built tun2socks/gVisor bridge from the Android TUN descriptor to the local relay.
- Direct UDP and DNS passthrough.
- First verified DPI-obfuscation strategy.

## [0.2.0-alpha.1]

### Added

- Local SOCKS5 `CONNECT` relay bound to loopback.
- Mandatory per-run random SOCKS5 credentials; invalid clients cannot reach `VpnService.protect()` or open outbound sockets.
- Direct protected TCP sockets to real destinations without a ConnectX server.
- Bounded concurrent connections, bidirectional relay and byte counters.
- Unit and end-to-end tests with a local echo server.
- Architecture decision for a source-built tun2socks/gVisor path.
- Engine-alpha status in the existing ConnectX home-screen design.

### Safety boundaries

- Ordinary application traffic is not routed into the relay yet.
- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- UDP, DNS passthrough, IPv6, QUIC and DPI obfuscation are not implemented.
- HTTPS interception, custom certificates and MITM are not used.

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
