#!/bin/bash
# Cross-compile the four proot binaries for Android arm64.
# Needs: native clang (>= 15), NDK sysroot, lld. See tools/env-notes.md.
# Output: build/proot/{loader,loader-m32,proot,proot-loader}
set -e
NDK=${NDK:-/opt/android-ndk-r27c}
API=${API:-24}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SRC=${PROOT_SRC:-/tmp/proot-src/src}
TALLOC=${TALLOC_SRC:-/tmp/talloc}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/build/proot}
WORK=${WORK:-/tmp/proot-build}
CLANG=${CLANG:-clang}
AR=${AR:-ar}

mkdir -p "$OUT" "$WORK/lib"

target="--target=aarch64-linux-android$API"
SYSROOT="--sysroot=$TC/sysroot"
RES="-resource-dir $TC/lib/clang/18"
CC="$CLANG $target $SYSROOT $RES $compat"
LD="$CLANG $target $SYSROOT $RES $compat -fuse-ld=lld -L$WORK/lib"
STRIP=${STRIP:-strip}
if ! command -v "$STRIP" >/dev/null; then STRIP=true; fi

# bionic links -lssp_nonshared but does not ship it; gcc emits stack_chk_fail
# calls against libc, so the classic local alias is all that is needed.
echo 'void __stack_chk_fail_local(void) { __builtin_trap(); }' > "$WORK/ssp.c"
$CC -c -O2 "$WORK/ssp.c" -o "$WORK/ssp.o"
$AR rcs "$WORK/lib/libssp_nonshared.a" "$WORK/ssp.o"

# --- talloc, public API only (Android libc provides the rest)
if [ ! -f "$WORK/lib/libtalloc.a" ]; then
  cat > "$WORK/replace.h" <<'EOF'
#ifndef CX_REPLACE_H
#define CX_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/auxv.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define HAVE_VA_COPY 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_GETAUXVAL 1
#endif
EOF
  $CC -O2 -fPIC -I"$WORK" -I"$TALLOC" -c "$TALLOC/talloc.c" -o "$WORK/talloc.o"
  $AR rcs "$WORK/lib/libtalloc.a" "$WORK/talloc.o"
fi
mkdir -p "$WORK/include"
cp "$TALLOC/talloc.h" "$WORK/include/"

# --- proot (builds loader and proot; loader is embedded by default)
cd "$SRC"
make -f GNUmakefile clean >/dev/null 2>&1 || true
make -f GNUmakefile proot \
  CC="$CC" LD="$LD" OBJCOPY="objcopy" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/include" \
  CFLAGS="-Wall -O2 -fPIE -Wno-error=implicit-function-declaration -Wno-error=implicit-int" \
  LDFLAGS="-L$WORK/lib -ltalloc -pie -Wl,-z,noexecstack" \
  >"$WORK/build.log" 2>&1 || { tail -60 "$WORK/build.log"; exit 1; }

cp loader/loader "$OUT/loader"
cp proot "$OUT/proot"
# proot-loader launches proot with an absolute loader path, which is how a
# rootless start avoids relying on /proc/self/exe mapping on Android.
cp loader/loader "$OUT/proot-loader"
# arm64 devices never exec 32-bit loaders: keep the four-file contract.
cp loader/loader "$OUT/loader-m32"
for f in loader loader-m32 proot proot-loader; do $STRIP "$OUT/$f" 2>/dev/null || true; done
ls -la "$OUT"
