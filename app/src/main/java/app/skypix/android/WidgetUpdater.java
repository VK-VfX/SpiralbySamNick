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
import app.skypix.core.Variants;
import app.skypix.weather.R;

/** Renders widgets (a light and a dark bitmap each) and pushes them to the launcher. */
final class WidgetUpdater {
    private WidgetUpdater() {}

    private static final int MAX_PIXELS = 900000;

    static void updateAll(Context ctx) {
        Context app = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        Cards.Data d = Store.data(app);
        for (int v = 0; v < Widgets.ALL.length; v++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(app, Widgets.ALL[v]));
            for (int id : ids) update(app, m, v, id, d);
        }
    }

    static int count(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int n = 0;
        for (Class<?> c : Widgets.ALL) n += m.getAppWidgetIds(new ComponentName(ctx, c)).length;
        return n;
    }

    static void update(Context ctx, AppWidgetManager m, int variant, int id, Cards.Data d) {
        push(ctx, m, variant, id, d, -1, 1f);
    }

    /**
     * @param onlyTheme -1 = send both light and dark bitmaps, 0 = light only, 1 = dark only
     * @param scale     render-resolution multiplier (animation frames use less to stay cheap)
     */
    private static void push(Context ctx, AppWidgetManager m, int variant, int id, Cards.Data d, int onlyTheme, float scale) {
        int kind = Variants.KIND[variant];
        d.transparentDark = Store.clearDark(ctx);
        int[] wh = sizeFor(m, variant, id);
        int w = wh[0], h = wh[1];

        float u = ctx.getResources().getDisplayMetrics().density * scale;
        while (w * u * h * u > MAX_PIXELS && u > 1f) u *= 0.9f;
        int pw = Math.max(1, Math.round(w * u)), ph = Math.max(1, Math.round(h * u));

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_frame);
        if (onlyTheme != 1) rv.setImageViewBitmap(R.id.img_light, draw(kind, w, h, u, pw, ph, false, d));
        if (onlyTheme != 0) rv.setImageViewBitmap(R.id.img_dark, draw(kind, w, h, u, pw, ph, true, d));
        rv.setContentDescription(R.id.root, Cards.describe(kind, d));

        Intent i = new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra("from_widget", kind)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 100 + variant, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.root, pi);
        m.updateAppWidget(id, rv);
    }

    private static int[] sizeFor(AppWidgetManager m, int variant, int id) {
        int[] def = {Variants.widthDp(variant), Variants.heightDp(variant)};
        Bundle o = m.getAppWidgetOptions(id);
        int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (w < 40) w = def[0];
        if (h < 30) h = def[1];
        return new int[]{w, h};
    }

    /** One frame of the unlock animation: a normal (full) update of the active theme at reduced resolution. */
    static void animateFrame(Context ctx, Cards.Data d, float t, boolean dark) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        d.anim = t;
        for (int v = 0; v < Widgets.ALL.length; v++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(ctx, Widgets.ALL[v]));
            for (int id : ids) push(ctx, m, v, id, d, dark ? 1 : 0, 0.7f);
        }
    }

    static Bitmap draw(int kind, int wDp, int hDp, float u, int pw, int ph, boolean dark, Cards.Data d) {
        Bitmap b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Cards.render(new AndroidGfx(new Canvas(b)), kind, pw / u, ph / u, u, dark, d);
        return b;
    }
}
