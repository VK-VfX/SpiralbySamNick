package com.nikoezveracamspiral

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** This app locks its single Activity to portrait (see AndroidManifest), so the display's
 * rotation relative to the device's natural orientation is always 0 -- the formula CameraEngine
 * uses to derive JPEG/video orientation only needs that one fixed value, never a live
 * OrientationEventListener reading. */
private const val DEVICE_ROTATION_DEGREES = 0

private val PANEL_COLOR = Color(0xEB161616)
private val OVERLAY_BUTTON_COLOR = Color(0x66000000)

private enum class SidePanel { NONE, SETTINGS, CONTROLS }

/**
 * The whole camera surface, laid out after Google's Pixel camera: a rounded viewfinder shaped 3:4
 * so it matches the saved photo exactly (what you see is what you get, no hidden crop), with the
 * format and flash pills and zoom chips floating inside it, then the mode pill, a ringed
 * shutter flanked by the last-capture thumbnail and the camera flip, and a bottom row with
 * settings, the Photo/Video toggle, and manual controls.
 */
@Composable
fun CameraScreen(controller: CameraController) {
    val cameraEpoch = controller.cameraEpoch
    var panel by remember { mutableStateOf(SidePanel.NONE) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val hasFrontCamera = remember(cameraEpoch) { controller.engine.hasFrontCamera() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraTheme.BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // The viewfinder keeps its 3:4 shape; on a short screen it shrinks (staying centered)
        // rather than squeezing the controls below it.
        val controlsHeight = 290.dp
        val naturalHeight = maxWidth * 4f / 3f
        val availableHeight = (maxHeight - controlsHeight - 14.dp).coerceAtLeast(240.dp)
        val viewfinderHeight = if (naturalHeight < availableHeight) naturalHeight else availableHeight
        val viewfinderWidth = viewfinderHeight * 3f / 4f

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(10.dp))
            Viewfinder(controller = controller, panel = panel, width = viewfinderWidth, height = viewfinderHeight)

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ModeChips(controller)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryThumbnail(controller)
                    ShutterButton(
                        isVideoMode = controller.mode.isVideo,
                        isRecording = controller.isRecording,
                        isBusy = controller.isCapturing,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (controller.mode.isVideo) {
                                scope.launch {
                                    if (controller.isRecording) controller.stopVideo() else controller.startVideo(DEVICE_ROTATION_DEGREES)
                                }
                            } else {
                                scope.launch { controller.capturePhoto(DEVICE_ROTATION_DEGREES) }
                            }
                        },
                    )
                    CircleIconButton(
                        icon = Icons.Rounded.FlipCameraAndroid,
                        description = "Switch camera",
                        size = 56.dp,
                        background = CameraTheme.SURFACE,
                        enabled = !controller.isRecording && hasFrontCamera,
                    ) {
                        scope.launch { controller.switchCamera() }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(
                        icon = Icons.Rounded.Settings,
                        description = "Settings",
                        size = 56.dp,
                        background = CameraTheme.SURFACE,
                        highlighted = panel == SidePanel.SETTINGS,
                    ) {
                        panel = if (panel == SidePanel.SETTINGS) SidePanel.NONE else SidePanel.SETTINGS
                    }
                    PhotoVideoToggle(controller)
                    CircleIconButton(
                        icon = Icons.Rounded.Tune,
                        description = "Camera controls",
                        size = 56.dp,
                        background = CameraTheme.SURFACE,
                        highlighted = panel == SidePanel.CONTROLS,
                    ) {
                        panel = if (panel == SidePanel.CONTROLS) SidePanel.NONE else SidePanel.CONTROLS
                    }
                }
            }
        }
    }
}

@Composable
private fun Viewfinder(controller: CameraController, panel: SidePanel, width: Dp, height: Dp) {
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }

    // The shutter blink: the viewfinder drops to near-black the instant a still is triggered and
    // fades back, the same cue every camera app gives that a frame was actually taken.
    val blink = remember { Animatable(0f) }
    LaunchedEffect(controller.shutterTick) {
        if (controller.shutterTick > 0) {
            blink.snapTo(0.85f)
            blink.animateTo(0f, tween(durationMillis = 300))
        }
    }

    val effectivePanel = if (panel == SidePanel.NONE && controller.mode == CaptureMode.PRO) SidePanel.CONTROLS else panel

    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF101010)),
    ) {
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

        if (blink.value > 0.001f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatPill(controller)
            FlashButton(controller)
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller.isRecording) {
                RecordingPill(elapsedMs = controller.recordingElapsedMs)
            }
            if (controller.mode.isComingSoon) {
                InfoPill(if (controller.mode.isVideo) "Slo-mo coming soon: recording normal video" else "${controller.mode.label} coming soon: taking a standard photo")
            }
            controller.lastError?.let { message ->
                ErrorBanner(message = message, onDismiss = { controller.clearError() })
            }
        }

        if (controller.countdownRemaining > 0) {
            Text(
                text = controller.countdownRemaining.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            when (effectivePanel) {
                SidePanel.SETTINGS -> SettingsPanel(controller)
                SidePanel.CONTROLS -> ControlsPanel(controller, manual = controller.mode == CaptureMode.PRO)
                SidePanel.NONE -> ZoomChips(controller)
            }
        }
    }
}

