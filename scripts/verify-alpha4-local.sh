#!/usr/bin/env bash
set -Eeuo pipefail

readonly EXPECTED_GO_VERSION="1.26.3"
readonly EXPECTED_GRADLE_VERSION="8.13"
readonly EXPECTED_NDK_VERSION="28.0.13004108"
readonly EXPECTED_ANDROID_SDK="35"
readonly EXPECTED_ANDROID_ABI="x86_64"
readonly APP_PACKAGE="dev.connectx"
readonly TEST_PACKAGE="dev.connectx.test"

RUN_DEVICE_GATES=0
CLEAN_FIRST=0

usage() {
  cat <<'EOF'
Usage: scripts/verify-alpha4-local.sh [--device] [--clean]

Runs the deterministic build checks for the v0.3.0-alpha.4 candidate.

  --device  Also run isolated Android instrumentation gates on one already-
            running Android 35 x86_64 emulator/device.
  --clean   Run Gradle clean and remove previous native output first.
  --help    Show this help.

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK root.
  GRADLE_BIN                       Gradle executable; defaults to ./gradlew
                                   when present, otherwise gradle.
  ADB_SERIAL                       Device serial when multiple devices exist.
  CONNECTX_VERIFY_OUT              Output directory. Defaults to
                                   build/local-verification-alpha4.
  CONNECTX_ALLOW_DEVICE_VARIANT=1  Permit a non-Android-35 or non-x86_64
                                   device for supplementary testing only.
EOF
}

while (($#)); do
  case "$1" in
    --device) RUN_DEVICE_GATES=1 ;;
    --clean) CLEAN_FIRST=1 ;;
    --help|-h) usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

log() { printf '\n==> %s\n' "$*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"; }

ANDROID_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$ANDROID_ROOT" ]] || fail "Set ANDROID_SDK_ROOT or ANDROID_HOME"
ANDROID_ROOT="$(cd -- "$ANDROID_ROOT" && pwd)"
NDK_ROOT="$ANDROID_ROOT/ndk/$EXPECTED_NDK_VERSION"
[[ -d "$NDK_ROOT" ]] || fail "Locked NDK is missing: $NDK_ROOT"

if [[ -n "${GRADLE_BIN:-}" ]]; then
  GRADLE=("$GRADLE_BIN")
