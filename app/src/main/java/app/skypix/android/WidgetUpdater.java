package app.skypix.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.widget.RemoteViews;

import app.skypix.core.Cards;
import app.skypix.weather.R;

/** Renders widgets (a light and a dark bitmap each) and pushes them to the launcher. */
final class WidgetUpdater {
    private WidgetUpdater() {}

    static final Class<?>[] PROVIDERS = {
        Widgets.Now.class, Widgets.NowWide.class, Widgets.Hourly.class, Widgets.Forecast.class,
        Widgets.Details.class, Widgets.Location.class, Widgets.Sun.class, Widgets.HeadsUp.class
    };

    private static final int MAX_PIXELS = 900000;

    static void updateAll(Context ctx) {
        Context app = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        Cards.Data d = Store.data(app);
        for (int kind = 0; kind < PROVIDERS.length; kind++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(app, PROVIDERS[kind]));
            for (int id : ids) update(app, m, kind, id, d);
        }
    }

    static int count(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int n = 0;
        for (Class<?> c : PROVIDERS) n += m.getAppWidgetIds(new ComponentName(ctx, c)).length;
        return n;
    }

    static void update(Context ctx, AppWidgetManager m, int kind, int id, Cards.Data d) {
        int[] def = Cards.size(kind);
        Bundle o = m.getAppWidgetOptions(id);
        int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (w < 40) w = def[0];
        if (h < 30) h = def[1];

        float u = ctx.getResources().getDisplayMetrics().density;
        while (w * u * h * u > MAX_PIXELS && u > 1f) u *= 0.9f;
        int pw = Math.max(1, Math.round(w * u)), ph = Math.max(1, Math.round(h * u));

        Bitmap light = draw(kind, w, h, u, pw, ph, false, d);
        Bitmap dark = draw(kind, w, h, u, pw, ph, true, d);

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_frame);
        rv.setImageViewBitmap(R.id.img_light, light);
        rv.setImageViewBitmap(R.id.img_dark, dark);
        rv.setContentDescription(R.id.root, Cards.describe(kind, d));

        Intent i = new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra("from_widget", kind)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 100 + kind, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.root, pi);
        m.updateAppWidget(id, rv);
    }

    static Bitmap draw(int kind, int wDp, int hDp, float u, int pw, int ph, boolean dark, Cards.Data d) {
        Bitmap b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Cards.render(new AndroidGfx(new Canvas(b)), kind, pw / u, ph / u, u, dark, d);
        return b;
    }
}
