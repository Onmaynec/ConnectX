#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC_BIN:-kotlinc}"
JAVA_BIN="${JAVA_BIN:-java}"

command -v "$KOTLINC_BIN" >/dev/null 2>&1 || {
  echo "error: kotlinc is required (override with KOTLINC_BIN)" >&2
  exit 1
}
command -v "$JAVA_BIN" >/dev/null 2>&1 || {
  echo "error: java is required (override with JAVA_BIN)" >&2
  exit 1
}

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/connectx-strategy-core.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

SMOKE_FILE="$TMP_DIR/StrategyCoreSmoke.kt"
OUTPUT_JAR="$TMP_DIR/strategy-core-smoke.jar"

cat > "$SMOKE_FILE" <<'KOTLIN'
package dev.connectx.strategy.api

private val context = StrategyContext(
    transport = TransportProtocol.TCP,
    network = NetworkProtocol.IPV4,
    application = ApplicationProtocol.TLS,
    scope = StrategyScope.LAB_ONLY,
)

private val enabledGate = StrategyFeatureGate(
    globallyEnabled = true,
    enabledStrategies = setOf(TlsClientHelloSplitStrategy.ID),
)

fun main() {
    val strategy = TlsClientHelloSplitStrategy()
    val payload = LabTlsClientHello.create(
        ByteArray(LabTlsClientHello.RANDOM_BYTES) { index -> (index + 1).toByte() },
    )
    check(payload.size == LabTlsClientHello.PAYLOAD_BYTES)

    val plan = strategy.plan(payload, context, enabledGate)
    check(plan is StrategyPlan.Segmented) { "complete ClientHello was refused: $plan" }
    check(plan.splitOffset == LabTlsClientHello.SPLIT_OFFSET)
    check(plan.segments.map(ByteArray::size) == listOf(43, 7))
    check(plan.reconstruct().contentEquals(payload))

    val oldPrefixOnly = legacyPrefixOnlyClientHello()
    val oldPlan = strategy.plan(oldPrefixOnly, context, enabledGate)
    check(oldPlan == StrategyPlan.Refused(StrategyRefusalReason.MALFORMED_LENGTH)) {
        "legacy prefix-only fixture was not rejected: $oldPlan"
    }

    val tls11 = payload.copyOf().apply { this[10] = 0x02 }
    check(strategy.plan(tls11, context, enabledGate) is StrategyPlan.Segmented) {
        "TLS 1.1 legacy version should remain structurally accepted"
    }

    val invalidLegacy = payload.copyOf().apply { this[10] = 0x04 }
    val invalidPlan = strategy.plan(invalidLegacy, context, enabledGate)
    check(invalidPlan == StrategyPlan.Refused(StrategyRefusalReason.NOT_CLIENT_HELLO)) {
        "0x0304 legacy version was not rejected: $invalidPlan"
    }

    println(
        "STRATEGY_CORE_OK bytes=${payload.size} " +
            "split=${plan.splitOffset} segments=${plan.segments.map(ByteArray::size)} " +
            "old=${(oldPlan as StrategyPlan.Refused).reason} " +
            "invalidLegacy=${(invalidPlan as StrategyPlan.Refused).reason}",
    )
}

private fun legacyPrefixOnlyClientHello(): ByteArray {
    val body = ByteArray(35)
    body[0] = 0x03
    body[1] = 0x03
    body[34] = 0

    val handshake = ByteArray(4 + body.size)
    handshake[0] = 0x01
    handshake[3] = body.size.toByte()
    body.copyInto(handshake, destinationOffset = 4)

    return ByteArray(5 + handshake.size).also { record ->
        record[0] = 0x16
        record[1] = 0x03
        record[2] = 0x03
        record[4] = handshake.size.toByte()
        handshake.copyInto(record, destinationOffset = 5)
    }
}
KOTLIN

SOURCE_ROOT="$ROOT_DIR/strategy/api/src/main/kotlin/dev/connectx/strategy/api"
SOURCES=(
  "$SOURCE_ROOT/StrategyModel.kt"
  "$SOURCE_ROOT/LabTlsClientHello.kt"
  "$SOURCE_ROOT/TlsClientHelloInspector.kt"
  "$SOURCE_ROOT/TlsClientHelloSplitStrategy.kt"
)

for source in "${SOURCES[@]}"; do
  [[ -f "$source" ]] || {
    echo "error: missing strategy source: $source" >&2
    exit 1
  }
done

printf 'Using %s\n' "$("$KOTLINC_BIN" -version 2>&1 | head -n 1)"
"$KOTLINC_BIN" "${SOURCES[@]}" "$SMOKE_FILE" \
  -include-runtime \
  -d "$OUTPUT_JAR"
"$JAVA_BIN" -jar "$OUTPUT_JAR"

echo "Strategy core smoke gate passed. This does not replace Gradle or Android runtime gates."
