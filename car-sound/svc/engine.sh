#!/system/bin/sh
# Car Sound's engine (Java, app_process, as root). When HMG-API stops it (the mod turned off or
# removed), the trap asks it to stop cleanly: effects released, Bose DSC put back.
J=$HMGAPI_MOD_DIR/engine.jar
D=/data/local/tmp/car-sound
mkdir -p $D
stop() {
    echo "stopping: releasing the effects, Bose DSC as it was"
    touch $D/stop
    wait $P
    exit 0
}
trap stop TERM INT HUP
CLASSPATH=$J app_process /system/bin com.carsound.engine.Engine &
P=$!
wait $P
echo "engine exited ($?)"
