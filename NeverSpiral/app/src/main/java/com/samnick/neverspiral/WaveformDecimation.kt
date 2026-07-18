package com.samnick.neverspiral

import kotlin.math.abs

/**
 * Decimates raw PCM [waveform] samples into a fixed-size [out] buffer by taking the signed sample
 * of largest magnitude within each bucket, not an average -- an average would smear out exactly
 * the sharp transients a waveform trace exists to show. [out]'s size is fixed regardless of how
 * many raw samples [AudioCaptureService] happened to hand back in a given buffer (that size
 * varies with however much the system had ready to read), so a trace's visual density stays
 * constant rather than fluctuating with buffer size. Shared by every mode that renders the raw
 * waveform (Lava Waveform, White Waveform) rather than duplicated per file, since the decimation
 * itself has nothing mode-specific about it -- only what each mode draws from the result differs.
 */
internal fun decimateWaveform(waveform: FloatArray, out: FloatArray) {
    val n = waveform.size
    val pointCount = out.size
    if (n == 0) {
        out.fill(0f)
        return
    }
    if (n <= pointCount) {
        for (i in out.indices) out[i] = waveform[(i.toLong() * n / pointCount).toInt().coerceIn(0, n - 1)]
        return
    }
    val bucket = n / pointCount
    for (i in out.indices) {
        val start = i * bucket
        val end = if (i == pointCount - 1) n else start + bucket
        var best = 0f
        for (j in start until end) {
            val v = waveform[j]
            if (abs(v) > abs(best)) best = v
        }
        out[i] = best
    }
}
