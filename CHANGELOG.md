# Changelog

All notable changes to ConnectX are documented in this file.

## [Unreleased]

### Planned

- Direct TCP/UDP passthrough through a local userspace network engine.
- First verified DPI-obfuscation strategy.

## [0.1.0] - pending verification

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

### Release gate

The `v0.1.0` tag and GitHub Release must not be created until CI succeeds on the exact release commit and the APK is installed on at least one Android 10+ device.
