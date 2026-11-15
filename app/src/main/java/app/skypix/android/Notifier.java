package app.skypix.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;

import app.skypix.core.CatNews;
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
    static final String CH_EMERGENCY = "emergency";
    static final String CH_NEWS = "news";
    static final String CH_MORNING = "morning";
    static final String CH_UPDATES = "updates";
    private static final int ID_NEWS = 1001, ID_MORNING = 1002, ID_UPDATE = 1003;

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Weather alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Bring-an-umbrella nudges, heat, UV, wind and air quality heads-ups");
        NotificationChannel emergency = new NotificationChannel(CH_EMERGENCY, "Emergency weather alerts", NotificationManager.IMPORTANCE_HIGH);
        emergency.setDescription("Floods, thunderstorms and damaging wind. Loud and impossible to miss");
        emergency.enableVibration(true);
        emergency.setVibrationPattern(new long[]{0, 400, 150, 400, 150, 700});
        emergency.enableLights(true);
        emergency.setLightColor(0xFFFF5A5F);
        emergency.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel news = new NotificationChannel(CH_NEWS, "Cat weather news", NotificationManager.IMPORTANCE_LOW);
        news.setDescription("A cat-voiced weather bulletin every two hours");
        news.setShowBadge(false);
        NotificationChannel morning = new NotificationChannel(CH_MORNING, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT);
        morning.setDescription("What the day looks like and what to carry");
        NotificationChannel updates = new NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW);
        updates.setDescription("Tells you when a new version of Skypix is ready");
        updates.setShowBadge(false);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(emergency);
        nm.createNotificationChannel(news);
        nm.createNotificationChannel(morning);
        nm.createNotificationChannel(updates);
        nm.deleteNotificationChannel("hourly");
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

    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    /** The "Cat News" bulletin, every two hours. */
    static void news(Context c, Place p, Weather w) {
        if (!Store.hourly(c) || !Store.notificationsAllowed(c) || inQuietHours(c)) return;
        ensureChannels(c);
        long now = System.currentTimeMillis() / 1000L;
        CatNews.Bulletin n = CatNews.news(w, Store.units(c), p.shortName(), now);
        Notification.Builder b = builder(c, CH_NEWS);
        b.setSmallIcon(R.drawable.ic_stat_weather)
                .setLargeIcon(largeIcon(n.icon))
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(new Notification.BigTextStyle().bigText(n.big))
                .setSubText(p.shortName())
                .setColor(0xFF1A73E8)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open(c, 11));
        post(c, ID_NEWS, b.build());
    }

    /** Good-morning briefing. */
    static void morning(Context c, Place p, Weather w) {
        if (!Store.morning(c) || !Store.notificationsAllowed(c)) return;
        ensureChannels(c);
        long now = System.currentTimeMillis() / 1000L;
        if (w.dayIndex(now) >= w.dMax.length) return;
        CatNews.Bulletin n = CatNews.morning(w, Store.units(c), p.shortName(), now);
        Notification.Builder b = builder(c, CH_MORNING);
        b.setSmallIcon(R.drawable.ic_stat_weather)
                .setLargeIcon(largeIcon(n.icon))
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(new Notification.BigTextStyle().bigText(n.big))
                .setSubText(p.shortName())
                .setColor(0xFFF29900)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open(c, 13));
        post(c, ID_MORNING, b.build());
    }

    static void updateAvailable(Context c, String versionName, String notes) {
        if (!Store.notificationsAllowed(c)) return;
        ensureChannels(c);
        Notification.Builder b = builder(c, CH_UPDATES);
        b.setSmallIcon(R.drawable.ic_stat_weather)
                .setLargeIcon(largeIcon(Icon.SUN))
                .setContentTitle("Skypix " + versionName + " is ready")
                .setContentText(notes.length() > 0 ? notes : "Open Skypix to install the update")
                .setStyle(new Notification.BigTextStyle().bigText((notes.length() > 0 ? notes + "\n" : "") + "Open Skypix and tap Install."))
                .setColor(0xFF1A73E8)
                .setAutoCancel(true)
                .setContentIntent(open(c, 14));
        post(c, ID_UPDATE, b.build());
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
            boolean emergency = in.sev >= Insights.WARNING;
            Notification.Builder b = builder(c, emergency ? CH_EMERGENCY : CH_ALERTS);
            b.setSmallIcon(R.drawable.ic_stat_weather)
                    .setLargeIcon(largeIcon(emergency ? Icon.STORM : in.icon))
                    .setContentTitle(CatNews.alertTitle(in))
                    .setContentText(in.detail + " \u00b7 " + p.shortName())
                    .setStyle(new Notification.BigTextStyle().bigText(CatNews.alertBody(in) + "\n" + p.title()))
                    .setColor(emergency ? 0xFFFF5A5F : 0xFFFFB020)
                    .setAutoCancel(true)
                    .setCategory(emergency ? Notification.CATEGORY_ALARM : Notification.CATEGORY_RECOMMENDATION)
                    .setContentIntent(open(c, 12));
            if (emergency) b.setVisibility(Notification.VISIBILITY_PUBLIC);
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
