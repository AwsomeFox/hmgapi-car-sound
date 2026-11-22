package com.carsound.engine;

import com.carsound.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/**
 * The car's amplifier: its own Bass, Mid and Treble (sound settings 8, 9 and 10, 0-20 with 10 the
 * centre), which it applies after Android to every source, with headroom Android doesn't have.
 *
 * Car Sound uses them two ways:
 *   - "flat" (Config.tone): the owner's tone held at the centre while Car Sound is on;
 *   - lifts (Config.lift "amp"): the speed curves' bass and treble and the low-volume loudness
 *     lift as whole amplifier steps on top of the owner's tone, instead of Android's equalizer.
 * The owner's own tone is the base: kept in BASE_FILE so an unclean restart can't lose it,
 * followed when it's changed in the car's Sound settings, and put back when Car Sound stops
 * adding anything.
 *
 * Everything that differs between cars is in a profile, by amplifier type (getAmpType(): 0 the
 * head unit's own, 1 external Mobis, 2 external Harman, 3 external Bose): how many dB a step is,
 * how many steps at most, how they shrink at high volume, how often the amplifier may be
 * written, how it's written, the volume groups, the car's own speed compensation. builtIn() has
 * the values from car tests and car reports; PROFILE_FILE (written by amp.sh) overrides any of
 * them for a test, without a rebuild.
 */
final class Amp {
    static final int BASS = 8, MIDDLE = 9, TREBLE = 10, CENTRE = 10, MAX = 20;
    static final File BASE_FILE = new File(Engine.DIR, "tone-original");
    static final File PROFILE_FILE = new File(Engine.DIR, "profile.json");
    /** amp.sh's test: "<bass> <mid> <treble>" steps held on top of the owner's tone, parked, for 10 minutes */
    static final File TEST_FILE = new File(Engine.DIR, "amp-test");

    static int ampType = -2;   // -2 not read yet, -1 unreadable
    static JSONObject profile;
    static long profileAt = -1;
    static int profileType = -3;
    static int[] cur;          // the car's tone now
    static int[] base;         // the owner's tone, while Car Sound changes it
    static int[] sent;         // what Car Sound last sent
    static int[] steps = new int[3];   // the steps Car Sound adds now: bass, mid, treble
    static int[] liftSteps = new int[2], liftWant = new int[2];   // of them, the speed and loudness lifts: bass, treble
    static long sentAt;
    static int writes;         // amplifier writes since the engine started (each may be saved by the car)
    static int volume = -1;    // the playing source's volume step (0-45 on the 260507 software)
    static double loudB, loudT;        // the loudness lift now (mB), smoothed
    static double restB, restT;        // what Android's equalizer adds for the lifts (mB)
    static boolean testing;
    static String why;         // why the amplifier isn't used, for the status
    /** the equalizer Android makes now (mB at Config.FREQ): the owner's bands, or what's left of them after the amplifier's steps (never a boost) */
    static int[] bandsNow = new int[5];
    static int[] presetSteps = new int[3];   // the equalizer's shape as amplifier steps: bass, mid, treble
    static String splitOf = "";
    /** the car's volume per stream type: the owner's (base), the steps Car Sound adds for speed (off), what it last sent */
    static final java.util.Map<Integer, int[]> vols = new java.util.HashMap<Integer, int[]>();
    static final File VOL_FILE = new File(Engine.DIR, "volume-state");
    static boolean volsLoaded;
    static int stream = -1, volSteps, volWrites;
    static boolean ampVolume;  // the speed volume is made with the car's volume now (so the loudness enhancer isn't)

    // ---- the profile

