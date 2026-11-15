package app.skypix.core;

import java.util.List;

/**
 * The words of Purrcast's notifications: a cat-voiced "weather news" bulletin, the morning briefing and the
 * practical tip that goes with every alert. Everything is written from the live forecast numbers; nothing is invented.
 */
public final class CatNews {
    private CatNews() {}

    public static final class Bulletin {
        public final Icon icon;
        public final String title;
        public final String text;
        public final String big;

        Bulletin(Icon icon, String title, String text, String big) {
            this.icon = icon;
            this.title = title;
            this.text = text;
            this.big = big;
        }
    }

    private static final String[] SIGN = {
            "Stay pawsitive!", "Purr-fect time to check in.", "Meow for now.", "Fur real, keep an eye on the sky.", "Nap when you can."};
    private static final String[] MORNING_SIGN = {
            "Have a purr-fect Sunday!", "Make it a pawsome Monday!", "Tuesday is for whisker-twitching adventures.",
            "Halfway there. Keep your paws up!", "Thursday: nearly there, keep purring!", "Friday! Time to stretch and celebrate.",
            "Saturday: nap, play, repeat."};

    private static String cat(Icon ic) {
        return Glyphs.catEmoji(ic);
    }

    private static String cap(String s) {
        return s == null || s.length() == 0 ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** One practical sentence for an insight: what to actually do. */
    public static String tip(Insights.Insight in) {
        String k = in.key;
        if (k.startsWith("official:")) return "Follow MET Malaysia's advice and stay safe.";
        if (k.startsWith("flood-watch")) return "Avoid low-lying roads and bring an umbrella.";
        if (k.startsWith("flood")) return "Stay out of flood water, move to higher ground if it rises, and don't get wet.";
        if (k.startsWith("storm")) return "Stay indoors, keep away from windows and unplug sensitive electronics.";
        if (k.startsWith("wet-now")) return "Keep your umbrella handy and don't get wet.";
        if (k.startsWith("wet:")) return "Grab an umbrella or raincoat so you don't get wet.";
        if (k.startsWith("heat")) return "Drink water, stay in the shade and check on pets and neighbours.";
        if (k.startsWith("hot")) return "Drink plenty of water and take breaks in the shade.";
        if (k.startsWith("freeze")) return "Layer up and keep pets warm.";
        if (k.startsWith("wind")) return "Secure loose items and watch for falling branches.";
        if (k.startsWith("uv")) return "Sunscreen, a hat and shade.";
        if (k.startsWith("aqi")) return "Limit time outdoors; a mask helps.";
        if (k.startsWith("fog")) return "Drive slowly with low beams on.";
        return "";
    }

    /** Title for an alert notification: emergencies get the worried cat. */
    public static String alertTitle(Insights.Insight in) {
        return cat(in.sev >= Insights.WARNING ? Icon.STORM : in.icon) + " " + in.title;
    }

    public static String alertBody(Insights.Insight in) {
        String t = tip(in);
        return t.length() == 0 ? in.detail : in.detail + "\n" + t;
    }

    /** The "Cat News" bulletin sent every two hours. */
    public static Bulletin news(Weather w, Units u, String place, long nowSec) {
        Weather.Now n = w.now(nowSec);
        int off = w.utcOffset;
        int hi = w.hourIndex(nowSec);
        int end = Math.min(hi + 12, w.hTime.length - 1);
        List<Insights.Insight> all = Insights.compute(w, u, nowSec);
        Icon icon = Wmo.icon(n.code, n.isDay);
        String title, text;
        Insights.Insight top = all.isEmpty() ? null : all.get(0);
        if (top != null && top.sev >= Insights.NOTICE) {
            icon = top.sev >= Insights.WARNING ? Icon.STORM : top.icon;
            title = cat(icon) + " Cat News: " + top.title;
            text = top.detail;
        } else {
            title = cat(icon) + " Cat News: " + cap(Wmo.label(n.code)) + ", " + u.t(n.temp);
            text = "Calm outlook for the next 12 hours · feels like " + u.t(n.feels);
        }

        StringBuilder b = new StringBuilder();
        b.append(cat(Wmo.icon(n.code, n.isDay))).append(' ').append(place).append(" now: ").append(u.t(n.temp))
                .append(" (feels ").append(u.t(n.feels)).append("), ").append(Wmo.label(n.code)).append('.');

        // the next few hours
        StringBuilder nx = new StringBuilder();
        for (int k = 2; k <= 6 && hi + k <= end + 1 && hi + k < w.hTime.length; k += 2) {
            int j = hi + k;
            if (nx.length() > 0) nx.append(" · ");
            nx.append(u.time(w.hTime[j], off, false)).append(' ').append(u.t(w.hTemp[j]));
            if (w.hPop[j] >= 30) nx.append(" (").append(Math.round(w.hPop[j])).append("%)");
        }
        if (nx.length() > 0) b.append("\nNext: ").append(nx);

        // rain outlook
        int first = -1;
        double peak = 0;
        double mm = 0;
        for (int j = hi + 1; j <= end; j++) {
            boolean wet = Wmo.isWet(w.hCode[j]) || Wmo.isSnow(w.hCode[j]) || (w.hPop[j] >= 50 && w.hPrecip[j] >= 0.1);
            if (wet) {
                if (first < 0) first = j;
                peak = Math.max(peak, w.hPop[j]);
                mm += Double.isNaN(w.hPrecip[j]) ? 0 : w.hPrecip[j];
            }
        }
        if (first >= 0) {
            b.append(Wmo.isSnow(w.hCode[first]) ? "\nSnow from " : "\nRain from ").append(u.time(w.hTime[first], off, false)).append(" · up to ")
                    .append(Math.round(peak)).append("% · about ").append(u.precip(mm));
        } else {
            b.append("\nNo rain expected in the next 12 hours.");
        }

        int di = w.dayIndex(nowSec);
        if (di < w.dMax.length) {
            b.append("\nToday: high ").append(u.t(w.dMax[di])).append(" · low ").append(u.t(w.dMin[di]));
            if (di < w.dUv.length && !Double.isNaN(w.dUv[di])) b.append(" · UV ").append(Math.round(w.dUv[di]));
            if (w.aqi >= 0) b.append(" · air ").append(Weather.aqiLabel(w.aqi).toLowerCase());
        }

        int tips = 0;
        for (Insights.Insight in : all) {
            if (in.sev < Insights.NOTICE) continue;
            String t = tip(in);
            if (t.length() == 0) continue;
            b.append(tips == 0 ? "\n" : " ").append(t);
            if (++tips == 2) break;
        }
        if (tips == 0) {
            b.append(n.isDay && n.temp >= 30 ? "\nPerfect weather for a nap in a sunny spot." : "\nNothing to hiss at. Enjoy!");
        }
        b.append('\n').append(cat(Icon.SUN)).append(' ').append(SIGN[(int) ((nowSec / 7200L) % SIGN.length)]);
        return new Bulletin(icon, title, text, b.toString());
    }

    /** The morning briefing: what the day looks like and what to carry. */
    public static Bulletin morning(Weather w, Units u, String place, long nowSec) {
        int off = w.utcOffset;
        int di = w.dayIndex(nowSec);
        int hi = w.hourIndex(nowSec);
        double hiT = w.dMax[di], loT = w.dMin[di], pop = w.dPop[di];
        Icon icon = Wmo.icon(w.dCode[di], true);
        String label = Wmo.label(w.dCode[di]);

        // first rainy hour in the next 14 hours
        int rainJ = -1;
        double rainPeak = 0;
        int end = Math.min(hi + 14, w.hTime.length - 1);
        for (int j = hi; j <= end; j++) {
            boolean wet = Wmo.isWet(w.hCode[j]) || Wmo.isSnow(w.hCode[j]) || (w.hPop[j] >= 50 && w.hPrecip[j] >= 0.1);
            if (wet) {
                if (rainJ < 0) rainJ = j;
                rainPeak = Math.max(rainPeak, w.hPop[j]);
            }
        }
        String umbrella;
        String umbrellaShort;
        if (rainJ >= 0) {
            umbrella = "Bring an umbrella: rain likely around " + u.time(w.hTime[rainJ], off, false) + " (" + Math.round(rainPeak) + "%). Don't get wet!";
            umbrellaShort = "bring an umbrella";
        } else if (pop >= 30) {
            umbrella = "A small chance of rain (" + Math.round(pop) + "%): keep a little umbrella handy.";
            umbrellaShort = "maybe an umbrella";
        } else {
            umbrella = "No umbrella needed today.";
            umbrellaShort = "no umbrella needed";
        }

        String title = cat(icon) + " Good morning! " + cap(label) + ", high " + u.t(hiT);
        String text = "Low " + u.t(loT) + " · rain " + Math.round(pop) + "% · " + umbrellaShort;

        StringBuilder b = new StringBuilder();
        b.append("Good morning, ").append(place).append("! Today: ").append(label).append(", high ").append(u.t(hiT))
                .append(", low ").append(u.t(loT)).append('.');
        b.append('\n').append(umbrella);
        for (Insights.Insight in : Insights.compute(w, u, nowSec)) {
            if (in.sev >= Insights.WARNING) {
                b.append("\nHeads-up: ").append(in.title).append(". ").append(tip(in));
                break;
            }
        }
        double uv = 0;
        int uvJ = hi;
        for (int j = hi; j <= end; j++) {
            if (!Double.isNaN(w.hUv[j]) && w.hUv[j] > uv) { uv = w.hUv[j]; uvJ = j; }
        }
        if (uv >= 3) {
            b.append("\nUV peaks at ").append(Math.round(uv)).append(" (").append(Weather.uvLabel(uv).toLowerCase()).append(") around ")
                    .append(u.time(w.hTime[uvJ], off, false)).append(uv >= 6 ? ": sunscreen and shade." : ".");
        }
        if (di < w.dSunrise.length) {
            b.append("\nSunrise ").append(u.time(w.dSunrise[di], off, true)).append(" · sunset ").append(u.time(w.dSunset[di], off, true));
        }
        b.append('\n').append(hiT >= 32 ? "Wear something light and stay hydrated."
                : hiT <= 18 ? "Wear a jacket, it's a cool one." : "Comfortable layers will do.");
        b.append('\n').append(cat(Icon.SUN)).append(' ').append(MORNING_SIGN[Math.floorMod(Fmt.dow(nowSec, off), 7)]);
        return new Bulletin(icon, title, text, b.toString());
    }
}
