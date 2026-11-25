#!/bin/bash
# Builds the single APK with aapt/javac/d8/zipalign/apksigner (no Gradle).
# The host is arm64, so build tools come from the arm64 android-tools package.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${SDK:-/opt/android-sdk}
BT=${BT:-/tmp/armtools35/build-tools}
JAR=$SDK/platforms/android-34/android.jar
D8_JAR=${D8_JAR:-/tmp/d8pkg/data/data/com.termux/files/usr/share/java/d8.jar}
SIGNER_JAR=${SIGNER_JAR:-/tmp/aspkg/data/data/com.termux/files/usr/share/java/apksigner.jar}
OUT=$ROOT/build/android
KEYSTORE=${KEYSTORE:-$ROOT/build/codexweb.jks}
KS_PASS=${KS_PASS:-codexweb}
KS_ALIAS=${KS_ALIAS:-codexweb}
DIST=${DIST:-$ROOT/build/dist/CodexWeb.apk}

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$(dirname "$DIST")"

# The runtime payload ships as flat assets/payload/<name> files: nested ZIPs
# under assets/ are not reliably streamable on Android. Every payload asset is
# kept STORED (no aapt filtering/compression) so the bytes come out intact.
rm -rf "$ROOT/assets/payload"
mkdir -p "$ROOT/assets/payload"
PAYLOAD_STAGE=${PAYLOAD_STAGE:-/tmp/cx-payload/out/stage}
[ -d "$PAYLOAD_STAGE" ] || { echo "缺少 $PAYLOAD_STAGE，先运行 tools/build-payload.sh"; exit 1; }
# The stage is a cache: always refresh the small runtime sources from src/ so
# a stale stage can never ship an old backend or UI.
cp "$ROOT/src/backend/codexd.py" "$PAYLOAD_STAGE/codexweb/codexd.py"
cp "$ROOT/src/guest/launch.sh" "$PAYLOAD_STAGE/codexweb/launch.sh"
cp "$ROOT/src/frontend/index.html" "$PAYLOAD_STAGE/codexweb/ui/index.html"
# Single source of truth: src/version, verbatim (no trailing space).
cp "$ROOT/src/version" "$PAYLOAD_STAGE/VERSION"
cp -a "$PAYLOAD_STAGE/." "$ROOT/assets/payload/"
chmod -R 644 "$ROOT/assets/payload"
find "$ROOT/assets/payload" -type d -exec chmod 755 {} +
for f in bin/proot bin/loader bin/loader-m32 bin/proot-loader; do chmod 755 "$ROOT/assets/payload/$f"; done

echo "[apk] aapt package"
# aapt's -0 matching is unreliable for extension-less files and it strips
# ".gz" from asset names, so payload compression is fixed up afterwards by
# normalize-assets.py (rewrites payload entries as STORED, keeps names).
"$BT/aapt" package -f -m \
  -S "$ROOT/res" \
  -A "$ROOT/assets" \
  -M "$ROOT/AndroidManifest.xml" \
  -I "$JAR" \
  -J "$OUT/gen" \
  -F "$OUT/app.unsigned.apk"

python3 "$ROOT/tools/normalize-assets.py" "$ROOT" "$OUT/app.unsigned.apk" "$OUT/app.raw.apk"

echo "[apk] javac"
find "$ROOT/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn -Xlint:-options --release 8 \
  -classpath "$JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[apk] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 24 --lib "$JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "[apk] package dex"
cp "$OUT/app.raw.apk" "$OUT/app.dexed.apk"
(cd "$OUT/dex" && python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_STORED); z.write('classes.dex','classes.dex'); z.close()" "$OUT/app.dexed.apk")

echo "[apk] zipalign -p 4"
"$BT/zipalign" -f -p 4 "$OUT/app.dexed.apk" "$OUT/app.aligned.apk"

echo "[apk] sign"
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" \
  -alias "$KS_ALIAS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Codex Web, OU=rootless"
java -jar "$SIGNER_JAR" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true \
  --out "$DIST" "$OUT/app.aligned.apk"
java -jar "$SIGNER_JAR" verify --print-certs "$DIST" | head -3
ls -la "$DIST"
