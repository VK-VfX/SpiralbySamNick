package app.skypix.core;

/** Smooth, Material-style weather glyphs drawn from circles, rounded bars and strokes (no bitmaps). */
public final class Glyphs {
    private Glyphs() {}

    private static final int SUN = 0xFFFFC62E, SUN_RAY = 0xFFFFB300, MOON = 0xFFF6E7B4;
    private static final int CLOUD = 0xFFF4F7FB, CLOUD_SHADE = 0xFFB9C4D2, STORM_CLOUD = 0xFF9AA6B8;
    private static final int DROP = 0xFF7DB8FF, BOLT = 0xFFFFD43B, SNOW = 0xFFFFFFFF, WIND = 0xFFE9F0F7;
    private static final int RED = 0xFFEA4335, AMBER = 0xFFFBBC04;

    private static final class P {
        final Gfx g;
        final int tint;
        P(Gfx g, int tint) { this.g = g; this.tint = tint; }
        int c(int color) { return tint != 0 ? tint : color; }
    }

    /** Draws {@code ic} into the square (x, y, s). {@code t} &gt;= 0 adds a little motion (seconds). */
    public static void draw(Gfx g, Icon ic, float x, float y, float s, float t) {
        draw(g, ic, x, y, s, t, 0);
    }

    /** With {@code tint != 0} every shape is drawn in that one colour (used for soft shadows). */
    public static void draw(Gfx g, Icon ic, float x, float y, float s, float t, int tint) {
        P p = new P(g, tint);
        switch (ic) {
            case SUN: sun(p, x + s * .5f, y + s * .5f, s * .23f, s, t, 1f); break;
            case MOON: moon(p, x + s * .5f, y + s * .5f, s * .3f, s); break;
            case PARTLY_DAY:
                sun(p, x + s * .36f, y + s * .36f, s * .17f, s, t, .8f);
                cloud(p, x + s * .56f, y + s * .62f, s * .74f, CLOUD);
                break;
            case PARTLY_NIGHT:
                moon(p, x + s * .36f, y + s * .36f, s * .2f, s);
                cloud(p, x + s * .56f, y + s * .62f, s * .74f, CLOUD);
                break;
            case CLOUD:
                cloud(p, x + s * .38f, y + s * .4f, s * .56f, CLOUD_SHADE);
                cloud(p, x + s * .55f, y + s * .6f, s * .8f, CLOUD);
                break;
            case FOG:
                cloud(p, x + s * .5f, y + s * .36f, s * .72f, CLOUD);
                bar(p, x + s * .2f, y + s * .66f, x + s * .8f, s * .07f, WIND);
                bar(p, x + s * .3f, y + s * .79f, x + s * .88f, s * .07f, CLOUD_SHADE);
                bar(p, x + s * .16f, y + s * .92f, x + s * .66f, s * .07f, WIND);
                break;
            case DRIZZLE: cloud(p, x + s * .5f, y + s * .36f, s * .78f, CLOUD); drops(p, x, y, s, t, 3, .1f); break;
            case RAIN: cloud(p, x + s * .5f, y + s * .34f, s * .8f, STORM_CLOUD); drops(p, x, y, s, t, 3, .17f); break;
            case STORM:
                cloud(p, x + s * .5f, y + s * .34f, s * .8f, STORM_CLOUD);
                float a = (t >= 0 && ((int) (t * 5)) % 4 == 0) ? 1f : 0.92f;
                p.g.line(x + s * .56f, y + s * .56f, x + s * .44f, y + s * .76f, s * .1f, p.c(BOLT));
                p.g.line(x + s * .44f, y + s * .76f, x + s * .56f, y + s * .76f, s * .1f, p.c(BOLT));
                p.g.line(x + s * .56f, y + s * .76f, x + s * .46f, y + s * .96f, s * .1f, p.c(BOLT));
                break;
            case SNOW:
                cloud(p, x + s * .5f, y + s * .34f, s * .8f, CLOUD);
                float dy = t < 0 ? 0 : (t * s * .06f) % (s * .06f);
                p.g.fillCircle(x + s * .32f, y + s * .74f + dy, s * .05f, p.c(SNOW));
                p.g.fillCircle(x + s * .5f, y + s * .88f + dy, s * .05f, p.c(SNOW));
                p.g.fillCircle(x + s * .68f, y + s * .74f + dy, s * .05f, p.c(SNOW));
                break;
            case WIND:
                float sh = t < 0 ? 0 : (float) Math.sin(t * 3f) * s * .03f;
                bar(p, x + s * .12f + sh, y + s * .34f, x + s * .64f + sh, s * .08f, WIND);
                bar(p, x + s * .12f, y + s * .5f, x + s * .88f - sh, s * .08f, WIND);
                bar(p, x + s * .22f - sh, y + s * .66f, x + s * .6f - sh, s * .08f, WIND);
                p.g.fillCircle(x + s * .74f + sh, y + s * .34f, s * .06f, p.c(WIND));
                break;
            case PIN:
                p.g.line(x + s * .32f, y + s * .52f, x + s * .5f, y + s * .88f, s * .12f, p.c(RED));
                p.g.line(x + s * .68f, y + s * .52f, x + s * .5f, y + s * .88f, s * .12f, p.c(RED));
                p.g.fillCircle(x + s * .5f, y + s * .4f, s * .26f, p.c(RED));
                p.g.fillCircle(x + s * .5f, y + s * .4f, s * .1f, p.tint != 0 ? p.tint : 0xFFFFFFFF);
                break;
            case ALERT:
                p.g.fillCircle(x + s * .5f, y + s * .5f, s * .42f, p.c(AMBER));
                p.g.line(x + s * .5f, y + s * .3f, x + s * .5f, y + s * .56f, s * .09f, p.tint != 0 ? p.tint : 0xFF3C2A00);
                p.g.fillCircle(x + s * .5f, y + s * .71f, s * .055f, p.tint != 0 ? p.tint : 0xFF3C2A00);
                break;
        }
    }

