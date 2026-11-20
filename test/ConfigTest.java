import com.carsound.Config;

/**
 * Off-car checks of Car Sound's settings model: the speed curves and the presets. Runs on a
 * desktop JVM with android.jar on the classpath (org.json there is only called by from/toSaved).
 */
public final class ConfigTest {
    static int failed;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failed++;
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    public static void main(String[] a) {
        int[] pts = { 0, 100, 200, 300, 400 };   // at 20, 35, 50, 65, 80 mph
        check("a standstill adds nothing", near(Config.curveAt(pts, 0), 0));
        check("unknown speed adds nothing", near(Config.curveAt(pts, -1), 0));
        check("halfway to the first point is half of it", near(Config.curveAt(new int[] { 200, 0, 0, 0, 0 }, 10), 100));
        check("at a point, its value", near(Config.curveAt(pts, 50), 200));
        check("between points, linear", near(Config.curveAt(pts, 42.5), 150));
        check("past the last point, flat at it", near(Config.curveAt(pts, 120), 400));

        Config c = new Config();
        check("speed tuning on by default", c.speedOn);
        check("its volume curve follows speed", near(c.speedAt(Config.VOL, 80), c.speed[Config.VOL][4]));
        c.speedOn = false;
        check("speed tuning off adds nothing", near(c.speedAt(Config.VOL, 80), 0));
        check("km/h to mph", near(Config.mph(100), 62.1371));

        check("five speed points", Config.MPH.length == 5);
        for (int i = 1; i < Config.MPH.length; i++) check("speed points rise (" + Config.MPH[i] + " mph)", Config.MPH[i] > Config.MPH[i - 1]);
        for (int k = 0; k < 3; k++) {
            for (int v : new Config().speed[k]) check(Config.CURVES[k] + " default within 0..+10 dB", v >= 0 && v <= Config.SPEED_MAX);
        }

        check("presets", Config.PRESETS.length >= 4);
        for (Object[] p : Config.PRESETS) {
            int[] bands = (int[]) p[1];
            int boost = (Integer) p[2];
            boolean ok = bands.length == Config.FREQ.length && boost >= 0 && boost <= 1000;
            for (int b : bands) ok &= b >= -1200 && b <= 1200;   // the screen shows and sets ±12 dB
            check("preset " + p[0] + ": 5 bands within ±12 dB, bass boost 0-1000", ok);
        }
        check("the default equalizer is the Recommended preset",
                java.util.Arrays.equals(new Config().bands, (int[]) Config.PRESETS[0][1]) && new Config().boost == (Integer) Config.PRESETS[0][2]);

        System.out.println(failed == 0 ? "ConfigTest: all passed" : "ConfigTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
