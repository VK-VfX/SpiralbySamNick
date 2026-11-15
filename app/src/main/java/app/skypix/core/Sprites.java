package app.skypix.core;

import java.util.EnumMap;

/** 16x16 pixel-art icons, built procedurally so they stay crisp at any integer scale. */
public final class Sprites {
    private Sprites() {}

    public static final int N = 16;

    private static final int SUN_Y = 0xFFFFD23F, SUN_O = 0xFFFF9F1C, SUN_H = 0xFFFFF0A0;
    private static final int MOON_L = 0xFFF1F4FF, MOON_S = 0xFFB9C4E6, STAR = 0xFFFFF3B0;
    private static final int CLOUD_T = 0xFFF5F8FF, CLOUD_B = 0xFFC3D0EC;
    private static final int GREY_T = 0xFFBCC7DE, GREY_B = 0xFF8D9CBF;
    private static final int STORM_T = 0xFF9AA3C4, STORM_B = 0xFF646F96;
    private static final int RAIN_B = 0xFF4DA8FF, RAIN_L = 0xFF9AD0FF, BOLT = 0xFFFFD23F, SNOW_W = 0xFFFFFFFF;
    private static final int FOG_A = 0xFFD2DCF2, FOG_B = 0xFF9FB0D3;

    private static final EnumMap<Icon, int[]> CACHE = new EnumMap<Icon, int[]>(Icon.class);

    public static synchronized int[] get(Icon icon) {
        int[] px = CACHE.get(icon);
        if (px == null) {
            px = build(icon);
            CACHE.put(icon, px);
        }
        return px;
    }

    /** Same silhouette in a single colour, used for the notification glyph. */
    public static int[] mono(Icon icon, int argb) {
        int[] src = get(icon);
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (src[i] >>> 24) > 100 ? argb : 0;
        return out;
    }

    private static int[] build(Icon icon) {
        int[] p = new int[N * N];
        switch (icon) {
            case SUN: sun(p); break;
            case MOON: moon(p, 7.5f, 8.5f, 5.4f, true); break;
            case PARTLY_DAY: partlyDay(p); break;
            case PARTLY_NIGHT: partlyNight(p); break;
            case CLOUD: cloud(p, 8f, 9f, 1.05f, CLOUD_T, CLOUD_B); break;
            case FOG: fog(p); break;
            case DRIZZLE: drizzle(p); break;
            case RAIN: rain(p); break;
            case STORM: storm(p); break;
            case SNOW: snow(p); break;
            case PIN: pin(p); break;
            case ALERT: alert(p); break;
            default: break;
        }
        return p;
    }