    private static void bar(P p, float x1, float y, float x2, float th, int color) {
        p.g.fillRoundRect(x1, y - th / 2, x2, y + th / 2, th / 2, p.c(color));
    }

    private static void sun(P p, float cx, float cy, float r, float s, float t, float rayScale) {
        float spin = t < 0 ? 0 : t * 0.5f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + spin;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            p.g.line(cx + c * r * 1.45f, cy + sn * r * 1.45f, cx + c * r * 1.85f * rayScale + c * r * 0.0f, cy + sn * r * 1.85f * rayScale, s * 0.06f, p.c(SUN_RAY));
        }
        p.g.fillCircle(cx, cy, r, p.c(SUN));
    }

    private static void moon(P p, float cx, float cy, float r, float s) {
        p.g.crescent(cx, cy, r, p.c(MOON));
        p.g.fillCircle(cx + r * 1.0f, cy - r * 0.95f, s * 0.03f, p.c(MOON));
        p.g.fillCircle(cx + r * 0.55f, cy + r * 1.05f, s * 0.022f, p.c(MOON));
    }

    private static void cloud(P p, float cx, float cy, float w, int color) {
        int c = p.c(color);
        p.g.fillRoundRect(cx - w * .5f, cy - w * .02f, cx + w * .5f, cy + w * .26f, w * .14f, c);
        p.g.fillCircle(cx - w * .17f, cy + w * .0f, w * .2f, c);
        p.g.fillCircle(cx + w * .1f, cy - w * .08f, w * .27f, c);
        p.g.fillCircle(cx + w * .32f, cy + w * .06f, w * .15f, c);
    }

    private static void drops(P p, float x, float y, float s, float t, int n, float len) {
        float travel = s * .08f;
        for (int i = 0; i < n; i++) {
            float ph = t < 0 ? 0 : ((t * 1.6f + i * .33f) % 1f);
            float dx = x + s * (.3f + .2f * i) - ph * s * .02f;
            float dy = y + s * .66f + ph * travel;
            p.g.line(dx, dy, dx - s * .03f, dy + s * len, s * .06f, p.c(DROP));
        }
    }
}
