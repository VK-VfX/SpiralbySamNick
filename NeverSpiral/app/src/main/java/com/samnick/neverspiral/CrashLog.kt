package com.samnick.neverspiral

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the most recent uncaught exception to a small local file so it can be inspected from
 * Settings. There's no crash-reporting backend -- during solo on-device testing this is the only
 * way to see what actually broke after the app dies and relaunches, rather than just noticing it
 * happened. Only the latest crash is kept; this is a debugging aid, not a history log.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun record(context: Context, throwable: Throwable) {
        try {
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file(context).writeText("$timestamp\n\n$writer")
        } catch (e: Exception) {
            // Best-effort -- if we can't even write the crash log, there's nothing more useful to
            // do before the process actually dies.
        }
    }

    fun read(context: Context): String? {
        val f = file(context)
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
