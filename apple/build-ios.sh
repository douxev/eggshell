#!/usr/bin/env bash
# Build the Rust core into a TransitionCore.xcframework + generate the Swift
# bindings, and drop both into the local SwiftPM package at apple/TransitionCore.
#
# This is the iOS analogue of scripts/build-android.sh. It MUST run on macOS
# with full Xcode (the vendored OpenSSL + SQLCipher C builds call xcrun/clang
# against the iOS SDKs, and `xcodebuild -create-xcframework` is macOS-only).
#
# Usage:
#   apple/build-ios.sh                 # release (what CI ships)
#   apple/build-ios.sh debug           # faster local debug build
#
# Targets built:
#   aarch64-apple-ios       -> physical iPhone/iPad        (device slice)
#   aarch64-apple-ios-sim   -> Apple-Silicon simulator  \  merged via lipo into
#   x86_64-apple-ios        -> Intel-Mac simulator       /  one simulator slice
set -euo pipefail

PROFILE="${1:-release}"
DEPLOY_TARGET="18.0"          # matches the app's iOS deployment target

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT_DIR/core"
PKG_DIR="$ROOT_DIR/apple/TransitionCore"
SWIFT_OUT="$PKG_DIR/Sources/TransitionCore"      # generated transition.swift lands here
XCF_OUT="$PKG_DIR/TransitionCoreFFI.xcframework" # binaryTarget path in Package.swift
BIND_DIR="$ROOT_DIR/apple/.bindings"             # scratch: headers + modulemap
HEADERS_DIR="$ROOT_DIR/apple/.headers"           # what create-xcframework copies in

CRATE="transition-uniffi"
LIB="libtransition_uniffi.a"

if [[ "$PROFILE" == "debug" ]]; then
    CARGO_PROFILE_FLAG=""
    PROFILE_DIR="debug"
else
    CARGO_PROFILE_FLAG="--release"
    PROFILE_DIR="release"
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "error: this script must run on macOS (needs xcrun/clang iOS SDKs + xcodebuild)." >&2
    echo "       On Linux/CI-without-mac it will not work — use a macos-* GitHub runner." >&2
    exit 1
fi

# --- C-side env for the vendored OpenSSL + SQLCipher cross-compile ----------
# Do NOT inject `-target <triple>` via CFLAGS_<triple>: openssl-src already picks
# the right Configure target (ios64-cross / iossimulator-xcrun) from the Rust
# triple, and appending the bare triple makes OpenSSL's Configure treat it as a
# SECOND target → "target already defined - ios64-cross" (perl exit 255). Both
# openssl-src (300.x) and the `cc` crate (SQLCipher) derive the SDK + min-version
# from the triple + IPHONEOS_DEPLOYMENT_TARGET themselves, including the
# simulator-vs-device distinction — so we only set the deployment target.
export IPHONEOS_DEPLOYMENT_TARGET="$DEPLOY_TARGET"
# Never let a system/Homebrew OpenSSL hijack the vendored build.
unset OPENSSL_DIR OPENSSL_LIB_DIR OPENSSL_INCLUDE_DIR || true

cd "$CORE_DIR"

echo "==> [1/6] rustup targets"
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

echo "==> [2/6] cargo build ($PROFILE_DIR) per Apple target"
for T in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
    echo "    - $T"
    cargo build -p "$CRATE" $CARGO_PROFILE_FLAG --target "$T"
done

echo "==> [3/6] lipo simulator arches (arm64 + x86_64) into one fat staticlib"
SIM_DIR="$CORE_DIR/target/ios-sim/$PROFILE_DIR"
mkdir -p "$SIM_DIR"
lipo -create \
    "target/aarch64-apple-ios-sim/$PROFILE_DIR/$LIB" \
    "target/x86_64-apple-ios/$PROFILE_DIR/$LIB" \
    -output "$SIM_DIR/$LIB"

echo "==> [4/6] generate Swift bindings (host artifact, arch-independent)"
# Mirror the Android approach: build a host cdylib and let bindgen introspect it.
cargo build -p "$CRATE"
HOST_LIB="$(ls "$CORE_DIR"/target/debug/libtransition_uniffi.dylib 2>/dev/null || true)"
if [[ -z "$HOST_LIB" ]]; then
    echo "error: host libtransition_uniffi.dylib not found for bindgen." >&2
    exit 1
fi
rm -rf "$BIND_DIR"; mkdir -p "$BIND_DIR"
cargo run -p "$CRATE" --features cli-bindgen --bin uniffi-bindgen -- \
    generate \
    --library "$HOST_LIB" \
    --language swift \
    --out-dir "$BIND_DIR"

# Discover the generated names (UDL namespace-derived, e.g. transition / transitionFFI)
SWIFT_FILE="$(ls "$BIND_DIR"/*.swift)"
HEADER_FILE="$(ls "$BIND_DIR"/*.h)"
MODULEMAP_FILE="$(ls "$BIND_DIR"/*.modulemap)"
echo "    swift wrapper : $(basename "$SWIFT_FILE")"
echo "    ffi header    : $(basename "$HEADER_FILE")"
echo "    modulemap     : $(basename "$MODULEMAP_FILE")  ->  module.modulemap"

# Clang/XCFrameworks only look for a file literally named module.modulemap.
rm -rf "$HEADERS_DIR"; mkdir -p "$HEADERS_DIR"
cp "$HEADER_FILE" "$HEADERS_DIR/"
cp "$MODULEMAP_FILE" "$HEADERS_DIR/module.modulemap"

echo "==> [5/6] create-xcframework (device + simulator)"
rm -rf "$XCF_OUT"
xcodebuild -create-xcframework \
    -library "$CORE_DIR/target/aarch64-apple-ios/$PROFILE_DIR/$LIB" -headers "$HEADERS_DIR" \
    -library "$SIM_DIR/$LIB"                                        -headers "$HEADERS_DIR" \
    -output "$XCF_OUT"

echo "==> [6/6] copy Swift wrapper into the SwiftPM target"
mkdir -p "$SWIFT_OUT"
# Keep only the generated wrapper + our hand-written re-exports/helpers.
rm -f "$SWIFT_OUT"/transition*.swift
cp "$SWIFT_FILE" "$SWIFT_OUT/"

rm -rf "$BIND_DIR" "$HEADERS_DIR"
echo "✓ XCFramework : $XCF_OUT"
echo "✓ Swift glue  : $SWIFT_OUT/$(basename "$SWIFT_FILE")"
