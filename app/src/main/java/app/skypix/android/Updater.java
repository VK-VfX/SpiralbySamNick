package app.skypix.android;

import android.content.Context;
import android.location.Location;

import app.skypix.core.Place;
import app.skypix.core.Warnings;
import app.skypix.core.Weather;

import java.util.Locale;

/** One refresh cycle: position -> forecast -> widgets -> alerts. Always call off the main thread. */
final class Updater {
    private Updater() {}

    private static final Object LOCK = new Object();

    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,"
            + "cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
            + "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation_probability,precipitation,"
            + "weather_code,visibility,wind_speed_10m,uv_index,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,"
            + "precipitation_probability_max,precipitation_sum"
            + "&minutely_15=precipitation&forecast_minutely_15=16"
            + "&timezone=auto&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm";

    private static final String AIR_URL =
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=us_aqi,pm2_5&timezone=auto";

    /**
     * @param fresh      acquire a new GPS/network fix first
     * @param maxAgeMs   skip the network if cached weather is newer than this and the place is unchanged
     * @return true if a place and weather are available afterwards
     */
    static boolean run(Context ctx, boolean fresh, long locTimeoutMs, long maxAgeMs) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            Place place = Store.loadPlace(app);
            if (fresh) {
                Location l = LocationHelper.acquire(app, locTimeoutMs);
                if (l != null) place = adopt(app, place, LocationHelper.toPlace(app, l));
            }
            if (place == null) return false;

            Weather cached = Store.loadWeather(app);
            long age = System.currentTimeMillis() - Store.fetchedAt(app);
            boolean farFromCache = cached != null
                    && Geo.distanceM(cached.lat, cached.lon, place.lat, place.lon) > 3000;
            if (cached == null || age > maxAgeMs || farFromCache) {
                fetch(app, place);
            }

            fetchOfficial(app, place);
            refreshSaved(app, Store.visible);
            WidgetUpdater.updateAll(app);
            Weather w = Store.loadWeather(app);
            if (w != null) Notifier.alerts(app, place, w);
            return w != null;
        }
    }

    /** Cat News entry point (every 2 hours): refresh, post the bulletin, and look for a new app version. */
    static void runNews(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean ok = run(app, Store.visible || Store.background(app), 8000, 20 * 60 * 1000L);
        Weather w = Store.loadWeather(app);
        Place p = Store.loadPlace(app);
        if ((ok || w != null) && w != null && p != null) Notifier.news(app, p, w);
        AppUpdate.checkQuietly(app);
    }

    /** Morning briefing entry point. */
    static void runMorning(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean ok = run(app, Store.visible || Store.background(app), 8000, 10 * 60 * 1000L);
        Weather w = Store.loadWeather(app);
        Place p = Store.loadPlace(app);
        if ((ok || w != null) && w != null && p != null) Notifier.morning(app, p, w);
    }

    /** MET Malaysia warnings, at most every 20 minutes, only while you are in Malaysia. */
    static void fetchOfficial(Context app, Place place) {
        try {
            android.content.SharedPreferences sp = Store.prefs(app);
            if (!Store.official(app)) return;
            String country = place.country == null ? "" : place.country.toLowerCase(Locale.ROOT);
            if (!country.contains("malaysia")) {
                sp.edit().putString("official_status", "Only available in Malaysia (you are in " + (place.country.length() > 0 ? place.country : "an unknown place") + ")").remove("official_json").apply();
                Store.loadOfficial(app);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - sp.getLong("official_at", 0) < 20 * 60 * 1000L && sp.contains("official_json")) {
                Store.loadOfficial(app);
                return;
            }
            String raw = Net.get("https://api.data.gov.my/weather/warning");
            long nowSec = now / 1000L;
            java.util.List<Warnings.Warning> all = Warnings.parse(raw, place, nowSec, Store.officialMarine(app));
            org.json.JSONArray keep = new org.json.JSONArray();
            int mine = 0;
            // keep every active record so the sea/coastal switch can take effect without another download
            for (Warnings.Warning w : all) {
                if (w.forYou) mine++;
                if (keep.length() < 200) keep.put(new org.json.JSONObject(w.raw));
            }
            String when = android.text.format.DateFormat.format("h:mm a", now).toString();
            String status = "MET Malaysia: " + all.size() + " active, " + mine + " for your area (checked " + when + ")";
            if (all.isEmpty() && raw.trim().length() > 2) status += ". Feed read, nothing active";
            sp.edit().putString("official_json", keep.toString()).putString("official_status", status).putLong("official_at", now).apply();
            Store.loadOfficial(app);
        } catch (Exception e) {
            Store.prefs(app).edit().putString("official_status", "Couldn't read MET Malaysia right now (" + e.getMessage() + ")").apply();
            Store.loadOfficial(app);
        }
    }

    /** Forecasts for saved places: the ones a widget shows (all of them while the app is open). */
    static void refreshSaved(Context app, boolean all) {
        try {
            java.util.Set<String> inUse = WidgetUpdater.placesInUse(app);
            for (Places.Saved s : Places.list(app)) {
                if (!all && !inUse.contains(s.id)) continue;
                long now = System.currentTimeMillis();
                if (now - Places.fetchedAt(app, s.id) < 15 * 60 * 1000L && Places.loadWeather(app, s.id) != null) continue;
                try {
                    String fc = Net.get(String.format(Locale.US, FORECAST_URL, s.place.lat, s.place.lon));
                    String air = "";
                    try {
                        air = Net.get(String.format(Locale.US, AIR_URL, s.place.lat, s.place.lon));
                    } catch (Exception ignored) {
                    }
                    Weather w = Weather.parse(fc, air, now);
                    if (w.valid()) Places.saveWeather(app, s.id, fc, air, now);
                } catch (Exception e) {
                    Ui.report("saved place " + s.name, e);
                }
            }
        } catch (Throwable t) {
            Ui.report("refreshSaved", t);
        }
    }

    private static Place adopt(Context app, Place old, Place np) {
        if (old != null) {
            double dist = Geo.distanceM(old.lat, old.lon, np.lat, np.lon);
            if (dist < 250 && old.city.length() > 0) {
                copyNames(old, np);
            } else if (!Geo.reverse(np) && dist < 3000) {
                copyNames(old, np);
            }
        } else {
            Geo.reverse(np);
        }
        Store.savePlace(app, np);
        return np;
    }

    private static void copyNames(Place from, Place to) {
        to.neighborhood = from.neighborhood;
        to.city = from.city;
        to.region = from.region;
        to.country = from.country;
    }

    private static void fetch(Context app, Place place) {
        try {
            String fc = Net.get(String.format(Locale.US, FORECAST_URL, place.lat, place.lon));
            String air = "";
            try {
                air = Net.get(String.format(Locale.US, AIR_URL, place.lat, place.lon));
            } catch (Exception e) {
                air = Store.lastAir(app);
            }
            long now = System.currentTimeMillis();
            Weather w = Weather.parse(fc, air, now);
            if (w.valid()) Store.saveWeather(app, fc, air, now);
        } catch (Exception e) {
            // offline or API hiccup: keep showing the cached forecast
            Ui.report("weather fetch", e);
        }
    }
}
