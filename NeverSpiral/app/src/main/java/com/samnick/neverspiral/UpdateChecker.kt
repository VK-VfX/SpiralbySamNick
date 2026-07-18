package com.samnick.neverspiral

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A GitHub-Releases-based update check -- the same pattern F-Droid-style apps use when they
 * aren't distributed through the Play Store: check the repo's latest release, and if it's newer,
 * send the user to the release page in their browser to download and install it manually.
 * Requires the repo to be public, since an unauthenticated request to a private repo's releases
 * API returns 404/403 -- [checkLatest] fails closed (returns null) rather than crashing when that
 * happens.
 *
 * Deliberately does *not* download and self-install the APK: that path needed the
 * REQUEST_INSTALL_PACKAGES permission, which combined with this app's audio-capture permissions
 * reads to Play Protect's heuristics like a trojan dropper (listens to audio + can silently
 * install more software), triggering an "app may be unsafe" warning on every sideloaded install.
 * A manual browser download avoids that permission entirely, at the cost of one extra tap.
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

    /** Opens [release]'s GitHub release page in the browser so the user can download and install
     * the APK manually -- see the class doc for why this doesn't self-install. */
    fun openReleasePage(context: Context, release: LatestRelease) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
    }
}
