#!/bin/sh
# Listen to Car Sound's speed curves while parked: a simulated speed for the engine.
#   sh sim.sh <mph>            hold that speed (until `off`, or 10 minutes)
#   sh sim.sh sweep [secs]     0, 20, 35, 50, 65, 80 mph, then back to 0, each for secs (default 12:
#                              changes move 1 dB a second, so a big step takes a few seconds)
#   sh sim.sh off              back to the car's real speed
# The engine uses it only while the car's real speed is 0 (never while driving) and ignores a
# file older than 10 minutes. The app shows "mph (test)" while it's on.
set -e
D=/data/local/tmp/car-sound
adb get-state >/dev/null 2>&1 || { echo "no head unit on adb"; exit 1; }
r() { adb shell "/system/bin/hmgapid sh '$1'" | tr -d '\r'; }
now() {
    r "cat $D/status.json" | python3 -c 'import json,sys
s=json.load(sys.stdin); db=lambda k: "%+.1f dB" % (s.get(k,0)/100.0)
mph = s.get("speed_kmh",-1)*0.621371
print("  %3.0f mph%s  volume %s  bass %s  treble %s" % (mph, " (test)" if s.get("sim") else "", db("speed_vol_mB"), db("speed_bass_mB"), db("speed_treble_mB")))'
}
set_mph() { r "echo $1 > $D/sim-mph; chmod 644 $D/sim-mph"; }
case ${1:-} in
off)
    r "rm -f $D/sim-mph"; sleep 2; now ;;
sweep)
    s=${2:-12}
    for m in 0 20 35 50 65 80 0; do
        set_mph $m
        echo "$m mph: listen for $s s"
        sleep "$s"
        now
    done
    r "rm -f $D/sim-mph"
    echo "done: back to the car's real speed" ;;
''|-h|--help)
    sed -n '2,8s/^# \{0,1\}//p' "$0"; exit 2 ;;
*)
    set_mph "$1"; sleep 3; now; echo "holding $1 mph (sh sim.sh off to stop; it also ends after 10 minutes)" ;;
esac
