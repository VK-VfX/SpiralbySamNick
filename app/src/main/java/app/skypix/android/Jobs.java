package app.skypix.android;

import android.app.AlarmManager;
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
        Context app = ctx.getApplicationContext();
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
        if (Store.hourly(app) && Store.anyLocation(app)) scheduleHourlyAlarm(app);
        else cancelHourlyAlarm(app);
    }

    /** Refresh as soon as possible (one-shot). */
    static void kick(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Store.anyLocation(app)) return;
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(ID_KICK, new ComponentName(app, UpdateService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(15000)
                .build());
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
