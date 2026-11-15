package app.skypix.core;

/** Smooth, Material-style weather glyphs drawn from circles, rounded bars and strokes (no bitmaps). */
public final class Glyphs {
    private Glyphs() {}

    /** When true every weather glyph is a Google Noto cat emoji (one cat mood per weather). */
    public static volatile boolean cats = true;

    /** The cat as an emoji character, for notification text (the system's Noto Color Emoji draws it). */
    public static String catEmoji(Icon ic) {
        String n = catFor(ic);
        if (n.equals("smile")) return "\uD83D\uDE38";
        if (n.equals("black")) return "\uD83D\uDC08\u200D\u2B1B";
        if (n.equals("grin")) return "\uD83D\uDE3A";
        if (n.equals("kiss")) return "\uD83D\uDE3D";
        if (n.equals("plain")) return "\uD83D\uDC31";
        if (n.equals("smirk")) return "\uD83D\uDE3C";
        if (n.equals("pout")) return "\uD83D\uDE3E";
        if (n.equals("cry")) return "\uD83D\uDE3F";
        if (n.equals("weary")) return "\uD83D\uDE40";
        if (n.equals("love")) return "\uD83D\uDE3B";
        if (n.equals("joy")) return "\uD83D\uDE39";
        return "\uD83D\uDC08";
    }

    public static String catFor(Icon ic) {
        switch (ic) {
            case SUN: return "smile";
            case MOON: return "black";
            case PARTLY_DAY: return "grin";
            case PARTLY_NIGHT: return "kiss";
            case CLOUD: return "plain";
            case FOG: return "kiss";
            case DRIZZLE: return "pout";
            case RAIN: return "cry";
            case STORM: return "weary";
            case SNOW: return "love";
            case WIND: return "smirk";
            case PIN: return "walk";
            case UMBRELLA: return "cry";
            case SWEAT: return "plain";
            case SHADES: return "grin";
            case SHIVER: return "plain";
            case MASK: return "plain";
            case SLEEPY: return "kiss";
            case SUNRISE: return "smile";
            case SUNSET: return "kiss";
            default: return "weary";
        }
    }

    /** What the plain vector glyph set shows when cats are switched off. */
    private static Icon vectorFor(Icon ic) {
        switch (ic) {
            case UMBRELLA: return Icon.RAIN;
            case SWEAT: case SHADES: case SUNRISE: case SUNSET: return Icon.SUN;
            case SHIVER: return Icon.SNOW;
            case MASK: return Icon.FOG;
            case SLEEPY: return Icon.MOON;
            default: return ic;
        }
    }

    private static final int SUN = 0xFFFFC62E, SUN_RAY = 0xFFFFB300, MOON = 0xFFF6E7B4;
    private static final int CLOUD = 0xFFF4F7FB, CLOUD_SHADE = 0xFFB9C4D2, STORM_CLOUD = 0xFF9AA6B8;
    private static final int DROP = 0xFF7DB8FF, BOLT = 0xFFFFD43B, SNOW = 0xFFFFFFFF, WIND = 0xFFE9F0F7;
    private static final int RED = 0xFFEA4335, AMBER = 0xFFFBBC04;

    private static final class P {
        final Gfx g;
        final int tint;
        final boolean light;
        P(Gfx g, int tint, boolean light) { this.g = g; this.tint = tint; this.light = light; }
        int c(int color) {
            if (tint != 0) return tint;
            if (!light) return color;
            // darker, more saturated variants so the glyphs read on a white card
            switch (color) {
                case CLOUD: return 0xFFB7C5D9;
                case CLOUD_SHADE: return 0xFF93A5C0;
                case STORM_CLOUD: return 0xFF7F90AB;
                case WIND: return 0xFF8DA2BD;
                case SNOW: return 0xFF8FB6EE;
                case MOON: return 0xFFF0B429;
                case DROP: return 0xFF4C8DF6;
                default: return color;
            }
        }
    }

