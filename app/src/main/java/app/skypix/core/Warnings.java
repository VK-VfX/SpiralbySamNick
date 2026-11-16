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
        public String title = "", text = "", instruction = "", detail = "";
        public boolean marine;
        public long from, to;
        public boolean forYou;
        public boolean emergency;
        public String raw = "";
    }

    /** Active warnings for {@code p} (and only those whose wording mentions the place or its state). */
    public static List<Warning> parse(String json, Place p, long nowSec) {
        return parse(json, p, nowSec, false);
    }

    /**
     * @param includeMarine also keep sea / coastal warnings that only name the state (off by default: an inland city
     *                      doesn't need "rough seas" alerts).
     */
    public static List<Warning> parse(String json, Place p, long nowSec, boolean includeMarine) {
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
            List<String> cityKeys = cityKeywords(p), stateKeys = stateKeywords(p);
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
                w.detail = bestDetail(o, w);
                String hay = (w.title + " " + w.text + " " + w.instruction + " " + w.detail).toLowerCase(Locale.ROOT);
                boolean city = false, state = false;
                for (String k : cityKeys) if (hay.contains(k)) { city = true; break; }
                for (String k : stateKeys) if (hay.contains(k)) { state = true; break; }
                w.marine = isMarine(hay);
                w.forYou = city || (state && (includeMarine || !w.marine));
                w.emergency = hay.contains("danger") || hay.contains("bahaya") || hay.contains("flood") || hay.contains("banjir")
                        || hay.contains("tsunami") || hay.contains("typhoon") || hay.contains("cyclone");
                out.add(w);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<String> cityKeywords(Place p) {
        List<String> k = new ArrayList<String>();
        if (p == null) return k;
        add(k, p.city);
        add(k, p.neighborhood);
        return k;
    }

    private static List<String> stateKeywords(Place p) {
        List<String> k = new ArrayList<String>();
        if (p == null) return k;
        add(k, p.region);
        String r = p.region == null ? "" : p.region.toLowerCase(Locale.ROOT);
        if (r.contains("kuala lumpur") || r.contains("wilayah persekutuan")) add(k, "kuala lumpur");
        return k;
    }

    private static final java.util.regex.Pattern MARINE = java.util.regex.Pattern.compile(
            "\\b(rough seas?|seas|waters|marine|coastal|waves|perairan|laut|ombak|maritime)\\b");

    /** Whole words only, so "Gombak" is not mistaken for "ombak" (waves). */
    private static boolean isMarine(String hay) {
        return MARINE.matcher(hay).find();
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
            String main = w.detail.length() > 0 ? w.detail : (norm(w.text).equals(norm(w.title)) ? "" : w.text);
            if (main.length() == 0 && norm(w.instruction).length() > 0 && !norm(w.instruction).equals(norm(w.title))) main = w.instruction;
            String body = sentence(main, 140);
            if (w.instruction.length() > 0 && !norm(w.instruction).equals(norm(main)) && !norm(w.instruction).equals(norm(w.title))) {
                body += (body.length() > 0 ? " \u00b7 " : "") + sentence(w.instruction, 80);
            }
            if (w.to > 0) body += (body.length() > 0 ? " \u00b7 " : "") + "until " + Fmt.time(w.to, 8 * 3600, false, true);
            if (body.length() == 0) body = "Issued by MET Malaysia";
            out.add(new Insights.Insight("official:" + Integer.toHexString((w.title + w.from).hashCode()),
                    w.emergency ? Insights.WARNING : Insights.NOTICE, icon,
                    "Official: " + title, body, "MET alert", title, 0L));
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The most informative English sentence in the record that isn't just the title again. */
    private static String bestDetail(JSONObject o, Warning w) {
        List<String[]> found = new ArrayList<String[]>();
        collect(o, "", found);
        String title = norm(w.title);
        String best = "";
        int bestScore = -1;
        for (String[] f : found) {
            String path = f[0].toLowerCase(Locale.ROOT), v = f[1].trim();
            String nv = norm(v);
            if (nv.length() < 25 || nv.equals(title) || (nv.startsWith(title) && nv.length() < title.length() + 12)) continue;
            if (v.indexOf(' ') < 0 || v.matches("^\\d{4}-\\d{2}.*")) continue;
            int score = v.length() / 8;
            if (path.contains("text") || path.contains("desc") || path.contains("content") || path.contains("message") || path.contains("detail")) score += 30;
            if (path.contains("instruction")) score += 18;
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return best;
    }

    private static void collect(Object node, String path, List<String[]> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String lk = k.toLowerCase(Locale.ROOT);
                // skip Malay duplicates
                if (lk.equals("bm") || lk.equals("ms") || lk.endsWith("_bm") || lk.endsWith("_ms") || lk.startsWith("bm_")) continue;
                collect(o.opt(k), path + "." + k, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && i < 20; i++) collect(a.opt(i), path + "[]", out);
        } else if (node instanceof String) {
            out.add(new String[]{path, (String) node});
        }
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
