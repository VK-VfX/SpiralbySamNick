package app.skypix.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import app.skypix.core.Gfx;

import java.util.HashMap;

/** Canvas-backed {@link Gfx}. */
final class AndroidGfx implements Gfx {
    private static final HashMap<int[], Bitmap> SPRITES = new HashMap<int[], Bitmap>();
    private static Typeface[] faces;

    private final Canvas c;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Path path = new Path();
    private int clipDepth;

    AndroidGfx(Canvas c) {
        this.c = c;
        spritePaint.setFilterBitmap(false);
        spritePaint.setAntiAlias(false);
        spritePaint.setDither(false);
        p.setSubpixelText(true);
    }

    private static synchronized Typeface face(int font) {
        if (faces == null) {
            faces = new Typeface[5];
            faces[FONT_LIGHT] = Typeface.create("sans-serif-light", Typeface.NORMAL);
            faces[FONT_REGULAR] = Typeface.create("sans-serif", Typeface.NORMAL);
            faces[FONT_MEDIUM] = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            faces[FONT_BOLD] = Typeface.create("sans-serif", Typeface.BOLD);
            faces[FONT_BLACK] = Typeface.create("sans-serif-black", Typeface.NORMAL);
        }
        return faces[font];
    }

    @Override public void fillRect(float l, float t, float r, float b, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb);
        p.setAntiAlias(false);
        c.drawRect(l, t, r, b, p);
        p.setAntiAlias(true);
    }

    @Override public void fillRoundRect(float l, float t, float r, float b, float radius, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, radius, radius, p);
    }

    @Override public void strokeRoundRect(float l, float t, float r, float b, float radius, float width, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(argb);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override public void fillRoundRectGradient(float l, float t, float r, float b, float radius, int top, int bottom) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, t, 0, b, top, bottom, Shader.TileMode.CLAMP));
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, radius, radius, p);
        p.setShader(null);
    }

    @Override public void fillCircle(float cx, float cy, float r, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb);
        c.drawCircle(cx, cy, r, p);
    }

    @Override public void glow(float cx, float cy, float r, int argb) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, r, argb, argb & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    @Override public void line(float x1, float y1, float x2, float y2, float width, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(argb);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override public void text(String s, float x, float y, float size, int font, int align, int argb) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(face(font));
        p.setTextSize(size);
        p.setColor(argb);
        float w = p.measureText(s);
        float ox = align == CENTER ? x - w / 2f : align == RIGHT ? x - w : x;
        c.drawText(s, ox, y, p);
    }

    @Override public float textWidth(String s, float size, int font) {
        p.setTypeface(face(font));
        p.setTextSize(size);
        return p.measureText(s);
    }

    @Override public void sprite(int[] argb, int w, int h, float x, float y, int cell) {
        Bitmap bmp;
        synchronized (SPRITES) {
            bmp = SPRITES.get(argb);
            if (bmp == null) {
                bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
                SPRITES.put(argb, bmp);
            }
        }
        src.set(0, 0, w, h);
        int ix = Math.round(x), iy = Math.round(y);
        dst.set(ix, iy, ix + w * cell, iy + h * cell);
        c.drawBitmap(bmp, src, dst, spritePaint);
    }

    @Override public void clipRoundRect(float l, float t, float r, float b, float radius) {
        c.save();
        clipDepth++;
        rf.set(l, t, r, b);
        path.reset();
        path.addRoundRect(rf, radius, radius, Path.Direction.CW);
        c.clipPath(path);
    }

    @Override public void restoreClip() {
        if (clipDepth > 0) {
            clipDepth--;
            c.restore();
        }
    }
}
