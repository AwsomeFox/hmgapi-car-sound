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
    static long sentAt;
    static int writes;         // amplifier writes since the engine started (each may be saved by the car)
    static int volume = -1;    // the playing source's volume step (0-45 on the 260507 software)
    static double loudB, loudT;        // the loudness lift now (mB), smoothed
    static double restB, restT;        // what Android's equalizer adds for the lifts (mB)
    static boolean testing;
    static String why;         // why the amplifier isn't used, for the status

    // ---- the profile

    static JSONObject builtIn(int type) {
        JSONObject p = new JSONObject();
        try {
            p.put("name", type == 3 ? "Bose" : type == 2 ? "Harman" : type == 1 ? "Mobis amplifier" : type == 0 ? "head unit amplifier" : "unknown amplifier");
            // nothing below is measured yet: guesses until a car test or car report says otherwise
            p.put("verified", false);
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
    static void tick(Config cfg, double speedB, double speedT, boolean carPlay, boolean parked) {
        loadProfile();
        cur = readTone();
        volume = readVolume(carPlay);

        // the loudness lift, gliding 2 dB a second as the volume changes
        double share = cfg.on && cfg.loudOn ? loudShare(cfg, volume) : 0;
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
        boolean useAmp = cfg.on && "amp".equals(cfg.lift) && ampType >= 0;
        why = !"amp".equals(cfg.lift) ? null : ampType < 0 ? "the amplifier type can't be read" : null;
        int[] want = new int[3];
        if (testing) {
            want = test;
        } else if (useAmp) {
            want[0] = stepsFor(wantB, pi("bass_step_mB"), steps[0], maxAt(pi("max_bass_steps"), volume));
            want[2] = stepsFor(wantT, pi("treble_step_mB"), steps[2], maxAt(pi("max_treble_steps"), volume));
        }
        boolean rest = profile.optBoolean("android_rest");
        restB = testing ? 0 : useAmp ? (rest ? wantB - want[0] * pi("bass_step_mB") : 0) : wantB;
        restT = testing ? 0 : useAmp ? (rest ? wantT - want[2] * pi("treble_step_mB") : 0) : wantT;

        try {
            boolean leave = !cfg.on || (!flat(cfg) && want[0] == 0 && want[1] == 0 && want[2] == 0);
            if (leave) {
                restore();
                steps = new int[3];
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
                return;
            }
            boolean up = sent == null || target[0] > sent[0] || target[1] > sent[1] || target[2] > sent[2];
            boolean held = up && sent != null && !testing && System.currentTimeMillis() - sentAt < pi("min_interval_s") * 1000L;
            if (held) return;   // steps stay as they were; tried again next second
            send(target, testing ? "amp.sh test" : "base " + tone(base) + (flat(cfg) ? ", held flat" : "") + ", lift bass " + want[0] + ", treble " + want[2] + " steps at volume " + volume);
            steps = want;
        } catch (Throwable t) {
            Engine.audio = null;
            Engine.log("car tone: " + t);
        }
    }

    static boolean flat(Config cfg) {
        return cfg.on && "flat".equals(cfg.tone);
    }

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
        s.put("amp_writes", writes);
        s.put("amp_testing", testing);
        s.put("loud_mB", new JSONArray().put(Math.round(loudB)).put(Math.round(loudT)));
        if (why != null) s.put("amp_why", why);
    }
}