elif [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
else
  GRADLE=(gradle)
fi

require_command git
require_command java
require_command go
require_command "${GRADLE[0]}"
require_command unzip
require_command grep
require_command sha256sum
require_command file

JAVA_LINE="$(java -version 2>&1 | head -n 1)"
grep -Eq '"17([."]|$)' <<<"$JAVA_LINE" ||
  fail "JDK 17 is required; found: $JAVA_LINE"

go version | grep -Fq "go$EXPECTED_GO_VERSION" ||
  fail "Go $EXPECTED_GO_VERSION is required; found: $(go version)"

GRADLE_VERSION="$("${GRADLE[@]}" --version | awk '/^Gradle / { print $2; exit }')"
[[ "$GRADLE_VERSION" == "$EXPECTED_GRADLE_VERSION" ]] ||
  fail "Gradle $EXPECTED_GRADLE_VERSION is required; found: ${GRADLE_VERSION:-unknown}"

COMMIT_SHA="$(git rev-parse HEAD)"
OUT_DIR="${CONNECTX_VERIFY_OUT:-$ROOT_DIR/build/local-verification-alpha4}"
RESULTS_DIR="$OUT_DIR/runtime-test-results"
mkdir -p "$OUT_DIR"

log "Verification target $COMMIT_SHA"
echo "$COMMIT_SHA" > "$OUT_DIR/COMMIT_SHA.txt"

grep -q 'versionCode = 11' app/build.gradle.kts
grep -q 'versionName = "0.3.0-alpha.4"' app/build.gradle.kts
test -s docs/architecture/external-tls-evidence.md
test -s docs/releases/v0.3.0-alpha.4.md
test -s docs/roadmap/alpha4-scope.md
grep -Fq '## [0.3.0-alpha.4]' CHANGELOG.md

if ((CLEAN_FIRST)); then
  log "Cleaning previous build outputs"
  "${GRADLE[@]}" --no-daemon clean
  rm -rf engine/go/build/android
fi

log "Verifying committed Go dependency lock"
(
  cd engine/go
  go mod download
  go mod verify
  go test -mod=readonly ./bridge
  git diff --exit-code -- go.mod go.sum
)

log "Building locked Android native bridge"
chmod +x engine/go/build-android.sh
engine/go/build-android.sh "$NDK_ROOT"
test -s engine/go/build/android/jniLibs/arm64-v8a/libconnectxbridge.so
test -s engine/go/build/android/jniLibs/x86_64/libconnectxbridge.so
file engine/go/build/android/jniLibs/*/libconnectxbridge.so |
  tee "$OUT_DIR/native-files.txt"

log "Running JVM and Android unit tests"
"${GRADLE[@]}" --no-daemon test

log "Running Android lint"
"${GRADLE[@]}" --no-daemon lintDebug

log "Assembling debug APK"
"${GRADLE[@]}" --no-daemon :app:assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK_PATH"

log "Verifying native and legal APK payloads"
unzip -l "$APK_PATH" | tee "$OUT_DIR/apk-contents.txt"
grep -q 'lib/arm64-v8a/libconnectxbridge.so' "$OUT_DIR/apk-contents.txt"
grep -q 'lib/x86_64/libconnectxbridge.so' "$OUT_DIR/apk-contents.txt"
grep -q 'assets/THIRD_PARTY_NOTICES.md' "$OUT_DIR/apk-contents.txt"
grep -q 'assets/tun2socks-MIT.txt' "$OUT_DIR/apk-contents.txt"
grep -q 'assets/gvisor-LICENSE.txt' "$OUT_DIR/apk-contents.txt"

mkdir -p "$OUT_DIR/apk" "$OUT_DIR/native"
cp "$APK_PATH" "$OUT_DIR/apk/ConnectX-v0.3.0-alpha.4-debug.apk"
rm -rf "$OUT_DIR/native/jniLibs"
cp -R engine/go/build/android/jniLibs "$OUT_DIR/native/jniLibs"
(
  cd "$OUT_DIR"
  sha256sum \
    apk/ConnectX-v0.3.0-alpha.4-debug.apk \
    native/jniLibs/arm64-v8a/libconnectxbridge.so \
    native/jniLibs/x86_64/libconnectxbridge.so \
    > CHECKSUMS.txt
)

if ((RUN_DEVICE_GATES)); then
  if [[ -x "$ANDROID_ROOT/platform-tools/adb" ]]; then
    ADB=("$ANDROID_ROOT/platform-tools/adb")
  else
    require_command adb
    ADB=(adb)
  fi
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    export ANDROID_SERIAL="$ADB_SERIAL"
    ADB+=( -s "$ADB_SERIAL" )
  fi

  log "Validating connected Android runtime"
  "${ADB[@]}" start-server
  mapfile -t DEVICES < <("${ADB[@]}" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ -z "${ADB_SERIAL:-}" && ${#DEVICES[@]} -ne 1 ]]; then
    fail "Expected exactly one ready device; set ADB_SERIAL when multiple devices are connected"
  fi
  "${ADB[@]}" wait-for-device

  DEVICE_SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "sdk=$DEVICE_SDK" | tee "$OUT_DIR/device.txt"
  echo "abi=$DEVICE_ABI" | tee -a "$OUT_DIR/device.txt"

  if [[ "${CONNECTX_ALLOW_DEVICE_VARIANT:-0}" != "1" ]]; then
    [[ "$DEVICE_SDK" == "$EXPECTED_ANDROID_SDK" ]] ||
      fail "Official reproduction requires Android $EXPECTED_ANDROID_SDK; found $DEVICE_SDK"
    [[ "$DEVICE_ABI" == "$EXPECTED_ANDROID_ABI" ]] ||
      fail "Official reproduction requires $EXPECTED_ANDROID_ABI; found $DEVICE_ABI"
  else
    echo "WARNING: device variant override is enabled; supplementary evidence only." |
      tee -a "$OUT_DIR/device.txt"
  fi

  mkdir -p "$RESULTS_DIR"

  archive_results() {
    local name="$1"
    local target="$RESULTS_DIR/$name"
    mkdir -p "$target"
    cp -R app/build/reports/androidTests "$target/reports" 2>/dev/null || true
    cp -R app/build/outputs/androidTest-results "$target/results" 2>/dev/null || true
  }

  reset_vpn_process() {
    "${ADB[@]}" shell am force-stop "$APP_PACKAGE" || true
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" || true
    "${ADB[@]}" shell appops set "$APP_PACKAGE" ACTIVATE_VPN default || true

    local deadline=$((SECONDS + 10))
    while ((SECONDS < deadline)); do
      if [[ -z "$("${ADB[@]}" shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
        break
      fi
      sleep 1
    done
    sleep 3
  }

  run_app_gate() {
    local name="$1"
    local class_name="$2"
    log "Running Android gate: $name"
    set +e
    "${GRADLE[@]}" --no-daemon \
      :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$class_name"
    local status=$?
    set -e
    archive_results "$name"
    reset_vpn_process
    ((status == 0)) || return "$status"
  }

  run_app_gate strategy-foundation dev.connectx.app.StrategyFoundationInstrumentedTest
  run_app_gate strategy-tun dev.connectx.app.NativeTlsSplitProbeInstrumentedTest
  run_app_gate strategy-evaluation dev.connectx.app.StrategyEvaluationInstrumentedTest
  run_app_gate external-tls-evidence dev.connectx.app.ExternalTlsEvidenceInstrumentedTest

  log "Running packaged JNI lifecycle gate"
  "${GRADLE[@]}" --no-daemon :vpn:nativebridge:connectedDebugAndroidTest
  reset_vpn_process

  run_app_gate tcp dev.connectx.app.NativeTcpProbeInstrumentedTest
  run_app_gate udp dev.connectx.app.NativeUdpProbeInstrumentedTest
  run_app_gate dns dev.connectx.app.NativeDnsProbeInstrumentedTest

  "${ADB[@]}" logcat -d > "$OUT_DIR/logcat.txt" 2>&1 || true
fi

cat > "$OUT_DIR/RESULT.txt" <<EOF
ConnectX v0.3.0-alpha.4 local verification passed.
Commit: $COMMIT_SHA
Static build gates: passed
Android runtime gates: $([[ $RUN_DEVICE_GATES -eq 1 ]] && echo passed || echo not-run)

This result is local evidence only. It does not replace the required exact-commit
GitHub Actions and Android runtime gate before merging or publishing the release.
EOF

log "Verification passed"
cat "$OUT_DIR/RESULT.txt"
echo "Artifacts: $OUT_DIR"
