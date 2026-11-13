package com.nikoezveracamspiral

import java.util.Locale
import kotlin.math.roundToInt

/** Converts a shutter speed in nanoseconds to a human label like "1/125" or "2.5\"" -- the
 * fraction-or-seconds convention every manual camera dial uses, rather than a raw millisecond
 * number nobody reads a shutter speed in. Plain Kotlin math, so it's covered by a fast JVM unit
 * test rather than needing an on-device/Robolectric run. */
fun shutterSpeedLabel(nanos: Long): String {
    val seconds = nanos / 1_000_000_000.0
    return if (seconds >= 1.0) {
        String.format(Locale.US, "%.1f\"", seconds)
    } else {
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        "1/$denominator"
    }
}

/** A log-spaced sequence of shutter speeds between [minNanos] and [maxNanos], matching the
 * discrete stops a real manual camera shutter dial exposes rather than a continuous slider over
 * raw nanoseconds. Falls back to just [minNanos] if the sensor's range doesn't overlap any
 * standard stop at all (an unusually narrow manual-exposure range). */
fun standardShutterSpeedsNanos(minNanos: Long, maxNanos: Long): List<Long> {
    val standardFractionDenominators = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 100, 60, 30, 15, 8, 4, 2, 1)
    val fromFractions = standardFractionDenominators.map { 1_000_000_000L / it }
    val standardWholeSeconds = listOf(2L, 4L, 8L, 15L, 30L).map { it * 1_000_000_000L }
    val combined = (fromFractions + standardWholeSeconds).filter { it in minNanos..maxNanos }.sorted()
    return combined.ifEmpty { listOf(minNanos) }
}

/** A log-spaced sequence of ISO sensitivities between [minIso] and [maxIso], matching the
 * discrete stops a real manual camera ISO dial exposes. */
fun standardIsoValues(minIso: Int, maxIso: Int): List<Int> {
    val standard = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
    val filtered = standard.filter { it in minIso..maxIso }
    return filtered.ifEmpty { listOf(minIso) }
}
