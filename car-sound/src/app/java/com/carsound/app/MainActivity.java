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
 * and the pages; on the right the page: Equalizer, Speed, Loudness or Car. The Car page's speed compensation
 * switch shows only on a car that has the setting (status "speed_comp_ok"). Every change is saved to
 * HMG-API at once (setting-set) and the engine applies it within a second.
 */
public class MainActivity extends Activity {
    static final String[] PAGES = { "Equalizer", "Speed", "Loudness", "Car" };
    static final String[] PAGE_NOTES = {
        "Pick a sound or drag a point. With the car's amplifier (Car page), boosts reach every source.",
        "Louder, more bass and more treble as you go faster, where road noise covers them.",
        "More bass and treble when the music is quiet.",
        "How Car Sound works with the car: its amplifier, its own speed compensation, and what Android audio is tuned.",
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
    long reportAsked;
    boolean bose;   // the engine found the car's speed compensation setting (Bose DSC or SDVC)

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
        r.setPadding(Ui.px(20), Ui.px(12), Ui.px(20), Ui.px(6));
        r.addView(Ui.text(this, "Car Sound", 24, Ui.TEXT, true));
        r.addView(Ui.text(this, "Better phone audio for your car", 13, Ui.TEXT_2, false));

        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setBackgroundDrawable(Ui.round(Ui.CARD, Ui.CARD_EDGE, 18));
        sw.setPadding(Ui.px(16), Ui.px(4), Ui.px(12), Ui.px(4));
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
        r.addView(sw, Ui.lp(-1, -2, 0, 8));

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setBackgroundDrawable(Ui.round(Ui.CARD, Ui.CARD_EDGE, 18));
        st.setPadding(Ui.px(16), Ui.px(6), Ui.px(16), Ui.px(6));
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
        statusDetail.setMaxLines(2);
        statusDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        st.addView(statusDetail);
        r.addView(st, Ui.lp(-1, -2, 0, 8));

        navHolder = new LinearLayout(this);
        navHolder.setOrientation(LinearLayout.VERTICAL);
        r.addView(navHolder, Ui.lp(-1, -2, 0, 6));
        nav();

        View spacer = new View(this);
        r.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView rep = Ui.text(this, "Save car report", 14, Ui.ACCENT, true);
        rep.setPadding(Ui.px(16), Ui.px(2), Ui.px(16), Ui.px(2));
        rep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.report = System.currentTimeMillis();
                reportAsked = cfg.report;
                saveNote.setText("Making the car report… (plug in a USB stick to get a copy)");
                save();
            }
        });
        r.addView(rep);
        saveNote = Ui.text(this, "", 12, Ui.TEXT_3, false);
        saveNote.setMaxLines(2);
        r.addView(saveNote);
        return r;
    }

    void nav() {
        navHolder.removeAllViews();
        for (int i = 0; i < PAGES.length; i++) {
            final int k = i;
            TextView t = Ui.text(this, PAGES[i], 17, i == page ? Ui.ACCENT : Ui.TEXT, true);
            t.setPadding(Ui.px(16), Ui.px(5), Ui.px(16), Ui.px(5));
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
        carToneNow = guardNow = dscTitle = null;
        dscCard = null;
        toneChoice = null;
        speedCustom = null;
        loudNow = ampNow = null;
        if (page == 0) equalizer();
        else if (page == 1) speedPage();
        else if (page == 2) loudness();
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

    final java.util.List<TextView> speedChipViews = new java.util.ArrayList<TextView>();
    TextView speedCustom;

    void speedChips() {
        int sel = cfg.speedPreset();
        for (int i = 0; i < speedChipViews.size(); i++) {
            TextView c = speedChipViews.get(i);
            c.setTextColor(i == sel ? Ui.ON_ACCENT : Ui.TEXT);
            c.setBackgroundDrawable(i == sel ? Ui.round(Ui.ACCENT, 0, 20) : Ui.round(0, Ui.CARD_EDGE, 20));
        }
        if (speedCustom != null) speedCustom.setText(sel < 0 ? "Your own curves" : "");
    }

    void speedPage() {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.addView(Ui.text(this, "Compensation", 16, Ui.TEXT_2, true), Ui.lp(-2, -2, 0, 0));
        speedChipViews.clear();
        for (int i = 0; i < Config.SPEED_PRESETS.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, Config.SPEED_PRESET_NAMES[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cfg.speed = Config.copy(Config.SPEED_PRESETS[k]);
                    curve.cfg = cfg;
                    curve.invalidate();
                    speedChips();
                    save();
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.leftMargin = Ui.px(10);
            chips.addView(c, p);
            speedChipViews.add(c);
        }
        speedCustom = Ui.text(this, "", 14, Ui.TEXT_3, false);
        speedCustom.setPadding(Ui.px(14), 0, 0, 0);
        chips.addView(speedCustom);
        pageHolder.addView(chips, new LinearLayout.LayoutParams(-1, -2));

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
                if (done) {
                    speedChips();
                    save();
                }
            }
        };
        top.addView(curve, Ui.lp(-1, -2, 0, 6));
        top.addView(Ui.text(this, "Pick a level, or drag a point: how much louder, or how much more bass or treble, at that speed. Changes follow your speed gently, about 1 dB a second. With the car's amplifier, the volume is the car's own, on every source.", 12, Ui.TEXT_3, false));
        pageHolder.addView(top, Ui.lp(-1, -2, 0, 8));
        speedChips();
    }

    TextView loudNow, ampNow;

    void loudness() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Lift bass and treble at low volume", 17, Ui.TEXT, true));
        c.addView(Ui.text(this, "Quiet music sounds thin: ears hear less bass and treble at low volume. This adds some back, and fades it out as you turn the volume up.", 13, Ui.TEXT_2, false), Ui.lp(-1, -2, 0, 4));
        int sel = cfg.loudPreset() + 1;   // 0: off
        if (cfg.loudOn && sel == 0) sel = 2;   // amounts of the owner's own: nearest to show is Medium
        c.addView(Ui.segmented(this, new String[] { "Off", Config.LOUD_NAMES[0], Config.LOUD_NAMES[1], Config.LOUD_NAMES[2] }, sel, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.loudOn = v > 0;
                if (v > 0) {
                    cfg.loudBass = Config.LOUD_PRESETS[v - 1][0];
                    cfg.loudTreble = Config.LOUD_PRESETS[v - 1][1];
                }
                save();
            }
        }), Ui.lp(-1, -2, 0, 10));
        loudNow = Ui.text(this, "", 15, Ui.ACCENT, true);
        c.addView(loudNow, Ui.lp(-1, -2, 0, 10));
        pageHolder.addView(c);

        LinearLayout f = Ui.card(this);
        slider(f, "No lift from this volume up", 5, Config.VOLUME_STEPS, 1, cfg.loudNone, new Fmt() {
            @Override
            public String f(int v) { return "volume " + v; }
        }, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.loudNone = v;
                cfg.loudFull = Math.min(cfg.loudFull, Math.max(0, v - 5));
                if (done) save();
            }
        });
        f.addView(Ui.text(this, "Set it to about where you usually listen. All of the lift is there at volume " + cfg.loudFull + " and below. Low is up to +3 dB of bass, Medium +5 dB, High +7 dB, with a third as much treble.", 13, Ui.TEXT_2, false), Ui.lp(-1, -2, 0, 4));
        pageHolder.addView(f, Ui.lp(-1, -2, 0, 8));
        showLive();
    }

    TextView carToneNow, guardNow, dscTitle;
    LinearLayout dscCard;
    View toneChoice;

    void bose() {
        // who makes the boosts
        LinearLayout a = Ui.card(this);
        LinearLayout ar = new LinearLayout(this);
        ar.setGravity(Gravity.CENTER_VERTICAL);
        ar.addView(Ui.text(this, "Boosts and speed volume are made by", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        carToneNow = Ui.text(this, "", 14, Ui.ACCENT, true);
        ar.addView(carToneNow);
        a.addView(ar);
        a.addView(Ui.segmented(this, new String[] { "The car's amplifier (recommended)", "Android alone" }, "amp".equals(cfg.lift) ? 0 : 1, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.lift = v == 0 ? "amp" : "android";
                if (toneChoice != null) toneChoice.setVisibility("amp".equals(cfg.lift) ? View.GONE : View.VISIBLE);
                save();
                showLive();
            }
        }), Ui.lp(-1, -2, 0, 6));
        ampNow = Ui.text(this, "", 12, Ui.TEXT_2, false);
        ampNow.setMaxLines(3);
        a.addView(ampNow, Ui.lp(-1, -2, 0, 4));
        toneChoice = Ui.segmented(this, new String[] { "Car's Bass/Mid/Treble as set", "Held flat with Car Sound" }, "flat".equals(cfg.tone) ? 1 : 0, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.tone = v == 1 ? "flat" : "leave";
                save();
            }
        });
        toneChoice.setVisibility("amp".equals(cfg.lift) ? View.GONE : View.VISIBLE);
        a.addView(toneChoice, Ui.lp(-1, -2, 0, 6));
        pageHolder.addView(a);

        // the car's own speed compensation
        LinearLayout c = Ui.card(this);
        dscCard = c;
        c.setVisibility(bose ? View.VISIBLE : View.GONE);
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        dscTitle = Ui.text(this, liveSpeedComp, 17, Ui.TEXT, true);
        cr.addView(dscTitle, new LinearLayout.LayoutParams(0, -2, 1));
        cr.addView(Ui.text(this, "Use one: off with Car Sound's speed tuning on", 12, Ui.TEXT_3, false));
        c.addView(cr);
        int sel = "off".equals(cfg.dsc) ? 1 : "on".equals(cfg.dsc) ? 2 : 0;
        c.addView(Ui.segmented(this, new String[] { "As set in the car", "Off with Car Sound", "On" }, sel, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.dsc = v == 1 ? "off" : v == 2 ? "on" : "leave";
                save();
            }
        }), Ui.lp(-1, -2, 0, 6));
        pageHolder.addView(c, Ui.lp(-1, -2, 0, 8));

        // Android's audio: what's tuned, and the clip guard
        LinearLayout g = Ui.card(this);
        g.addView(Ui.text(this, "Android's equalizer is on", 17, Ui.TEXT, true));
        g.addView(Ui.segmented(this, new String[] { "All Android audio, from the start", "Phone audio only" }, "phone".equals(cfg.scope) ? 1 : 0, new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.scope = v == 1 ? "phone" : "all";
                save();
            }
        }), Ui.lp(-1, -2, 0, 6));
        LinearLayout gr = new LinearLayout(this);
        gr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout gl = new LinearLayout(this);
        gl.setOrientation(LinearLayout.VERTICAL);
        gl.addView(Ui.text(this, "Clip guard", 16, Ui.TEXT, true));
        guardNow = Ui.text(this, "", 12, Ui.TEXT_2, false);
        gl.addView(guardNow);
        gr.addView(gl, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle gt = new Ui.Toggle(this);
        gt.on = cfg.guard;
        gt.listener = new Ui.OnValue() {
            @Override
            public void value(int v, boolean done) {
                cfg.guard = v == 1;
                save();
            }
        };
        gr.addView(gt);
        g.addView(gr, Ui.lp(-1, -2, 0, 6));
        pageHolder.addView(g, Ui.lp(-1, -2, 0, 8));
        showLive();
    }

    // the Car page's live lines, from the engine's status
    JSONArray liveTone, liveLoud, liveSteps;
    int livePeak = -9600, liveClips, liveGuard, liveVolume = -1, liveAmpType = -2, liveWrites, liveVolSteps;
    boolean liveSuspended;
    String liveAmpName = "", liveSpeedComp = "The car's speed compensation";
    boolean liveVerified, liveTesting, liveTestProfile;

    void showLive() {
        if (carToneNow != null) {
            carToneNow.setText(liveTone == null ? "" : "Car now: bass " + signed(liveTone.optInt(0)) + "  mid " + signed(liveTone.optInt(1)) + "  treble " + signed(liveTone.optInt(2))
                    + (liveVolSteps > 0 ? "  volume +" + liveVolSteps : ""));
        }
        if (dscTitle != null) dscTitle.setText(liveSpeedComp);
        if (loudNow != null) {
            int lb = liveLoud == null ? 0 : liveLoud.optInt(0), lt = liveLoud == null ? 0 : liveLoud.optInt(1);
            loudNow.setText(!cfg.loudOn ? "Off" : liveVolume < 0 ? "The car's volume isn't known yet"
                    : "At your volume now (" + (liveVolume + liveVolSteps) + "): bass " + Ui.dB(lb) + ", treble " + Ui.dB(lt) + (lb == 0 && lt == 0 ? " (above where the lift ends)" : ""));
        }
        if (ampNow != null) {
            String car = liveAmpType < 0 ? "" : " This car: " + liveAmpName + (liveVerified ? "." : ", still being tuned: tell us how it sounds with a car report.") + (liveTestProfile ? " Test profile in use." : "");
            ampNow.setText(("amp".equals(cfg.lift)
                    ? "The car's own Bass, Mid, Treble and volume make the boosts, on every source, and Android's equalizer only cuts. Car Sound sets the car's tone while it's on and puts yours back when it's off."
                    : "Android's equalizer and loudness enhancer make everything, on Android's audio only. Each boost there turns the rest of the music down.")
                    + car + (liveSuspended ? " Android audio is tuned per app until the head unit restarts." : ""));
        }
        if (guardNow != null) {
            guardNow.setText("Backs the level off if Android's output clips.  " + (livePeak <= -9600 ? "Nothing playing" : "Peak " + Ui.dB(livePeak))
                    + "  •  " + (liveClips == 0 ? "no clipping in the last minute" : liveClips + " s clipped in the last minute")
                    + (liveGuard > 0 ? "  •  backed off " + Ui.dB(-liveGuard) : ""));
        }
    }

    static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
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
                setBose(st.optBoolean("speed_comp_ok"));
                JSONArray ss = st.optJSONArray("sessions");
                boolean playing = st.optBoolean("playing", ss != null && ss.length() > 0);
                boolean all = "all".equals(st.optString("scope_now"));
                String fxError = st.optString("fx_error", "");
                if (!cfg.on) title = "Off";
                else if (playing) { title = all ? "Tuning the car's audio" : "Tuning phone audio"; dot = Ui.ACCENT; }
                else if (fxError.length() > 0) { title = "Can't tune this car's audio"; dot = Ui.WARN; }
                else title = all ? "Ready" : "Waiting for phone audio";
                liveTone = st.optJSONArray("car_tone");
                livePeak = st.optInt("peak_mB", -9600);
                liveClips = st.optInt("clip_s", 0);
                liveGuard = st.optInt("guard_mB", 0);
                liveLoud = st.optJSONArray("loud_mB");
                liveSteps = st.optJSONArray("amp_steps");
                liveVolume = st.optInt("volume", -1);
                liveAmpType = st.optInt("amp_type", -2);
                liveAmpName = st.optString("amp_name");
                liveVerified = st.optBoolean("amp_verified");
                liveTesting = st.optBoolean("amp_testing");
                liveTestProfile = st.optBoolean("amp_test_profile");
                liveWrites = st.optInt("amp_writes");
                liveVolSteps = st.optInt("vol_steps");
                liveSuspended = "suspended".equals(st.optString("scope_why"));
                if (st.optString("speed_comp_name").length() > 0) liveSpeedComp = st.optString("speed_comp_name");
                if (reportAsked != 0 && st.optLong("report_for") == reportAsked) {
                    saveNote.setText(st.optString("report"));
                }
                showLive();
                String dsc = st.optString("dsc");
                detail = (!cfg.speedOn ? "Speed tuning off"
                        : speed < 0 ? "Speed tuning: no speed from HMG-API"
                        : "Speed: volume " + Ui.dB(st.optDouble("speed_vol_mB", 0)) + ", bass " + Ui.dB(st.optDouble("speed_bass_mB", 0))
                        + ", treble " + Ui.dB(st.optDouble("speed_treble_mB", 0)))
                        + (!bose ? "" : "off".equals(dsc) ? "  •  speed compensation held off" : "on".equals(dsc) ? "  •  speed compensation on" : "")
                        + (liveVolSteps > 0 ? "  •  car volume +" + liveVolSteps : "")
                        + (liveGuard > 0 ? "  •  clip guard " + Ui.dB(-liveGuard) : "");
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

    /** show or hide the Car page's speed compensation switch (from the status tick: changing visibility, not rebuilding) */
    void setBose(boolean has) {
        bose = has;
        if (dscCard != null) dscCard.setVisibility(bose ? View.VISIBLE : View.GONE);
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
