package com.carsound.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carsound.Config;

/** Car Sound's look: a green accent on dark (night) or light (day) surfaces, and its own widgets. */
final class Ui {
    private Ui() {}

    static boolean night = true;
    static float dp = 1;
    static int BG_TOP, BG_BOTTOM, CARD, CARD_EDGE, RAIL, ACCENT, ACCENT_PRESSED, ACCENT_DIM, ON_ACCENT, TEXT, TEXT_2, TEXT_3, LINE, WARN;

    static void init(Context c) {
        dp = c.getResources().getDisplayMetrics().density;
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = mode != Configuration.UI_MODE_NIGHT_NO;   // the car's own day/night; dark when it doesn't say
        if (night) {
            BG_TOP = 0xFF0D1411; BG_BOTTOM = 0xFF050807; RAIL = 0xFF0A100D;
            CARD = 0xFF131C18; CARD_EDGE = 0xFF20302A;
            ACCENT = 0xFF34D399; ACCENT_PRESSED = 0xFF6EE7B7; ACCENT_DIM = 0x2E34D399; ON_ACCENT = 0xFF03140C;
            TEXT = 0xFFF0F5F2; TEXT_2 = 0xFF9AAAA2; TEXT_3 = 0xFF64736C; LINE = 0xFF22312B; WARN = 0xFFF2A65A;
        } else {
            BG_TOP = 0xFFF2F7F4; BG_BOTTOM = 0xFFE2EAE5; RAIL = 0xFFEAF1ED;
            CARD = 0xFFFFFFFF; CARD_EDGE = 0xFFD3DED8;
            ACCENT = 0xFF0E8A5F; ACCENT_PRESSED = 0xFF0B7350; ACCENT_DIM = 0x330E8A5F; ON_ACCENT = 0xFFFFFFFF;
            TEXT = 0xFF101A15; TEXT_2 = 0xFF475A50; TEXT_3 = 0xFF6B7B73; LINE = 0xFFE0E8E3; WARN = 0xFFB8650F;
        }
    }

    static int px(float d) {
        return Math.round(d * dp);
    }

    static GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (stroke != 0) g.setStroke(px(1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundDrawable(round(CARD, CARD_EDGE, 20));
        l.setPadding(px(20), px(12), px(20), px(12));
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.topMargin = px(top);
        return p;
    }

    static String dB(double mB) {
        double d = Math.round(mB / 50.0) / 2.0;
        String s = d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d);
        return (d > 0 ? "+" : "") + s + " dB";
    }

    static int mph(double kmh) {
        return (int) Math.round(kmh * 0.621371);
    }

    // ---- a horizontal slider: track, accent fill, a round thumb

    interface OnValue {
        void value(int v, boolean done);
    }

    static final class Slider extends View {
        int min, max, step, value;
        OnValue listener;
        boolean dragging;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Slider(Context c, int min, int max, int step) {
            super(c);
            this.min = min;
            this.max = max;
            this.step = step;
            setMinimumHeight(px(40));
        }

