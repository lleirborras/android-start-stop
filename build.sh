#!/bin/sh
# Builds build/nostartstop.apk without gradle.
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"

# Version stamped into the APK; CI sets these from the tag and run number.
VERSION_NAME="${VERSION_NAME:-dev}"
VERSION_CODE="${VERSION_CODE:-1}"

# Set KEYSTORE (PKCS12), KS_PASS and KEY_ALIAS to sign with a stable key.
# Without KEYSTORE the local debug key is used, which is fine for sideloading only.
DEBUG_KS="$HOME/.android/debug.keystore"
KS="${KEYSTORE:-$DEBUG_KS}"
export KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

rm -rf build && mkdir -p build/stubs build/classes build/dex

find stubs -name '*.java' -exec javac -nowarn -source 8 -target 8 -cp "$JAR" -d build/stubs {} +
find src -name '*.java' -exec javac -Xlint:all,-deprecation,-options -Werror -source 8 -target 8 -cp "$JAR:build/stubs" -d build/classes {} +
find build/classes -name '*.class' -exec "$BT/d8" --min-api 21 --lib "$JAR" --classpath build/stubs --output build/dex {} +

"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --replace-version --version-name "$VERSION_NAME" --version-code "$VERSION_CODE"
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$BT/zipalign" -f 4 build/unaligned.apk build/nostartstop.apk

if [ "$KS" = "$DEBUG_KS" ] && [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS build/nostartstop.apk
"$BT/apksigner" verify build/nostartstop.apk
echo "built build/nostartstop.apk ($VERSION_NAME, code $VERSION_CODE)"
