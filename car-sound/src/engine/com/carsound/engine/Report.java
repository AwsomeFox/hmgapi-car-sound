package com.carsound.engine;

import android.media.audiofx.AudioEffect;

import com.carsound.Config;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A car report: what Car Sound needs to know to tune its amplifier profile (Amp) for a car,
 * as one text file to attach to a GitHub issue. Asked for by the app (Config.report, a new
 * time each tap) or by amp.sh (REQUEST_FILE). Written to DIR/car-report.txt and, if a USB stick
 * is in, CarSound/car-sound-report-<time>.txt on it.
 *
 * It holds the head unit's model and software, the amplifier type, which of the car's sound
 * settings it has and their values, the volume groups, Android's audio effects and output, the
 * engine's status and log, and the car audio service's own log lines about tone, volume and
 * amplifier. Nothing that names a person or the car: no serial numbers, VIN, Bluetooth names
 * or addresses, locations or song details (the log lines are picked by tag and keyword).
 */
final class Report {
    static final File OUT = new File(Engine.DIR, "car-report.txt");
    static final File REQUEST_FILE = new File(Engine.DIR, "report-now");
    static final File DONE_FILE = new File(Engine.DIR, "report-done");
    static final String CONST = "com.hkmc.media.MicomAudioManagerConstant$";
    static String last = "";
    static long lastFor;

    static void check(Config cfg) {
        boolean byFile = REQUEST_FILE.exists();
        long done = 0;
        try {
            done = Long.parseLong(Engine.read(DONE_FILE).trim());
        } catch (Exception e) {
            // none yet
        }
        if (!byFile && (cfg.report == 0 || cfg.report == done)) return;
        REQUEST_FILE.delete();
        if (cfg.report != 0) Engine.write(DONE_FILE, String.valueOf(cfg.report));
        lastFor = byFile ? 0 : cfg.report;
        try {
            String text = build();
            List<String> where = new ArrayList<String>();
            writeTo(OUT, text);
            where.add(OUT.getPath());
            String name = "car-sound-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt";
            for (String m : usb()) {
                try {
                    File d = new File(m, "CarSound");
                    d.mkdirs();
                    File f = new File(d, name);
                    writeTo(f, text);
                    where.add(f.getPath());
                } catch (Exception e) {
                    Engine.log("car report: can't write to " + m + ": " + e);
                }
            }
            last = where.size() > 1 ? "Saved to USB: CarSound/" + name : "Saved on the head unit (no USB stick found)";
            Engine.log("car report: " + where);
        } catch (Throwable t) {
            last = "Car report failed: " + t;
            Engine.log("car report: " + t);
        }
    }

    static void status(JSONObject s) throws Exception {
        if (last.length() > 0) {
            s.put("report", last);
            s.put("report_for", lastFor);
        }
    }

