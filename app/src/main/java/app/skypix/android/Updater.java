package app.skypix.android;

import android.content.Context;
import android.location.Location;

import app.skypix.core.Place;
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

            WidgetUpdater.updateAll(app);
            Weather w = Store.loadWeather(app);
            if (w != null) Notifier.alerts(app, place, w);
            return w != null;
        }
    }

    /** Hourly briefing entry point: refresh, then post the summary notification. */
    static void runHourly(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean ok = run(app, Store.visible || Store.background(app), 8000, 20 * 60 * 1000L);
        Weather w = Store.loadWeather(app);
        Place p = Store.loadPlace(app);
        if ((ok || w != null) && w != null && p != null) Notifier.hourly(app, p, w);
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
        }
    }
}