    static JSONObject builtIn(int type) {
        JSONObject p = new JSONObject();
        try {
            p.put("name", type == 3 ? "Bose" : type == 2 ? "Harman" : type == 1 ? "Mobis amplifier" : type == 0 ? "head unit amplifier" : "unknown amplifier");
            // nothing below is measured: the Bose values were tuned by ear on a 2024 Ioniq 5 Limited (so "auto"
            // uses that amplifier); the others are the same guesses until a car test or car report says otherwise
            p.put("verified", type == 3);
            p.put("bass_step_mB", 100);      // how much one Bass step lifts its band
            p.put("treble_step_mB", 100);
            p.put("max_bass_steps", type == 0 ? 4 : 6);
            p.put("max_treble_steps", 4);
            p.put("taper_from", 28);         // from this volume step the maximum shrinks...
            p.put("taper_to", 40);           // ...to taper_steps at this one, where the amplifier has the least headroom
            p.put("taper_steps", type == 0 ? 0 : 2);
            p.put("min_interval_s", 8);      // between lifts up (lowering is never held back)
            p.put("hysteresis", 0.3);        // in steps, so a lift between two steps doesn't flicker
            p.put("method", "bmt");          // "bmt": setBassMidTreb, like the car's Sound page; "sound": setSound per setting
            p.put("type_offset", 0);         // added to the setting type with "sound" (to try a write the car doesn't save)
            p.put("android_rest", false);    // also make the part between two steps with Android's equalizer
            p.put("vol_group_aa", 3);        // VOLUMEGROUP_AAMUSIC
            p.put("vol_group_cp", 4);        // VOLUMEGROUP_DIPOMUSIC
            // the equalizer's boosts as amplifier steps (Config.lift "amp"): how far each control reaches
            // into the equalizer's five bands (60, 230, 910, 3600, 14000 Hz), in percent of a step
            p.put("mid_step_mB", 100);
            p.put("bass_shape", new JSONArray("[100,55,10,0,0]"));
            p.put("mid_shape", new JSONArray("[0,30,100,30,0]"));
            p.put("treble_shape", new JSONArray("[0,0,0,60,100]"));
            p.put("max_preset_steps", 6);    // the most steps the equalizer's own shape may take of each control
            // the speed volume as the car's own volume steps
            p.put("vol_step_mB", 100);       // how much louder one volume step is
            p.put("max_vol_steps", 14);
            p.put("vol_cap", 38);            // never raise the volume past this step (of 45)
            p.put("vol_ui", 0);              // setVolume's UI option: 0 no pop-up (the knob sends 9)
            p.put("speed_comp", type == 3 ? 47 : 43);   // BOSE_DSCMODE, else SDVC
            p.put("speed_comp_name", type == 3 ? "Bose Dynamic Speed Compensation" : "Speed Dependent Volume");
            p.put("speed_comp_on", type == 3 ? 1 : 2);  // the value "On" sends
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** the built-in profile for this car's amplifier, with PROFILE_FILE's values over it */
    static void loadProfile() {
        if (ampType < 0) {   // tried again each second until the car's audio service answers
            try {
                ampType = (Integer) Engine.call(Engine.audio(), "getAmpType");
                Engine.log("amplifier type " + ampType);
            } catch (Throwable t) {
                if (ampType == -2) Engine.log("amplifier type: " + t);
                Engine.audio = null;
                ampType = -1;
            }
        }
        long at = PROFILE_FILE.isFile() ? PROFILE_FILE.lastModified() : 0;
        if (profile != null && at == profileAt && ampType == profileType) return;
        profileAt = at;
        profileType = ampType;
        JSONObject p = builtIn(ampType);
        if (at != 0) {
            try {
                JSONObject o = new JSONObject(Engine.read(PROFILE_FILE));
                for (Iterator<?> it = o.keys(); it.hasNext(); ) {
                    String k = (String) it.next();
                    p.put(k, o.get(k));
                }
                Engine.log("amplifier profile: " + p + " (with the test values in " + PROFILE_FILE + ")");
            } catch (Exception e) {
                Engine.log("can't read " + PROFILE_FILE + " (" + e + "): the built-in profile");
            }
        } else if (profile != null) {
            Engine.log("amplifier profile: the built-in one again");
        }
        profile = p;
    }

    static int pi(String k) {
        return profile.optInt(k);
    }

    // ---- the car

    static int[] readTone() {
        try {
            Object au = Engine.audio();
            return new int[] { (Integer) Engine.call(au, "getSoundSetting", BASS), (Integer) Engine.call(au, "getSoundSetting", MIDDLE), (Integer) Engine.call(au, "getSoundSetting", TREBLE) };
        } catch (Throwable t) {
            Engine.audio = null;
            return null;
        }
    }

    static int readVolume(boolean carPlay) {
        try {
            return (Integer) Engine.call(Engine.audio(), "getVolumeOfVolumeGroup", pi(carPlay ? "vol_group_cp" : "vol_group_aa"));
        } catch (Throwable t) {
            Engine.audio = null;
            return -1;
        }
    }

    static void send(int[] v, String reason) throws Exception {
        Object au = Engine.audio();
        if ("sound".equals(profile.optString("method"))) {
            int off = pi("type_offset");
            int[] types = { BASS, MIDDLE, TREBLE };
            for (int i = 0; i < 3; i++) if (sent == null || sent[i] != v[i]) Engine.call(au, "setSound", types[i] + off, v[i]);
        } else {
            Engine.call(au, "setBassMidTreb", v[0], v[1], v[2]);
        }
        writes++;
        sent = v.clone();
        cur = v.clone();
        sentAt = System.currentTimeMillis();
        Engine.log("car tone " + tone(v) + " (" + reason + ")");
    }

    static String tone(int[] v) {
        return v == null ? "?" : "bass " + signed(v[0] - CENTRE) + ", mid " + signed(v[1] - CENTRE) + ", treble " + signed(v[2] - CENTRE);
    }

    static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    // ---- the equalizer's shape, split between the amplifier and Android

    /**
     * The owner's five bands as amplifier steps (bass, mid, treble; each 0..max_preset_steps) plus
     * what Android's equalizer still has to do, shifted so its highest band is 0: Android 4.4's
     * equalizer turns its whole output down by its largest boost, so a boost there only makes
     * the rest quieter, while a cut costs nothing. Every combination is tried; the best has the
     * least level error (the residue's highest band nearest 0), then the fewest steps.
     */
    static void split(int[] bands) {
        String key = java.util.Arrays.toString(bands) + profile;
        if (key.equals(splitOf)) return;
        splitOf = key;
        int max = pi("max_preset_steps");
        int[] step = { pi("bass_step_mB"), pi("mid_step_mB"), pi("treble_step_mB") };
        double[][] shape = new double[3][5];
        String[] names = { "bass_shape", "mid_shape", "treble_shape" };
        for (int k = 0; k < 3; k++) {
            JSONArray a = profile.optJSONArray(names[k]);
            for (int i = 0; i < 5; i++) shape[k][i] = a == null ? 0 : a.optDouble(i, 0) / 100.0;
        }
        double best = Double.MAX_VALUE;
        int[] bestSteps = new int[3];
        double[] bestRest = new double[5];
        for (int b = 0; b <= max; b++) for (int m = 0; m <= max; m++) for (int t = 0; t <= max; t++) {
            double[] r = new double[5];
            double top = -1e9;
            for (int i = 0; i < 5; i++) {
                r[i] = bands[i] - b * step[0] * shape[0][i] - m * step[1] * shape[1][i] - t * step[2] * shape[2][i];
                top = Math.max(top, r[i]);
            }
            double cost = Math.abs(top) * 10 + (b + m + t) * 20;
            for (int i = 0; i < 5; i++) cost += Math.max(0, top - r[i] - 1500) * 100;   // deeper than the equalizer can cut
            if (cost < best) {
                best = cost;
                bestSteps = new int[] { b, m, t };
                for (int i = 0; i < 5; i++) bestRest[i] = r[i] - top;
            }
        }
        presetSteps = bestSteps;
        int[] rest = new int[5];
        for (int i = 0; i < 5; i++) rest[i] = (int) Math.round(Math.max(-1500, bestRest[i]));
        splitRest = rest;
        Engine.log("equalizer " + java.util.Arrays.toString(bands) + " mB as amplifier steps bass +" + bestSteps[0] + ", mid +" + bestSteps[1] + ", treble +" + bestSteps[2]
                + ", and Android's equalizer " + java.util.Arrays.toString(rest) + " mB");
    }

    static int[] splitRest = new int[5];

    // ---- the speed volume as the car's own volume steps

    static void loadVols() {
        if (volsLoaded) return;
        volsLoaded = true;
        try {
            for (String l : Engine.read(VOL_FILE).split("\n")) {
                String[] w = l.trim().split("\\s+");
                if (w.length == 4) vols.put(Integer.parseInt(w[0]), new int[] { Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]) });
            }
        } catch (Exception e) {
            // none yet
        }
    }

