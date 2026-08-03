# Changelog

All notable changes to ConnectX are documented in this file.

## [Unreleased]

### Planned

- Transport-level observation of actual TCP segment boundaries.
- Physical-device repeated TUN lifecycle verification.
- First strategy verified on a reproducible restricted network.

## [0.3.0-alpha.1]

### Added

- New pure Kotlin `:strategy:api` module.
- Typed `BypassStrategy` contract and canonical strategy identifiers.
- Capability model for TCP, UDP, IPv4, IPv6, TLS, QUIC and root.
- Typed execution context separating lab-only and user-traffic scopes.
- Global strategy feature gate disabled by default.
- Immutable strategy registry with duplicate-id rejection.
- First lab-only strategy descriptor: `tls-clienthello-split-v1`.
- Bounded TLS record and ClientHello inspector.
- Deterministic two-write split plan after the ClientHello fixed prefix.
- Lossless payload reconstruction helper with defensive segment copies.
- Exact TEST-NET TLS lab target limited to `192.0.2.1:18443`.
- Android foreground `VpnService` path: synthetic ClientHello → two ordered writes → TUN → gVisor/tun2socks → authenticated relay → loopback echo.
- Protected relay connection to the process-local echo endpoint.
- Manual Compose control labelled `TLS write-split (Lab)`.
- Typed strategy diagnostics: strategy id, write count, split offset, latency, bytes and relay connections.
- JVM unit tests for parser boundaries, feature gates, registry invariants and reconstruction.
- Reducer tests for strategy start, completion and scoped failure states.
- Isolated Android instrumentation gate proving the planner is packaged and disabled by default.
- Isolated Android instrumentation gate verifying the lossless two-write stream through the real TEST-NET TUN path.
- Version-aware Compose diagnostics.
- Application version `0.3.0-alpha.1`, versionCode `8`.

### Rejected inputs

- feature-disabled execution;
- ordinary user-traffic scope;
- repeated planning of an already handled payload;
- UDP, IPv6, QUIC and unknown application contexts;
- non-handshake TLS records;
- non-ClientHello handshakes;
- unsupported TLS record versions;
- truncated records;
- mismatched record or handshake lengths;
- trailing data and payloads larger than the bounded lab limit;
- every strategy-lab target except `192.0.2.1:18443`.

### Safety boundaries

- The strategy is activated only by an explicit lab action and is not connected to ordinary device traffic.
- The global feature gate remains disabled by default and the first strategy rejects `USER_TRAFFIC` scope.
- Two socket writes are not claimed to equal two TCP segments or two IP packets on the wire.
- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- No default IPv4 or IPv6 route is added.
- The strategy endpoint and authenticated relay bind only to `127.0.0.1` after exact target rewriting.
- System DNS traffic is not intercepted and no external resolver is contacted.
- TLS is not decrypted, semantically modified or intercepted with custom certificates.
- Payloads, query names and SOCKS credentials are not logged.
- No remote ConnectX server is used.
- The unchanged native transport bridge keeps its `0.2.0-alpha.6` component version.
- This release does not claim working censorship bypass.

## [0.2.0-alpha.6]

### Added

- Explicit `Native DNS probe` mode and dedicated foreground `VpnService`.
- Strict bounded DNS codec without additional runtime dependencies.
- One exact uncompressed `A/IN connectx.invalid` request with a random transaction ID.
- Deterministic authoritative non-recursive answer `192.0.2.42`.
- Loopback-only DNS responder that never forwards requests to an external resolver.
- Exact UDP allow-list limited to `192.0.2.53:53`.
- Protected outbound relay datagram socket through `VpnService.protect()`.
- DNS latency, uploaded/downloaded bytes, association, datagram, query and response counters.
- DNS reducer state and Compose diagnostics.
- JVM tests for DNS encoding, decoding, malformed packets and the real loopback responder.
- Android instrumentation gate for the full DNS path through TUN, gVisor/tun2socks and authenticated SOCKS5 UDP relay.
- Isolated Android runtime sessions for JNI, TCP, UDP and DNS gates with explicit process teardown between tests.
- Per-gate Android reports retained in the emulator diagnostics artifact.
- Native bridge version synchronized to `0.2.0-alpha.6`.

### Rejected inputs

- compressed DNS names;
- multiple questions;
- non-`A` query types;
- non-`IN` classes;
- unexpected flags, sections, answer address or transaction ID;
- truncated, oversized or trailing packet data;
- every target except `192.0.2.53:53`.

### Safety boundaries

- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- No default IPv4 or IPv6 route is added.
- System DNS traffic is not intercepted.
- No external DNS resolver is contacted.
- The responder and authenticated relay bind only to `127.0.0.1`.
- Query names, payloads and SOCKS credentials are not logged.
- IPv6, QUIC and DPI obfuscation remain disabled.
- No remote ConnectX server, HTTPS interception or certificate installation is used.
- This release does not claim working censorship bypass.

## [0.2.0-alpha.5]

### Added

- Explicit `Native UDP probe` mode in the diagnostics card.
- End-to-end Android path: IPv4 datagram socket → TEST-NET TUN → gVisor/tun2socks → authenticated SOCKS5 UDP relay → loopback echo endpoint.
- Exact UDP target override limited to `192.0.2.1:18081`.
- Protected outbound relay datagram socket through `VpnService.protect()`.
- Random 64-byte nonce echo verification.
- Probe latency, uploaded/downloaded byte counters, UDP association count and datagram count in UI state and broadcasts.
- SOCKS5 UDP framing with `FRAG != 0` rejection.
- Generation-safe teardown after success, failure, stop or VPN revoke.
- JVM end-to-end tests for authenticated UDP ASSOCIATE and loopback echo.
- Android instrumentation gate that verifies both real TCP and UDP paths through foreground `VpnService`.
- Native TCP/UDP flow counters for payload-free runtime diagnostics.

### Fixed

- The UDP probe now binds explicitly to IPv4 wildcard `0.0.0.0` instead of a potentially IPv6 dual-stack wildcard.
- A bounded VPN-route settle and at most three fresh-socket attempts prevent the first one-shot UDP datagram from escaping before the TEST-NET route is active.
- Relay diagnostics now distinguish association, framing, resolver and echo stages without logging payloads, target data or credentials.

### Safety boundaries

- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- No default IPv4 or IPv6 routes are added.
- UDP ASSOCIATE is available only in the explicit diagnostic mode.
- Only the exact reserved endpoint `192.0.2.1:18081` is accepted.
- The relay and echo endpoint bind only to `127.0.0.1`.
- DNS, IPv6, QUIC and DPI obfuscation remain disabled.
- No remote ConnectX server, HTTPS interception, certificate installation or traffic-content logging is used.
- This release does not claim working censorship bypass.

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
