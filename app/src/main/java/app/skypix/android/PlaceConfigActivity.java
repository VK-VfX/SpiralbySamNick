package app.skypix.android;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.skypix.core.Cards;
import app.skypix.core.Variants;

import java.util.List;

/**
 * "Widget settings": opens when a widget is added and again when you touch and hold it and choose Widget settings.
 * Lets you pick the widget's background (Solid, Glass, Clear) and which place it shows, with a live preview.
 */
public final class PlaceConfigActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int variant = -1;
    private int bg;
    private String placeId = "";
    private ImageView preview;
    private final Ui.Btn[] bgButtons = new Ui.Btn[3];
    private LinearLayout placeBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in != null && in.getExtras() != null) {
            widgetId = in.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        // backing out cancels adding the widget
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
            if (info != null) {
                String cn = info.provider.getClassName();
                for (int v = 0; v < Widgets.ALL.length; v++) if (Widgets.ALL[v].getName().equals(cn)) variant = v;
            }
        } catch (Throwable ignored) {
        }
        bg = Places.effectiveBg(this, widgetId);
        placeId = Places.widgetPlace(this, widgetId);

        getWindow().setStatusBarColor(Ui.bg(this));
        getWindow().setNavigationBarColor(Ui.bg(this));
        if (!Ui.isDark(this)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 36), pad, pad);

        col.addView(Ui.text(this, "Widget settings", 30, 500, Ui.onSurface(this)));
        String name = variant >= 0 ? Variants.TITLE[variant] : "Widget";
        col.addView(Ui.text(this, name + ". Touch and hold the widget any time to come back here.", 14.5f, 400, Ui.onSurfaceVar(this)), lp(6));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setClipToOutline(true);
        final int radius = Ui.dp(this, 28);
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        col.addView(preview, lp(18));

        col.addView(Ui.text(this, "Background", 15, 500, Ui.primary(this)), lp(24));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Solid", "Glass", "Clear"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            bgButtons[i] = new Ui.Btn(this, names[i], bg == i);
            bgButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    bg = mode;
                    for (int k = 0; k < 3; k++) bgButtons[k].setFilled(k == bg);
                    refreshPreview();
                }
            });
            row.addView(bgButtons[i], new LinearLayout.LayoutParams(-2, -2));
        }
        col.addView(row, lp(8));
        col.addView(Ui.text(this, "Solid is a full card, Glass lets the wallpaper show through, and Clear is fully see-through in dark mode (frosted glass in light mode).", 13, 400, Ui.onSurfaceVar(this)), lp(6));

        final List<Places.Saved> saved = Places.list(this);
        if (!saved.isEmpty()) {
            col.addView(Ui.text(this, "Place", 15, 500, Ui.primary(this)), lp(24));
            placeBox = new LinearLayout(this);
            placeBox.setOrientation(LinearLayout.VERTICAL);
            col.addView(placeBox, lp(8));
            buildPlaces();
        }

        LinearLayout done = new LinearLayout(this);
        done.setGravity(android.view.Gravity.END);
        Ui.Btn ok = new Ui.Btn(this, "Done", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        done.addView(ok, new LinearLayout.LayoutParams(-2, -2));
        col.addView(done, lp(24));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.bg(this));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);
        refreshPreview();
    }

    private LinearLayout.LayoutParams lp(float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    private void buildPlaces() {
        placeBox.removeAllViews();
        addPlaceRow("Where I am", "Follows your location", "");
        for (Places.Saved s : Places.list(this)) addPlaceRow(s.name, s.place.title(), s.id);
    }

    private void addPlaceRow(String title, String sub, final String id) {
        boolean on = id.equals(placeId);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        row.setBackground(Ui.surface(this, on ? Ui.primaryContainer(this) : Ui.container(this), 24));
        int ink = on ? Ui.onPrimaryContainer(this) : Ui.onSurface(this);
        row.addView(Ui.text(this, title, 16.5f, 500, ink));
        row.addView(Ui.text(this, sub, 13.5f, 400, on ? ink : Ui.onSurfaceVar(this)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                placeId = id;
                buildPlaces();
                refreshPreview();
            }
        });
        placeBox.addView(row, lp(8));
    }

    /** Draws the widget as it will look, over a colourful backdrop so glass and clear are easy to judge. */
    private void refreshPreview() {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(this);
            int[] wh = variant >= 0 ? WidgetUpdater.sizeFor(m, variant, widgetId) : new int[]{250, 110};
            int kind = variant >= 0 ? Variants.KIND[variant] : Cards.NOW;
            Cards.Data d = Store.data(this);
            Places.Saved s = Places.get(this, placeId);
            if (s != null) {
                d.place = s.place;
                d.weather = Places.loadWeather(this, s.id);
            }
            Places.applyBg(d, bg);
            float u = getResources().getDisplayMetrics().density;
            int pw = Math.max(1, Math.round(wh[0] * u)), ph = Math.max(1, Math.round(wh[1] * u));
            Bitmap w = WidgetUpdater.draw(kind, wh[0], wh[1], u, pw, ph, Ui.isDark(this), d);
            int margin = Math.round(26 * u);
            Bitmap out = Bitmap.createBitmap(pw + 2 * margin, ph + 2 * margin, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, out.getWidth(), out.getHeight(), 0xFFFF8A65, 0xFF5C6BC0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, out.getWidth(), out.getHeight(), p);
            p.setShader(null);
            p.setColor(0x55FFFFFF);
            c.drawCircle(out.getWidth() * 0.82f, out.getHeight() * 0.22f, out.getHeight() * 0.28f, p);
            p.setColor(0x33000000);
            c.drawCircle(out.getWidth() * 0.12f, out.getHeight() * 0.85f, out.getHeight() * 0.35f, p);
            c.drawBitmap(w, margin, margin, null);
            preview.setImageBitmap(out);
        } catch (Throwable t) {
            Ui.report("widget preview", t);
        }
    }

    private void save() {
        Places.setWidgetPlace(this, widgetId, placeId);
        Places.setWidgetBg(this, widgetId, bg);
        WidgetUpdater.updateOne(this, widgetId);
        Jobs.syncAnimService(getApplicationContext());
        Jobs.kick(this);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
