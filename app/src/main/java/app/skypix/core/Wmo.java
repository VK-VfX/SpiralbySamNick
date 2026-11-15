package app.skypix.core;

/** Maps WMO weather codes (as returned by Open-Meteo) to icons and labels. */
public final class Wmo {
    private Wmo() {}

    public static Icon icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? Icon.SUN : Icon.MOON;
        if (code == 2) return day ? Icon.PARTLY_DAY : Icon.PARTLY_NIGHT;
        if (code == 3) return Icon.CLOUD;
        if (code == 45 || code == 48) return Icon.FOG;
        if (code >= 51 && code <= 57) return Icon.DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return Icon.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Icon.SNOW;
        if (code >= 95) return Icon.STORM;
        return Icon.CLOUD;
    }

    public static String label(int code) {
        switch (code) {
            case 0: return "clear";
            case 1: return "mostly clear";
            case 2: return "partly cloudy";
            case 3: return "overcast";
            case 45: return "fog";
            case 48: return "rime fog";
            case 51: return "light drizzle";
            case 53: return "drizzle";
            case 55: return "heavy drizzle";
            case 56: case 57: return "freezing drizzle";
            case 61: return "light rain";
            case 63: return "rain";
            case 65: return "heavy rain";
            case 66: case 67: return "freezing rain";
            case 71: return "light snow";
            case 73: return "snow";
            case 75: return "heavy snow";
            case 77: return "snow grains";
            case 80: return "light showers";
            case 81: return "showers";
            case 82: return "violent showers";
            case 85: return "snow showers";
            case 86: return "heavy snow showers";
            case 95: return "thunderstorm";
            case 96: case 99: return "thunderstorm, hail";
            default: return "unknown";
        }
    }

    public static boolean isWet(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    public static boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    public static boolean isStorm(int code) {
        return code >= 95;
    }
}
