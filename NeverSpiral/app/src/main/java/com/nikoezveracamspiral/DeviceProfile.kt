package com.nikoezveracamspiral

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class PerformanceTier(val label: String) { HIGH("High"), MID("Mid"), LOW("Basic") }

/**
 * What kind of phone this is, so the app can size its processing to it. Pixel phones are the
 * reference: the camera, zoom stops, lenses and video options come from what each phone's camera
 * reports at runtime (so a Pixel 8a, a Pixel 9 Pro XL or a Pixel Fold each get their own), while
 * this profile covers what the camera can't say: how much memory and speed the burst processing
 * can safely use.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val isPixel: Boolean,
    val isFoldable: Boolean,
    val isTablet: Boolean,
    val totalRamMb: Int,
    val tier: PerformanceTier,
    /** Pixel budget (width x height) for the merged burst and for single frames. */
    val burstMaxPixels: Int,
    val singleMaxPixels: Int,
    val heapMb: Int = 0,
) {
    val label: String get() = if (isPixel) model else "$manufacturer $model"
    val ramGb: Int get() = ((totalRamMb + 512) / 1024)

    companion object {
        fun detect(context: Context): DeviceProfile {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            val ramMb = (info.totalMem / (1024 * 1024)).toInt()
            val model = Build.MODEL ?: "Unknown"
            val isPixel = Build.MANUFACTURER.equals("Google", ignoreCase = true) && model.startsWith("Pixel", ignoreCase = true)
            // Pixel 9 Pro XL (16 GB) and Pixel 8 Pro (12 GB) are high; Pixel 8a, 7a, 7, 6 (6-8 GB) mid;
            // anything smaller is kept conservative.
            val tier = when {
                ramMb >= 10_500 -> PerformanceTier.HIGH
                ramMb >= 7_000 -> PerformanceTier.MID
                else -> PerformanceTier.LOW
            }
            // The budget follows the app's real heap limit, not just the phone's RAM: a merged burst
            // needs about 16 bytes per pixel and a single frame about 12, and a Pixel 8a's heap
            // ceiling (about half a 9 Pro XL's) was too small for a 13 MP merge.
            val heapBytes = Runtime.getRuntime().maxMemory()
            val burst = (heapBytes * 0.5 / 16.0).toInt().coerceIn(6_000_000, 17_000_000)
            val single = (heapBytes * 0.55 / 12.0).toInt().coerceIn(8_000_000, 24_000_000)
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER,
                model = model,
                isPixel = isPixel,
                isFoldable = model.contains("Fold", ignoreCase = true),
                isTablet = model.contains("Tablet", ignoreCase = true),
                totalRamMb = ramMb,
                tier = tier,
                burstMaxPixels = burst,
                singleMaxPixels = single,
                heapMb = (heapBytes / (1024 * 1024)).toInt(),
            )
        }
    }
}
