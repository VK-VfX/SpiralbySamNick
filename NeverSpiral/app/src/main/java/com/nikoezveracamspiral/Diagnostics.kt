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
                try {
                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val jpeg = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                    val yuv = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.maxByOrNull { it.width.toLong() * it.height }
                    val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString { "${it.lower}-${it.upper}" }
                    val fast = map?.highSpeedVideoSizes?.joinToString { "${it.width}x${it.height}" }
                    out.append("  largest JPEG $jpeg, largest YUV $yuv, fps ranges [$fps], high-speed sizes [$fast]\n")
                    val dist = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                    val distNames = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
                    out.append("  distortion correction modes [${dist?.joinToString { distNames[it] ?: it.toString() } ?: "none"}]\n")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val names = mapOf(0 to "AUTOMATIC", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT")
                        val ext = manager.getCameraExtensionCharacteristics(id).supportedExtensions.joinToString { names[it] ?: it.toString() }
                        out.append("  camera extensions [$ext]\n")
                    }
                } catch (e: Exception) {
                    out.append("  stream/extension info unavailable (${e.message})\n")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (pid in c.physicalCameraIds) {
                        try {
                            val pc = manager.getCameraCharacteristics(pid)
                            val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                            val minFocus = pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                            out.append("  physical $pid: focal [$focal] mm, closest focus $minFocus diopters\n")
                        } catch (e: Exception) {
                            out.append("  physical $pid: not readable\n")
                        }
                    }
                }
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
                val profile = controller.deviceProfile
                appendLine("Profile: ${profile.label}, ${profile.ramGb} GB, tier ${profile.tier}, burst budget ${profile.burstMaxPixels} px, heap limit ${profile.heapMb} MB, foldable=${profile.isFoldable}")
                appendLine("Zoom stops: ${controller.engine.lensSummary()}")
                val dm = context.resources.displayMetrics
                appendLine("Display: ${dm.widthPixels}x${dm.heightPixels} px, ${dm.densityDpi} dpi")
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
