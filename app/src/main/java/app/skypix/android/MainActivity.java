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
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import app.skypix.core.Cards;
import app.skypix.core.Wmo;
import app.skypix.core.Weather;
import app.skypix.weather.R;
import app.skypix.core.Fmt;
import app.skypix.core.Gfx;
import app.skypix.core.Insights;
import app.skypix.core.Place;
import app.skypix.core.Variants;

import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1, REQ_BACKGROUND = 2, REQ_NOTIFY = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout content;
    private TextView statusText;
    private FrameLayout rootView;
    private int titleTaps;
    private long lastTitleTap;
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
        rootView = root;
        root.addView(new Ui.Backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 32));
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
        int bg = Ui.bg(this);
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
        Ui.animStartMs = android.os.SystemClock.uptimeMillis();
        Store.touchStreak(this);
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
                } catch (Throwable t) {
                    Ui.report("refresh", t);
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
        if (statusText != null) statusText.setText(s);
    }

    // ---- screen -----------------------------------------------------------------------------------------------------

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    private void rebuild() {
        try {
            rebuildUnsafe();
        } catch (Throwable t) {
            Ui.report("rebuild", t);
            content.removeAllViews();
            TextView e = new TextView(this);
            e.setText("Purrcast couldn't draw this screen.\n\n" + Ui.lastError);
            e.setTextColor(0xFFFF8A8A);
            e.setTextIsSelectable(true);
            content.addView(e);
        }
    }

    private void rebuildUnsafe() {
        final int keepY = scroll.getScrollY();
        content.removeAllViews();
        cardByKind.clear();
        boolean dark = Ui.isDark(this);
        int ink = dark ? 0xFFFFFFFF : 0xFF0E1630;
        int ink2 = dark ? 0x99FFFFFF : 0x990E1630;
        final Cards.Data d = Store.data(this);

        // header: big Google-style title, quiet status line, round refresh button
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.purrcast_mark);
        logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        logo.setContentDescription("Purrcast");
        logo.setClipToOutline(true);
        final int logoRadius = Ui.dp(this, 15);
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), logoRadius); }
        });
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        logoLp.rightMargin = Ui.dp(this, 14);
        head.addView(logo, logoLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "Purrcast", 34, 500, Ui.onSurface(this));
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = android.os.SystemClock.uptimeMillis();
                titleTaps = now - lastTitleTap < 900 ? titleTaps + 1 : 1;
                lastTitleTap = now;
                if (titleTaps >= 7) {
                    titleTaps = 0;
                    Toast.makeText(MainActivity.this, "Meow! \uD83D\uDE3A", Toast.LENGTH_SHORT).show();
                    if (rootView != null) rootView.addView(new Ui.CatRain(MainActivity.this), new FrameLayout.LayoutParams(-1, -1));
                }
            }
        });
        titles.addView(title);
        statusText = Ui.text(this, statusLine(d), 14, 400, Ui.onSurfaceVar(this));
        titles.addView(statusText, lp(-2, -2, 4));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.RefreshButton refresh = new Ui.RefreshButton(this);
        refresh.spinning = refreshing;
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Store.anyLocation(MainActivity.this)) requestLocation();
                else startRefresh(true);
            }
        });
        head.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        content.addView(head, lp(-1, -2, 0));

        content.addView(new Ui.StreakCard(this, Store.streak(this), Store.bestStreak(this), Store.streakMask(this)), lp(-1, -2, 16));

        addBanners();

        if (d.ready()) {
            addCard(Cards.NOW_WIDE, 160, null, d, 20);
            List<Insights.Insight> notes = Insights.compute(d.weather, d.units, d.nowSec);
            int shown = 0;
            for (Insights.Insight in : notes) {
                if (shown >= 4) break;
                content.addView(new Ui.CardView(this, Cards.HEADSUP, 68, in, d), lp(-1, -2, 12));
                shown++;
            }
            if (shown == 0) addCard(Cards.HEADSUP, 68, null, d, 12);
            addCard(Cards.HOURLY, 150, null, d, 12);
            addCard(Cards.FORECAST, 236, null, d, 12);
            addCard(Cards.DETAILS, 196, null, d, 12);
            addCard(Cards.SUN, 150, null, d, 12);
            addCard(Cards.LOCATION, 150, null, d, 12);
        }

        addSettings(ink, ink2);
        addPlaces();
        addUpdates();
        addWidgetPicker(ink, ink2);

        TextView foot = new TextView(this);
        foot.setText("Weather & air quality: Open-Meteo.com (CC BY 4.0)\nPlace names: © OpenStreetMap contributors\nCat emoji: Google Noto Emoji (Apache 2.0)\nNo accounts, no analytics, no Google services.");
        foot.setTextSize(12);
        foot.setTypeface(AndroidGfx.typeface(400));
        foot.setTextColor(Ui.onSurfaceVar(this));
        foot.setLineSpacing(0, 1.2f);
        content.addView(foot, lp(-1, -2, 26));
        if (Ui.lastError != null) {
            TextView err = new TextView(this);
            err.setText("Last issue (please send this):\n" + Ui.lastError);
            err.setTextColor(0xFFFF8A8A);
            err.setTextSize(10.5f);
            err.setTextIsSelectable(true);
            content.addView(err, lp(-1, -2, 14));
        }

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
        if (!Store.anyLocation(this)) return "Location needed";
        if (refreshing) return "Updating\u2026";
        if (d.place == null) return "Finding you\u2026";
        long fetched = Store.fetchedAt(this);
        StringBuilder sb = new StringBuilder();
        if (fetched > 0) sb.append("Updated ").append(Fmt.ago((System.currentTimeMillis() - fetched) / 1000L));
        if (!Float.isNaN(d.place.accuracyM)) sb.append(sb.length() > 0 ? " \u00b7 " : "").append("\u00b1").append(Math.round(d.place.accuracyM)).append(" m");
        return sb.length() == 0 ? "Ready" : sb.toString();
    }

    // ---- banners ----------------------------------------------------------------------------------------------------

    private void banner(String title, String body, String button, View.OnClickListener action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, p);
        box.setBackground(Ui.surface(this, Ui.primaryContainer(this), 28));
        box.addView(Ui.text(this, title, 20, 500, Ui.onPrimaryContainer(this)));
        TextView t = Ui.text(this, body, 14.5f, 400, Ui.onPrimaryContainer(this));
        t.setLineSpacing(0, 1.2f);
        box.addView(t, lp(-1, -2, 8));
        Ui.Btn b = new Ui.Btn(this, button, true);
        b.setOnClickListener(action);
        box.addView(b, lp(-2, -2, 14));
        content.addView(box, lp(-1, -2, 16));
    }

    private void addBanners() {
        if (!Store.anyLocation(this)) {
            banner("Where are you?", "Purrcast only needs your location. It is used to show weather for the exact spot you are standing, and it never leaves your phone except as coordinates sent to the weather service.",
                    "Allow location", new View.OnClickListener() {
                        @Override public void onClick(View v) { requestLocation(); }
                    });
            return;
        }
        if (!Store.fine(this)) {
            banner("Approximate only", "Android is only sharing a rough area. Switch Location to \"Precise\" for street-level accuracy.",
                    "Open settings", new View.OnClickListener() {
                        @Override public void onClick(View v) { openAppSettings(); }
                    });
        }
        if (!locationServicesOn()) {
            banner("Location is off", "Turn on location services so Purrcast can find where you are.",
                    "Open settings", new View.OnClickListener() {
                        @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                    });
        }
        if (Build.VERSION.SDK_INT >= 33 && !Store.notificationsAllowed(this) && (Store.alerts(this) || Store.hourly(this))) {
            banner("Turn on alerts", "Allow notifications to get weather alerts and the hourly briefing.",
                    "Allow", new View.OnClickListener() {
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
        try {
            Jobs.sync(this);
            if (code == REQ_LOCATION && Store.anyLocation(this)) {
                Jobs.kick(this);
                startRefresh(true);
            }
        } catch (Throwable t) {
            Ui.report("permission result", t);
        }
        ui.post(new Runnable() {
            @Override public void run() { rebuild(); }
        });
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    private LinearLayout section(String title, int ink) {
        content.addView(Ui.text(this, title, 15, 500, Ui.primary(this)), lp(-2, -2, 32));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        box.setBackground(Ui.surface(this, Ui.container(this), 28));
        content.addView(box, lp(-1, -2, 12));
        return box;
    }

    private LinearLayout row(LinearLayout parent, String title, String sub, View control) {
        if (parent.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(Ui.divider(this));
            parent.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(this, 1))));
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 64));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(0, Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        text.addView(Ui.text(this, title, 16.5f, 400, Ui.onSurface(this)));
        if (sub != null) {
            TextView st = Ui.text(this, sub, 13.5f, 400, Ui.onSurfaceVar(this));
            st.setLineSpacing(0, 1.12f);
            text.addView(st, lp(-1, -2, 2));
        }
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        if (control != null) r.addView(control, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private CompoundButton toggle(boolean on, CompoundButton.OnCheckedChangeListener l) {
        Ui.Toggle s = new Ui.Toggle(this);
        s.setChecked(on);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private void addSettings(int ink, int ink2) {
        LinearLayout box = section("Settings", ink);

        // units
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        String cur = Store.tempPref(this);
        String[][] opts = {{"Auto", "auto"}, {"°C", "c"}, {"°F", "f"}};
        for (final String[] o : opts) {
            Ui.Btn b = new Ui.Btn(this, o[0], cur.equals(o[1]));
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
        String[] bgNames = {"Solid", "Glass", "Clear"};
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            Ui.Btn b = new Ui.Btn(this, bgNames[m], Store.bgMode(this) == m);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Store.setBgMode(MainActivity.this, mode);
                    WidgetUpdater.updateAll(MainActivity.this);
                    rebuild();
                }
            });
            style.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        row(box, "Widget background", "Solid, see-through glass, or fully clear (dark mode)", style);

        if (Build.VERSION.SDK_INT >= 31) {
            LinearLayout colors = new LinearLayout(this);
            colors.setOrientation(LinearLayout.HORIZONTAL);
            String[] names = {"Weather", "Material You"};
            for (int i = 0; i < 2; i++) {
                final boolean on = i == 1;
                Ui.Btn b = new Ui.Btn(this, names[i], Store.materialYou(this) == on);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Store.setMaterialYou(MainActivity.this, on);
                        applySystemBars();
                        WidgetUpdater.updateAll(MainActivity.this);
                        rebuild();
                    }
                });
                colors.addView(b, new LinearLayout.LayoutParams(-2, -2));
            }
            row(box, "Colours", "Weather blue, or colours picked from your wallpaper", colors);
        }

        row(box, "Cat icons", "Weather shown as Google Noto cats, a different mood for every sky",
                toggle(Store.cats(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setCats(MainActivity.this, on);
                        app.skypix.core.Glyphs.cats = on;
                        WidgetUpdater.updateAll(MainActivity.this);
                        rebuild();
                    }
                }));

        row(box, "Animate on unlock", "5 seconds of weather motion each time you open your phone",
                toggle(Store.animate(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setAnimate(MainActivity.this, on);
                        Jobs.syncAnimService(getApplicationContext());
                    }
                }));

        Ui.Btn playNow = new Ui.Btn(this, "Play now", false);
        playNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (WidgetUpdater.count(MainActivity.this) == 0) {
                    Toast.makeText(MainActivity.this, "Add a widget first, then try again", Toast.LENGTH_LONG).show();
                    return;
                }
                Jobs.syncAnimService(getApplicationContext());
                Jobs.AnimService.play(MainActivity.this, "test");
                Toast.makeText(MainActivity.this, "Playing on your home screen widgets for 5 s", Toast.LENGTH_LONG).show();
                ui.postDelayed(new Runnable() {
                    @Override public void run() { rebuild(); }
                }, 6000);
            }
        });
        row(box, "Test the animation", animStatus(), playNow);

        row(box, "Emergency & weather alerts", "Floods, thunderstorms, bring-an-umbrella nudges, heat, UV and wind",
                toggle(Store.alerts(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setAlerts(MainActivity.this, on);
                        Jobs.sync(MainActivity.this);
                        if (on) askNotifications();
                    }
                }));

        row(box, "Official warnings", Store.officialStatus(this),
                toggle(Store.official(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setOfficial(MainActivity.this, on);
                        Store.loadOfficial(MainActivity.this);
                        startRefresh(true);
                        rebuild();
                    }
                }));

        row(box, "Cat weather news", "A bulletin from the cats every 2 hours, written from the live forecast",
                toggle(Store.hourly(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setHourly(MainActivity.this, on);
                        Jobs.sync(MainActivity.this);
                        if (on) askNotifications();
                    }
                }));

        Ui.Btn morningTime = Store.morning(this) ? new Ui.Btn(this, hourLabel(Store.morningHour(this)), false) : null;
        if (morningTime != null) morningTime.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickMorning(); }
        });
        LinearLayout morningControls = new LinearLayout(this);
        morningControls.setOrientation(LinearLayout.HORIZONTAL);
        morningControls.setGravity(Gravity.CENTER_VERTICAL);
        if (morningTime != null) morningControls.addView(morningTime, new LinearLayout.LayoutParams(-2, -2));
        morningControls.addView(toggle(Store.morning(this), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                Store.setMorning(MainActivity.this, on);
                Jobs.sync(MainActivity.this);
                if (on) askNotifications();
                rebuild();
            }
        }));
        row(box, "Morning briefing", "The day's weather and what to carry, once each morning", morningControls);

        row(box, "Quiet hours", "No cat news " + hourLabel(Store.quietStart(this)) + " – " + hourLabel(Store.quietEnd(this)),
                toggle(Store.quiet(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                        Store.setQuiet(MainActivity.this, on);
                        rebuild();
                    }
                }));
        if (Store.quiet(this)) {
            LinearLayout times = new LinearLayout(this);
            times.setOrientation(LinearLayout.HORIZONTAL);
            Ui.Btn from = new Ui.Btn(this, "From " + hourLabel(Store.quietStart(this)), false);
            from.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickHour(true); }
            });
            Ui.Btn to = new Ui.Btn(this, "To " + hourLabel(Store.quietEnd(this)), false);
            to.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickHour(false); }
            });
            times.addView(from, new LinearLayout.LayoutParams(-2, -2));
            times.addView(to, new LinearLayout.LayoutParams(-2, -2));
            box.addView(times, lp(-2, -2, 4));
        }

        // location accuracy
        String locState = Store.fine(this) ? "Precise location is on" : Store.coarse(this) ? "Approximate only" : "Not allowed yet";
        Ui.Btn bg = null;
        String bgSub;
        if (Build.VERSION.SDK_INT < 29) bgSub = "Widgets refresh your position automatically";
        else if (Store.background(this)) bgSub = "Widgets follow you in the background";
        else {
            bgSub = "Widgets use your last known spot until you open Purrcast";
            if (Store.fine(this)) {
                bg = new Ui.Btn(this, "Always", false);
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

    private String animStatus() {
        int widgets = WidgetUpdater.count(this);
        StringBuilder sb = new StringBuilder();
        if (widgets == 0) sb.append("No widgets on your home screen yet");
        else sb.append(Jobs.AnimService.running ? "Unlock listener is running" : "Unlock listener is not running");
        if (Jobs.AnimService.lastPlayMs > 0) {
            sb.append(" \u00b7 last played (").append(Jobs.AnimService.lastTrigger).append(") ").append(Fmt.time(Jobs.AnimService.lastPlayMs / 1000L, java.util.TimeZone.getDefault().getOffset(Jobs.AnimService.lastPlayMs) / 1000, DateFormat.is24HourFormat(this), true));
        }
        return sb.toString();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.notificationsAllowed(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private String hourLabel(int h) {
        return DateFormat.is24HourFormat(this) ? (h < 10 ? "0" : "") + h + ":00" : (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    private void pickMorning() {
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, int hour, int minute) {
                Store.setMorningHour(MainActivity.this, hour);
                Jobs.sync(MainActivity.this);
                rebuild();
            }
        }, Store.morningHour(this), 0, DateFormat.is24HourFormat(this)).show();
    }

    // ---- saved places ---------------------------------------------------------------------------------------------

    private void addPlaces() {
        LinearLayout box = section("Saved places", 0);
        final Context app = getApplicationContext();
        for (final Places.Saved sp : Places.list(this)) {
            Weather w = Places.loadWeather(this, sp.id);
            String sub = sp.place.title();
            if (w != null) sub = Store.units(this).t(w.now(System.currentTimeMillis() / 1000L).temp) + " \u00b7 " + Wmo.label(w.now(System.currentTimeMillis() / 1000L).code) + " \u00b7 " + sub;
            Ui.Btn remove = new Ui.Btn(this, "Remove", false);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Places.remove(app, sp.id);
                    WidgetUpdater.updateAll(app);
                    rebuild();
                }
            });
            row(box, sp.name, sub, remove);
        }
        Ui.Btn search = new Ui.Btn(this, "Search", true);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { searchPlaceDialog(); }
        });
        row(box, "Add a place", "Find a city or town. Widgets can show any saved place", search);
        Cards.Data cur = Store.data(this);
        if (cur.place != null) {
            Ui.Btn here = new Ui.Btn(this, "Save", false);
            here.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveHereDialog(); }
            });
            row(box, "Save where I am", "Name this spot, like Home or Work", here);
        }
    }

    private void saveHereDialog() {
        final Place here = Store.loadPlace(this);
        if (here == null) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText("Home");
        input.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this).setTitle("Name this place").setView(input)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Place copy = Place.fromJson(here.toJson().toString());
                        Places.add(getApplicationContext(), input.getText().toString(), copy);
                        refreshSavedAsync();
                        rebuild();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void refreshSavedAsync() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Updater.refreshSaved(app, true);
                WidgetUpdater.updateAll(app);
                runOnUiThread(new Runnable() { @Override public void run() { rebuild(); } });
            }
        }, "skypix-places").start();
    }

    private void searchPlaceDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("City or town");
        new android.app.AlertDialog.Builder(this).setTitle("Add a place").setView(input)
                .setPositiveButton("Search", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { runPlaceSearch(input.getText().toString().trim()); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void runPlaceSearch(final String query) {
        if (query.length() < 2) return;
        Toast.makeText(this, "Searching\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" + java.net.URLEncoder.encode(query, "UTF-8");
                    org.json.JSONObject o = new org.json.JSONObject(Net.get(url));
                    final org.json.JSONArray r = o.optJSONArray("results");
                    if (r == null || r.length() == 0) {
                        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, "No places found for that name", Toast.LENGTH_LONG).show(); } });
                        return;
                    }
                    final String[] labels = new String[r.length()];
                    for (int i = 0; i < r.length(); i++) {
                        org.json.JSONObject x = r.getJSONObject(i);
                        String admin = x.optString("admin1", "");
                        labels[i] = x.optString("name") + (admin.length() > 0 ? ", " + admin : "") + ", " + x.optString("country", "");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            new android.app.AlertDialog.Builder(MainActivity.this).setTitle("Pick a place")
                                    .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int which) {
                                            try {
                                                org.json.JSONObject x = r.getJSONObject(which);
                                                Place p = new Place();
                                                p.lat = x.getDouble("latitude");
                                                p.lon = x.getDouble("longitude");
                                                p.city = x.optString("name");
                                                p.region = x.optString("admin1", "");
                                                p.country = x.optString("country", "");
                                                p.provider = "search";
                                                Places.add(getApplicationContext(), p.city, p);
                                                refreshSavedAsync();
                                                rebuild();
                                            } catch (Exception e) {
                                                Ui.report("add place", e);
                                            }
                                        }
                                    }).show();
                        }
                    });
                } catch (Throwable t) {
                    Ui.report("place search", t);
                    runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, "Couldn't search right now. Are you online?", Toast.LENGTH_LONG).show(); } });
                }
            }
        }, "skypix-geocode").start();
    }

    // ---- app updates ----------------------------------------------------------------------------------------------

    private TextView updateSub;

    private void addUpdates() {
        LinearLayout box = section("App updates", 0);
        AppUpdate.Info av = AppUpdate.available;
        String sub;
        if (AppUpdate.status.length() > 0) sub = AppUpdate.status;
        else if (av != null) sub = "Version " + av.name + " is ready" + (av.notes.length() > 0 ? ": " + av.notes : "");
        else sub = "Tap Check to look for a new version";
        Ui.Btn action = new Ui.Btn(this, av != null ? "Install" : "Check", av != null);
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AppUpdate.busy) return;
                if (AppUpdate.available != null) installUpdate(); else checkUpdate();
            }
        });
        LinearLayout r = row(box, "Purrcast " + AppUpdate.installedName(this), sub, action);
        View col = r.getChildAt(0);
        updateSub = col instanceof LinearLayout && ((LinearLayout) col).getChildCount() > 1 ? (TextView) ((LinearLayout) col).getChildAt(1) : null;
        row(box, "Check automatically", "Once a day. Only contacts GitHub, nothing about you is sent",
                toggle(Store.autoUpdate(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean on) { Store.setAutoUpdate(MainActivity.this, on); }
                }));
    }

    private void setUpdateStatus(final String s) {
        AppUpdate.status = s;
        runOnUiThread(new Runnable() {
            @Override public void run() { if (updateSub != null) updateSub.setText(s); }
        });
    }

    private void checkUpdate() {
        final Context app = getApplicationContext();
        AppUpdate.busy = true;
        setUpdateStatus("Checking\u2026");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppUpdate.Info i = AppUpdate.check(app);
                    AppUpdate.status = i == null ? "You're on the latest version" : "";
                } catch (Throwable t) {
                    AppUpdate.status = "Couldn't check right now. Are you online?";
                }
                AppUpdate.busy = false;
                runOnUiThread(new Runnable() { @Override public void run() { rebuild(); } });
            }
        }, "skypix-update-check").start();
    }

    private void installUpdate() {
        final Context app = getApplicationContext();
        final AppUpdate.Info info = AppUpdate.available;
        if (info == null) return;
        if (!AppUpdate.ensureInstallPermission(this)) {
            setUpdateStatus("Allow Purrcast to install apps, come back, then tap Install again");
            return;
        }
        AppUpdate.busy = true;
        setUpdateStatus("Downloading 0%");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File apk = AppUpdate.download(app, info, new AppUpdate.Progress() {
                        @Override public void onProgress(String text) { setUpdateStatus(text); }
                    });
                    setUpdateStatus("Verified. Handing over to Android\u2026");
                    AppUpdate.install(app, apk);
                } catch (Throwable t) {
                    AppUpdate.busy = false;
                    setUpdateStatus("Update failed: " + t.getMessage());
                }
            }
        }, "skypix-update-install").start();
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

    private void addWidgetPicker(int ink, int ink2) {
        content.addView(Ui.text(this, "Add widgets", 15, 500, Ui.primary(this)), lp(-2, -2, 32));
        final AppWidgetManager awm = AppWidgetManager.getInstance(this);
        boolean canPin = Build.VERSION.SDK_INT >= 26 && awm.isRequestPinAppWidgetSupported();
        for (int v = 0; v < Variants.COUNT; v++) {
            final int variant = v;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 14));
            card.setBackground(Ui.surface(this, Ui.container(this), 28));

            int res = getResources().getIdentifier("preview_" + Variants.KEY[v], "drawable", getPackageName());
            if (res != 0) {
                android.widget.ImageView img = new android.widget.ImageView(this);
                img.setImageResource(res);
                img.setAdjustViewBounds(true);
                img.setMaxHeight(Ui.dp(this, 150));
                img.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
                img.setContentDescription(Variants.TITLE[v] + " widget preview");
                card.addView(img, lp(-2, -2, 0));
            }
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(Ui.text(this, Variants.TITLE[v] + "  \u00b7  " + Variants.size(v), 16.5f, 500, Ui.onSurface(this)));
            TextView sub = Ui.text(this, Variants.BLURB[v], 13.5f, 400, Ui.onSurfaceVar(this));
            sub.setLineSpacing(0, 1.12f);
            text.addView(sub, lp(-1, -2, 2));
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            if (canPin) {
                Ui.Btn add = new Ui.Btn(this, "Add", true);
                add.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        awm.requestPinAppWidget(new ComponentName(MainActivity.this, Widgets.ALL[variant]), null, null);
                    }
                });
                line.addView(add, new LinearLayout.LayoutParams(-2, -2));
            }
            card.addView(line, lp(-1, -2, res != 0 ? 12 : 0));
            content.addView(card, lp(-1, -2, 12));
        }
        if (!canPin) {
            content.addView(Ui.text(this, "Long-press your home screen, tap Widgets, and look for Purrcast.", 13.5f, 400, Ui.onSurfaceVar(this)), lp(-1, -2, 12));
        }
    }
}
