#!/bin/sh
# Test Car Sound's use of the car's amplifier (its own Bass/Mid/Treble) and tune its profile
# with car data, over adb, parked. See docs/AMPLIFIER.md for what to listen and look for.
#
#   sh amp.sh status                 amplifier type, profile, the car's tone, volume, lifts now
#   sh amp.sh sweep [bass|treble|mid] [secs]
#                                    step that control +1, +2 ... and back down, secs each (default 6),
#                                    printing the car's own log line for each write: listen for
#                                    how big a step is, clicks or dropouts, and watch for pop-ups
#   sh amp.sh hold <bass> <mid> <treble>
#                                    hold those steps on top of your tone (10 minutes, or until off)
#   sh amp.sh off                    end a sweep or hold: back to Car Sound's own lifts
#   sh amp.sh profile '<json>'       test values over the built-in profile, e.g.
#                                    '{"bass_step_mB": 150, "max_bass_steps": 4}'
#   sh amp.sh profile clear          the built-in profile again
#   sh amp.sh report                 make a car report and copy it to out/
#
# The engine applies a sweep or hold only while the car's real speed is 0.
set -e
cd "$(dirname "$0")"
D=/data/local/tmp/car-sound
adb get-state >/dev/null 2>&1 || { echo "no head unit on adb"; exit 1; }
r() { adb shell "/system/bin/hmgapid sh '$1'" | tr -d '\r'; }
st() {
    r "cat $D/status.json" | python3 -c 'import json,sys
s=json.load(sys.stdin)
t=s.get("car_tone") or ["?"]*3; a=s.get("amp_steps") or [0,0,0]; l=s.get("loud_mB") or [0,0]
print("  amplifier %s (type %s)%s%s" % (s.get("amp_name","?"), s.get("amp_type","?"), "" if s.get("amp_verified") else ", not verified", ", TEST PROFILE" if s.get("amp_test_profile") else ""))
print("  car tone: bass %s, mid %s, treble %s (0 = centre)   volume %s" % (t[0], t[1], t[2], s.get("volume")))
print("  Car Sound adds: bass +%s, mid +%s, treble +%s steps%s   loudness lift: bass %+.1f dB, treble %+.1f dB" % (a[0], a[1], a[2], " (test)" if s.get("amp_testing") else "", l[0]/100.0, l[1]/100.0))
print("  amplifier writes since the engine started: %s" % s.get("amp_writes"))'
}
lastwrite() {
    adb shell "logcat -d -v time -s MicomAudioService:* MicomAudioManager:*" | tr -d '\r' | grep -E "setBassMidTreb|setSound\(" | tail -1
}
hold() { r "echo $1 $2 $3 > $D/amp-test; chmod 644 $D/amp-test"; }
case ${1:-} in
status)
    st ;;
sweep)
    what=${2:-bass}; secs=${3:-6}
    case $what in bass) n=6 ;; mid) n=3 ;; treble) n=4 ;; *) echo "bass, mid or treble"; exit 2 ;; esac
    echo "parked? the engine ignores this while moving. Each step lasts $secs s."
    st
    for i in $(seq 0 $n) $(seq $((n - 1)) -1 0); do
        case $what in bass) hold $i 0 0 ;; mid) hold 0 $i 0 ;; treble) hold 0 0 $i ;; esac
        sleep 2
        echo "$what +$i:  $(lastwrite)"
        sleep $((secs - 2))
    done
    r "rm -f $D/amp-test"
    sleep 2
    echo "done: back to Car Sound's own lifts"
    st ;;
hold)
    [ $# -eq 4 ] || { echo "sh amp.sh hold <bass> <mid> <treble>"; exit 2; }
    hold "$2" "$3" "$4"; sleep 2; echo "$(lastwrite)"; st ;;
off)
    r "rm -f $D/amp-test"; sleep 2; st ;;
profile)
    [ -n "${2:-}" ] || { echo "sh amp.sh profile '<json>' | clear"; exit 2; }
    if [ "$2" = clear ]; then
        r "rm -f $D/profile.json"
    else
        echo "$2" | python3 -c 'import json,sys; json.load(sys.stdin)' || { echo "not JSON"; exit 2; }
        t=$(mktemp)
        printf '%s' "$2" > "$t"
        adb push "$t" /data/local/tmp/car-sound-profile.json >/dev/null
        rm -f "$t"
        r "cp /data/local/tmp/car-sound-profile.json $D/profile.json; chmod 644 $D/profile.json; rm /data/local/tmp/car-sound-profile.json"
    fi
    sleep 2
    adb shell "/system/bin/hmgapid service-log car-sound engine" | tr -d '\r' | grep "amplifier profile" | tail -1
    st ;;
report)
    r "touch $D/report-now"
    for i in $(seq 1 20); do
        sleep 1
        [ -n "$(r "ls $D/report-now 2>/dev/null")" ] || break
    done
    mkdir -p out
    f=out/car-report-$(date +%Y%m%d-%H%M%S).txt
    adb pull $D/car-report.txt "$f" >/dev/null
    echo "$f ($(wc -l < "$f" | tr -d ' ') lines): read it before you post it" ;;
*)
    sed -n '2,20s/^# \{0,1\}//p' "$0"; exit 2 ;;
esac
