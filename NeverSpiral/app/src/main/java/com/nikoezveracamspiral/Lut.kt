package com.nikoezveracamspiral

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * A 3D colour lookup table imported from a `.cube` file. [data] holds size^3 RGB triples in 0..1
 * with red varying fastest, the layout the format defines.
 */
class Lut(val name: String, val size: Int, val data: FloatArray) {
    /** A best-fit 4x5 colour matrix for the live preview, which can't run a 3D table: the photo,
     * the editor and (approximately) video use it, saved photos use the exact table. */
    val fit: FloatArray by lazy { fitMatrix() }

    /** Trilinear lookup: [r], [g], [b] in 0..255, result written to [out] in 0..255. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = (r / 255f).coerceIn(0f, 1f) * n1
        val fg = (g / 255f).coerceIn(0f, 1f) * n1
        val fb = (b / 255f).coerceIn(0f, 1f) * n1
        val r0 = minOf(fr.toInt(), n1 - 1).coerceAtLeast(0)
        val g0 = minOf(fg.toInt(), n1 - 1).coerceAtLeast(0)
        val b0 = minOf(fb.toInt(), n1 - 1).coerceAtLeast(0)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        val n2 = size * size
        for (c in 0 until 3) {
            fun at(ri: Int, gi: Int, bi: Int) = data[(ri + gi * size + bi * n2) * 3 + c]
            val c00 = at(r0, g0, b0) * (1 - tr) + at(r0 + 1, g0, b0) * tr
            val c10 = at(r0, g0 + 1, b0) * (1 - tr) + at(r0 + 1, g0 + 1, b0) * tr
            val c01 = at(r0, g0, b0 + 1) * (1 - tr) + at(r0 + 1, g0, b0 + 1) * tr
            val c11 = at(r0, g0 + 1, b0 + 1) * (1 - tr) + at(r0 + 1, g0 + 1, b0 + 1) * tr
            val c0 = c00 * (1 - tg) + c10 * tg
            val c1 = c01 * (1 - tg) + c11 * tg
            out[c] = (c0 * (1 - tb) + c1 * tb).coerceIn(0f, 1f) * 255f
        }
    }

    private fun fitMatrix(): FloatArray {
        val steps = 12
        val ata = Array(4) { DoubleArray(4) }
        val atb = Array(4) { DoubleArray(3) }
        val out = FloatArray(3)
        for (ri in 0 until steps) for (gi in 0 until steps) for (bi in 0 until steps) {
            val x = doubleArrayOf(ri / (steps - 1.0), gi / (steps - 1.0), bi / (steps - 1.0), 1.0)
            sample((x[0] * 255).toFloat(), (x[1] * 255).toFloat(), (x[2] * 255).toFloat(), out)
            for (i in 0 until 4) {
                for (j in 0 until 4) ata[i][j] += x[i] * x[j]
                for (c in 0 until 3) atb[i][c] += x[i] * (out[c] / 255.0)
            }
        }
        for (i in 0 until 4) ata[i][i] += 1e-6
        // Gauss-Jordan on [ata | atb].
        val m = Array(4) { i -> DoubleArray(7) { j -> if (j < 4) ata[i][j] else atb[i][j - 4] } }
        for (col in 0 until 4) {
            var pivot = col
            for (r in col + 1 until 4) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val d = m[col][col]
            if (kotlin.math.abs(d) < 1e-12) continue
            for (j in 0 until 7) m[col][j] /= d
            for (r in 0 until 4) {
                if (r == col) continue
                val f = m[r][col]
                for (j in 0 until 7) m[r][j] -= f * m[col][j]
            }
        }
        val a = FloatArray(20)
        for (c in 0 until 3) {
            for (j in 0 until 3) a[c * 5 + j] = m[j][4 + c].toFloat()
            a[c * 5 + 4] = (m[3][4 + c] * 255.0).toFloat()
        }
        a[18] = 1f
        return a
    }

    companion object {
        /** Parses a `.cube` 3D LUT. Throws [IllegalArgumentException] with a readable message
         * for 1D tables and malformed files. */
        fun parseCube(name: String, text: String): Lut {
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> Unit
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs aren't supported; use a 3D .cube file")
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Bad LUT_3D_SIZE")
                    "DOMAIN_MIN" -> min = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> max = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    else -> {
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add((r - min[0]) / (max[0] - min[0]))
                                values.add((g - min[1]) / (max[1] - min[1]))
                                values.add((b - min[2]) / (max[2] - min[2]))
                            }
                        }
                    }
                }
            }
            require(size in 2..65) { "This doesn't look like a 3D .cube LUT (missing or unsupported LUT_3D_SIZE)" }
            require(values.size == size * size * size * 3) { "The LUT has ${values.size / 3} entries but its size needs ${size * size * size}" }
            return Lut(name, size, FloatArray(values.size) { values[it] })
        }
    }
}

/** Imported LUTs live in the app's private storage and survive restarts. */
object LutStore {
    private fun dir(context: Context) = File(context.filesDir, "luts").apply { mkdirs() }

    fun load(context: Context): List<Lut> =
        (dir(context).listFiles { f -> f.extension.equals("cube", true) } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f ->
                try {
                    Lut.parseCube(f.nameWithoutExtension, f.readText())
                } catch (e: Exception) {
                    null
                }
            }

    fun import(context: Context, uri: Uri): Lut {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Couldn't read that file")
        var name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "LUT"
        name = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "LUT" }.take(24)
        val lut = Lut.parseCube(name, text)
        File(dir(context), "$name.cube").writeText(text)
        return lut
    }

    fun delete(context: Context, lut: Lut) {
        File(dir(context), "${lut.name}.cube").delete()
    }
}
