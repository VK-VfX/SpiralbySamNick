package app.skypix.android;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateFormat;

import app.skypix.core.Cards;
import app.skypix.core.Insights;
import app.skypix.core.Place;
import app.skypix.core.Warnings;
import app.skypix.core.Units;
import app.skypix.core.Weather;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Preferences plus the last-known weather/place cache. */
final class Store {
    private Store() {}

    /** True while the activity is on screen; background location is only usable then (unless granted). */
    static volatile boolean visible;

    static SharedPreferences prefs(Context c) { return sp(c); }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("skypix", Context.MODE_PRIVATE);
    }

    // ---- settings ----
    static String tempPref(Context c) { return sp(c).getString("temp", "auto"); }
    static void setTempPref(Context c, String v) { sp(c).edit().putString("temp", v).apply(); }

    static boolean alerts(Context c) { return sp(c).getBoolean("alerts", true); }
    static void setAlerts(Context c, boolean v) { sp(c).edit().putBoolean("alerts", v).apply(); }

    static boolean hourly(Context c) { return sp(c).getBoolean("hourly", true); }
    static void setHourly(Context c, boolean v) { sp(c).edit().putBoolean("hourly", v).apply(); }

    /** Morning briefing. */
    static boolean morning(Context c) { return sp(c).getBoolean("morning", true); }
    static void setMorning(Context c, boolean v) { sp(c).edit().putBoolean("morning", v).apply(); }
    static int morningHour(Context c) { return sp(c).getInt("morning_hour", 7); }
    static void setMorningHour(Context c, int h) { sp(c).edit().putInt("morning_hour", h).apply(); }

    /** OTA updates. */
    static boolean autoUpdate(Context c) { return sp(c).getBoolean("auto_update", true); }
    static void setAutoUpdate(Context c, boolean v) { sp(c).edit().putBoolean("auto_update", v).apply(); }
    static long lastUpdateCheck(Context c) { return sp(c).getLong("update_check", 0L); }
    static void setLastUpdateCheck(Context c, long t) { sp(c).edit().putLong("update_check", t).apply(); }
    static long notifiedVersion(Context c) { return sp(c).getLong("update_notified", 0L); }
    static void setNotifiedVersion(Context c, long v) { sp(c).edit().putLong("update_notified", v).apply(); }

    static boolean quiet(Context c) { return sp(c).getBoolean("quiet", true); }
    static void setQuiet(Context c, boolean v) { sp(c).edit().putBoolean("quiet", v).apply(); }

    static int quietStart(Context c) { return sp(c).getInt("qs", 22); }
    static int quietEnd(Context c) { return sp(c).getInt("qe", 7); }
    static void setQuietHours(Context c, int start, int end) { sp(c).edit().putInt("qs", start).putInt("qe", end).apply(); }

    // ---- Material You colours (Android 12+) ----
    static boolean materialYou(Context c) { return sp(c).getBoolean("you", false); }
    static void setMaterialYou(Context c, boolean v) { sp(c).edit().putBoolean("you", v).apply(); }

    /** Fills the dynamic-colour fields of the card data from the system palette (no-op unless enabled on Android 12+). */
    static void applyColors(Context c, Cards.Data d) {
        d.youTop = 0;
        if (Build.VERSION.SDK_INT < 31 || !materialYou(c)) return;
        try {
            d.youTop = c.getColor(android.R.color.system_accent1_600);
            d.youBottom = c.getColor(android.R.color.system_accent1_700);
            d.youP0 = c.getColor(android.R.color.system_accent1_500);
            d.youP1 = c.getColor(android.R.color.system_accent1_700);
        } catch (Throwable t) {
            d.youTop = 0;
        }
    }

    // ---- official warnings ----
    static boolean official(Context c) { return sp(c).getBoolean("official", true); }
    static void setOfficial(Context c, boolean v) { sp(c).edit().putBoolean("official", v).apply(); }
    static String officialStatus(Context c) { return sp(c).getString("official_status", "Not checked yet"); }

    /** Re-reads the stored official warnings into {@link Insights#official} for the current place. */
    static void loadOfficial(Context c) {
        try {
            if (!official(c)) {
                Insights.official = new java.util.ArrayList<Insights.Insight>();
                return;
            }
            Place p = loadPlace(c);
            long now = System.currentTimeMillis() / 1000L;
            Insights.official = Warnings.toInsights(Warnings.parse(sp(c).getString("official_json", ""), p, now));
        } catch (Throwable t) {
            Insights.official = new java.util.ArrayList<Insights.Insight>();
        }
    }

    // ---- streak: consecutive days the app was opened ----
    static long dayNumber() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    /** Call when the app comes to the front. Keeps a 64-day bit history (bit 0 = today). */
    static synchronized void touchStreak(Context c) {
        SharedPreferences p = sp(c);
        long today = dayNumber();
        long last = p.getLong("streak_last", 0);
        long mask = p.getLong("streak_mask", 0);
        if (last == today && mask != 0) return;
        long delta = last == 0 ? 64 : Math.max(0, today - last);
        mask = delta >= 64 ? 0 : (mask << delta);
        mask |= 1L;
        int run = 0;
        while (run < 64 && ((mask >>> run) & 1L) != 0) run++;
        int best = Math.max(run, p.getInt("streak_best", 0));
        p.edit().putLong("streak_last", today).putLong("streak_mask", mask).putInt("streak_best", best).apply();
    }

    static int streak(Context c) {
        long mask = sp(c).getLong("streak_mask", 0);
        int run = 0;
        while (run < 64 && ((mask >>> run) & 1L) != 0) run++;
        return run;
    }

    static int bestStreak(Context c) { return sp(c).getInt("streak_best", 0); }
    static long streakMask(Context c) { return sp(c).getLong("streak_mask", 0); }

    static boolean cats(Context c) { return sp(c).getBoolean("cats", true); }
    static void setCats(Context c, boolean v) { sp(c).edit().putBoolean("cats", v).apply(); }

    static boolean animate(Context c) { return sp(c).getBoolean("animate", true); }
    static void setAnimate(Context c, boolean v) { sp(c).edit().putBoolean("animate", v).apply(); }

    /** Widget background: 0 = solid, 1 = frosted glass, 2 = clear (dark mode fully see-through). */
    static int bgMode(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains("bg_mode")) return p.getInt("bg_mode", 0);
        return p.getBoolean("clear_dark", false) ? 2 : 0;
    }

    static void setBgMode(Context c, int mode) { sp(c).edit().putInt("bg_mode", mode).apply(); }

    static void applyBackground(Context c, Cards.Data d) {
        int mode = bgMode(c);
        d.transparentDark = mode == 2;
        d.bgAlpha = mode == 1 ? 0.6f : 1f;
    }

    static Units units(Context c) {
        return Units.resolve(tempPref(c), Locale.getDefault(), DateFormat.is24HourFormat(c));
    }

    // ---- cache ----
    static synchronized void saveWeather(Context c, String forecast, String air, long fetchedAtMs) {
        sp(c).edit().putString("fc", forecast).putString("air", air == null ? "" : air).putLong("fetched", fetchedAtMs).apply();
    }

    static synchronized Weather loadWeather(Context c) {
        SharedPreferences s = sp(c);
        String fc = s.getString("fc", null);
        if (fc == null) return null;
        try {
            Weather w = Weather.parse(fc, s.getString("air", ""), s.getLong("fetched", 0));
            return w.valid() ? w : null;
        } catch (Exception e) {
            return null;
        }
    }

    static long fetchedAt(Context c) { return sp(c).getLong("fetched", 0); }

    static String lastAir(Context c) { return sp(c).getString("air", ""); }

    static synchronized void savePlace(Context c, Place p) {
        sp(c).edit().putString("place", p.toJson().toString()).apply();
    }

    static synchronized Place loadPlace(Context c) {
        String j = sp(c).getString("place", null);
        return j == null ? null : Place.fromJson(j);
    }

    static Cards.Data data(Context c) {
        Cards.Data d = new Cards.Data();
        d.weather = loadWeather(c);
        d.place = loadPlace(c);
        d.units = units(c);
        d.nowSec = System.currentTimeMillis() / 1000L;
        loadOfficial(c);
        applyColors(c, d);
        return d;
    }

    // ---- notification de-duplication ----
    static synchronized boolean markSeen(Context c, String key) {
        SharedPreferences s = sp(c);
        Set<String> seen = new HashSet<String>(s.getStringSet("seen", new HashSet<String>()));
        if (seen.contains(key)) return false;
        if (seen.size() > 60) seen.clear();
        seen.add(key);
        s.edit().putStringSet("seen", seen).apply();
        return true;
    }

    // ---- permissions ----
    static boolean fine(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean coarse(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean anyLocation(Context c) {
        return fine(c) || coarse(c);
    }

    static boolean background(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean notificationsAllowed(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
}
