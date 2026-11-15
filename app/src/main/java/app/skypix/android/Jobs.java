package app.skypix.android;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import app.skypix.core.Cards;
import app.skypix.weather.R;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** Background scheduling using only platform APIs (JobScheduler + AlarmManager). */
public final class Jobs {
    private Jobs() {}

    static final int ID_PERIODIC = 1, ID_KICK = 2, ID_HOURLY = 3;
    private static final long PERIOD_MS = 30 * 60 * 1000L;

    /** Makes the scheduled work match what's enabled: widgets present, alerts, hourly briefing. */
    static void sync(Context ctx) {
        try {
            syncUnsafe(ctx);
        } catch (Throwable t) {
            Ui.report("Jobs.sync", t);
        }
    }

    private static void syncUnsafe(Context ctx) {
        Context app = ctx.getApplicationContext();
        // each step is isolated so one failure can never stop the others (the unlock listener most of all)
        try {
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            boolean needData = WidgetUpdater.count(app) > 0 || Store.alerts(app) || Store.hourly(app);
            if (needData && Store.anyLocation(app)) {
                if (js.getPendingJob(ID_PERIODIC) == null) {
                    js.schedule(new JobInfo.Builder(ID_PERIODIC, new ComponentName(app, UpdateService.class))
                            .setPeriodic(PERIOD_MS)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .build());
                }
            } else {
                js.cancel(ID_PERIODIC);
            }
        } catch (Throwable t) {
            Ui.report("Jobs.periodic", t);
        }
        try {
            if (Store.hourly(app) && Store.anyLocation(app)) scheduleHourlyAlarm(app);
            else cancelHourlyAlarm(app);
        } catch (Throwable t) {
            Ui.report("Jobs.alarm", t);
        }
        try {
            syncAnimService(app);
        } catch (Throwable t) {
            Ui.report("Jobs.animService", t);
        }
    }

    /** Runs the unlock-animation listener only while it's enabled and there is a widget to animate. */
    static void syncAnimService(Context app) {
        Intent i = new Intent(app, AnimService.class);
        if (Store.animate(app) && WidgetUpdater.count(app) > 0) {
            try {
                app.startForegroundService(i);
            } catch (RuntimeException e) {
                // started from the background on a restricted OS version; it will start next time the app is opened
            }
        } else {
            app.stopService(i);
        }
    }

    /** Refresh as soon as possible (one-shot). */
    static void kick(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (!Store.anyLocation(app)) return;
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            js.schedule(new JobInfo.Builder(ID_KICK, new ComponentName(app, UpdateService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(15000)
                    .build());
        } catch (Throwable t) {
            Ui.report("Jobs.kick", t);
        }
    }

    private static PendingIntent alarmIntent(Context app) {
        return PendingIntent.getBroadcast(app, 0, new Intent(app, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleHourlyAlarm(Context app) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 20);
        c.set(Calendar.MILLISECOND, 0);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), alarmIntent(app));
    }

    static void cancelHourlyAlarm(Context app) {
        ((AlarmManager) app.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(app));
    }

    private static void scheduleHourlyJob(Context app) {
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(ID_HOURLY, new ComponentName(app, UpdateService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60000)
                .build());
    }

    public static final class UpdateService extends JobService {
        @Override
        public boolean onStartJob(final JobParameters params) {
            final Context app = getApplicationContext();
            final int id = params.getJobId();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        if (id == ID_HOURLY) {
                            Updater.runHourly(app);
                        } else {
                            Updater.run(app, Store.background(app), 8000, id == ID_KICK ? 5 * 60 * 1000L : 15 * 60 * 1000L);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        jobFinished(params, false);
                    }
                }
            }, "skypix-update").start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }


    /**
     * Listens for the phone being unlocked and plays a 5 second weather animation on the widgets.
     * Android only delivers the unlock broadcast to a running process, hence the (silent, minimised)
     * foreground service. It does nothing else and can be switched off in settings.
     */
    public static final class AnimService extends Service {
        static volatile boolean running;
        static volatile long lastPlayMs;
        private static volatile int generation;

        static volatile String lastTrigger = "";

        /** Plays the 5 second animation on every widget. A newer call cancels an older one. */
        static void play(final Context ctx, final String trigger) {
            final Context app = ctx.getApplicationContext();
            final int gen = ++generation;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        if (!Store.animate(app)) return;
                        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                        if (pm != null && pm.isPowerSaveMode()) {
                            lastTrigger = trigger + " (skipped: battery saver)";
                            return;
                        }
                        Cards.Data d = Store.data(app);
                        if (!d.ready()) {
                            lastTrigger = trigger + " (skipped: no weather yet)";
                            return;
                        }
                        // let the launcher finish its own unlock transition before the flipbook starts
                        Thread.sleep(trigger.equals("test") ? 0 : 400);
                        if (gen != generation) return;
                        lastPlayMs = System.currentTimeMillis();
                        lastTrigger = trigger;
                        WidgetUpdater.animateAll(app, d, Ui.isDark(app));
                        Thread.sleep(WidgetUpdater.ANIM_FRAMES * (long) WidgetUpdater.ANIM_INTERVAL_MS + 200);
                        if (gen == generation) WidgetUpdater.updateAll(app);
                    } catch (Throwable e) {
                        Ui.report("animation", e);
                    }
                }
            }, "skypix-anim").start();
        }

        private final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String why = Intent.ACTION_SCREEN_ON.equals(i.getAction()) ? "screen on" : "unlock";
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    // phones with no lock screen never send USER_PRESENT-after-unlock, so start right away
                    KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && km.isKeyguardLocked()) return;
                }
                play(c, why);
            }
        };

        @Override
        public void onCreate() {
            super.onCreate();
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("anim", "Widget animations", NotificationManager.IMPORTANCE_MIN));
            Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification n = new Notification.Builder(this, "anim")
                    .setSmallIcon(R.drawable.ic_stat_weather)
                    .setContentTitle("Skypix is ready to animate your widgets")
                    .setContentText("Turn this off in Skypix settings")
                    .setContentIntent(PendingIntent.getActivity(this, 21, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, n);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screen, f);
            running = true;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            running = false;
            try {
                unregisterReceiver(screen);
            } catch (IllegalArgumentException ignored) {
            }
            super.onDestroy();
        }
    }

    public static final class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context app = ctx.getApplicationContext();
            if (Store.hourly(app)) {
                scheduleHourlyAlarm(app);
                scheduleHourlyJob(app);
            }
        }
    }

    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context app = ctx.getApplicationContext();
            sync(app);
            kick(app);
        }
    }
}
