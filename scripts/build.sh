#!/usr/bin/env bash
# Builds a signed, installable Skypix APK with only: JDK, aapt, zipalign, apksigner (apt: aapt zipalign apksigner),
# dx 11 (repackaged AOSP dx) and the Android 14 stubs jar (both from Maven Central). No Gradle, no Android Studio, no Google services.
#
#   scripts/build.sh            -> dist/skypix.apk
set -euo pipefail
cd "$(dirname "$0")/.."

DEPS=build/deps
mkdir -p "$DEPS" build/gen build/classes build/dex dist
ANDROID_JAR="$DEPS/android-all.jar"
DX_JAR="$DEPS/dx.jar"
fetch() { [ -s "$2" ] || curl -sSfL -o "$2" "$1"; }
fetch https://repo.maven.apache.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar "$ANDROID_JAR"
fetch https://repo.maven.apache.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/11.0.0_r3/dalvik-dx-11.0.0_r3.jar "$DX_JAR"
fetch https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar "$DEPS/json.jar"

# 1. regenerate generated assets (icons + widget-picker previews) from the real renderer
MODE=icons scripts/preview.sh app/src/main/res/drawable
MODE=previews scripts/preview.sh app/src/main/res/drawable-nodpi

# 2. resources -> R.java + unsigned apk
rm -rf build/gen/* build/classes/* build/dex/*
aapt package -f -m -J build/gen -M app/src/main/AndroidManifest.xml -S app/src/main/res \
  -I "$ANDROID_JAR" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 3 --version-name 1.0.2 -F build/unsigned.apk

# 3. java -> class -> dex (no lambdas / default methods: dx 11 (repackaged AOSP dx) can't desugar them)
javac -nowarn --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d build/classes \
  $(find app/src/main/java build/gen -name '*.java') 2>&1 | grep -v -E "Picked up|warning: \[options\]|^[0-9]+ warning" || true
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 --output=build/dex/classes.dex build/classes
cp build/dex/classes.dex build/classes.dex
(cd build && aapt add -f unsigned.apk classes.dex >/dev/null)

# 4. align + sign (v1+v2+v3). The keystore is a throwaway signing identity kept in the repo so updates install over each other.
[ -f keystore/skypix.jks ] || keytool -genkeypair -keystore keystore/skypix.jks -storepass skypix123 -keypass skypix123 \
  -alias skypix -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Skypix, O=Skypix"
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks keystore/skypix.jks --ks-pass pass:skypix123 --key-pass pass:skypix123 --out dist/skypix.apk build/aligned.apk
apksigner verify --verbose dist/skypix.apk | head -5
ls -la dist/skypix.apk