    static void writeTo(File f, String text) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(text.getBytes("UTF-8"));
        o.close();
        f.setReadable(true, false);
    }

    /** mounted USB sticks: FAT/exFAT/NTFS mounts outside Android's own partitions */
    static List<String> usb() {
        List<String> out = new ArrayList<String>();
        try {
            for (String l : Engine.read(new File("/proc/mounts")).split("\n")) {
                String[] w = l.split(" ");
                if (w.length < 3) continue;
                String at = w[1], fs = w[2];
                if (!fs.matches("vfat|exfat|texfat|sdfat|tntfs|ntfs|fuseblk")) continue;
                if (at.startsWith("/system") || at.startsWith("/data") || at.startsWith("/cache") || at.startsWith("/firmware")
                        || at.startsWith("/persist") || at.contains("emulated") || at.startsWith("/mnt/secure") || at.startsWith("/mnt/asec")) continue;
                if (new File(at).canWrite() && !out.contains(at)) out.add(at);
            }
        } catch (Exception e) {
            // none
        }
        return out;
    }

    // ---- the report

    static String build() {
        StringBuilder b = new StringBuilder();
        b.append("Car Sound car report\n").append(new Date()).append("\n\n");
        line(b, "Car Sound", version());
        b.append("\n## Head unit\n");
        for (String p : new String[] { "ro.build.display.id", "ro.build.version.release", "ro.build.version.incremental", "ro.product.model", "ro.product.name", "ro.product.device", "ro.product.board" }) {
            line(b, p, prop(p));
        }

        b.append("\n## Amplifier\n");
        Amp.loadProfile();
        line(b, "getAmpType()", String.valueOf(Amp.ampType));
        line(b, "profile", String.valueOf(Amp.profile));
        line(b, "test profile file", Amp.PROFILE_FILE.isFile() ? "yes" : "no");
        line(b, "amplifier writes since the engine started", String.valueOf(Amp.writes));

        Object au = null;
        try {
            au = Engine.audio();
        } catch (Throwable t) {
            line(b, "car audio service", "ERR " + t);
        }
        if (au != null) {
            b.append("\n## The car's sound settings (type name = value, available)\n");
            Map<Integer, String> types = constants("SoundSettingsType");
            for (int t = 0; t <= 70; t++) {
                String n = types.containsKey(t) ? types.get(t) : "?";
                line(b, "sound " + t + " " + n, safe(au, "getSoundSetting", t) + ", available " + safe(au, "getSoundAvailability", t));
            }
            b.append("\n## Volume groups (index name = level)\n");
            Map<Integer, String> groups = constants("VolumeGroup");
            for (int g = 0; g <= 40; g++) {
                String v = safe(au, "getVolumeOfVolumeGroup", g);
                if (v.startsWith("ERR") && !groups.containsKey(g)) continue;
                line(b, "group " + g + " " + (groups.containsKey(g) ? groups.get(g) : "?"), v);
            }
            for (String m : new String[] { "getAutoAdjustingVolumeSetting", "getVolumeControlEnabledStream", "getActiveSoundDesign", "getSoundPriority" }) {
                line(b, m + "()", safe(au, m));
            }
        }

        b.append("\n## Android audio effects\n");
        try {
            for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) b.append(d.name).append(" (").append(d.implementor).append(") ").append(d.uuid).append('\n');
        } catch (Throwable t) {
            b.append("ERR ").append(t).append('\n');
        }

        b.append("\n## Android audio output\n");
        for (String l : run("dumpsys", "media.audio_flinger")) {
            String t = l.trim();
            if (t.startsWith("Output thread") || t.startsWith("Sample rate") || t.startsWith("HAL frame count") || t.startsWith("Normal frame count")
                    || t.startsWith("Channel Count") || t.startsWith("Format") || t.startsWith("Output device") || t.contains("active tracks")) {
                b.append(t).append('\n');
            }
        }

        b.append("\n## Car Sound's status\n");
        try {
            b.append(Engine.read(new File(Config.STATUS))).append('\n');
        } catch (Exception e) {
            b.append("none\n");
        }
        b.append("\n## Car Sound's log\n");
        synchronized (Engine.class) {
            for (String l : Engine.recent) b.append(l).append('\n');
        }

        b.append("\n## The car audio service's log (tone, volume, amplifier lines only)\n");
        List<String> lines = run("logcat", "-d", "-v", "time", "-s", "MicomAudioService:*", "MicomAudioManager:*", "SoundSettings:*", "MicomVolumePolicyService:*");
        List<String> keep = new ArrayList<String>();
        for (String l : lines) {
            if (l.matches(".*(setBassMidTreb|setSound|getAmpType|AMP_TYPE|Bass|Treb|Middle|setVolumeInternal|SDVC|DSC|loadFeatures|Availability|Reset).*")) keep.add(l);
        }
        for (int i = Math.max(0, keep.size() - 300); i < keep.size(); i++) b.append(keep.get(i)).append('\n');
        return b.toString();
    }

    static void line(StringBuilder b, String k, String v) {
        b.append(k).append(" = ").append(v).append('\n');
    }

    static String version() {
        try {
            String dir = System.getenv("HMGAPI_MOD_DIR");
            return new JSONObject(Engine.read(new File(dir, "mod.json"))).optString("version");
        } catch (Exception e) {
            return "?";
        }
    }

    static String prop(String name) {
        List<String> l = run("getprop", name);
        return l.isEmpty() ? "" : l.get(0);
    }

    static String safe(Object target, String name, Object... args) {
        try {
            return String.valueOf(Engine.call(target, name, args));
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "ERR " + c.getClass().getSimpleName();
        }
    }

    /** int constants of a MicomAudioManagerConstant nested class, by value */
    static Map<Integer, String> constants(String nested) {
        Map<Integer, String> out = new TreeMap<Integer, String>();
        try {
            for (Field f : Class.forName(CONST + nested).getDeclaredFields()) {
                if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    out.put(v, out.containsKey(v) ? out.get(v) + "/" + f.getName() : f.getName());
                }
            }
        } catch (Throwable t) {
            // not on this build
        }
        return out;
    }

    static List<String> run(String... cmd) {
        List<String> out = new ArrayList<String>();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null && out.size() < 20000) out.add(l);
            r.close();
            p.waitFor();
        } catch (Exception e) {
            out.add("ERR " + e);
        }
        return out;
    }
}
