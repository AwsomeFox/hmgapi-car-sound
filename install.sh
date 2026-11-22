#!/bin/sh
# Install Car Sound's build (out/car-sound-<version>.zip, from build.sh) on the head unit over adb, through
# HMG-API: engine install + apply, then the app's sync and the engine's restart (an update
# doesn't restart a running service). Prints the engine's last log lines and its status.
set -e
cd "$(dirname "$0")"
Z=$(ls out/car-sound-*.zip | tail -1)
T=/data/local/tmp/car-sound-install
adb get-state >/dev/null 2>&1 || { echo "no head unit on adb"; exit 1; }
r() { adb shell "/system/bin/hmgapid sh '$1'" | tr -d '\r'; }
adb shell "rm -rf $T; mkdir -p $T" >/dev/null
adb push "$Z" $T/ >/dev/null
r "/system/bin/hmgapid engine install $T/$(basename "$Z")" | grep -i -E "^installed|refus|error" || true
r "/system/bin/hmgapid engine apply" | grep -i -E "car-sound|refus|error|nothing" || true
adb wait-for-device
until adb shell "/system/bin/hmgapid ping" 2>/dev/null | grep -q ok; do sleep 2; done   # apply restarts adbd
r "/system/bin/hmgapid engine apps" | tail -1
# the running engine stops itself (its stop file) and HMG-API starts the new one: a killed engine
# would leave Android's output mix effects suspended until the head unit restarts
last() { adb shell "/system/bin/hmgapid service-log car-sound engine" | tr -d '\r' | grep "engine started" | tail -1; }
was=$(last)
r "touch /data/local/tmp/car-sound/stop"
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    sleep 2
    [ "$(last)" != "$was" ] && break
    [ "$i" = 15 ] && adb shell "/system/bin/hmgapid service-restart car-sound engine" | tr -d '\r'
done
sleep 6
adb shell "/system/bin/hmgapid service-log car-sound engine" | tr -d '\r' | grep -v "settings:" | python3 -c 'import sys; l=sys.stdin.readlines(); sys.stdout.writelines(l[-6:])'
r "cat /data/local/tmp/car-sound/status.json"; echo
