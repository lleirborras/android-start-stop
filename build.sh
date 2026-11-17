#!/bin/sh
# Builds build/nostartstop.apk without gradle.
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KS="$HOME/.android/debug.keystore"

rm -rf build && mkdir -p build/stubs build/classes build/dex

javac -nowarn -source 8 -target 8 -cp "$JAR" -d build/stubs $(find stubs -name '*.java')
javac -nowarn -source 8 -target 8 -cp "$JAR:build/stubs" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --classpath build/stubs --output build/dex $(find build/classes -name '*.class')

"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$BT/zipalign" -f 4 build/unaligned.apk build/nostartstop.apk

mkdir -p "$(dirname "$KS")"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android build/nostartstop.apk
"$BT/apksigner" verify build/nostartstop.apk
echo "built build/nostartstop.apk"
