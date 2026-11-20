package com.carsound.engine;

import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.IBinder;

import com.carsound.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Car Sound's engine: Android's equalizer and bass boost on phone projection audio
 * (Android Auto, CarPlay), volume, bass and treble that follow the car's speed, and the Bose Dynamic Speed
 * Compensation switch. HMG-API runs it as root through svc/engine.sh (app_process).
 *
 * Settings: HMG-API's file for the mod (/data/hmgapi/settings/car-sound.json, the owner's
 * values only), read again whenever it changes. Status for the app: Config.STATUS. Stopping
 * (svc/engine.sh's trap writes "stop" next to it): effects released, so the audio is as it
 * was, and Bose DSC put back.
 */
public final class Engine {
    static final String SETTINGS = "/data/hmgapi/settings/" + Config.MOD + ".json";
    static final File DIR = new File(Config.STATUS).getParentFile();
    static final String[] PACKAGES = { "com.google.android.projection.sink", "com.daudio.app.dipo" };
    static final String AUDIO = "com.hkmc.media.IMicomAudioService";
    /** the owner's Bose DSC from before Car Sound changed it (survives restarts) */
    static final File DSC_FILE = new File(DIR, "dsc-original");
    static final int DSC = 47;   // BOSE_DSCMODE: 1 on, 0 off (what the car's Sound menu sends)
    static final SimpleDateFormat T = new SimpleDateFormat("MM-dd HH:mm:ss");

    static final class Fx {
        final int session;
        final Equalizer eq;
        final BassBoost bb;
        LoudnessEnhancer le;   // the speed tuning's volume: louder with a limiter, so it doesn't clip
        long seen;

        Fx(int session) {
            this.session = session;
            eq = new Equalizer(Integer.MAX_VALUE, session);
            bb = new BassBoost(Integer.MAX_VALUE, session);
            try {
                le = new LoudnessEnhancer(session);
            } catch (Throwable t) {
                le = null;
                log("audio session " + session + ": no loudness enhancer (" + t + "): speed volume off");
            }
        }

        void release() {
            try { eq.release(); } catch (Throwable t) { }
            try { bb.release(); } catch (Throwable t) { }
            try { if (le != null) le.release(); } catch (Throwable t) { }
        }
    }

    static final Map<Integer, Fx> fx = new HashMap<Integer, Fx>();
    static Config cfg = new Config();
    static long settingsAt = -1;
    static float speed = -1;
    /** the speed tuning as applied (mB): VOL, BASS, TREBLE; each moves at most 1 dB a second */
    static final double[] now = new double[3];
    static int dscOriginal = -1;
    static String dscApplied = "";
    static short lo = -1500, hi = 1500;

    public static void main(String[] a) throws Exception {
        DIR.mkdirs();
        DIR.setReadable(true, false);
        DIR.setExecutable(true, false);
        File stop = new File(DIR, "stop");
        stop.delete();
        log("engine started (uid " + android.os.Process.myUid() + ")");
        long tick = 0;
        try {
            while (!stop.exists()) {
                try {
                    settings();
                    speed = speed();
                    if (tick % 2 == 0) sessions();
                    apply();
                    dsc();
                    status(null);
                } catch (Throwable t) {
                    log("tick: " + t);
                }
                tick++;
                Thread.sleep(1000);
            }
        } finally {
            for (Fx f : fx.values()) f.release();
            fx.clear();
            restoreDsc();
            status("stopped");
            stop.delete();
            log("engine stopped: effects released, Bose DSC as it was");
        }
        System.exit(0);
    }

    static void log(String s) {
        System.out.println(T.format(new Date()) + " " + s);
    }

    static String read(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            StringBuilder b = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) b.append(l).append('\n');
            return b.toString();
        } finally {
            r.close();
        }
    }

    static void settings() {
        File f = new File(SETTINGS);
        long at = f.isFile() ? f.lastModified() : 0;
        if (at == settingsAt) return;
        settingsAt = at;
        try {
            cfg = Config.from(at == 0 ? null : new JSONObject(read(f)));
            log("settings: " + (at == 0 ? "the defaults" : cfg.toSaved().toString()));
        } catch (Exception e) {
            log("can't read " + SETTINGS + " (" + e + "): keeping the last ones");
        }
    }

    // ---- the car's services, by name

    static Object audio;

    static Object sm(String method, Object... args) throws Exception {
        Class<?> c = Class.forName("android.os.ServiceManager");
        for (Method m : c.getMethods()) if (m.getName().equals(method) && m.getParameterTypes().length == args.length) return m.invoke(null, args);
        throw new NoSuchMethodException(method);
    }

    static Object proxy(String descriptor, ClassLoader cl) throws Exception {
        for (String n : (String[]) sm("listServices")) {
            IBinder b = (IBinder) sm("checkService", n);
            if (b == null) continue;
            try {
                if (!descriptor.equals(b.getInterfaceDescriptor())) continue;
            } catch (Throwable t) {
                continue;
            }
            return Class.forName(descriptor + "$Stub", true, cl).getMethod("asInterface", IBinder.class).invoke(null, b);
        }
        throw new IllegalStateException("no " + descriptor);
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == args.length) return m.invoke(target, args);
        throw new NoSuchMethodException(name);
    }

    /**
     * The car's speed (km/h) from HMG-API's shared "speed" reading (mod.json "vehicle" "read"),
     * read from the car by HMG-API through its mapped hook point; -1 when there's none.
     */
    static String speedWhy;

    /**
     * A simulated speed for listening tests while parked: DIR/sim-mph holds a speed in mph (the
     * sweep script writes it). Used only while the car's real speed is 0, and only for 10 minutes
     * after the file last changed, so it can never act while driving or be left on by mistake.
     */
    static final File SIM = new File(DIR, "sim-mph");
    static boolean simulating;

    static float speed() {
        float real = realSpeed();
        boolean sim = false;
        float out = real;
        if (SIM.isFile() && real == 0 && System.currentTimeMillis() - SIM.lastModified() < 10 * 60 * 1000L) {
            try {
                double mph = Double.parseDouble(read(SIM).trim());
                if (mph >= 0 && mph <= 120) {
                    out = (float) (mph / 0.621371);
                    sim = true;
                }
            } catch (Exception e) {
                // not a number: no simulation
            }
        }
        if (sim != simulating) log(sim ? "speed: SIMULATED (parked test), " + Math.round(Config.mph(out)) + " mph" : "speed: the car's again");
        simulating = sim;
        return out;
    }

    static float realSpeed() {
        try {
            double v = com.carsound.Hmgapid.vehicle("speed");
            if (speedWhy != null) {
                log("speed: from HMG-API again");
                speedWhy = null;
            }
            return Double.isNaN(v) ? -1 : (float) v;
        } catch (Throwable t) {
            String why = String.valueOf(t.getMessage());
            if (!why.equals(speedWhy)) log("speed: none from HMG-API (" + why + "): speed tuning waits");
            speedWhy = why;
            return -1;
        }
    }

    static Object audio() throws Exception {
        if (audio == null) audio = proxy(AUDIO, null);
        return audio;
    }

    // ---- phone projection's audio sessions: active tracks of those apps in Android's mixer

    static Set<Integer> pids() {
        Set<Integer> out = new HashSet<Integer>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        for (File d : procs) {
            String n = d.getName();
            if (!n.matches("[0-9]+")) continue;
            try {
                String cmd = read(new File(d, "cmdline")).replace('\0', ' ').trim();
                for (String p : PACKAGES) if (p.equals(cmd)) out.add(Integer.parseInt(n));
            } catch (Exception e) {
                // gone
            }
        }
        return out;
    }

    static void sessions() throws Exception {
        Set<Integer> pids = pids(), found = new HashSet<Integer>();
        Process pr = Runtime.getRuntime().exec(new String[] { "dumpsys", "media.audio_flinger" });
        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        boolean active = false;
        String l;
        while ((l = r.readLine()) != null) {
            if (l.contains(" active tracks")) { active = true; continue; }
            if (l.startsWith("Output thread") || l.startsWith("Input thread") || l.contains("underrun") || l.contains("Effect Chains")) active = false;
            if (!active) continue;
            String[] w = l.trim().split("\\s+");   // Name Client Type Fmt Chn-mask Session ...
            if (w.length < 6 || !w[0].matches("[0-9]+")) continue;
            try {
                int client = Integer.parseInt(w[1]), session = Integer.parseInt(w[5]);
                if (pids.contains(client) && session > 0) found.add(session);
            } catch (NumberFormatException e) {
                // a header
            }
        }
        r.close();
        pr.waitFor();
        long now = System.currentTimeMillis();
        for (int s : found) {
            Fx f = fx.get(s);
            if (f == null) {
                try {
                    f = new Fx(s);
                    short[] range = f.eq.getBandLevelRange();
                    lo = range[0];
                    hi = range[1];
                    fx.put(s, f);
                    log("audio session " + s + ": equalizer and bass boost on it");
                } catch (Throwable t) {
                    log("audio session " + s + ": no effects (" + t + ")");
                    continue;
                }
            }
            f.seen = now;
        }
        for (Iterator<Map.Entry<Integer, Fx>> it = fx.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Fx> e = it.next();
            if (now - e.getValue().seen > 60000) {
                e.getValue().release();
                it.remove();
                log("audio session " + e.getKey() + ": gone, effects released");
            }
        }
    }

    static void apply() {
        double mph = speed < 0 ? -1 : Config.mph(speed);
        for (int k = 0; k < 3; k++) {
            double target = cfg.on ? cfg.speedAt(k, mph) : 0;
            now[k] += Math.max(-100, Math.min(100, target - now[k]));
        }
        for (Fx f : fx.values()) {
            try {
                if (!cfg.on) {
                    if (f.eq.getEnabled()) f.eq.setEnabled(false);
                    if (f.bb.getEnabled()) f.bb.setEnabled(false);
                    if (f.le != null && f.le.getEnabled()) f.le.setEnabled(false);
                    continue;
                }
                short n = f.eq.getNumberOfBands();
                for (short i = 0; i < n && i < 5; i++) {
                    double mB = cfg.bands[i] + (i == 0 ? now[Config.BASS] : i >= 3 ? now[Config.TREBLE] : 0);
                    short want = (short) Math.max(lo, Math.min(hi, Math.round(mB)));
                    if (f.eq.getBandLevel(i) != want) f.eq.setBandLevel(i, want);
                }
                if (f.le != null) {
                    int gain = (int) Math.round(now[Config.VOL]);
                    if (Math.abs(f.le.getTargetGain() - gain) >= 10) f.le.setTargetGain(gain);
                    if (f.le.getEnabled() != (gain > 0)) f.le.setEnabled(gain > 0);
                }
                if (!f.eq.getEnabled()) f.eq.setEnabled(true);
                if (f.bb.getStrengthSupported() && f.bb.getRoundedStrength() != cfg.boost) f.bb.setStrength((short) cfg.boost);
                if (f.bb.getEnabled() != (cfg.boost > 0)) f.bb.setEnabled(cfg.boost > 0);
            } catch (Throwable t) {
                log("session " + f.session + ": " + t);
            }
        }
    }

    static void dsc() {
        String want = cfg.on ? cfg.dsc : "leave";
        if (want.equals(dscApplied)) return;
        try {
            if ("leave".equals(want)) {
                restoreDsc();
            } else {
                Object au = audio();
                if (dscOriginal < 0) dscOriginal = savedDsc();
                if (dscOriginal < 0) {   // the owner's own setting, kept in a file so a restart can't lose it
                    dscOriginal = (Integer) call(au, "getSoundSetting", DSC);
                    write(DSC_FILE, String.valueOf(dscOriginal));
                }
                call(au, "setSound", DSC, "off".equals(want) ? 0 : 1);
                log("Bose DSC " + want + " (it was " + (dscOriginal == 1 ? "on" : "off") + ")");
            }
            dscApplied = want;
        } catch (Throwable t) {
            audio = null;
            log("Bose DSC: " + t);
        }
    }

    /** the owner's Bose DSC from before Car Sound changed it (DSC_FILE), or -1 */
    static int savedDsc() {
        try {
            return Integer.parseInt(read(DSC_FILE).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static void write(File f, String text) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            o.write(text.getBytes("UTF-8"));
            o.close();
        } catch (Exception e) {
            log("can't write " + f + ": " + e);
        }
    }

    static void restoreDsc() {
        if (dscOriginal < 0) dscOriginal = savedDsc();   // changed before a restart that skipped the clean stop
        if (dscOriginal < 0) return;
        try {
            call(audio(), "setSound", DSC, dscOriginal);
            log("Bose DSC back " + (dscOriginal == 1 ? "on" : "off"));
            dscOriginal = -1;
            DSC_FILE.delete();
        } catch (Throwable t) {
            log("Bose DSC restore: " + t);
        }
    }

    static void status(String state) {
        try {
            JSONObject s = new JSONObject();
            s.put("state", state != null ? state : cfg.on ? "on" : "off");
            s.put("time", System.currentTimeMillis());
            s.put("speed_kmh", speed);
            s.put("sim", simulating);
            JSONArray ss = new JSONArray();
            for (int k : fx.keySet()) ss.put(k);
            s.put("sessions", ss);
            s.put("speed_vol_mB", Math.round(now[Config.VOL]));
            s.put("speed_bass_mB", Math.round(now[Config.BASS]));
            s.put("speed_treble_mB", Math.round(now[Config.TREBLE]));
            s.put("dsc", dscApplied);
            File tmp = new File(DIR, "status.json.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(s.toString().getBytes("UTF-8"));
            o.close();
            tmp.setReadable(true, false);
            tmp.renameTo(new File(Config.STATUS));
        } catch (Throwable t) {
            // no status this second
        }
    }
}
