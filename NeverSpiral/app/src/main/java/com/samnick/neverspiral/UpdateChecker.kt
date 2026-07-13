package com.samnick.neverspiral

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A GitHub-Releases-based OTA update path -- the same pattern F-Droid-style apps use when they
 * aren't distributed through the Play Store: check the repo's latest release for an attached
 * APK, download it via [DownloadManager], then hand it to the system installer. Requires the
 * repo to be public, since an unauthenticated request to a private repo's releases API returns
 * 404/403 -- [checkLatest] fails closed (returns null) rather than crashing when that happens.
 */
object UpdateChecker {
    private const val RELEASES_API_URL = "https://api.github.com/repos/VK-VfX/SpiralbySamNick/releases/latest"

    data class LatestRelease(
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val apkDownloadUrl: String,
    )

    /** The latest published release with an APK asset attached, or null if none is reachable. */
    suspend fun checkLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(RELEASES_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val downloadUrl = apkUrl ?: return@withContext null

            LatestRelease(
                tagName = json.optString("tag_name"),
                name = json.optString("name", json.optString("tag_name")),
                htmlUrl = json.optString("html_url"),
                apkDownloadUrl = downloadUrl,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether [release] is actually newer than the installed app, comparing [release]'s tag
     * (`v3.0.1`, matching [android.content.pm.PackageInfo.versionName] once the leading `v` is
     * stripped) against the installed `versionName` component-by-component. Without this, every
     * reachable release looked "available" even when it was the exact build already installed --
     * the CI workflow tags releases with the real app version specifically so this comparison is
     * possible; a release tagged some other way (or an unparseable version) is conservatively
     * treated as not newer, so a check never wrongly nags about a phantom update.
     */
    fun isNewerThanInstalled(context: Context, release: LatestRelease): Boolean {
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: return false
        val remoteParts = parseVersion(release.tagName) ?: return false
        val installedParts = parseVersion(installed) ?: return false
        for (i in 0 until maxOf(remoteParts.size, installedParts.size)) {
            val remote = remoteParts.getOrElse(i) { 0 }
            val current = installedParts.getOrElse(i) { 0 }
            if (remote != current) return remote > current
        }
        return false
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.removePrefix("v")
        val parts = cleaned.split(".").map { it.toIntOrNull() }
        return if (parts.any { it == null }) null else parts.map { it!! }
    }

    /** Whether this app is currently allowed to prompt an APK install (always true below Android 8). */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Sends the user to the system screen to grant "install unknown apps" for this app. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
    }

    private const val DOWNLOAD_POLL_INTERVAL_MS = 500L

    // Bounds the wait for a download that stalls (no network, a paused/never-resumed transfer) --
    // without this the caller's "Downloading..." state hung forever with no way to recover.
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

    /**
     * Downloads [release]'s APK via DownloadManager, waits for completion, then launches the
     * system installer. Returns whether the install intent was actually launched; false covers a
     * failed/timed-out download or a missing destination file, so the caller can show a retry
     * state instead of silently assuming success.
     */
    suspend fun downloadAndInstall(context: Context, release: LatestRelease): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "sams-music-viz-${release.tagName}.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        // A leftover file from a previous attempt (a failed download, or retrying the same
        // release) can make DownloadManager refuse to write to the same path again.
        if (destinationFile.exists()) destinationFile.delete()

        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl))
            .setTitle("Sam's Music Viz ${release.tagName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)

        val succeeded = withContext(Dispatchers.IO) {
            var result = false
            var elapsedMs = 0L
            while (elapsedMs < DOWNLOAD_TIMEOUT_MS) {
                var stillPending = true
                downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                result = true
                                stillPending = false
                            }
                            DownloadManager.STATUS_FAILED -> stillPending = false
                        }
                    } else {
                        stillPending = false
                    }
                }
                if (!stillPending) break
                delay(DOWNLOAD_POLL_INTERVAL_MS)
                elapsedMs += DOWNLOAD_POLL_INTERVAL_MS
            }
            result
        }

        if (!succeeded || !destinationFile.exists()) return false

        // DownloadManager.getUriForDownloadedFile() is built for downloads in the public Downloads
        // collection and is unreliable (often an unopenable URI) for a file saved under an
        // app-private external directory like this one -- FileProvider is what actually grants the
        // system installer read access to a file living in our private storage.
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destinationFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }
}
