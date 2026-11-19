#!/bin/sh
# Builds build/nostartstop.apk without gradle.
set -eu
cd "$(dirname "$0")"

# d8 from build-tools 34.0.0 crashes on newer JDKs; .tool-versions pins 17.
java_major=$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')
if [ "$java_major" != 17 ]; then
    echo "build.sh needs JDK 17, found javac $java_major (with asdf: asdf install)" >&2
    exit 1
fi

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

# Diagnostics are compiled in only when POSTHOG_KEY is set (CI sets it from a repository variable).
# The key is a public, write-only PostHog project key.
POSTHOG_KEY="${POSTHOG_KEY:-}"
POSTHOG_HOST="${POSTHOG_HOST:-https://eu.i.posthog.com}"
case "$POSTHOG_KEY" in *[!A-Za-z0-9_]*) echo "POSTHOG_KEY has unexpected characters" >&2; exit 1 ;; esac
case "$POSTHOG_HOST" in
    https://*[!A-Za-z0-9.-]*) echo "POSTHOG_HOST must look like https://host" >&2; exit 1 ;;
    https://?*) ;;
    *) echo "POSTHOG_HOST must look like https://host" >&2; exit 1 ;;
esac
mkdir -p build/gen/com/furgo/nostartstop
cat > build/gen/com/furgo/nostartstop/BuildConfig.java <<JAVA
package com.furgo.nostartstop;

final class BuildConfig {
    static final String POSTHOG_KEY = "$POSTHOG_KEY";
    static final String POSTHOG_HOST = "$POSTHOG_HOST";
}
JAVA

find stubs -name '*.java' -exec javac -nowarn -source 8 -target 8 -cp "$JAR" -d build/stubs {} +
find src build/gen -name '*.java' -exec javac -Xlint:all,-deprecation,-options -Werror -source 8 -target 8 -cp "$JAR:build/stubs" -d build/classes {} +
find build/classes -name '*.class' -exec "$BT/d8" --min-api 21 --lib "$JAR" --classpath build/stubs --output build/dex {} +

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
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