    /** Draws {@code ic} into the square (x, y, s). {@code t} &gt;= 0 adds a little motion (seconds). */
    public static void draw(Gfx g, Icon ic, float x, float y, float s, float t) {
        draw(g, ic, x, y, s, t, 0);
    }

    /** With {@code tint != 0} every shape is drawn in that one colour (used for soft shadows). */
    public static void draw(Gfx g, Icon ic, float x, float y, float s, float t, int tint) {
        draw(g, ic, x, y, s, t, tint, false);
    }

    /** {@code onLight} swaps the pale cloud/moon colours for ones that show up on a white card. */
    public static void draw(Gfx g, Icon ic, float x, float y, float s, float t, int tint, boolean onLight) {
        if (cats) {
            // the cat has its own colours, so the soft-shadow pass is skipped
            if (tint == 0) cat(g, ic, x, y, s, t);
            return;
        }
        ic = vectorFor(ic);
        P p = new P(g, tint, onLight);
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

    // ---- cats with accessories ------------------------------------------------------------------------------------

    private static final int INK = 0xFF1B1B1F, SWEAT_BLUE = 0xFF4FC3F7;

    private static void cat(Gfx g, Icon ic, float x, float y, float s, float t) {
        String face = catFor(ic);
        switch (ic) {
            case UMBRELLA: {
                // a sad cat sheltering under a little umbrella (no raindrops: cats hate water)
                g.line(x + s * .5f, y + s * .30f, x + s * .5f, y + s * .62f, s * .035f, 0xFF8E9AAF);
                g.emoji(face, x + s * .14f, y + s * .26f, s * .72f);
                dome(g, x + s * .5f, y + s * .38f, s * .40f, 0xFF1A73E8);
                g.line(x + s * .12f, y + s * .385f, x + s * .88f, y + s * .385f, s * .03f, 0xFF0B57D0);
                g.line(x + s * .5f, y + s * .0f, x + s * .5f, y + s * .06f, s * .035f, 0xFF0B57D0);
                g.line(x + s * .5f, y + s * .06f, x + s * .5f, y + s * .385f, s * .018f, 0x44FFFFFF);
                break;
            }
            case SWEAT: {
                g.emoji(face, x, y, s);
                g.fillCircle(x + s * .17f, y + s * .62f, s * .065f, 0x66FF6F61);
                g.fillCircle(x + s * .83f, y + s * .62f, s * .065f, 0x66FF6F61);
                float slide = t < 0 ? 0 : ((t * 0.9f) % 1f) * s * .07f;
                drop(g, x + s * .84f, y + s * .22f + slide, s * .055f);
                drop(g, x + s * .14f, y + s * .30f + slide * .6f, s * .042f);
                break;
            }
            case SHADES: {
                g.emoji(face, x, y, s);
                float ly = y + s * .53f, lw = s * .30f, lh = s * .20f;
                g.line(x + s * .45f, ly - s * .03f, x + s * .55f, ly - s * .03f, s * .035f, INK);
                g.line(x + s * .17f, ly - s * .02f, x + s * .07f, ly - s * .07f, s * .03f, INK);
                g.line(x + s * .83f, ly - s * .02f, x + s * .93f, ly - s * .07f, s * .03f, INK);
                g.fillRoundRect(x + s * .30f - lw / 2, ly - lh / 2, x + s * .30f + lw / 2, ly + lh / 2, s * .07f, INK);
                g.fillRoundRect(x + s * .70f - lw / 2, ly - lh / 2, x + s * .70f + lw / 2, ly + lh / 2, s * .07f, INK);
                g.line(x + s * .22f, ly - s * .05f, x + s * .30f, ly - s * .08f + s * .0f, s * .022f, 0x66FFFFFF);
                g.line(x + s * .62f, ly - s * .05f, x + s * .70f, ly - s * .08f + s * .0f, s * .022f, 0x66FFFFFF);
                break;
            }
            case SHIVER: {
                g.emoji(face, x, y, s);
                // scarf
                g.fillRoundRect(x + s * .20f, y + s * .79f, x + s * .80f, y + s * .92f, s * .06f, 0xFFE94B4B);
                g.fillRoundRect(x + s * .62f, y + s * .84f, x + s * .76f, y + s * 1.0f, s * .04f, 0xFFD13A3A);
                g.line(x + s * .30f, y + s * .85f, x + s * .70f, y + s * .85f, s * .02f, 0x55FFFFFF);
                // shiver marks + snowflake
                float sh = t < 0 ? 0 : (float) Math.sin(t * 24) * s * .012f;
                g.line(x + s * .02f + sh, y + s * .38f, x + s * .02f + sh, y + s * .52f, s * .03f, 0xFF4C8DF6);
                g.line(x + s * .98f - sh, y + s * .38f, x + s * .98f - sh, y + s * .52f, s * .03f, 0xFF4C8DF6);
                flake(g, x + s * .86f, y + s * .14f, s * .09f);
                break;
            }
            case MASK: {
                g.emoji(face, x, y, s);
                g.line(x + s * .22f, y + s * .62f, x + s * .10f, y + s * .50f, s * .022f, 0xFF8FB8E8);
                g.line(x + s * .78f, y + s * .62f, x + s * .90f, y + s * .50f, s * .022f, 0xFF8FB8E8);
                g.fillRoundRect(x + s * .22f, y + s * .56f, x + s * .78f, y + s * .86f, s * .09f, 0xFFD6EBFF);
                g.strokeRoundRect(x + s * .22f, y + s * .56f, x + s * .78f, y + s * .86f, s * .09f, s * .02f, 0xFF8FB8E8);
                g.line(x + s * .27f, y + s * .66f, x + s * .73f, y + s * .66f, s * .016f, 0xFF9CC4EE);
                g.line(x + s * .27f, y + s * .73f, x + s * .73f, y + s * .73f, s * .016f, 0xFF9CC4EE);
                g.line(x + s * .27f, y + s * .80f, x + s * .73f, y + s * .80f, s * .016f, 0xFF9CC4EE);
                break;
            }
            case FOG: {
                g.emoji(face, x, y, s);
                float drift = t < 0 ? 0 : (float) Math.sin(t * 1.6) * s * .04f;
                g.fillRoundRect(x + s * .02f + drift, y + s * .64f, x + s * .70f + drift, y + s * .72f, s * .04f, 0xB8FFFFFF);
                g.fillRoundRect(x + s * .30f - drift, y + s * .77f, x + s * .98f - drift, y + s * .85f, s * .04f, 0xB8EFF3F8);
                g.fillRoundRect(x + s * .10f + drift, y + s * .90f, x + s * .80f + drift, y + s * .98f, s * .04f, 0xB8FFFFFF);
                break;
            }
            case WIND: {
                float sway = t < 0 ? 0 : (float) Math.sin(t * 5) * s * .03f;
                g.emoji(face, x + s * .12f + sway, y + s * .04f, s * .88f);
                g.line(x + s * .0f, y + s * .34f, x + s * .26f, y + s * .34f, s * .035f, 0xFF8DA2BD);
                g.line(x + s * .0f, y + s * .50f, x + s * .20f, y + s * .50f, s * .035f, 0xFF8DA2BD);
                g.line(x + s * .04f, y + s * .66f, x + s * .26f, y + s * .66f, s * .035f, 0xFF8DA2BD);
                break;
            }
            case SLEEPY: {
                g.emoji(face, x, y + s * .06f, s * .9f);
                float rise = t < 0 ? 0 : ((t * 0.7f) % 1f) * s * .08f;
                g.text("z", x + s * .74f, y + s * .34f - rise, s * .22f, Gfx.FONT_BOLD, Gfx.LEFT, 0xFF7C8CFF);
                g.text("Z", x + s * .84f, y + s * .20f - rise, s * .28f, Gfx.FONT_BOLD, Gfx.LEFT, 0xFF5E6AD2);
                break;
            }
            case SUNRISE:
            case SUNSET: {
                // the cat, with a little sun at the horizon in the corner
                int c = ic == Icon.SUNRISE ? 0xFFFFC62E : 0xFFFF7043;
                float glow = t < 0 ? 0 : (float) Math.sin(t * 3) * s * .012f;
                g.emoji(face, x, y, s * .84f);
                for (int i = 0; i < 5; i++) {
                    double ang = Math.PI * (0.12 + 0.19 * i);
                    float dx = (float) Math.cos(ang), dy = -(float) Math.sin(ang);
                    g.line(x + s * .80f + dx * s * .20f, y + s * .94f + dy * s * .20f,
                            x + s * .80f + dx * (s * .27f + glow), y + s * .94f + dy * (s * .27f + glow), s * .035f, c);
                }
                dome(g, x + s * .80f, y + s * .94f, s * .15f, c);
                g.line(x + s * .56f, y + s * .945f, x + s * 1.0f, y + s * .945f, s * .03f, 0xFFB26A2D);
                break;
            }
            case STORM: {
                g.emoji(face, x, y, s);
                g.line(x + s * .90f, y + s * .02f, x + s * .80f, y + s * .18f, s * .06f, 0xFFFFD43B);
                g.line(x + s * .80f, y + s * .18f, x + s * .90f, y + s * .18f, s * .06f, 0xFFFFD43B);
                g.line(x + s * .90f, y + s * .18f, x + s * .80f, y + s * .36f, s * .06f, 0xFFFFD43B);
                break;
            }
            case ALERT: {
                g.emoji(face, x, y, s);
                g.fillCircle(x + s * .85f, y + s * .17f, s * .15f, 0xFFEA4335);
                g.line(x + s * .85f, y + s * .10f, x + s * .85f, y + s * .18f, s * .035f, 0xFFFFFFFF);
                g.fillCircle(x + s * .85f, y + s * .24f, s * .02f, 0xFFFFFFFF);
                break;
            }
            default:
                g.emoji(face, x, y, s);
        }
    }

    /** Upper half of a disc (flat bottom at cy). */
    private static void dome(Gfx g, float cx, float cy, float r, int color) {
        int n = Math.max(10, (int) Math.min(40, r / 1.5f));
        for (int i = 0; i < n; i++) {
            float y0 = cy - r + r * i / n, y1 = cy - r + r * (i + 1) / n;
            float dy = cy - (y0 + y1) / 2f;
            float hw = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fillRect(cx - hw, y0, cx + hw, y1 + 0.6f, color);
        }
    }

    private static void drop(Gfx g, float cx, float cy, float r) {
        g.fillCircle(cx, cy + r * 0.55f, r, SWEAT_BLUE);
        g.line(cx, cy - r * 1.35f, cx - r * 0.55f, cy + r * 0.1f, r * 0.55f, SWEAT_BLUE);
        g.line(cx, cy - r * 1.35f, cx + r * 0.55f, cy + r * 0.1f, r * 0.55f, SWEAT_BLUE);
        g.fillCircle(cx - r * 0.3f, cy + r * 0.3f, r * 0.28f, 0x99FFFFFF);
    }

    private static void flake(Gfx g, float cx, float cy, float r) {
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 3 * i;
            float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
            g.line(cx - dx, cy - dy, cx + dx, cy + dy, r * 0.32f, 0xFF4C8DF6);
            g.line(cx - dx * 0.9f, cy - dy * 0.9f, cx + dx * 0.9f, cy + dy * 0.9f, r * 0.14f, 0xFFFFFFFF);
        }
    }
}
