package com.nikoezveracamspiral

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Runs the whole Portrait pipeline on a 12 MP photo of a person, on the emulator, with the real
 * depth model and the real person model, under a heap about the size of a Pixel 8a's. It
 * records how long each style takes and how much memory it uses, and leaves the pictures behind.
 */
@RunWith(AndroidJUnit4::class)
class PortraitPipelineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val target = inst.targetContext

    /** A 3000 x 4000 JPEG made from the public-domain test portrait. */
    private fun testPhoto(): ByteArray {
        val src = inst.context.assets.open("person.png").use { BitmapFactory.decodeStream(it) }
        val cropWidth = src.width * 3 / 4
        val crop = Bitmap.createBitmap(src, (src.width - cropWidth) / 2, 0, cropWidth, src.height)
        val big = Bitmap.createScaledBitmap(crop, 3000, 4000, true)
        val out = ByteArrayOutputStream()
        big.compress(Bitmap.CompressFormat.JPEG, 95, out)
        big.recycle()
        return out.toByteArray()
    }

    @Test
    fun everyStyleOnAPhotoThenEditingIt() {
        val profile = DeviceProfile.detect(target)
        PhotoProcessor.configure(profile.singleMaxPixels, profile.burstMaxPixels)
        TestResults.log("Device ${profile.label}: ${profile.ramGb} GB, tier ${profile.tier}, app heap ${profile.heapMb} MB, single-frame budget ${profile.singleMaxPixels} px")

        DepthModel.init(target)
        if (!DepthModel.isReady()) {
            val started = SystemClock.elapsedRealtime()
            runBlocking { DepthModel.download { } }
            TestResults.log("Depth model downloaded in ${SystemClock.elapsedRealtime() - started} ms")
        }
        assertTrue("the depth model should be ready", DepthModel.isReady())

        val bytes = testPhoto()
        val workSize = minOf(PortraitEffect.workSizeFor(Runtime.getRuntime().maxMemory()), 1024)
        TestResults.log("Working size $workSize px")
        TestResults.memory("before")

        var firstKept: Pair<ByteArray, PortraitAnalysis>? = null
        for (style in listOf(PortraitStyle.NATURAL, PortraitStyle.BOKEH, PortraitStyle.STAGE, PortraitStyle.MONO)) {
            val kept = arrayOfNulls<Pair<ByteArray, PortraitAnalysis>>(1)
            val started = SystemClock.elapsedRealtime()
            val out = PhotoProcessor.processShots(
                listOf(bytes), PhotoProcessor.ToneMode.None, Look(), AspectOption.R4_3, false, 95, 0.5f,
                1f, 1f, false, style, null, null, workSize,
                portraitSink = { before, analysis -> kept[0] = before to analysis },
            )
            TestResults.log("${style.label}: ${SystemClock.elapsedRealtime() - started} ms, result ${out.size / 1024} KB, kept data: ${kept[0] != null}")
            TestResults.memory("after ${style.label}")
            assertNotNull("${style.label} should have found a subject and kept its data", kept[0])
            TestResults.saveJpeg("portrait_${style.name.lowercase()}.jpg", out)
            if (firstKept == null) {
                firstKept = kept[0]
                TestResults.saveJpeg("before_blur.jpg", kept[0]!!.first)
            }
        }

        // Editing it afterwards: a live preview, and a full re-render with another style and subject.
        val (before, analysis) = firstKept!!
        var started = SystemClock.elapsedRealtime()
        val preview = PhotoProcessor.portraitPreview(before, analysis, PortraitStyle.STAGE, 0.8f, Pair(0.5f, 0.4f), 900_000)
        TestResults.log("Editor preview: ${SystemClock.elapsedRealtime() - started} ms, ${preview?.width}x${preview?.height}")
        assertNotNull(preview)
        TestResults.saveBitmap("edit_preview_stage_strong.jpg", preview!!)

        started = SystemClock.elapsedRealtime()
        val rerendered = PhotoProcessor.portraitRerender(before, analysis, PortraitStyle.NATURAL, 0.9f, null, 95)
        TestResults.log("Editor full re-render: ${SystemClock.elapsedRealtime() - started} ms, ${(rerendered?.size ?: 0) / 1024} KB")
        assertNotNull(rerendered)
        TestResults.saveJpeg("edit_rerender_natural_strong.jpg", rerendered!!)

        // The saved record round-trips through the real store, as the app uses it.
        PortraitStore.save(target, "20000101_000000", before, analysis, PortraitStyle.NATURAL, 0.5f)
        val loaded = PortraitStore.load(target, "20000101_000000")
        assertNotNull(loaded)
        assertEquals(analysis.width, loaded!!.analysis.width)
        PortraitStore.delete(target, "20000101_000000")
        TestResults.memory("at the end")
    }
}
