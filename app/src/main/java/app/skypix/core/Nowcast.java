package app.skypix.core;

/** "Rain in 15 minutes": a short-range read of the 15-minute precipitation series. */
public final class Nowcast {
    private Nowcast() {}

    public static final int DRY = 0, RAINING = 1, STARTS = 2;

    /** mm per 15 min that counts as rain (light drizzle included). */
    public static final double WET = 0.08;

    public static final class Result {
        public int state;
        /** STARTS: minutes until rain begins. RAINING: minutes until it stops, or -1 if it carries on beyond the window. */
        public int minutes = -1;
        /** Heaviest rate in the relevant stretch, mm per hour. */
        public double peakRate;
        /** Rain expected over the next hour, mm. */
        public double nextHourMm;
        public long startsAt;
        public long endsAt;
        public int windowMinutes;
    }

    /** Null when the forecast has no 15-minute data. */
    public static Result compute(Weather w, long nowSec) {
        if (w == null || w.mTime == null || w.mTime.length < 3) return null;
        int n = w.mTime.length;
        int cur = -1;
        for (int i = 0; i < n; i++) {
            if (nowSec >= w.mTime[i] - 900 && nowSec < w.mTime[i]) { cur = i; break; }
        }
        if (cur < 0) {
            // series started later than now, or it is stale: use the first bin that has not finished yet
            for (int i = 0; i < n; i++) if (w.mTime[i] > nowSec) { cur = i; break; }
            if (cur < 0) return null;
        }
        Result r = new Result();
        r.windowMinutes = (n - cur) * 15;
        for (int i = cur; i < Math.min(n, cur + 4); i++) r.nextHourMm += Math.max(0, w.mPrecip[i]);
        boolean wetNow = w.mPrecip[cur] >= WET;
        if (wetNow) {
            r.state = RAINING;
            double peak = 0;
            int stop = -1;
            for (int i = cur; i < n; i++) {
                peak = Math.max(peak, w.mPrecip[i] * 4);
                if (w.mPrecip[i] < WET && (i + 1 >= n || w.mPrecip[i + 1] < WET)) { stop = i; break; }
            }
            r.peakRate = peak;
            if (stop >= 0) {
                r.endsAt = w.mTime[stop] - 900;
                r.minutes = (int) Math.max(5, (r.endsAt - nowSec + 30) / 60);
            }
            return r;
        }
        for (int i = cur + 1; i < n; i++) {
            if (w.mPrecip[i] >= WET) {
                r.state = STARTS;
                r.startsAt = w.mTime[i] - 900;
                r.minutes = (int) Math.max(5, (r.startsAt - nowSec + 30) / 60);
                double peak = 0;
                for (int j = i; j < Math.min(n, i + 4); j++) peak = Math.max(peak, w.mPrecip[j] * 4);
                r.peakRate = peak;
                return r;
            }
        }
        r.state = DRY;
        return r;
    }

    /** Light / moderate / heavy wording for a rate in mm per hour. */
    public static String intensity(double mmPerHour) {
        if (mmPerHour >= 50) return "Violent rain";
        if (mmPerHour >= 7.6) return "Heavy rain";
        if (mmPerHour >= 2.5) return "Moderate rain";
        return "Light rain";
    }

    /** "15 min" style, rounded to the nearest 5. */
    public static String minutesText(int minutes) {
        int m = Math.max(5, Math.round(minutes / 5f) * 5);
        if (m >= 60) {
            int h = m / 60, rest = m % 60;
            return rest == 0 ? h + " h" : h + " h " + rest + " min";
        }
        return m + " min";
    }
}
