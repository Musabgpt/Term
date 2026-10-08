#!/usr/bin/env bash
set -euo pipefail
ROOT="$(pwd)"
ASSET="$ROOT/app/src/main/assets"
NATIVE="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$ASSET" "$NATIVE"
python3 "$ROOT/scripts/fetch-linux.py" "$ASSET" "$NATIVE"
test -s "$ASSET/alpine-rootfs.tgz"
test -s "$NATIVE/libproot.so"
test -s "$NATIVE/libproot-loader.so"
echo "Linux runtime files ready"
