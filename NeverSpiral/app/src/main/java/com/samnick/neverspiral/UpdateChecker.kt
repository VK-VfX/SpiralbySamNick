package com.samnick.neverspiral

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    /** Downloads [release]'s APK via DownloadManager, waits for completion, then launches the system installer. */
    suspend fun downloadAndInstall(context: Context, release: LatestRelease) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl))
            .setTitle("Sam's Visualizer ${release.tagName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "sams-visualizer-${release.tagName}.apk")

        val downloadId = downloadManager.enqueue(request)

        val uri = withContext(Dispatchers.IO) {
            var resultUri: Uri? = null
            var pending = true
            while (pending) {
                downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                resultUri = downloadManager.getUriForDownloadedFile(downloadId)
                                pending = false
                            }
                            DownloadManager.STATUS_FAILED -> pending = false
                        }
                    } else {
                        pending = false
                    }
                }
                if (pending) delay(500)
            }
            resultUri
        } ?: return

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
