package com.nikoezveracamspiral

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Report a problem": gathers device info, what the cameras can do, the current settings and the
 * app's recent log into one text file and hands it to the share sheet, so a bug on a real phone
 * arrives with the details needed to fix it.
 */
object Diagnostics {
    private fun cameraSummary(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = StringBuilder()
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",") ?: "-"
                val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString() else "n/a"
                val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                out.append("camera $id: $facing, hardware level $level, sensor orientation $orientation, ")
                    .append("zoom range $zoom, active array ${active?.width()}x${active?.height()}, capabilities [$caps]\n")
            } catch (e: Exception) {
                out.append("camera $id: unreadable (${e.message})\n")
            }
        }
        return out.toString()
    }

    private fun recentLog(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600", "-v", "time"))
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "log unavailable: ${e.message}"
    }

    suspend fun share(context: Context, controller: CameraController) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val report = File(dir, "nikos-cam-report.txt")
            val text = buildString {
                appendLine("Niko's Cam ${UpdateManager.installedVersionName(context)} (${UpdateManager.installedVersionCode(context)})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("Cameras:")
                append(cameraSummary(context))
                appendLine()
                appendLine("Settings: mode=${controller.mode}, raw=${controller.isRawEnabled}, hdr=${controller.hdrEnabled}/${controller.hdrStrength}, ")
                appendLine("  ratio=${controller.aspectRatio}, quality=${controller.jpegQuality}, look=${controller.look.lut?.name ?: controller.look.filter}, ")
                appendLine("  video=${controller.videoConfig}")
                controller.lastError?.let { appendLine("Last error: $it") }
                appendLine()
                appendLine("Recent log:")
                append(recentLog())
            }
            report.writeText(text)
            report
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Niko's Cam problem report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share problem report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
