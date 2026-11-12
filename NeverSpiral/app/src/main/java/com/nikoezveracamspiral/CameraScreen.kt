package com.nikoezveracamspiral

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * The entire camera surface: full-bleed live preview with the mode carousel, shutter, and Pro
 * mode's manual dials overlaid on top -- the same "controls float over the live image" layout
 * every modern flagship camera app uses, rather than letterboxing the preview behind a separate
 * control bar.
 */
@Composable
fun CameraScreen(controller: CameraController) {
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(CameraTheme.BACKGROUND)) {
        CameraPreview(
            controller = controller,
            onTap = { offset, size ->
                focusIndicator = offset
                val nx = (offset.x / size.width).coerceIn(0f, 1f)
                val ny = (offset.y / size.height).coerceIn(0f, 1f)
                controller.engine.tapToFocus(nx, ny)
            },
        )

        if (controller.isGridEnabled) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        focusIndicator?.let { position ->
            FocusIndicator(position = position, onFinished = { focusIndicator = null })
        }

        TopBar(controller = controller, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding())

        if (controller.isRecording) {
            RecordingPill(
                elapsedMs = controller.recordingElapsedMs,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp),
            )
        }

        controller.lastError?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = { controller.clearError() },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp),
            )
        }

        if (controller.countdownRemaining > 0) {
            Text(
                text = controller.countdownRemaining.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (controller.zoomRatio > 1.01f) {
            Text(
                text = String.format(Locale.US, "%.1fx", controller.zoomRatio),
                color = CameraTheme.TEXT_PRIMARY,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CameraTheme.SURFACE_RAISED.copy(alpha = 0.8f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (controller.mode == CaptureMode.PRO) {
                ProControlsPanel(controller)
            }
            ModeCarousel(controller)
            Spacer(modifier = Modifier.height(10.dp))
            CaptureControlsRow(
                controller = controller,
                onCapture = {
                    scope.launch { controller.capturePhoto(DEVICE_ROTATION_DEGREES) }
                },
                onStartVideo = {
                    scope.launch { controller.startVideo(DEVICE_ROTATION_DEGREES) }
                },
                onStopVideo = {
                    scope.launch { controller.stopVideo() }
                },
            )
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

/** This app locks its single Activity to portrait (see AndroidManifest), so the display's
 * rotation relative to the device's natural orientation is always 0 -- the formula CameraEngine
 * uses to derive JPEG/video orientation only needs that one fixed value, never a live
 * OrientationEventListener reading. */
private const val DEVICE_ROTATION_DEGREES = 0

private data class IntSize(val width: Int, val height: Int)

@Composable
private fun CameraPreview(
    controller: CameraController,
    onTap: (Offset, IntSize) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var viewSize by remember { mutableStateOf(IntSize(0, 0)) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset -> onTap(offset, viewSize) })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    controller.setZoom(controller.zoomRatio * zoom)
                }
            },
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        viewSize = IntSize(width, height)
                        scope.launch { openForPreview(controller, this@apply, texture, width, height) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        viewSize = IntSize(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        controller.stop()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }
}

private suspend fun openForPreview(controller: CameraController, textureView: TextureView, texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
    val cameraId = controller.engine.findCameraId(CameraCharacteristics.LENS_FACING_BACK)
        ?: controller.engine.findCameraId(CameraCharacteristics.LENS_FACING_FRONT)
        ?: return
    // CameraController.start() opens the camera itself; applyTransform is invoked from inside
    // it once that's done, so engine.lensFacing/sensorOrientation are already valid by the time
    // this reads them. (An earlier version of this function opened the camera here too, *and*
    // start() opened it again -- Android rejects opening a camera ID that's already open from the
    // same process, which crashed the app immediately on launch.)
    controller.start(cameraId, texture, viewWidth, viewHeight) { previewSize ->
        applyPreviewTransform(textureView, viewWidth, viewHeight, previewSize, controller.engine.sensorOrientation, controller.engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
    }
}

/**
 * Scales and rotates the TextureView's displayed buffer to center-crop-fill the view, built as
 * three separately-verifiable steps rather than one combined matrix (an earlier version combined
 * an axis-swapped RectToRect scale -- which cannot itself rotate anything, only stretch X and Y
 * independently -- with an *additional* explicit rotation on top, compounding into neither a
 * clean rotation nor a clean stretch; that's what the previous build's "sensors mostly inverted"
 * and "selfie cam is horizontal" bugs actually were).
 *
 * Without any transform, TextureView stretches the buffer's native `previewSize.width x height`
 * pixels to non-uniformly fill the view's `viewWidth x viewHeight` bounds -- so step 1 undoes
 * that distortion (restoring the buffer's true aspect ratio), step 2 rotates by the sensor's
 * actual mounting angle (read from CameraCharacteristics, not assumed from front/back) so the
 * image reads upright with the device held normally -- mirroring the front camera horizontally
 * afterward, the same "look in a mirror while framing a selfie" convention every camera app
 * uses for the *preview* only, not the saved photo -- and step 3 scales up to cover the view
 * (crop-to-fill), accounting for the axes having swapped if the rotation was 90 or 270 degrees.
 */
private fun applyPreviewTransform(textureView: TextureView, viewWidth: Int, viewHeight: Int, previewSize: Size, sensorOrientation: Int, isFrontFacing: Boolean) {
    if (viewWidth == 0 || viewHeight == 0) return
    val bufW = previewSize.width.toFloat()
    val bufH = previewSize.height.toFloat()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    val matrix = Matrix()
    matrix.postScale(bufW / viewWidth, bufH / viewHeight, centerX, centerY)
    matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
    if (isFrontFacing) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }
    val axesSwapped = sensorOrientation % 180 != 0
    val scale = if (axesSwapped) {
        max(viewWidth / bufH, viewHeight / bufW)
    } else {
        max(viewWidth / bufW, viewHeight / bufH)
    }
    matrix.postScale(scale, scale, centerX, centerY)

    textureView.setTransform(matrix)
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        val lineColor = Color.White.copy(alpha = 0.35f)
        drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = 1.dp.toPx())
        drawLine(lineColor, Offset(thirdW * 2, 0f), Offset(thirdW * 2, size.height), strokeWidth = 1.dp.toPx())
        drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = 1.dp.toPx())
        drawLine(lineColor, Offset(0f, thirdH * 2), Offset(size.width, thirdH * 2), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun FocusIndicator(position: Offset, onFinished: () -> Unit) {
    LaunchedEffect(position) {
        delay(700)
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            color = Color.White,
            topLeft = Offset(position.x - 40f, position.y - 40f),
            size = androidx.compose.ui.geometry.Size(80f, 80f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun TopBar(controller: CameraController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TopBarIcon(glyph = controller.flashMode.glyph) { controller.cycleFlash() }
        Spacer(modifier = Modifier.width(10.dp))
        TopBarIcon(glyph = if (controller.timerSeconds == 0) "⏱" else "${controller.timerSeconds}s", highlighted = controller.timerSeconds != 0) {
            controller.cycleTimer()
        }
        Spacer(modifier = Modifier.width(10.dp))
        TopBarIcon(glyph = "▦", highlighted = controller.isGridEnabled) {
            controller.isGridEnabled = !controller.isGridEnabled
        }
        if (controller.engine.isRawSupported) {
            Spacer(modifier = Modifier.width(10.dp))
            val scope = rememberCoroutineScope()
            TopBarIcon(glyph = "RAW", highlighted = controller.isRawEnabled, textGlyph = true) {
                scope.launch { controller.toggleRaw() }
            }
        }
    }
}

@Composable
private fun TopBarIcon(glyph: String, highlighted: Boolean = false, textGlyph: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) CameraTheme.ACCENT else CameraTheme.SURFACE.copy(alpha = 0.6f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (highlighted) Color.Black else CameraTheme.TEXT_PRIMARY,
            fontSize = if (textGlyph) 11.sp else 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RecordingPill(elapsedMs: Long, modifier: Modifier = Modifier) {
    val totalSeconds = elapsedMs / 1000
    val label = String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CameraTheme.SURFACE_RAISED.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(CameraTheme.CRITICAL))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = CameraTheme.TEXT_PRIMARY, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/** A dismissible banner for a failed camera action -- [CameraController] catches every failure
 * in its own suspend functions rather than letting them crash the app, so this is where the
 * user actually learns something went wrong (e.g. the camera was busy, a session reconfiguration
 * failed) instead of the shutter button just silently doing nothing. */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CameraTheme.CRITICAL.copy(alpha = 0.92f))
            .clickable { onDismiss() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("✕", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ModeCarousel(controller: CameraController) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        items(CaptureMode.entries) { candidate ->
            val selected = candidate == controller.mode
            Text(
                text = candidate.label,
                color = if (selected) CameraTheme.TEXT_PRIMARY else CameraTheme.TEXT_SECONDARY,
                fontSize = if (selected) 14.sp else 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 1.sp,
                modifier = Modifier.clickable {
                    if (controller.isRecording) return@clickable
                    controller.mode = candidate
                },
            )
        }
    }
}

@Composable
private fun CaptureControlsRow(
    controller: CameraController,
    onCapture: () -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryThumbnail(controller)

        ShutterButton(
            mode = controller.mode,
            isRecording = controller.isRecording,
            isBusy = controller.isCapturing,
            onClick = {
                when (controller.mode) {
                    CaptureMode.VIDEO -> if (controller.isRecording) onStopVideo() else onStartVideo()
                    else -> onCapture()
                }
            },
        )

        val scope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(CameraTheme.SURFACE)
                .clickable(enabled = !controller.isRecording && controller.engine.hasFrontCamera()) {
                    scope.launch { controller.switchCamera() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⟲", color = CameraTheme.TEXT_PRIMARY, fontSize = 20.sp)
        }
    }
}

@Composable
private fun GalleryThumbnail(controller: CameraController) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CameraTheme.SURFACE)
            .clickable(enabled = controller.lastCaptureUri != null) {
                val uri = controller.lastCaptureUri ?: return@clickable
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // No gallery/viewer app available; nothing more we can do.
                }
            },
    )
}

@Composable
private fun ShutterButton(mode: CaptureMode, isRecording: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    val isVideoMode = mode == CaptureMode.VIDEO
    val coreColor = if (isVideoMode || isRecording) CameraTheme.CRITICAL else Color.White
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(enabled = !isBusy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 32.dp else 60.dp)
                .clip(if (isRecording) RoundedCornerShape(8.dp) else CircleShape)
                .background(coreColor),
        )
    }
}

