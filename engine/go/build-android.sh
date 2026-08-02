#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="$SCRIPT_DIR/upstream.lock"
OUTPUT_ROOT="$SCRIPT_DIR/build/android"

read_lock() {
  local key="$1"
  sed -n "s/^${key}=//p" "$LOCK_FILE" | head -n 1
}

UPSTREAM_REPOSITORY="$(read_lock repository)"
UPSTREAM_VERSION="$(read_lock version)"
UPSTREAM_COMMIT="$(read_lock commit)"
GO_VERSION="$(read_lock go)"
ANDROID_API="$(read_lock android_api)"
LOCKED_NDK_VERSION="$(read_lock ndk)"

if [[ -z "$UPSTREAM_REPOSITORY" || -z "$UPSTREAM_VERSION" || -z "$UPSTREAM_COMMIT" || \
      -z "$GO_VERSION" || -z "$ANDROID_API" || -z "$LOCKED_NDK_VERSION" ]]; then
  echo "Invalid upstream.lock" >&2
  exit 1
fi

NDK_ROOT="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "Android NDK path is required" >&2
  exit 1
fi

NDK_PROPERTIES="$NDK_ROOT/source.properties"
if [[ ! -f "$NDK_PROPERTIES" ]]; then
  echo "Android NDK source.properties is missing: $NDK_PROPERTIES" >&2
  exit 1
fi
INSTALLED_NDK_VERSION="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_PROPERTIES" | head -n 1)"
if [[ "$INSTALLED_NDK_VERSION" != "$LOCKED_NDK_VERSION" ]]; then
  echo "Unexpected Android NDK: expected $LOCKED_NDK_VERSION, got $INSTALLED_NDK_VERSION" >&2
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
ACTUAL_GO_VERSION="$(go env GOVERSION)"
if [[ "$ACTUAL_GO_VERSION" != "go${GO_VERSION}" ]]; then
  echo "Unexpected Go toolchain: expected go${GO_VERSION}, got $ACTUAL_GO_VERSION" >&2
  exit 1
fi

# Verify that the locked release tag still resolves to the audited upstream SHA.
TAG_COMMIT="$(git ls-remote "$UPSTREAM_REPOSITORY" "refs/tags/${UPSTREAM_VERSION}^{}" | awk 'NR == 1 {print $1}')"
if [[ -z "$TAG_COMMIT" ]]; then
  TAG_COMMIT="$(git ls-remote "$UPSTREAM_REPOSITORY" "refs/tags/${UPSTREAM_VERSION}" | awk 'NR == 1 {print $1}')"
fi
if [[ -z "$TAG_COMMIT" ]]; then
  echo "Unable to resolve upstream tag ${UPSTREAM_VERSION}" >&2
  exit 1
fi
if [[ "$TAG_COMMIT" != "$UPSTREAM_COMMIT" ]]; then
  echo "Upstream tag mismatch: expected $UPSTREAM_COMMIT, got $TAG_COMMIT" >&2
  exit 1
fi

# Download only the versions committed in go.mod, verify module checksums, and
# refuse any accidental version drift before compiling native code.
go mod download
go mod verify
RESOLVED_VERSION="$(go list -m -f '{{.Version}}' github.com/xjasonlyu/tun2socks/v2)"
if [[ "$RESOLVED_VERSION" != "$UPSTREAM_VERSION" ]]; then
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

HEADER_SOURCE="$OUTPUT_ROOT/jniLibs/arm64-v8a/libconnectxbridge.h"
if [[ -f "$HEADER_SOURCE" ]]; then
  cp "$HEADER_SOURCE" "$OUTPUT_ROOT/include/libconnectxbridge.h"
fi
find "$OUTPUT_ROOT/jniLibs" -name '*.h' -delete

cp go.mod "$OUTPUT_ROOT/metadata/go.mod"
if [[ -f go.sum ]]; then
  cp go.sum "$OUTPUT_ROOT/metadata/go.sum"
fi

cat > "$OUTPUT_ROOT/metadata/build.txt" <<META
upstream_repository=$UPSTREAM_REPOSITORY
upstream_version=$UPSTREAM_VERSION
upstream_commit=$UPSTREAM_COMMIT
resolved_tag_commit=$TAG_COMMIT
resolved_version=$RESOLVED_VERSION
go_version=$ACTUAL_GO_VERSION
android_api=$ANDROID_API
ndk_version=$INSTALLED_NDK_VERSION
abis=arm64-v8a,x86_64
META

CHECKSUM_FILE="$OUTPUT_ROOT/metadata/SHA256SUMS.txt"
(
  cd "$OUTPUT_ROOT"
  find . -type f ! -path './metadata/SHA256SUMS.txt' -print0 \
    | sort -z \
    | xargs -0 sha256sum
) > "$CHECKSUM_FILE"

echo "Built ConnectX native bridge for arm64-v8a and x86_64"
