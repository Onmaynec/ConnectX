#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ADB_BIN="${ADB_BIN:-adb}"
GRADLE_BIN="${GRADLE_BIN:-gradle}"
OUTPUT_DIR="${1:-physical-evidence-alpha7}"

command -v "$ADB_BIN" >/dev/null || { echo "adb not found" >&2; exit 2; }
command -v "$GRADLE_BIN" >/dev/null || { echo "Gradle 8.13 not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
command -v git >/dev/null || { echo "git not found" >&2; exit 2; }

mapfile -t DEVICES < <("$ADB_BIN" devices | awk '$2 == "device" {print $1}')
if [[ "${#DEVICES[@]}" -ne 1 ]]; then
  echo "exactly one authorized Android device is required" >&2
  exit 2
fi
SERIAL="${DEVICES[0]}"
prop() { "$ADB_BIN" -s "$SERIAL" shell getprop "$1" | tr -d '\r'; }

QEMU="$(prop ro.kernel.qemu)"
ABI="$(prop ro.product.cpu.abi)"
API="$(prop ro.build.version.sdk)"
if [[ "$QEMU" == "1" ]]; then
  echo "emulator rejected: a physical arm64 device is required" >&2
  exit 2
fi
if [[ "$ABI" != "arm64-v8a" ]]; then
  echo "device rejected: primary ABI must be arm64-v8a" >&2
  exit 2
fi
if [[ ! "$API" =~ ^[0-9]+$ ]] || (( API < 29 )); then
  echo "device rejected: Android API 29 or newer is required" >&2
  exit 2
fi

NATIVE_LIB="engine/go/build/android/jniLibs/arm64-v8a/libconnectxbridge.so"
if [[ ! -s "$NATIVE_LIB" ]]; then
  echo "native bridge is missing; run engine/go/build-android.sh with NDK 28.0.13004108" >&2
  exit 2
fi

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

"$GRADLE_BIN" --no-daemon :app:assembleDebug
"$GRADLE_BIN" --no-daemon :vpn:nativebridge:connectedDebugAndroidTest
"$GRADLE_BIN" --no-daemon \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.connectx.app.ExternalTlsEvidenceInstrumentedTest

APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
SOURCE_COMMIT="$(git rev-parse HEAD)"
APK_SHA256="$(sha256sum "$APK" | awk '{print $1}')"

cat > "$OUTPUT_DIR/DEVICE_EVIDENCE.txt" <<EOF
ConnectX v0.3.0-alpha.7 — physical device evidence bundle
schema_version=1
source_commit=$SOURCE_COMMIT
apk_sha256=$APK_SHA256
device_class=PHYSICAL
android_api=$API
abi_family=ARM64
native_lifecycle=PASS
external_evidence_loopback=PASS
fd_budget_gate=PASS
restricted_network_manual=REQUIRED
claim=readiness-only-not-restricted-network-proof
EOF

python3 scripts/validate_device_evidence_bundle.py "$OUTPUT_DIR/DEVICE_EVIDENCE.txt"

echo "Readiness bundle created at $OUTPUT_DIR/DEVICE_EVIDENCE.txt"
echo "No serial, model, manufacturer, fingerprint, SSID, hostname or IP was written."
echo "A manual restricted-network schema v3 report is still required for issue #11."
