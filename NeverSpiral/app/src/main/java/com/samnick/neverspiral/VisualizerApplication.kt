package com.samnick.neverspiral

import android.app.Application

/**
 * Installs a custom uncaught-exception handler so a crash gets written to [CrashLog] before the
 * process dies, then re-raises to the previous (system default) handler so the OS still shows its
 * usual "app has stopped" behavior -- this only adds a side effect, it doesn't swallow crashes.
 */
class VisualizerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.record(this, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
