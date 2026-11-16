package app.skypix.core;

/**
 * Draws every Purrcast card/widget. Coordinates are in dp; {@code u} is pixels per dp.
 * Light system theme => deep, near-opaque cards. Dark system theme => translucent glass cards.
 */
public final class Cards {
    private Cards() {}

    public static final int NOW = 0, NOW_WIDE = 1, HOURLY = 2, FORECAST = 3, DETAILS = 4, LOCATION = 5, SUN = 6, HEADSUP = 7, ICON = 8,
            LINE_HOURLY = 9, LINE_DAILY = 10, BIG = 11, CLASSIC = 12, RAIN_SOON = 13,
            CLOCK_BIG = 14, CLOCK_DAY = 15, CLOCK_SKY = 16, CLOCK_FLIP = 17, CLOCK_SCHEDULE = 18;
    public static final int COUNT = 19;

    public static final class Data {
        public Weather weather;
        public Place place;
        public Units units = new Units(false, false, false);
        public long nowSec;
        /** Dark theme only: draw widgets with no fill (see-through) instead of AMOLED black. */
        public boolean transparentDark;
        /** Opacity of the card background: 1 = solid, lower = frosted glass (applies in light and dark). */
        public float bgAlpha = 1f;
        /** Material You colours (0 = off): card gradient top/bottom and the big-number gradient. */
        public int youTop, youBottom, youP0, youP1;
        /** Seconds east of UTC for the phone's own clock (clock widgets show phone time, not the forecast place's time). */
        public int tzOffset;
        /** Seconds into the 5 s unlock animation, or a negative value for the static card. */
        public float anim = -1;

        public boolean ready() {
            return weather != null && weather.valid() && place != null;
        }
    }

    /** Default (min) widget size in dp for each kind. */
    public static int[] size(int kind) {
        switch (kind) {
            case NOW: return new int[]{110, 110};
            case NOW_WIDE: return new int[]{250, 110};
            case HOURLY: return new int[]{250, 110};
            case FORECAST: return new int[]{250, 180};
            case DETAILS: return new int[]{250, 110};
            case LOCATION: return new int[]{110, 110};
            case ICON: return new int[]{40, 40};
            case SUN: return new int[]{250, 110};
            case LINE_HOURLY: case LINE_DAILY: case BIG: case RAIN_SOON: case CLOCK_BIG: case CLOCK_FLIP: return new int[]{110, 110};
            case CLOCK_DAY: case CLOCK_SKY: case CLOCK_SCHEDULE: return new int[]{250, 110};
            case CLASSIC: return new int[]{110, 110};
            default: return new int[]{250, 56};
        }
    }

    public static String title(int kind) {
        switch (kind) {
            case NOW: return "Now";
            case NOW_WIDE: return "Now wide";
            case HOURLY: return "Hourly";
            case FORECAST: return "Forecast";
            case DETAILS: return "Details";
            case LOCATION: return "Location";
            case SUN: return "Sun";
            case ICON: return "Icon";
            case LINE_HOURLY: return "Line hourly";
            case LINE_DAILY: return "Line daily";
            case BIG: return "Big number";
            case CLASSIC: return "Classic";
            case RAIN_SOON: return "Rain soon";
            case CLOCK_BIG: return "Clock";
            case CLOCK_DAY: return "Day clock";
            case CLOCK_SKY: return "Sky clock";
            case CLOCK_FLIP: return "Flip clock";
            case CLOCK_SCHEDULE: return "Cat schedule";
            default: return "Heads-up";
        }
    }

    // ---- palette -------------------------------------------------------------------------------------------------

    private static final int WHITE = 0xFFFFFFFF, T2 = 0xE0FFFFFF, T3 = 0xB0FFFFFF, TILE = 0x1CFFFFFF;
    private static final int HI = 0xFFFFB86B, LO = 0xFF7CC4FF, POP = 0xFF6FC1FF, SUNY = 0xFFFFD23F;

    private static final class Mood {
        final int top, bottom, glow;
        Mood(int top, int bottom, int glow) { this.top = top; this.bottom = bottom; this.glow = glow; }
    }

    private static final Mood CLEAR_DAY = new Mood(0xFF1A73E8, 0xFF1765CC, 0xFFFFC62E);
    private static final Mood CLEAR_NIGHT = new Mood(0xFF1F2A4D, 0xFF182140, 0xFFB8C4FF);
    private static final Mood CLOUDY = new Mood(0xFF4B5D78, 0xFF42536C, 0xFFD3DCEB);
    private static final Mood RAINY = new Mood(0xFF30506F, 0xFF29455F, 0xFF7DB8FF);
    private static final Mood STORMY = new Mood(0xFF3B3A66, 0xFF323158, 0xFFFFD43B);
    private static final Mood SNOWY = new Mood(0xFF607C99, 0xFF566F8B, 0xFFFFFFFF);
    private static final Mood FOGGY = new Mood(0xFF5B6675, 0xFF525C6A, 0xFFE0E6F0);

    private static Mood mood(Data d) {
        if (d == null || !d.ready()) return CLEAR_NIGHT;
        Weather.Now n = d.weather.now(d.nowSec);
        if (Wmo.isStorm(n.code)) return STORMY;
        if (Wmo.isSnow(n.code)) return SNOWY;
        if (Wmo.isWet(n.code)) return RAINY;
        if (n.code == 45 || n.code == 48) return FOGGY;
        if (n.code == 3) return CLOUDY;
        return n.isDay ? CLEAR_DAY : CLEAR_NIGHT;
    }

    private static int alpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ---- drawing context -----------------------------------------------------------------------------------------

    private static final class R {
        final Gfx g;
        final float u, w, h;
        final boolean dark;
        final boolean clear;
        float fill = 1f;
        Mood skin;
        int pastelTop, pastelBottom;
        float t = -1;

        R(Gfx g, float u, float w, float h, boolean dark, boolean clear) {
            this.g = g; this.u = u; this.w = w; this.h = h; this.dark = dark; this.clear = dark && clear;
        }

        /** True on see-through cards (clear or glass), where light text needs a soft shadow to stay readable. */
        boolean shade;

        private boolean lightInk(int color) {
            int a = color >>> 24;
            float lum = (0.299f * ((color >> 16) & 255) + 0.587f * ((color >> 8) & 255) + 0.114f * (color & 255)) / 255f;
            return a > 90 && lum > 0.62f;
        }

        void text(String s, float x, float base, float size, int font, int align, int color) {
            if (shade && lightInk(color)) {
                float o = 1.1f;
                // a soft dark halo all round, plus a drop shadow, so letters stand out from whatever is behind
                g.text(s, (x + o) * u, (base + o) * u, size * u, font, align, 0x50000000);
                g.text(s, (x - o) * u, (base + o) * u, size * u, font, align, 0x50000000);
                g.text(s, (x + o) * u, (base - o) * u, size * u, font, align, 0x50000000);
                g.text(s, (x - o) * u, (base - o) * u, size * u, font, align, 0x50000000);
                g.text(s, x * u, (base + o * 1.8f) * u, size * u, font, align, 0x80000000);
            }
            g.text(s, x * u, base * u, size * u, font, align, color);
        }

        float tw(String s, float size, int font) {
            return g.textWidth(s, size * u, font) / u;
        }

        String fit(String s, float size, int font, float maxW) {
            if (s == null) return "";
            if (tw(s, size, font) <= maxW) return s;
            String t = s;
            while (t.length() > 1 && tw(t + "…", size, font) > maxW) t = t.substring(0, t.length() - 1);
            return t.trim() + "…";
        }

        /** Small caps label (hours, percentages, day names). Smooth vector type, sized from the old pixel cell. */
        private float pixSize(float cell) {
            return Math.max(11.5f, PixelFont.H * cell * 1.42f);
        }

        void pix(String s, float x, float y, float cell, int align, int color) {
            float size = pixSize(cell);
            text(s, x, y + size * 0.74f, size, Gfx.FONT_MEDIUM, align, color);
        }

        float pixW(String s, float cell) {
            return tw(s, pixSize(cell), Gfx.FONT_MEDIUM);
        }

        /** Label cap height in dp. */
        float pixH(float cell) {
            return pixSize(cell) * 0.74f;
        }

        /** Draws a smooth vector weather glyph and returns its size in dp. */
        float icon(Icon ic, float x, float y, float size) {
            float bob = t < 0 ? 0 : (float) Math.sin(t * 4.2 + x * 0.07) * size * 0.03f;
            Glyphs.draw(g, ic, x * u, (y + bob) * u, size * u, t);
            return size;
        }

        /** Same glyph with a soft shadow, so pale icons stay readable on light wallpapers. */
        float iconOutlined(Icon ic, float x, float y, float size, int outline) {
            float bob = t < 0 ? 0 : (float) Math.sin(t * 4.2 + x * 0.07) * size * 0.03f;
            int shadow = (outline & 0x00FFFFFF) | 0x55000000;
            Glyphs.draw(g, ic, (x + size * 0.015f) * u, (y + bob + size * 0.04f) * u, size * u, t, shadow);
            Glyphs.draw(g, ic, x * u, (y + bob) * u, size * u, t);
            return size;
        }

        float iconSize(float size) {
            return size;
        }

