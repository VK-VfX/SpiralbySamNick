package app.skypix.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Official weather warnings (MET Malaysia open data). The feed's exact field names are read defensively:
 * several plausible layouts are accepted so a small change on the server doesn't silently break alerts.
 */
public final class Warnings {
    private Warnings() {}

    public static final class Warning {
        public String title = "", text = "", instruction = "";
        public long from, to;
        public boolean forYou;
        public boolean emergency;
        public String raw = "";
    }

    /** Active warnings for {@code p} (and only those whose wording mentions the place or its state). */
    public static List<Warning> parse(String json, Place p, long nowSec) {
        List<Warning> out = new ArrayList<Warning>();
        if (json == null || json.trim().length() == 0) return out;
        try {
            JSONArray arr;
            String t = json.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                JSONObject o = new JSONObject(t);
                arr = o.optJSONArray("data");
                if (arr == null) arr = o.optJSONArray("results");
                if (arr == null) arr = o.optJSONArray("warnings");
                if (arr == null) arr = new JSONArray();
            }
            List<String> keys = keywords(p);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Warning w = new Warning();
                w.raw = o.toString();
                w.title = first(o, "warning_issue.title_en", "title_en", "heading.en", "heading_en", "title.en", "title", "warning_issue.title");
                w.text = first(o, "text.en", "text_en", "text", "description.en", "description_en", "description", "content.en");
                w.instruction = first(o, "instruction.en", "instruction_en", "instruction");
                w.from = time(first(o, "valid_from", "validity.from", "start", "valid.from"));
                w.to = time(first(o, "valid_to", "validity.to", "end", "valid.to"));
                if (w.title.length() == 0 && w.text.length() == 0) continue;
                if (w.to != 0 && w.to < nowSec) continue;
                if (w.from != 0 && w.from > nowSec + 6 * 3600L) continue;
                String hay = (w.title + " " + w.text + " " + w.instruction).toLowerCase(Locale.ROOT);
                for (String k : keys) {
                    if (hay.contains(k)) { w.forYou = true; break; }
                }
                w.emergency = hay.contains("danger") || hay.contains("bahaya") || hay.contains("flood") || hay.contains("banjir")
                        || hay.contains("tsunami") || hay.contains("typhoon") || hay.contains("cyclone");
                out.add(w);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<String> keywords(Place p) {
        List<String> k = new ArrayList<String>();
        if (p == null) return k;
        add(k, p.city);
        add(k, p.neighborhood);
        add(k, p.region);
        String r = p.region == null ? "" : p.region.toLowerCase(Locale.ROOT);
        if (r.contains("kuala lumpur") || r.contains("wilayah persekutuan")) add(k, "kuala lumpur");
        return k;
    }

    private static void add(List<String> k, String s) {
        if (s == null) return;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.length() >= 4 && !k.contains(s)) k.add(s);
    }

    /** Warnings for this place as ordinary Insights (so widgets, notifications and the app all show them). */
    public static List<Insights.Insight> toInsights(List<Warning> list) {
        List<Insights.Insight> out = new ArrayList<Insights.Insight>();
        for (Warning w : list) {
            if (!w.forYou) continue;
            String hay = (w.title + " " + w.text).toLowerCase(Locale.ROOT);
            Icon icon = hay.contains("flood") || hay.contains("banjir") ? Icon.ALERT
                    : hay.contains("thunder") || hay.contains("ribut") ? Icon.STORM
                    : hay.contains("rain") || hay.contains("hujan") ? Icon.UMBRELLA
                    : hay.contains("wind") || hay.contains("angin") ? Icon.WIND
                    : hay.contains("hot") || hay.contains("heat") || hay.contains("panas") ? Icon.SWEAT
                    : hay.contains("haze") || hay.contains("jerebu") ? Icon.MASK : Icon.ALERT;
            String title = w.title.length() > 0 ? w.title : "Weather warning";
            if (title.length() > 70) title = title.substring(0, 69).trim() + "…";
            String body = sentence(w.text.length() > 0 ? w.text : w.instruction, 150);
            if (w.instruction.length() > 0 && w.text.length() > 0) body += " · " + sentence(w.instruction, 90);
            out.add(new Insights.Insight("official:" + Integer.toHexString((w.title + w.from).hashCode()),
                    w.emergency ? Insights.WARNING : Insights.NOTICE, icon,
                    "Official: " + title, body, "MET alert", title, 0L));
        }
        return out;
    }

    private static String sentence(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        int dot = s.indexOf(". ");
        if (dot > 20 && dot < max) s = s.substring(0, dot + 1);
        return s.length() > max ? s.substring(0, max - 1).trim() + "…" : s;
    }

    // ---- JSON helpers ----
    private static String first(JSONObject o, String... paths) {
        for (String p : paths) {
            String v = at(o, p);
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        return "";
    }

    private static String at(JSONObject o, String path) {
        Object cur = o;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(part);
            if (cur == null || cur == JSONObject.NULL) return null;
        }
        if (cur instanceof JSONObject) {
            Object en = ((JSONObject) cur).opt("en");
            return en instanceof String ? (String) en : null;
        }
        return cur instanceof String ? (String) cur : String.valueOf(cur);
    }

    /** ISO-ish date time to epoch seconds; text without a zone is taken as Malaysian time (UTC+8). 0 if unreadable. */
    static long time(String s) {
        if (s == null || s.length() < 10) return 0;
        try {
            String t = s.trim().replace(' ', 'T');
            int y = Integer.parseInt(t.substring(0, 4)), mo = Integer.parseInt(t.substring(5, 7)), d = Integer.parseInt(t.substring(8, 10));
            int h = 0, mi = 0, se = 0;
            if (t.length() >= 16) { h = Integer.parseInt(t.substring(11, 13)); mi = Integer.parseInt(t.substring(14, 16)); }
            if (t.length() >= 19 && Character.isDigit(t.charAt(17))) se = Integer.parseInt(t.substring(17, 19));
            int off = 8 * 3600;
            int idx = Math.max(t.lastIndexOf('+'), t.lastIndexOf('-'));
            if (t.endsWith("Z")) off = 0;
            else if (idx > 10 && t.length() >= idx + 3) {
                int sign = t.charAt(idx) == '-' ? -1 : 1;
                int oh = Integer.parseInt(t.substring(idx + 1, idx + 3));
                int om = t.length() >= idx + 6 ? Integer.parseInt(t.substring(idx + 4, idx + 6)) : 0;
                off = sign * (oh * 3600 + om * 60);
            }
            java.util.GregorianCalendar c = new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(y, mo - 1, d, h, mi, se);
            return c.getTimeInMillis() / 1000L - off;
        } catch (Exception e) {
            return 0;
        }
    }
}
