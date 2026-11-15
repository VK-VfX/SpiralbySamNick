package app.skypix.android;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import app.skypix.core.Cards;
import app.skypix.core.Fmt;
import app.skypix.core.Gfx;
import app.skypix.core.Insights;
import app.skypix.core.Place;

import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1, REQ_BACKGROUND = 2, REQ_NOTIFY = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout content;
    private Ui.PixelText statusText;
    private boolean refreshing;
    private int pendingScrollKind = -1;
    private final HashMap<Integer, View> cardByKind = new HashMap<Integer, View>();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            rebuild();
            ui.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.addView(new Ui.Backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applySystemBars();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        pendingScrollKind = i != null && i.hasExtra("from_widget") ? i.getIntExtra("from_widget", -1) : -1;
    }

    private void applySystemBars() {
        boolean dark = Ui.isDark(this);
        int bg = dark ? 0xFF000000 : 0xFFE7EDF8;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        applySystemBars();
        rebuild();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Store.visible = true;
        Notifier.ensureChannels(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
        Jobs.sync(this);
        startRefresh(false);
        ui.postDelayed(ticker, 60000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onStop() {
        Store.visible = false;
        super.onStop();
    }

    // ---- refresh ----------------------------------------------------------------------------------------------------

    private void startRefresh(final boolean force) {
        if (refreshing || !Store.anyLocation(this)) return;
        refreshing = true;
        setStatus("UPDATING...");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Updater.run(app, true, 12000, force ? 0 : 8 * 60 * 1000L);
                } catch (Throwable ignored) {
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        rebuild();
                    }
                });
            }
        }, "skypix-refresh").start();
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.set(s);
    }

    // ---- screen -----------------------------------------------------------------------------------------------------

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    private void rebuild() {
        final int keepY = scroll.getScrollY();
        content.removeAllViews();
        cardByKind.clear();
        boolean dark = Ui.isDark(this);
        int ink = dark ? 0xFFFFFFFF : 0xFF0E1630;
        int ink2 = dark ? 0x99FFFFFF : 0x990E1630;
        final Cards.Data d = Store.data(this);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(new Ui.PixelText(this, "SKYPIX", 3.2f, ink, Gfx.LEFT));
        statusText = new Ui.PixelText(this, statusLine(d), 1.3f, ink2, Gfx.LEFT);
        titles.addView(statusText, lp(-2, -2, 8));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.PixelButton refresh = new Ui.PixelButton(this, refreshing ? "..." : "REFRESH", false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Store.anyLocation(MainActivity.this)) requestLocation();
                else startRefresh(true);
            }
        });
        head.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        content.addView(head, lp(-1, -2, 0));

        addBanners();

        if (d.ready()) {
            addCard(Cards.NOW_WIDE, 150, null, d, 16);
            List<Insights.Insight> notes = Insights.compute(d.weather, d.units, d.nowSec);
            int shown = 0;
            for (Insights.Insight in : notes) {
                if (shown >= 4) break;
                content.addView(new Ui.CardView(this, Cards.HEADSUP, 68, in, d), lp(-1, -2, 10));
                shown++;
            }
            if (shown == 0) addCard(Cards.HEADSUP, 68, null, d, 10);
            addCard(Cards.HOURLY, 150, null, d, 10);
            addCard(Cards.FORECAST, 236, null, d, 10);
            addCard(Cards.DETAILS, 196, null, d, 10);
            addCard(Cards.SUN, 150, null, d, 10);
            addCard(Cards.LOCATION, 150, null, d, 10);
        }

        addSettings(ink, ink2);
        addWidgetPicker(ink, ink2);

        TextView foot = new TextView(this);
        foot.setText("Weather & air quality: Open-Meteo.com (CC BY 4.0)\nPlace names: © OpenStreetMap contributors\nNo accounts, no analytics, no Google services.");
        foot.setTextSize(11);
        foot.setTextColor(ink2);
        foot.setLineSpacing(0, 1.2f);
        content.addView(foot, lp(-1, -2, 26));

        if (pendingScrollKind < 0) scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(0, keepY); }
        });
        if (pendingScrollKind >= 0) {
            final View target = cardByKind.get(pendingScrollKind);
            pendingScrollKind = -1;
            if (target != null) scroll.post(new Runnable() {
                @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, target.getTop() - Ui.dp(MainActivity.this, 8))); }
            });
        }
    }

    private void addCard(int kind, float h, Insights.Insight in, Cards.Data d, float topDp) {
        Ui.CardView v = new Ui.CardView(this, kind, h, in, d);
        content.addView(v, lp(-1, -2, topDp));
        if (!cardByKind.containsKey(kind)) cardByKind.put(kind, v);
    }

    private String statusLine(Cards.Data d) {
        if (!Store.anyLocation(this)) return "LOCATION NEEDED";
        if (refreshing) return "UPDATING...";
        if (d.place == null) return "FINDING YOU...";
        long fetched = Store.fetchedAt(this);
        StringBuilder sb = new StringBuilder();
        if (fetched > 0) sb.append("UPD ").append(Fmt.ago((System.currentTimeMillis() - fetched) / 1000L).toUpperCase());
        if (!Float.isNaN(d.place.accuracyM)) sb.append("  ±").append(Math.round(d.place.accuracyM)).append("M");
        return sb.length() == 0 ? "READY" : sb.toString();
    }

    // ---- banners ----------------------------------------------------------------------------------------------------

    private void banner(String title, String body, String button, View.OnClickListener action) {
        boolean dark = Ui.isDark(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 22));
        bg.setColor(dark ? 0xFF000000 : 0xF2142A5E);
        bg.setStroke(Ui.dp(this, 1), dark ? 0x33FFFFFF : 0x33FFFFFF);
        box.setBackground(bg);
        box.addView(new Ui.PixelText(this, title, 2f, 0xFFFFFFFF, Gfx.LEFT));
        TextView t = new TextView(this);
        t.setText(body);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(13.5f);
        t.setLineSpacing(0, 1.15f);
        box.addView(t, lp(-1, -2, 10));
        Ui.PixelButton b = new Ui.PixelButton(this, button, true);
        b.setOnClickListener(action);
        box.addView(b, lp(-2, -2, 10));
        content.addView(box, lp(-1, -2, 16));
    }

    private void addBanners() {
        if (!Store.anyLocation(this)) {
            banner("WHERE ARE YOU?", "Skypix only needs your location. It is used to show weather for the exact spot you are standing, and it never leaves your phone except as coordinates sent to the weather service.",
                    "ALLOW LOCATION", new View.OnClickListener() {
                        @Override public void onClick(View v) { requestLocation(); }
                    });
            return;
        }
        if (!Store.fine(this)) {
            banner("APPROXIMATE ONLY", "Android is only sharing a rough area. Switch Location to \"Precise\" for street-level accuracy.",
                    "OPEN SETTINGS", new View.OnClickListener() {
                        @Override public void onClick(View v) { openAppSettings(); }
                    });
        }
        if (!locationServicesOn()) {
            banner("LOCATION IS OFF", "Turn on location services so Skypix can find where you are.",
                    "OPEN SETTINGS", new View.OnClickListener() {
                        @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                    });
        }
        if (Build.VERSION.SDK_INT >= 33 && !Store.notificationsAllowed(this) && (Store.alerts(this) || Store.hourly(this))) {
            banner("TURN ON ALERTS", "Allow notifications to get weather alerts and the hourly briefing.",
                    "ALLOW", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                        }
                    });
        }
    }

    private boolean locationServicesOn() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void requestLocation() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        Jobs.sync(this);
        if (code == REQ_LOCATION && Store.anyLocation(this)) {
            Jobs.kick(this);
            startRefresh(true);
        }
        rebuild();
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    private LinearLayout section(String title, int ink) {
        content.addView(new Ui.PixelText(this, title, 2f, ink, Gfx.LEFT), lp(-2, -2, 30));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 22));
        boolean dark = Ui.isDark(this);
        bg.setColor(dark ? 0xFF000000 : 0xF2142A5E);
        bg.setStroke(Ui.dp(this, 1), dark ? 0x33FFFFFF : 0x22FFFFFF);
        box.setBackground(bg);
        content.addView(box, lp(-1, -2, 10));
        return box;
    }

    private LinearLayout row(LinearLayout parent, String title, String sub, View control) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        text.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0xB3FFFFFF);
            s.setTextSize(12);
            text.addView(s);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        if (control != null) r.addView(control, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(r, lp(-1, -2, parent.getChildCount() == 0 ? 0 : 6));
        return r;
    }

    private Switch toggle(boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setChecked(on);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private void addSettings(int ink, int ink2) {
        LinearLayout box = section("SETTINGS", ink);

        // units
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        String cur = Store.tempPref(this);
        String[][] opts = {{"AUTO", "auto"}, {"°C", "c"}, {"°F", "f"}};
        for (final String[] o : opts) {
            Ui.PixelButton b = new Ui.PixelButton(this, o[0], cur.equals(o[1]));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Store.setTempPref(MainActivity.this, o[1]);
                    WidgetUpdater.updateAll(MainActivity.this);
                    rebuild();
                }
            });
            seg.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        row(box, "Units", "Temperature, wind and pressure", seg);

        LinearLayout style = new LinearLayout(this);
        style.setOrientation(LinearLayout.HORIZONTAL);
        for (final boolean clearOpt : new boolean[]{false, true}) {
            Ui.PixelButton b = new Ui.PixelButton(this, clearOpt ? "CLEAR" : "BLACK", Store.clearDark(this) == clearOpt);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Store.setClearDark(MainActivity.this, clearOpt);
                    WidgetUpdater.updateAll(MainActivity.this);
                    rebuild();
                }
            });
            style.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        row(box, "Dark mode widgets", "AMOLED black, or fully see-through", style);

        row(box, "Weather alerts", "Rain, storms, heat, UV, wind, air quality",
                toggle(Store.alerts(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setAlerts(MainActivity.this, on);
                        Jobs.sync(MainActivity.this);
                        if (on) askNotifications();
                    }
                }));

        row(box, "Hourly briefing", "A quiet summary at the top of every hour",
                toggle(Store.hourly(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setHourly(MainActivity.this, on);
                        Jobs.sync(MainActivity.this);
                        if (on) askNotifications();
                    }
                }));

        row(box, "Quiet hours", "No hourly briefing " + hourLabel(Store.quietStart(this)) + " – " + hourLabel(Store.quietEnd(this)),
                toggle(Store.quiet(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setQuiet(MainActivity.this, on);
                        rebuild();
                    }
                }));
        if (Store.quiet(this)) {
            LinearLayout times = new LinearLayout(this);
            times.setOrientation(LinearLayout.HORIZONTAL);
            Ui.PixelButton from = new Ui.PixelButton(this, "FROM " + hourLabel(Store.quietStart(this)), false);
            from.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickHour(true); }
            });
            Ui.PixelButton to = new Ui.PixelButton(this, "TO " + hourLabel(Store.quietEnd(this)), false);
            to.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickHour(false); }
            });
            times.addView(from, new LinearLayout.LayoutParams(-2, -2));
            times.addView(to, new LinearLayout.LayoutParams(-2, -2));
            box.addView(times, lp(-2, -2, 4));
        }

        // location accuracy
        String locState = Store.fine(this) ? "Precise location is on" : Store.coarse(this) ? "Approximate only" : "Not allowed yet";
        Ui.PixelButton bg = null;
        String bgSub;
        if (Build.VERSION.SDK_INT < 29) bgSub = "Widgets refresh your position automatically";
        else if (Store.background(this)) bgSub = "Widgets follow you in the background";
        else {
            bgSub = "Widgets use your last known spot until you open Skypix";
            if (Store.fine(this)) {
                bg = new Ui.PixelButton(this, "ALWAYS", false);
                bg.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Toast.makeText(MainActivity.this, "Choose \"Allow all the time\"", Toast.LENGTH_LONG).show();
                        }
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND);
                    }
                });
            }
        }
        row(box, locState, null, null);
        row(box, "Background location", bgSub, bg);
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.notificationsAllowed(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private String hourLabel(int h) {
        return DateFormat.is24HourFormat(this) ? (h < 10 ? "0" : "") + h + ":00" : (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    private void pickHour(final boolean start) {
        int h = start ? Store.quietStart(this) : Store.quietEnd(this);
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, int hour, int minute) {
                if (start) Store.setQuietHours(MainActivity.this, hour, Store.quietEnd(MainActivity.this));
                else Store.setQuietHours(MainActivity.this, Store.quietStart(MainActivity.this), hour);
                rebuild();
            }
        }, h, 0, DateFormat.is24HourFormat(this)).show();
    }

    // ---- widget picker --------------------------------------------------------------------------------------------

    private static final String[] SIZES = {"2×2", "4×2", "4×2", "4×3", "4×2", "2×2", "4×2", "4×1"};
    private static final String[] BLURBS = {
        "Temperature, condition, high/low", "Big temperature with humidity", "Next hours with rain chance",
        "Five-day outlook with range bars", "Feels like, humidity, wind, UV, air, pressure", "Exact place, coordinates and GPS accuracy",
        "Sun arc, sunrise, sunset, daylight left", "The one thing to know right now"
    };

    private void addWidgetPicker(int ink, int ink2) {
        LinearLayout box = section("ADD WIDGETS", ink);
        final AppWidgetManager awm = AppWidgetManager.getInstance(this);
        boolean canPin = Build.VERSION.SDK_INT >= 26 && awm.isRequestPinAppWidgetSupported();
        for (int k = 0; k < Cards.COUNT; k++) {
            final int kind = k;
            Ui.PixelButton add = canPin ? new Ui.PixelButton(this, "ADD", true) : null;
            if (add != null) add.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    awm.requestPinAppWidget(new ComponentName(MainActivity.this, WidgetUpdater.PROVIDERS[kind]), null, null);
                }
            });
            row(box, Cards.title(k) + "  ·  " + SIZES[k], BLURBS[k], add);
        }
        if (!canPin) {
            TextView hint = new TextView(this);
            hint.setText("Long-press your home screen, tap Widgets, and look for Skypix.");
            hint.setTextColor(0xB3FFFFFF);
            hint.setTextSize(12);
            box.addView(hint, lp(-1, -2, 10));
        }
    }
}