@Composable
private fun ProControlsPanel(controller: CameraController) {
    val settings = controller.proSettings
    val isoRange = controller.engine.isoRange
    val exposureRange = controller.engine.exposureTimeRange

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CameraTheme.SURFACE.copy(alpha = 0.78f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MANUAL", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(74.dp))
            ProToggleChip("EXPOSURE", settings.manualExposureEnabled) {
                controller.updateProSettings { it.copy(manualExposureEnabled = !it.manualExposureEnabled) }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (controller.engine.supportsManualFocus) {
                ProToggleChip("FOCUS", settings.manualFocusEnabled) {
                    controller.updateProSettings { it.copy(manualFocusEnabled = !it.manualFocusEnabled) }
                }
            }
        }

        if (settings.manualExposureEnabled && isoRange != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val isoSteps = remember(isoRange) { standardIsoValues(isoRange.lower, isoRange.upper) }
            ProDiscreteRow("ISO", settings.iso.toString(), isoSteps.indexOf(settings.iso).coerceAtLeast(0), isoSteps.size) { index ->
                controller.updateProSettings { it.copy(iso = isoSteps[index]) }
            }
        }

        if (settings.manualExposureEnabled && exposureRange != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val shutterSteps = remember(exposureRange) { standardShutterSpeedsNanos(exposureRange.lower, exposureRange.upper) }
            val currentIndex = shutterSteps.indexOf(settings.exposureTimeNanos).coerceAtLeast(0)
            ProDiscreteRow("SHUTTER", shutterSpeedLabel(settings.exposureTimeNanos), currentIndex, shutterSteps.size) { index ->
                controller.updateProSettings { it.copy(exposureTimeNanos = shutterSteps[index]) }
            }
        }

        if (!settings.manualExposureEnabled) {
            val evRange = controller.engine.exposureCompensationRange
            if (evRange != null && evRange.lower != evRange.upper) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EV", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(74.dp))
                    Slider(
                        value = settings.exposureCompensation.toFloat(),
                        valueRange = evRange.lower.toFloat()..evRange.upper.toFloat(),
                        steps = (evRange.upper - evRange.lower - 1).coerceAtLeast(0),
                        onValueChange = { controller.updateProSettings { s -> s.copy(exposureCompensation = it.toInt()) } },
                        colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${settings.exposureCompensation}", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
                }
            }
        }

        if (settings.manualFocusEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FOCUS", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(74.dp))
                Slider(
                    value = settings.focusDistance,
                    valueRange = 0f..controller.engine.minFocusDistance.coerceAtLeast(0.01f),
                    onValueChange = { controller.updateProSettings { s -> s.copy(focusDistance = it) } },
                    colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WB", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(74.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (wb in WhiteBalanceMode.entries) {
                    val selected = settings.whiteBalanceMode == wb
                    Text(
                        text = wb.label,
                        color = if (selected) Color.Black else CameraTheme.TEXT_SECONDARY,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) CameraTheme.ACCENT else CameraTheme.SURFACE_RAISED)
                            .clickable { controller.updateProSettings { it.copy(whiteBalanceMode = wb) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProToggleChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) Color.Black else CameraTheme.TEXT_SECONDARY,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) CameraTheme.ACCENT else CameraTheme.SURFACE_RAISED)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ProDiscreteRow(label: String, valueLabel: String, index: Int, stepCount: Int, onIndexChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(74.dp))
        Slider(
            value = index.toFloat(),
            valueRange = 0f..(stepCount - 1).coerceAtLeast(1).toFloat(),
            steps = (stepCount - 2).coerceAtLeast(0),
            onValueChange = { onIndexChange(it.toInt().coerceIn(0, stepCount - 1)) },
            colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
            modifier = Modifier.weight(1f),
        )
        Text(valueLabel, color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
    }
}
