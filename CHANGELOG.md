# Changelog

All notable changes to ConnectX are documented in this file.

## [Unreleased]

### Planned

- Physical-device repeated TUN lifecycle verification.
- Direct UDP and DNS passthrough.
- First verified DPI-obfuscation strategy.

## [0.2.0-alpha.4]

### Added

- Explicit `Native TCP probe` mode in the existing diagnostics card.
- End-to-end Android path: application socket → TEST-NET TUN → gVisor/tun2socks → authenticated relay → loopback echo endpoint.
- Exact relay target override limited to `192.0.2.1:18080`.
- Random 64-byte nonce echo verification.
- Probe latency, uploaded/downloaded byte counters and relay connection count in UI state and broadcasts.
- Automatic probe teardown after success, failure, stop or VPN revoke.
- Full Android instrumentation gate that prepares `VpnService`, starts the foreground service and verifies the real TCP path.
- Deterministic IPv4 loopback binding for the native SOCKS endpoint and probe echo endpoint.
- Native shutdown order aligned with upstream tun2socks: fd-backed device closes before gVisor stack wait.

### Safety boundaries

- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- No default IPv4 or IPv6 routes are added.
- The relay rewrite applies to one exact reserved diagnostic endpoint only.
- The echo endpoint and authenticated relay bind only to `127.0.0.1`.
- DNS, UDP, IPv6, QUIC and DPI obfuscation remain disabled.
- No remote ConnectX server, HTTPS interception, certificate installation or traffic-content logging is used.
- This release does not claim working censorship bypass.

## [0.2.0-alpha.3]

### Added

- Explicit user-initiated `Native self-test` mode.
- Authenticated relay + TEST-NET TUN + native gVisor bridge lifecycle.
- ABI, native version and self-test result diagnostics in the existing UI.
- Mode-aware status broadcasts and reducer transitions.
- Native session shutdown before TUN and relay teardown.
- Bounded JNI runtime smoke report with controlled invalid-fd failure.
- x86_64 Android emulator CI gate that loads the real `.so` and calls JNI.
- Emulator diagnostics and instrumentation test artifacts.

### Safety boundaries

- Native self-test requires explicit user action and Android VPN permission.
- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- No default IPv4/IPv6 routes are added.
- Ordinary application traffic is not routed through the unfinished engine.
- UDP, DNS passthrough, IPv6, QUIC and DPI obfuscation are not implemented.
- No SOCKS credentials or traffic contents are exposed in diagnostics.

## [0.2.0-alpha.2]

### Added

- Source-built Go/JNI bridge using tun2socks v2.7.0 and gVisor.
- Locked upstream commit, Go version, Android API and NDK version.
- Android shared libraries for `arm64-v8a` and `x86_64`.
- Native start/stop/status/error API without CLI `log.Fatal` behavior.
- Explicit ownership transfer of a duplicated Android TUN descriptor.
- Idempotent native shutdown and fd-closure tests.
- APK verification that both native ABIs are packaged.
- Third-party notices and upstream MIT license.

### Safety boundaries

- The native bridge is packaged but disabled by default.
- Ordinary application traffic is not routed into gVisor yet.
- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- Default route, UDP, DNS passthrough, IPv6, QUIC and DPI obfuscation are not enabled.

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