    private static void set(int[] p, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < N && y < N) p[y * N + x] = c;
    }

    private static void disc(int[] p, float cx, float cy, float r, int c) {
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                if (dx * dx + dy * dy <= r * r) p[y * N + x] = c;
            }
        }
    }

    private static void pts(int[] p, int c, int... xy) {
        for (int i = 0; i + 1 < xy.length; i += 2) set(p, xy[i], xy[i + 1], c);
    }

    private static void sun(int[] p) {
        pts(p, SUN_Y, 7, 1, 8, 1, 7, 2, 8, 2, 7, 13, 8, 13, 7, 14, 8, 14, 1, 7, 1, 8, 2, 7, 2, 8,
                13, 7, 13, 8, 14, 7, 14, 8, 3, 3, 12, 3, 3, 12, 12, 12, 2, 2, 13, 2, 2, 13, 13, 13);
        sunDisc(p, 8f, 8f, 3.9f);
    }

    private static void sunDisc(int[] p, float cx, float cy, float r) {
        disc(p, cx, cy, r, SUN_Y);
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                if (p[y * N + x] != SUN_Y) continue;
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                if (dx + dy > r * 0.55f) p[y * N + x] = SUN_O;
                else if (dx + dy < -r * 0.9f) p[y * N + x] = SUN_H;
            }
        }
    }

    private static void moon(int[] p, float cx, float cy, float r, boolean stars) {
        float bx = cx + 3.1f, by = cy - 2.1f, br = r * 0.84f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                if (dx * dx + dy * dy > r * r) continue;
                float ex = x + 0.5f - bx, ey = y + 0.5f - by;
                float d = (float) Math.sqrt(ex * ex + ey * ey);
                if (d <= br) continue;
                p[y * N + x] = d < br + 1.5f ? MOON_S : MOON_L;
            }
        }
        if (stars) {
            pts(p, STAR, 12, 2, 11, 3, 12, 3, 13, 3, 12, 4, 13, 9, 14, 12);
        }
    }

    private static void cloud(int[] p, float cx, float cy, float s, int top, int bottom) {
        float[][] circles = {{-3.6f, 1.0f, 3.1f}, {0.2f, -1.7f, 4.2f}, {3.9f, 0.5f, 3.0f}};
        float bottomY = cy + 3.9f * s;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                boolean in = false;
                for (float[] c : circles) {
                    float dx = px - (cx + c[0] * s), dy = py - (cy + c[1] * s);
                    if (dx * dx + dy * dy <= c[2] * s * c[2] * s) { in = true; break; }
                }
                if (!in && py >= cy + 0.4f * s && py <= bottomY && Math.abs(px - cx) <= 6.4f * s) {
                    float dx = Math.abs(px - cx) - 4.6f * s;
                    in = dx <= 0 || (dx * dx + (py - (cy + 2.2f * s)) * (py - (cy + 2.2f * s))) <= (1.8f * s) * (1.8f * s);
                }
                if (in) p[y * N + x] = py > bottomY - 1.9f ? bottom : top;
            }
        }
    }

    private static void partlyDay(int[] p) {
        pts(p, SUN_Y, 5, 0, 6, 0, 5, 1, 6, 1, 0, 5, 0, 6, 1, 5, 1, 6, 1, 1, 2, 2, 10, 2, 10, 3, 9, 1);
        sunDisc(p, 6f, 6f, 3.1f);
        cloud(p, 9.4f, 10.2f, 0.92f, CLOUD_T, CLOUD_B);
    }

    private static void partlyNight(int[] p) {
        int[] m = new int[N * N];
        moon(m, 5.6f, 6.2f, 4.2f, false);
        for (int i = 0; i < m.length; i++) if (m[i] != 0) p[i] = m[i];
        pts(p, STAR, 11, 1, 10, 2, 11, 2, 12, 2, 11, 3);
        cloud(p, 9.4f, 10.2f, 0.92f, CLOUD_T, CLOUD_B);
    }

    private static void fog(int[] p) {
        cloud(p, 8f, 5.6f, 0.9f, CLOUD_T, CLOUD_B);
        for (int x = 1; x <= 12; x++) set(p, x, 11, FOG_A);
        for (int x = 4; x <= 14; x++) set(p, x, 13, FOG_B);
        for (int x = 2; x <= 9; x++) set(p, x, 15, FOG_A);
    }

    private static void drizzle(int[] p) {
        cloud(p, 8f, 6f, 1.0f, GREY_T, GREY_B);
        pts(p, RAIN_L, 4, 12, 8, 12, 12, 12, 6, 14, 10, 14);
    }

    private static void rain(int[] p) {
        cloud(p, 8f, 6f, 1.0f, GREY_T, GREY_B);
        pts(p, RAIN_B, 4, 12, 3, 13, 8, 12, 7, 13, 12, 12, 11, 13, 6, 14, 5, 15, 10, 14, 9, 15);
    }

    private static void storm(int[] p) {
        cloud(p, 8f, 5.6f, 1.0f, STORM_T, STORM_B);
        pts(p, BOLT, 9, 9, 10, 9, 8, 10, 9, 10, 7, 11, 8, 11, 9, 11, 10, 11, 8, 12, 9, 12, 7, 13, 8, 13, 7, 14);
    }

    private static void snow(int[] p) {
        cloud(p, 8f, 6f, 1.0f, GREY_T, GREY_B);
        pts(p, SNOW_W, 4, 11, 3, 12, 4, 12, 5, 12, 4, 13, 11, 11, 10, 12, 11, 12, 12, 12, 11, 13, 8, 14, 7, 15, 9, 15);
    }

    private static final String[] PIN = {
        "................",
        ".....RRRRRR.....",
        "....RRRRRRRR....",
        "...RRRRRRRRRR...",
        "...RRRRWWRRRRr..",
        "...RRRWWWWRRRr..",
        "...RRRWWWWRRRr..",
        "...RRRRWWRRRRr..",
        "....RRRRRRRRr...",
        "....RRRRRRRRr...",
        ".....RRRRRRr....",
        "......RRRRr.....",
        "......RRRr......",
        ".......Rr.......",
        ".......r........",
        "................"};

    private static final String[] ALERT = {
        "................",
        ".......YY.......",
        "......YYYY......",
        "......YYYY......",
        ".....YYYYYY.....",
        ".....YYKKYY.....",
        "....YYYKKYYY....",
        "....YYYKKYYY....",
        "...YYYYKKYYYY...",
        "...YYYYKKYYYY...",
        "..YYYYYYYYYYYY..",
        "..YYYYYKKYYYYY..",
        ".YYYYYYYYYYYYYY.",
        ".YYYYYYYYYYYYYY.",
        "................",
        "................"};

    private static void pin(int[] p) {
        art(p, PIN, 'R', 0xFFFF5A5F, 'r', 0xFFC93A45, 'W', 0xFFFFFFFF);
    }

    private static void alert(int[] p) {
        art(p, ALERT, 'Y', 0xFFFFC233, 'K', 0xFF3B2A00, 'x', 0);
    }

    private static void art(int[] p, String[] rows, Object... pal) {
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                char ch = rows[y].charAt(x);
                for (int i = 0; i < pal.length; i += 2) {
                    if (((Character) pal[i]).charValue() == ch) p[y * N + x] = (Integer) pal[i + 1];
                }
            }
        }
    }
}
