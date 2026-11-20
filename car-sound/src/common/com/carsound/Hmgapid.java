package com.carsound;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Car Sound's calls to hmgapid, HMG-API's daemon (abstract socket @hmgapid), which answers a
 * mod's own app and services for their own mod only:
 *   setting-get          the owner's saved values (JSON; not the defaults)        (the app)
 *   setting-set <json>   replaces them (the engine reads them again within a second) (the app)
 *   vehicle speed        "speed <km/h>", or "-": HMG-API's shared reading (the engine; mod.json
 *                        declares it in "vehicle" "read")
 * Wire format: "LFD1", the argument count, each argument as length + UTF-8 (32-bit little-endian);
 * the answer is frames (type, length, data): 'O' output, 'X' exit code (0 = ok). Calls block:
 * keep them off the UI thread.
 */
public final class Hmgapid {
    private Hmgapid() {}

    public static String settingGet() throws IOException {
        return ok(call("setting-get"));
    }

    public static void settingSet(String json) throws IOException {
        ok(call("setting-set", json));
    }

    /** one vehicle reading (HMG-API's, e.g. "speed"): its value, or NaN when the car doesn't answer */
    public static double vehicle(String name) throws IOException {
        for (String line : ok(call("vehicle", name)).split("\n")) {
            String[] w = line.trim().split(" ");
            if (w.length == 2 && w[0].equals(name)) {
                try {
                    return Double.parseDouble(w[1]);
                } catch (NumberFormatException e) {
                    return Double.NaN;   // "-": no answer from the car
                }
            }
        }
        return Double.NaN;
    }

    private static String ok(String[] r) throws IOException {
        if (!"0".equals(r[0])) throw new IOException("HMG-API said no (" + r[0] + "): " + r[1].trim());
        return r[1];
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
        o.write(v >> 16 & 0xff);
        o.write(v >> 24 & 0xff);
    }

    private static int u32(byte[] b) {
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    /** {exit code, output} */
    private static String[] call(String... args) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            try {
                s.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            } catch (IOException e) {
                throw new IOException("HMG-API isn't running");
            }
            s.setSoTimeout(3000);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            u32(req, args.length);
            for (String a : args) {
                byte[] b = a.getBytes("UTF-8");
                u32(req, b.length);
                req.write(b);
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toByteArray());
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            int code = -1;
            while (true) {
                int type = in.read();
                if (type < 0) break;
                byte[] n = new byte[4];
                in.readFully(n);
                int len = u32(n);
                if (len < 0 || len > (1 << 20)) throw new IOException("HMG-API: a bad answer");
                byte[] data = new byte[len];
                in.readFully(data);
                if (type == 'O') text.write(data);
                else if (type == 'X' && len == 4) {
                    code = u32(data);
                    break;
                }
            }
            return new String[] { String.valueOf(code), text.toString("UTF-8") };
        } finally {
            s.close();
        }
    }
}
