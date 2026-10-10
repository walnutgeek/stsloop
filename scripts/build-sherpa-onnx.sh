#!/usr/bin/env bash
# Build sherpa-onnx's Android native libraries from a pinned commit and vendor
# them, plus the Kotlin API sources, into :audio.
#
#   scripts/build-sherpa-onnx.sh
#
# Outputs (the .so files are git-ignored; the .kt files are committed):
#   audio/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
#   audio/src/main/jniLibs/arm64-v8a/libonnxruntime.so
#   audio/src/main/kotlin/com/k2fsa/sherpa/onnx/*.kt
#
# Needs the NDK and CMake from scripts/setup-android.sh, plus git, curl,
# unzip and make. The sherpa-onnx checkout and build tree live in
# $STSLOOP_CACHE (default ~/.cache/stsloop), outside the repo. Idempotent:
# a finished build is reused unless the pins or build flags change.
set -euo pipefail

# --- Pins. Change these together, then re-run and commit the .kt diff. ------
SHERPA_ONNX_TAG="v1.13.8"
SHERPA_ONNX_COMMIT="11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"
# Prebuilt onnxruntime that upstream's build-android-arm64-v8a.sh downloads
# for this tag. The checksum was recorded on first download (trust on first
# use); upstream publishes none.
ONNXRUNTIME_VERSION="1.28.2"
ONNXRUNTIME_SHA256="01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d"
NDK_VERSION="29.0.14206865" # must match scripts/setup-android.sh
CMAKE_VERSION="3.31.6"      # must match scripts/setup-android.sh
ABI="arm64-v8a"
# The single upstream source of the Kotlin API. The example apps under
# android/ symlink to this directory, so it is the canonical copy.
KOTLIN_API_DIR="sherpa-onnx/kotlin-api"
KOTLIN_API_FILES=(
  FeatureConfig.kt
  HomophoneReplacerConfig.kt
  OnlineRecognizer.kt
  OnlineStream.kt
  QnnConfig.kt
  Vad.kt
  VersionInfo.kt
  WaveReader.kt
)
# ---------------------------------------------------------------------------

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CACHE="${STSLOOP_CACHE:-$HOME/.cache/stsloop}"
SRC="$CACHE/sherpa-onnx-$SHERPA_ONNX_TAG"
JNI_OUT="$REPO_ROOT/audio/src/main/jniLibs/$ABI"
KT_OUT="$REPO_ROOT/audio/src/main/kotlin/com/k2fsa/sherpa/onnx"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CMAKE_BIN="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"
[[ -d "$NDK" ]] || die "NDK $NDK_VERSION not found; run scripts/setup-android.sh"
[[ -x "$CMAKE_BIN/cmake" ]] || die "CMake $CMAKE_VERSION not found; run scripts/setup-android.sh"
for tool in git curl unzip make sha256sum; do
  command -v "$tool" >/dev/null || die "$tool is required"
done

mkdir -p "$CACHE"

if [[ ! -d "$SRC/.git" ]]; then
  say "Cloning sherpa-onnx $SHERPA_ONNX_TAG"
  git clone --quiet --depth 1 --branch "$SHERPA_ONNX_TAG" \
    https://github.com/k2-fsa/sherpa-onnx.git "$SRC"
fi
head="$(git -C "$SRC" rev-parse HEAD)"
[[ "$head" == "$SHERPA_ONNX_COMMIT" ]] ||
  die "$SRC is at $head, expected $SHERPA_ONNX_COMMIT (tag moved?)"

ORT_DIR="$CACHE/onnxruntime-android-$ONNXRUNTIME_VERSION"
if [[ ! -f "$ORT_DIR/jni/$ABI/libonnxruntime.so" ]]; then
  say "Fetching onnxruntime $ONNXRUNTIME_VERSION"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 -o "$tmp/ort.zip" \
    "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ONNXRUNTIME_VERSION/onnxruntime-android-$ONNXRUNTIME_VERSION.zip"
  echo "$ONNXRUNTIME_SHA256  $tmp/ort.zip" | sha256sum -c --quiet -
  rm -rf "$ORT_DIR"
  mkdir -p "$ORT_DIR"
  unzip -q "$tmp/ort.zip" -d "$ORT_DIR"
fi

# Upstream's own script, with only what stsloop uses switched on: no TTS,
# no diarization, no binaries, JNI only.
BUILD_ENV=(
  ANDROID_NDK="$NDK"
  BUILD_SHARED_LIBS=ON
  SHERPA_ONNXRUNTIME_LIB_DIR="$ORT_DIR/jni/$ABI"
  SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_DIR/headers"
  SHERPA_ONNX_ENABLE_TTS=OFF
  SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
  SHERPA_ONNX_ENABLE_BINARY=OFF
  SHERPA_ONNX_ENABLE_C_API=OFF
  SHERPA_ONNX_ENABLE_JNI=ON
)
# The build is reused only if it came from this exact pin and these flags;
# otherwise the build tree (and its CMake cache) is wiped and rebuilt.
BUILD_DIR="$SRC/build-android-$ABI"
INSTALL="$BUILD_DIR/install/lib"
STAMP="$BUILD_DIR/.stsloop-stamp"
stamp="$(printf '%s\n' "$SHERPA_ONNX_COMMIT" "$ONNXRUNTIME_SHA256" "$CMAKE_VERSION" "${BUILD_ENV[@]}")"
if [[ ! -f "$INSTALL/libsherpa-onnx-jni.so" || "$(cat "$STAMP" 2>/dev/null)" != "$stamp" ]]; then
  say "Building sherpa-onnx for $ABI with NDK $NDK_VERSION (takes a while)"
  rm -rf "$BUILD_DIR"
  (cd "$SRC" && env PATH="$CMAKE_BIN:$PATH" "${BUILD_ENV[@]}" ./build-android-arm64-v8a.sh)
  printf '%s\n' "$stamp" >"$STAMP"
fi

say "Vendoring native libraries into ${JNI_OUT#"$REPO_ROOT"/}"
mkdir -p "$JNI_OUT"
install -m 0644 "$INSTALL/libsherpa-onnx-jni.so" "$INSTALL/libonnxruntime.so" "$JNI_OUT/"

say "Vendoring Kotlin API from $KOTLIN_API_DIR into ${KT_OUT#"$REPO_ROOT"/}"
mkdir -p "$KT_OUT"
for f in "${KOTLIN_API_FILES[@]}"; do
  install -m 0644 "$SRC/$KOTLIN_API_DIR/$f" "$KT_OUT/$f"
done

ls -l "$JNI_OUT"
