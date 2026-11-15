package app.skypix.android;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateFormat;

import app.skypix.core.Cards;
import app.skypix.core.Place;
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

    static boolean quiet(Context c) { return sp(c).getBoolean("quiet", true); }
    static void setQuiet(Context c, boolean v) { sp(c).edit().putBoolean("quiet", v).apply(); }

    static int quietStart(Context c) { return sp(c).getInt("qs", 22); }
    static int quietEnd(Context c) { return sp(c).getInt("qe", 7); }
    static void setQuietHours(Context c, int start, int end) { sp(c).edit().putInt("qs", start).putInt("qe", end).apply(); }

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
