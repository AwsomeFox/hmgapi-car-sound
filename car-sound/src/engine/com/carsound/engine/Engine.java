package com.carsound.engine;

import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Visualizer;
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
 * (Android Auto, CarPlay), volume, bass and treble that follow the car's speed, and, on a car
 * with the Bose amplifier, its Dynamic Speed Compensation switch. HMG-API runs it as root through
 * svc/engine.sh (app_process).
 *
 * Clip guard: Android 4.4's equalizer already turns its own output down by its largest boost,
 * and the loudness enhancer (the speed volume) is a 7:1 compressor with a hard clamp at full
 * scale, so clipping can only come from the clamp. A Visualizer, created last so it runs last in
 * each session's chain, measures the peak that leaves Car Sound four times a second; while it
 * reaches full scale the guard backs the loudness enhancer's gain off 1 dB a second (up to 6 dB,
 * below 0 when there's no speed volume), and gives it back slowly once the peaks stay clear.
 *
 * Settings: HMG-API's file for the mod (/data/hmgapi/settings/car-sound.json, the owner's
 * values only), read again whenever it changes. Status for the app: Config.STATUS. Stopping
 * (svc/engine.sh's trap writes "stop" next to it): effects released, so the audio is as it
 * was, and Bose DSC put back.
 *
 * Other cars: the equalizer follows the bands the car's equalizer has (Config.eqAt), and the
 * car's speed compensation (Amp's profile: Bose DSC 47, or SDVC 43 on other amplifiers) is only
 * touched when the car says it has it (getSoundAvailability, as the car's own Sound settings
 * ask), since on another amplifier that setting may not exist.
 */
public final class Engine {
    static final String SETTINGS = "/data/hmgapi/settings/" + Config.MOD + ".json";
    static final File DIR = new File(Config.STATUS).getParentFile();
    static final String[] PACKAGES = { "com.google.android.projection.sink", "com.daudio.app.dipo" };
    static final String AUDIO = "com.hkmc.media.IMicomAudioService";
    /** the owner's Bose DSC from before Car Sound changed it (survives restarts) */
    static final File DSC_FILE = new File(DIR, "dsc-original");
    /** the car's own speed compensation: Amp's profile (BOSE_DSCMODE 47 on Bose, SDVC 43 on others), 0 off */
    static int speedComp() {
        return Amp.profile != null ? Amp.profile.optInt("speed_comp", 47) : 47;
    }
    /** recheck whether the car has that setting this often (ms): the micom can answer late after a start */
    static final long BOSE_RECHECK = 60000, BOSE_RETRY = 5000;
    static final SimpleDateFormat T = new SimpleDateFormat("MM-dd HH:mm:ss");

    static final class Fx {
        final int session;
        final Equalizer eq;
        final BassBoost bb;
        LoudnessEnhancer le;   // the speed tuning's volume: louder with a limiter, so it doesn't clip
        final double[] hz;     // the centre of each of the car's equalizer bands
        final short lo, hi;    // and the levels they take (mB)
        Visualizer vis;        // last in the chain: the peak leaving Car Sound
        long seen;

        Fx(int session) {
            this.session = session;
            eq = new Equalizer(Integer.MAX_VALUE, session);
            bb = new BassBoost(Integer.MAX_VALUE, session);
            short n = eq.getNumberOfBands();
            hz = new double[n];
            for (short i = 0; i < n; i++) hz[i] = eq.getCenterFreq(i) / 1000.0;   // mHz
            short[] range = eq.getBandLevelRange();
            lo = range[0];
            hi = range[1];
            try {
                le = new LoudnessEnhancer(session);
            } catch (Throwable t) {
                le = null;
                log("audio session " + session + ": no loudness enhancer (" + t + "): speed volume off");
            }
            try {
                vis = new Visualizer(session);
                vis.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
                vis.setEnabled(true);
            } catch (Throwable t) {
                vis = null;
                log("audio session " + session + ": no output meter (" + t + "): clip guard off");
            }
        }

