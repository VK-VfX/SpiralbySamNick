package app.skypix.core;

/** Minimal drawing surface so the widget art renders identically on Android and on the JVM preview tool. */
public interface Gfx {
    int FONT_LIGHT = 0;
    int FONT_REGULAR = 1;
    int FONT_MEDIUM = 2;
    int FONT_BOLD = 3;
    int FONT_BLACK = 4;

    int LEFT = 0;
    int CENTER = 1;
    int RIGHT = 2;

    void fillRect(float l, float t, float r, float b, int argb);

    void fillRoundRect(float l, float t, float r, float b, float radius, int argb);

    void strokeRoundRect(float l, float t, float r, float b, float radius, float width, int argb);

    void fillRoundRectGradient(float l, float t, float r, float b, float radius, int topArgb, int bottomArgb);

    void fillCircle(float cx, float cy, float r, int argb);

    /** Radial gradient from {@code argb} at the centre to fully transparent at the edge. */
    void glow(float cx, float cy, float r, int argb);

    void line(float x1, float y1, float x2, float y2, float width, int argb);

    /** Draws text with its baseline at {@code y}. */
    void text(String s, float x, float y, float size, int font, int align, int argb);

    float textWidth(String s, float size, int font);

    /** Draws a pixel sprite scaled by an integer cell size, nearest-neighbour. */
    void sprite(int[] argb, int w, int h, float x, float y, int cell);

    /** A crescent moon: a disc of radius r with a second, offset disc cut out of it. */
    void crescent(float cx, float cy, float r, int argb);

    void clipRoundRect(float l, float t, float r, float b, float radius);

    void restoreClip();
}
