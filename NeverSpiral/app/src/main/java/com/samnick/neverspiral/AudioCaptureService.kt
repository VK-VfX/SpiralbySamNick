package com.samnick.neverspiral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Foreground service that captures whatever the device is currently playing (via
 * [AudioPlaybackCaptureConfiguration], not the microphone) as stereo PCM, and publishes each
 * buffer's RMS amplitude, FFT bands, and a decimated left/right trace to [AudioAnalyzer]. Working
 * this way means it reacts identically whether playback is on the speaker, wired headphones, or
 * Bluetooth, unlike listening through the mic.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system requires startForeground() to be called within moments of
        // startForegroundService(), so this must happen before any early return.
        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        startCapture(projection)
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "spiral_audio_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Music visualizer", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Visualizing audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun startCapture(projection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 44100
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: UnsupportedOperationException) {
            stopSelf()
            return
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            stopSelf()
            return
        }

        audioRecord = record
        record.startRecording()

        job = scope.launch {
            val buffer = ShortArray(minBufferSize)
            try {
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        analyzeStereo(buffer, read)
                    } else if (read < 0) {
                        break // read error (e.g. record was stopped from under us)
                    }
                }
            } catch (_: IllegalStateException) {
                // Record was released while a read was in flight; nothing left to do.
            }
        }
    }

    /** [buffer] holds interleaved L,R 16-bit samples; [length] is the number of samples read. */
    private fun analyzeStereo(buffer: ShortArray, length: Int) {
        val frames = length / 2
        if (frames <= 0) return

        var sumSquares = 0.0
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            val l = buffer[f * 2]
            val r = buffer[f * 2 + 1]
            val avg = (l + r) / 2
            mono[f] = avg.toShort()
            val normalized = avg.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / frames).toFloat()
        val bands = if (frames >= SpectrumAnalyzer.FFT_SIZE) SpectrumAnalyzer.computeBands(mono) else null

        val waveform = FloatArray(frames) { i -> mono[i].toFloat() / Short.MAX_VALUE }

        AudioAnalyzer.publish(rms, bands, waveform)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // Already stopped; nothing to clean up.
            }
            record.release()
        }
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        AudioAnalyzer.reset()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }
}
