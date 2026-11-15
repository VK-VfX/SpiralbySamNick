package app.skypix.android;

import app.skypix.core.Place;

import org.json.JSONObject;

import java.util.Locale;

/** Reverse geocoding via OpenStreetMap Nominatim (no Google services involved). */
final class Geo {
    private Geo() {}

    /** Fills the name fields of {@code p}. Returns false if the lookup failed. */
    static boolean reverse(Place p) {
        try {
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&accept-language=%s",
                    p.lat, p.lon, Locale.getDefault().toLanguageTag());
            JSONObject a = new JSONObject(Net.get(url)).optJSONObject("address");
            if (a == null) return false;
            p.neighborhood = first(a, "suburb", "quarter", "neighbourhood", "city_district", "hamlet");
            p.city = first(a, "city", "town", "village", "municipality", "county", "state_district");
            p.region = first(a, "state", "region", "province");
            p.country = a.optString("country", "");
            if (p.neighborhood.equals(p.city)) p.neighborhood = "";
            return p.city.length() > 0 || p.neighborhood.length() > 0 || p.region.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.length() > 0) return v;
        }
        return "";
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
