# ConnectX v0.3.0-alpha.4 scope

## Goal

Make restricted-network TLS evidence repeatable and capable of recognizing a strategy-only recovery without expanding capture beyond TEST-NET-1.

## Included

- Telegram, YouTube, Discord and custom-host target selection;
- three samples per BASELINE/STRATEGY/RECOVERY phase;
- restricted-baseline evaluator branch;
- explicit environment-instability classification;
- success/failure counters and median latency;
- redacted repeated-evidence report;
- versioned native bridge and Android gates;
- exact-commit CI and prerelease publisher.

## Excluded

- default routes;
- routing ordinary application traffic;
- automatic strategy selection;
- IPv6, QUIC and voice traffic;
- root mode;
- HTTP requests, authentication or private APIs;
- universal bypass claims.

## Release gate

- all JVM/unit tests;
- Android lint and APK payload verification;
- Android 35 x86_64 strategy, repeated evidence, JNI, TCP, UDP and DNS gates;
- two repeated evidence sessions and eighteen responder flows;
- deterministic APK/native/checksum assets on the exact release commit.
