package com.nikoezveracamspiral

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Where the portrait depth model stands on this phone. */
sealed interface DepthStatus {
    data object Missing : DepthStatus
    data class Downloading(val progress: Float) : DepthStatus
    data object Ready : DepthStatus
    data class Failed(val message: String) : DepthStatus
}

/**
 * The AI that tells Portrait mode how far away everything in a photo is: Intel's MiDaS "small"
 * network, which looks at one ordinary photo and returns a relative depth map (nearer = brighter),
 * the same kind of depth a dual-lens or LiDAR camera would measure. Portrait uses it to blur things
 * by their real distance, the way a large lens does.
 *
 * The 63 MB model is downloaded once (on Wi-Fi, or when you tap to allow it), checked against a
 * fixed checksum and kept in the app's private storage, where it survives app updates. Until it is
 * there, Portrait falls back to the subject-outline blur.
 */
object DepthModel {
    private const val MODEL_URL = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"
    const val SIZE_BYTES = 66_338_288L
    private const val SHA256 = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd"
    private const val SIDE = 256

    @Volatile
    private var file: File? = null
    private var interpreter: Interpreter? = null

    /** Set when the model can't run on this phone, so it isn't retried on every photo. */
    @Volatile
    private var broken = false

    fun init(context: Context) {
        file = File(File(context.filesDir, "models").apply { mkdirs() }, "midas_v2_1_small.tflite")
    }

    fun isReady(): Boolean {
        val f = file ?: return false
        return !broken && f.isFile && f.length() == SIZE_BYTES
    }

    fun status(): DepthStatus = if (isReady()) DepthStatus.Ready else DepthStatus.Missing

    /** Downloads and verifies the model. Throws on any failure; nothing half-written is kept. */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file ?: throw IllegalStateException("Depth model storage isn't ready")
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) throw IllegalStateException("The download server answered ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReported = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        done += count
                        val percent = (done * 100 / SIZE_BYTES).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress((done.toFloat() / SIZE_BYTES).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != SIZE_BYTES || !hex.equals(SHA256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("The downloaded model didn't match its checksum")
            }
            synchronized(this@DepthModel) {
                interpreter?.close()
                interpreter = null
                target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("Couldn't save the model")
                broken = false
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Runs the network on [work] (the photo at working size) and returns its raw 256x256 depth
     * values (larger = nearer; the scale is relative), or null if the model isn't there or fails.
     */
    @Synchronized
    fun estimate(work: IntArray, ww: Int, wh: Int): FloatArray? {
        val model = file
        if (model == null || !isReady()) return null
        return try {
            val net = interpreter ?: Interpreter(model, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
            val small = PortraitEffect.downscale(work, ww, wh, SIDE, SIDE)
            val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (p in small) {
                input.putFloat((((p shr 16) and 0xFF) / 255f - mean[0]) / std[0])
                input.putFloat((((p shr 8) and 0xFF) / 255f - mean[1]) / std[1])
                input.putFloat(((p and 0xFF) / 255f - mean[2]) / std[2])
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder())
            net.run(input, output)
            output.rewind()
            FloatArray(SIDE * SIDE) { output.float }
        } catch (e: Throwable) {
            Log.w("DepthModel", "Depth estimation failed; using the outline blur", e)
            broken = true
            interpreter?.close()
            interpreter = null
            null
        }
    }
}
