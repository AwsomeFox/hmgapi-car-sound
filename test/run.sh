#!/bin/sh
# Car Sound's off-car tests, with the HMG-API mod SDK hmgapi-sdk.json pins:
#   - the settings model (test/ConfigTest.java: speed curves, presets)
#   - mod.json: valid, its "hmgapi" floor and hook points against the SDK (bin/check-points.py)
#   - svc/engine.sh only calls commands the head unit has (bin/check-scripts.sh)
#   - a build: the signed mod zip has what HMG-API installs, and verifies
# Exit 1 on any failure.
set -eu
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
A=$(ls "$SDK"/platforms/android-*/android.jar | tail -1)
[ -n "${HMGAPI_SDK:-}" ] || eval "$(sh tools/sdk.sh)"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0

echo "== settings model"
javac --release 8 -Xlint:-options -nowarn -cp "$A" -d "$T" car-sound/src/common/com/carsound/Config.java test/ConfigTest.java
java -cp "$T:$A" ConfigTest || fail=1

echo "== mod.json"
python3 -c 'import json; m=json.load(open("car-sound/mod.json")); assert m["id"]=="car-sound"; print("ok    mod.json", m["version"])' || fail=1
python3 -c 'import json; json.load(open("car-sound/settings.json")); print("ok    settings.json")' || fail=1
python3 "$HMGAPI_SDK/bin/check-points.py" car-sound || fail=1

echo "== scripts the head unit runs"
sh "$HMGAPI_SDK/bin/check-scripts.sh" car-sound || fail=1

echo "== a test build"
sh build.sh >"$T/build.log" 2>&1 || { cat "$T/build.log"; fail=1; }
V=$(python3 -c 'import json; print(json.load(open("car-sound/mod.json"))["version"])')
Z=out/car-sound-$V.zip
if [ -f "$Z" ]; then
    for f in META-HMGAPI/manifest.json META-HMGAPI/signature app/CarSound.apk engine.jar svc/engine.sh mod.json settings.json icon/icon.png icon/icon_night.png icon/icon_disabled.png icon/icon_disabled_night.png; do
        if unzip -l "$Z" "$f" >/dev/null 2>&1; then echo "ok    $Z has $f"; else echo "FAIL  $Z has no $f"; fail=1; fi
    done
else
    echo "FAIL  no $Z"; fail=1
fi

[ $fail = 0 ] && echo "all tests passed" || echo "TESTS FAILED"
exit $fail
