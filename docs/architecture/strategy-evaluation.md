# Strategy Evaluation and Rollback Gate

## Purpose

`v0.3.0-alpha.4` extends the bounded strategy evaluator so it can classify both healthy and reproducibly restricted baselines. It answers two narrow questions:

1. when baseline is healthy, does TLS split preserve the same path without excessive failures or latency;
2. when baseline is repeatedly unavailable, does TLS split restore the TLS response and does recovery reproduce the unavailable baseline.

The answer applies only to the current explicit Lab session, selected public hostname and current network. It is not persisted as universal evidence for an ISP, country, application or device.

## Repeated A/B/A sequence

The external evidence service opens nine fresh TCP connections:

```text
A — BASELINE × 3
  generated ClientHello
  → one SocketOutputStream.write()

B — STRATEGY × 3
  same ClientHello
  → tls-clienthello-split-v1
  → two ordered write() calls with a bounded gap

A — RECOVERY × 3
  same ClientHello
  → one SocketOutputStream.write()
```

Every connection uses the TEST-NET-only Android TUN, native userspace stack and authenticated local SOCKS5 relay. The relay rewrites only `192.0.2.1:18445` to one DNS-pinned public IPv4 on TCP/443 and protects its outbound socket with `VpnService.protect()`.

Two Java/Kotlin `write()` calls are not claimed to be two TCP packets. Kernel and userspace buffering may coalesce them.

## Evaluation input

The pure Kotlin evaluator receives only bounded samples:

- phase name;
- success latency in milliseconds; or
- a typed failure reason.

It never receives packet payloads, hostnames, URLs, credentials, DNS names, user identifiers or wall-clock timestamps. Each phase is capped at 100 samples by the API; alpha.4 submits exactly three.

Failure reasons are `TIMEOUT`, `CONNECTION_FAILED`, `PAYLOAD_MISMATCH`, `STRATEGY_REFUSED`, `CANCELLED` and `INTERNAL_ERROR`.

## Alpha.4 policy

- required successes per phase: `2`;
- maximum failures per phase: `1`;
- maximum relative latency regression: `50%`;
- minimum absolute regression budget: `250 ms`;
- cooldown after rollback, rejection, interruption or setup failure: `60 seconds`;
- restricted-baseline classification: enabled only for the explicit external evidence service.

For a healthy baseline the allowed strategy latency is:

```text
baseline median + max(
  ceil(baseline median × 50%),
  250 ms
)
```

Percentage multiplication and deadline arithmetic saturate at `Long.MAX_VALUE`.

## Decision order

### Healthy baseline

1. baseline must meet its sample budget;
2. recovery must also be healthy before the strategy is kept or blamed;
3. strategy failure or excessive latency produces rollback;
4. a complete A/B/A sequence within budget produces `KEEP_FOR_LAB_SESSION`.

### Restricted baseline

A baseline is consistently restricted when it exceeds the failure budget and does not meet the success requirement.

- restricted baseline + healthy strategy + restricted recovery → `STRATEGY_RESTORED_RESTRICTED_BASELINE`;
- restricted baseline + healthy strategy + healthy recovery → `RESTRICTED_BASELINE_NOT_REPRODUCED`;
- restricted baseline + consistently unhealthy strategy → `STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE`;
- mixed or insufficient samples → `INCONCLUSIVE`.

The recovery requirement prevents one transient baseline failure from being presented as strategy success.

## Session gate

```text
READY
  └─ explicit begin → EVALUATING

EVALUATING
  ├─ keep → LAB_APPROVED
  └─ rollback / reject / abort → COOLDOWN

LAB_APPROVED
  └─ new explicit user action → EVALUATING

COOLDOWN
  └─ deadline reached → READY

DISABLED
  └─ no automatic transition
```

An interrupted service, setup failure, cancellation or cleanup failure cannot leave the strategy approved. Generation checks run before and during each phase, write, response validation and relay-stat wait. A duplicated stop after completed teardown is idempotent.

## Android boundaries

The external evidence service:

- starts only after explicit user action and Android `VpnService` consent;
- installs only `192.0.2.0/24`, never `0.0.0.0/0` or `::/0`;
- validates one public hostname and pins one public IPv4;
- permits only TCP/443;
- sends one locally generated ClientHello and reads five TLS record-header bytes;
- uses random in-memory SOCKS5 credentials;
- closes socket, native stack, TUN and relay in generation-safe order;
- does not read or modify ordinary application traffic.

## Release gate

Android instrumentation runs two explicit sessions. Each session performs nine flows, so the deterministic loopback responder must observe eighteen connections. The release workflow repeats the canonical build, unit, lint, APK payload, strategy, evidence, JNI, TCP, UDP and DNS gates on the exact release commit before creating the prerelease.

## What this does not prove

A positive result does not prove:

- universal DPI bypass;
- that write boundaries survive as packet boundaries;
- compatibility with every endpoint behind Telegram, YouTube or Discord;
- production readiness;
- effectiveness on another network or later session.

A public release may describe only the measured, repeated result for the selected target and current network.
