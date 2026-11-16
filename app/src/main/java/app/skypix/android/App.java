package app.skypix.android;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Installs a crash catcher that shows the stack trace on screen so problems can be reported precisely. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AndroidGfx.init(this);
        app.skypix.core.Glyphs.cats = Store.cats(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String report = "Purrcast 1.0.11 · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                            + Build.MANUFACTURER + " " + Build.MODEL + "\nThread: " + t.getName() + "\n\n" + sw;
                    Intent i = new Intent(App.this, CrashActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("report", report);
                    startActivity(i);
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                } catch (Throwable ignored) {
                    if (previous != null) previous.uncaughtException(t, e);
                }
            }
        });
    }
}