        void set(int v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), px(40));
        }

        float x(int v) {
            float l = px(14), r = getWidth() - px(14);
            return l + (r - l) * (v - min) / (float) (max - min);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f, l = px(14), r = getWidth() - px(14), t = px(3);
            p.setColor(LINE);
            c.drawRoundRect(new RectF(l, cy - t, r, cy + t), t, t, p);
            p.setColor(ACCENT);
            c.drawRoundRect(new RectF(l, cy - t, x(value), cy + t), t, t, p);
            if (dragging) {
                p.setColor(ACCENT_DIM);
                c.drawCircle(x(value), cy, px(22), p);
            }
            p.setColor(ACCENT);
            c.drawCircle(x(value), cy, px(12), p);
            p.setColor(ON_ACCENT);
            c.drawCircle(x(value), cy, px(4), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float l = px(14), r = getWidth() - px(14);
            float f = Math.max(0, Math.min(1, (e.getX() - l) / (r - l)));
            int v = min + Math.round((max - min) * f / step) * step;
            boolean done = e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL;
            dragging = !done;
            getParent().requestDisallowInterceptTouchEvent(!done);
            if (v != value || done) {
                set(v);
                if (listener != null) listener.value(value, done);
            }
            invalidate();
            return true;
        }
    }

    // ---- a pill switch

    static final class Toggle extends View {
        boolean on;
        OnValue listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c) {
            super(c);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    on = !on;
                    invalidate();
                    if (listener != null) listener.value(on ? 1 : 0, true);
                }
            });
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(px(64), px(36));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            p.setColor(on ? ACCENT : LINE);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(on ? ON_ACCENT : TEXT_3);
            float r = h / 2 - px(5);
            c.drawCircle(on ? w - h / 2 : h / 2, h / 2, r, p);
        }
    }

    // ---- segmented buttons

    static LinearLayout segmented(final Context c, String[] labels, int selected, final OnValue listener) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundDrawable(round(0, CARD_EDGE, 24));
        row.setPadding(px(4), px(4), px(4), px(4));
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView t = text(c, labels[i], 17, i == selected ? ON_ACCENT : TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), px(9), px(16), px(9));
            if (i == selected) t.setBackgroundDrawable(round(ACCENT, 0, 20));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < row.getChildCount(); j++) {
                        TextView o = (TextView) row.getChildAt(j);
                        o.setTextColor(j == k ? ON_ACCENT : TEXT);
                        o.setBackgroundDrawable(j == k ? round(ACCENT, 0, 20) : null);
                    }
                    listener.value(k, true);
                }
            });
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    static TextView chip(Context c, String label, boolean selected, View.OnClickListener click) {
        TextView t = text(c, label, 16, selected ? ON_ACCENT : TEXT, true);
        t.setPadding(px(16), px(7), px(16), px(7));
        t.setBackgroundDrawable(selected ? round(ACCENT, 0, 20) : round(0, CARD_EDGE, 20));
        t.setOnClickListener(click);
        return t;
    }

    // ---- the equalizer: five draggable points on a smooth curve

    static final class EqView extends View {
        static final int RANGE = 1200;   // ±12 dB shown and set (the effect takes ±15: headroom)
        int[] bands = new int[5];
        int active = -1;
        OnValue listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        EqView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), px(176));
        }

        float left() { return px(64); }
        float right() { return getWidth() - px(36); }
        float top() { return px(34); }
        float bottom() { return getHeight() - px(32); }

        float bx(int i) {
            return left() + (right() - left()) * i / 4f;
        }

        float by(int mB) {
            float f = (RANGE - Math.max(-RANGE, Math.min(RANGE, mB))) / (2f * RANGE);
            return top() + (bottom() - top()) * f;
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setTextSize(px(14));
            p.setStyle(Paint.Style.FILL);
            for (int db = -12; db <= 12; db += 6) {
                float y = by(db * 100);
                p.setColor(db == 0 ? CARD_EDGE : LINE);
                p.setStrokeWidth(px(db == 0 ? 2 : 1));
                c.drawLine(left(), y, right(), y, p);
                p.setColor(TEXT_3);
                c.drawText((db > 0 ? "+" : "") + db, px(16), y + px(5), p);
            }
            Path curve = new Path();
            float[] xs = new float[5], ys = new float[5];
            for (int i = 0; i < 5; i++) {
                xs[i] = bx(i);
                ys[i] = by(bands[i]);
            }
            curve.moveTo(xs[0], ys[0]);
            for (int i = 0; i < 4; i++) {   // Catmull-Rom through the points, as cubic Béziers
                float x0 = i > 0 ? xs[i - 1] : xs[i], y0 = i > 0 ? ys[i - 1] : ys[i];
                float x3 = i < 3 ? xs[i + 2] : xs[i + 1], y3 = i < 3 ? ys[i + 2] : ys[i + 1];
                curve.cubicTo(xs[i] + (xs[i + 1] - x0) / 6, ys[i] + (ys[i + 1] - y0) / 6,
                        xs[i + 1] - (x3 - xs[i]) / 6, ys[i + 1] - (y3 - ys[i]) / 6, xs[i + 1], ys[i + 1]);
            }
            Path fill = new Path(curve);
            fill.lineTo(xs[4], by(0));
            fill.lineTo(xs[0], by(0));
            fill.close();
            p.setShader(new LinearGradient(0, top(), 0, bottom(), ACCENT_DIM, 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(4));
            p.setColor(ACCENT);
            c.drawPath(curve, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 5; i++) {
                if (i == active) {
                    p.setColor(ACCENT_DIM);
                    c.drawCircle(xs[i], ys[i], px(26), p);
                }
                p.setColor(ACCENT);
                c.drawCircle(xs[i], ys[i], px(13), p);
                p.setColor(ON_ACCENT);
                c.drawCircle(xs[i], ys[i], px(5), p);
                p.setColor(i == active ? ACCENT : TEXT);
                p.setTextSize(px(16));
                String v = dB(bands[i]);
                float ly = ys[i] - px(22) < top() ? ys[i] + px(34) : ys[i] - px(22);   // near the top: below the point
                c.drawText(v, xs[i] - p.measureText(v) / 2, ly, p);
                p.setColor(TEXT_2);
                p.setTextSize(px(15));
                int f = Config.FREQ[i];
                String lab = f >= 1000 ? (f % 1000 == 0 ? f / 1000 + " kHz" : f / 1000.0 + " kHz") : f + " Hz";
                c.drawText(lab, xs[i] - p.measureText(lab) / 2, getHeight() - px(10), p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float best = Float.MAX_VALUE;
                for (int i = 0; i < 5; i++) {
                    float d = Math.abs(e.getX() - bx(i));
                    if (d < best) { best = d; active = i; }
                }
            }
            boolean done = e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL;
            getParent().requestDisallowInterceptTouchEvent(!done);
            if (active >= 0) {
                float f = (e.getY() - top()) / (bottom() - top());
                int v = Math.round((RANGE - f * 2 * RANGE) / 50f) * 50;
                v = Math.max(-RANGE, Math.min(RANGE, v));
                if (v != bands[active] || done) {
                    bands[active] = v;
                    if (listener != null) listener.value(active, done);
                }
            }
            if (done) active = -1;
            invalidate();
            return true;
        }
    }

    // ---- speed tuning: volume, bass and treble against speed, the selected one draggable

    static final class SpeedView extends View {
        static final int MAX_MPH = 90;
        Config cfg;
        int sel = Config.VOL;
        float mph = -1;     // the car's speed now; below 0: unknown
        int active = -1;
        OnValue listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpeedView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), px(206));
        }

        float l() { return px(60); }
        float r() { return getWidth() - px(20); }
        float t() { return px(30); }
        float b() { return getHeight() - px(30); }
        float x(double m) { return (float) (l() + (r() - l()) * m / MAX_MPH); }
        float y(double mB) { return (float) (b() - (b() - t()) * mB / Config.SPEED_MAX); }

        Path curve(int k) {
            Path path = new Path();
            path.moveTo(x(0), y(0));
            for (int i = 0; i < Config.MPH.length; i++) path.lineTo(x(Config.MPH[i]), y(cfg.speed[k][i]));
            path.lineTo(x(MAX_MPH), y(cfg.speed[k][Config.MPH.length - 1]));
            return path;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cfg == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(px(14));
            for (int db = 0; db <= Config.SPEED_MAX / 100; db += 5) {
                p.setColor(db == 0 ? CARD_EDGE : LINE);
                p.setStrokeWidth(px(1));
                c.drawLine(l(), y(db * 100), r(), y(db * 100), p);
                p.setColor(TEXT_3);
                c.drawText("+" + db, px(14), y(db * 100) + px(5), p);
            }
            for (int m = 0; m <= 80; m += 20) {
                p.setColor(TEXT_3);
                String s = m + " mph";
                c.drawText(s, x(m) - p.measureText(s) / 2, getHeight() - px(6), p);
            }
            // the other two curves, faint
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2));
            for (int k = 0; k < 3; k++) {
                if (k == sel) continue;
                p.setColor(cfg.speedOn ? TEXT_3 : LINE);
                c.drawPath(curve(k), p);
            }
            // the selected one, its fill and its points
            Path sc = curve(sel), fill = new Path(sc);
            fill.lineTo(x(MAX_MPH), y(0));
            fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, t(), 0, b(), ACCENT_DIM, 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(4));
            p.setColor(cfg.speedOn ? ACCENT : TEXT_3);
            c.drawPath(sc, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < Config.MPH.length; i++) {
                float xx = x(Config.MPH[i]), yy = y(cfg.speed[sel][i]);
                if (i == active) {
                    p.setColor(ACCENT_DIM);
                    c.drawCircle(xx, yy, px(24), p);
                }
                p.setColor(cfg.speedOn ? ACCENT : TEXT_3);
                c.drawCircle(xx, yy, px(12), p);
                p.setColor(ON_ACCENT);
                c.drawCircle(xx, yy, px(4), p);
                p.setColor(i == active ? ACCENT : TEXT);
                p.setTextSize(px(15));
                String v = dB(cfg.speed[sel][i]);
                float ly = yy - px(20) < t() - px(8) ? yy + px(32) : yy - px(20);
                c.drawText(v, xx - p.measureText(v) / 2, ly, p);
            }
            // the car's speed now
            if (mph >= 0) {
                float sx = x(Math.min(MAX_MPH, mph));
                p.setColor(TEXT_2);
                p.setStrokeWidth(px(2));
                c.drawLine(sx, t(), sx, b(), p);
                p.setColor(ACCENT_PRESSED);
                c.drawCircle(sx, y(cfg.speedOn ? Config.curveAt(cfg.speed[sel], mph) : 0), px(7), p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float best = Float.MAX_VALUE;
                for (int i = 0; i < Config.MPH.length; i++) {
                    float d = Math.abs(e.getX() - x(Config.MPH[i]));
                    if (d < best) { best = d; active = i; }
                }
            }
            boolean done = e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL;
            getParent().requestDisallowInterceptTouchEvent(!done);
            if (active >= 0) {
                float f = (b() - e.getY()) / (b() - t());
                int v = Math.max(0, Math.min(Config.SPEED_MAX, Math.round(f * Config.SPEED_MAX / 50f) * 50));
                if (v != cfg.speed[sel][active] || done) {
                    cfg.speed[sel][active] = v;
                    if (listener != null) listener.value(active, done);
                }
            }
            if (done) active = -1;
            invalidate();
            return true;
        }
    }
}
