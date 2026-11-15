package app.skypix.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Turns a forecast into short, human, actionable notes (also the source for notifications). */
public final class Insights {
    private Insights() {}

    public static final int INFO = 0, NOTICE = 1, WARNING = 2;

    public static final class Insight {
        public final String key;
        public final int sev;
        public final Icon icon;
        public final String title;
        public final String detail;
        /** Compact wordings for small widgets; widgets pick the longest that fits. */
        public final String shortTitle;
        public final String shortDetail;
        /** Epoch seconds the note is about, or 0 when it applies right now. */
        public final long at;

        Insight(String key, int sev, Icon icon, String title, String detail, String shortTitle, String shortDetail, long at) {
            this.key = key;
            this.sev = sev;
            this.icon = icon;
            this.title = title;
            this.detail = detail;
            this.shortTitle = shortTitle;
            this.shortDetail = shortDetail;
            this.at = at;
        }
    }

    public static List<Insight> compute(Weather w, Units u, long nowSec) {
        List<Insight> out = new ArrayList<Insight>();
        if (w == null || !w.valid()) return out;
        Weather.Now n = w.now(nowSec);
        int off = w.utcOffset;
        int hi = w.hourIndex(nowSec);
        int end = Math.min(hi + 12, w.hTime.length - 1);
        long day = Fmt.epochDay(nowSec, off);

        // Rain / snow
        boolean wetNow = Wmo.isWet(n.code) || Wmo.isSnow(n.code) || n.precip >= 0.2;
        String noun = Wmo.isSnow(n.code) ? "Snow" : Wmo.isStorm(n.code) ? "Storm" : "Rain";
        if (wetNow) {
            int easing = -1;
            for (int j = hi + 1; j <= end; j++) {
                if (w.hPop[j] < 30 && w.hPrecip[j] < 0.1 && !Wmo.isWet(w.hCode[j]) && !Wmo.isSnow(w.hCode[j])) {
                    easing = j;
                    break;
                }
            }
            if (easing > 0) {
                out.add(new Insight("wet-now:" + day, NOTICE, Wmo.isSnow(n.code) ? Icon.SHIVER : Icon.UMBRELLA,
                        noun + " easing around " + u.time(w.hTime[easing], off, false),
                        "Right now: " + Wmo.label(n.code) + " \u00b7 dry paws soon", noun + " eases " + u.time(w.hTime[easing], off, false), Wmo.label(n.code), 0L));
            } else {
                out.add(new Insight("wet-now:" + day, NOTICE, Wmo.isSnow(n.code) ? Icon.SHIVER : Icon.UMBRELLA,
                        noun + " is here to stay",
                        "The umbrella stays out for the next 12 hours", noun + " continues", "Wet for 12 h", 0L));
            }
        } else {
            for (int j = hi + 1; j <= end; j++) {
                boolean wet = Wmo.isWet(w.hCode[j]) || Wmo.isSnow(w.hCode[j]);
                if ((w.hPop[j] >= 50 && (w.hPrecip[j] >= 0.1 || wet)) || (wet && w.hPop[j] >= 40)) {
                    boolean snow = Wmo.isSnow(w.hCode[j]);
                    long hrs = Math.max(1, (w.hTime[j] - nowSec + 1800) / 3600);
                    out.add(new Insight("wet:" + (w.hTime[j] / 3600), NOTICE,
                            snow ? Icon.SHIVER : Icon.UMBRELLA,
                            snow ? "Snow likely around " + u.time(w.hTime[j], off, false) : "Bring an umbrella: rain ~" + u.time(w.hTime[j], off, false),
                            Math.round(w.hPop[j]) + "% chance · " + u.precip(w.hPrecip[j]) + " · in about " + hrs + " h", (snow ? "Snow" : "Rain") + " ~" + u.time(w.hTime[j], off, false), Math.round(w.hPop[j]) + "% \u00b7 " + hrs + " h", w.hTime[j]));
                    break;
                }
            }
        }

        // Thunderstorms
        for (int j = hi; j <= end; j++) {
            if (Wmo.isStorm(w.hCode[j])) {
                out.add(new Insight("storm:" + (w.hTime[j] / 3600), WARNING, Icon.STORM,
                        "Thunderstorms at " + u.time(w.hTime[j], off, false),
                        "Possible \u00b7 hide indoors, don't get wet", "Storm " + u.time(w.hTime[j], off, false), "Stay indoors", w.hTime[j]));
                break;
            }
        }

        // Flooding: heavy rain totals over the next hours
        double p3 = 0, p24 = 0, rate = 0;
        int rateIdx = hi;
        for (int j = hi; j < Math.min(hi + 24, w.hTime.length); j++) {
            double pr = Double.isNaN(w.hPrecip[j]) ? 0 : w.hPrecip[j];
            p24 += pr;
            if (j < hi + 3) p3 += pr;
            if (pr > rate) { rate = pr; rateIdx = j; }
        }
        if (p3 >= 30 || rate >= 25 || p24 >= 100) {
            String amount = (p3 >= 30 || rate >= 25)
                    ? "About " + u.precip(Math.max(p3, rate)) + " of rain in the next 3 hours"
                    : "About " + u.precip(p24) + " of rain over the next 24 hours";
            out.add(new Insight("flood:" + day, WARNING, Icon.ALERT, "Flood alert: stay high and dry",
                    amount + " \u00b7 avoid low roads, don't wade through water", "Flood risk", "Heavy rain", w.hTime[rateIdx]));
        } else if (p3 >= 15 || rate >= 10 || p24 >= 50) {
            out.add(new Insight("flood-watch:" + day, NOTICE, Icon.UMBRELLA, "Downpour ahead: keep paws dry",
                    "Up to " + u.precip(Math.max(p3, p24 / 3)) + " expected \u00b7 stay dry and avoid low-lying areas", "Heavy rain", "Flooding possible", w.hTime[rateIdx]));
        }

        // Heat / cold
        double maxFeels = n.feels;
        double minTemp = n.temp;
        int minIdx = hi, maxIdx = hi;
        for (int j = hi; j <= end; j++) {
            if (!Double.isNaN(w.hFeels[j]) && w.hFeels[j] > maxFeels) { maxFeels = w.hFeels[j]; maxIdx = j; }
            if (!Double.isNaN(w.hTemp[j]) && w.hTemp[j] < minTemp) { minTemp = w.hTemp[j]; minIdx = j; }
        }
        if (maxFeels >= 41) {
            out.add(new Insight("heat:" + day, WARNING, Icon.SWEAT, "Too hot to purr: stay cool",
                    "Feels like " + u.t(maxFeels) + " around " + u.time(w.hTime[maxIdx], off, false) + " · hydrate and avoid the sun", "Extreme heat", "Feels " + u.t(maxFeels), w.hTime[maxIdx]));
        } else if (maxFeels >= 35) {
            out.add(new Insight("hot:" + day, NOTICE, Icon.SWEAT, "Hot out there: find some shade",
                    "Feels like " + u.t(maxFeels) + " around " + u.time(w.hTime[maxIdx], off, false), "Hot today", "Feels " + u.t(maxFeels), w.hTime[maxIdx]));
        }
        if (minTemp <= 0) {
            out.add(new Insight("freeze:" + day, NOTICE, Icon.SHIVER, "Brrr! Scarf weather",
                    "Down to " + u.t(minTemp) + " around " + u.time(w.hTime[minIdx], off, false), "Freezing", "Low " + u.t(minTemp), w.hTime[minIdx]));
        }

        // Wind
        double gust = Double.isNaN(n.gust) ? 0 : n.gust;
        for (int j = hi; j <= end; j++) if (!Double.isNaN(w.hWind[j])) gust = Math.max(gust, w.hWind[j] * 1.4);
        if (gust >= 60) {
            out.add(new Insight("wind:" + day, WARNING, Icon.WIND, "Hold your whiskers: strong gusts",
                    "Gusts up to " + u.speedText(gust), "Strong gusts", "Gusts " + u.speedText(gust), 0L));
        } else if (gust >= 40) {
            out.add(new Insight("wind:" + day, NOTICE, Icon.WIND, "Breezy: whiskers in the wind",
                    "Gusts up to " + u.speedText(gust), "Windy", "Gusts " + u.speedText(gust), 0L));
        }

        // UV
        double uvPeak = 0;
        int uvIdx = hi;
        for (int j = hi; j <= Math.min(hi + 10, w.hTime.length - 1); j++) {
            if (!Double.isNaN(w.hUv[j]) && w.hUv[j] > uvPeak) { uvPeak = w.hUv[j]; uvIdx = j; }
        }
        if (uvPeak >= 8) {
            out.add(new Insight("uv:" + day, WARNING, Icon.SHADES, "Shades on: " + Weather.uvLabel(uvPeak).toLowerCase() + " UV",
                    "UV " + Math.round(uvPeak) + " around " + u.time(w.hTime[uvIdx], off, false) + " · sunscreen and shade", "UV " + Math.round(uvPeak), "Sunscreen + shade", w.hTime[uvIdx]));
        } else if (uvPeak >= 6) {
            out.add(new Insight("uv:" + day, NOTICE, Icon.SHADES, "Shades on: high UV today",
                    "UV " + Math.round(uvPeak) + " around " + u.time(w.hTime[uvIdx], off, false) + " · wear sunscreen", "High UV", "UV " + Math.round(uvPeak), w.hTime[uvIdx]));
        }

        // Air quality
        if (w.aqi >= 151) {
            out.add(new Insight("aqi:" + day, WARNING, Icon.MASK, "Mask up: unhealthy air",
                    "AQI " + w.aqi + " · limit time outdoors", "Poor air", "AQI " + w.aqi, 0L));
        } else if (w.aqi >= 101) {
            out.add(new Insight("aqi:" + day, NOTICE, Icon.MASK, "Mask up: hazy air today",
                    "AQI " + w.aqi + " · " + Weather.aqiLabel(w.aqi), "Air caution", "AQI " + w.aqi, 0L));
        }

        // Fog
        if (n.code == 45 || n.code == 48) {
            out.add(new Insight("fog:" + day, NOTICE, Icon.FOG, "Foggy: drive slowly",
                    "Visibility about " + u.distance(n.visibility), "Foggy", "Vis. " + u.distance(n.visibility), 0L));
        }

        // Temperature swing
        double hiT = n.temp, loT = n.temp;
        int hiIdx = hi, loIdx = hi;
        for (int j = hi; j <= end; j++) {
            if (w.hTemp[j] > hiT) { hiT = w.hTemp[j]; hiIdx = j; }
            if (w.hTemp[j] < loT) { loT = w.hTemp[j]; loIdx = j; }
        }
        if (n.temp - loT >= 8) {
            out.add(new Insight("cool:" + day, INFO, Icon.SHIVER, "Cuddle weather later",
                    "Dropping to " + u.t(loT) + " by " + u.time(w.hTime[loIdx], off, false), "Cooler later", u.t(loT) + " by " + u.time(w.hTime[loIdx], off, false), w.hTime[loIdx]));
        } else if (hiT - n.temp >= 8) {
            out.add(new Insight("warm:" + day, INFO, Icon.SUN, "Sunbathing weather later",
                    "Climbing to " + u.t(hiT) + " by " + u.time(w.hTime[hiIdx], off, false), "Warmer later", u.t(hiT) + " by " + u.time(w.hTime[hiIdx], off, false), w.hTime[hiIdx]));
        }

        // Sun
        int di = w.dayIndex(nowSec);
        if (di < w.dSunset.length) {
            long sr = w.dSunrise[di], ss = w.dSunset[di];
            if (ss > nowSec && ss - nowSec <= 5400 && sr < nowSec) {
                out.add(new Insight("sunset:" + day, INFO, Icon.SUNSET, "Golden hour: sunset in " + Fmt.duration(ss - nowSec),
                        "Sun goes down at " + u.time(ss, off, true), "Sunset " + Fmt.duration(ss - nowSec), "At " + u.time(ss, off, true), ss));
            } else if (sr > nowSec && sr - nowSec <= 5400) {
                out.add(new Insight("sunrise:" + day, INFO, Icon.SUNRISE, "Rise and purr: sunrise in " + Fmt.duration(sr - nowSec),
                        "Sun comes up at " + u.time(sr, off, true), "Sunrise " + Fmt.duration(sr - nowSec), "At " + u.time(sr, off, true), sr));
            }
        }

        Collections.sort(out, new Comparator<Insight>() {
            @Override
            public int compare(Insight a, Insight b) {
                return b.sev - a.sev;
            }
        });
        return out;
    }

