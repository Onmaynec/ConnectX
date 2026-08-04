# Strategy Foundation

## Status

Accepted for `v0.3.0-alpha.1` as a lab-only foundation.

## Decision

DPI-related behaviour is represented by a typed `BypassStrategy` that produces an explicit execution plan. A strategy does not receive Android services, sockets, file descriptors or global routing controls directly.

The first plan type is an ordered list of defensive-copy byte segments. The Android service owns execution, lifecycle and cancellation.

## Capability model

Each strategy declares a descriptor containing:

- stable canonical id;
- supported transports;
- supported network protocols;
- supported application protocols;
- root requirement;
- reversibility.

Capabilities describe where a strategy may be considered. They do not prove effectiveness.

## Scope separation

`StrategyScope.LAB_ONLY` and `StrategyScope.USER_TRAFFIC` are separate values.

The first strategy, `tls-clienthello-split-v1`, contains a hard refusal for every scope except `LAB_ONLY`. This refusal remains active even when a caller enables the general user-traffic feature flag by mistake.

## Feature gates

The global strategy gate is disabled by default. Enabling it requires both:

1. `globallyEnabled = true`;
2. the exact strategy id in `enabledStrategies`.

A root-requiring strategy must additionally receive an explicit root-available context.

## TLS inspection boundary

The bounded inspector accepts one complete, structurally valid TLS ClientHello record suitable for the Lab planner.

The shared `LabTlsClientHello` fixture is exactly 50 bytes and contains:

- TLS 1.2 legacy record and ClientHello versions;
- a 32-byte caller-supplied random;
- an empty session id;
- one two-byte cipher suite;
- one null compression method;
- no extensions, host name, credential or external identifier.

The inspector validates:

- record content type, version and exact length;
- ClientHello handshake type, version and exact length;
- session-id vector bounds and the 32-byte maximum;
- a non-empty, even-length cipher-suite vector;
- a non-empty compression-method vector;
- optional extension-block length and every extension body boundary;
- absence of record chaining and trailing bytes.

The previous 44-byte prefix-only fixture contained only the fixed ClientHello prefix and session-id length. It is now rejected as `MALFORMED_LENGTH`; it is not considered a valid ClientHello.

The inspector does not:

- decrypt TLS;
- parse certificates;
- inspect application data;
- extract SNI;
- rewrite semantic TLS fields;
- accept record chaining or trailing bytes.

## Execution boundary

The Android lab executor performs two ordered `SocketOutputStream.write()` calls for the exact shared synthetic ClientHello and exact TEST-NET destination.

The deterministic split offset is `43`, producing segments of 43 and 7 bytes. Both the one-shot Lab probe and the A/B/A evaluator use the same builder rather than maintaining private byte layouts.

This proves:

- the planner returned two non-empty segments;
- reconstruction is byte-identical;
- the stream traversed the Android TUN and native stack;
- the local echo endpoint received the complete byte sequence;
- lifecycle teardown completed.

It does **not** prove:

- two TCP segments;
- two IP packets;
- a stable boundary visible to a DPI appliance;
- effectiveness on a restricted network.

Packet-level evidence requires a later controlled observation mechanism and physical-network validation.

## Safety boundary

- route limited to `192.0.2.0/24`;
- exact strategy destination `192.0.2.1:18443`;
- relay rewrite only to a loopback endpoint;
- no default routes;
- no external target;
- no MITM or certificate installation;
- no payload logging;
- no user-traffic activation.
