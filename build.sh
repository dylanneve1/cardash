#!/usr/bin/env bash
# Build CarDash without Gradle/AGP: aapt2 -> javac -> d8 -> apksigner.
# Keeps the APK free of AndroidX and of any native code, which is what
# makes it run on a 32-bit armeabi-v7a API 30 head unit.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
# Compile against a modern platform, but the manifest targets API 30.
PLATFORM="$SDK/platforms/android-34/android.jar"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  -A "$HERE/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 30 \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "==> javac"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  -encoding UTF-8 \
  -Xlint:-options \
  @"$OUT/sources.txt"

echo "==> d8"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BT/d8" --min-api 21 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> sign"
KS="$HERE/cardash.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias cardash \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass cardash -keypass cardash \
    -dname "CN=CarDash, OU=Talon, O=Claudius, L=Greystones, C=IE" >/dev/null
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:cardash --key-pass pass:cardash \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$HERE/cardash.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$HERE/cardash.apk" | head -3
ls -la "$HERE/cardash.apk"
echo "==> done"
