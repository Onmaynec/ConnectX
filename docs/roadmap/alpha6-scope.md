# v0.3.0-alpha.6 scope — Evidence Quality Gate

## Goal

Turn raw repeated A/B/A counters into a deterministic, privacy-bounded evidence artifact suitable for manual restricted-network review.

## In scope

- Pure Kotlin classification of complete three-sample BASELINE/STRATEGY/RECOVERY phases.
- Explicit confidence and safe recommendation enums.
- Redacted report schema v2.
- Deterministic report fingerprint based only on aggregate non-secret fields.
- Structural allow-list validation before a report is shared.
- Compose presentation of evidence quality and next action.
- Full version, changelog, release notes and guarded prerelease metadata synchronization.

## Out of scope

- Automatic strategy enablement.
- Persistent per-network strategy selection.
- Default IPv4/IPv6 routes.
- Real application traffic.
- HTTP diagnostics.
- Multiple strategy registry.
- Root, QUIC or IPv6 work.
- Claiming success without physical-device restricted-network evidence.

## Definition of done

- Assessment and report validator unit tests are green.
- Existing Android runtime gates remain green.
- Report ID is deterministic and privacy-safe.
- Unsafe report mutations are rejected.
- `v0.3.0-alpha.6` is published from the exact successful `main` CI commit.
