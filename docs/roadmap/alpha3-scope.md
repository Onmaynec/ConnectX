# v0.2.0-alpha.3 — controlled native bridge self-test

## Goal

Allow an explicit, user-initiated native bridge lifecycle check on a physical Android device while keeping ordinary application traffic outside the unfinished engine.

## Safety boundary

- retain the TEST-NET-1 route `192.0.2.0/24`;
- do not add `0.0.0.0/0` or `::/0`;
- do not enable UDP, DNS, IPv6, QUIC or DPI strategies;
- require an explicit diagnostic action in the UI;
- surface ABI, native version, start/stop result and errors without logging SOCKS credentials;
- always close native session, duplicated TUN fd, relay and TUN after stop/revoke/error.

## Acceptance criteria

- native library availability and version are shown in the app;
- self-test performs load → version → start → isRunning and remains active until explicit stop;
- the native bridge receives only a duplicated TUN descriptor;
- repeated start requests are idempotent;
- revoke/stop closes native session before relay and TUN teardown;
- status broadcasts identify Foundation vs native self-test mode;
- diagnostics never include traffic contents or SOCKS credentials;
- reducer/lifecycle tests cover mode transitions;
- CI verifies APK/native payloads and publishes a prerelease;
- release notes clearly state that bypass is not implemented yet.
