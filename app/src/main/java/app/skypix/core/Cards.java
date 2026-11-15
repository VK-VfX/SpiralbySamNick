package app.skypix.core;

/**
 * Draws every Skypix card/widget. Coordinates are in dp; {@code u} is pixels per dp.
 * Light system theme => deep, near-opaque cards. Dark system theme => translucent glass cards.
 */
public final class Cards {
    private Cards() {}

    public static final int NOW = 0, NOW_WIDE = 1, HOURLY = 2, FORECAST = 3, DETAILS = 4, LOCATION = 5, SUN = 6, HEADSUP = 7, ICON = 8;
    public static final int COUNT = 9;

    public static final class Data {
        public Weather weather;
        public Place place;
        public Units units = new Units(false, false, false);
        public long nowSec;
        /** Dark theme only: draw widgets with no fill (see-through) instead of AMOLED black. */
        public boolean transparentDark;
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
            default: return "Heads-up";
        }
    }

    // ---- palette -------------------------------------------------------------------------------------------------

    private static final int WHITE = 0xFFFFFFFF, T2 = 0xBFFFFFFF, T3 = 0x85FFFFFF, TILE = 0x1CFFFFFF;
    private static final int HI = 0xFFFFB86B, LO = 0xFF7CC4FF, POP = 0xFF6FC1FF, SUNY = 0xFFFFD23F;

    private static final class Mood {
        final int top, bottom, glow;
        Mood(int top, int bottom, int glow) { this.top = top; this.bottom = bottom; this.glow = glow; }
    }

    private static final Mood CLEAR_DAY = new Mood(0xFF1F55B8, 0xFF0C2B66, 0xFFFFC857);
    private static final Mood CLEAR_NIGHT = new Mood(0xFF1C2354, 0xFF090C26, 0xFF8EA2FF);
    private static final Mood CLOUDY = new Mood(0xFF3E506E, 0xFF1D283C, 0xFFB7C6E6);
    private static final Mood RAINY = new Mood(0xFF29415F, 0xFF111B2D, 0xFF4DA8FF);
    private static final Mood STORMY = new Mood(0xFF3B2C66, 0xFF140E2E, 0xFFFFD23F);
    private static final Mood SNOWY = new Mood(0xFF4A6383, 0xFF22324A, 0xFFFFFFFF);
    private static final Mood FOGGY = new Mood(0xFF4B5666, 0xFF252C37, 0xFFD2DCF2);

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
        float t = -1;

        R(Gfx g, float u, float w, float h, boolean dark, boolean clear) {
            this.g = g; this.u = u; this.w = w; this.h = h; this.dark = dark; this.clear = dark && clear;
        }

        void text(String s, float x, float base, float size, int font, int align, int color) {
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

        void pix(String s, float x, float y, float cell, int align, int color) {
            PixelFont.draw(g, s, x * u, y * u, cell * u, align, color);
        }

        float pixW(String s, float cell) {
            return PixelFont.drawWidth(s, cell * u) / u;
        }

        /** Pixel-font height in dp after snapping. */
        float pixH(float cell) {
            return 7 * Math.max(1, Math.round(cell * u)) / u;
        }

        /** Draws a sprite and returns the snapped size in dp. */
        float icon(Icon ic, float x, float y, float size) {
            int cell = Math.max(1, Math.round(size * u / Sprites.N));
            int bob = t < 0 ? 0 : Math.round((float) Math.sin(t * 4.2 + x * 0.07) * cell * 0.7f);
            g.sprite(Sprites.get(ic), Sprites.N, Sprites.N, Math.round(x * u), Math.round(y * u) + bob, cell);
            return cell * Sprites.N / u;
        }

        /** Sprite with a thin dark pixel outline so pale icons stay readable on light wallpapers. */
        float iconOutlined(Icon ic, float x, float y, float size, int outline) {
            int cell = Math.max(1, Math.round(size * u / Sprites.N));
            int bob = t < 0 ? 0 : Math.round((float) Math.sin(t * 4.2 + x * 0.07) * cell * 0.7f);
            int o = Math.max(1, Math.round(cell * 0.34f));
            int[] mono = Sprites.mono(ic, outline);
            int px = Math.round(x * u), py = Math.round(y * u) + bob;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) g.sprite(mono, Sprites.N, Sprites.N, px + dx * o, py + dy * o, cell);
                }
            }
            g.sprite(Sprites.get(ic), Sprites.N, Sprites.N, px, py, cell);
            return cell * Sprites.N / u;
        }

        float iconSize(float size) {
            return Math.max(1, Math.round(size * u / Sprites.N)) * Sprites.N / u;
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
        r.t = d == null ? -1 : d.anim;
        if (kind == ICON) {
            iconWidget(r, d);
            return;
        }
        Mood m = mood(d);
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
        if (d == null || !d.ready()) return "Skypix. Tap to set up.";
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
        r.g.clipRoundRect(0, 0, w * r.u, h * r.u, 22 * r.u);
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
        r.g.restoreClip();
    }

    // ---- shared chrome -------------------------------------------------------------------------------------------

    private static void base(R r, Mood m) {
        float rad = 22;
        if (r.clear) {
            // Transparent variant: no fill at all, just a hairline glass edge so the wallpaper shows through.
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, rad * r.u, 1f * r.u, 0x33FFFFFF);
            return;
        }
        // Dark mode is AMOLED: the card fades to pure black so it vanishes into a black wallpaper.
        int top = r.dark ? alpha(mix(m.top, 0xFF000000, 0.5f), 0x8C) : alpha(m.top, 0xF2);
        int bottom = r.dark ? 0xE6000000 : alpha(m.bottom, 0xF7);
        r.g.fillRoundRectGradient(0, 0, r.w * r.u, r.h * r.u, rad * r.u, top, bottom);
        r.g.clipRoundRect(0, 0, r.w * r.u, r.h * r.u, rad * r.u);
        r.g.glow(r.w * r.u * 0.9f, -r.h * r.u * 0.1f, Math.max(r.w, r.h) * r.u * 0.75f, alpha(m.glow, r.dark ? 0x26 : 0x55));
        r.g.restoreClip();
        if (r.dark) {
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, rad * r.u, 1f * r.u, 0x2EFFFFFF);
        } else {
            r.g.strokeRoundRect(0.5f * r.u, 0.5f * r.u, (r.w - 0.5f) * r.u, (r.h - 0.5f) * r.u, rad * r.u, 1f * r.u, 0x22FFFFFF);
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
            r.text(u.t(wx.hTemp[j]), w - pad, cy + 5, 14, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
            if (w >= 150 && wx.hPop[j] >= 20) {
                r.pix(Math.round(wx.hPop[j]) + "%", w - pad - 40, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.RIGHT, POP);
            }
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
            r.text(u.t(wx.hTemp[j]), w - pad - 3, cy + 5, 14, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
            if (w >= 150 && wx.hPop[j] >= 20) {
                r.pix(Math.round(wx.hPop[j]) + "%", w - pad - 48, cy - r.pixH(1.2f) / 2, 1.2f, Gfx.RIGHT, POP);
            }
        }
    }

    /** Greedy word wrap; the last allowed line is ellipsised. */
    private static java.util.List<String> wrap(R r, String s, float size, int font, float maxW, int maxLines) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String[] words = s.split(" ");
        String line = "";
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
            if (r.tw(tryLine, size, font) <= maxW || line.length() == 0) {
                line = tryLine;
            } else {
                out.add(line);
                line = words[i];
                if (out.size() == maxLines - 1) {
                    StringBuilder rest = new StringBuilder(line);
                    for (int k = i + 1; k < words.length; k++) rest.append(' ').append(words[k]);
                    line = rest.toString();
                    break;
                }
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

        int cols = (int) clamp((float) Math.floor((w - 2 * pad) / 44f), 4, 8);
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

            float pitch = 5f, seg = 3.5f;
            int count = (int) Math.floor(barW / pitch);
            float sx = barLeft + (barW - count * pitch + (pitch - seg)) / 2;
            for (int s = 0; s < count; s++) {
                double t = gmin + (gmax - gmin) * (s + 0.5) / count;
                double dt = (gmax - gmin) / count;
                boolean on = t + dt / 2 >= wx.dMin[j] && t - dt / 2 <= wx.dMax[j];
                int col = TILE;
                if (on) {
                    float f = (float) ((t - gmin) / (gmax - gmin));
                    col = mix(0xFF5DADE2, 0xFFFF9F1C, f);
                    if (i == 0 && Math.abs(t - n.temp) <= dt / 2 + 0.01) col = WHITE;
                }
                float x = sx + s * pitch;
                r.rect(x, cy - seg / 2 - 0.5f, x + seg, cy + seg / 2 + 0.5f, 1f, col);
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
            r.pix(t[i][0], x + 8, y + 6, 1.1f, Gfx.LEFT, T3);
            boolean showSub = tw >= 56;
            float vs = clamp(th * 0.36f, 13, 21);
            if (showSub && th < 48) vs = Math.min(vs, 14);
            String raw = showSub ? t[i][1] : t[i][1].replace(" ", "");
            while (vs > 11 && r.tw(raw, vs, Gfx.FONT_BOLD) > tw - 12) vs -= 0.5f;
            String val = r.fit(raw, vs, Gfx.FONT_BOLD, tw - 12);
            r.text(val, x + 8, y + th - (showSub ? (th < 48 ? 14.5f : 16) : 8), vs, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            if (showSub) r.text(r.fit(t[i][2], 9.5f, Gfx.FONT_REGULAR, tw - 12), x + 8, y + th - 5, 9.5f, Gfx.FONT_REGULAR, Gfx.LEFT, subCol[i]);
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
            r.pix("UPD " + d.units.time(p.fixTimeMs / 1000, d.weather.utcOffset, true), pad, h - pad - coordH, cellC, Gfx.LEFT, T3);
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
                r.pix("UPD " + d.units.time(p.fixTimeMs / 1000, d.weather.utcOffset, true), pad, yy + 2 * (coordH + 4), cellC, Gfx.LEFT, T3);
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

        for (float x = pad; x <= w - pad; x += 6) {
            r.rect(x, horizonY, x + 2, horizonY + 2, 0.5f, 0x38FFFFFF);
        }

        float f = ss > sr ? clamp((float) (now - sr) / (float) (ss - sr), 0, 1) : 0;
        int dots = (int) ((arcR - arcL) / 6.5f);
        for (int i = 0; i <= dots; i++) {
            float t = (float) i / dots;
            float x = arcL + (arcR - arcL) * t;
            float y = horizonY - (float) Math.sin(Math.PI * t) * arcH;
            boolean past = day && t <= f;
            r.rect(x - 1.2f, y - 1.2f, x + 1.2f, y + 1.2f, 0.5f, past ? SUNY : 0x4AFFFFFF);
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

        if (w >= 200 || h >= 120) r.text(r.fit(d.place.shortName(), 12.5f, Gfx.FONT_MEDIUM, w * (w < 200 ? 0.8f : 0.35f)), pad, pad + 11, 12.5f, Gfx.FONT_MEDIUM, Gfx.LEFT, T2);
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
            r.text(r.fit(in.title, titleSize, Gfx.FONT_BOLD, maxW), tx, base, titleSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            r.text(r.fit(in.detail, detailSize, Gfx.FONT_REGULAR, maxW), tx, base + detailSize + 4, detailSize, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
        } else {
            r.text(r.fit(in.title, titleSize, Gfx.FONT_BOLD, maxW), tx, h / 2 + 5, titleSize, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        }
        if (!compact) r.text(temp, w - pad, h / 2 + tsz * 0.36f, tsz, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
    }

    private static void headsUpTall(R r, Data d) {
        float w = r.w, h = r.h;
        float pad = clamp(w * 0.1f, 10, 16);
        Insights.Insight in = Insights.headline(d.weather, d.units, d.nowSec);
        int stripe = in.sev == Insights.WARNING ? 0xFFFF5A5F : in.sev == Insights.NOTICE ? 0xFFFFB020 : 0xFF4DD0E1;
        r.rect(pad, pad, pad + 24, pad + 3, 1.5f, stripe);
        float isz = clamp(w * 0.3f, 26, 40);
        r.icon(in.icon, pad - 2, pad + 10, isz);
        Weather.Now n = d.weather.now(d.nowSec);
        r.text(d.units.t(n.temp), w - pad, pad + 10 + isz * 0.62f, 22, Gfx.FONT_BOLD, Gfx.RIGHT, WHITE);
        float y = pad + 10 + r.iconSize(isz) + 18;
        float maxW = w - 2 * pad;
        int lines = h >= 150 ? 4 : 3;
        for (String line : wrap(r, in.title, 14, Gfx.FONT_BOLD, maxW, Math.min(lines, 3))) {
            r.text(line, pad, y, 14, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
            y += 17;
        }
        y += 3;
        for (String line : wrap(r, in.detail, 11, Gfx.FONT_REGULAR, maxW, lines)) {
            if (y > h - pad + 2) break;
            r.text(line, pad, y, 11, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
            y += 14;
        }
    }

    /** Alert rows used on the in-app alerts list (same look as the heads-up widget, one per insight). */
    public static void renderInsight(Gfx g, Insights.Insight in, float wDp, float hDp, float u, boolean dark, Data d) {
        R r = new R(g, u, wDp, hDp, dark, d != null && d.transparentDark);
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
        r.text(r.fit(in.title, 14, Gfx.FONT_BOLD, maxW), tx, h / 2 - 1, 14, Gfx.FONT_BOLD, Gfx.LEFT, WHITE);
        r.text(r.fit(in.detail, 11, Gfx.FONT_REGULAR, maxW), tx, h / 2 + 15, 11, Gfx.FONT_REGULAR, Gfx.LEFT, T2);
    }
}
