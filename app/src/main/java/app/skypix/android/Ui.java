package app.skypix.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import app.skypix.core.Cards;
import app.skypix.core.Gfx;
import app.skypix.core.Insights;

/** Small custom views so the app screen shares the widgets' pixel look. */
final class Ui {
    private Ui() {}

    /** Last non-fatal error, shown in the app footer so it can be reported instead of crashing. */
    static volatile String lastError;

    static void report(String where, Throwable t) {
        lastError = where + ": " + t;
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) lastError += "\n  at " + st[i];
    }

    /** Uptime (ms) when the in-app 5 s weather animation started; 0 = never. */
    static volatile long animStartMs;

    static float animT() {
        if (animStartMs == 0) return -1;
        float t = (android.os.SystemClock.uptimeMillis() - animStartMs) / 1000f;
        return t < 5f ? t : -1;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** One widget design drawn at full width. */
    static final class CardView extends View {
        private final int kind;
        private final float heightDp;
        private final Insights.Insight insight;
        private Cards.Data data;

        CardView(Context c, int kind, float heightDp, Insights.Insight insight, Cards.Data data) {
            super(c);
            this.kind = kind;
            this.heightDp = heightDp;
            this.insight = insight;
            this.data = data;
            setContentDescription(insight != null ? insight.title + ". " + insight.detail : Cards.describe(kind, data));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), dp(getContext(), heightDp));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float u = getResources().getDisplayMetrics().density;
            AndroidGfx g = new AndroidGfx(canvas);
            float w = getWidth() / u, h = getHeight() / u;
            float t = animT();
            try {
                if (data != null) data.anim = t;
                if (insight != null) Cards.renderInsight(g, insight, w, h, u, isDark(getContext()), data);
                else Cards.render(g, kind, w, h, u, isDark(getContext()), data);
            } catch (Throwable e) {
                report("draw card " + kind, e);
                return;
            }
            if (t >= 0) postInvalidateDelayed(33);
        }
    }

    // ---- Material 3 palette (Pixel look; dark is AMOLED) ------------------------------------------------------------

    static int bg(Context c) { return isDark(c) ? 0xFF000000 : 0xFFF0F4F9; }
    static int container(Context c) { return isDark(c) ? 0xFF131416 : 0xFFFFFFFF; }
    static int onSurface(Context c) { return isDark(c) ? 0xFFE3E3E3 : 0xFF1F1F1F; }
    static int onSurfaceVar(Context c) { return isDark(c) ? 0xFFC4C7C5 : 0xFF444746; }
    static int primary(Context c) { return isDark(c) ? 0xFFA8C7FA : 0xFF0B57D0; }
    static int onPrimary(Context c) { return isDark(c) ? 0xFF062E6F : 0xFFFFFFFF; }
    static int primaryContainer(Context c) { return isDark(c) ? 0xFF0B2A57 : 0xFFD3E3FD; }
    static int onPrimaryContainer(Context c) { return isDark(c) ? 0xFFD3E3FD : 0xFF041E49; }
    static int tonal(Context c) { return isDark(c) ? 0xFF1B3157 : 0xFFD3E3FD; }
    static int onTonal(Context c) { return isDark(c) ? 0xFFD3E3FD : 0xFF041E49; }
    static int divider(Context c) { return isDark(c) ? 0xFF2A2B2E : 0xFFE3E7EC; }
    static int outline(Context c) { return isDark(c) ? 0xFF8E918F : 0xFF747775; }

    /** A TextView in Google Sans (on Pixel) at the given weight. */
    static android.widget.TextView text(Context c, String s, float sp, int weight, int color) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(AndroidGfx.typeface(weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Rounded surface used for cards and groups. */
    static android.graphics.drawable.GradientDrawable surface(Context c, int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(color);
        return d;
    }

    /** Material pill button: filled (primary) or tonal. */
    static final class Btn extends View {
        private String label;
        private boolean filled;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Btn(Context c, String label, boolean filled) {
            super(c);
            this.label = label;
            this.filled = filled;
            setClickable(true);
            setFocusable(true);
            setContentDescription(label);
        }

        void setLabel(String l) {
            label = l;
            setContentDescription(l);
            requestLayout();
            invalidate();
        }

        void setFilled(boolean f) {
            filled = f;
            invalidate();
        }

        private float textPx() {
            return 14f * getResources().getDisplayMetrics().scaledDensity;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = Math.round(new AndroidGfx(new Canvas()).textWidth(label, textPx(), Gfx.FONT_MEDIUM)) + dp(getContext(), 40);
            int mode = MeasureSpec.getMode(wSpec);
            int size = MeasureSpec.getSize(wSpec);
            int mw = mode == MeasureSpec.EXACTLY ? size : (mode == MeasureSpec.AT_MOST ? Math.min(w, size) : w);
            setMeasuredDimension(Math.max(mw, dp(getContext(), 64)), dp(getContext(), 48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            AndroidGfx g = new AndroidGfx(canvas);
            float u = getResources().getDisplayMetrics().density;
            float top = (getHeight() - 40 * u) / 2f, bottom = top + 40 * u;
            int fill = filled ? primary(c) : tonal(c);
            int ink = filled ? onPrimary(c) : onTonal(c);
            if (isPressed()) fill = mixColor(fill, ink, 0.14f);
            g.fillRoundRect(0, top, getWidth(), bottom, 20 * u, fill);
            float tp = textPx();
            g.text(label, getWidth() / 2f, top + 20 * u + tp * 0.36f, tp, Gfx.FONT_MEDIUM, Gfx.CENTER, ink);
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }
    }

    static int mixColor(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Round tonal refresh button; the arrow spins while {@link #spinning}. */
    static final class RefreshButton extends View {
        boolean spinning;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rf = new android.graphics.RectF();

        RefreshButton(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
            setContentDescription("Refresh");
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 48), dp(getContext(), 48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float u = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(isPressed() ? mixColor(tonal(c), onTonal(c), 0.14f) : tonal(c));
            canvas.drawCircle(cx, cy, 20 * u, p);
            float rot = spinning ? (android.os.SystemClock.uptimeMillis() % 900) / 900f * 360f : 0;
            canvas.save();
            canvas.rotate(rot, cx, cy);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * u);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(onTonal(c));
            float r = 7.5f * u;
            rf.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(rf, -40, 290, false, p);
            // arrow head at the arc's open end
            double a = Math.toRadians(-40);
            float ax = cx + (float) Math.cos(a) * r, ay = cy + (float) Math.sin(a) * r;
            p.setStyle(Paint.Style.FILL);
            android.graphics.Path head = new android.graphics.Path();
            head.moveTo(ax + 4.2f * u, ay - 4.6f * u);
            head.lineTo(ax + 4.2f * u, ay + 3.2f * u);
            head.lineTo(ax - 3.6f * u, ay + 0.6f * u);
            head.close();
            canvas.drawPath(head, p);
            canvas.restore();
            if (spinning) postInvalidateOnAnimation();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }
    }

    /** Material 3 switch (52 x 32). */
    static final class Toggle extends android.widget.CompoundButton {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c) {
            super(c);
            setButtonDrawable(null);
            setBackground(null);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float u = getResources().getDisplayMetrics().density;
            float top = (getHeight() - 32 * u) / 2f;
            boolean on = isChecked();
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? primary(c) : container(c) == 0xFFFFFFFF ? 0xFFE1E3E1 : 0xFF2B2D30);
            canvas.drawRoundRect(0, top, getWidth(), top + 32 * u, 16 * u, 16 * u, p);
            if (!on) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * u);
                p.setColor(outline(c));
                canvas.drawRoundRect(u, top + u, getWidth() - u, top + 31 * u, 15 * u, 15 * u, p);
                p.setStyle(Paint.Style.FILL);
            }
            float tr = (on ? 12 : 8) * u;
            float tx = on ? getWidth() - 16 * u : 16 * u;
            p.setColor(on ? onPrimary(c) : outline(c));
            canvas.drawCircle(tx, top + 16 * u, tr, p);
        }
    }

    /** Easter egg: a few seconds of Noto cats raining down the screen. */
    static final class CatRain extends View {
        private static final String[] NAMES = {"plain", "grin", "smile", "joy", "love", "smirk", "kiss", "weary", "cry", "pout", "walk", "black"};
        private final long start = android.os.SystemClock.uptimeMillis();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF rf = new android.graphics.RectF();

        CatRain(Context c) {
            super(c);
            setClickable(false);
        }

        private static float rnd(int i, int salt) {
            double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
            return (float) (v - Math.floor(v));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float u = getResources().getDisplayMetrics().density;
            float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
            if (t > 4.5f) {
                post(new Runnable() {
                    @Override public void run() {
                        if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(CatRain.this);
                    }
                });
                return;
            }
            float h = getHeight(), w = getWidth();
            for (int i = 0; i < 30; i++) {
                android.graphics.Bitmap b = AndroidGfx.emojiBitmap(NAMES[(int) (rnd(i, 1) * NAMES.length) % NAMES.length]);
                if (b == null) continue;
                float size = (26 + rnd(i, 2) * 30) * u;
                float x = rnd(i, 3) * (w - size);
                float speed = (230 + rnd(i, 4) * 260) * u;
                float y = -size + t * speed - rnd(i, 5) * h * 0.5f;
                if (y > h || y < -size) continue;
                canvas.save();
                canvas.rotate((rnd(i, 6) - 0.5f) * 50f + t * (rnd(i, 7) - 0.5f) * 90f, x + size / 2, y + size / 2);
                rf.set(x, y, x + size, y + size);
                canvas.drawBitmap(b, null, rf, p);
                canvas.restore();
            }
            postInvalidateOnAnimation();
        }
    }

    /** Flat page background: Google surface in light, pure black in dark. */
    static final class Backdrop extends View {
        Backdrop(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(bg(getContext()));
        }
    }
}