    /** The single most useful line for glanceable surfaces. */
    public static Insight headline(Weather w, Units u, long nowSec) {
        List<Insight> all = compute(w, u, nowSec);
        if (!all.isEmpty()) return all.get(0);
        if (w == null || !w.valid()) return new Insight("none", INFO, Icon.CLOUD, "No data yet", "Open Purrcast to get started", "No data", "Open Purrcast", 0L);
        Weather.Now n = w.now(nowSec);
        int hi = w.hourIndex(nowSec);
        int end = Math.min(hi + 12, w.hTime.length - 1);
        double maxPop = 0;
        for (int j = hi; j <= end; j++) maxPop = Math.max(maxPop, w.hPop[j]);
        int pun = (int) (((nowSec + w.utcOffset) / 3600L) % 4);
        String[] punTitle = {"Purrfectly calm next 12 hours", "Nap weather for the next 12 hours", "Cat approved for the next 12 hours", "Nothing to hiss at for 12 hours"};
        String[] punShort = {"Purrfect", "Nap weather", "Cat approved", "All calm"};
        return new Insight("steady", INFO, Icon.SLEEPY,
                punTitle[pun],
                "Feels like " + u.t(n.feels) + " · rain chance up to " + Math.round(maxPop) + "%", punShort[pun], "Rain up to " + Math.round(maxPop) + "%", 0L);
    }

    /** Multi-line body for the hourly notification. */
    public static String hourlyBody(Weather w, Units u, long nowSec) {
        Weather.Now n = w.now(nowSec);
        StringBuilder sb = new StringBuilder();
        sb.append("Feels like ").append(u.t(n.feels)).append(" · humidity ").append(Math.round(n.humidity)).append("%");
        Insight h = headline(w, u, nowSec);
        sb.append("\n").append(h.title);
        if (h.detail.length() > 0) sb.append(" — ").append(h.detail);
        int hi = w.hourIndex(nowSec);
        sb.append("\nNext: ");
        for (int k = 1; k <= 3 && hi + k < w.hTime.length; k++) {
            int j = hi + k;
            if (k > 1) sb.append("  ");
            sb.append(Fmt.hourTag(w.hTime[j], w.utcOffset, u.h24)).append(' ').append(u.t(w.hTemp[j]));
            if (w.hPop[j] >= 30) sb.append(" (").append(Math.round(w.hPop[j])).append("%)");
        }
        return sb.toString();
    }
}
