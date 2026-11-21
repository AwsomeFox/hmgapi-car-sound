package com.carsound;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Car Sound's settings, shared by the app and the engine. HMG-API keeps them as two text
 * settings of the mod (settings.json): "on" ("1"/"0") and "config", this JSON:
 *   {"bands": [mB x5], "boost": 0-1000,
 *    "speed": {"on": bool, "vol": [mB x5], "bass": [mB x5], "treble": [mB x5]}, "dsc": "leave"|"off"|"on",
 *    "tone": "leave"|"flat", "guard": bool, "lift": "android"|"amp",
 *    "loud": {"on": bool, "bass": mB, "treble": mB, "full": volume step, "none": volume step}, "report": ms}
 * The speed lists are the extra volume, bass and treble at MPH's speeds (0 at a standstill, in
 * between linear, past the last speed as at it).
 * mB: millibels (100 = 1 dB). Bands: Android's equalizer, 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz.
 */
public final class Config {
    public static final String MOD = "car-sound";
    public static final int[] FREQ = { 60, 230, 910, 3600, 14000 };
    public static final String STATUS = "/data/local/tmp/car-sound/status.json";

    public boolean on = true;
    public int[] bands = { 700, -200, 0, 150, 200 };
    public int boost = 400;
    public boolean speedOn = true;
    /** speed tuning: at each of these speeds (mph), how much louder (VOL), more bass (BASS) and more treble (TREBLE), in mB */
    public static final int[] MPH = { 20, 35, 50, 65, 80 };
    public static final int VOL = 0, BASS = 1, TREBLE = 2;
    public static final String[] CURVES = { "Volume", "Bass", "Treble" };
    public int[][] speed = copy(SPEED_PRESETS[1]);
    public static final int SPEED_MAX = 1000;   // +10 dB at most
    public String dsc = "leave";
    /** the car's own Bass, Mid and Treble (in the amplifier, after Car Sound): "leave" as set, or "flat" (centre) while Car Sound is on */
    public String tone = "leave";
    /** clip guard: back the speed volume off (or turn the audio down a little) while Car Sound's output reaches full scale */
    public boolean guard = true;
    /** where the speed curves' bass and treble and the loudness lift are made: "android" (the equalizer, phone audio) or "amp" (the car's Bass and Treble steps, every source) */
    public String lift = "android";
    /** loudness: bass and treble lifted at low volume, as ears lose them; all of it at volume loudFull or below, none from loudNone */
    public boolean loudOn = false;
    public int loudBass = 600, loudTreble = 200, loudFull = 6, loudNone = 20;
    public static final int LOUD_MAX = 1000, VOLUME_STEPS = 45;
    /** a car report asked for at this time (ms); the engine makes one for each new value */
    public long report;

    /**
     * speed presets: volume, bass and treble (mB) at MPH's speeds. Cabin noise in an EV is mostly
     * tyre and wind roar, rising about 8-10 dB from 30 to 70 mph and loudest below ~500 Hz, so the
     * volume does most of the work and the bass follows; treble only a little (tyre hiss is
     * higher, but a treble lift soon sounds harsh).
     */
    public static final String[] SPEED_PRESET_NAMES = { "Low", "Medium", "High", "Max" };
    public static final int[][][] SPEED_PRESETS = {
        { { 0, 100, 150, 250, 300 }, { 0, 50, 100, 150, 200 }, { 0, 0, 50, 50, 100 } },
        { { 0, 150, 250, 400, 500 }, { 0, 100, 150, 250, 350 }, { 0, 50, 100, 150, 200 } },
        { { 0, 200, 400, 550, 700 }, { 0, 150, 250, 400, 500 }, { 0, 50, 150, 200, 300 } },
        { { 100, 300, 550, 800, 1000 }, { 50, 200, 400, 600, 800 }, { 0, 100, 200, 300, 400 } },
    };

    public static int[][] copy(int[][] a) {
        int[][] out = new int[a.length][];
        for (int i = 0; i < a.length; i++) out[i] = a[i].clone();
        return out;
    }

    /** which of SPEED_PRESETS the speed curves are, or -1 (the owner's own) */
    public int speedPreset() {
        for (int i = 0; i < SPEED_PRESETS.length; i++) if (java.util.Arrays.deepEquals(SPEED_PRESETS[i], speed)) return i;
        return -1;
    }