    static void saveVols() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<Integer, int[]> e : vols.entrySet()) {
            if (e.getValue()[1] != 0) b.append(e.getKey()).append(' ').append(e.getValue()[0]).append(' ').append(e.getValue()[1]).append(' ').append(e.getValue()[2]).append('\n');
        }
        if (b.length() == 0) VOL_FILE.delete();
        else Engine.write(VOL_FILE, b.toString());
    }

    /**
     * The speed volume (mB) as steps of the car's own volume for the source that's playing, like
     * the knob (setVolume(stream, level, UI option)): the owner's volume stays the base, found
     * again whenever the knob moves it; a step a second at most; never past vol_cap; nothing
     * while muted or at volume 0. want 0 gives the steps back. Returns the owner's volume, or -1.
     */
    static int volume(double wantMB, boolean use) {
        loadVols();
        try {
            Object au = Engine.audio();
            stream = (Integer) Engine.call(au, "getCurStreamType");
            int cur = (Integer) Engine.call(au, "getVolume", stream, 0);
            int[] st = vols.get(stream);
            if (st == null) {
                st = new int[] { cur, 0, cur };
                vols.put(stream, st);
            } else if (cur != st[2]) {   // the knob (or the car) moved it: the owner's volume is that, less the steps added
                st[0] = Math.max(0, cur - st[1]);
                st[2] = cur;
            }
            boolean muted = Boolean.TRUE.equals(Engine.call(au, "getUserMuteState"));
            int max = Math.max(0, Math.min(pi("max_vol_steps"), pi("vol_cap") - st[0]));
            int want = use && !muted && st[0] > 0 ? stepsFor(wantMB, pi("vol_step_mB"), st[1], max) : 0;
            ampVolume = use;
            if (want != st[1] && !muted) {
                int next = st[1] + (want > st[1] ? 1 : -1);
                Engine.call(au, "setVolume", stream, st[0] + next, pi("vol_ui"));
                volWrites++;
                st[1] = next;
                st[2] = st[0] + next;
                saveVols();
            }
            volSteps = st[1];
            return st[0];
        } catch (Throwable t) {
            Engine.audio = null;
            return -1;
        }
    }

    // ---- once a second

    /** the loudness lift's share at this volume: 1 at or below cfg.loudFull, 0 at or above cfg.loudNone */
    static double loudShare(Config cfg, int vol) {
        if (vol < 0 || cfg.loudNone <= cfg.loudFull) return 0;
        return Math.max(0, Math.min(1, (cfg.loudNone - vol) / (double) (cfg.loudNone - cfg.loudFull)));
    }

    /** the most steps at this volume: max, shrinking linearly to taper_steps from taper_from to taper_to */
    static int maxAt(int max, int vol) {
        int from = pi("taper_from"), to = pi("taper_to"), low = Math.min(max, pi("taper_steps"));
        if (vol <= from || to <= from) return max;
        if (vol >= to) return low;
        return (int) Math.floor(max - (max - low) * (vol - from) / (double) (to - from));
    }

    /** a lift (mB) as whole steps, kept at the last count while it's within the hysteresis of it */
    static int stepsFor(double mB, int stepMB, int last, int max) {
        if (stepMB <= 0) return 0;
        double x = mB / stepMB;
        int n = Math.abs(x - last) < 0.5 + profile.optDouble("hysteresis", 0.3) ? last : (int) Math.round(x);
        return Math.max(0, Math.min(max, n));
    }

    /**
     * @param speedB the speed curves' bass and treble now (mB, already smoothed)
     */
    static void tick(Config cfg, double speedV, double speedB, double speedT, boolean carPlay, boolean parked) {
        loadProfile();
        cur = readTone();
        boolean amp = cfg.on && wanted(cfg);
        int own = volume(speedV, amp);   // the owner's volume for the source playing, without the speed steps
        volume = own >= 0 ? own : readVolume(carPlay);

        // the loudness lift, gliding 2 dB a second as the volume changes
        // by the volume as it is now, speed steps included: at speed the lift fades as the volume rises
        double share = cfg.on && cfg.loudOn ? loudShare(cfg, volume < 0 ? -1 : volume + volSteps) : 0;
        loudB += Math.max(-200, Math.min(200, cfg.loudBass * share - loudB));
        loudT += Math.max(-200, Math.min(200, cfg.loudTreble * share - loudT));
        double wantB = speedB + loudB, wantT = speedT + loudT;

        // the owner changed the tone in the car's Sound settings since Car Sound last set it: that's the new base
        if (sent != null && cur != null && !java.util.Arrays.equals(cur, sent)) {
            base = new int[3];
            for (int i = 0; i < 3; i++) base[i] = clamp(cur[i] - steps[i]);   // less the lift Car Sound adds now
            Engine.write(BASE_FILE, base[0] + "," + base[1] + "," + base[2]);
            Engine.log("car tone changed in the car (" + tone(cur) + "): the owner's tone is now " + tone(base));
            sent = cur.clone();
        }

        int[] test = test(parked);
        testing = test != null;
        boolean useAmp = amp;
        why = !"amp".equals(cfg.lift) ? null : ampType < 0 ? "the amplifier type can't be read" : null;
        usingAmp = amp;
        int[] want = new int[3];
        if (useAmp && !testing) {
            split(cfg.bands);
            bandsNow = splitRest;
        } else {
            bandsNow = cfg.bands;
            presetSteps = new int[3];
            splitOf = "";
        }
        if (testing) {
            want = test;
        } else if (useAmp) {
            // the lifts (speed, loudness) on top of the equalizer's own steps; the lifts shrink at high volume, the shape doesn't
            int lb = stepsFor(wantB, pi("bass_step_mB"), liftSteps[0], maxAt(pi("max_bass_steps"), volume));
            int lt = stepsFor(wantT, pi("treble_step_mB"), liftSteps[1], maxAt(pi("max_treble_steps"), volume));
            liftWant = new int[] { lb, lt };
            want[0] = presetSteps[0] + lb;
            want[1] = presetSteps[1];
            want[2] = presetSteps[2] + lt;
        }
        boolean rest = profile.optBoolean("android_rest");
        restB = testing ? 0 : useAmp ? (rest ? wantB - want[0] * pi("bass_step_mB") : 0) : wantB;
        restT = testing ? 0 : useAmp ? (rest ? wantT - want[2] * pi("treble_step_mB") : 0) : wantT;

        try {
            boolean leave = !cfg.on || (!flat(cfg) && want[0] == 0 && want[1] == 0 && want[2] == 0);
            if (leave) {
                restore();
                steps = new int[3];
                liftSteps = new int[2];
                return;
            }
            if (base == null) base = savedBase();
            if (base == null) {   // the owner's own tone, kept in a file so a restart can't lose it
                if (cur == null) throw new IllegalStateException("can't read the car's tone");
                base = cur.clone();
                Engine.write(BASE_FILE, base[0] + "," + base[1] + "," + base[2]);
            }
            int[] from = flat(cfg) ? new int[] { CENTRE, CENTRE, CENTRE } : base;
            int[] target = { clamp(from[0] + want[0]), clamp(from[1] + want[1]), clamp(from[2] + want[2]) };
            if (sent != null && java.util.Arrays.equals(target, sent)) {
                steps = want;
                liftSteps = liftWant;
                return;
            }
            boolean up = sent == null || target[0] > sent[0] || target[1] > sent[1] || target[2] > sent[2];
            boolean held = up && sent != null && !testing && System.currentTimeMillis() - sentAt < pi("min_interval_s") * 1000L;
            if (held) return;   // steps stay as they were; tried again next second
            send(target, testing ? "amp.sh test" : "base " + tone(base) + (flat(cfg) ? ", from the centre" : "") + ", equalizer steps " + java.util.Arrays.toString(presetSteps)
                    + ", lifts bass " + liftWant[0] + ", treble " + liftWant[1] + " at volume " + volume);
            steps = want;
            liftSteps = liftWant;
        } catch (Throwable t) {
            Engine.audio = null;
            Engine.log("car tone: " + t);
        }
    }

    /** Car Sound sets the tone from the centre: asked for ("flat"), or it makes the equalizer's shape with the amplifier */
    static boolean flat(Config cfg) {
        return cfg.on && ("flat".equals(cfg.tone) || wanted(cfg));
    }

    /** the amplifier makes the boosts: asked for ("amp"), or left to Car Sound ("auto") on an amplifier whose profile is verified */
    static boolean wanted(Config cfg) {
        if (ampType < 0 || profile == null) return false;
        return "amp".equals(cfg.lift) || ("auto".equals(cfg.lift) && profile.optBoolean("verified"));
    }

    static boolean usingAmp;

    static int clamp(int v) {
        return Math.max(0, Math.min(MAX, v));
    }

    static int[] test(boolean parked) {
        if (!TEST_FILE.isFile() || !parked || System.currentTimeMillis() - TEST_FILE.lastModified() > 10 * 60 * 1000L) return null;
        try {
            String[] p = Engine.read(TEST_FILE).trim().split("\\s+");
            return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
        } catch (Exception e) {
            return null;
        }
    }

    static int[] savedBase() {
        try {
            String[] p = Engine.read(BASE_FILE).trim().split(",");
            return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
        } catch (Exception e) {
            return null;
        }
    }

    /** the speed steps of the car's volume given back at once (the engine stops) */
    static void restoreVolume() {
        try {
            int[] st = vols.get(stream);
            if (st == null || st[1] == 0) return;
            Object au = Engine.audio();
            int cur = (Integer) Engine.call(au, "getVolume", stream, 0);
            if (cur == st[2]) Engine.call(au, "setVolume", stream, st[0], pi("vol_ui"));
            Engine.log("car volume back to " + st[0] + " (it had " + st[1] + " speed steps)");
            st[1] = 0;
            st[2] = st[0];
            saveVols();
        } catch (Throwable t) {
            Engine.log("car volume restore: " + t);
        }
    }

    /** back to the owner's tone, unless it was changed in the car's Sound settings since */
    static void restore() {
        int[] was = base != null ? base : savedBase();
        if (was == null) return;
        try {
            int[] now = readTone();
            if (now != null && sent != null && !java.util.Arrays.equals(now, sent)) {
                Engine.log("car tone changed in the car since (" + tone(now) + "): left as it is");
            } else if (now == null || !java.util.Arrays.equals(now, was)) {
                send(was, "back to the owner's tone");
            }
            BASE_FILE.delete();
            base = null;
            sent = null;
        } catch (Throwable t) {
            Engine.log("car tone restore: " + t);
        }
    }

    static void status(JSONObject s) throws Exception {
        if (cur != null) s.put("car_tone", new JSONArray().put(cur[0] - CENTRE).put(cur[1] - CENTRE).put(cur[2] - CENTRE));
        s.put("amp_type", ampType);
        if (profile != null) {
            s.put("amp_name", profile.optString("name"));
            s.put("amp_verified", profile.optBoolean("verified"));
            s.put("amp_test_profile", profileAt > 0);
            s.put("speed_comp_name", profile.optString("speed_comp_name"));
        }
        s.put("volume", volume);
        s.put("amp_steps", new JSONArray().put(steps[0]).put(steps[1]).put(steps[2]));
        s.put("amp_eq_steps", new JSONArray().put(presetSteps[0]).put(presetSteps[1]).put(presetSteps[2]));
        s.put("android_eq_mB", new JSONArray().put(bandsNow[0]).put(bandsNow[1]).put(bandsNow[2]).put(bandsNow[3]).put(bandsNow[4]));
        s.put("vol_steps", volSteps);
        s.put("stream", stream);
        s.put("amp_writes", writes + volWrites);
        s.put("amp_on", usingAmp);
        s.put("amp_testing", testing);
        s.put("loud_mB", new JSONArray().put(Math.round(loudB)).put(Math.round(loudT)));
        if (why != null) s.put("amp_why", why);
    }
}