        void rect(float l, float t, float r, float b, float rad, int color) {
            g.fillRoundRect(l * u, t * u, r * u, b * u, rad * u, color);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- entry points --------------------------------------------------------------------------------------------

    public static void render(Gfx g, int kind, float wDp, float hDp, float u, boolean dark, Data d) {
        R r = new R(g, u, wDp, hDp, dark, d != null && d.transparentDark);
        r.fill = fillFor(d, dark);
        r.shade = r.clear || r.fill < 0.95f;
        applySkin(r, d);
        r.t = d == null ? -1 : d.anim;
        if (kind == ICON) {
            iconWidget(r, d);
            return;
        }
        Mood m = mood(d);
        if (kind >= CLOCK_BIG && d != null) {
            clocks(r, kind, m, d);
            return;
        }
        if (kind >= LINE_HOURLY && d != null && d.ready()) {
            modern(r, kind, m, d);
            return;
        }
        base(r, m);
        fx(r, m, d);
        if (d == null || !d.ready()) {
            empty(r);
            return;
        }
        switch (kind) {
            case NOW:
                if (r.w >= r.h * 1.7f) nowWide(r, d);
                else if (r.h >= 150 && r.h >= r.w * 1.3f) nowTall(r, d);
                else now(r, d);
                break;
            case NOW_WIDE: nowWide(r, d); break;
            case HOURLY:
                if (r.w < 200 && r.h >= 150) hourlyTall(r, d);
                else hourly(r, d);
                break;
            case FORECAST: forecast(r, d); break;
            case DETAILS: details(r, d); break;
            case LOCATION: location(r, d); break;
            case SUN: sun(r, d); break;
            default: headsUp(r, d); break;
        }
    }

    public static String describe(int kind, Data d) {
        if (kind >= CLOCK_BIG && d != null) {
            int[] t = hms(d, d.tzOffset);
            return "Clock: " + (d.units.h24 ? hourText(t[0], true) + ":" + minText(t[1]) : hourText(t[0], false) + ":" + minText(t[1]) + (t[0] < 12 ? " AM" : " PM")) + ", " + dateText(d, d.tzOffset);
        }
        if (d == null || !d.ready()) return "Purrcast. Tap to set up.";
        Weather.Now n = d.weather.now(d.nowSec);
        Units u = d.units;
        String base = d.place.title() + ": " + u.temp(n.temp) + " degrees " + (u.fahrenheit ? "Fahrenheit" : "Celsius")
                + ", " + Wmo.label(n.code);
        if (kind == ICON) return u.temp(n.temp) + " degrees, " + Wmo.label(n.code);
        if (kind == LOCATION) {
            return "Location: " + d.place.title() + ", accuracy " + Math.round(d.place.accuracyM) + " meters";
        }
        if (kind == HEADSUP) {
            Insights.Insight i = Insights.headline(d.weather, u, d.nowSec);
            return i.title + ". " + i.detail;
        }
        return base + ". High " + u.temp(d.weather.todayMax(d.nowSec)) + ", low " + u.temp(d.weather.todayMin(d.nowSec)) + ".";
    }


    // ---- unlock animation ----------------------------------------------------------------------------------------

    private static float hash(int i, int salt) {
        int x = i * 374761393 + salt * 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        x ^= x >>> 16;
        return (x & 0xFFFFFF) / 16777216f;
    }

    private static int withAlpha(int rgb, float a) {
        int v = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (v << 24) | (rgb & 0xFFFFFF);
    }

    /** Weather motion drawn behind the content for the 5 s after unlocking. */
    private static void fx(R r, Mood m, Data d) {
        if (d == null || !d.ready() || d.anim < 0) return;
        float t = d.anim;
        float env = Math.min(1f, Math.min(t / 0.5f, (5f - t) / 0.7f));
        if (env <= 0) return;
        float w = r.w, h = r.h;
        Weather.Now n = d.weather.now(d.nowSec);
        r.g.clipRoundRect(0, 0, w * r.u, h * r.u, RAD * r.u);
        int area = (int) (w * h);

        if (m == RAINY || m == STORMY) {
            int count = (int) clamp(area / 1300f, 16, 64);
            float boost = m == STORMY ? 1.35f : 1f;
            boolean light = n.code >= 51 && n.code <= 57;
            for (int i = 0; i < count; i++) {
                float x = hash(i, 1) * w;
                float speed = (190 + hash(i, 2) * 130) * boost * (light ? 0.7f : 1f);
                float len = (light ? 4 : 7) + hash(i, 3) * 5;
                float span = h + 30;
                float y = ((hash(i, 4) * span + t * speed) % span) - 15;
                float a = 0.55f * env * (0.45f + 0.55f * hash(i, 5));
                r.rect(x, y, x + 1.4f, y + len, 0.4f, withAlpha(0x9AD0FF, a));
            }
            if (m == STORMY) {
                float[] strikes = {1.1f, 2.9f, 3.25f};
                for (float s0 : strikes) {
                    float dt = t - s0;
                    if (dt > 0 && dt < 0.2f) {
                        r.rect(0, 0, w, h, 0, withAlpha(0xFFFFFF, 0.38f * (1 - dt / 0.2f)));
                    }
                }
            }
        } else if (m == SNOWY) {
            int count = (int) clamp(area / 1100f, 18, 56);
            for (int i = 0; i < count; i++) {
                float sz = 1.8f + hash(i, 3) * 2.2f;
                float speed = 24 + hash(i, 2) * 30;
                float span = h + 20;
                float y = ((hash(i, 4) * span + t * speed) % span) - 10;
                float x = hash(i, 1) * w + (float) Math.sin(t * 1.3 + hash(i, 6) * 6.28) * 7;
                r.rect(x, y, x + sz, y + sz, 0.5f, withAlpha(0xFFFFFF, 0.8f * env * (0.5f + 0.5f * hash(i, 5))));
            }
        } else if (m == FOGGY) {
            for (int i = 0; i < 5; i++) {
                float bw = w * (0.5f + hash(i, 1) * 0.4f);
                float span = w + bw;
                float x = ((hash(i, 2) * span + t * (6 + hash(i, 3) * 8)) % span) - bw;
                float y = h * (0.1f + 0.8f * (i / 4f));
                r.rect(x, y, x + bw, y + 13, 6.5f, withAlpha(0xFFFFFF, 0.1f * env));
            }
        } else if (m == CLEAR_NIGHT) {
            for (int i = 0; i < 16; i++) {
                float x = hash(i, 1) * w, y = hash(i, 2) * h * 0.85f;
                float tw = (float) Math.sin(t * (2 + hash(i, 3) * 2.5) + hash(i, 4) * 6.28);
                float a = Math.max(0, tw) * env * 0.9f;
                float sz = hash(i, 5) > 0.7f ? 2.4f : 1.6f;
                r.rect(x, y, x + sz, y + sz, 0.3f, withAlpha(0xFFF3B0, a));
            }
        } else if (m == CLEAR_DAY) {
            float pulse = 0.55f + 0.1f * (float) Math.sin(t * 2.4);
            r.g.glow(w * r.u * 0.86f, h * r.u * 0.12f, Math.max(w, h) * r.u * pulse, withAlpha(0xFFD23F, 0.28f * env));
            for (int i = 0; i < 9; i++) {
                float x = hash(i, 1) * w, y = hash(i, 2) * h;
                float tw = (float) Math.sin(t * (2.2 + hash(i, 3) * 2) + hash(i, 4) * 6.28);
                float a = Math.max(0, tw);
                a = a * a * a * env * 0.8f;
                int c = withAlpha(0xFFF0A0, a);
                r.rect(x, y - 1.6f, x + 1.4f, y + 2.2f, 0.2f, c);
                r.rect(x - 1.6f, y, x + 3f, y + 1.4f, 0.2f, c);
            }
        }

        if (n.code == 2 || n.code == 3 || m == CLOUDY) {
            for (int i = 0; i < 4; i++) {
                float cw = 60 + hash(i, 1) * 50, ch = 16 + hash(i, 2) * 10;
                float span = w + cw + 40;
                float x = ((hash(i, 3) * span + t * (9 + hash(i, 4) * 9)) % span) - cw;
                float y = h * (0.08f + 0.6f * hash(i, 5));
                r.rect(x, y, x + cw, y + ch, ch / 2, withAlpha(0xFFFFFF, 0.1f * env));
                r.rect(x + cw * 0.2f, y - ch * 0.4f, x + cw * 0.6f, y + ch * 0.4f, ch / 2, withAlpha(0xFFFFFF, 0.1f * env));
            }
        }
        // easter egg: now and then a cat dashes across the bottom of the card
        if (((d.nowSec / 60) % 3) == 0 && t > 0.5f && t < 4.3f && Glyphs.cats) {
            float f = (t - 0.5f) / 3.8f;
            float size = clamp(h * 0.2f, 18, 28);
            float x = w + size - (w + 2 * size) * f;
            float y = h - size - 3 - Math.abs((float) Math.sin(t * 10)) * 4;
            r.g.emoji("walk", x * r.u, y * r.u, size * r.u);
        }
        r.g.restoreClip();
    }

    // ---- shared chrome -------------------------------------------------------------------------------------------

    private static final float RAD = 28;

    /** Dynamic (Material You) colours replace the weather tint of the card background, never the weather effects. */
    private static void applySkin(R r, Data d) {
        if (d == null || d.youTop == 0) return;
        r.skin = new Mood(d.youTop, d.youBottom, d.youTop);
        r.pastelTop = d.youP0;
        r.pastelBottom = d.youP1;
    }

    /** See-through "clear" only exists in dark mode; in light mode it becomes a light frosted glass. */
    private static float fillFor(Data d, boolean dark) {
        if (d == null) return 1f;
        if (d.transparentDark && !dark) return 0.5f;
        return d.bgAlpha;
    }

    private static int fade(int argb, float f) {
        return ((Math.round(((argb >>> 24) & 255) * f)) << 24) | (argb & 0xFFFFFF);
    }

    private static void base(R r, Mood m) {
        if (r.skin != null) m = r.skin;
        float rad = RAD;
        if (r.clear) {
            // Transparent variant: no fill at all, just a faint edge so the wallpaper shows through.
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, rad * r.u, 1f * r.u, 0x29FFFFFF);
            return;
        }
        int top, bottom;
        if (r.dark) {
            // AMOLED: a faint tint of the weather that falls to pure black.
            top = alpha(mix(m.top, 0xFF000000, 0.62f), 0xF2);
            bottom = 0xFF000000;
        } else {
            // Material tonal surface: almost flat, a hint of depth.
            top = alpha(m.top, 0xFF);
            bottom = alpha(m.bottom, 0xFF);
        }
        r.g.fillRoundRectGradient(0, 0, r.w * r.u, r.h * r.u, rad * r.u, fade(top, r.fill), fade(bottom, r.fill));
        if (r.dark) {
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, rad * r.u, 1f * r.u, 0x1FFFFFFF);
        }
    }

    private static void empty(R r) {
        float pad = 14;
        float sz = clamp(Math.min(r.h - 2 * pad, r.w * 0.3f), 24, 44);
        float s = r.icon(Icon.PIN, pad, (r.h - sz) / 2, sz);
        float x = pad + s + 12;
        float max = r.w - x - pad;
        r.text(r.fit("Tap to set up", 15, Gfx.FONT_BOLD, max), x, r.h / 2 - 1, 15, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        if (r.h >= 70) {
            r.text(r.fit("Allow location for weather", 11.5f, Gfx.FONT_REGULAR, max), x, r.h / 2 + 16, 11.5f, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        }
    }

    private static void hiLo(R r, float x, float y, float cell, double hi, double lo, Units u, int align) {
        String a = (Double.isNaN(hi) ? "--" : String.valueOf(u.temp(hi)));
        String b = (Double.isNaN(lo) ? "--" : String.valueOf(u.temp(lo)));
        String up = "↑", dn = "↓";
        float gap = 6 * Math.max(1, Math.round(cell * r.u)) / r.u * 0.9f;
        float wa = r.pixW(a, cell), wu = r.pixW(up, cell), wb = r.pixW(b, cell), wd = r.pixW(dn, cell);
        float total = wa + cell + wu + gap + wb + cell + wd;
        float cx = align == Gfx.RIGHT ? x - total : align == Gfx.CENTER ? x - total / 2 : x;
        r.pix(a, cx, y, cell, Gfx.LEFT, WHITE);
        cx += wa + cell;
        r.pix(up, cx, y, cell, Gfx.LEFT, HI);
        cx += wu + gap;
        r.pix(b, cx, y, cell, Gfx.LEFT, WHITE);
        cx += wb + cell;
        r.pix(dn, cx, y, cell, Gfx.LEFT, LO);
    }

    // ---- NOW (2x2) -----------------------------------------------------------------------------------------------

    private static void now(R r, Data d) {
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.1f, 10, 16);
        Weather.Now n = d.weather.now(d.nowSec);
        Units u = d.units;
        float isz = clamp(w * 0.26f, 22, 40);
        float real = r.icon(Wmo.icon(n.code, n.isDay), w - pad - isz, pad - 3, isz);
        String loc = r.fit(d.place.shortName(), 12, Gfx.FONT_MEDIUM, w - 2 * pad - real - 4);
        r.text(loc, pad, pad + 10, 12, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);

        float cell = 1.5f;
        float pixTop = h - pad - r.pixH(cell);
        hiLo(r, pad, pixTop, cell, d.weather.todayMax(d.nowSec), d.weather.todayMin(d.nowSec), u, Gfx.LEFT);
        float condBase = pixTop - 7;
        r.text(r.fit(Wmo.label(n.code), 12, Gfx.FONT_REGULAR, w - 2 * pad), pad, condBase, 12, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        float ts = clamp(h * 0.35f, 28, 48);
        r.text(u.t(n.temp), pad - 1, condBase - 17, ts, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
    }

    // ---- MODERN CARDS: line charts, big number, classic (Pixel / Material look) --------------------------------

    private static void modern(R r, int kind, Mood m, Data d) {
        switch (kind) {
            case LINE_HOURLY: lineCard(r, d, true); break;
            case LINE_DAILY: lineCard(r, d, false); break;
            case BIG: bigNumber(r, m, d); break;
            case RAIN_SOON: rainSoon(r, m, d); break;
            default: classic(r, m, d); break;
        }
    }

    private static final int WARM_L = 0xFFF29900, COOL_L = 0xFF1A73E8, WARM_D = 0xFFFFB74D, COOL_D = 0xFF8AB4F8;

    /** White card in light mode, pure black in dark mode, outline only when "clear". */
    private static void paperBg(R r) {
        if (r.clear) {
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, RAD * r.u, 1f * r.u, 0x29FFFFFF);
        } else if (r.dark) {
            r.g.fillRoundRect(0, 0, r.w * r.u, r.h * r.u, RAD * r.u, fade(0xFF000000, r.fill));
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, RAD * r.u, 1f * r.u, 0x1FFFFFFF);
        } else {
            r.g.fillRoundRect(0, 0, r.w * r.u, r.h * r.u, RAD * r.u, fade(0xFFFFFFFF, r.fill));
        }
    }

    private static void lineCard(R r, Data d, boolean hourly) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        boolean paper = !r.dark;
        paperBg(r);
        int ink = paper ? 0xFF1F1F1F : WHITE, ink2 = paper ? 0xFF5F6368 : T2;
        int warm = paper ? WARM_L : WARM_D, cool = paper ? COOL_L : COOL_D;
        float pad = clamp(w * 0.085f, 10, 16);
        Weather.Now n = wx.now(d.nowSec);
        boolean wide = w >= 200, tall = h >= 150;

        // header
        float hs = tall || wide ? 15 : 12.5f;
        float top;
        String temp = u.t(n.temp);
        float tsz = tall ? 34 : wide ? 24 : 18;
        if (tall) {
            r.text(r.fit(d.place.shortName(), hs, Gfx.FONT_MEDIUM, w - 2 * pad), pad, pad + hs, hs, Gfx.FONT_MEDIUM, Gfx.LEFT, ink);
            r.text("H: " + u.t(wx.todayMax(d.nowSec)) + "  L: " + u.t(wx.todayMin(d.nowSec)), pad, pad + hs + 18, 13, Gfx.FONT_REGULAR, Gfx.LEFT, ink2);
            r.text(temp, pad, pad + hs + 18 + tsz + 2, tsz, Gfx.FONT_MEDIUM, Gfx.LEFT, warm);
            top = pad + hs + 18 + tsz + 8;
        } else {
            float tw = r.tw(temp, tsz, Gfx.FONT_MEDIUM);
            r.text(temp, w - pad, pad + tsz * 0.8f, tsz, Gfx.FONT_MEDIUM, Gfx.RIGHT, warm);
            r.text(r.fit(d.place.shortName(), hs, Gfx.FONT_MEDIUM, w - 2 * pad - tw - 8), pad, pad + hs, hs, Gfx.FONT_MEDIUM, Gfx.LEFT, ink);
            if (wide) {
                // high/low sits on the place's line so the chart gets the whole lower half
                String nm = r.fit(d.place.shortName(), hs, Gfx.FONT_MEDIUM, w - 2 * pad - tw - 8);
                float hx = pad + r.tw(nm, hs, Gfx.FONT_MEDIUM) + 10;
                String hl = "H:" + u.t(wx.todayMax(d.nowSec)) + " L:" + u.t(wx.todayMin(d.nowSec));
                if (hx + r.tw(hl, 12, Gfx.FONT_REGULAR) < w - pad - tw - 6) r.text(hl, hx, pad + hs, 12, Gfx.FONT_REGULAR, Gfx.LEFT, ink2);
                top = pad + hs + 8;
            } else {
                top = pad + 24;
            }
        }

        int base = hourly ? wx.hourIndex(d.nowSec) : wx.dayIndex(d.nowSec);
        int avail = (hourly ? wx.hTime.length : wx.dTime.length) - base;
        int cols = (int) clamp((float) Math.floor((w - 2 * pad) / (wide ? 44f : 30f)), 3, 7);
        cols = Math.min(cols, avail);
        if (cols < 2) return;
        double[] vals = new double[cols];
        Icon[] icons = new Icon[cols];
        String[] labels = new String[cols];
        for (int k = 0; k < cols; k++) {
            int j = base + k;
            if (hourly) {
                vals[k] = wx.hTemp[j];
                icons[k] = Wmo.icon(wx.hCode[j], wx.hDay[j]);
                labels[k] = k == 0 ? "Now" : (wide ? Fmt.hourTag(wx.hTime[j], wx.utcOffset, u.h24) : String.valueOf(k));
            } else {
                vals[k] = wx.dMax[j];
                icons[k] = Wmo.icon(wx.dCode[j], true);
                String nm = k == 0 ? "Today" : DAY_NAMES[Fmt.dow(wx.dTime[j] + 43200, wx.utcOffset)];
                labels[k] = wide ? (k == 0 ? "Today" : nm) : nm.substring(0, 1);
            }
        }
        chart(r, d, pad, w - pad, top, h - pad - 16, vals, icons, labels, ink, ink2, warm, cool, u, paper);
    }

