#!/bin/bash
# Windows/Git-Bash build of the single APK, reusing the checked-in runtime
# payload (rootfs.tar.gz + proot binaries) and a local Android SDK + JDK.
# Mirrors tools/build-apk.sh's aapt -> javac -> d8 -> zipalign -> apksigner
# pipeline without the Linux/Termux-only /tmp/cx-payload staging step.
set -eo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

# Override any of these via environment if your SDK/JDK lives elsewhere.
SDK=${SDK:-/c/Users/Administrator/WorkBuddy/2026-11-23-13-53-36/tools/sdk}
JDK=${JDK:-/c/Users/Administrator/WorkBuddy/2026-11-23-13-53-36/tools/jdk/zulu17.56.15-ca-jdk17.0.14-win_x64}
BT=${BT:-$SDK/build-tools/34.0.0}
JAR=$SDK/platforms/android-34/android.jar
D8_JAR=${D8_JAR:-$BT/lib/d8.jar}
SIGNER_JAR=${SIGNER_JAR:-$BT/lib/apksigner.jar}
OUT=$ROOT/build/android
KEYSTORE=${KEYSTORE:-$ROOT/build/codexweb.jks}
KS_PASS=${KS_PASS:-codexweb}
KS_ALIAS=${KS_ALIAS:-codexweb}
DIST=${DIST:-$ROOT/build/dist/CodexWeb.apk}

JAVAC="$JDK/bin/javac.exe"
JAVA="$JDK/bin/java.exe"
KEYTOOL="$JDK/bin/keytool.exe"
AIDL="$BT/aidl.exe"

# Native Windows tools need Win32 paths, not MSYS (/d/...) paths.
wpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

for f in "$JAR" "$D8_JAR" "$SIGNER_JAR" "$JAVAC" "$BT/aapt.exe" "$BT/zipalign.exe" "$AIDL"; do
  [ -e "$f" ] || { echo "缺少构建工具: $f" >&2; exit 1; }
done

# Recreate build output dirs. Use rename-aside instead of delete: on Windows a
# stale javac/java handle can lock files and make rmtree hang for minutes, and
# the sandbox's safe-rm shim can block rm -rf with an interactive prompt.
STAMP=$$
for name in gen classes dex; do
  if [ -d "$OUT/$name" ]; then
    mv "$OUT/$name" "$OUT/${name}.old.$STAMP" 2>/dev/null || true
  fi
done
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$(dirname "$DIST")"

echo "[apk] refresh payload from src/ (single source of truth)"
# Keep the on-device payload-unpack stamp in sync with src/version. This Java
# constant decides whether RuntimePayload re-extracts codexd.py/index.html on
# upgrade; a stale value makes new APKs silently keep running the old payload
# (e.g. a new /api/balance route never appears). Drift here caused v68-v70 to
# ship without their payload ever reaching the device.
APP_VER=$(tr -d '[:space:]' < "$ROOT/src/version")
[ -n "$APP_VER" ] || { echo "src/version is empty" >&2; exit 1; }
RP_JAVA="$ROOT/java/com/aishell/codexweb/RuntimePayload.java"
if grep -q 'static final String VERSION = ' "$RP_JAVA"; then
  sed -i -E "s/static final String VERSION = \"[0-9]+\";/static final String VERSION = \"$APP_VER\";/" "$RP_JAVA"
else
  echo "RuntimePayload.java VERSION constant not found" >&2; exit 1;
fi
echo "[apk] payload unpack stamp = $APP_VER"
cp "$ROOT/src/backend/codexd.py" "$ROOT/assets/payload/codexweb/codexd.py"
cp "$ROOT/src/guest/launch.sh" "$ROOT/assets/payload/codexweb/launch.sh"
cp "$ROOT/src/frontend/index.html" "$ROOT/assets/payload/codexweb/ui/index.html"
# Inject real version into the frontend placeholder so the sidebar shows the
# actual build instead of a stale hardcoded number.
sed -i -E "s/\{\{APP_VERSION\}\}/$APP_VER/g" "$ROOT/assets/payload/codexweb/ui/index.html"
cp "$ROOT/src/version" "$ROOT/assets/payload/VERSION"
for f in bin/proot bin/loader bin/loader-m32 bin/proot-loader; do
  [ -s "$ROOT/assets/payload/$f" ] || { echo "缺少 payload: $f" >&2; exit 1; }
done
[ -s "$ROOT/assets/payload/rootfs.tar.gz" ] || { echo "缺少 rootfs.tar.gz" >&2; exit 1; }

echo "[apk] aapt package"
"$BT/aapt.exe" package -f -m \
  -S "$ROOT/res" \
  -A "$ROOT/assets" \
  -M "$ROOT/AndroidManifest.xml" \
  -I "$JAR" \
  -J "$OUT/gen" \
  -F "$OUT/app.unsigned.apk"

python3 "$ROOT/tools/normalize-assets.py" "$ROOT" "$OUT/app.unsigned.apk" "$OUT/app.raw.apk"

echo "[apk] aidl (Shizuku service interfaces)"
aidl_sources=()
while IFS= read -r f; do aidl_sources+=("$(wpath "$f")"); done < <(find "$ROOT/aidl" -name '*.aidl')
"$AIDL" --lang=java -I "$(wpath "$ROOT/aidl")" -o "$(wpath "$OUT/gen")" "${aidl_sources[@]}"

echo "[apk] javac"
find "$ROOT/java" -name '*.java' | while IFS= read -r f; do wpath "$f"; done > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' | while IFS= read -r f; do wpath "$f"; done >> "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -Xlint:-options --release 8 \
  -classpath "$(wpath "$JAR")" \
  -d "$(wpath "$OUT/classes")" @"$OUT/sources.txt"

echo "[apk] d8"
find "$OUT/classes" -name '*.class' | while IFS= read -r f; do wpath "$f"; done > "$OUT/classes.txt"
"$JAVA" -cp "$(wpath "$D8_JAR")" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$(wpath "$JAR")" \
  --output "$(wpath "$OUT/dex")" @"$OUT/classes.txt"

echo "[apk] package dex"
cp "$OUT/app.raw.apk" "$OUT/app.dexed.apk"
(cd "$OUT/dex" && python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_STORED); z.write('classes.dex','classes.dex'); z.close()" "$OUT/app.dexed.apk")

echo "[apk] zipalign -p 4"
"$BT/zipalign.exe" -f -p 4 "$OUT/app.dexed.apk" "$OUT/app.aligned.apk"

echo "[apk] sign"
[ -f "$KEYSTORE" ] || "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" \
  -alias "$KS_ALIAS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Codex Web, OU=rootless"
"$JAVA" -jar "$SIGNER_JAR" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true \
  --out "$DIST" "$OUT/app.aligned.apk"
"$JAVA" -jar "$SIGNER_JAR" verify --print-certs "$DIST" | head -3
ls -la "$DIST"
# Keep dist lean: after a successful build, drop older CodexWeb-v<N> APKs and
# older launcher archives. Unversioned CodexWeb.apk/.idsig stay as build output.
if [ "${SKIP_CLEANUP:-0}" = "1" ]; then
  echo "[apk] cleanup skipped (SKIP_CLEANUP=1); intermediates kept on disk"
else
  echo "[apk] cleanup old APKs"
  python3 "$ROOT/tools/cleanup-apks.py" "$(dirname "$DIST")"
fi
echo "BUILD_OK $DIST"
