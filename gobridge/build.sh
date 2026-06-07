#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

GOARCH_MAP=(
  "arm64-v8a:arm64"
  "armeabi-v7a:arm"
  "x86_64:amd64"
  "x86:386"
)

GOOS=android
LIB_NAME="libsnispf.so"

mkdir -p "$OUTPUT_DIR"

for PAIR in "${GOARCH_MAP[@]}"; do
  ABI="${PAIR%%:*}"
  GOARCH="${PAIR##*:}"

  OUT="$OUTPUT_DIR/$ABI"
  mkdir -p "$OUT"

  echo "Building $ABI ($GOARCH)..."

  CC_ARM64="aarch64-linux-android21-clang"
  CC_ARM="armv7a-linux-androideabi21-clang"
  CC_AMD64="x86_64-linux-android21-clang"
  CC_386="i686-linux-android21-clang"

  case "$ABI" in
    arm64-v8a)  CC="$CC_ARM64" ;;
    armeabi-v7a) CC="$CC_ARM" ;;
    x86_64)     CC="$CC_AMD64" ;;
    x86)        CC="$CC_386" ;;
  esac

  CGO_ENABLED=1 \
  GOOS=$GOOS \
  GOARCH=$GOARCH \
  CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/$CC" \
    go build -buildmode=c-shared \
    -o "$OUT/$LIB_NAME" \
    "$SCRIPT_DIR/snispf.go"

  echo "  -> $OUT/$LIB_NAME"
done

echo "Build complete."
