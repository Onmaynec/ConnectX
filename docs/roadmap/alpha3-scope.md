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

## Android runtime gate

The release candidate must also pass an x86_64 Android emulator smoke test that loads the packaged shared library and exercises JNI version, controlled invalid-fd failure and idempotent stop paths.

The smoke workflow is split into two jobs. The first job builds and verifies the locked native payload with Go and Android NDK. The second job starts from a clean runner, downloads only that verified payload and installs the emulator image. Keeping NDK and AVD storage on separate runners prevents the emulator's mandatory userdata image from exhausting the hosted runner disk.

The runtime job uses one explicit `ANDROID_AVD_HOME` for both `avdmanager` and `emulator`. It records available disk space, the resolved AVD list, acceleration report, emulator output and bounded ADB diagnostics for every run.

A successful emulator smoke test does not replace the physical-device gate for repeated real TUN start/stop/revoke testing on arm64 hardware.
