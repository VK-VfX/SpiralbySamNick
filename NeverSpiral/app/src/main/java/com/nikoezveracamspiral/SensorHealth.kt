package com.nikoezveracamspiral

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved dark test: when, with which camera, and the bad pixels it found. */
class SensorRun(
    val timeMillis: Long,
    val camera: String,
    val width: Int,
    val height: Int,
    val iso: Int,
    val exposureMs: Int,
    val spots: List<Spot>,
)

enum class SensorStatus(val label: String) {
    HEALTHY("No hot pixels found"),
    MINOR("A few faint hot pixels"),
    ATTENTION("Visible hot pixels"),
}

/** The result of the camera screen's sensor test, as it is shown. */
sealed class SensorCheckState {
    object Idle : SensorCheckState()
    object Running : SensorCheckState()
    class Done(val run: SensorRun) : SensorCheckState()
    class Failed(val message: String) : SensorCheckState()
}

/**
 * Sensor health: a dark test that finds the camera sensor's hot pixels (points that glow in dark,
 * high-gain pictures), remembers every run so changes show up over time, and writes a report that
 * can go to Google support. Nothing leaves the phone unless the report is shared.
 */
object SensorHealth {
    private const val PREFS = "sensor_health"
    private const val KEY = "runs"
    private const val KEEP = 12

    /** Brightness of the frame (0..255) above which the lens clearly was not covered. */
    private const val MAX_DARK_MEAN = 30

    class Analysis(val mean: Float, val width: Int, val height: Int, val spots: List<Spot>)

    /** Reads the dark frames and returns the specks that appear in most of them, or null if the frames can't be read. */
    fun analyse(jpegs: List<ByteArray>): Analysis? {
        val perFrame = ArrayList<List<Spot>>()
        var mean = 0f
        var w = 0
        var h = 0
        for (jpeg in jpegs) {
            val bmp = PhotoProcessor.decodeUpright(jpeg, 16_000_000) ?: continue
            try {
                w = bmp.width
                h = bmp.height
                val luma = ByteArray(w * h)
                val row = IntArray(w)
                var total = 0L
                for (y in 0 until h) {
                    bmp.getPixels(row, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val p = row[x]
                        val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                        luma[y * w + x] = l.toByte()
                        total += l
                    }
                }
                mean += total.toFloat() / (w * h)
                perFrame.add(HotPixels.find(luma, w, h, minPeak = 40, minContrast = 28, maxRing = 40))
            } finally {
                bmp.recycle()
            }
        }
        if (perFrame.isEmpty()) return null
        mean /= perFrame.size
        val need = if (perFrame.size >= 3) 2 else perFrame.size
        return Analysis(mean, w, h, HotPixels.consistent(perFrame, need))
    }

    fun lensWasCovered(a: Analysis): Boolean = a.mean <= MAX_DARK_MEAN

    fun status(spots: List<Spot>): SensorStatus = when {
        spots.isEmpty() -> SensorStatus.HEALTHY
        spots.size <= 3 && spots.all { it.peak < 150 } -> SensorStatus.MINOR
        else -> SensorStatus.ATTENTION
    }

    /** One line about how the latest run compares with the first one for the same camera, or null for the first run. */
    fun trend(runs: List<SensorRun>, latest: SensorRun): String? {
        val same = runs.filter { it.camera == latest.camera && it.timeMillis < latest.timeMillis }
        val first = same.minByOrNull { it.timeMillis } ?: return null
        val date = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(first.timeMillis))
        val n = latest.spots.size
        val m = first.spots.size
        val peakNow = latest.spots.maxOfOrNull { it.peak } ?: 0
        val peakThen = first.spots.maxOfOrNull { it.peak } ?: 0
        return when {
            n > m -> "$n hot pixel${if (n == 1) "" else "s"} now, $m at the first check on $date."
            n < m -> "$n hot pixel${if (n == 1) "" else "s"} now, $m at the first check on $date."
            peakNow >= peakThen + 25 -> "Same number as on $date, but the brightest is stronger (${peakNow} vs ${peakThen})."
            else -> "No change since the first check on $date."
        }
    }

    fun describe(s: Spot): String =
        "${(s.x * 100).toInt()}% across, ${(s.y * 100).toInt()}% down, brightness ${s.peak}"

    /** A plain-text report of every run, for sharing with support. */
    fun report(device: String, android: String, appVersion: String, runs: List<SensorRun>): String = buildString {
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.US)
        appendLine("Camera sensor check (hot pixels)")
        appendLine("Phone: $device")
        appendLine("Android: $android")
        appendLine("App: Niko's Cam $appVersion")
        appendLine()
        appendLine("How it works: three dark frames are taken with the lens covered (high ISO, long exposure) and any point that is much brighter than its surroundings in most frames is listed. Brightness is 0 to 255.")
        appendLine()
        if (runs.isEmpty()) appendLine("No checks have been run yet.")
        for (r in runs.sortedBy { it.timeMillis }) {
            appendLine("${fmt.format(Date(r.timeMillis))}  ${r.camera}  ${r.width}x${r.height}  ISO ${r.iso}  ${r.exposureMs} ms")
            appendLine("  Result: ${status(r.spots).label} (${r.spots.size})")
            r.spots.forEachIndexed { i, s -> appendLine("  ${i + 1}. at pixel ${s.xPx}, ${s.yPx} (${describe(s)})") }
        }
    }

    // ---- storage ----

    fun load(context: Context): List<SensorRun> = try {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]"
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val spots = o.getJSONArray("spots")
            SensorRun(
                o.getLong("t"), o.getString("cam"), o.getInt("w"), o.getInt("h"), o.getInt("iso"), o.getInt("ms"),
                (0 until spots.length()).map { j ->
                    val s = spots.getJSONObject(j)
                    Spot(s.getInt("x"), s.getInt("y"), s.getDouble("fx").toFloat(), s.getDouble("fy").toFloat(), s.getInt("p"), s.getInt("c"))
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, runs: List<SensorRun>) {
        val arr = JSONArray()
        for (r in runs.takeLast(KEEP)) {
            val spots = JSONArray()
            for (s in r.spots) {
                spots.put(JSONObject().put("x", s.xPx).put("y", s.yPx).put("fx", s.x.toDouble()).put("fy", s.y.toDouble()).put("p", s.peak).put("c", s.contrast))
            }
            arr.put(JSONObject().put("t", r.timeMillis).put("cam", r.camera).put("w", r.width).put("h", r.height).put("iso", r.iso).put("ms", r.exposureMs).put("spots", spots))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
