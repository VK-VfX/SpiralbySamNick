package app.skypix.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

import app.skypix.core.Cards;

/** The eight home-screen widgets. Each is a thin provider; all drawing lives in {@link Cards}. */
public final class Widgets {
    private Widgets() {}

    abstract static class Base extends AppWidgetProvider {
        abstract int kind();

        @Override
        public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
            Cards.Data d = Store.data(ctx);
            for (int id : ids) WidgetUpdater.update(ctx, m, kind(), id, d);
            Jobs.sync(ctx);
            Jobs.kick(ctx);
        }

        @Override
        public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle newOptions) {
            WidgetUpdater.update(ctx, m, kind(), id, Store.data(ctx));
        }

        @Override
        public void onEnabled(Context ctx) {
            Jobs.sync(ctx);
            Jobs.kick(ctx);
        }

        @Override
        public void onDisabled(Context ctx) {
            Jobs.sync(ctx);
        }
    }

    public static final class Now extends Base { @Override int kind() { return Cards.NOW; } }
    public static final class NowWide extends Base { @Override int kind() { return Cards.NOW_WIDE; } }
    public static final class Hourly extends Base { @Override int kind() { return Cards.HOURLY; } }
    public static final class Forecast extends Base { @Override int kind() { return Cards.FORECAST; } }
    public static final class Details extends Base { @Override int kind() { return Cards.DETAILS; } }
    public static final class Location extends Base { @Override int kind() { return Cards.LOCATION; } }
    public static final class Sun extends Base { @Override int kind() { return Cards.SUN; } }
    public static final class HeadsUp extends Base { @Override int kind() { return Cards.HEADSUP; } }
}
