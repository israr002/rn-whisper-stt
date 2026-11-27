#!/usr/bin/env bash
# Builds cpp/whisper.cpp into ios/Frameworks/whisper.xcframework (static, device + simulator).
# Based on whisper.cpp's own build-xcframework.sh, limited to iOS.
#
# Usage: scripts/build-ios-whisper.sh [--force]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/cpp/whisper.cpp"
OUT="$ROOT/ios/Frameworks/whisper.xcframework"
BUILD="$ROOT/ios/build-whisper"
IOS_MIN=15.1 # React Native 0.78's minimum

if [[ "$(uname)" != "Darwin" ]]; then
  echo "Skipping whisper.xcframework: requires macOS" >&2
  exit 0
fi
if [[ -d "$OUT" && "${1:-}" != "--force" ]]; then
  echo "whisper.xcframework already built (use --force to rebuild)"
  exit 0
fi
if [[ ! -f "$SRC/CMakeLists.txt" ]]; then
  echo "whisper.cpp not found at $SRC. Run: git submodule update --init" >&2
  exit 1
fi
# cmake from PATH, else the newest one bundled with the Android SDK (React Native devs usually have it)
CMAKE=$(command -v cmake || true)
if [[ -z "$CMAKE" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  CMAKE=$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)
fi
[[ -x "$CMAKE" ]] || { echo "cmake not found (brew install cmake)" >&2; exit 1; }

COMMON_ARGS=(
  -G Xcode
  -DCMAKE_SYSTEM_NAME=iOS
  -DCMAKE_OSX_DEPLOYMENT_TARGET=$IOS_MIN
  -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
  -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO
  -DBUILD_SHARED_LIBS=OFF
  -DWHISPER_BUILD_EXAMPLES=OFF
  -DWHISPER_BUILD_TESTS=OFF
  -DWHISPER_BUILD_SERVER=OFF
  -DGGML_NATIVE=OFF
  -DGGML_OPENMP=OFF
  -DGGML_METAL=ON
  -DGGML_METAL_EMBED_LIBRARY=ON # Metal shaders compiled into the binary, no .metallib to ship
  -DGGML_METAL_USE_BF16=OFF
  # No Accelerate/BLAS: its cblas_sgemm needs iOS 16.4+, but RN apps target 15.1. Metal runs the
  # model on the GPU; ggml's own NEON code is the CPU fallback.
  -DGGML_BLAS=OFF
  -DCMAKE_C_FLAGS="-Wno-shorten-64-to-32 -Wno-unused-command-line-argument"
  -DCMAKE_CXX_FLAGS="-Wno-shorten-64-to-32 -Wno-unused-command-line-argument"
)

build_slice() { # name sysroot archs
  local name=$1 sysroot=$2 archs=$3
  echo "==> Building whisper.cpp for $name ($archs)"
  "$CMAKE" -S "$SRC" -B "$BUILD/$name" "${COMMON_ARGS[@]}" \
    -DCMAKE_OSX_SYSROOT=$sysroot \
    -DCMAKE_OSX_ARCHITECTURES="$archs" \
    -DCMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS=$sysroot >/dev/null
  "$CMAKE" --build "$BUILD/$name" --config Release -- -quiet

  # One static library per slice: whisper + every ggml backend it links
  local libs
  # Skip Xcode's per-arch intermediates (Objects-normal/<arch>/Binary/*.a): a multi-arch build
  # leaves one per arch next to the final universal library, and merging them doubled the slice
  libs=$(find "$BUILD/$name" -path "*Release-$sysroot*" -name '*.a' -not -path '*/Objects-normal/*' | sort)
  echo "    merging: $(for l in $libs; do basename "$l"; done | tr '\n' ' ')"
  mkdir -p "$BUILD/$name/combined"
  libtool -static -o "$BUILD/$name/combined/libwhisper.a" $libs 2>/dev/null
}

rm -rf "$BUILD" "$OUT"
build_slice ios-device iphoneos arm64
build_slice ios-sim iphonesimulator "arm64;x86_64"

# Public headers: whisper.h and the ggml headers it includes
HEADERS="$BUILD/headers"
mkdir -p "$HEADERS"
cp "$SRC/include/whisper.h" "$SRC"/ggml/include/*.h "$HEADERS/"

mkdir -p "$(dirname "$OUT")"
xcodebuild -create-xcframework \
  -library "$BUILD/ios-device/combined/libwhisper.a" -headers "$HEADERS" \
  -library "$BUILD/ios-sim/combined/libwhisper.a" -headers "$HEADERS" \
  -output "$OUT" >/dev/null

[[ -n "${KEEP_BUILD:-}" ]] || rm -rf "$BUILD"
echo "==> Built $OUT"
