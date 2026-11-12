package com.nikoezveracamspiral

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateStatus
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Over-the-air updates. Every CI build publishes its APK plus a small `version.json` to the
 * `never-spiral-apk-builds` branch; the app reads that file, compares version codes, downloads
 * the APK into its cache and hands it to Android's package installer. Debug builds are signed
 * with one committed keystore, so the system accepts the update in place and keeps app data.
 */
object UpdateManager {
    private const val BASE = "https://raw.githubusercontent.com/VK-VfX/SpiralbySamNick/never-spiral-apk-builds"

    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Returns the newer release, or null if this install is current. Throws on network errors. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL("$BASE/version.json?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            if (connection.responseCode != 200) throw IllegalStateException("Update server answered ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val info = UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.optString("apkUrl", "$BASE/never-spiral-debug.apk"),
                notes = json.optString("notes", ""),
            )
            if (info.versionCode > installedVersionCode(context)) info else null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "NeverSpiral-${info.versionName}.apk")
        val connection = URL(info.apkUrl + "?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) throw IllegalStateException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    /** Opens the system installer for [file]. Returns false if Android first needs the user to
     * allow this app to install packages; the settings screen for that is opened instead. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
