package app.skypix.tools;

import app.skypix.core.Cards;
import app.skypix.core.Fmt;
import app.skypix.core.Place;
import app.skypix.core.Units;
import app.skypix.core.Weather;

/** Synthetic-but-plausible data so every widget can be rendered and inspected off-device. */
final class Samples {
    private Samples() {}

    static final int OFF = 8 * 3600;

    static Place place() {
        Place p = new Place();
        p.lat = 14.6507;
        p.lon = 121.1029;
        p.accuracyM = 8;
        p.altitudeM = 42;
        p.precise = true;
        p.provider = "gps";
        p.neighborhood = "Concepcion Uno";
        p.city = "Marikina";
        p.region = "Metro Manila";
        p.country = "Philippines";
        p.fixTimeMs = (Fmt.parseLocal("2026-11-15T15:28", OFF)) * 1000;
        return p;
    }

    /** scenario: clear, night, rain, storm, snow, cloudy, fog, partly */
    static Cards.Data data(String scenario, boolean fahrenheit, boolean h24) {
        String nowIso = scenario.equals("night") ? "2026-11-15T22:30" : "2026-11-15T15:30";
        long now = Fmt.parseLocal(nowIso, OFF);
        Cards.Data d = new Cards.Data();
        d.weather = weather(scenario, now);
        d.place = place();
        d.units = new Units(fahrenheit, fahrenheit, h24);
        d.nowSec = now;
        return d;
    }

    static Weather weather(String sc, long now) {
        Weather w = new Weather();
        w.utcOffset = OFF;
        w.fetchedAtMs = now * 1000;
        w.lat = 14.6507;
        w.lon = 121.1029;
        w.elevation = 42;
        long day0 = Fmt.parseLocal("2026-11-15", OFF);
        int n = 7 * 24;
        w.hTime = new long[n];
        w.hTemp = new double[n]; w.hFeels = new double[n]; w.hHum = new double[n];
        w.hPop = new double[n]; w.hPrecip = new double[n]; w.hWind = new double[n];
        w.hUv = new double[n]; w.hVis = new double[n];
        w.hCode = new int[n]; w.hDay = new boolean[n];
        double base = sc.equals("snow") ? -2 : sc.equals("cloudy") ? 24 : 28;
        for (int i = 0; i < n; i++) {
            int hour = i % 24;
            w.hTime[i] = day0 + i * 3600L;
            double t = base + 4.5 * Math.sin(2 * Math.PI * (hour - 9) / 24.0);
            w.hTemp[i] = t;
            w.hFeels[i] = t + (sc.equals("snow") ? -4 : 5);
            w.hHum[i] = 62 + 8 * Math.cos(2 * Math.PI * (hour - 4) / 24.0);
            w.hDay[i] = hour >= 6 && hour < 18;
            w.hUv[i] = w.hDay[i] ? Math.max(0, 9 * Math.sin(Math.PI * (hour - 6) / 12.0)) : 0;
            w.hWind[i] = 10 + (hour % 5);
            w.hVis[i] = 24000;
            int code = 0; double pop = 4, pr = 0;
            if (sc.equals("partly")) code = 2;
            else if (sc.equals("cloudy")) code = 3;
            else if (sc.equals("fog")) { code = 45; w.hVis[i] = 600; }
            else if (sc.equals("snow")) { code = 73; pop = 80; pr = 1.2; }
            else if (sc.equals("rain") && hour >= 17 && hour <= 21 && i < 48) { code = 63; pop = 78; pr = 1.8; }
            else if (sc.equals("rain")) { code = 3; pop = 20; }
            else if (sc.equals("storm") && hour >= 17 && hour <= 20 && i < 48) { code = 95; pop = 85; pr = 4.0; }
            else if (sc.equals("storm")) { code = 2; pop = 30; }
            else if (sc.equals("night")) code = hour >= 3 && hour < 6 ? 2 : 1;
            w.hCode[i] = code; w.hPop[i] = pop; w.hPrecip[i] = pr;
        }
        int hi = w.hourIndex(now);
        w.curTime = now;
        w.temp = w.hTemp[hi]; w.feels = w.hFeels[hi]; w.humidity = w.hHum[hi];
        w.windKmh = 12; w.windDir = 80; w.gustKmh = 21; w.pressure = 1009; w.cloud = 35;
        w.precipNow = (sc.equals("rain") || sc.equals("snow")) ? 0.4 : 0;
        w.code = sc.equals("rain") ? 61 : w.hCode[hi];
        w.isDay = w.hDay[hi];
        int dn = 7;
        w.dTime = new long[dn]; w.dSunrise = new long[dn]; w.dSunset = new long[dn];
        w.dMax = new double[dn]; w.dMin = new double[dn]; w.dPop = new double[dn];
        w.dUv = new double[dn]; w.dPrecip = new double[dn]; w.dCode = new int[dn];
        int[] codes = {1, 2, 63, 3, 0, 95, 61};
        double[] pops = {10, 25, 80, 40, 5, 70, 55};
        double[] mx = {36, 35, 31, 32, 36, 30, 31};
        double[] mn = {27, 27, 25, 26, 27, 25, 26};
        for (int i = 0; i < dn; i++) {
            w.dTime[i] = day0 + i * 86400L;
            w.dSunrise[i] = day0 + i * 86400L + 5 * 3600 + 58 * 60;
            w.dSunset[i] = day0 + i * 86400L + 17 * 3600 + 31 * 60;
            w.dMax[i] = sc.equals("snow") ? 1 : mx[i];
            w.dMin[i] = sc.equals("snow") ? -5 : mn[i];
            w.dPop[i] = pops[i]; w.dUv[i] = 9; w.dPrecip[i] = 2; w.dCode[i] = sc.equals("snow") ? 73 : codes[i];
        }
        w.aqi = 57;
        w.pm25 = 18;
        return w;
    }
}
