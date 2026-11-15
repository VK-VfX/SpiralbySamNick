package app.skypix.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parsed Open-Meteo forecast (+ optional air quality). All temperatures °C, wind km/h, precipitation mm. */
public final class Weather {
    public long fetchedAtMs;
    public int utcOffset;
    public double lat = Double.NaN, lon = Double.NaN, elevation = Double.NaN;

    public long curTime;
    public double temp = Double.NaN, feels = Double.NaN, humidity = Double.NaN;
    public double windKmh = Double.NaN, windDir = Double.NaN, gustKmh = Double.NaN;
    public double pressure = Double.NaN, cloud = Double.NaN, precipNow = 0;
    public int code;
    public boolean isDay = true;

    public long[] hTime = new long[0];
    public double[] hTemp = new double[0], hFeels = new double[0], hHum = new double[0];
    public double[] hPop = new double[0], hPrecip = new double[0], hWind = new double[0];
    public double[] hUv = new double[0], hVis = new double[0];
    public int[] hCode = new int[0];
    public boolean[] hDay = new boolean[0];

    public long[] dTime = new long[0], dSunrise = new long[0], dSunset = new long[0];
    public double[] dMax = new double[0], dMin = new double[0], dPop = new double[0];
    public double[] dUv = new double[0], dPrecip = new double[0];
    public int[] dCode = new int[0];

    public int aqi = -1;
    public double pm25 = Double.NaN;

    /** A point-in-time view of the conditions. */
    public static final class Now {
        public double temp, feels, humidity, wind, gust, pressure, uv, visibility, precip, cloud, windDir;
        public int code;
        public boolean isDay;
    }

    public static Weather parse(String forecastJson, String airJson, long fetchedAtMs) throws JSONException {
        JSONObject f = new JSONObject(forecastJson);
        Weather w = new Weather();
        w.fetchedAtMs = fetchedAtMs;
        w.utcOffset = f.optInt("utc_offset_seconds", 0);
        w.lat = f.optDouble("latitude", Double.NaN);
        w.lon = f.optDouble("longitude", Double.NaN);
        w.elevation = f.optDouble("elevation", Double.NaN);

        JSONObject c = f.optJSONObject("current");
        if (c != null) {
            w.curTime = Fmt.parseLocal(c.optString("time", ""), w.utcOffset);
            w.temp = c.optDouble("temperature_2m", Double.NaN);
            w.feels = c.optDouble("apparent_temperature", Double.NaN);
            w.humidity = c.optDouble("relative_humidity_2m", Double.NaN);
            w.windKmh = c.optDouble("wind_speed_10m", Double.NaN);
            w.windDir = c.optDouble("wind_direction_10m", Double.NaN);
            w.gustKmh = c.optDouble("wind_gusts_10m", Double.NaN);
            w.pressure = c.optDouble("pressure_msl", Double.NaN);
            w.cloud = c.optDouble("cloud_cover", Double.NaN);
            double p = c.optDouble("precipitation", 0);
            w.precipNow = Double.isNaN(p) ? 0 : p;
            w.code = c.optInt("weather_code", 0);
            w.isDay = c.optInt("is_day", 1) == 1;
        }

        JSONObject h = f.optJSONObject("hourly");
        if (h != null && h.optJSONArray("time") != null) {
            JSONArray t = h.getJSONArray("time");
            int n = t.length();
            w.hTime = new long[n];
            for (int i = 0; i < n; i++) w.hTime[i] = Fmt.parseLocal(t.getString(i), w.utcOffset);
            w.hTemp = dbl(h, "temperature_2m", n);
            w.hFeels = dbl(h, "apparent_temperature", n);
            w.hHum = dbl(h, "relative_humidity_2m", n);
            w.hPop = dbl(h, "precipitation_probability", n);
            w.hPrecip = dbl(h, "precipitation", n);
            w.hWind = dbl(h, "wind_speed_10m", n);
            w.hUv = dbl(h, "uv_index", n);
            w.hVis = dbl(h, "visibility", n);
            w.hCode = ints(h, "weather_code", n, 0);
            int[] day = ints(h, "is_day", n, 1);
            w.hDay = new boolean[n];
            for (int i = 0; i < n; i++) w.hDay[i] = day[i] == 1;
        }

        JSONObject d = f.optJSONObject("daily");
        if (d != null && d.optJSONArray("time") != null) {
            JSONArray t = d.getJSONArray("time");
            int n = t.length();
            w.dTime = new long[n];
            w.dSunrise = new long[n];
            w.dSunset = new long[n];
            JSONArray sr = d.optJSONArray("sunrise"), ss = d.optJSONArray("sunset");
            for (int i = 0; i < n; i++) {
                w.dTime[i] = Fmt.parseLocal(t.getString(i), w.utcOffset);
                w.dSunrise[i] = sr != null && !sr.isNull(i) ? Fmt.parseLocal(sr.getString(i), w.utcOffset) : 0;
                w.dSunset[i] = ss != null && !ss.isNull(i) ? Fmt.parseLocal(ss.getString(i), w.utcOffset) : 0;
            }
            w.dMax = dbl(d, "temperature_2m_max", n);
            w.dMin = dbl(d, "temperature_2m_min", n);
            w.dPop = dbl(d, "precipitation_probability_max", n);
            w.dUv = dbl(d, "uv_index_max", n);
            w.dPrecip = dbl(d, "precipitation_sum", n);
            w.dCode = ints(d, "weather_code", n, 0);
        }

        if (airJson != null && airJson.length() > 0) {
            try {
                JSONObject a = new JSONObject(airJson).optJSONObject("current");
                if (a != null) {
                    double aq = a.optDouble("us_aqi", Double.NaN);
                    w.aqi = Double.isNaN(aq) ? -1 : (int) Math.round(aq);
                    w.pm25 = a.optDouble("pm2_5", Double.NaN);
                }
            } catch (JSONException ignored) {
            }
        }
        return w;
    }

