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

# Install real ARM64 developer packages in CI, so they work even if apk
# networking in Android/PRoot fails. Alpine signs repository indexes and
# packages; signature validation remains enabled. Do not use --allow-untrusted.
STAGING="$RUNNER_TEMP/arabic-terminal-alpine"
mkdir -p "$STAGING"
tar -xzf "$ASSET/alpine-rootfs.tgz" -C "$STAGING"
docker run --rm --network host \
  -v "$STAGING:/opt/guest" alpine:3.24 sh -ec '
    apk --root /opt/guest --arch aarch64 --scripts=false \
        --commit-hooks=false update
    apk --root /opt/guest --arch aarch64 --scripts=false \
        --commit-hooks=false add python3 py3-pip git nodejs npm curl ca-certificates-bundle
    apk --root /opt/guest --arch aarch64 info -e python3 git nodejs npm
    test -f /opt/guest/usr/bin/python3 || test -L /opt/guest/usr/bin/python3
    test -f /opt/guest/usr/bin/node || test -L /opt/guest/usr/bin/node
  '
echo "Offline developer tools:"
for exe in "$STAGING/usr/bin/"{python3,node,npm,git}; do
    test -e "$exe" || { echo "Missing packaged executable: $exe"; exit 1; }
done
# Full offline rootfs replaces the minimal 4MB upstream archive and is
# extracted once at runtime by LinuxEnvironment.
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -czf "$ASSET/alpine-rootfs.tgz" -C "$STAGING" .
sha256sum "$ASSET/alpine-rootfs.tgz" | awk '{print $1}' > "$ASSET/alpine-rootfs.sha256"
test "$(stat -c%s "$ASSET/alpine-rootfs.tgz")" -lt 150000000
echo "Linux runtime + Python/Git/Node/npm bundled: $(du -h "$ASSET/alpine-rootfs.tgz" | cut -f1)"
