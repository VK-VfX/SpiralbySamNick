package app.skypix.tools;

import app.skypix.core.Gfx;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/** JVM backend for {@link Gfx}, used only by the preview/icon tools. */
final class AwtGfx implements Gfx {
    final BufferedImage img;
    private final Graphics2D g;
    private final Deque<Shape> clips = new ArrayDeque<Shape>();
    private final String family;

    AwtGfx(int w, int h, String family) {
        this.family = family;
        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static Color c(int argb) {
        return new Color(argb, true);
    }

    private Font font(int font, float size) {
        int style = (font == FONT_BOLD || font == FONT_BLACK) ? Font.BOLD : Font.PLAIN;
        return new Font(family, style, 1).deriveFont(style, size);
    }

    @Override public void fillRect(float l, float t, float r, float b, int argb) {
        g.setPaint(c(argb));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override public void fillRoundRect(float l, float t, float r, float b, float rad, int argb) {
        g.setPaint(c(argb));
        g.fill(new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2));
    }

    @Override public void strokeRoundRect(float l, float t, float r, float b, float rad, float width, int argb) {
        g.setPaint(c(argb));
        g.setStroke(new java.awt.BasicStroke(width));
        g.draw(new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2));
    }

    @Override public void fillRoundRectGradient(float l, float t, float r, float b, float rad, int top, int bottom) {
        g.setPaint(new GradientPaint(0, t, c(top), 0, b, c(bottom)));
        g.fill(new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2));
    }

    @Override public void fillCircle(float cx, float cy, float r, int argb) {
        g.setPaint(c(argb));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    @Override public void glow(float cx, float cy, float r, int argb) {
        Color a = c(argb);
        Color z = new Color(a.getRed(), a.getGreen(), a.getBlue(), 0);
        g.setPaint(new RadialGradientPaint(cx, cy, r, new float[]{0f, 1f}, new Color[]{a, z}));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    @Override public void line(float x1, float y1, float x2, float y2, float width, int argb) {
        g.setPaint(c(argb));
        g.setStroke(new java.awt.BasicStroke(width, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    @Override public void text(String s, float x, float y, float size, int font, int align, int argb) {
        Font f = font(font, size);
        g.setFont(f);
        g.setPaint(c(argb));
        float w = textWidth(s, size, font);
        float ox = align == CENTER ? x - w / 2 : align == RIGHT ? x - w : x;
        g.drawString(s, ox, y);
    }

    @Override public float textWidth(String s, float size, int font) {
        Font f = font(font, size);
        return (float) f.getStringBounds(s, new FontRenderContext(null, true, true)).getWidth();
    }

    @Override public void sprite(int[] argb, int w, int h, float x, float y, int cell) {
        BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        tmp.setRGB(0, 0, w, h, argb, 0, w);
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(tmp, Math.round(x), Math.round(y), w * cell, h * cell, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
    }

    @Override public void clipRoundRect(float l, float t, float r, float b, float rad) {
        clips.push(g.getClip() == null ? new Rectangle2D.Float(-1e6f, -1e6f, 2e6f, 2e6f) : g.getClip());
        g.clip(new RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2));
    }

    @Override public void restoreClip() {
        if (!clips.isEmpty()) g.setClip(clips.pop());
    }

    void dispose() {
        g.dispose();
    }
}