    private static double[] dbl(JSONObject o, String key, int n) {
        double[] out = new double[n];
        JSONArray a = o.optJSONArray(key);
        for (int i = 0; i < n; i++) out[i] = a == null ? Double.NaN : a.optDouble(i, Double.NaN);
        return out;
    }

    private static int[] ints(JSONObject o, String key, int n, int def) {
        int[] out = new int[n];
        JSONArray a = o.optJSONArray(key);
        for (int i = 0; i < n; i++) out[i] = a == null ? def : a.optInt(i, def);
        return out;
    }

    public boolean valid() {
        return !Double.isNaN(temp) && hTime.length > 0 && dTime.length > 0;
    }

    /** Index of the hourly slot containing {@code nowSec}. */
    public int hourIndex(long nowSec) {
        int idx = 0;
        for (int i = 0; i < hTime.length; i++) {
            if (hTime[i] <= nowSec) idx = i; else break;
        }
        return idx;
    }

    public int dayIndex(long nowSec) {
        int idx = 0;
        for (int i = 0; i < dTime.length; i++) {
            if (dTime[i] <= nowSec) idx = i; else break;
        }
        return idx;
    }

    public Now now(long nowSec) {
        Now n = new Now();
        int hi = hourIndex(nowSec);
        boolean fresh = curTime > 0 && Math.abs(nowSec - curTime) < 50 * 60;
        if (fresh || hTime.length == 0) {
            n.temp = temp;
            n.feels = feels;
            n.humidity = humidity;
            n.code = code;
            n.isDay = isDay;
            n.wind = windKmh;
        } else {
            n.temp = hTemp[hi];
            n.feels = hFeels[hi];
            n.humidity = hHum[hi];
            n.code = hCode[hi];
            n.isDay = hDay[hi];
            n.wind = hWind[hi];
        }
        n.gust = gustKmh;
        n.windDir = windDir;
        n.pressure = pressure;
        n.cloud = cloud;
        n.precip = fresh ? precipNow : (hTime.length > 0 ? hPrecip[hi] : 0);
        n.uv = hTime.length > 0 ? hUv[hi] : Double.NaN;
        n.visibility = hTime.length > 0 ? hVis[hi] : Double.NaN;
        return n;
    }

    public double todayMax(long nowSec) {
        return dMax.length == 0 ? Double.NaN : dMax[dayIndex(nowSec)];
    }

    public double todayMin(long nowSec) {
        return dMin.length == 0 ? Double.NaN : dMin[dayIndex(nowSec)];
    }

    public static String uvLabel(double uv) {
        if (Double.isNaN(uv)) return "--";
        if (uv < 3) return "Low";
        if (uv < 6) return "Moderate";
        if (uv < 8) return "High";
        if (uv < 11) return "Very high";
        return "Extreme";
    }

    public static String aqiLabel(int aqi) {
        if (aqi < 0) return "--";
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Sensitive";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very bad";
        return "Hazardous";
    }

    public static int aqiColor(int aqi) {
        if (aqi < 0) return 0xFF9AA3B5;
        if (aqi <= 50) return 0xFF5BE584;
        if (aqi <= 100) return 0xFFFFD23F;
        if (aqi <= 150) return 0xFFFF9F1C;
        if (aqi <= 200) return 0xFFFF5A5F;
        if (aqi <= 300) return 0xFFC77DFF;
        return 0xFFB5179E;
    }
}
