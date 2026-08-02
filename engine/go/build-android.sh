#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="$SCRIPT_DIR/upstream.lock"
OUTPUT_ROOT="$SCRIPT_DIR/build/android"

read_lock() {
  local key="$1"
  sed -n "s/^${key}=//p" "$LOCK_FILE" | head -n 1
}

UPSTREAM_COMMIT="$(read_lock commit)"
GO_VERSION="$(read_lock go)"
ANDROID_API="$(read_lock android_api)"
LOCKED_NDK_VERSION="$(read_lock ndk)"

if [[ -z "$UPSTREAM_COMMIT" || -z "$GO_VERSION" || -z "$ANDROID_API" ]]; then
  echo "Invalid upstream.lock" >&2
  exit 1
fi

NDK_ROOT="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "Android NDK path is required" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) echo "Unsupported build host" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK LLVM toolchain not found: $TOOLCHAIN" >&2
  exit 1
fi

cd "$SCRIPT_DIR"
export GOTOOLCHAIN="go${GO_VERSION}"

# Resolve exactly the locked commit. The resulting pseudo-version must end in
# the same commit prefix, otherwise the build is rejected.
go get "github.com/xjasonlyu/tun2socks/v2@${UPSTREAM_COMMIT}"
go mod tidy
RESOLVED_VERSION="$(go list -m -f '{{.Version}}' github.com/xjasonlyu/tun2socks/v2)"
if [[ "$RESOLVED_VERSION" != *-"${UPSTREAM_COMMIT:0:12}" ]]; then
  echo "Resolved unexpected tun2socks version: $RESOLVED_VERSION" >&2
  exit 1
fi

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT/jniLibs" "$OUTPUT_ROOT/include" "$OUTPUT_ROOT/metadata"

build_abi() {
  local abi="$1"
  local goarch="$2"
  local compiler_prefix="$3"
  local abi_dir="$OUTPUT_ROOT/jniLibs/$abi"
  local output="$abi_dir/libconnectxbridge.so"

  mkdir -p "$abi_dir"
  CGO_ENABLED=1 \
  GOOS=android \
  GOARCH="$goarch" \
  CC="$TOOLCHAIN/${compiler_prefix}${ANDROID_API}-clang" \
  go build \
    -trimpath \
    -buildmode=c-shared \
    -ldflags="-s -w -X github.com/Onmaynec/ConnectX/engine/go/bridge.upstreamCommit=${UPSTREAM_COMMIT}" \
    -o "$output" \
    ./cmd/connectxbridge

  if [[ ! -s "$output" ]]; then
    echo "Native library was not produced for $abi" >&2
    exit 1
  fi
}

build_abi "arm64-v8a" "arm64" "aarch64-linux-android"
build_abi "x86_64" "amd64" "x86_64-linux-android"

# c-shared emits one C header next to each library. Keep one copy for ABI
# inspection and remove duplicate headers from packaged jniLibs directories.
HEADER_SOURCE="$OUTPUT_ROOT/jniLibs/arm64-v8a/libconnectxbridge.h"
if [[ -f "$HEADER_SOURCE" ]]; then
  cp "$HEADER_SOURCE" "$OUTPUT_ROOT/include/libconnectxbridge.h"
fi
find "$OUTPUT_ROOT/jniLibs" -name '*.h' -delete

cat > "$OUTPUT_ROOT/metadata/build.txt" <<META
upstream_commit=$UPSTREAM_COMMIT
resolved_version=$RESOLVED_VERSION
go_version=$GO_VERSION
android_api=$ANDROID_API
ndk_version=$LOCKED_NDK_VERSION
abis=arm64-v8a,x86_64
META

find "$OUTPUT_ROOT" -type f -print0 | sort -z | xargs -0 sha256sum > "$OUTPUT_ROOT/metadata/SHA256SUMS.txt"
echo "Built ConnectX native bridge for arm64-v8a and x86_64"