@Composable
private fun CameraPreview(
    controller: CameraController,
    onTap: (Offset, IntSize) -> Unit,
) {
    val scope = rememberCoroutineScope()

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset -> onTap(offset, size) })
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
                        scope.launch { openForPreview(controller, this@apply, texture) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        controller.previewSize?.let { previewSize ->
                            applyPreviewTransform(this@apply, previewSize, controller.engine.sensorOrientation, controller.engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                        }
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

private suspend fun openForPreview(controller: CameraController, textureView: TextureView, texture: SurfaceTexture) {
    val cameraId = controller.engine.findCameraId(CameraCharacteristics.LENS_FACING_BACK)
        ?: controller.engine.findCameraId(CameraCharacteristics.LENS_FACING_FRONT)
        ?: return
    // CameraController.start() opens the camera itself and invokes this once it's open, so
    // sensorOrientation/lensFacing are valid by the time they're read here.
    controller.start(cameraId, texture) { previewSize ->
        applyPreviewTransform(textureView, previewSize, controller.engine.sensorOrientation, controller.engine.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
    }
}

/**
 * Fits the preview into the view with a center-crop and, for the front camera, a horizontal
 * mirror -- and deliberately applies NO rotation.
 *
 * The camera framework already rotates preview buffers upright for a device held in its natural
 * (portrait) orientation, using the sensor orientation, before TextureView samples them; this
 * is the same reason Google's own Camera2Basic sample applies an identity transform at rotation
 * 0. An earlier build rotated by the sensor orientation again on top of that, which is exactly
 * the 90-degree-off viewfinder seen on a real device (while the saved photo, whose orientation
 * comes from JPEG_ORIENTATION instead, was fine).
 *
 * Without any transform, TextureView stretches the (already upright) content non-uniformly to
 * the view's bounds. The viewfinder is shaped to the still-capture aspect and the preview size
 * is chosen to match, so the two normally agree and this is an identity; the scale only matters
 * when they differ slightly, where it restores the true aspect and crops the overflow. The front
 * camera's preview is mirrored (as if looking in a mirror) but its saved photo is not, the
 * standard selfie convention.
 */
private fun applyPreviewTransform(textureView: TextureView, previewSize: Size, sensorOrientation: Int, isFrontFacing: Boolean) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW == 0f || viewH == 0f) return

    val bufW = previewSize.width.toFloat()
    val bufH = previewSize.height.toFloat()
    val contentAspect = if (sensorOrientation % 180 != 0) bufH / bufW else bufW / bufH
    val viewAspect = viewW / viewH
    val drawW: Float
    val drawH: Float
    if (contentAspect > viewAspect) {
        drawH = viewH
        drawW = viewH * contentAspect
    } else {
        drawW = viewW
        drawH = viewW / contentAspect
    }

    val matrix = Matrix()
    matrix.postScale(drawW / viewW, drawH / viewH, viewW / 2f, viewH / 2f)
    if (isFrontFacing) {
        matrix.postScale(-1f, 1f, viewW / 2f, viewH / 2f)
    }
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

/** The JPEG/RAW pill at the viewfinder's top-left, as in the reference: shows the current output
 * format and, where the sensor supports RAW, toggles it. */
@Composable
private fun FormatPill(controller: CameraController) {
    val scope = rememberCoroutineScope()
    val rawSupported = controller.engine.isRawSupported
    val rawOn = controller.isRawEnabled && rawSupported
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (rawOn) Color.White else OVERLAY_BUTTON_COLOR)
            .clickable(enabled = rawSupported && !controller.isRecording) { scope.launch { controller.toggleRaw() } }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (rawOn) "RAW" else "JPEG",
            color = if (rawOn) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun FlashButton(controller: CameraController) {
    val torch = controller.flashMode == FlashMode.TORCH
    val label = when (controller.flashMode) {
        FlashMode.OFF -> null
        FlashMode.ON -> "On"
        FlashMode.TORCH -> "Torch"
    }
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (torch) Color.White else OVERLAY_BUTTON_COLOR)
            .clickable { controller.cycleFlash() }
            .padding(horizontal = if (label == null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (controller.flashMode == FlashMode.OFF) Icons.Rounded.FlashOff else Icons.Rounded.FlashOn,
            contentDescription = "Flash",
            tint = if (torch) Color.Black else Color.White,
            modifier = Modifier.size(22.dp),
        )
        if (label != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = if (torch) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ZoomChips(controller: CameraController) {
    val maxZoom = controller.engine.maxZoomRatio()
    val presets = listOf(1f, 2f, 5f, 10f).filter { it == 1f || it <= maxZoom + 0.01f }
    if (presets.size < 2) return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(OVERLAY_BUTTON_COLOR)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (preset in presets) {
            val selected = abs(controller.zoomRatio - preset) < 0.15f
            Box(
                modifier = Modifier
                    .size(width = if (selected) 54.dp else 44.dp, height = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { controller.setZoom(preset) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selected) "${preset.toInt()}×" else preset.toInt().toString(),
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun RecordingPill(elapsedMs: Long) {
    val totalSeconds = elapsedMs / 1000
    val label = String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OVERLAY_BUTTON_COLOR)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(CameraTheme.CRITICAL))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoPill(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OVERLAY_BUTTON_COLOR)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A dismissible banner for a failed camera action -- [CameraController] catches every failure
 * in its own suspend functions rather than letting them crash the app, so this is where the
 * user actually learns something went wrong instead of the shutter silently doing nothing. */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CameraTheme.CRITICAL.copy(alpha = 0.92f))
            .clickable { onDismiss() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(modifier = Modifier.width(8.dp))
        Text("✕", color = Color.White, fontSize = 13.sp)
    }
}

/** The mode pill under the viewfinder: the selected mode is a white pill with dark text, the
 * others plain gray text -- the Photo group (Photo/Pro/Portrait/Pano) or, when the bottom toggle
 * is on video, the Video group (Video/Slo-mo). */
@Composable
private fun ModeChips(controller: CameraController) {
    val modes = if (controller.mode.isVideo) {
        listOf(CaptureMode.VIDEO, CaptureMode.SLOW_MOTION)
    } else {
        listOf(CaptureMode.PHOTO, CaptureMode.PRO, CaptureMode.PORTRAIT, CaptureMode.PANORAMA)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(modes) { candidate ->
            val selected = candidate == controller.mode
            Text(
                text = candidate.label,
                color = if (selected) Color.Black else CameraTheme.TEXT_SECONDARY,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { controller.selectMode(candidate) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PhotoVideoToggle(controller: CameraController) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(CameraTheme.SURFACE)
            .padding(4.dp),
    ) {
        ToggleSegment(icon = Icons.Rounded.PhotoCamera, description = "Photo", selected = !controller.mode.isVideo) {
            if (controller.mode.isVideo) controller.selectMode(CaptureMode.PHOTO)
        }
        ToggleSegment(icon = Icons.Rounded.Videocam, description = "Video", selected = controller.mode.isVideo) {
            if (!controller.mode.isVideo) controller.selectMode(CaptureMode.VIDEO)
        }
    }
}

@Composable
private fun ToggleSegment(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 62.dp, height = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) Color.Black else Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    background: Color,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (highlighted) Color.White else background)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (highlighted) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/** The last capture's thumbnail, popping in with a small bounce each time a new one lands. */
@Composable
private fun GalleryThumbnail(controller: CameraController) {
    val context = LocalContext.current
    val uri = controller.lastCaptureUri
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val popScale = remember { Animatable(1f) }

    LaunchedEffect(uri) {
        bitmap = if (uri == null) null else withContext(Dispatchers.IO) { loadThumbnail(context, uri) }?.asImageBitmap()
    }
    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            popScale.snapTo(0.55f)
            popScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(popScale.value)
            .clip(CircleShape)
            .background(CameraTheme.SURFACE)
            .clickable(enabled = uri != null) {
                val target = uri ?: return@clickable
                val intent = Intent(Intent.ACTION_VIEW, target)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // No gallery/viewer app available; nothing more we can do.
                }
            },
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = "Last capture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    } else {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }
} catch (e: Exception) {
    null
}

/** A white ring around a white disc, as in the reference. The disc shrinks while pressed and
 * while a capture is in flight -- the tactile half of the capture animation (the viewfinder
 * blink is the other) -- and turns red for video, becoming a rounded square while recording. */
@Composable
private fun ShutterButton(isVideoMode: Boolean, isRecording: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val discScale by animateFloatAsState(
        targetValue = if (pressed || isBusy) 0.86f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "shutter-disc-scale",
    )
    val discSize by animateDpAsState(
        targetValue = if (isRecording) 34.dp else 72.dp,
        animationSpec = tween(durationMillis = 160),
        label = "shutter-disc-size",
    )
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = !isBusy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(discSize)
                .scale(discScale)
                .clip(if (isRecording) RoundedCornerShape(9.dp) else CircleShape)
                .background(if (isVideoMode) CameraTheme.CRITICAL else Color.White),
        )
    }
}

@Composable
private fun SettingsPanel(controller: CameraController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(PANEL_COLOR)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelRow("Timer") {
            OptionChips(listOf(0 to "Off", 3 to "3s", 10 to "10s"), controller.timerSeconds) { controller.timerSeconds = it }
        }
        PanelRow("Grid") {
            OptionChips(listOf(false to "Off", true to "On"), controller.isGridEnabled) { controller.isGridEnabled = it }
        }
    }
}

@Composable
private fun PanelRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        content()
    }
}

@Composable
private fun <T> OptionChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            val isSelected = value == selected
            Text(
                text = label,
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else CameraTheme.SURFACE_RAISED)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Exposure compensation and white balance always; Pro mode adds the fully manual exposure
 * (ISO + shutter) and manual focus controls on top. */
@Composable
private fun ControlsPanel(controller: CameraController, manual: Boolean) {
    val settings = controller.proSettings
    val isoRange = controller.engine.isoRange
    val exposureRange = controller.engine.exposureTimeRange
    val manualExposure = manual && settings.manualExposureEnabled
    val manualFocus = manual && settings.manualFocusEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(PANEL_COLOR)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (manual) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manual", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                ProToggleChip("Exposure", settings.manualExposureEnabled) {
                    controller.updateProSettings { it.copy(manualExposureEnabled = !it.manualExposureEnabled) }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (controller.engine.supportsManualFocus) {
                    ProToggleChip("Focus", settings.manualFocusEnabled) {
                        controller.updateProSettings { it.copy(manualFocusEnabled = !it.manualFocusEnabled) }
                    }
                }
            }
        }

        if (manualExposure && isoRange != null) {
            val isoSteps = remember(isoRange) { standardIsoValues(isoRange.lower, isoRange.upper) }
            ProDiscreteRow("ISO", settings.iso.toString(), isoSteps.indexOf(settings.iso).coerceAtLeast(0), isoSteps.size) { index ->
                controller.updateProSettings { it.copy(iso = isoSteps[index]) }
            }
        }

        if (manualExposure && exposureRange != null) {
            val shutterSteps = remember(exposureRange) { standardShutterSpeedsNanos(exposureRange.lower, exposureRange.upper) }
            val currentIndex = shutterSteps.indexOf(settings.exposureTimeNanos).coerceAtLeast(0)
            ProDiscreteRow("Shutter", shutterSpeedLabel(settings.exposureTimeNanos), currentIndex, shutterSteps.size) { index ->
                controller.updateProSettings { it.copy(exposureTimeNanos = shutterSteps[index]) }
            }
        }

        if (!manualExposure) {
            val evRange = controller.engine.exposureCompensationRange
            if (evRange != null && evRange.lower != evRange.upper) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exposure", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    Slider(
                        value = settings.exposureCompensation.toFloat(),
                        valueRange = evRange.lower.toFloat()..evRange.upper.toFloat(),
                        steps = (evRange.upper - evRange.lower - 1).coerceAtLeast(0),
                        onValueChange = { controller.updateProSettings { s -> s.copy(exposureCompensation = it.toInt()) } },
                        colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${settings.exposureCompensation}", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                }
            }
        }

        if (manualFocus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Focus", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                Slider(
                    value = settings.focusDistance,
                    valueRange = 0f..controller.engine.minFocusDistance.coerceAtLeast(0.01f),
                    onValueChange = { controller.updateProSettings { s -> s.copy(focusDistance = it) } },
                    colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("White bal.", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(72.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (wb in WhiteBalanceMode.entries) {
                    val selected = settings.whiteBalanceMode == wb
                    Text(
                        text = wb.label,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Color.White else CameraTheme.SURFACE_RAISED)
                            .clickable { controller.updateProSettings { it.copy(whiteBalanceMode = wb) } }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
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
        color = if (enabled) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Color.White else CameraTheme.SURFACE_RAISED)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ProDiscreteRow(label: String, valueLabel: String, index: Int, stepCount: Int, onIndexChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = index.toFloat(),
            valueRange = 0f..(stepCount - 1).coerceAtLeast(1).toFloat(),
            steps = (stepCount - 2).coerceAtLeast(0),
            onValueChange = { onIndexChange(it.toInt().coerceIn(0, stepCount - 1)) },
            colors = SliderDefaults.colors(thumbColor = CameraTheme.ACCENT, activeTrackColor = CameraTheme.ACCENT, inactiveTrackColor = CameraTheme.HAIRLINE),
            modifier = Modifier.weight(1f),
        )
        Text(valueLabel, color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(52.dp))
    }
}
