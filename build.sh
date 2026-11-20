#!/bin/sh
# Builds Car Sound into out/: the engine (engine.jar), the app (CarSound.apk) and the signed
# HMG-API mod zip, out/car-sound-<version>.zip, which `hmgapid engine install`, the HMG-API app
# (Updates, from a USB stick) and the SDK's mknemesismod.sh take.
#
#   sh build.sh [<HMG-API author key>]
#
# The author key signs the mod (hmgapi.engine.Signing keygen; <key>.pub beside it). Without one
# the mod is signed with a throwaway key: fine for a test, but HMG-API only updates an installed
# mod from the same key, so releases always use the same one (the release workflow's
# HMGAPI_AUTHOR_KEY secret).
#
# Env:
#   ANDROID_HOME       the Android SDK: build-tools (d8, aapt2, zipalign, apksigner), a platform
#                      android.jar [~/Library/Android/sdk]
#   HMGAPI_SDK         HMG-API's mod SDK [the one hmgapi-sdk.json pins, via tools/sdk.sh]
#   APP_KEYSTORE       the .p12 that signs the app, APP_KEYSTORE_PASS its password [keys/car-sound-app.p12,
#                      password "carsound"; made on first build]. Android only updates an installed
#                      app from the same key: keep it, and use it for every release.
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
A=$(ls "$SDK"/platforms/android-*/android.jar | tail -1)
BT=$(ls -d "$SDK"/build-tools/* | tail -1)
[ -n "${HMGAPI_SDK:-}" ] || eval "$(sh tools/sdk.sh)"
M=car-sound
ID=$(python3 -c 'import json; print(json.load(open("car-sound/mod.json"))["id"])')
V=$(python3 -c 'import json; print(json.load(open("car-sound/mod.json"))["version"])')
AV=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' $M/src/app/AndroidManifest.xml | head -1)
[ "$V" = "$AV" ] || { echo "build: mod.json version $V, but the app's manifest says $AV: change both"; exit 1; }
B=out/build
rm -rf out && mkdir -p $B/engine $B/app/classes $B/app/dex $B/mod/app $B/mod/svc $B/mod/icon

# engine.jar: run by svc/engine.sh under app_process, as root
javac --release 8 -Xlint:-options -encoding UTF-8 -nowarn -cp "$A" -d $B/engine $(find $M/src/engine $M/src/common -name '*.java')
"$BT/d8" --release --min-api 19 --lib "$A" --output $B/mod/engine.jar $(find $B/engine -name '*.class')

# app/CarSound.apk: the screen. v1 (JAR) signing for Android 4.4; HMG-API installs it
"$BT/aapt2" compile --dir $M/src/app/res -o $B/app/res.zip
"$BT/aapt2" link -I "$A" --manifest $M/src/app/AndroidManifest.xml --min-sdk-version 19 --target-sdk-version 19 -o $B/app/base.apk $B/app/res.zip
javac --release 8 -Xlint:-options -encoding UTF-8 -nowarn -cp "$A" -d $B/app/classes $(find $M/src/app/java $M/src/common -name '*.java')
"$BT/d8" --release --min-api 19 --lib "$A" --output $B/app/dex $(find $B/app/classes -name '*.class')
cp $B/app/base.apk $B/app/unsigned.apk
(cd $B/app/dex && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 $B/app/unsigned.apk $B/app/aligned.apk
KS=${APP_KEYSTORE:-keys/car-sound-app.p12}
KP=${APP_KEYSTORE_PASS:-carsound}
if [ ! -f "$KS" ]; then
    if [ -n "${APP_KEYSTORE:-}" ]; then echo "build: no app keystore $KS"; exit 1; fi
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype pkcs12 -storepass "$KP" -keypass "$KP" \
        -alias car-sound -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Car Sound" >/dev/null 2>&1
    echo "build: made the app key $KS: keep it, every update of the app must be signed with it" >&2
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KP" --min-sdk-version 9 \
    --v2-signing-enabled false --v3-signing-enabled false --out $B/mod/app/CarSound.apk $B/app/aligned.apk

# the signed mod zip, fixed timestamps so the same sources give the same bytes
KEY=${1:-}
if [ -z "$KEY" ]; then
    KEY=out/throwaway.key
    java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing keygen "$KEY" >/dev/null
    echo "build: signed with a throwaway author key (a test build; pass your HMG-API author key for a release)" >&2
fi
cp $M/mod.json $M/settings.json $B/mod/ && cp $M/svc/engine.sh $B/mod/svc/ && cp $M/icon/icon*.png $B/mod/icon/
java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing sign $B/mod "$KEY"
java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing verify $B/mod >/dev/null
(cd $B/mod && find . -exec touch -t 202601010000 {} + && \
    zip -X -q ../../$ID-$V.zip META-HMGAPI/manifest.json META-HMGAPI/signature \
        app/CarSound.apk engine.jar svc/engine.sh mod.json settings.json icon/*.png)
rm -f out/throwaway.key out/throwaway.key.pub
(cd out && shasum -a 256 $ID-$V.zip)
