package app.skypix.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import app.skypix.core.Cards;
import app.skypix.core.Place;
import app.skypix.core.Weather;

import java.util.ArrayList;
import java.util.List;

/** Places the user saved (Home, Work ...), their cached forecasts, and which place each widget shows. */
final class Places {
    private Places() {}

    static final class Saved {
        String id;
        String name;
        Place place;
    }

    private static SharedPreferences sp(Context c) {
        return Store.prefs(c);
    }

    static synchronized List<Saved> list(Context c) {
        List<Saved> out = new ArrayList<Saved>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("saved_places", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Saved s = new Saved();
                s.id = o.getString("id");
                s.name = o.getString("name");
                s.place = Place.fromJson(o.getJSONObject("place").toString());
                if (s.place != null) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Saved> list) {
        JSONArray a = new JSONArray();
        try {
            for (Saved s : list) {
                a.put(new JSONObject().put("id", s.id).put("name", s.name).put("place", s.place.toJson()));
            }
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("saved_places", a.toString()).apply();
    }

    static synchronized Saved add(Context c, String name, Place p) {
        List<Saved> list = list(c);
        Saved s = new Saved();
        s.id = "p" + System.currentTimeMillis();
        s.name = name.trim().length() == 0 ? p.shortName() : name.trim();
        s.place = p;
        // the card shows this name, so "Home" reads as Home, not as a street
        s.place.city = s.name;
        s.place.neighborhood = "";
        list.add(s);
        write(c, list);
        return s;
    }

    static synchronized void remove(Context c, String id) {
        List<Saved> list = list(c);
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) list.remove(i--);
        write(c, list);
        sp(c).edit().remove("pw_" + id + "_fc").remove("pw_" + id + "_air").remove("pw_" + id + "_at").apply();
    }

    static Saved get(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        for (Saved s : list(c)) if (s.id.equals(id)) return s;
        return null;
    }

    // ---- which place a widget shows ("" = wherever you are) ----
    static String widgetPlace(Context c, int widgetId) {
        return sp(c).getString("wp_" + widgetId, "");
    }

    static void setWidgetPlace(Context c, int widgetId, String placeId) {
        sp(c).edit().putString("wp_" + widgetId, placeId == null ? "" : placeId).apply();
    }

    static void forgetWidget(Context c, int widgetId) {
        sp(c).edit().remove("wp_" + widgetId).remove("wbg_" + widgetId).apply();
    }

    /** Background for one widget: 0 solid, 1 glass, 2 clear; -1 = follow the app default. */
    static int widgetBg(Context c, int widgetId) {
        return sp(c).getInt("wbg_" + widgetId, -1);
    }

    static void setWidgetBg(Context c, int widgetId, int mode) {
        sp(c).edit().putInt("wbg_" + widgetId, mode).apply();
    }

    static int effectiveBg(Context c, int widgetId) {
        int m = widgetBg(c, widgetId);
        return m >= 0 ? m : Store.bgMode(c);
    }

    /** Applies a background mode to card data. */
    static void applyBg(Cards.Data d, int mode) {
        d.transparentDark = mode == 2;
        d.bgAlpha = mode == 1 ? 0.6f : 1f;
    }

    // ---- forecast cache per saved place ----
    static void saveWeather(Context c, String id, String fc, String air, long at) {
        sp(c).edit().putString("pw_" + id + "_fc", fc).putString("pw_" + id + "_air", air == null ? "" : air).putLong("pw_" + id + "_at", at).apply();
    }

    static long fetchedAt(Context c, String id) {
        return sp(c).getLong("pw_" + id + "_at", 0);
    }

    static Weather loadWeather(Context c, String id) {
        String fc = sp(c).getString("pw_" + id + "_fc", null);
        if (fc == null) return null;
        try {
            Weather w = Weather.parse(fc, sp(c).getString("pw_" + id + "_air", ""), sp(c).getLong("pw_" + id + "_at", 0));
            return w.valid() ? w : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Everything a widget needs for the place it was set to. */
    static Cards.Data dataForWidget(Context c, int widgetId) {
        String pid = widgetPlace(c, widgetId);
        Saved s = get(c, pid);
        Cards.Data d = Store.data(c);
        if (s != null) {
            d.place = s.place;
            d.weather = loadWeather(c, s.id);
        }
        applyBg(d, effectiveBg(c, widgetId));
        return d;
    }
}