    /** Smooth temperature curve with a weather glyph on every point and labels above and below. */
    private static void chart(R r, Data d, float left, float right, float top, float bottom, double[] v, Icon[] icons,
                              String[] labels, int ink, int ink2, int warm, int cool, Units u, boolean paper) {
        int n = v.length;
        float cw = (right - left) / n;
        float isz = clamp(Math.min(cw * 0.66f, (bottom - top) * 0.42f), 13, 26);
        float labelH = 15;
        float plotTop = top + labelH + isz / 2;
        float plotBot = bottom - isz / 2 - 6;
        if (plotBot - plotTop < 6) plotBot = plotTop + 6;
        double lo = 1e9, hi = -1e9;
        for (double x : v) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
        if (hi - lo < 2) { double mid = (hi + lo) / 2; hi = mid + 1; lo = mid - 1; }
        float[] px = new float[n], py = new float[n];
        for (int k = 0; k < n; k++) {
            px[k] = left + cw * (k + 0.5f);
            py[k] = plotBot - (float) ((v[k] - lo) / (hi - lo)) * (plotBot - plotTop);
        }
        // unlock animation: the curve draws itself left to right, glyphs pop in as it reaches them
        float prog = r.t < 0 ? 1f : clamp(r.t / 1.5f, 0, 1);
        int steps = 14;
        int total = (n - 1) * steps;
        int drawn = (int) (total * prog);
        float lx = px[0], ly = py[0];
        for (int i = 1; i <= Math.min(drawn, total); i++) {
            int seg = (i - 1) / steps;
            float f = ((i - 1) % steps + 1) / (float) steps;
            float x = catmull(px, seg, f), y = catmull(py, seg, f);
            double val = v[seg] + (v[Math.min(n - 1, seg + 1)] - v[seg]) * f;
            int col = mix(cool, warm, (float) ((val - lo) / (hi - lo)));
            r.g.line(lx * r.u, ly * r.u, x * r.u, y * r.u, 2.4f * r.u, col);
            lx = x;
            ly = y;
        }
        for (int k = 0; k < n; k++) {
            float reach = k * steps;
            float pop = r.t < 0 ? 1f : clamp((drawn - reach) / (steps * 0.8f), 0, 1);
            if (pop <= 0) continue;
            float s = isz * (0.6f + 0.4f * pop);
            if (paper) r.g.fillCircle(px[k] * r.u, py[k] * r.u, (s / 2 + 1.5f) * r.u, 0xFFFFFFFF);
            else r.g.fillCircle(px[k] * r.u, py[k] * r.u, (s / 2 + 1.5f) * r.u, 0xFF000000);
            Glyphs.draw(r.g, icons[k], (px[k] - s / 2) * r.u, (py[k] - s / 2) * r.u, s * r.u, r.t < 0 ? -1 : r.t, 0, paper);
            int c = mix(cool, warm, (float) ((v[k] - lo) / (hi - lo)));
            r.text(u.t(v[k]), px[k], py[k] - isz / 2 - 3, 12.5f, Gfx.FONT_MEDIUM, Gfx.CENTER, withAlpha(c, pop));
            r.text(r.fit(labels[k], 12, Gfx.FONT_REGULAR, cw), px[k], bottom + 13, 12, Gfx.FONT_REGULAR, Gfx.CENTER, k == 0 ? ink : ink2);
        }
    }