        void release() {
            try { eq.release(); } catch (Throwable t) { }
            try { bb.release(); } catch (Throwable t) { }
            try { if (le != null) le.release(); } catch (Throwable t) { }
            try { if (vis != null) vis.release(); } catch (Throwable t) { }
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
    /** the clip guard: how far the loudness enhancer's gain is backed off now (mB) */
    static double guard, guardMost;
    /** this second's highest output peak (mB, 0 = full scale), whether it reached full scale, and the last second that did */
    static int secPeak = -9600, lastPeak = -9600;
    static boolean secClip;
    static long lastClip;
    static final java.util.ArrayDeque<Long> clips = new java.util.ArrayDeque<Long>();
    static final int CLIP_MB = -10;      // a peak within 0.1 dB of full scale: the clamp
    static final int GUARD_MAX = 600;
    /** the car has its speed compensation setting (Amp's profile: Bose DSC or SDVC; getSoundAvailability); null until it has answered */
    static Boolean bose;
    static int boseType = -1;
    static long boseAt;
    static String boseWhy;
    /** why phone audio can't be tuned (the last session's effects failed), or null */
    static String fxError;

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
                meter();   // four times a second: the Visualizer keeps only about the last half second
                if (tick % 4 == 0) {
                    try {
                        settings();
                        speed = speed();
                        if (tick % 8 == 0) sessions();
                        clipGuard();
                        smooth();
                        Amp.tick(cfg, now[Config.BASS], now[Config.TREBLE], carPlay, realKmh == 0);
                        apply();
                        bose();
                        dsc();
                        Report.check(cfg);
                        status(null);
                    } catch (Throwable t) {
                        log("tick: " + t);
                    }
                }
                tick++;
                Thread.sleep(250);
            }
        } finally {
            for (Fx f : fx.values()) f.release();
            fx.clear();
            restoreDsc();
            Amp.restore();
            status("stopped");
            stop.delete();
            log("engine stopped: effects released, Bose DSC as it was");
        }
        System.exit(0);
    }

    /** the last log lines, for the car report */
    static final java.util.LinkedList<String> recent = new java.util.LinkedList<String>();

    static synchronized void log(String s) {
        String l = T.format(new Date()) + " " + s;
        System.out.println(l);
        recent.add(l);
        if (recent.size() > 200) recent.removeFirst();
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

    static float realKmh = -1;

    static float speed() {
        float real = realSpeed();
        realKmh = real;
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

    /** whether the phone audio playing is CarPlay's (its volume group is another) */
    static boolean carPlay;

    static Map<Integer, String> pids() {
        Map<Integer, String> out = new HashMap<Integer, String>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        for (File d : procs) {
            String n = d.getName();
            if (!n.matches("[0-9]+")) continue;
            try {
                String cmd = read(new File(d, "cmdline")).replace('\0', ' ').trim();
                for (String p : PACKAGES) if (cmd.equals(p) || cmd.startsWith(p + ":")) out.put(Integer.parseInt(n), p);   // or one of its processes
            } catch (Exception e) {
                // gone
            }
        }
        return out;
    }

    static void sessions() throws Exception {
        Map<Integer, String> pids = pids();
        Set<Integer> found = new HashSet<Integer>();
        boolean cp = false, any = false;
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
                if (pids.containsKey(client) && session > 0) {
                    found.add(session);
                    any = true;
                    if (PACKAGES[1].equals(pids.get(client))) cp = true;
                }
            } catch (NumberFormatException e) {
                // a header
            }
        }
        r.close();
        pr.waitFor();
        if (any) carPlay = cp;
        long now = System.currentTimeMillis();
        for (int s : found) {
            Fx f = fx.get(s);
            if (f == null) {
                try {
                    f = new Fx(s);
                    fx.put(s, f);
                    fxError = null;
                    StringBuilder b = new StringBuilder();
                    for (double c : f.hz) b.append(b.length() == 0 ? "" : ", ").append(Math.round(c)).append(" Hz");
                    log("audio session " + s + ": equalizer (" + b + "; " + f.lo / 100 + " to " + f.hi / 100 + " dB) and bass boost on it");
                } catch (Throwable t) {
                    if (!String.valueOf(t).equals(fxError)) log("audio session " + s + ": no effects (" + t + ")");
                    fxError = String.valueOf(t);
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

    /** the speed curves now, each moving at most 1 dB a second */
    static void smooth() {
        double mph = speed < 0 ? -1 : Config.mph(speed);
        for (int k = 0; k < 3; k++) {
            double target = cfg.on ? cfg.speedAt(k, mph) : 0;
            now[k] += Math.max(-100, Math.min(100, target - now[k]));
        }
    }

    static void apply() {
        for (Fx f : fx.values()) {
            try {
                if (!cfg.on) {
                    if (f.eq.getEnabled()) f.eq.setEnabled(false);
                    if (f.bb.getEnabled()) f.bb.setEnabled(false);
                    if (f.le != null && f.le.getEnabled()) f.le.setEnabled(false);
                    continue;
                }
                for (short i = 0; i < f.hz.length; i++) {
                    double c = f.hz[i];
                    // the lifts (speed curves, loudness) Android makes: all of them, or none when the amplifier makes them
                    double mB = Config.eqAt(cfg.bands, c) + (c < Config.BASS_HZ ? Amp.restB : 0) + (c >= Config.TREBLE_HZ ? Amp.restT : 0);
                    short want = (short) Math.max(f.lo, Math.min(f.hi, Math.round(mB)));
                    if (f.eq.getBandLevel(i) != want) f.eq.setBandLevel(i, want);
                }
                if (f.le != null) {
                    int gain = (int) Math.round(now[Config.VOL] - guard);
                    if (Math.abs(f.le.getTargetGain() - gain) >= 10) f.le.setTargetGain(gain);
                    if (f.le.getEnabled() != (gain != 0)) f.le.setEnabled(gain != 0);
                }
                if (!f.eq.getEnabled()) f.eq.setEnabled(true);
                if (f.bb.getStrengthSupported() && f.bb.getRoundedStrength() != cfg.boost) f.bb.setStrength((short) cfg.boost);
                if (f.bb.getEnabled() != (cfg.boost > 0)) f.bb.setEnabled(cfg.boost > 0);
            } catch (Throwable t) {
                log("session " + f.session + ": " + t);
            }
        }
    }

    // ---- the clip guard

    static void meter() {
        Visualizer.MeasurementPeakRms m = new Visualizer.MeasurementPeakRms();
        for (Fx f : fx.values()) {
            if (f.vis == null) continue;
            try {
                if (f.vis.getMeasurementPeakRms(m) != Visualizer.SUCCESS) continue;
                if (m.mPeak > -9600) {
                    secPeak = Math.max(secPeak, m.mPeak);
                    if (m.mPeak >= CLIP_MB) secClip = true;
                }
            } catch (Throwable t) {
                // released meanwhile
            }
        }
    }

    /** once a second: this second's peak; the guard backs off 1 dB a clipped second, and gives 0.25 dB a second back after 5 clear seconds */
    static void clipGuard() {
        long t = System.currentTimeMillis();
        lastPeak = secPeak;
        boolean clipped = secClip;
        secPeak = -9600;
        secClip = false;
        boolean shaping = cfg.on && (now[Config.VOL] > 0 || cfg.boost > 0 || maxBand() > 0);
        if (clipped) {
            clips.addLast(t);
            lastClip = t;
        }
        while (!clips.isEmpty() && t - clips.peekFirst() > 60000) clips.removeFirst();
        double was = guard;
        if (!cfg.on || !cfg.guard || !shaping) guard = 0;
        else if (clipped) guard = Math.min(GUARD_MAX, guard + 100);
        else if (t - lastClip > 5000) guard = Math.max(0, guard - 25);
        if (guard > 0 && was == 0) log("clip guard: output at full scale, backing off");
        if (guard > guardMost) guardMost = guard;
        if (guard == 0 && was > 0) {
            log("clip guard: clear again (it backed off up to " + Math.round(guardMost / 10.0) / 10.0 + " dB)");
            guardMost = 0;
        }
    }

    static int maxBand() {
        int m = Integer.MIN_VALUE;
        for (int b : cfg.bands) m = Math.max(m, b);
        return m;
    }

    /**
     * Does the car have its speed compensation setting (speedComp(): Bose DSC 47 on a Bose
     * amplifier, SDVC 43 on others)? Asked as the car's own Sound settings ask:
     * getSoundAvailability on its audio service. Every 5 s until the car answers, then every
     * minute (a micom that answered "no" while starting up gets another chance), and again
     * when the profile names another setting.
     */
    static void bose() {
        long t = System.currentTimeMillis();
        if (boseType != speedComp()) {
            bose = null;
            boseType = speedComp();
        }
        if (t - boseAt < (bose == null ? BOSE_RETRY : BOSE_RECHECK)) return;
        boseAt = t;
        try {
            Object au = audio();
            boolean has = ((Number) call(au, "getSoundAvailability", speedComp())).intValue() > 0;
            if (bose == null || bose != has) {
                String amp = "";
                try {
                    amp = ", amplifier type " + call(au, "getAmpType");
                } catch (Throwable e) {
                    // not on this firmware: only for the log
                }
                log(has ? "the car has its speed compensation (setting " + speedComp() + ")" + amp + ": its switch is on the Car page"
                        : "the car has no speed compensation setting " + speedComp() + amp + ": Car Sound leaves it alone");
            }
            bose = has;
            boseWhy = null;
        } catch (Throwable e) {
            audio = null;
            String why = String.valueOf(e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e);
            if (!why.equals(boseWhy)) log("can't ask the car about its speed compensation (" + why + ")" + (bose == null ? ": its switch waits" : ""));
            boseWhy = why;
        }
    }

    static void dsc() {
        if (bose == null) return;   // not known yet: change nothing
        String want = cfg.on && bose ? cfg.dsc : "leave";   // "leave" still puts back a change made before (DSC_FILE)
        if (want.equals(dscApplied)) return;
        try {
            if ("leave".equals(want)) {
                restoreDsc();
            } else {
                Object au = audio();
                if (dscOriginal < 0) dscOriginal = savedDsc();
                if (dscOriginal < 0) {   // the owner's own setting, kept in a file so a restart can't lose it
                    dscOriginal = (Integer) call(au, "getSoundSetting", speedComp());
                    write(DSC_FILE, String.valueOf(dscOriginal));
                }
                int on = Amp.profile != null ? Amp.profile.optInt("speed_comp_on", 1) : 1;
                call(au, "setSound", speedComp(), "off".equals(want) ? 0 : dscOriginal > 0 ? dscOriginal : on);
                log("speed compensation (setting " + speedComp() + ") " + want + " (it was " + dscOriginal + ")");
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
            call(audio(), "setSound", speedComp(), dscOriginal);
            log("speed compensation (setting " + speedComp() + ") back to " + dscOriginal);
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
            s.put("peak_mB", lastPeak);
            s.put("clip_s", clips.size());
            s.put("guard_mB", Math.round(guard));
            Amp.status(s);
            Report.status(s);
            if (bose != null) s.put("speed_comp_ok", bose.booleanValue());
            if (fxError != null && fx.isEmpty()) s.put("fx_error", fxError);
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
