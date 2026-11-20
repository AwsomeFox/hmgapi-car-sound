package com.carsound.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carsound.Config;
import com.carsound.Hmgapid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * Car Sound's screen (1920x720): a left rail with the master switch, what the engine is doing
 * and the pages; on the right the page: Equalizer, Speed or, on a car with the Bose amplifier
 * (the engine asks the car: status "bose"), Bose. Every change is saved to HMG-API at once
 * (setting-set) and the engine applies it within a second.
 */
public class MainActivity extends Activity {
    static final String[] PAGES = { "Equalizer", "Speed", "Bose" };
    static final int BOSE = 2;   // the page only a car with Bose DSC has
    static final String[] PAGE_NOTES = {
        "Android Auto and CarPlay audio. Drag a point; the radio and Bluetooth audio aren't changed.",
        "Louder, more bass and more treble as you go faster, where road noise covers them.",
        "The Bose system's own speed compensation, in the car's Sound settings.",
    };

    Config cfg = new Config();
    int page = 0;
    final Handler h = new Handler();
    LinearLayout pageHolder, navHolder;
    TextView statusTitle, statusSpeed, statusDetail, saveNote, pageTitle, pageNote;
    View statusDot;
    Ui.Toggle master;
    Ui.SpeedView curve;
    float speed = -1;
    boolean simulated;   // the engine's speed is a parked listening test (sim-mph)
    boolean loaded;
    boolean bose;   // the engine found Bose DSC on this car

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        build();
        load();
        h.post(statusTick);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build();   // the car switched day/night
    }

    void build() {
        Ui.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { Ui.BG_TOP, Ui.BG_BOTTOM }));
        root.addView(rail(), new LinearLayout.LayoutParams(Ui.px(300), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(right(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        showPage();
    }

    // ---- left rail

    View rail() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(Ui.RAIL);
        r.setPadding(Ui.px(20), Ui.px(16), Ui.px(20), Ui.px(10));
        r.addView(Ui.text(this, "Car Sound", 24, Ui.TEXT, true));
        r.addView(Ui.text(this, "Better phone audio for your car", 13, Ui.TEXT_2, false));

        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setBackgroundDrawable(Ui.round(Ui.CARD, Ui.CARD_EDGE, 18));
        sw.setPadding(Ui.px(16), Ui.px(8), Ui.px(12), Ui.px(8));
        sw.addView(Ui.text(this, "Car Sound on", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        master = new Ui.Toggle(this);
        master.on = cfg.on;
        master.listener = new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.on = v == 1;
                save();
            }
        };
        sw.addView(master);
        r.addView(sw, Ui.lp(-1, -2, 0, 12));

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setBackgroundDrawable(Ui.round(Ui.CARD, Ui.CARD_EDGE, 18));
        st.setPadding(Ui.px(16), Ui.px(10), Ui.px(16), Ui.px(10));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        titleRow.addView(statusDot, new LinearLayout.LayoutParams(Ui.px(12), Ui.px(12)));
        statusTitle = Ui.text(this, "Starting…", 15, Ui.TEXT, true);
        statusTitle.setPadding(Ui.px(10), 0, 0, 0);
        titleRow.addView(statusTitle);
        st.addView(titleRow);
        statusSpeed = Ui.text(this, "", 24, Ui.TEXT, true);
        statusSpeed.setPadding(0, Ui.px(2), 0, 0);
        st.addView(statusSpeed);
        statusDetail = Ui.text(this, "", 13, Ui.TEXT_2, false);
        st.addView(statusDetail);
        r.addView(st, Ui.lp(-1, -2, 0, 10));

        navHolder = new LinearLayout(this);
        navHolder.setOrientation(LinearLayout.VERTICAL);
        r.addView(navHolder, Ui.lp(-1, -2, 0, 10));
        nav();

        View spacer = new View(this);
        r.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        saveNote = Ui.text(this, "", 12, Ui.TEXT_3, false);
        r.addView(saveNote);
        return r;
    }

    void nav() {
        navHolder.removeAllViews();
        for (int i = 0; i < PAGES.length; i++) {
            if (i == BOSE && !bose) continue;
            final int k = i;
            TextView t = Ui.text(this, PAGES[i], 17, i == page ? Ui.ACCENT : Ui.TEXT, true);
            t.setPadding(Ui.px(16), Ui.px(8), Ui.px(16), Ui.px(8));
            t.setBackgroundDrawable(i == page ? Ui.round(Ui.ACCENT_DIM, 0, 16) : null);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    page = k;
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            nav();
                            showPage();
                        }
                    });
                }
            });
            navHolder.addView(t, Ui.lp(-1, -2, 0, 2));
        }
    }

    // ---- right pane

    View right() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.px(24), Ui.px(14), Ui.px(64), Ui.px(8));
        pageTitle = Ui.text(this, "", 22, Ui.TEXT, true);
        col.addView(pageTitle);
        pageNote = Ui.text(this, "", 13, Ui.TEXT_2, false);
        col.addView(pageNote);
        pageHolder = new LinearLayout(this);   // no scroll view: each page fits the 720-pixel screen
        pageHolder.setOrientation(LinearLayout.VERTICAL);
        col.addView(pageHolder, Ui.lp(-1, 0, 1, 8));
        return col;
    }

    void showPage() {
        if (pageHolder == null) return;
        pageTitle.setText(PAGES[page]);
        pageNote.setText(PAGE_NOTES[page]);
        pageHolder.removeAllViews();
        curve = null;
        if (page == 0) equalizer();
        else if (page == 1) speedPage();
        else bose();
    }

    // the equalizer page's widgets: input updates them in place (rebuilding the page from a
    // touch or click crashed this Android 4.4 build in LinearLayout.forceUniformWidth)
    final java.util.List<TextView> chipViews = new java.util.ArrayList<TextView>();
    Ui.EqView eqView;
    Ui.Slider boostSlider;
    TextView boostValue;
    final Fmt boostFmt = new Fmt() {
        @Override
        public String f(int v) { return v == 0 ? "Off" : Math.round(v / 10.0) + "%"; }
    };

    void chips() {
        for (int i = 0; i < chipViews.size(); i++) {
            Object[] pr = Config.PRESETS[i];
            boolean sel = Arrays.equals((int[]) pr[1], cfg.bands) && (Integer) pr[2] == cfg.boost;
            TextView c = chipViews.get(i);
            c.setTextColor(sel ? Ui.ON_ACCENT : Ui.TEXT);
            c.setBackgroundDrawable(sel ? Ui.round(Ui.ACCENT, 0, 20) : Ui.round(0, Ui.CARD_EDGE, 20));
        }
    }

    void equalizer() {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipViews.clear();
        for (final Object[] pr : Config.PRESETS) {
            TextView c = Ui.chip(this, (String) pr[0], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cfg.bands = ((int[]) pr[1]).clone();
                    cfg.boost = (Integer) pr[2];
                    eqView.bands = cfg.bands.clone();
                    eqView.invalidate();
                    boostSlider.set(cfg.boost);
                    boostValue.setText(boostFmt.f(cfg.boost));
                    chips();
                    save();
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = Ui.px(8);
            chips.addView(c, p);
            chipViews.add(c);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        pageHolder.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = Ui.card(this);
        eqView = new Ui.EqView(this);
        eqView.bands = cfg.bands.clone();
        eqView.listener = new Ui.OnValue() {
            @Override
            public void value(int band, boolean done) {
                cfg.bands = eqView.bands.clone();
                if (done) {
                    chips();
                    save();
                }
            }
        };
        card.addView(eqView, new LinearLayout.LayoutParams(-1, -2));
        pageHolder.addView(card, Ui.lp(-1, -2, 0, 8));

        Object[] sl = slider(card, "Bass boost", 0, 1000, 10, cfg.boost, boostFmt, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.boost = v;
                if (done) {
                    chips();
                    save();
                }
            }
        });
        boostSlider = (Ui.Slider) sl[0];
        boostValue = (TextView) sl[1];
        card.addView(Ui.text(this, "On top of the equalizer. Above about 60% the amplifier may limit or distort at high volume.", 12, Ui.TEXT_3, false));
        chips();
    }

    void speedPage() {
        LinearLayout top = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, "Change the sound with speed", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout seg = Ui.segmented(this, Config.CURVES, 0, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                curve.sel = v;
                curve.invalidate();
            }
        });
        row.addView(seg, new LinearLayout.LayoutParams(Ui.px(380), -2));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.px(20), 1));
        final Ui.Toggle t = new Ui.Toggle(this);
        t.on = cfg.speedOn;
        t.listener = new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.speedOn = v == 1;
                save();
                curve.invalidate();
            }
        };
        row.addView(t);
        top.addView(row);
        curve = new Ui.SpeedView(this);
        curve.cfg = cfg;
        curve.mph = speed < 0 ? -1 : (float) Config.mph(speed);
        curve.listener = new Ui.OnValue() {
            @Override
            public void value(int point, boolean done) {
                if (done) save();
            }
        };
        top.addView(curve, Ui.lp(-1, -2, 0, 6));
        top.addView(Ui.text(this, "Drag a point: how much louder, or how much more bass or treble, at that speed. Changes follow your speed gently, about 1 dB a second. Volume and treble apply to phone audio only, like the equalizer.", 12, Ui.TEXT_3, false));
        pageHolder.addView(top);
    }

    void bose() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Bose Dynamic Speed Compensation", 17, Ui.TEXT, true));
        int sel = "off".equals(cfg.dsc) ? 1 : "on".equals(cfg.dsc) ? 2 : 0;
        c.addView(Ui.segmented(this, new String[] { "As set in the car", "Off with Car Sound", "On" }, sel, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.dsc = v == 1 ? "off" : v == 2 ? "on" : "leave";
                save();
            }
        }), Ui.lp(-1, -2, 0, 10));
        c.addView(Ui.text(this, "Use one speed compensation, not both: with Car Sound's speed tuning on, choose \"Off with Car Sound\". Car Sound puts the car's setting back when it's turned off or removed.", 14, Ui.TEXT_2, false), Ui.lp(-1, -2, 0, 10));
        pageHolder.addView(c);
    }

    // ---- small helpers

    interface Fmt {
        String f(int v);
    }

    static Ui.OnValue chain(final Ui.OnValue a, final Ui.OnValue b) {
        return new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                a.value(v, done);
                b.value(v, done);
            }
        };
    }

    Object[] slider(LinearLayout into, String label, int min, int max, int step, int value, final Fmt fmt, final Ui.OnValue listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.text(this, label, 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView val = Ui.text(this, fmt.f(value), 16, Ui.ACCENT, true);
        row.addView(val);
        into.addView(row, Ui.lp(-1, -2, 0, into.getChildCount() == 0 ? 0 : 6));
        Ui.Slider s = new Ui.Slider(this, min, max, step);
        s.set(value);
        s.listener = new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                val.setText(fmt.f(v));
                listener.value(v, done);
            }
        };
        into.addView(s, Ui.lp(-1, -2, 0, 0));
        return new Object[] { s, val };
    }

    // ---- HMG-API: load once, save on every change

    void load() {
        new Thread() {
            @Override
            public void run() {
                Config c;
                String err = null;
                try {
                    String j = Hmgapid.settingGet().trim();
                    c = Config.from(j.length() == 0 ? null : new JSONObject(j));
                } catch (Exception e) {
                    c = new Config();
                    err = e.getMessage();
                }
                final Config got = c;
                final String why = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cfg = got;
                        loaded = true;
                        master.on = cfg.on;
                        master.invalidate();
                        showPage();
                        saveNote.setText(why == null ? "" : "Can't reach HMG-API: " + why);
                    }
                });
            }
        }.start();
    }

    final Runnable saver = new Runnable() {
        @Override
        public void run() {
            final String json = cfg.toSaved().toString();
            new Thread() {
                @Override
                public void run() {
                    String note;
                    try {
                        Hmgapid.settingSet(json);
                        note = "Saved";
                    } catch (Exception e) {
                        note = "Not saved: " + e.getMessage();
                    }
                    final String n = note;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { saveNote.setText(n); }
                    });
                }
            }.start();
        }
    };

    void save() {
        if (!loaded) return;   // don't overwrite the owner's settings with the defaults before they've loaded
        h.removeCallbacks(saver);
        h.postDelayed(saver, 300);
    }

    // ---- the engine's status

    final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            status();
            h.postDelayed(this, 1500);
        }
    };

    void status() {
        String title, detail = "";
        int dot = Ui.TEXT_3;
        speed = -1;
        try {
            JSONObject st = new JSONObject(read(new File(Config.STATUS)));
            long age = (System.currentTimeMillis() - st.optLong("time")) / 1000;
            if (age > 10 || "stopped".equals(st.optString("state"))) {
                title = "Engine not running";
                dot = Ui.WARN;
            } else {
                speed = (float) st.optDouble("speed_kmh", -1);
                simulated = st.optBoolean("sim");
                setBose(st.optBoolean("bose"));
                JSONArray ss = st.optJSONArray("sessions");
                boolean playing = ss != null && ss.length() > 0;
                String fxError = st.optString("fx_error", "");
                if (!cfg.on) title = "Off";
                else if (playing) { title = "Tuning phone audio"; dot = Ui.ACCENT; }
                else if (fxError.length() > 0) { title = "Can't tune this car's audio"; dot = Ui.WARN; }
                else title = "Waiting for phone audio";
                String dsc = st.optString("dsc");
                detail = (!cfg.speedOn ? "Speed tuning off"
                        : speed < 0 ? "Speed tuning: no speed from HMG-API"
                        : "Speed: volume " + Ui.dB(st.optDouble("speed_vol_mB", 0)) + ", bass " + Ui.dB(st.optDouble("speed_bass_mB", 0))
                        + ", treble " + Ui.dB(st.optDouble("speed_treble_mB", 0)))
                        + (!bose ? "" : "off".equals(dsc) ? "  •  Bose DSC held off" : "on".equals(dsc) ? "  •  Bose DSC on" : "");
                if (cfg.on && !playing && fxError.length() > 0) detail = "Android's audio effects: " + fxError;
            }
        } catch (Exception e) {
            title = "Engine not running";
            dot = Ui.WARN;
        }
        statusTitle.setText(title);
        statusDot.setBackgroundDrawable(Ui.round(dot, 0, 6));
        statusSpeed.setText(speed < 0 ? "– mph" : Ui.mph(speed) + (simulated ? " mph (test)" : " mph"));
        statusDetail.setText(detail);
        if (curve != null) {
            curve.mph = speed < 0 ? -1 : (float) Config.mph(speed);
            curve.invalidate();
        }
    }

    /** show or hide the Bose page (from the status tick, a posted runnable: rebuilding here is safe) */
    void setBose(boolean has) {
        if (has == bose) return;
        bose = has;
        if (!bose && page == BOSE) {
            page = 0;
            showPage();
        }
        nav();
    }

    static String read(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            StringBuilder b = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) b.append(l);
            return b.toString();
        } finally {
            r.close();
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(statusTick);
        super.onDestroy();
    }
}