    private static float catmull(float[] p, int seg, float t) {
        int n = p.length;
        float p0 = p[Math.max(0, seg - 1)], p1 = p[seg], p2 = p[Math.min(n - 1, seg + 1)], p3 = p[Math.min(n - 1, seg + 2)];
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Pastel top/bottom colours for the big-number card. */
    private static int[] pastel(Mood m) {
        if (m == CLEAR_DAY) return new int[]{0xFFB7A2F0, 0xFF78A8F7};
        if (m == CLEAR_NIGHT) return new int[]{0xFF5B5FA8, 0xFF2A3470};
        if (m == CLOUDY) return new int[]{0xFF9FB1CE, 0xFF6F86AC};
        if (m == RAINY) return new int[]{0xFF7F9CC4, 0xFF4E6F9C};
        if (m == STORMY) return new int[]{0xFF8888C0, 0xFF4F4C86};
        if (m == SNOWY) return new int[]{0xFFA9C4E4, 0xFF7FA0C8};
        return new int[]{0xFFA9B3C2, 0xFF7C8799};
    }

    private static void tintedBg(R r, int top, int bottom) {
        if (r.clear) {
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, RAD * r.u, 1f * r.u, 0x29FFFFFF);
            return;
        }
        if (r.dark) {
            top = alpha(mix(top, 0xFF000000, 0.6f), 0xF2);
            bottom = 0xFF000000;
        }
        r.g.fillRoundRectGradient(0, 0, r.w * r.u, r.h * r.u, RAD * r.u, fade(top, r.fill), fade(bottom, r.fill));
        if (r.dark) r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, RAD * r.u, 1f * r.u, 0x1FFFFFFF);
    }

    private static void bigNumber(R r, Mood m, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        int[] pc = r.skin != null ? new int[]{r.pastelTop, r.pastelBottom} : pastel(m);
        tintedBg(r, pc[0], pc[1]);
        fx(r, m, d);
        Weather.Now n = wx.now(d.nowSec);
        float pad = clamp(w * 0.07f, 10, 16);

        // weather orb top-left, high/low top-right
        boolean compact = w < 150;
        float isz = compact ? 22 : clamp(Math.min(w, h) * 0.26f, 22, 36);
        r.iconOutlined(Wmo.icon(n.code, n.isDay), pad - 2, pad - 3, isz, 0xFF20304F);
        float hiLoCell = compact ? 1.05f : 1.35f;
        hiLo(r, w - pad, pad + 1, hiLoCell, wx.todayMax(d.nowSec), wx.todayMin(d.nowSec), u, Gfx.RIGHT);

        // giant thin temperature
        String t = u.t(n.temp);
        float ts = clamp(Math.min(h * 0.56f, w * 0.44f), 40, 96);
        float cy = h * 0.5f + ts * 0.33f + 4;
        r.text(t, w / 2, cy, ts, Gfx.FONT_LIGHT, Gfx.CENTER, WHITE);

        // place and local time
        String foot = w < 150 ? d.place.shortName() : d.place.shortName() + " @ " + u.time(d.nowSec, wx.utcOffset, true);
        float fs = 11.5f;
        r.text(r.fit(foot, fs, Gfx.FONT_MEDIUM, w - 2 * pad - 14), w / 2 + 6, h - pad + 1, fs, Gfx.FONT_MEDIUM, Gfx.CENTER, 0xE6FFFFFF);
        float fw = Math.min(r.tw(foot, fs, Gfx.FONT_MEDIUM), w - 2 * pad - 14);
        r.g.fillCircle((w / 2 + 6 - fw / 2 - 7) * r.u, (h - pad - 3.5f) * r.u, 2.6f * r.u, 0xE6FFFFFF);
    }

    private static void classic(R r, Mood m, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        int top = r.skin != null ? r.skin.top : m.top;
        int bottom = r.skin != null ? r.skin.bottom : mix(m.top, 0xFFFFFFFF, 0.28f);
        tintedBg(r, top, bottom);
        fx(r, m, d);
        Weather.Now n = wx.now(d.nowSec);
        float pad = clamp(w * 0.09f, 10, 16);
        if (w >= 170 && h < 135) {
            // short and wide: place and temperature on the left, cat and condition on the right
            float ls2 = 16;
            r.text(r.fit(d.place.shortName(), ls2, Gfx.FONT_MEDIUM, w * 0.5f), pad, pad + ls2, ls2, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
            float ts2 = clamp(h * 0.50f, 34, 56);
            r.text(u.t(n.temp), pad - 1, pad + ls2 + 4 + ts2 * 0.82f, ts2, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
            float isz2 = clamp(h * 0.38f, 28, 44);
            r.icon(Wmo.icon(n.code, n.isDay), w - pad - isz2, pad - 2, isz2);
            r.text(r.fit(Wmo.label(n.code), 13, Gfx.FONT_MEDIUM, w * 0.4f), w - pad, h - pad - 12, 13, Gfx.FONT_MEDIUM, Gfx.RIGHT, WHITE);
            r.text("L:" + u.t(wx.todayMin(d.nowSec)) + " H:" + u.t(wx.todayMax(d.nowSec)), w - pad, h - pad + 3, 13, Gfx.FONT_MEDIUM, Gfx.RIGHT, WHITE);
            return;
        }
        float ls = clamp(w * 0.15f, 15, 22);
        float locBase = pad + ls;
        r.text(r.fit(d.place.shortName(), ls, Gfx.FONT_MEDIUM, w - 2 * pad), pad, locBase, ls, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
        float ts = clamp(h * 0.38f, 38, 72);
        String tt = u.t(n.temp);
        while (ts > 30 && r.tw(tt, ts, Gfx.FONT_LIGHT) > w - 2 * pad) ts -= 2;
        r.text(tt, pad - 1, locBase + 6 + ts * 0.82f, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);

        float lh = 13;
        float lhBase = h - pad + 2;
        r.text("L:" + u.t(wx.todayMin(d.nowSec)) + " H:" + u.t(wx.todayMax(d.nowSec)), pad, lhBase, lh, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
        float cBase = lhBase - 16;
        float ic = 18;
        r.icon(Wmo.icon(n.code, n.isDay), pad - 2, cBase - ic - 1, ic);
        r.text(r.fit(Wmo.label(n.code), lh, Gfx.FONT_MEDIUM, w - 2 * pad - ic - 4), pad + ic + 3, cBase - 4, lh, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
    }

    private static void rainSoon(R r, Mood m, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        Nowcast.Result nc = Nowcast.compute(wx, d.nowSec);
        boolean wet = nc != null && nc.state != Nowcast.DRY;
        Mood mx = wet ? RAINY : (m == CLEAR_NIGHT ? CLEAR_NIGHT : CLOUDY);
        tintedBg(r, mx.top, mx.bottom);
        float pad = clamp(w * 0.08f, 10, 16);
        boolean wide = w >= 200;

        String head, sub;
        Icon icon;
        if (nc == null) {
            Weather.Now n = wx.now(d.nowSec);
            icon = Wmo.icon(n.code, n.isDay);
            head = Wmo.isWet(n.code) ? "Raining now" : "No rain nearby";
            sub = "Hourly estimate";
        } else if (nc.state == Nowcast.STARTS) {
            icon = Icon.UMBRELLA;
            head = "Rain in ~" + Nowcast.minutesText(nc.minutes);
            sub = Nowcast.intensity(nc.peakRate) + " \u00b7 bring an umbrella";
        } else if (nc.state == Nowcast.RAINING) {
            icon = Icon.RAIN;
            head = nc.minutes > 0 ? "Stops in ~" + Nowcast.minutesText(nc.minutes) : "Raining, may last";
            sub = Nowcast.intensity(nc.peakRate) + " now";
        } else {
            icon = Icon.SLEEPY;
            head = "No rain for " + Math.min(nc.windowMinutes, 240) / 60 + " h";
            sub = "Dry paws, nap time";
        }
        if (!wide) {
            sub = sub.replace(" \u00b7 bring an umbrella", "").replace(" rain now", "").replace(" rain", "").replace("Dry paws, nap time", "Nap time");
        }
        float isz = clamp(Math.min(h * 0.26f, 38), 24, 40);
        r.icon(icon, pad - 2, pad - 3, isz);
        float tx = pad + isz + 8;
        float hs = wide ? 17 : 14.5f;
        java.util.List<String> lines = wrapFit(r, head, "Rain " + (nc != null && nc.state == Nowcast.STARTS ? "soon" : "now"), hs, Gfx.FONT_MEDIUM, w - tx - pad, 2);
        float by = pad + hs - 1;
        for (String ln : lines) {
            r.text(ln, tx, by, hs, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
            by += hs + 2;
        }
        r.text(r.fit(sub, 11.5f, Gfx.FONT_REGULAR, w - tx - pad), tx, by + 1, 11.5f, Gfx.FONT_REGULAR, Gfx.LEFT, T2);

        // bars: the next 2-3 hours in 15 minute steps (or hours when only hourly data exists)
        int bins = wide ? 12 : 8;
        double[] rate = new double[bins];
        String[] tags = new String[]{"Now", "+1h", "+2h", "+3h"};
        if (nc != null) {
            int cur = 0;
            for (int i = 0; i < wx.mTime.length; i++) if (d.nowSec < wx.mTime[i]) { cur = i; break; }
            for (int i = 0; i < bins && cur + i < wx.mPrecip.length; i++) rate[i] = Math.max(0, wx.mPrecip[cur + i]) * 4;
        } else {
            int hi = wx.hourIndex(d.nowSec);
            for (int i = 0; i < bins; i++) {
                int j = hi + i / 4;
                rate[i] = j < wx.hPrecip.length && !Double.isNaN(wx.hPrecip[j]) ? wx.hPrecip[j] : 0;
            }
        }
        double top = 2.5;
        for (double v : rate) top = Math.max(top, v);
        float gap = 3;
        float left = pad, right = w - pad;
        float bw = (right - left - gap * (bins - 1)) / bins;
        float baseY = h - pad - 14;
        float maxH = Math.max(14, baseY - (by + 12));
        for (int i = 0; i < bins; i++) {
            float bh = rate[i] < 0.3 ? 3 : 3 + (float) (rate[i] / top) * (maxH - 3);
            float x = left + i * (bw + gap);
            int col = rate[i] < 0.3 ? 0x33FFFFFF : mix(0xFF9CCBFF, 0xFF4C8DF6, (float) Math.min(1, rate[i] / 8));
            if (i == 0 && rate[i] >= 0.3) col = WHITE;
            r.rect(x, baseY - bh, x + bw, baseY, Math.min(bw / 2, 3.5f), col);
        }
        int per = bins / (wide ? 3 : 2);
        for (int t = 0; t * per < bins && t < 4; t++) {
            float x = left + t * per * (bw + gap);
            r.text(tags[t], x, h - pad + 1, 10.5f, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        }
    }

    // ---- CLOCKS ---------------------------------------------------------------------------------------------------

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static void clocks(R r, int kind, Mood m, Data d) {
        switch (kind) {
            case CLOCK_BIG: clockBig(r, m, d); break;
            case CLOCK_SKY: clockSky(r, m, d); break;
            case CLOCK_FLIP: clockFlip(r, d); break;
            case CLOCK_SCHEDULE: clockSchedule(r, m, d); break;
            default: clockDay(r, m, d); break;
        }
    }

    /** {hour 0-23, minute, second} on the phone's clock. */
    private static int[] hms(Data d, int off) {
        long local = d.nowSec + off;
        int sod = (int) (((local % 86400L) + 86400L) % 86400L);
        return new int[]{sod / 3600, (sod % 3600) / 60, sod % 60};
    }

    private static String dateText(Data d, int off) {
        long local = d.nowSec + off;
        int[] c = Fmt.civil(Math.floorDiv(local, 86400L));
        return DAY_NAMES[Fmt.dow(d.nowSec, off)] + ", " + c[2] + " " + MONTHS[c[1] - 1];
    }

    private static String hourText(int h24, boolean is24) {
        return is24 ? (h24 < 10 ? "0" + h24 : String.valueOf(h24)) : String.valueOf(h24 % 12 == 0 ? 12 : h24 % 12);
    }

    private static String minText(int m) {
        return m < 10 ? "0" + m : String.valueOf(m);
    }

    /** Big, light digital time with the date and the weather at a glance. */
    private static void clockBig(R r, Mood m, Data d) {
        float w = r.w, h = r.h;
        Mood sk = r.skin != null ? r.skin : m;
        tintedBg(r, sk.top, sk.bottom);
        if (d.ready()) fx(r, m, d);
        int[] t = hms(d, d.tzOffset);
        boolean is24 = d.units.h24;
        float pad = clamp(w * 0.09f, 10, 16);
        Weather.Now n = d.ready() ? d.weather.now(d.nowSec) : null;
        if (w < 160) {
            float ts = clamp(h * 0.40f, 34, 72);
            r.text(hourText(t[0], is24), pad - 2, pad + ts * 0.80f, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
            r.text(minText(t[1]), pad - 2, pad + ts * 0.80f + ts * 0.90f, ts, Gfx.FONT_LIGHT, Gfx.LEFT, 0xE6FFFFFF);
            r.text(dateText(d, d.tzOffset), pad, h - pad + 1, 12.5f, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
            if (n != null) r.icon(Wmo.icon(n.code, n.isDay), w - pad - 26, pad - 2, 26);
        } else {
            String time = hourText(t[0], is24) + ":" + minText(t[1]);
            float ts = clamp(h * 0.62f, 40, 110);
            while (ts > 30 && r.tw(time, ts, Gfx.FONT_LIGHT) > w * 0.60f) ts -= 2;
            float base = pad + ts * 0.82f;
            r.text(time, pad - 3, base, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
            if (!is24) r.text(t[0] < 12 ? "AM" : "PM", pad - 3 + r.tw(time, ts, Gfx.FONT_LIGHT) + 4, base, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
            r.text(dateText(d, d.tzOffset), pad, base + 22, 15, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
            if (n != null) {
                float isz = clamp(h * 0.34f, 28, 44);
                r.icon(Wmo.icon(n.code, n.isDay), w - pad - isz, pad - 2, isz);
                r.text(d.units.t(n.temp), w - pad, pad + isz + 24, 24, Gfx.FONT_MEDIUM, Gfx.RIGHT, WHITE);
                r.text(r.fit(d.place.shortName(), 12, Gfx.FONT_REGULAR, w * 0.30f), w - pad, h - pad + 1, 12, Gfx.FONT_REGULAR, Gfx.RIGHT, T2);
            }
        }
    }

    private static void poly(R r, float[] xs, float[] ys, int color) {
        float[] px = new float[xs.length], py = new float[ys.length];
        for (int i = 0; i < xs.length; i++) { px[i] = xs[i] * r.u; py[i] = ys[i] * r.u; }
        r.g.fillPolygon(px, py, color);
    }

    /** Time on top; underneath, a cat that walks along the day from midnight to midnight, with sunrise and sunset marked. */
    private static void clockDay(R r, Mood m, Data d) {
        float w = r.w, h = r.h;
        Mood sk = r.skin != null ? r.skin : m;
        tintedBg(r, sk.top, sk.bottom);
        int[] t = hms(d, d.tzOffset);
        boolean is24 = d.units.h24;
        float pad = clamp(w * 0.06f, 12, 16);
        String time = hourText(t[0], is24) + ":" + minText(t[1]);
        float ts = clamp(h * 0.37f, 18, 60);
        float ampmW = is24 ? 0 : 20;
        while (ts > 20 && r.tw(time, ts, Gfx.FONT_LIGHT) + ampmW + 4 > w - 2 * pad + 2) ts -= 2;
        float timeW = r.tw(time, ts, Gfx.FONT_LIGHT);
        float base = pad + ts * 0.80f;
        r.text(time, pad - 2, base, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
        if (!is24) r.text(t[0] < 12 ? "AM" : "PM", pad - 2 + timeW + 4, base, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        String date = dateText(d, d.tzOffset);
        float dateW = r.tw(date, 14, Gfx.FONT_MEDIUM);
        if (pad - 2 + timeW + ampmW + 12 + dateW <= w - pad) {
            r.text(date, w - pad, pad + 16, 14, Gfx.FONT_MEDIUM, Gfx.RIGHT, WHITE);
        } else if (h >= 100) {
            // narrow card: the date goes under the time instead of beside it
            r.text(r.fit(date, 14, Gfx.FONT_MEDIUM, w - 2 * pad), pad, base + 20, 14, Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
        }
        if (h < 90) return; // a strip: just the time, the sun track needs more room

        float f = (t[0] * 3600 + t[1] * 60 + t[2]) / 86400f;
        float trackY = h - pad - 22, trackL = pad + 6, trackR = w - pad - 6, th = 8;
        float fr = -1, fs = -1;
        boolean day = t[0] >= 6 && t[0] < 18;
        String note = "";
        if (d.ready()) {
            int di = d.weather.dayIndex(d.nowSec);
            if (di < d.weather.dSunrise.length && d.weather.dSunrise[di] > 0) {
                int off = d.weather.utcOffset;
                long srSec = d.weather.dSunrise[di], ssSec = d.weather.dSunset[di];
                fr = (float) (((srSec + off) % 86400L + 86400L) % 86400L) / 86400f;
                fs = (float) (((ssSec + off) % 86400L + 86400L) % 86400L) / 86400f;
                day = d.nowSec >= srSec && d.nowSec < ssSec;
                if (day) note = "Sunset in " + Fmt.duration(ssSec - d.nowSec);
                else if (d.nowSec < srSec) note = "Sunrise in " + Fmt.duration(srSec - d.nowSec);
                else note = "Sunrise tomorrow";
            }
        }
        r.rect(trackL, trackY, trackR, trackY + th, th / 2, TILE);
        if (fr >= 0) r.rect(trackL + (trackR - trackL) * fr, trackY, trackL + (trackR - trackL) * fs, trackY + th, th / 2, 0x40FFD54F);
        float cx = trackL + (trackR - trackL) * f;
        r.rect(trackL, trackY, Math.max(trackL + th, cx), trackY + th, th / 2, day ? 0xFFFFD54F : 0xFF9FA8DA);
        float cs = clamp(h * 0.24f, 22, 30);
        r.icon(day ? Icon.SUN : Icon.SLEEPY, cx - cs / 2, trackY + th / 2 - cs + 5, cs);
        float avail = w - 2 * pad;
        boolean room = r.tw(note, 12, Gfx.FONT_MEDIUM) + r.tw("12 AM", 11, Gfx.FONT_REGULAR) + 12 <= avail;
        if (note.length() > 0) r.text(room ? note : r.fit(note, 12, Gfx.FONT_MEDIUM, avail), pad, h - pad + 3, 12, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        if (room || note.length() == 0) r.text("12 AM", w - pad, h - pad + 3, 11, Gfx.FONT_REGULAR, Gfx.RIGHT, T3);
    }


    // ---- sky clock ----
    private static final float[] SKY_H = {0f, 5.0f, 6.5f, 8f, 12f, 16f, 17.8f, 19f, 20.5f, 24f};
    private static final int[] SKY_TOP = {0xFF0B1030, 0xFF1D2159, 0xFF3F4A8F, 0xFF4B93EE, 0xFF1A73E8, 0xFF3D8BEA, 0xFF4E4A9A, 0xFF1F2A60, 0xFF0F1538, 0xFF0B1030};
    private static final int[] SKY_BOTTOM = {0xFF1B2358, 0xFF4A3C80, 0xFFF7A072, 0xFFBFE3FF, 0xFF64B5F6, 0xFFFFD27F, 0xFFFF7E5F, 0xFF6A4C93, 0xFF222B66, 0xFF1B2358};

    /** The card's sky really is the sky: dawn peach, noon blue, sunset orange, starry night, with the sun or moon crossing it. */
    private static void clockSky(R r, Mood m, Data d) {
        float w = r.w, h = r.h;
        int[] t = hms(d, d.tzOffset);
        float hr = t[0] + t[1] / 60f + t[2] / 3600f;
        int k = 0;
        while (k < SKY_H.length - 2 && hr >= SKY_H[k + 1]) k++;
        float f = clamp((hr - SKY_H[k]) / (SKY_H[k + 1] - SKY_H[k]), 0, 1);
        int top = mix(SKY_TOP[k], SKY_TOP[k + 1], f), bottom = mix(SKY_BOTTOM[k], SKY_BOTTOM[k + 1], f);
        tintedBg(r, top, bottom);
        r.g.clipRoundRect(0, 0, w * r.u, h * r.u, RAD * r.u);
        float night = hr < 5.5f || hr > 19.5f ? 1f : (hr < 7f ? (7f - hr) / 1.5f : (hr > 18f ? (hr - 18f) / 1.5f : 0f));
        if (night > 0) {
            for (int i = 0; i < 26; i++) {
                float sx = hash(i, 11) * w, sy = hash(i, 12) * h * 0.8f;
                float tw = 0.5f + 0.5f * (float) Math.sin((r.t < 0 ? 0 : r.t) * 2 + hash(i, 13) * 6.28);
                r.rect(sx, sy, sx + 1.8f, sy + 1.8f, 0.9f, withAlpha(0xFFFFFF, night * (0.4f + 0.5f * tw)));
            }
        }
        // sun (6-18) or moon (18-6) travelling along an arc behind the digits
        boolean day = hr >= 6f && hr < 18f;
        float p = day ? (hr - 6f) / 12f : ((hr >= 18f ? hr - 18f : hr + 6f) / 12f);
        float ox = w * (0.10f + 0.80f * p), oy = h * 0.34f - (float) Math.sin(Math.PI * p) * h * 0.24f;
        if (day) {
            r.g.glow(ox * r.u, oy * r.u, h * 0.42f * r.u, withAlpha(0xFFE082, 0.45f));
            r.g.fillCircle(ox * r.u, oy * r.u, 9 * r.u, 0xFFFFD54F);
        } else {
            r.g.glow(ox * r.u, oy * r.u, h * 0.32f * r.u, withAlpha(0xC5CAE9, 0.30f));
            r.g.crescent(ox * r.u, oy * r.u, 9 * r.u, 0xFFF6E7B4);
        }
        r.g.restoreClip();
        float pad = clamp(w * 0.07f, 12, 16);
        boolean is24 = d.units.h24;
        String time = hourText(t[0], is24) + ":" + minText(t[1]);
        float ts = clamp(h * 0.42f, 30, 70);
        while (ts > 28 && r.tw(time, ts, Gfx.FONT_LIGHT) > w * 0.62f) ts -= 2;
        r.text(time, pad - 2, h - pad - 18, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
        if (!is24) r.text(t[0] < 12 ? "AM" : "PM", pad - 2 + r.tw(time, ts, Gfx.FONT_LIGHT) + 4, h - pad - 18, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        r.text(dateText(d, d.tzOffset), pad, h - pad + 3, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
    }

    // ---- flip clock ----
    private static void flipTile(R r, float x, float y, float w, float h, String text, float fs, int[] c, int ink, boolean outline) {
        float rad = Math.min(14, w * 0.17f);
        float u = r.u;
        r.g.clipRoundRect(x * u, y * u, (x + w) * u, (y + h) * u, rad * u);
        r.g.fillRoundRectGradient(x * u, y * u, (x + w) * u, (y + h / 2) * u, 0, c[0], c[1]);
        r.g.fillRoundRectGradient(x * u, (y + h / 2) * u, (x + w) * u, (y + h) * u, 0, c[2], c[3]);
        r.rect(x, y, x + w, y + 1.2f, 0, 0x2EFFFFFF);
        r.g.restoreClip();
        if (outline) r.g.strokeRoundRect((x + 0.5f) * u, (y + 0.5f) * u, (x + w - 0.5f) * u, (y + h - 0.5f) * u, rad * u, 1f * u, 0x26FFFFFF);
        float base = y + h / 2 + fs * 0.35f;
        r.text(text, x + w / 2 + 0.7f, base + 1.6f, fs, Gfx.FONT_BOLD, Gfx.CENTER, 0x66000000);
        r.text(text, x + w / 2, base, fs, Gfx.FONT_BOLD, Gfx.CENTER, ink);
        // hinge with a little bevel, and the two pins
        r.rect(x, y + h / 2 - 0.8f, x + w, y + h / 2 + 0.8f, 0, c[4]);
        r.rect(x, y + h / 2 + 0.8f, x + w, y + h / 2 + 1.5f, 0, 0x16FFFFFF);
        r.g.fillCircle(x * u, (y + h / 2) * u, 3.4f * u, c[4]);
        r.g.fillCircle((x + w) * u, (y + h / 2) * u, 3.4f * u, c[4]);
    }

    /** Two split-flap tiles with gloss, a bevelled hinge and a cat peeking over the minutes. No card, so it sits on the wallpaper. */
    private static void clockFlip(R r, Data d) {
        float w = r.w, h = r.h;
        int[] t = hms(d, d.tzOffset);
        boolean is24 = d.units.h24;
        int[] c;
        if (r.skin != null) {
            int base = mix(r.skin.top, 0xFF000000, r.dark ? 0.72f : 0.55f);
            c = new int[]{mix(base, 0xFFFFFFFF, 0.14f), mix(base, 0xFFFFFFFF, 0.06f), base, mix(base, 0xFF000000, 0.25f), mix(base, 0xFF000000, 0.7f)};
        } else if (r.dark) {
            c = new int[]{0xFF2A2C32, 0xFF202226, 0xFF191A1E, 0xFF121316, 0xFF000000};
        } else {
            c = new int[]{0xFF454851, 0xFF363940, 0xFF2D3037, 0xFF23252B, 0xFF0F1013};
        }
        float head = 18, labelH = 24, gap = 14;
        float ch = h - head - labelH;
        float cw = (w - gap) / 2f;
        float fs = clamp(Math.min(ch * 0.74f, cw / 1.42f), 24, 90);
        // the cat sits behind the tiles so only its head shows over the minutes tile
        boolean night = t[0] < 6 || t[0] >= 19;
        float cs = 34;
        r.icon(night ? Icon.PARTLY_NIGHT : Icon.CLOUD, w - cs - clamp(cw * 0.12f, 6, 14), head - cs * 0.58f, cs);
        flipTile(r, 0, head, cw, ch, hourText(t[0], is24), fs, c, 0xFFF4F4F6, r.dark);
        flipTile(r, cw + gap, head, cw, ch, minText(t[1]), fs, c, 0xFFF4F4F6, r.dark);
        r.g.fillCircle((cw + gap / 2) * r.u, (head + ch / 2 - 8) * r.u, 2.2f * r.u, r.dark ? 0xCCFFFFFF : 0xCC2B2D33);
        r.g.fillCircle((cw + gap / 2) * r.u, (head + ch / 2 + 8) * r.u, 2.2f * r.u, r.dark ? 0xCCFFFFFF : 0xCC2B2D33);
        if (!is24) r.text(t[0] < 12 ? "AM" : "PM", 9, head + 15, 10, Gfx.FONT_BOLD, Gfx.LEFT, 0x99FFFFFF);
        r.text(dateText(d, d.tzOffset), w / 2, h - 5, 12.5f, Gfx.FONT_MEDIUM, Gfx.CENTER, r.dark ? 0xFFC4C7C5 : 0xFF444746);
    }

    // ---- cat schedule clock ----
    /** What is the cat doing right now? Time plus the cat's daily routine. */
    private static void clockSchedule(R r, Mood m, Data d) {
        float w = r.w, h = r.h;
        Mood sk = r.skin != null ? r.skin : m;
        tintedBg(r, sk.top, sk.bottom);
        int[] t = hms(d, d.tzOffset);
        boolean is24 = d.units.h24;
        String phrase;
        Icon icon;
        int hr = t[0];
        boolean wetNow = d.ready() && Wmo.isWet(d.weather.now(d.nowSec).code);
        if (hr < 5) { phrase = "Zoomies hour"; icon = Icon.WIND; }
        else if (hr < 7) { phrase = "Breakfast o'clock"; icon = Icon.SUN; }
        else if (hr < 11) { phrase = wetNow ? "Indoor cat day" : "Sunbeam patrol"; icon = wetNow ? Icon.UMBRELLA : Icon.SHADES; }
        else if (hr < 15) { phrase = "Nap o'clock"; icon = Icon.SLEEPY; }
        else if (hr < 17) { phrase = "Window watching"; icon = Icon.CLOUD; }
        else if (hr < 19) { phrase = "Dinner o'clock"; icon = Icon.SNOW; }
        else if (hr < 22) { phrase = "Cuddle time"; icon = Icon.SUNSET; }
        else { phrase = "Night patrol"; icon = Icon.MOON; }
        float pad = clamp(w * 0.06f, 12, 16);
        String time = hourText(t[0], is24) + ":" + minText(t[1]);
        float ts = clamp(h * 0.56f, 36, 90);
        while (ts > 30 && r.tw(time, ts, Gfx.FONT_LIGHT) > w * 0.55f) ts -= 2;
        r.text(time, pad - 2, pad + ts * 0.80f, ts, Gfx.FONT_LIGHT, Gfx.LEFT, WHITE);
        if (!is24) r.text(t[0] < 12 ? "AM" : "PM", pad - 2 + r.tw(time, ts, Gfx.FONT_LIGHT) + 4, pad + ts * 0.80f, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        r.text(dateText(d, d.tzOffset), pad, h - pad + 3, 13, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        float isz = clamp(h * 0.42f, 34, 56);
        r.icon(icon, w - pad - isz, pad - 2, isz);
        float dateW = r.tw(dateText(d, d.tzOffset), 13, Gfx.FONT_MEDIUM);
        r.text(r.fit(phrase, 14.5f, Gfx.FONT_MEDIUM, w - 2 * pad - dateW - 10), w - pad, h - pad + 3, 14.5f, Gfx.FONT_MEDIUM, Gfx.RIGHT, WHITE);
    }


    // ---- NOW WIDE (4x2) ------------------------------------------------------------------------------------------

    private static void nowWide(R r, Data d) {
        float w = r.w, h = r.h;
        float s = clamp(Math.min(w / 280f, h / 118f), 0.72f, 1.6f);
        float pad = 16 * s;
        Weather.Now n = d.weather.now(d.nowSec);
        Units u = d.units;

        float isz = clamp(Math.min(h * 0.5f, w * 0.26f), 40, 90);
        float real = r.iconSize(isz);
        float half = Math.max(real / 2, 46 * s);
        float rightCx = w - pad * 0.7f - half;
        float leftMax = rightCx - half - pad;

        String temp = w >= 240 ? u.tUnit(n.temp) : u.t(n.temp);
        float tsz = clamp(h * 0.44f, 34, 66);
        while (tsz > 30 && r.tw(temp, tsz, Gfx.FONT_BOLD) > leftMax) tsz -= 2;
        float tBase = pad * 0.75f + tsz * 0.8f;
        r.text(temp, pad - 2, tBase, tsz, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);

        float lsz = 13.5f * s;
        float lBase = tBase + lsz + 6 * s;
        r.text(r.fit(d.place.title(), lsz, Gfx.FONT_LIGHT, leftMax), pad, lBase, lsz, Gfx.FONT_LIGHT, Gfx.LEFT, T2);

        float cell = 1.6f * s;
        float pY = lBase + 8 * s;
        hiLo(r, pad, pY, cell, d.weather.todayMax(d.nowSec), d.weather.todayMin(d.nowSec), u, Gfx.LEFT);

        float hBase = pY + r.pixH(cell) + 6 * s + 12 * s;
        if (hBase < h - 4) {
            r.text("humidity: " + Math.round(n.humidity) + "%", pad, hBase, 12.5f * s, Gfx.FONT_LIGHT, Gfx.LEFT, T2);
        }

        float iy = (h - real) / 2 - 10 * s;
        r.icon(Wmo.icon(n.code, n.isDay), rightCx - real / 2, iy, isz);
        String label = Wmo.label(n.code);
        float csz = 15 * s;
        while (csz > 10 && r.tw(label, csz, Gfx.FONT_LIGHT) > 2 * half) csz -= 0.5f;
        r.text(r.fit(label, csz, Gfx.FONT_LIGHT, 2 * half), rightCx, iy + real + csz + 5 * s, csz, Gfx.FONT_LIGHT, Gfx.CENTER, WHITE);
    }


    // ---- NOW TALL (2x3, 2x4) -------------------------------------------------------------------------------------

    private static void nowTall(R r, Data d) {
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.1f, 10, 16);
        Weather wx = d.weather;
        Units u = d.units;
        Weather.Now n = wx.now(d.nowSec);
        float isz = clamp(w * 0.26f, 22, 40);
        float real = r.icon(Wmo.icon(n.code, n.isDay), w - pad - isz, pad - 3, isz);
        r.text(r.fit(d.place.shortName(), 12, Gfx.FONT_MEDIUM, w - 2 * pad - real - 4), pad, pad + 10, 12, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        float ts = clamp(w * 0.36f, 32, 50);
        float tBase = pad + 18 + ts * 0.8f;
        r.text(u.t(n.temp), pad - 1, tBase, ts, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        float cBase = tBase + 17;
        r.text(r.fit(Wmo.label(n.code), 12, Gfx.FONT_REGULAR, w - 2 * pad), pad, cBase, 12, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        float cell = 1.5f;
        float pY = cBase + 9;
        hiLo(r, pad, pY, cell, wx.todayMax(d.nowSec), wx.todayMin(d.nowSec), u, Gfx.LEFT);
        float y0 = pY + r.pixH(cell) + 10;
        r.rect(pad, y0, w - pad, y0 + 1, 0.5f, 0x22FFFFFF);

        float top = y0 + 6;
        float rowH = 28;
        int hi = wx.hourIndex(d.nowSec);
        int rows = (int) Math.floor((h - pad - top + 2) / rowH);
        rows = Math.min(rows, Math.min(6, wx.hTime.length - hi - 1));
        for (int k = 1; k <= rows; k++) {
            int j = hi + k;
            float cy = top + (k - 1) * rowH + rowH / 2;
            r.pix(Fmt.hourTag(wx.hTime[j], wx.utcOffset, u.h24), pad, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.LEFT, T2);
            float ix = pad + (w < 150 ? 38 : 56);
            r.icon(Wmo.icon(wx.hCode[j], wx.hDay[j]), ix, cy - r.iconSize(20) / 2, 20);
            String tt = u.t(wx.hTemp[j]);
            r.text(tt, w - pad, cy + 5, 14, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
            popBetween(r, wx.hPop[j], ix + 22, w - pad - r.tw(tt, 14, Gfx.FONT_BOLD) - 8, cy);
        }
    }

    // ---- HOURLY TALL (2x3, 2x4) ----------------------------------------------------------------------------------

    private static void hourlyTall(R r, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.08f, 10, 14);
        Weather.Now n = wx.now(d.nowSec);
        int hi = wx.hourIndex(d.nowSec);
        String temp = u.t(n.temp);
        r.text(r.fit(d.place.shortName(), 13, Gfx.FONT_BOLD, w - 2 * pad), pad, pad + 12, 13, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        float tsz = 24;
        float tw = r.tw(temp, tsz, Gfx.FONT_BOLD);
        r.text(temp, pad - 1, pad + 12 + 6 + tsz * 0.8f, tsz, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        r.text(r.fit(Wmo.label(n.code), 11, Gfx.FONT_REGULAR, w - 2 * pad - tw - 8), w - pad, pad + 12 + 6 + tsz * 0.8f - 2, 11, Gfx.FONT_REGULAR, Gfx.RIGHT, T2);

        float top = pad + 12 + 6 + tsz + 8;
        float rowH = h >= 220 ? 30 : 28;
        int rows = (int) Math.floor((h - pad - top + 4) / rowH);
        rows = Math.max(1, Math.min(rows, Math.min(9, wx.hTime.length - hi)));
        for (int k = 0; k < rows; k++) {
            int j = hi + k;
            float y = top + k * rowH, cy = y + rowH / 2;
            if (k == 0) r.rect(pad - 3, y + 1, w - pad + 3, y + rowH - 1, 10, TILE);
            String tag = k == 0 ? "NOW" : Fmt.hourTag(wx.hTime[j], wx.utcOffset, u.h24);
            r.pix(tag, pad + 3, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.LEFT, k == 0 ? WHITE : T2);
            float ix = pad + (w < 150 ? 36 : 54);
            r.icon(Wmo.icon(wx.hCode[j], wx.hDay[j]), ix, cy - r.iconSize(20) / 2, 20);
            String tt = u.t(wx.hTemp[j]);
            r.text(tt, w - pad - 3, cy + 5, 14, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
            popBetween(r, wx.hPop[j], ix + 22, w - pad - 3 - r.tw(tt, 14, Gfx.FONT_BOLD) - 8, cy);
        }
    }

    /** Chance of rain in a list row: drawn only where it fits between the icon and the temperature, never on top of them. */
    private static void popBetween(R r, double pop, float left, float right, float cy) {
        if (Double.isNaN(pop) || pop < 20) return;
        String t = Math.round(pop) + "%";
        if (right - r.pixW(t, 1.2f) < left) return;
        r.pix(t, right, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.RIGHT, POP);
    }

    /** The first wording that fits on one line; if none do, the last one, ellipsised. */
    private static String pick(R r, float size, int font, float maxW, String... options) {
        for (String o : options) {
            if (o != null && r.tw(o, size, font) <= maxW) return o;
        }
        return r.fit(options[options.length - 1], size, font, maxW);
    }

    /** First two words of a short wording, for the tightest strips. */
    private static String tiny(String s) {
        if (s == null) return null;
        String[] w = s.split(" ");
        return w.length <= 2 ? s : w[0] + " " + w[1];
    }

    /** Wraps the full wording; if that would be cut off, wraps the short wording instead. */
    private static java.util.List<String> wrapFit(R r, String full, String shortV, float size, int font, float maxW, int maxLines) {
        java.util.List<String> lines = wrap(r, full, size, font, maxW, maxLines);
        boolean cut = !lines.isEmpty() && lines.get(lines.size() - 1).endsWith("\u2026");
        if (cut && shortV != null && !shortV.equals(full)) lines = wrap(r, shortV, size, font, maxW, maxLines);
        return lines;
    }

    /** Greedy word wrap; once maxLines is reached the remainder is squeezed into the last line and ellipsised. */
    private static java.util.List<String> wrap(R r, String s, float size, int font, float maxW, int maxLines) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String[] words = s.split(" ");
        String line = "";
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
            if (line.length() == 0 || r.tw(tryLine, size, font) <= maxW) {
                line = tryLine;
            } else if (out.size() == maxLines - 1) {
                StringBuilder rest = new StringBuilder(line);
                for (int k = i; k < words.length; k++) rest.append(' ').append(words[k]);
                line = rest.toString();
                break;
            } else {
                out.add(line);
                line = words[i];
            }
        }
        if (line.length() > 0) out.add(r.fit(line, size, font, maxW));
        return out;
    }

    // ---- HOURLY (4x2) --------------------------------------------------------------------------------------------

    private static void hourly(R r, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.04f, 10, 16);
        Weather.Now n = wx.now(d.nowSec);
        int hi = wx.hourIndex(d.nowSec);

        float hs = h >= 150 ? 15 : 13.5f;
        String temp = u.t(n.temp);
        float tsz = h >= 150 ? 30 : 24;
        float tw = r.tw(temp, tsz, Gfx.FONT_BOLD);
        r.text(temp, w - pad, pad + tsz * 0.78f, tsz, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
        r.text(r.fit(d.place.shortName(), hs, Gfx.FONT_BOLD, w - 2 * pad - tw - 10), pad, pad + hs, hs, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        float sub = 11;
        float subBase = pad + hs + sub + 3;
        if (h >= 100) {
            r.text(r.fit(Wmo.label(n.code), sub, Gfx.FONT_REGULAR, w - 2 * pad - tw - 10), pad, subBase, sub, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        }

        int cols = (int) clamp((float) Math.floor((w - 2 * pad) / 44f), 2, 8);
        cols = Math.min(cols, wx.hTime.length - hi);
        if (cols < 1) return;
        float cw = (w - 2 * pad) / cols;
        float top = h >= 100 ? subBase + 7 : pad + tsz;
        float cell = 1.3f;
        float timeY = top + 5;
        float iconY = timeY + r.pixH(cell) + 4;
        float room = h - iconY - pad - 30;
        float isz = clamp(Math.min(room, cw - 12), 16, 32);
        float real = r.iconSize(isz);
        float tempBase = iconY + real + 15;

        r.rect(pad + 1, top - 1, pad + cw - 1, h - pad + 2, 12, TILE);
        for (int k = 0; k < cols; k++) {
            int j = hi + k;
            float cx = pad + cw * (k + 0.5f);
            String tag = k == 0 ? "NOW" : Fmt.hourTag(wx.hTime[j], wx.utcOffset, u.h24);
            float c = cell;
            while (c > 1 && r.pixW(tag, c) > cw - 3) c -= 0.2f;
            r.pix(tag, cx, timeY, c, Gfx.CENTER, k == 0 ? WHITE : T2);
            r.icon(Wmo.icon(wx.hCode[j], wx.hDay[j]), cx - real / 2, iconY, isz);
            r.text(u.t(wx.hTemp[j]), cx, tempBase, 14, Gfx.FONT_BOLD, Gfx.CENTER, WHITE);
            double pop = wx.hPop[j];
            float popY = tempBase + 5;
            if (pop >= 20 && popY + r.pixH(cell) <= h - pad + 3) {
                r.pix(Math.round(pop) + "%", cx, popY, cell, Gfx.CENTER, POP);
            }
        }
    }

    // ---- FORECAST (4x3) ------------------------------------------------------------------------------------------

    private static final String[] DAY_NAMES = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private static void forecast(R r, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.05f, 12, 18);
        int di = wx.dayIndex(d.nowSec);
        r.text(r.fit(d.place.shortName(), 14, Gfx.FONT_BOLD, w * 0.55f), pad, pad + 13, 14, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        boolean narrow = w < 200;
        if (!narrow) r.pix("FORECAST", w - pad, pad + 3, 1.3f, Gfx.RIGHT, T3);

        float top = pad + 24;
        float avail = h - top - pad + 4;
        int rows = (int) clamp((float) Math.floor(avail / (narrow ? 30f : 25f)), 1, 7);
        rows = Math.min(rows, wx.dTime.length - di);
        float rowH = avail / rows;

        double gmin = 1e9, gmax = -1e9;
        for (int i = 0; i < rows; i++) {
            gmin = Math.min(gmin, wx.dMin[di + i]);
            gmax = Math.max(gmax, wx.dMax[di + i]);
        }
        if (gmax - gmin < 1) gmax = gmin + 1;

        float barW = clamp(w * 0.3f, 56, 130);
        float barRight = w - pad - 30;
        float barLeft = barRight - barW;
        boolean showPop = w >= 290;
        Weather.Now n = wx.now(d.nowSec);

        for (int i = 0; i < rows; i++) {
            int j = di + i;
            float y0 = top + i * rowH;
            float cy = y0 + rowH / 2;
            if (i > 0) r.rect(pad, y0 - 0.5f, w - pad, y0 + 0.5f, 0.5f, 0x14FFFFFF);
            String name = i == 0 ? "Today" : DAY_NAMES[Fmt.dow(wx.dTime[j] + 43200, wx.utcOffset)];
            if (narrow) {
                r.text(name, pad, cy + 4.5f, 12, i == 0 ? Gfx.FONT_BOLD : Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
                r.icon(Wmo.icon(wx.dCode[j], true), pad + 38, cy - r.iconSize(18) / 2, 18);
                r.text(u.t(wx.dMax[j]), w - pad, cy - 0.5f, 13, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
                r.text(u.t(wx.dMin[j]), w - pad, cy + 11, 10.5f, Gfx.FONT_REGULAR, Gfx.RIGHT, T2);
                continue;
            }
            r.text(name, pad, cy + 4.5f, 13.5f, i == 0 ? Gfx.FONT_BOLD : Gfx.FONT_MEDIUM, Gfx.LEFT, WHITE);
            float isz = clamp(rowH - 5, 16, 24);
            float ix = pad + 50;
            r.icon(Wmo.icon(wx.dCode[j], true), ix, cy - r.iconSize(isz) / 2, isz);
            if (showPop && wx.dPop[j] >= 20) {
                r.pix(Math.round(wx.dPop[j]) + "%", ix + r.iconSize(isz) + 6, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.LEFT, POP);
            }
            r.text(u.t(wx.dMin[j]), barLeft - 7, cy + 4.5f, 13, Gfx.FONT_REGULAR, Gfx.RIGHT, T2);
            r.text(u.t(wx.dMax[j]), barRight + 7, cy + 4.5f, 13.5f, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);

            // smooth range pill: a faint track with the day's min..max filled in cold-to-warm
            float th = 5f;
            r.rect(barLeft, cy - th / 2, barRight, cy + th / 2, th / 2, TILE);
            float x0 = barLeft + barW * (float) clamp((float) ((wx.dMin[j] - gmin) / (gmax - gmin)), 0, 1);
            float x1 = barLeft + barW * (float) clamp((float) ((wx.dMax[j] - gmin) / (gmax - gmin)), 0, 1);
            if (x1 - x0 < th) x1 = x0 + th;
            int steps = Math.max(1, (int) ((x1 - x0) / 2));
            for (int q = 0; q < steps; q++) {
                float f0 = (float) q / steps;
                float xa = x0 + (x1 - x0) * f0, xb = x0 + (x1 - x0) * (q + 1) / steps + 0.4f;
                int col = mix(0xFF7DB8FF, 0xFFFFA726, (float) ((wx.dMin[j] + (wx.dMax[j] - wx.dMin[j]) * f0 - gmin) / (gmax - gmin)));
                if (q == 0) r.rect(xa, cy - th / 2, xa + th, cy + th / 2, th / 2, col);
                else if (q == steps - 1) r.rect(xb - th, cy - th / 2, xb, cy + th / 2, th / 2, col);
                if (q > 0 && q < steps - 1 || steps == 1) r.rect(xa + (q == 0 ? th / 2 : 0), cy - th / 2, Math.min(xb, x1 - th / 2), cy + th / 2, 0, col);
            }
            if (i == 0) {
                float nx = barLeft + barW * clamp((float) ((n.temp - gmin) / (gmax - gmin)), 0, 1);
                r.g.fillCircle(nx * r.u, cy * r.u, 3.6f * r.u, WHITE);
            }
        }
    }

    private static int mix(int a, int b, float f) {
        f = clamp(f, 0, 1);
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    // ---- DETAILS (4x2) -------------------------------------------------------------------------------------------

    private static void details(R r, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        Weather.Now n = wx.now(d.nowSec);
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.04f, 10, 14);
        float gap = 6;

        String[][] t = new String[9][];
        int[] subCol = new int[9];
        for (int i = 0; i < 9; i++) subCol[i] = T2;
        t[0] = new String[]{"FEELS", String.valueOf(u.temp(n.feels)) + "°", "air " + u.t(n.temp)};
        double dew = dewPoint(n.temp, n.humidity);
        t[1] = new String[]{"HUMID", Math.round(n.humidity) + "%", "dew " + u.t(dew)};
        t[2] = new String[]{"WIND", Double.isNaN(n.wind) ? "--" : String.valueOf(u.speed(n.wind)), u.speedUnit() + (Double.isNaN(n.windDir) ? "" : " " + Fmt.compass(n.windDir))};
        t[3] = new String[]{"UV", Double.isNaN(n.uv) ? "--" : String.valueOf(Math.round(n.uv)), Weather.uvLabel(n.uv)};
        t[4] = new String[]{"AIR", wx.aqi < 0 ? "--" : String.valueOf(wx.aqi), Weather.aqiLabel(wx.aqi)};
        subCol[4] = wx.aqi < 0 ? T2 : Weather.aqiColor(wx.aqi);
        t[5] = new String[]{"PRESS", u.pressure(n.pressure), u.pressureUnit()};
        t[6] = new String[]{"VISIB", Double.isNaN(n.visibility) ? "--" : u.distance(n.visibility), "visibility"};
        t[7] = new String[]{"CLOUD", Double.isNaN(n.cloud) ? "--" : Math.round(n.cloud) + "%", "cover"};
        int hi = wx.hourIndex(d.nowSec);
        double maxPop = 0;
        for (int j = hi; j < Math.min(hi + 24, wx.hTime.length); j++) maxPop = Math.max(maxPop, wx.hPop[j]);
        t[8] = new String[]{"RAIN", Math.round(maxPop) + "%", "next 24h"};

        int cols = w >= 200 ? 3 : 2;
        int maxRows = cols == 3 ? 3 : 4;
        int rows = (int) clamp(Math.round((h - 2 * pad + gap) / (46f + gap)), 1, maxRows);
        float tw = (w - 2 * pad - (cols - 1) * gap) / cols;
        float th = (h - 2 * pad - (rows - 1) * gap) / rows;
        for (int i = 0; i < Math.min(9, rows * cols); i++) {
            int col = i % cols, row = i / cols;
            float x = pad + col * (tw + gap), y = pad + row * (th + gap);
            r.rect(x, y, x + tw, y + th, 13, TILE);
            float ls = 11f;
            while (ls > 8.5f && r.tw(t[i][0], ls, Gfx.FONT_MEDIUM) > tw - 14) ls -= 0.5f;
            r.text(r.fit(t[i][0], ls, Gfx.FONT_MEDIUM, tw - 12), x + 8, y + 6 + ls * 0.8f, ls, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
            boolean showSub = tw >= 56;
            float vs = clamp(th * 0.36f, 13, 21);
            if (showSub && th < 48) vs = Math.min(vs, 14);
            String raw = showSub ? t[i][1] : t[i][1].replace(" ", "");
            while (vs > 11 && r.tw(raw, vs, Gfx.FONT_BOLD) > tw - 12) vs -= 0.5f;
            String val = r.fit(raw, vs, Gfx.FONT_BOLD, tw - 12);
            r.text(val, x + 8, y + th - (showSub ? (th < 48 ? 14.5f : 16) : 8), vs, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            if (showSub) r.text(r.fit(t[i][2], 10.5f, Gfx.FONT_REGULAR, tw - 12), x + 8, y + th - 5, 10.5f, Gfx.FONT_REGULAR, Gfx.LEFT, subCol[i]);
        }
    }

    private static double dewPoint(double tC, double rh) {
        if (Double.isNaN(tC) || Double.isNaN(rh) || rh <= 0) return Double.NaN;
        double a = 17.62, b = 243.12;
        double g = Math.log(rh / 100.0) + a * tC / (b + tC);
        return b * g / (a - g);
    }

    // ---- LOCATION (2x2 / 4x2) ------------------------------------------------------------------------------------

    private static void location(R r, Data d) {
        Place p = d.place;
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.09f, 10, 16);
        boolean wide = w >= 220;
        float psz = clamp(Math.min(h * 0.3f, 40), 22, 40);
        float real = r.icon(Icon.PIN, pad - 2, pad - 2, psz);

        float acc = p.accuracyM;
        int bars = Double.isNaN(acc) ? 0 : acc <= 10 ? 4 : acc <= 30 ? 3 : acc <= 100 ? 2 : 1;
        float bx = w - pad - 4 * 6 + 2;
        for (int i = 0; i < 4; i++) {
            float bh = 4 + i * 3;
            r.rect(bx + i * 6, pad + 2 + (13 - bh), bx + i * 6 + 4, pad + 15, 1f, i < bars ? (bars >= 3 ? 0xFF5BE584 : 0xFFFFB020) : 0x30FFFFFF);
        }
        if (!Double.isNaN(acc)) {
            String a = "±" + Math.round(acc) + "M";
            r.pix(a, w - pad + 2, pad + 20, 1.4f, Gfx.RIGHT, T2);
        }

        float nameSize = wide ? 18 : 15;
        float cellC = 1.3f;
        float coordH = r.pixH(cellC);
        float maxName = w - 2 * pad;
        if (wide) {
            float left = pad;
            float split = w * 0.5f;
            r.text(r.fit(p.shortName(), nameSize, Gfx.FONT_BOLD, split - left - 8), left, h * 0.58f, nameSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            String sub = p.subtitle().length() > 0 ? p.subtitle() + ", " + p.country : p.country;
            r.text(r.fit(sub, 11.5f, Gfx.FONT_REGULAR, split - left - 8), left, h * 0.58f + 17, 11.5f, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
            float cx = w - pad;
            float y = h - pad - 3 * (coordH + 5);
            r.pix("LAT " + Fmt.coord(p.lat, true), cx, y, cellC, Gfx.RIGHT, WHITE);
            r.pix("LON " + Fmt.coord(p.lon, false), cx, y + coordH + 5, cellC, Gfx.RIGHT, WHITE);
            String alt = Double.isNaN(p.altitudeM) ? "ALT --" : "ALT " + Math.round(p.altitudeM) + " M";
            r.pix(alt, cx, y + 2 * (coordH + 5), cellC, Gfx.RIGHT, T2);
            r.pix("Updated " + d.units.time(p.fixTimeMs / 1000, d.weather.utcOffset, true), pad, h - pad - coordH, cellC, Gfx.LEFT, T3);
        } else {
            boolean tall = h >= 150;
            float y = h - pad - (tall ? 5 : 2) * coordH - (tall ? 30 : 4);
            r.text(r.fit(p.shortName(), nameSize, Gfx.FONT_BOLD, maxName), pad, y - 22, nameSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            String sub = p.neighborhood.length() > 0 ? p.neighborhood : (p.region.length() > 0 ? p.region : p.country);
            r.text(r.fit(sub, 10.5f, Gfx.FONT_REGULAR, maxName), pad, y - 8, 10.5f, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
            r.pix(Fmt.coord(p.lat, true), pad, y, cellC, Gfx.LEFT, WHITE);
            r.pix(Fmt.coord(p.lon, false), pad, y + coordH + 4, cellC, Gfx.LEFT, WHITE);
            if (tall) {
                float yy = y + 2 * (coordH + 4) + 6;
                r.pix(Double.isNaN(p.altitudeM) ? "ALT --" : "ALT " + Math.round(p.altitudeM) + " M", pad, yy, cellC, Gfx.LEFT, T2);
                r.pix(Float.isNaN(p.accuracyM) ? "ACC --" : "ACC \u00b1" + Math.round(p.accuracyM) + " M", pad, yy + coordH + 4, cellC, Gfx.LEFT, T2);
                r.pix(d.units.time(p.fixTimeMs / 1000, d.weather.utcOffset, true), pad, yy + 2 * (coordH + 4), cellC, Gfx.LEFT, T3);
            }
        }
    }

    // ---- SUN (4x2) -----------------------------------------------------------------------------------------------

    private static void sun(R r, Data d) {
        Weather wx = d.weather;
        Units u = d.units;
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.05f, 12, 18);
        int di = wx.dayIndex(d.nowSec);
        long sr = wx.dSunrise[di], ss = wx.dSunset[di];
        long now = d.nowSec;
        boolean day = now >= sr && now <= ss;

        float cell = 1.3f;
        float blockTop = h - pad - r.pixH(cell) - (w < 200 ? 16 : 18);
        float horizonY = blockTop - 10;
        float arcH = clamp(horizonY - pad - (w < 200 ? 26 : 10), 14, w * (w < 200 ? 0.45f : 0.2f));
        float arcL = pad + (w < 200 ? 4 : 10), arcR = w - pad - (w < 200 ? 4 : 10);

        r.rect(pad, horizonY, w - pad, horizonY + 1.5f, 0.75f, 0x30FFFFFF);

        float f = ss > sr ? clamp((float) (now - sr) / (float) (ss - sr), 0, 1) : 0;
        int dots = Math.max(8, (int) ((arcR - arcL) / 2.5f));
        float px = arcL, py = horizonY;
        for (int i = 1; i <= dots; i++) {
            float t = (float) i / dots;
            float x = arcL + (arcR - arcL) * t;
            float y = horizonY - (float) Math.sin(Math.PI * t) * arcH;
            boolean past = day && t <= f;
            r.g.line(px * r.u, py * r.u, x * r.u, y * r.u, 2.6f * r.u, past ? SUNY : 0x40FFFFFF);
            px = x;
            py = y;
        }
        float isz = 24;
        float real = r.iconSize(isz);
        if (day) {
            float x = arcL + (arcR - arcL) * f;
            float y = horizonY - (float) Math.sin(Math.PI * f) * arcH;
            r.icon(Icon.SUN, x - real / 2, y - real / 2, isz);
        } else {
            r.icon(Icon.MOON, w / 2 - real / 2, horizonY - arcH * 0.7f - real / 2, isz);
        }

        if (w >= 200) r.text(r.fit(d.place.shortName(), 12.5f, Gfx.FONT_MEDIUM, w * (w < 200 ? 0.8f : 0.35f)), pad, pad + 11, 12.5f, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
        boolean narrow = w < 200;
        float ts = narrow ? 11.5f : 14;
        String srT = u.time(sr, wx.utcOffset, true), ssT = u.time(ss, wx.utcOffset, true);
        if (narrow) {
            srT = srT.replace(" AM", "a").replace(" PM", "p");
            ssT = ssT.replace(" AM", "a").replace(" PM", "p");
        }
        r.pix(narrow ? "RISE" : "SUNRISE", pad, blockTop, cell, Gfx.LEFT, T3);
        r.text(srT, pad, blockTop + r.pixH(cell) + ts, ts, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        r.pix(narrow ? "SET" : "SUNSET", w - pad, blockTop, cell, Gfx.RIGHT, T3);
        r.text(ssT, w - pad, blockTop + r.pixH(cell) + ts, ts, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);

        String label, value;
        if (day) {
            label = "DAYLIGHT LEFT";
            value = Fmt.duration(ss - now);
        } else {
            long next = now < sr ? sr : (di + 1 < wx.dSunrise.length ? wx.dSunrise[di + 1] : sr + 86400);
            label = "SUNRISE IN";
            value = Fmt.duration(next - now);
        }
        if (narrow) {
            r.pix(value.toUpperCase().replace(" ", ""), w / 2, pad + 1, cell * 1.15f, Gfx.CENTER, T2);
        } else {
            r.pix(label, w / 2, blockTop, cell, Gfx.CENTER, T3);
            r.text(value, w / 2, blockTop + r.pixH(cell) + 14, 14, Gfx.FONT_MEDIUM, Gfx.CENTER, WHITE);
        }
    }


    // ---- ICON (1x1, 2x1, 2x2): no card, just the weather and the temperature ------------------------------------

    private static Icon iconFor(Weather.Now n) {
        Icon base = Wmo.icon(n.code, n.isDay);
        boolean calm = base == Icon.SUN || base == Icon.MOON || base == Icon.PARTLY_DAY
                || base == Icon.PARTLY_NIGHT || base == Icon.CLOUD;
        double wind = Double.isNaN(n.wind) ? 0 : n.wind;
        double gust = Double.isNaN(n.gust) ? 0 : n.gust;
        return calm && (wind >= 35 || gust >= 55) ? Icon.WIND : base;
    }

    private static void iconInk(R r, Icon icon, float x, float y, float size) {
        if (r.dark) r.icon(icon, x, y, size);
        else r.iconOutlined(icon, x, y, size, 0xCC0E1630);
    }

    private static void shadowText(R r, String s, float x, float base, float size, int font, int align, int ink) {
        if (!r.dark) {
            // a whisper of light behind dark ink keeps it readable over busy wallpapers
            r.text(s, x + 0.6f, base + 0.6f, size, font, align, 0x40FFFFFF);
        } else {
            r.text(s, x + 0.8f, base + 0.8f, size, font, align, 0x66000000);
        }
        r.text(s, x, base, size, font, align, ink);
    }

    private static void iconWidget(R r, Data d) {
        float w = r.w, h = r.h;
        int ink = r.dark ? 0xFFFFFFFF : 0xFF0E1630;
        int ink2 = r.dark ? 0xCCFFFFFF : 0xB30E1630;
        if (d == null || !d.ready()) {
            float sz = clamp(Math.min(w, h) * 0.6f, 16, 48);
            float real = r.iconSize(sz);
            r.icon(Icon.PIN, (w - real) / 2, (h - real) / 2, sz);
            return;
        }
        Weather.Now n = d.weather.now(d.nowSec);
        Icon icon = iconFor(n);
        Units u = d.units;
        String temp = u.t(n.temp);
        boolean wide = w >= h * 1.6f;
        if (wide) {
            float isz = clamp(h * 0.8f, 18, 70);
            float real = r.iconSize(isz);
            float ts = clamp(h * 0.52f, 14, 40);
            String text = u.tUnit(n.temp);
            while (ts > 12 && real + 8 + r.tw(text, ts, Gfx.FONT_BOLD) > w - 4) ts -= 1;
            float tw = r.tw(text, ts, Gfx.FONT_BOLD);
            float x0 = (w - (real + 8 + tw)) / 2;
            iconInk(r, icon, x0, (h - real) / 2, isz);
            shadowText(r, text, x0 + real + 8, h / 2 + ts * 0.36f, ts, Gfx.FONT_BOLD, Gfx.LEFT, ink);
        } else {
            boolean label = h >= 90;
            float ts = clamp(h * (label ? 0.24f : 0.3f), 11, 30);
            float lsz = 11.5f;
            float isz = clamp(Math.min(w * 0.62f, h * (label ? 0.5f : 0.56f)), 16, 80);
            float real = r.iconSize(isz);
            float textH = ts * 0.78f + (label ? lsz + 5 : 0);
            float total = real + 3 + textH;
            float y0 = (h - total) / 2;
            iconInk(r, icon, (w - real) / 2, y0, isz);
            float base = y0 + real + 3 + ts * 0.78f;
            String text = w >= 80 ? u.tUnit(n.temp) : temp;
            while (ts > 10 && r.tw(text, ts, Gfx.FONT_BOLD) > w - 4) ts -= 1;
            shadowText(r, text, w / 2, base, ts, Gfx.FONT_BOLD, Gfx.CENTER, ink);
            if (label) {
                String l = icon == Icon.WIND ? "windy" : Wmo.label(n.code);
                r.text(r.fit(l, lsz, Gfx.FONT_MEDIUM, w - 8), w / 2, base + lsz + 4, lsz, Gfx.FONT_MEDIUM, Gfx.CENTER, ink2);
            }
        }
    }

    // ---- HEADS-UP (4x1) ------------------------------------------------------------------------------------------

    private static void headsUp(R r, Data d) {
        float w = r.w, h = r.h;
        if (w < 200 && h >= 80) { headsUpTall(r, d); return; }
        float pad = clamp(h * 0.22f, 10, 16);
        Insights.Insight in = Insights.headline(d.weather, d.units, d.nowSec);
        int stripe = in.sev == Insights.WARNING ? 0xFFFF5A5F : in.sev == Insights.NOTICE ? 0xFFFFB020 : 0xFF4DD0E1;
        r.rect(7, h * 0.24f, 10, h * 0.76f, 1.5f, stripe);

        float isz = clamp(h - 2 * pad + 4, 22, 40);
        float real = r.iconSize(isz);
        float ix = 18;
        r.icon(in.icon, ix, (h - real) / 2, isz);

        Weather.Now n = d.weather.now(d.nowSec);
        String temp = d.units.t(n.temp);
        boolean compact = w < 200;
        float tsz = clamp(h * 0.42f, 18, 28);
        float tw = compact ? 0 : r.tw(temp, tsz, Gfx.FONT_BOLD);
        float tx = ix + real + 10;
        float maxW = w - tx - pad - tw - 10;
        boolean two = h >= 46;
        float titleSize = h >= 70 ? 15 : 13.5f;
        float detailSize = 11;
        if (two) {
            float block = titleSize + detailSize + 4;
            float base = (h - block) / 2 + titleSize - 1;
            r.text(pick(r, titleSize, Gfx.FONT_BOLD, maxW, in.title, in.shortTitle, tiny(in.shortTitle)), tx, base, titleSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            r.text(pick(r, detailSize, Gfx.FONT_REGULAR, maxW, in.detail, in.shortDetail), tx, base + detailSize + 4, detailSize, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        } else {
            r.text(pick(r, titleSize, Gfx.FONT_BOLD, maxW, in.title, in.shortTitle, tiny(in.shortTitle)), tx, h / 2 + 5, titleSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        }
        if (!compact) r.text(temp, w - pad, h / 2 + tsz * 0.36f, tsz, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
    }

    private static void headsUpTall(R r, Data d) {
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.1f, 10, 16);
        Insights.Insight in = Insights.headline(d.weather, d.units, d.nowSec);
        int stripe = in.sev == Insights.WARNING ? 0xFFFF5A5F : in.sev == Insights.NOTICE ? 0xFFFFB020 : 0xFF4DD0E1;
        float maxW = w - 2 * pad;
        r.rect(pad, pad, pad + 24, pad + 3, 1.5f, stripe);

        // where you are: wraps to two lines when the name is long
        float y = pad + 16;
        for (String line : wrap(r, d.place.title(), 11.5f, Gfx.FONT_MEDIUM, maxW, 2)) {
            r.text(line, pad, y, 11.5f, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
            y += 14;
        }
        y += 2;

        float isz = clamp(w * 0.26f, 22, 34);
        float real = r.iconSize(isz);
        r.icon(in.icon, pad - 2, y, isz);
        Weather.Now n = d.weather.now(d.nowSec);
        r.text(d.units.t(n.temp), w - pad, y + real * 0.72f, 22, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
        y += real + 16;

        int titleLines = (int) clamp((float) Math.floor((h - pad - y + 4) / 17f), 1, 3);
        for (String line : wrapFit(r, in.title, in.shortTitle, 14, Gfx.FONT_BOLD, maxW, titleLines)) {
            r.text(line, pad, y, 14, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            y += 17;
        }
        y += 1;
        int detailLines = (int) Math.floor((h - pad - y + 6) / 14f);
        if (detailLines >= 1) {
            for (String line : wrapFit(r, in.detail, in.shortDetail, 11, Gfx.FONT_REGULAR, maxW, Math.min(detailLines, 3))) {
                r.text(line, pad, y, 11, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
                y += 14;
            }
        }
    }

    /** Alert rows used on the in-app alerts list (same look as the heads-up widget, one per insight). */
    public static void renderInsight(Gfx g, Insights.Insight in, float wDp, float hDp, float u, boolean dark, Data d) {
        R r = new R(g, u, wDp, hDp, dark, d != null && d.transparentDark);
        r.fill = fillFor(d, dark);
        r.shade = r.clear || r.fill < 0.95f;
        applySkin(r, d);
        r.t = d == null ? -1 : d.anim;
        base(r, mood(d));
        fx(r, mood(d), d);
        float h = hDp, w = wDp;
        int stripe = in.sev == Insights.WARNING ? 0xFFFF5A5F : in.sev == Insights.NOTICE ? 0xFFFFB020 : 0xFF4DD0E1;
        r.rect(7, h * 0.22f, 10, h * 0.78f, 1.5f, stripe);
        float isz = 30;
        float real = r.iconSize(isz);
        r.icon(in.icon, 18, (h - real) / 2, isz);
        float tx = 18 + real + 10;
        float maxW = w - tx - 14;
        r.text(pick(r, 14, Gfx.FONT_BOLD, maxW, in.title, in.shortTitle, tiny(in.shortTitle)), tx, h / 2 - 1, 14, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        r.text(pick(r, 11, Gfx.FONT_REGULAR, maxW, in.detail, in.shortDetail), tx, h / 2 + 15, 11, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
    }
}
