#!/bin/bash
# Builds /tmp/cx-payload/out/stage/* (also copied to assets/payload/ by build-apk.sh):
#   rootfs.tar.gz           Alpine + python3 + ca-certificates + codex
#   bin/{proot,loader,loader-m32,proot-loader}
#   codexweb/{codexd.py,launch.sh,ui/index.html}
#   VERSION
# The zip is STORED so the device-side copy is a straight byte copy.
set -eo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/src
CACHE=${CACHE:-/tmp/cx-cache}
WORK=${WORK:-/tmp/cx-payload}
ALPINE_VER=${ALPINE_VER:-3.20.3}
CODEX_VER=${CODEX_VER:-0.171.0}
ROOTFS=$WORK/rootfs
OUT=$WORK/out
mkdir -p "$CACHE" "$WORK" "$OUT"

fetch() { # url file
  if [ ! -f "$2" ]; then
    curl -fL --retry 5 --retry-all-errors -C - -o "$2.part" "$1" || curl -fL --retry 5 -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
}

if [ ! -d "$ROOTFS" ]; then
  echo "[payload] alpine $ALPINE_VER"
  base="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64"
  fetch "$base/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz" "$CACHE/alpine-minirootfs-$ALPINE_VER.tar.gz"
  mkdir -p "$ROOTFS"
  tar xzf "$CACHE/alpine-minirootfs-$ALPINE_VER.tar.gz" -C "$ROOTFS"
  cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
  # apk exits non-zero when a ncurses terminfo rename is rejected by the
  # container filesystem; the interpreter and CA bundle are what matter here
  # --no-scripts: apk triggers (terminfo renames) can fail on the build
  # filesystem; the interpreter and CA bundle are all the runtime needs
  chroot "$ROOTFS" /sbin/apk add --no-cache -q --no-scripts python3 ca-certificates || true
  test -x "$ROOTFS/usr/bin/python3.12"
  test -s "$ROOTFS/etc/ssl/certs/ca-certificates.crt"
  rm -rf "$ROOTFS/var/cache/apk"/* "$ROOTFS/etc/ssl1.1" 2>/dev/null || true
fi

if [ ! -x "$ROOTFS/opt/codex/codex" ]; then
  echo "[payload] codex $CODEX_VER"
  gh="https://github.com/openai/codex/releases/download/rust-v$CODEX_VER"
  fetch "$gh/codex-aarch64-unknown-linux-musl.tar.gz" "$CACHE/codex-$CODEX_VER.tar.gz"
  fetch "$gh/codex-code-mode-host-aarch64-unknown-linux-musl.tar.gz" "$CACHE/cmh-$CODEX_VER.tar.gz"
  mkdir -p "$ROOTFS/opt/codex"
  tar xzf "$CACHE/codex-$CODEX_VER.tar.gz" -C "$ROOTFS/opt/codex"
  mv "$ROOTFS/opt/codex/codex-aarch64-unknown-linux-musl" "$ROOTFS/opt/codex/codex"
  tar xzf "$CACHE/cmh-$CODEX_VER.tar.gz" -C "$ROOTFS/opt/codex"
  mv "$ROOTFS/opt/codex/codex-code-mode-host-aarch64-unknown-linux-musl" \
     "$ROOTFS/opt/codex/codex-code-mode-host"
  chmod 755 "$ROOTFS/opt/codex/codex" "$ROOTFS/opt/codex/codex-code-mode-host"
fi

find "$ROOTFS" -name "__pycache__" -type d -prune -exec rm -rf {} + 2>/dev/null || true
rm -rf "$ROOTFS/root/.cache" "$ROOTFS/tmp/"* "$ROOTFS/root/.ash_history" 2>/dev/null || true
mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/tmp" "$ROOTFS/sdcard" "$ROOTFS/root/.codex"
chmod 1777 "$ROOTFS/tmp"

echo "[payload] rootfs.tar.gz"
rm -f "$OUT/rootfs.tar.gz"
tar -C "$ROOTFS" --numeric-owner -czf "$OUT/rootfs.tar.gz" .
# Single source of truth for the payload cache version (build-apk.sh re-copies it).
cp "$SRC/version" "$OUT/VERSION"

rm -rf "$OUT/stage"
mkdir -p "$OUT/stage/bin" "$OUT/stage/codexweb/ui"
cp "$ROOT/build/proot/proot" "$ROOT/build/proot/loader" "$ROOT/build/proot/loader-m32" \
   "$ROOT/build/proot/proot-loader" "$OUT/stage/bin/"
cp "$SRC/backend/codexd.py" "$OUT/stage/codexweb/codexd.py"
cp "$SRC/guest/launch.sh" "$OUT/stage/codexweb/launch.sh"
cp "$SRC/frontend/index.html" "$OUT/stage/codexweb/ui/index.html"
cp "$OUT/rootfs.tar.gz" "$OUT/VERSION" "$OUT/stage/"
chmod 755 "$OUT/stage/bin/"* "$OUT/stage/codexweb/launch.sh"

python3 - "$OUT/stage" "$OUT/payload.zip" <<'EOF'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
    for base, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, src))
print("payload.zip %.1f MiB" % (os.path.getsize(dst) / 1048576.0))
EOF
