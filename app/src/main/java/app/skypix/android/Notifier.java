package app.skypix.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;

import app.skypix.core.Icon;
import app.skypix.core.Insights;
import app.skypix.core.Place;
import app.skypix.core.Units;
import app.skypix.core.Weather;
import app.skypix.core.Wmo;
import app.skypix.weather.R;

import java.util.Calendar;
import java.util.List;

final class Notifier {
    private Notifier() {}

    static final String CH_ALERTS = "alerts";
    static final String CH_HOURLY = "hourly";
    private static final int ID_HOURLY = 1001;

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Weather alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Rain, storms, heat, UV, wind and air quality heads-ups for where you are");
        NotificationChannel hourly = new NotificationChannel(CH_HOURLY, "Hourly briefing", NotificationManager.IMPORTANCE_LOW);
        hourly.setDescription("A quiet once-an-hour summary of conditions and what's next");
        hourly.setShowBadge(false);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(hourly);
    }

    private static PendingIntent open(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Bitmap largeIcon(Icon icon) {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        app.skypix.core.Glyphs.draw(new AndroidGfx(new android.graphics.Canvas(b)), icon, 0, 0, 128, -1);
        return b;
    }

    private static boolean inQuietHours(Context c) {
        if (!Store.quiet(c)) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int s = Store.quietStart(c), e = Store.quietEnd(c);
        return s > e ? (h >= s || h < e) : (h >= s && h < e);
    }

    static void hourly(Context c, Place p, Weather w) {
        if (!Store.hourly(c) || !Store.notificationsAllowed(c) || inQuietHours(c)) return;
        ensureChannels(c);
        long now = System.currentTimeMillis() / 1000L;
        Units u = Store.units(c);
        Weather.Now n = w.now(now);
        Icon icon = Wmo.icon(n.code, n.isDay);
        String title = u.t(n.temp) + " · " + Wmo.label(n.code) + " · " + p.shortName();
        String body = Insights.hourlyBody(w, u, now);
        String first = body.contains("\n") ? body.substring(0, body.indexOf('\n')) : body;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_HOURLY) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_weather)
                .setLargeIcon(largeIcon(icon))
                .setContentTitle(title)
                .setContentText(first)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setColor(0xFF2F6FD6)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open(c, 11));
        post(c, ID_HOURLY, b.build());
    }

    static void alerts(Context c, Place p, Weather w) {
        if (!Store.alerts(c) || !Store.notificationsAllowed(c)) return;
        long now = System.currentTimeMillis() / 1000L;
        Units u = Store.units(c);
        List<Insights.Insight> list = Insights.compute(w, u, now);
        for (Insights.Insight in : list) {
            if (in.sev < Insights.NOTICE) continue;
            if (in.at != 0 && in.at - now > 4 * 3600L) continue;
            if (!Store.markSeen(c, in.key)) continue;
            ensureChannels(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_ALERTS) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat_weather)
                    .setLargeIcon(largeIcon(in.icon))
                    .setContentTitle(in.title)
                    .setContentText(in.detail + " · " + p.shortName())
                    .setStyle(new Notification.BigTextStyle().bigText(in.detail + "\n" + p.title()))
                    .setColor(in.sev == Insights.WARNING ? 0xFFFF5A5F : 0xFFFFB020)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_RECOMMENDATION)
                    .setContentIntent(open(c, 12));
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            post(c, in.key.hashCode(), b.build());
        }
    }

    private static void post(Context c, int id, Notification n) {
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }
}