    /**
     * presets: name, bands (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz), bass boost. Tuned for the Ioniq 5's
     * 8-speaker Bose system (with subwoofer), whose flat setting owners find heavy in the mid-bass
     * (about 100-250 Hz) and light in the deep bass, so most cut 230 Hz while lifting 60 Hz.
     *   Recommended  deep bass up, mid-bass mud down, a little presence and air
     *   Deep bass    more of the same, for bass-heavy music; may limit at high volume
     *   Harman car   the shape of Harman's in-car target: a 6-8 dB bass shelf under ~80 Hz and a
     *                gentle fall above ~2.5 kHz
     *   Highway      road noise covers the lows and highs: both up, for 60+ mph
     *   Clear        the owners' "muddy Bose" fix: bass down, mids and treble up, no boost
     *   Low volume   loudness: lows and highs up, as ears lose them when it's quiet
     *   Voices       podcasts and audiobooks: 1-4 kHz up, the boom cut
     *   Flat         Android's equalizer and bass boost out of the way
     */
    public static final Object[][] PRESETS = {
        { "Recommended", new int[] { 700, -200, 0, 150, 200 }, 400 },
        { "Deep bass", new int[] { 1000, -100, 0, 150, 200 }, 650 },
        { "Harman car", new int[] { 750, 150, 0, -100, -200 }, 250 },
        { "Highway", new int[] { 900, 0, 100, 250, 300 }, 450 },
        { "Clear", new int[] { -200, -300, 100, 300, 400 }, 0 },
        { "Low volume", new int[] { 800, 0, 0, 200, 400 }, 300 },
        { "Voices", new int[] { -100, -300, 300, 400, 0 }, 0 },
        { "Flat", new int[] { 0, 0, 0, 0, 0 }, 0 },
    };

    /** from HMG-API's saved values ({"on": "1", "config": "{...}"}); missing ones are the defaults */
    public static Config from(JSONObject saved) {
        Config c = new Config();
        if (saved == null) return c;
        c.on = !"0".equals(saved.optString("on", "1"));
        String j = saved.optString("config", "");
        if (j.length() == 0) return c;
        try {
            JSONObject o = new JSONObject(j);
            JSONArray b = o.optJSONArray("bands");
            if (b != null) for (int i = 0; i < 5 && i < b.length(); i++) c.bands[i] = clamp(b.optInt(i, c.bands[i]), -1500, 1500);
            c.boost = clamp(o.optInt("boost", c.boost), 0, 1000);
            JSONObject s = o.optJSONObject("speed");
            if (s != null) {
                c.speedOn = s.optBoolean("on", c.speedOn);
                String[] keys = { "vol", "bass", "treble" };
                for (int k = 0; k < 3; k++) {
                    JSONArray a = s.optJSONArray(keys[k]);
                    if (a != null) for (int i = 0; i < MPH.length && i < a.length(); i++) c.speed[k][i] = clamp(a.optInt(i, c.speed[k][i]), 0, SPEED_MAX);
                }
            }
            String d = o.optString("dsc", c.dsc);
            c.dsc = "off".equals(d) || "on".equals(d) ? d : "leave";
            c.tone = "flat".equals(o.optString("tone")) ? "flat" : "leave";
            c.guard = o.optBoolean("guard", c.guard);
            c.lift = "amp".equals(o.optString("lift")) ? "amp" : "android";
            JSONObject l = o.optJSONObject("loud");
            if (l != null) {
                c.loudOn = l.optBoolean("on", c.loudOn);
                c.loudBass = clamp(l.optInt("bass", c.loudBass), 0, LOUD_MAX);
                c.loudTreble = clamp(l.optInt("treble", c.loudTreble), 0, LOUD_MAX);
                c.loudFull = clamp(l.optInt("full", c.loudFull), 0, VOLUME_STEPS);
                c.loudNone = clamp(l.optInt("none", c.loudNone), 0, VOLUME_STEPS);
            }
            c.report = o.optLong("report", 0);
        } catch (Exception e) {
            // a broken config: the defaults
        }
        return c;
    }

    /** for setting-set: {"on": "1"|"0", "config": "<json>"} */
    public JSONObject toSaved() {
        try {
            JSONObject o = new JSONObject();
            JSONArray b = new JSONArray();
            for (int v : bands) b.put(v);
            o.put("bands", b);
            o.put("boost", boost);
            JSONObject s = new JSONObject();
            s.put("on", speedOn);
            String[] keys = { "vol", "bass", "treble" };
            for (int k = 0; k < 3; k++) {
                JSONArray a = new JSONArray();
                for (int v : speed[k]) a.put(v);
                s.put(keys[k], a);
            }
            o.put("speed", s);
            o.put("dsc", dsc);
            o.put("tone", tone);
            o.put("guard", guard);
            o.put("lift", lift);
            JSONObject l = new JSONObject();
            l.put("on", loudOn);
            l.put("bass", loudBass);
            l.put("treble", loudTreble);
            l.put("full", loudFull);
            l.put("none", loudNone);
            o.put("loud", l);
            if (report != 0) o.put("report", report);
            JSONObject saved = new JSONObject();
            saved.put("on", on ? "1" : "0");
            saved.put("config", o.toString());
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** one speed curve (VOL, BASS, TREBLE) at a speed in mph: mB; 0 when speed tuning is off or the speed unknown */
    public double speedAt(int curve, double mph) {
        if (!speedOn || mph <= 0) return 0;
        return curveAt(speed[curve], mph);
    }

    /** a curve through (0 mph, 0) and MPH's points, flat after the last */
    public static double curveAt(int[] pts, double mph) {
        if (mph <= 0) return 0;
        double px = 0, py = 0;
        for (int i = 0; i < MPH.length; i++) {
            if (mph <= MPH[i]) return py + (pts[i] - py) * (mph - px) / (MPH[i] - px);
            px = MPH[i];
            py = pts[i];
        }
        return py;
    }

    public static double mph(double kmh) {
        return kmh * 0.621371;
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
