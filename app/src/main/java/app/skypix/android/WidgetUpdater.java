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
        Store.applyBackground(ctx, d);
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

    static final int ANIM_FRAMES = 33;
    static final int ANIM_INTERVAL_MS = 150;

    /**
     * Sends every widget ONE update that carries the whole animation as a ViewFlipper of pre-drawn frames;
     * the launcher then plays it by itself, so nothing depends on how fast it accepts updates.
     * The caller restores the normal static widgets afterwards with {@link #updateAll}.
     */
    static void animateAll(Context ctx, Cards.Data d, boolean dark) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        long limit = (long) (dm.widthPixels * (long) dm.heightPixels * 4L * 1.5);
        for (int v = 0; v < Widgets.ALL.length; v++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(ctx, Widgets.ALL[v]));
            for (int id : ids) {
                try {
                    pushFlipbook(ctx, m, v, id, d, dark, limit);
                } catch (Throwable t) {
                    Ui.report("animate widget " + Variants.KEY[v], t);
                }
            }
        }
    }

    private static void pushFlipbook(Context ctx, AppWidgetManager m, int variant, int id, Cards.Data d, boolean dark, long limit) {
        int kind = Variants.KIND[variant];
        Store.applyBackground(ctx, d);
        int[] wh = sizeFor(m, variant, id);
        int w = wh[0], h = wh[1];
        float u0 = ctx.getResources().getDisplayMetrics().density;
        // keep all frames of this widget well inside the system's per-update bitmap budget
        double budget = limit * 0.4;
        double full = (double) ANIM_FRAMES * 4.0 * (w * u0) * (h * u0);
        float scale = (float) Math.max(0.25, Math.min(0.8, Math.sqrt(budget / full)));
        float u = u0 * scale;
        int pw = Math.max(1, Math.round(w * u)), ph = Math.max(1, Math.round(h * u));

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_anim);
        for (int i = 0; i < ANIM_FRAMES; i++) {
            d.anim = i * ANIM_INTERVAL_MS / 1000f;
            int fid = ctx.getResources().getIdentifier("f" + i, "id", ctx.getPackageName());
            rv.setImageViewBitmap(fid, draw(kind, w, h, u, pw, ph, dark, d));
        }
        d.anim = -1;
        rv.setContentDescription(R.id.root, Cards.describe(kind, d));
        Intent in = new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra("from_widget", kind)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        rv.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 100 + variant, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(id, rv);
    }

    static Bitmap draw(int kind, int wDp, int hDp, float u, int pw, int ph, boolean dark, Cards.Data d) {
        Bitmap b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Cards.render(new AndroidGfx(new Canvas(b)), kind, pw / u, ph / u, u, dark, d);
        return b;
    }
}
