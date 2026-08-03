# Strategy Evaluation and Rollback Gate

## Purpose

`v0.3.0-alpha.2` adds a bounded health gate around the first lab-only strategy. It answers a narrower question than “does this bypass DPI?”:

> Does the strategy preserve byte integrity and complete the same local TEST-NET path without unacceptable errors or latency regression, while the baseline and recovery paths remain healthy?

The answer is used only for the current explicit Lab session. It is not persisted as proof that a strategy works on an ISP, mobile carrier, Wi-Fi network, domain, application, or country.

## A/B/A sequence

```text
A — BASELINE
  synthetic TLS ClientHello
  → one SocketOutputStream.write()
  → 192.0.2.1:18444
  → Android TEST-NET TUN
  → native userspace stack
  → authenticated loopback SOCKS5 relay
  → loopback echo endpoint

B — STRATEGY
  same synthetic TLS ClientHello
  → tls-clienthello-split-v1 plan
  → two ordered write() calls with bounded gap
  → same TEST-NET path and endpoint

A — RECOVERY
  same synthetic TLS ClientHello
  → one write()
  → same TEST-NET path and endpoint
```

Every phase uses a separate TCP connection. The service verifies that the echoed payload is byte-for-byte identical to the original synthetic ClientHello and waits for the relay to confirm one new connection and the expected byte deltas.

Two Java/Kotlin `write()` calls are not claimed to be two TCP packets on the wire. Kernel and userspace buffering may coalesce writes.

## Evaluation input

The pure Kotlin evaluator receives only bounded, privacy-safe samples:

- phase name;
- success latency in milliseconds; or
- a typed failure reason.

It never receives packet payloads, host names, URLs, credentials, DNS names, user identifiers, or wall-clock timestamps.

Failure reasons are:

- `TIMEOUT`;
- `CONNECTION_FAILED`;
- `PAYLOAD_MISMATCH`;
- `STRATEGY_REFUSED`;
- `CANCELLED`;
- `INTERNAL_ERROR`.

Each phase is capped at 100 samples by the API. The alpha.2 Android Lab currently submits one sample per phase.

## Policy

The Android Lab policy is intentionally conservative and fixed in code:

- required successes per phase: `1`;
- maximum failures per phase: `0`;
- maximum relative latency regression: `50%`;
- minimum absolute regression budget: `100 ms`;
- cooldown after rollback, rejection, interruption, or setup failure: `60 seconds`.

The allowed strategy latency is:

```text
baseline median + max(
  ceil(baseline median × 50%),
  100 ms
)
```

Percentage multiplication and deadline arithmetic saturate at `Long.MAX_VALUE`; they do not wrap on overflow. A zero-millisecond synthetic sample is also handled without division.

## Decision order

The evaluator is deterministic:

1. An unhealthy or insufficient baseline is never blamed on the strategy.
2. A healthy recovery is required before the evaluator can keep or blame the strategy.
3. Failed recovery produces `REJECT_ENVIRONMENT_UNSTABLE` or `INCONCLUSIVE`.
4. Strategy failure with healthy baseline and recovery produces `ROLLBACK_CONFIRMED`.
5. Excessive strategy latency with healthy baseline and recovery produces `ROLLBACK_CONFIRMED`.
6. Only a fully healthy A/B/A sequence within the latency budget produces `KEEP_FOR_LAB_SESSION`.

Possible decisions:

| Decision | Meaning |
| --- | --- |
| `KEEP_FOR_LAB_SESSION` | The local synthetic check passed; the strategy may remain approved only in the current Lab process/session. |
| `ROLLBACK_CONFIRMED` | Baseline and recovery were healthy, while the strategy failed or exceeded its latency budget. |
| `REJECT_BASELINE_UNHEALTHY` | The environment was already unhealthy before strategy execution. |
| `REJECT_ENVIRONMENT_UNSTABLE` | Recovery did not restore a healthy baseline path. |
| `INCONCLUSIVE` | The bounded samples were insufficient to make a safe decision. |

## Session gate

The immutable session gate has five states:

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

An interrupted service, setup failure, cancellation, or cleanup failure cannot silently leave the strategy approved. Generation checks are performed before and during every phase, write, echo validation, and relay-stat wait.

A late or duplicated `ACTION_STOP` after a completed evaluation is explicitly idempotent. Once resources are closed and the gate is `LAB_APPROVED`, a stale stop command does not manufacture a failure or move the session into cooldown. Cooldown on stop is applied only while resources are still active or the gate is still `EVALUATING`.

## Android boundaries

The dedicated `ConnectXStrategyEvaluationService`:

- is started only after an explicit user action and Android `VpnService` consent;
- uses only route `192.0.2.0/24`;
- targets only `192.0.2.1:18444`;
- rewrites that exact target to a server bound to `127.0.0.1`;
- protects relay sockets from re-entering the VPN;
- uses random in-memory SOCKS5 credentials;
- closes client socket, native stack, TUN, relay, and endpoint in generation-safe order;
- treats a duplicated post-teardown stop as an idempotent no-op for gate policy;
- does not install `0.0.0.0/0` or `::/0`;
- does not read or modify ordinary application traffic.

The Android instrumentation gate verifies the full A/B/A path, confirms native teardown, sends a late stop after success, immediately starts a new explicit evaluation to `STATUS_STARTED`, and then verifies active-stop cleanup. This regression sequence prevents an already approved result from being poisoned by delayed service commands.

## Release activation

The release workflow is committed with the implementation, but its `.publish` marker is intentionally not. After the implementation PR is merged and the workflow exists in `main`, a separate minimal marker PR activates the exact-commit release guard. This avoids relying on the same push both registering and triggering a newly introduced workflow.

## What this does not prove

A successful local evaluation does not prove:

- that an ISP or firewall can be bypassed;
- that write boundaries survive as packet boundaries;
- compatibility with Telegram, YouTube, Discord, or any external service;
- compatibility with TLS implementations other than the built-in synthetic record;
- safety or effectiveness on a real restricted network;
- production readiness.

Real strategy claims require separate, consented device testing on representative restricted networks with reproducible diagnostics and rollback evidence.
