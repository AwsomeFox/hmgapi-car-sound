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
# The runtime finds JNI libraries (the audio effects' audioeffect_jni) through java.library.path,
# which it takes from LD_LIBRARY_PATH. HMG-API 1.1.0-rc.8 runs root services with an effective
# group other than the real one, so the linker treats each exec as secure and drops LD_*
# variables: give the runtime the path itself (it overrides the environment's).
#
# The engine ignores TERM, INT and HUP (an ignored signal stays ignored across exec): Android
# keeps its output mix's effects suspended until the audio service restarts if a process is
# killed while it holds an enabled effect on an app's audio session. So a stop always goes
# through the stop file, which the engine sees within a quarter second and exits on, with
# its effects released.
(trap '' TERM INT HUP; CLASSPATH=$J exec app_process -Djava.library.path=/vendor/lib:/system/lib /system/bin com.carsound.engine.Engine) &
P=$!
wait $P
echo "engine exited ($?)"
