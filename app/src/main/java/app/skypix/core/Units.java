package app.skypix.core;

import java.util.Locale;

public final class Units {
    public final boolean fahrenheit;
    public final boolean mph;
    public final boolean h24;

    public Units(boolean fahrenheit, boolean mph, boolean h24) {
        this.fahrenheit = fahrenheit;
        this.mph = mph;
        this.h24 = h24;
    }

    /** pref is "auto", "c" or "f". */
    public static Units resolve(String pref, Locale locale, boolean h24) {
        String cc = locale == null ? "" : locale.getCountry();
        boolean f;
        if ("f".equals(pref)) f = true;
        else if ("c".equals(pref)) f = false;
        else f = "US".equals(cc) || "LR".equals(cc) || "MM".equals(cc) || "BS".equals(cc)
                || "BZ".equals(cc) || "KY".equals(cc) || "PW".equals(cc);
        boolean mph = f || "GB".equals(cc);
        return new Units(f, mph, h24);
    }

    public long temp(double c) {
        double v = fahrenheit ? c * 9.0 / 5.0 + 32.0 : c;
        long r = Math.round(v);
        return r == 0 ? 0 : r;
    }

    public String t(double c) {
        return Double.isNaN(c) ? "--°" : temp(c) + "°";
    }

    public String tUnit(double c) {
        return Double.isNaN(c) ? "--°" : temp(c) + "°" + (fahrenheit ? "F" : "C");
    }

    public String unitLetter() {
        return fahrenheit ? "F" : "C";
    }

    public long speed(double kmh) {
        return Math.round(mph ? kmh * 0.621371 : kmh);
    }

    public String speedUnit() {
        return mph ? "mph" : "km/h";
    }

    public String speedText(double kmh) {
        return Double.isNaN(kmh) ? "--" : speed(kmh) + " " + speedUnit();
    }

    public String pressure(double hpa) {
        if (Double.isNaN(hpa)) return "--";
        if (mph) {
            double inhg = hpa * 0.02953;
            long r = Math.round(inhg * 100);
            return (r / 100) + "." + (r % 100 < 10 ? "0" : "") + (r % 100);
        }
        return String.valueOf(Math.round(hpa));
    }

    public String pressureUnit() {
        return mph ? "inHg" : "hPa";
    }

    public String precip(double mm) {
        if (mph) {
            double in = mm / 25.4;
            return (Math.round(in * 100) / 100.0) + " in";
        }
        return (Math.round(mm * 10) / 10.0) + " mm";
    }

    public String distance(double meters) {
        if (Double.isNaN(meters)) return "--";
        if (mph) {
            double mi = meters / 1609.34;
            return mi >= 10 ? Math.round(mi) + " mi" : (Math.round(mi * 10) / 10.0) + " mi";
        }
        double km = meters / 1000.0;
        return km >= 10 ? Math.round(km) + " km" : (Math.round(km * 10) / 10.0) + " km";
    }

    public String time(long epochSec, int off, boolean minutes) {
        return Fmt.time(epochSec, off, h24, minutes);
    }
}
