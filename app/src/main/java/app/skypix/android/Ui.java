package app.skypix.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import app.skypix.core.Cards;
import app.skypix.core.Gfx;
import app.skypix.core.Insights;
import app.skypix.core.PixelFont;

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

    /** Text in the 5x7 pixel font. */
    static final class PixelText extends View {
        private String text;
        private final float cellDp;
        private int color;
        private final int align;

        PixelText(Context c, String text, float cellDp, int color, int align) {
            super(c);
            this.text = text;
            this.cellDp = cellDp;
            this.color = color;
            this.align = align;
        }

        void set(String t) {
            text = t;
            requestLayout();
            invalidate();
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        private int cell() {
            return Math.max(1, Math.round(cellDp * getResources().getDisplayMetrics().density));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int cell = cell();
            int w = Math.round(PixelFont.width(new AndroidGfx(new Canvas()), text, cell));
            int mode = MeasureSpec.getMode(wSpec);
            int size = MeasureSpec.getSize(wSpec);
            int mw = mode == MeasureSpec.EXACTLY ? size : (mode == MeasureSpec.AT_MOST ? Math.min(w, size) : w);
            setMeasuredDimension(mw, PixelFont.H * cell + dp(getContext(), 2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            AndroidGfx g = new AndroidGfx(canvas);
            float x = align == Gfx.CENTER ? getWidth() / 2f : align == Gfx.RIGHT ? getWidth() : 0;
            PixelFont.draw(g, text, x, 0, cell(), align, color);
        }
    }

    /** Pill button with a pixel-font label. */
    static final class PixelButton extends View {
        private String label;
        private boolean filled;
        private int accent = 0xFF3D7BFF;

        PixelButton(Context c, String label, boolean filled) {
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

        void setAccent(int c) {
            accent = c;
            invalidate();
        }

        private int cell() {
            return Math.max(1, Math.round(1.8f * getResources().getDisplayMetrics().density));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = Math.round(PixelFont.width(new AndroidGfx(new Canvas()), label, cell())) + dp(getContext(), 32);
            int mode = MeasureSpec.getMode(wSpec);
            int size = MeasureSpec.getSize(wSpec);
            int mw = mode == MeasureSpec.EXACTLY ? size : (mode == MeasureSpec.AT_MOST ? Math.min(w, size) : w);
            setMeasuredDimension(mw, dp(getContext(), 48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            AndroidGfx g = new AndroidGfx(canvas);
            float u = getResources().getDisplayMetrics().density;
            boolean dark = isDark(getContext());
            float top = (getHeight() - 38 * u) / 2f, bottom = top + 38 * u;
            int ink = dark ? 0xFFFFFFFF : 0xFF0E1630;
            if (filled) {
                g.fillRoundRect(0, top, getWidth(), bottom, 19 * u, isPressed() ? (accent & 0x00FFFFFF) | 0xCC000000 : accent);
                ink = 0xFFFFFFFF;
            } else {
                g.fillRoundRect(0, top, getWidth(), bottom, 19 * u, isPressed() ? 0x33808080 : (dark ? 0x1FFFFFFF : 0x14000000));
                g.strokeRoundRect(0.5f * u, top + 0.5f * u, getWidth() - 0.5f * u, bottom - 0.5f * u, 19 * u, 1f * u, dark ? 0x40FFFFFF : 0x33000000);
            }
            int cell = cell();
            float ty = top + (38 * u - PixelFont.H * cell) / 2f;
            PixelFont.draw(g, label, getWidth() / 2f, ty, cell, Gfx.CENTER, ink);
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }
    }

    /** Light: soft colour blobs. Dark: pure AMOLED black. */
    static final class Backdrop extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Backdrop(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean dark = isDark(getContext());
            float w = getWidth(), h = getHeight();
            p.setShader(null);
            p.setColor(dark ? 0xFF000000 : 0xFFE7EDF8);
            canvas.drawRect(0, 0, w, h, p);
            if (!dark) {
                blob(canvas, w * 0.1f, h * 0.12f, w * 0.9f, 0x88FFB6C1);
                blob(canvas, w * 0.95f, h * 0.45f, w * 1.0f, 0x889FD3FF);
                blob(canvas, w * 0.3f, h * 0.92f, w * 0.9f, 0x88B8F0C8);
            }
        }

        private void blob(Canvas c, float cx, float cy, float r, int color) {
            p.setShader(new RadialGradient(cx, cy, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
        }
    }
}
