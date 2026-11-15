package app.skypix.core;

public final class Place {
    public double lat, lon;
    public float accuracyM = Float.NaN;
    public double altitudeM = Double.NaN;
    public long fixTimeMs;
    public String provider = "";
    public boolean precise;

    public String neighborhood = "";
    public String city = "";
    public String region = "";
    public String country = "";

    public String title() {
        String c = city.length() > 0 ? city : neighborhood.length() > 0 ? neighborhood : region;
        if (c.length() == 0) return Fmt.coord(lat, true) + " " + Fmt.coord(lon, false);
        return country.length() > 0 && !country.equals(c) ? c + ", " + country : c;
    }

    public String shortName() {
        if (city.length() > 0) return city;
        if (neighborhood.length() > 0) return neighborhood;
        if (region.length() > 0) return region;
        return Fmt.coord(lat, true);
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (neighborhood.length() > 0 && !neighborhood.equals(city)) sb.append(neighborhood);
        if (region.length() > 0 && !region.equals(city) && !region.equals(neighborhood)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(region);
        }
        return sb.toString();
    }

    public org.json.JSONObject toJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("lat", lat).put("lon", lon).put("acc", Double.isNaN(accuracyM) ? -1 : accuracyM)
                    .put("alt", Double.isNaN(altitudeM) ? -9999 : altitudeM).put("t", fixTimeMs)
                    .put("prov", provider).put("precise", precise).put("nb", neighborhood)
                    .put("city", city).put("region", region).put("country", country);
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    public static Place fromJson(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            Place p = new Place();
            p.lat = o.getDouble("lat");
            p.lon = o.getDouble("lon");
            double acc = o.optDouble("acc", -1);
            p.accuracyM = acc < 0 ? Float.NaN : (float) acc;
            double alt = o.optDouble("alt", -9999);
            p.altitudeM = alt < -9000 ? Double.NaN : alt;
            p.fixTimeMs = o.optLong("t", 0);
            p.provider = o.optString("prov", "");
            p.precise = o.optBoolean("precise", false);
            p.neighborhood = o.optString("nb", "");
            p.city = o.optString("city", "");
            p.region = o.optString("region", "");
            p.country = o.optString("country", "");
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
