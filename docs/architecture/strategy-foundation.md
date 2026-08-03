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

The bounded inspector validates only enough structure to decide whether a payload is one complete TLS ClientHello record suitable for the lab planner.

It does not:

- decrypt TLS;
- parse certificates;
- inspect application data;
- extract SNI;
- rewrite semantic TLS fields;
- accept record chaining or trailing bytes.

## Execution boundary

The Android lab executor performs two ordered `SocketOutputStream.write()` calls for an exact synthetic ClientHello and exact TEST-NET destination.

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
