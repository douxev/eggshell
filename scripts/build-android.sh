#!/usr/bin/env bash
# Phase 0 — build chain facade.
#
# This is what Android Studio's preBuild task does under the hood, exposed as a
# script so you can iterate on the Rust side without launching Gradle.
#
# Usage:
#   scripts/build-android.sh                    # builds for the default ABI set
#   scripts/build-android.sh debug              # debug profile
#   ANDROID_NDK_HOME=… scripts/build-android.sh # required if env not set globally

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT_DIR/core"
ANDROID_DIR="$ROOT_DIR/android"
JNI_LIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs"
UNIFFI_OUT_DIR="$ANDROID_DIR/app/build/generated/uniffi/kotlin"

PROFILE="${1:-release}"
PROFILE_FLAG=""
TARGET_PROFILE_DIR="release"
if [[ "$PROFILE" == "debug" ]]; then
    PROFILE_FLAG=""
    TARGET_PROFILE_DIR="debug"
else
    PROFILE_FLAG="--release"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "warning: ANDROID_NDK_HOME is not set. cargo-ndk will look for the NDK on its own; if it errors, install the NDK via Android Studio and export ANDROID_NDK_HOME."
fi

echo "[1/2] cargo ndk → cdylib for arm64-v8a, armeabi-v7a, x86_64"
cd "$CORE_DIR"
cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -o "$JNI_LIBS_DIR" \
    build $PROFILE_FLAG -p transition-uniffi

echo "[2/2] uniffi-bindgen → Kotlin bindings"
# Host build of the cdylib is needed because bindgen reads metadata from it.
cargo build -p transition-uniffi
mkdir -p "$UNIFFI_OUT_DIR"
cargo run -p transition-uniffi \
    --features cli-bindgen \
    --bin uniffi-bindgen -- \
    generate \
    --library "target/debug/libtransition_uniffi.so" \
    --language kotlin \
    --out-dir "$UNIFFI_OUT_DIR"

echo "✓ Native libs at $JNI_LIBS_DIR"
echo "✓ Kotlin bindings at $UNIFFI_OUT_DIR"
