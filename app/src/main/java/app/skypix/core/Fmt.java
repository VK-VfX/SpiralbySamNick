package app.skypix.core;

/** Date/time helpers that work on epoch seconds plus a fixed UTC offset, with no java.time dependency. */
public final class Fmt {
    private Fmt() {}

    public static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    public static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Parses "2026-11-15T14:00" or "2026-11-15" (location-local) into epoch seconds. */
    public static long parseLocal(String iso, int offsetSec) {
        if (iso == null || iso.length() < 10) return 0;
        int y = Integer.parseInt(iso.substring(0, 4));
        int mo = Integer.parseInt(iso.substring(5, 7));
        int d = Integer.parseInt(iso.substring(8, 10));
        int h = 0, mi = 0;
        if (iso.length() >= 16) {
            h = Integer.parseInt(iso.substring(11, 13));
            mi = Integer.parseInt(iso.substring(14, 16));
        }
        return daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L - offsetSec;
    }

    public static long epochDay(long epochSec, int off) {
        return Math.floorDiv(epochSec + off, 86400L);
    }

    public static int dow(long epochSec, int off) {
        return (int) Math.floorMod(epochDay(epochSec, off) + 4, 7L);
    }

    public static int hour(long epochSec, int off) {
        return (int) (Math.floorMod(epochSec + off, 86400L) / 3600L);
    }

    public static int minute(long epochSec, int off) {
        return (int) (Math.floorMod(epochSec + off, 3600L) / 60L);
    }

    /** "3 PM", "3:05 PM" or "15:05". */
    public static String time(long epochSec, int off, boolean h24, boolean showMinutes) {
        int h = hour(epochSec, off), m = minute(epochSec, off);
        if (h24) return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
        int hh = h % 12 == 0 ? 12 : h % 12;
        String s = showMinutes || m != 0 ? hh + ":" + (m < 10 ? "0" : "") + m : String.valueOf(hh);
        return s + (h < 12 ? " AM" : " PM");
    }

    /** Compact hour label for pixel text: "3PM" or "15". */
    public static String hourTag(long epochSec, int off, boolean h24) {
        int h = hour(epochSec, off);
        if (h24) return (h < 10 ? "0" : "") + h + ":00";
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + (h < 12 ? "AM" : "PM");
    }

    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        if (h == 0) return m + "m";
        return h + "h " + (m < 10 ? "0" : "") + m + "m";
    }

    public static String ago(long seconds) {
        if (seconds < 90) return "just now";
        if (seconds < 3600) return (seconds / 60) + " min ago";
        if (seconds < 86400) return (seconds / 3600) + " h ago";
        return (seconds / 86400) + " d ago";
    }

    public static String compass(double deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8L);
        return n[i];
    }

    public static String coord(double v, boolean lat) {
        String hemi = lat ? (v >= 0 ? "N" : "S") : (v >= 0 ? "E" : "W");
        double a = Math.abs(v);
        long whole = (long) a;
        long frac = Math.round((a - whole) * 10000);
        if (frac >= 10000) { whole++; frac -= 10000; }
        String f = String.valueOf(frac);
        while (f.length() < 4) f = "0" + f;
        return whole + "." + f + " " + hemi;
    }
}
