package com.nikoezveracamspiral

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.HdrOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.CropPortrait
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Iso
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ShutterSpeed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import android.view.OrientationEventListener
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** This app locks its single Activity to portrait (see AndroidManifest), so the display's
 * rotation relative to the device's natural orientation is always 0 -- the formula CameraEngine
 * uses to derive JPEG/video orientation only needs that one fixed value, never a live
 * OrientationEventListener reading. */

private val PANEL_COLOR = Color(0xEB161616)
private val OVERLAY_BUTTON_COLOR = Color(0x66000000)

private enum class SidePanel { NONE, SETTINGS, CONTROLS }

private enum class StripItem { FILTER, ADJUST, HDR, BLUR, ZOOM, EV, SHUTTER, ISO, WB, FOCUS }

/**
 * The whole camera surface, laid out after Google's Pixel camera: a rounded viewfinder shaped 3:4
 * so it matches the saved photo exactly (what you see is what you get, no hidden crop), with the
 * format and flash pills and zoom chips floating inside it, then the mode pill, a ringed
 * shutter flanked by the last-capture thumbnail and the camera flip, and a bottom row with
 * settings, the Photo/Video toggle, and manual controls.
 */
internal val LocalIconRotation = androidx.compose.runtime.compositionLocalOf { 0f }

/** Keeps an icon upright as the phone is turned (the layout itself stays portrait). */
@Composable
internal fun Modifier.keepUpright(): Modifier {
    val rotation = LocalIconRotation.current
    return this.graphicsLayer { rotationZ = rotation }
}

@Composable
fun CameraScreen(controller: CameraController) {
    val context = LocalContext.current
    var editorUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }

    // The phone's roll, in quarter turns: used for the saved photo's orientation, and to keep the
    // icons upright.
    var deviceRotation by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val quantized = ((orientation + 45) / 90 * 90) % 360
                if (quantized != deviceRotation) deviceRotation = quantized
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    var iconTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deviceRotation) {
        val desired = -deviceRotation.toFloat()
        iconTarget += ((desired - iconTarget + 540f) % 360f) - 180f
    }
    val iconRotation by animateFloatAsState(iconTarget, tween(260), label = "icon-rotation")

    CompositionLocalProvider(LocalIconRotation provides iconRotation) {
        val uri = editorUri
        val playing = videoUri
        if (playing != null) {
            BackHandler { videoUri = null }
            VideoPlayerScreen(uri = playing, onClose = { videoUri = null })
        } else if (uri != null) {
            BackHandler { editorUri = null }
            EditorScreen(uri = uri, controller = controller, onClose = { editorUri = null })
        } else {
            CameraContent(controller, deviceRotation, onOpenEditor = { editorUri = it }, onOpenVideo = { videoUri = it })
        }
    }
}

@Composable
private fun CameraContent(controller: CameraController, deviceRotation: Int, onOpenEditor: (Uri) -> Unit, onOpenVideo: (Uri) -> Unit) {
    val cameraEpoch = controller.cameraEpoch
    var panel by remember { mutableStateOf(SidePanel.NONE) }
    // The update pill opens Settings straight on the About page, where updates live.
    var settingsOnAbout by remember { mutableStateOf(false) }
    var strip by remember { mutableStateOf(StripItem.FILTER) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val hasFrontCamera = remember(cameraEpoch) { controller.engine.hasFrontCamera() }

    var previewView by remember { mutableStateOf<TextureView?>(null) }
    var viewfinderRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var thumbRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    BackHandler(enabled = panel != SidePanel.NONE) { panel = SidePanel.NONE }

    val triggerShutter: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (controller.mode.isVideo) {
            scope.launch {
                if (controller.isRecording) controller.stopVideo() else controller.startVideo(deviceRotation)
            }
        } else {
            scope.launch { controller.capturePhoto(deviceRotation) }
        }
    }
    val currentShutter by androidx.compose.runtime.rememberUpdatedState(triggerShutter)
    // The volume keys work as a shutter button while the viewfinder is showing.
    DisposableEffect(Unit) {
        KeyEvents.onVolumeKey = { currentShutter() }
        onDispose { KeyEvents.onVolumeKey = null }
    }

    LaunchedEffect(controller.mode) {
        if (controller.mode == CaptureMode.PRO) panel = SidePanel.CONTROLS
    }
    LaunchedEffect(Unit) {
        if (controller.autoCheckUpdates) controller.checkForUpdates(silent = true)
    }
    LaunchedEffect(Unit) { controller.keepSettingsSaved() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraTheme.BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding()
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        // The viewfinder takes the chosen output ratio (so what you see is what gets saved) and
        // shrinks, staying centered, on a screen too short for it plus the controls.
        val ratio = controller.aspectRatio.widthOverHeight
        // Pixel screens run from 6.1" (8a) to 6.8" (9 Pro XL) and the folds; the control area scales with the screen.
        val controlsHeight = (maxHeight * 0.31f).coerceIn(250.dp, 320.dp)
        val naturalHeight = maxWidth / ratio
        val availableHeight = (maxHeight - controlsHeight - 14.dp).coerceAtLeast(240.dp)
        val targetHeight = if (naturalHeight < availableHeight) naturalHeight else availableHeight
        val viewfinderHeight by animateDpAsState(targetHeight, tween(220), label = "viewfinder-height")
        val viewfinderWidth by animateDpAsState(targetHeight * ratio, tween(220), label = "viewfinder-width")

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(10.dp))
            Viewfinder(
                controller = controller,
                showControls = panel == SidePanel.CONTROLS,
                strip = strip,
                width = viewfinderWidth,
                height = viewfinderHeight,
                onOpenSettings = { settingsOnAbout = true; panel = SidePanel.SETTINGS },
                onDismissPanel = { panel = SidePanel.NONE },
                onViewReady = { previewView = it },
                onPositioned = { viewfinderRect = it },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Tapping the empty space between the controls returns to shooting.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (panel == SidePanel.CONTROLS && controller.mode != CaptureMode.PRO) panel = SidePanel.NONE
                    },
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (panel == SidePanel.CONTROLS) {
                    ControlStrip(controller, strip) { strip = it }
                } else {
                    ModeChips(controller)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryThumbnail(controller, onOpenEditor, onOpenVideo, onPositioned = { thumbRect = it })
                    ShutterButton(
                        isVideoMode = controller.mode.isVideo,
                        isRecording = controller.isRecording,
                        isBusy = controller.isCapturing,
                        rippleTick = controller.shutterTick,
                        onClick = triggerShutter,
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
                        settingsOnAbout = false
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

        FlyingCapture(controller.shutterTick, previewView, viewfinderRect, thumbRect, origin)

        if (panel == SidePanel.SETTINGS) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = SidePanel.NONE },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                ) {
                    SettingsSheet(controller, startOnAbout = settingsOnAbout)
                }
            }
        }
    }
}

@Composable
private fun Viewfinder(controller: CameraController, showControls: Boolean, strip: StripItem, width: Dp, height: Dp,
    onOpenSettings: () -> Unit,
    onDismissPanel: () -> Unit,
    onViewReady: (TextureView) -> Unit,
    onPositioned: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }
    var formatOpen by remember { mutableStateOf(false) }
    var textureRef by remember { mutableStateOf<TextureView?>(null) }
    // Portrait's depth from focus reads small copies of the live picture through this.
    DisposableEffect(textureRef) {
        val view = textureRef
        val grabber: (Int) -> android.graphics.Bitmap? = { width ->
            if (view != null && view.isAvailable && view.width > 0 && view.height > 0) {
                try {
                    view.getBitmap(width, (width * view.height.toFloat() / view.width).toInt().coerceAtLeast(64))
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        controller.previewGrabber = if (view == null) null else grabber
        onDispose { controller.previewGrabber = null }
    }
    var dialOpen by remember { mutableStateOf(false) }
    // The sun slider that follows a tap-to-focus: where it sits and a key that restarts its timer.
    var sliderAnchor by remember { mutableStateOf<Offset?>(null) }
    var sliderKey by remember { mutableIntStateOf(0) }

    // Lens change (camera flip, jump to another zoom stop): a blurred still of the old view
    // fades out over the new one instead of a hard cut.
    var lensShot by remember { mutableStateOf<ImageBitmap?>(null) }
    val lensFade = remember { Animatable(0f) }
    LaunchedEffect(controller.lensSwitchTick) {
        if (controller.lensSwitchTick == 0) return@LaunchedEffect
        lensShot = try {
            textureRef?.let { snapshot(it, 270, 360)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
        lensFade.snapTo(1f)
        delay(200)
        lensFade.animateTo(0f, tween(420))
        lensShot = null
    }

    // The shutter blink: the viewfinder drops to near-black the instant a still is triggered and
    // fades back, the same cue every camera app gives that a frame was actually taken.
    val blink = remember { Animatable(0f) }
    LaunchedEffect(controller.shutterTick) {
        if (controller.shutterTick > 0) {
            blink.snapTo(0.85f)
            blink.animateTo(0f, tween(durationMillis = 300))
        }
    }

    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF101010))
            .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
    ) {
        CameraPreview(
            controller = controller,
            onViewReady = { textureRef = it; onViewReady(it) },
            onTap = { offset, size ->
                // Choosing a filter and then tapping the picture goes back to shooting.
                if (showControls && controller.mode != CaptureMode.PRO) onDismissPanel()
                focusIndicator = offset
                sliderAnchor = offset
                sliderKey += 1
                var nx = (offset.x / size.width).coerceIn(0f, 1f)
                var ny = (offset.y / size.height).coerceIn(0f, 1f)
                // The viewfinder crops the sensor image to the chosen ratio, so map the tap back
                // into the full sensor frame.
                val contentWH = 1f / controller.engine.captureAspect()
                val viewWH = size.width.toFloat() / size.height
                if (viewWH > contentWH) ny = 0.5f + (ny - 0.5f) * contentWH / viewWH
                else if (viewWH < contentWH) nx = 0.5f + (nx - 0.5f) * viewWH / contentWH
                controller.engine.tapToFocus(nx, ny)
            },
        )

        // Portrait's live effect: the chosen style's blurred background laid over the live view.
        var liveLayer by remember { mutableStateOf<ImageBitmap?>(null) }
        val liveOn = controller.mode == CaptureMode.PORTRAIT && !controller.isRecording
        LaunchedEffect(liveOn, controller.portraitStyle, controller.portraitBlurStrength, controller.cameraEpoch) {
            if (!liveOn) {
                liveLayer = null
                PortraitLive.release()
                return@LaunchedEffect
            }
            val style = controller.portraitStyle
            val strength = controller.portraitBlurStrength
            try {
                while (isActive) {
                    val view = textureRef
                    val frame = if (view != null && view.isAvailable && view.width > 0 && view.height > 0) {
                        val lw = 288
                        val lh = (lw * view.height.toFloat() / view.width).toInt().coerceAtLeast(64)
                        try { view.getBitmap(lw, lh) } catch (e: Exception) { null }
                    } else null
                    if (frame != null) {
                        val layer = withContext(Dispatchers.Default) { PortraitLive.render(frame, strength, style) }
                        liveLayer = layer?.asImageBitmap()
                    }
                    delay(40)
                }
            } finally {
                PortraitLive.release()
            }
        }
        liveLayer?.let { layer ->
            if (liveOn) {
                Image(
                    bitmap = layer,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        GridOverlay(style = controller.gridStyle, modifier = Modifier.fillMaxSize())

        if (controller.histogramEnabled) {
            HistogramOverlay(textureRef, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 70.dp))
        }

        if (!controller.isRecording) {
            LevelIndicator(modifier = Modifier.align(Alignment.Center))
        }

        focusIndicator?.let { position ->
            FocusIndicator(position = position, onFinished = { focusIndicator = null })
        }

        val manualExposureNow = controller.mode == CaptureMode.PRO && controller.proSettings.manualExposureEnabled
        sliderAnchor?.let { anchor ->
            if (!manualExposureNow && !controller.isRecording) {
                ExposureSlider(
                    anchor = anchor,
                    key = sliderKey,
                    controller = controller,
                    onFinished = { sliderAnchor = null },
                )
            }
        }

        if (lensFade.value > 0.001f) {
            val shot = lensShot
            Box(modifier = Modifier.fillMaxSize().alpha(lensFade.value)) {
                if (shot != null) {
                    Image(
                        bitmap = shot,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(28.dp),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)))
                }
            }
        }

        if (blink.value > 0.001f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatPill(controller, onClick = { formatOpen = !formatOpen })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HdrChip(controller)
                FlashButton(controller)
            }
        }

        if (formatOpen) {
            FormatPopup(controller, modifier = Modifier.padding(start = 12.dp, top = 62.dp)) { formatOpen = false }
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
                InfoPill(if (controller.mode.isVideo) "Slo-mo in progress: recording normal video" else "${controller.mode.label} in progress: taking a standard photo")
            }
            if (controller.mode == CaptureMode.MACRO) MacroPill(controller)
            if (controller.aeAfLocked) InfoPill("AE/AF locked: long-press to unlock")
            if (controller.mode == CaptureMode.NIGHT && controller.tripod && controller.holdStillMs == 0L) {
                InfoPill("Steady: using longer exposures")
            }
            controller.videoProgress?.let { InfoPill("Applying filter to video: ${(it * 100).toInt()}%") }
            if (controller.holdStillMs > 0) {
                HoldStillPill(controller.holdStillMs, controller.shutterTick)
            }
            (controller.update as? UpdateStatus.Available)?.let { available ->
                InfoPill("Update ${available.info.versionName} available: tap to open", onClick = onOpenSettings)
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
            if (showControls) {
                ControlsContent(controller, strip) { textureRef?.let { snapshot(it, 180, 240) } }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (controller.mode == CaptureMode.NIGHT) NightDurationPills(controller)
                    if (controller.mode == CaptureMode.PORTRAIT) PortraitStylePills(controller)
                    if (dialOpen) {
                        ZoomDial(controller, onClose = { dialOpen = false })
                    } else {
                        LensLabel(controller)
                        ZoomChips(controller, onOpenDial = { dialOpen = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    controller: CameraController,
    onViewReady: (TextureView) -> Unit,
    onTap: (Offset, IntSize) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Android takes the camera away when the app is backgrounded, but the TextureView's surface can
    // survive that, so no "surface available" callback follows on return. Close the camera when the
    // app stops and reopen it when it comes back.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val textureRef = remember { arrayOfNulls<TextureView>(1) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> scope.launch {
                    if (controller.isRecording) controller.stopVideo()
                    controller.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val view = textureRef[0]
                    val texture = view?.surfaceTexture
                    if (view != null && view.isAvailable && texture != null && !controller.cameraStarted) {
                        scope.launch { openForPreview(controller, view, texture) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset -> onTap(offset, size) },
                    onLongPress = { controller.toggleAeAfLock() },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    controller.setZoom(controller.zoomRatio * zoom)
                }
            },
        factory = { ctx ->
            TextureView(ctx).apply {
                textureRef[0] = this
                onViewReady(this)
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        scope.launch { openForPreview(controller, this@apply, texture) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        controller.previewSize?.let { previewSize ->
                            applyPreviewTransform(this@apply, previewSize, controller.engine.sensorOrientation, controller.mirrorPreview())
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
        update = { view ->
            // The chosen style shows live: a color filter on the preview layer, using the same
            // matrix the saved photo gets. Reading look here re-runs this on change.
            val look = controller.look
            val filter = if (look.filter == PhotoFilter.NATURAL && look.lut == null && look.exposure == 0f && look.contrast == 0f &&
                look.saturation == 0f && look.warmth == 0f && look.tint == 0f) null
            else ColorMatrixColorFilter(look.toColorMatrix())
            val layerPaint = filter?.let { Paint().apply { colorFilter = it } }
            PreviewPaint.paints[view] = layerPaint
            view.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint)
            // Selfie mirroring is a setting; re-apply the transform whenever it flips.
            controller.previewSize?.let { applyPreviewTransform(view, it, controller.engine.sensorOrientation, controller.mirrorPreview()) }
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
        applyPreviewTransform(textureView, previewSize, controller.engine.sensorOrientation, controller.mirrorPreview())
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
private fun GridOverlay(style: GridStyle, modifier: Modifier = Modifier) {
    if (style == GridStyle.OFF) return
    Canvas(modifier = modifier) {
        val lineColor = Color.White.copy(alpha = 0.4f)
        val stroke = 1.dp.toPx()
        fun vertical(f: Float) = drawLine(lineColor, Offset(size.width * f, 0f), Offset(size.width * f, size.height), strokeWidth = stroke)
        fun horizontal(f: Float) = drawLine(lineColor, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = stroke)
        when (style) {
            GridStyle.THIRDS -> { vertical(1f / 3f); vertical(2f / 3f); horizontal(1f / 3f); horizontal(2f / 3f) }
            GridStyle.GOLDEN -> { vertical(0.382f); vertical(0.618f); horizontal(0.382f); horizontal(0.618f) }
            GridStyle.SQUARE -> {
                // Marks the centered 1:1 area (the crop a square photo would keep).
                val square = size.width
                if (square < size.height) {
                    val top = (size.height - square) / 2f
                    drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, top))
                    drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top + square), androidx.compose.ui.geometry.Size(size.width, top))
                    drawLine(lineColor, Offset(0f, top), Offset(size.width, top), strokeWidth = stroke)
                    drawLine(lineColor, Offset(0f, top + square), Offset(size.width, top + square), strokeWidth = stroke)
                }
            }
            GridStyle.OFF -> Unit
        }
    }
}

/** A horizon line that tilts against the phone's roll and shows the angle, as in the reference;
 * hidden when the phone is flat or tilted far past a usable horizon. */
@Composable
private fun LevelIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var roll by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val g = sqrt(x * x + y * y + z * z)
                if (g < 1f || abs(z) / g > 0.7f) {
                    roll = null
                    return
                }
                val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                smoothed += 0.2f * (angle - smoothed)
                roll = smoothed
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (manager != null && sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { manager?.unregisterListener(listener) }
    }
    val angle = roll ?: return
    if (abs(angle) > 12f) return
    val level = abs(angle) < 1f
    Row(
        modifier = modifier
            .width(240.dp)
            .graphicsLayer { rotationZ = angle },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(2.dp).background(if (level) CameraTheme.ACCENT else Color.White.copy(alpha = 0.7f)))
        Text(
            text = String.format(Locale.US, "%.1f°", abs(angle)),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(modifier = Modifier.weight(1f).height(2.dp).background(if (level) CameraTheme.ACCENT else Color.White.copy(alpha = 0.7f)))
    }
}

@Composable
private fun FocusIndicator(position: Offset, onFinished: () -> Unit) {
    val scale = remember { Animatable(1.6f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(position) {
        scale.snapTo(1.6f)
        alpha.snapTo(1f)
        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
        delay(650)
        alpha.animateTo(0f, tween(200))
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = alpha.value)
        drawCircle(color = color, radius = 38.dp.toPx() * scale.value, center = position, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = color, radius = 3.dp.toPx(), center = position)
    }
}

/** After the shutter, a snapshot of the viewfinder shrinks and flies into the thumbnail, the way
 * Apple's and Pixel's cameras hand a shot off to the gallery while it is still processing. */
@Composable
private fun FlyingCapture(
    tick: Int,
    view: TextureView?,
    from: androidx.compose.ui.geometry.Rect?,
    to: androidx.compose.ui.geometry.Rect?,
    origin: Offset,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(tick) {
        if (tick == 0 || view == null || from == null || to == null) return@LaunchedEffect
        val snapshot = try {
            snapshot(view, 240, 320)?.asImageBitmap()
        } catch (e: Exception) {
            null
        } ?: return@LaunchedEffect
        image = snapshot
        progress.snapTo(0f)
        delay(140)
        progress.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        image = null
    }
    val shot = image
    val start = from
    val end = to
    if (shot != null && start != null && end != null) {
        val p = progress.value
        val left = androidx.compose.ui.util.lerp(start.left, end.left, p) - origin.x
        val top = androidx.compose.ui.util.lerp(start.top, end.top, p) - origin.y
        val w = androidx.compose.ui.util.lerp(start.width, end.width, p)
        val h = androidx.compose.ui.util.lerp(start.height, end.height, p)
        Image(
            bitmap = shot,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .clip(RoundedCornerShape(androidx.compose.ui.util.lerp(28f, 28f, p).dp))
                .alpha(1f - 0.4f * p),
        )
    }
}

/**
 * The brightness slider that appears beside the focus ring after a tap: a sun you drag up for a
 * brighter picture or down for a darker one, like Pixel's. It fades away a few seconds after you
 * stop touching it.
 */
@Composable
private fun ExposureSlider(anchor: Offset, key: Int, controller: CameraController, onFinished: () -> Unit) {
    val range = controller.engine.exposureCompensationRange ?: return
    if (range.upper <= range.lower) return
    val haptic = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val trackDp = 150.dp
    val trackPx = with(density) { trackDp.toPx() }
    val index = controller.proSettings.exposureCompensation.coerceIn(range.lower, range.upper)
    var floatIndex by remember(key) { mutableFloatStateOf(index.toFloat()) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(key) {
        appear.snapTo(0f)
        appear.animateTo(1f, tween(180))
    }
    LaunchedEffect(key, dragging) {
        if (!dragging) {
            delay(3200)
            appear.animateTo(0f, tween(220))
            onFinished()
        }
    }
    val ringRadius = with(density) { 38.dp.toPx() }
    val gap = with(density) { 36.dp.toPx() }
    val sliderWidth = with(density) { 44.dp.toPx() }
    androidx.compose.ui.layout.Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(44.dp, trackDp)
                    .alpha(appear.value)
                    .pointerInput(key) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            val perIndex = trackPx / (range.upper - range.lower).toFloat()
                            floatIndex = (floatIndex - dy / perIndex).coerceIn(range.lower.toFloat(), range.upper.toFloat())
                            val target = floatIndex.roundToInt()
                            if (target != controller.proSettings.exposureCompensation) {
                                controller.setExposureIndex(target)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    },
            ) {
                val fraction = (index - range.lower).toFloat() / (range.upper - range.lower).toFloat()
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = size.width / 2f
                    drawLine(Color.White.copy(alpha = 0.55f), Offset(x, 6.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                    val y = (size.height - 22.dp.toPx()) * (1f - fraction) + 11.dp.toPx()
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x, y))
                    drawCircle(Color(0xFF202020), radius = 4.dp.toPx(), center = Offset(x, y))
                    for (i in 0 until 8) {
                        val a = i * (Math.PI.toFloat() / 4f)
                        val from = Offset(x + kotlin.math.cos(a) * 14.dp.toPx(), y + kotlin.math.sin(a) * 14.dp.toPx())
                        val to = Offset(x + kotlin.math.cos(a) * 18.dp.toPx(), y + kotlin.math.sin(a) * 18.dp.toPx())
                        drawLine(Color.White, from, to, strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(sliderWidth.roundToInt(), trackPx.roundToInt()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // To the right of the ring unless that runs off the edge, then to its left.
            var x = anchor.x + ringRadius + gap
            if (x + placeable.width > constraints.maxWidth - 8f) x = anchor.x - ringRadius - gap - placeable.width
            val y = (anchor.y - placeable.height / 2f).coerceIn(8f, (constraints.maxHeight - placeable.height - 8f).coerceAtLeast(8f))
            placeable.place(x.roundToInt(), y.roundToInt())
        }
    }
}

/**
 * A zoom dial: slide sideways along a scale of tick marks to zoom smoothly, with a small tap in
 * your hand for every tick and a firmer one at each lens stop. Closes itself a moment after you let go.
 */
@Composable
private fun ZoomDial(controller: CameraController, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val minZoom = controller.engine.minZoomRatio()
    val maxZoom = controller.engine.maxZoomRatio().coerceAtMost(30f).coerceAtLeast(1f)
    val stops = remember(controller.cameraEpoch) { controller.engine.zoomStops().filter { it in (minZoom - 0.01f)..(maxZoom + 0.01f) } }
    var dragging by remember { mutableStateOf(false) }
    var idleKey by remember { mutableIntStateOf(0) }
    var lastTick by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    LaunchedEffect(dragging, idleKey) {
        if (!dragging) {
            delay(2600)
            onClose()
        }
    }
    val log2 = { v: Float -> ln(v) / ln(2f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${zoomLabel(controller.zoomRatio)}×",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(OVERLAY_BUTTON_COLOR)
                .clickable { onClose() }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(37.dp))
                .background(OVERLAY_BUTTON_COLOR)
                .pointerInput(minZoom, maxZoom) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; idleKey += 1 },
                        onDragCancel = { dragging = false; idleKey += 1 },
                    ) { change, dx ->
                        change.consume()
                        val unit = size.width * 0.30f // pixels per doubling of zoom
                        val log = log2(controller.zoomRatio) - dx / unit
                        val target = 2f.pow(log).coerceIn(minZoom, maxZoom)
                        controller.setZoom(target)
                        val tick = floor(log * 12f).toInt()
                        if (tick != lastTick) {
                            lastTick = tick
                            val nearStop = stops.any { abs(log2(it) - log) < 0.02f }
                            haptic.performHapticFeedback(if (nearStop) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = size.width * 0.30f
                val centre = size.width / 2f
                val current = log2(controller.zoomRatio)
                val baseY = size.height - 14.dp.toPx()
                // Minor ticks every twelfth of a doubling, fading toward the edges.
                val first = floor((current - 1.8f) * 12f).toInt()
                val last = floor((current + 1.8f) * 12f).toInt()
                for (t in first..last) {
                    val l = t / 12f
                    val z = 2f.pow(l)
                    if (z < minZoom - 0.001f || z > maxZoom + 0.001f) continue
                    val x = centre + (l - current) * unit
                    if (x < 0f || x > size.width) continue
                    val edge = 1f - (abs(x - centre) / (size.width / 2f)).coerceIn(0f, 1f)
                    val major = t % 12 == 0
                    val h = if (major) 18.dp.toPx() else 9.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.25f + 0.65f * edge), Offset(x, baseY), Offset(x, baseY - h), strokeWidth = (if (major) 2.dp else 1.dp).toPx())
                }
                // Lens stops, labelled.
                drawIntoCanvas { canvas ->
                    labelPaint.textSize = 13.sp.toPx()
                    for (stop in stops) {
                        val x = centre + (log2(stop) - current) * unit
                        if (x < 8f || x > size.width - 8f) continue
                        val edge = 1f - (abs(x - centre) / (size.width / 2f)).coerceIn(0f, 1f)
                        labelPaint.alpha = (255 * (0.35f + 0.65f * edge)).toInt()
                        canvas.nativeCanvas.drawText(zoomLabel(stop), x, baseY - 24.dp.toPx(), labelPaint)
                    }
                }
                // The pointer.
                drawLine(Color.White, Offset(centre, 6.dp.toPx()), Offset(centre, 22.dp.toPx()), strokeWidth = 3.dp.toPx())
            }
        }
    }
}

/** Small readout of which physical lens is shooting, so it's clear when the telephoto or
 * ultra-wide takes over from the main lens. */
@Composable
private fun LensLabel(controller: CameraController) {
    var label by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            label = controller.engine.activeLensLabel()
            delay(400)
        }
    }
    val text = label ?: return
    Text(
        text = "$text · ${zoomLabel(controller.zoomRatio)}×",
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Portrait looks: how the background (and for Studio, the subject) is treated. */
@Composable
private fun PortraitStylePills(controller: CameraController) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(OVERLAY_BUTTON_COLOR)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (style in PortraitStyle.entries) {
            val selected = controller.portraitStyle == style
            Text(
                text = style.label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { controller.portraitStyle = style }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun NightDurationPills(controller: CameraController) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(OVERLAY_BUTTON_COLOR)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (seconds in listOf(3, 6)) {
            val selected = !controller.nightGoogle && controller.nightSeconds == seconds
            Text(
                text = "Wait ${seconds}s",
                color = if (selected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable {
                        controller.nightSeconds = seconds
                        if (controller.nightGoogle) controller.changeNightGoogle(false)
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        if (controller.googleNightAvailable) {
            val selected = controller.nightGoogle
            Text(
                text = "Google",
                color = if (selected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { controller.changeNightGoogle(true) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

/** The JPEG/RAW pill at the viewfinder's top-left: shows the current output format and opens the
 * format picker. */
@Composable
private fun FormatPill(controller: CameraController, onClick: () -> Unit) {
    val rawOn = controller.isRawEnabled && controller.engine.isRawSupported
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (rawOn) Color.White else OVERLAY_BUTTON_COLOR)
            .clickable(enabled = !controller.isRecording) { onClick() }
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
private fun FormatPopup(controller: CameraController, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rawSupported = controller.engine.isRawSupported
    val rawOn = controller.isRawEnabled && rawSupported
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(PANEL_COLOR)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Format", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatOption("JPEG", selected = !rawOn, enabled = true) {
                controller.setRaw(false)
                onDismiss()
            }
            FormatOption("RAW + JPEG", selected = rawOn, enabled = rawSupported) {
                controller.setRaw(true)
                onDismiss()
            }
        }
        if (!rawSupported) {
            Text("RAW isn't available on this camera", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FormatOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else CameraTheme.SURFACE_RAISED)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun HdrChip(controller: CameraController) {
    if (controller.mode.isVideo || controller.mode == CaptureMode.NIGHT) return
    val on = controller.hdrEnabled
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) Color.White else OVERLAY_BUTTON_COLOR)
            .clickable { controller.hdrEnabled = !controller.hdrEnabled }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (on) Icons.Rounded.HdrOn else Icons.Rounded.HdrOff,
            contentDescription = "HDR",
            tint = if (on) Color.Black else Color.White,
            modifier = Modifier.size(22.dp).keepUpright(),
        )
    }
}

@Composable
private fun FlashButton(controller: CameraController) {
    val torch = controller.flashMode == FlashMode.TORCH
    val label = when (controller.flashMode) {
        FlashMode.OFF -> null
        FlashMode.AUTO -> "Auto"
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
            imageVector = when (controller.flashMode) {
                FlashMode.OFF -> Icons.Rounded.FlashOff
                FlashMode.AUTO -> Icons.Rounded.FlashAuto
                else -> Icons.Rounded.FlashOn
            },
            contentDescription = "Flash",
            tint = if (torch) Color.Black else Color.White,
            modifier = Modifier.size(22.dp).keepUpright(),
        )
        if (label != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = if (torch) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun zoomLabel(z: Float): String = when {
    z < 1f -> String.format(Locale.US, "%.1f", z).removePrefix("0")
    z == z.toInt().toFloat() -> z.toInt().toString()
    else -> String.format(Locale.US, "%.1f", z)
}

@Composable
private fun ZoomChips(controller: CameraController, onOpenDial: () -> Unit) {
    val minZoom = controller.engine.minZoomRatio()
    val maxZoom = controller.engine.maxZoomRatio()
    val presets = controller.engine.zoomStops().filter { it == 1f || (it >= minZoom - 0.01f && it <= maxZoom + 0.01f) }
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
            val label = zoomLabel(preset)
            Box(
                modifier = Modifier
                    .size(width = if (selected) 54.dp else 44.dp, height = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .pointerInput(preset) {
                        detectTapGestures(
                            onTap = { controller.jumpZoom(preset) },
                            onLongPress = { onOpenDial() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selected) "$label×" else label,
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

/** Macro: says which lens is in use and how close the lens is currently focused. */
@Composable
private fun MacroPill(controller: CameraController) {
    var distance by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            distance = controller.engine.currentFocusDistanceCm()
            delay(300)
        }
    }
    val wide = controller.engine.minZoomRatio() < 1f
    val focus = distance?.let { String.format(Locale.US, "%.1f cm", it) } ?: "searching"
    InfoPill((if (wide) "Macro, ultra-wide lens" else "Macro") + "  ·  focus $focus")
}

@Composable
private fun HoldStillPill(durationMs: Long, tick: Int) {
    val progress = remember(tick) { Animatable(0f) }
    LaunchedEffect(tick) { progress.animateTo(1f, tween(durationMs.toInt().coerceAtLeast(300), easing = androidx.compose.animation.core.LinearEasing)) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(OVERLAY_BUTTON_COLOR)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Hold still", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF))) {
            Box(modifier = Modifier.fillMaxWidth(progress.value).height(3.dp).background(Color.White))
        }
    }
}

@Composable
private fun InfoPill(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OVERLAY_BUTTON_COLOR)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
    @Suppress("UNUSED_VARIABLE")
    val epoch = controller.cameraEpoch // refresh when a different camera opens
    val modes = if (controller.mode.isVideo) {
        listOf(CaptureMode.VIDEO)
    } else {
        listOf(CaptureMode.NIGHT, CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.MACRO, CaptureMode.PRO)
            .filter { it != CaptureMode.MACRO || controller.engine.supportsMacro() || controller.mode == CaptureMode.MACRO }
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
            modifier = Modifier.size(26.dp).keepUpright(),
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
            modifier = Modifier.size(size * 0.46f).keepUpright(),
        )
    }
}

/** The last capture's thumbnail, popping in with a small bounce each time a new one lands. */
@Composable
private fun GalleryThumbnail(controller: CameraController, onOpenEditor: (Uri) -> Unit, onOpenVideo: (Uri) -> Unit, onPositioned: (androidx.compose.ui.geometry.Rect) -> Unit) {
    val context = LocalContext.current
    val uri = controller.lastCaptureUri
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val popScale = remember { Animatable(1f) }
    val processing = controller.pendingProcessing > 0
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "processing")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sweep",
    )
    val breathe by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathe",
    )
    val pulse = if (processing) breathe else 1f
    val glow by androidx.compose.animation.core.rememberInfiniteTransition(label = "thumb-glow").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "thumb-glow-alpha",
    )

    LaunchedEffect(uri, controller.captureRevision) {
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
            .onGloballyPositioned { onPositioned(it.boundsInRoot()) }
            .scale(popScale.value * pulse)
            .drawBehind {
                // A soft halo that breathes while the photo is being developed.
                if (processing) {
                    val radius = size.minDimension * 0.95f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.5f * glow), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            }
            .clip(CircleShape)
            .background(CameraTheme.SURFACE)
            .clickable(enabled = uri != null) {
                val target = uri ?: return@clickable
                if (!controller.lastCaptureIsVideo) {
                    onOpenEditor(target)
                    return@clickable
                }
                onOpenVideo(target)
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
        if (processing) {
            // A soft dim plus a comet-tail ring orbiting the thumbnail while the shot develops.
            Box(modifier = Modifier.fillMaxSize().background(Color(0x55000000)))
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                rotate(sweep) {
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Transparent, Color.White)),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
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
private fun ShutterButton(isVideoMode: Boolean, isRecording: Boolean, isBusy: Boolean, rippleTick: Int, onClick: () -> Unit) {
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
    // A ring that spreads out from the button each time a shot is taken.
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(rippleTick) {
        if (rippleTick > 0) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Box(
        modifier = Modifier
            .size(92.dp)
            .drawBehind {
                val r = ripple.value
                if (r > 0f && r < 1f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - r) * 0.6f),
                        radius = size.minDimension / 2f * (1f + 0.6f * r),
                        style = Stroke(width = (4.dp.toPx() * (1f - r)).coerceAtLeast(1f)),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .semantics { contentDescription = if (isVideoMode) (if (isRecording) "Stop recording" else "Start recording") else "Take photo" }
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
}

/** The settings bottom sheet: capture options as icon-segment rows, then output quality and
 * over-the-air updates. Scrolls when taller than the screen allows. */
@Composable
private fun SettingsSheet(controller: CameraController, startOnAbout: Boolean = false) {
    val scope = rememberCoroutineScope()
    val rawSupported = controller.engine.isRawSupported
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xFF242424))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)),
        )
        var aboutOpen by remember { mutableStateOf(startOnAbout) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (aboutOpen) {
                Text(
                    "‹  About",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier.weight(1f).clickable { aboutOpen = false },
                )
            } else {
                Text("Camera", color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CameraTheme.SURFACE_RAISED)
                        .clickable { aboutOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "About", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (aboutOpen) {
            AboutPage(controller)
            return@Column
        }

        var tab by remember { mutableIntStateOf(0) }
        SettingsTabs(tab) { tab = it }
        Crossfade(targetState = tab, label = "settings-tab") { current ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (current) {
                    0 -> {
        SheetSection("Flash", controller.flashMode.name.lowercase().replaceFirstChar { it.uppercase() })
        SegmentRow(
            options = listOf(
                Triple(FlashMode.OFF, "Off", Icons.Rounded.FlashOff),
                Triple(FlashMode.AUTO, "Auto", Icons.Rounded.FlashAuto),
                Triple(FlashMode.ON, "On", Icons.Rounded.FlashOn),
                Triple(FlashMode.TORCH, "Torch", Icons.Rounded.FlashlightOn),
            ),
            selected = controller.flashMode,
        ) { target -> while (controller.flashMode != target) controller.cycleFlash() }

        SheetSection("Timer", if (controller.timerSeconds == 0) "Off" else "${controller.timerSeconds} s")
        SegmentRow(
            options = listOf(0, 2, 3, 5, 10, 15).map { Triple(it, if (it == 0) "Off" else "${it}s", null as ImageVector?) },
            selected = controller.timerSeconds,
        ) { controller.timerSeconds = it }

        SheetSection("Ratio", controller.aspectRatio.label)
        SegmentRow(
            options = AspectOption.entries.map { Triple(it, it.label, (if (it == AspectOption.R1_1) Icons.Rounded.CropSquare else Icons.Rounded.CropPortrait) as ImageVector?) },
            selected = controller.aspectRatio,
        ) { controller.aspectRatio = it }

        SheetSection("Photo format", if (controller.isRawEnabled && rawSupported) "RAW + JPEG" else "JPEG")
        SegmentRow(
            options = listOf(
                Triple(false, "JPEG", Icons.Rounded.PhotoCamera),
                Triple(true, "RAW + JPEG", Icons.Rounded.Iso),
            ),
            selected = controller.isRawEnabled && rawSupported,
        ) { wantRaw ->
            if (wantRaw && !rawSupported) return@SegmentRow
            controller.setRaw(wantRaw)
        }

        SheetSection("JPEG quality", "${controller.jpegQuality}")
        SegmentRow(
            options = listOf(85 to "Standard", 95 to "High", 100 to "Maximum").map { Triple(it.first, it.second, null as ImageVector?) },
            selected = controller.jpegQuality,
        ) { controller.jpegQuality = it }

        SheetSection("Zoom enhance (multi-frame)", if (controller.zoomEnhance) "On" else "Off")
        SegmentRow(
            options = listOf(true to "On", false to "Off").map { Triple(it.first, it.second, null as ImageVector?) },
            selected = controller.zoomEnhance,
        ) { controller.zoomEnhance = it }

        SheetSection("Ultra HDR", if (controller.ultraHdrEnabled) "On" else "Off")
        SegmentRow(
            options = listOf(true to "On", false to "Off").map { Triple(it.first, it.second, null as ImageVector?) },
            selected = controller.ultraHdrEnabled,
        ) { controller.setUltraHdr(it) }
        Text(
            "Saves the extra brightness of the scene so HDR screens show brighter highlights. It looks like a normal photo everywhere else.",
            color = CameraTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

                    }
                    1 -> {
        SheetSection("Video resolution", controller.videoConfig.res.label)
        SegmentRow(
            options = controller.engine.supportedVideoResolutions().map { Triple(it, it.label, null as ImageVector?) },
            selected = controller.videoConfig.res,
        ) { controller.videoConfig = controller.videoConfig.copy(res = it) }
        if (controller.engine.supports60fps()) {
            SheetSection("Video frame rate", "${controller.videoConfig.fps} fps")
            SegmentRow(
                options = listOf(30, 60).map { Triple(it, "$it fps", null as ImageVector?) },
                selected = controller.videoConfig.fps,
            ) { controller.videoConfig = controller.videoConfig.copy(fps = it) }
        }
        if (controller.engine.supportsHevc()) {
            SheetSection("Video codec", if (controller.videoConfig.hevc) "HEVC" else "H.264")
            SegmentRow(
                options = listOf(false to "H.264 (compatible)", true to "HEVC (smaller)").map { Triple(it.first, it.second, null as ImageVector?) },
                selected = controller.videoConfig.hevc,
            ) { controller.videoConfig = controller.videoConfig.copy(hevc = it) }
        }

                    }
                    2 -> {
        SheetSection("Grid", controller.gridStyle.label)
        SegmentRow(
            options = listOf(
                Triple(GridStyle.OFF, "Off", Icons.Rounded.GridOff),
                Triple(GridStyle.THIRDS, "Thirds", Icons.Rounded.Grid3x3),
                Triple(GridStyle.GOLDEN, "Golden", Icons.Rounded.GridOn),
                Triple(GridStyle.SQUARE, "Square", Icons.Rounded.CropSquare),
            ),
            selected = controller.gridStyle,
        ) { controller.gridStyle = it }

        SheetSection("Histogram", if (controller.histogramEnabled) "On" else "Off")
        SegmentRow(
            options = listOf(true to "On", false to "Off").map { Triple(it.first, it.second, null as ImageVector?) },
            selected = controller.histogramEnabled,
        ) { controller.histogramEnabled = it }

        SheetSection("Selfie", if (controller.mirrorSelfies) "Mirrored" else "As seen by lens")
        SegmentRow(
            options = listOf(
                Triple(true, "Mirror", Icons.Rounded.FlipCameraAndroid),
                Triple(false, "Don't mirror", Icons.Rounded.FlipCameraAndroid),
            ),
            selected = controller.mirrorSelfies,
        ) { controller.mirrorSelfies = it }

        SheetSection("Colour", if (controller.engine.p3Active) "Display P3" else "sRGB")
        Text(
            "Photos are processed in the wider Display P3 colour range where the camera supports it. Now: ${controller.engine.sessionSummary}.",
            color = CameraTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

                    }
                    else -> {
                        UpdateSection(controller)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** The four groups of the settings sheet. */
@Composable
private fun SettingsTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Photo", "Video", "Display", "Phone")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(CameraTheme.SURFACE_RAISED)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val background by animateColorAsState(if (on) Color.White else Color.Transparent, tween(160), label = "tab-background")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Over-the-air updates, shown inside the About page: check, download and install a newer build. */
@Composable
private fun UpdatesBlock(controller: CameraController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = controller.update
    SheetSection("Updates", "v${UpdateManager.installedVersionName(context)}")
    val message = when (status) {
        UpdateStatus.Idle -> "Check for a newer build of Niko's Cam."
        UpdateStatus.Checking -> "Checking..."
        UpdateStatus.UpToDate -> "You're on the latest version."
        is UpdateStatus.Available -> "Version ${status.info.versionName} is available." + if (status.info.notes.isNotBlank()) " ${status.info.notes}" else ""
        is UpdateStatus.Downloading -> "Downloading ${status.info.versionName}: ${(status.progress * 100).toInt()}%"
        is UpdateStatus.Ready -> "Version ${status.info.versionName} downloaded. Tap Install."
        is UpdateStatus.Failed -> status.message
    }
    Text(message, color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (status) {
            is UpdateStatus.Available -> UpdateButton("Download & install", Modifier.weight(1f)) { scope.launch { controller.downloadUpdate() } }
            is UpdateStatus.Ready -> UpdateButton("Install", Modifier.weight(1f)) { controller.installUpdate() }
            is UpdateStatus.Downloading, UpdateStatus.Checking -> Unit
            else -> UpdateButton("Check for updates", Modifier.weight(1f)) { scope.launch { controller.checkForUpdates() } }
        }
    }
    SheetSection("Check automatically at launch", if (controller.autoCheckUpdates) "On" else "Off")
    SegmentRow(
        options = listOf(true to "On", false to "Off").map { Triple(it.first, it.second, null as ImageVector?) },
        selected = controller.autoCheckUpdates,
    ) { controller.changeAutoCheckUpdates(it) }

}

/** This phone and the problem report. */
@Composable
private fun UpdateSection(controller: CameraController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = controller.deviceProfile
    SheetSection("This phone", profile.label)
    Text(
        "${profile.ramGb} GB memory · ${profile.tier.label} performance · lenses ${controller.engine.lensSummary()}",
        color = CameraTheme.TEXT_SECONDARY,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    SheetSection("Something wrong?", "")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        UpdateButton("Report a problem (share log)", Modifier.weight(1f)) {
            scope.launch {
                try {
                    Diagnostics.share(context, controller)
                } catch (e: Exception) {
                    controller.reportError("Couldn't prepare the report: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun AboutPage(controller: CameraController) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.dev_avatar),
            contentDescription = "Nikoezvera",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp).clip(CircleShape),
        )
        Text("Nikoezvera", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Developer of Niko's Cam", color = CameraTheme.TEXT_SECONDARY, fontSize = 14.sp)
        Text(
            "v${UpdateManager.installedVersionName(context)}",
            color = CameraTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        SheetSection("Contact", "Telegram")
        UpdateButton("@xnzvix", Modifier.fillMaxWidth()) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/xnzvix")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // No app can open the link; nothing more to do.
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UpdatesBlock(controller)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun UpdateButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SheetSection(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp)
    }
}

@Composable
private fun <T> SegmentRow(options: List<Triple<T, String, ImageVector?>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for ((value, label, icon) in options) {
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(if (isSelected) Color.White else Color(0xFF383838))
                    .clickable { onSelect(value) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = if (isSelected) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    label,
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The strip that replaces the mode pills while controls are open: each item shows its current
 * value and selects which control the viewfinder panel presents. */
@Composable
private fun ControlStrip(controller: CameraController, selected: StripItem, onSelect: (StripItem) -> Unit) {
    val s = controller.proSettings
    val manualExposure = controller.mode == CaptureMode.PRO && s.manualExposureEnabled
    val manualFocus = controller.mode == CaptureMode.PRO && s.manualFocusEnabled
    val edited = !controller.look.copy(filter = PhotoFilter.NATURAL, lut = null, strength = 1f).isNeutral
    val items = buildList {
        add(Triple(StripItem.FILTER, Icons.Rounded.Palette, controller.look.lut?.name ?: controller.look.filter.label))
        add(Triple(StripItem.ADJUST, Icons.Rounded.AutoFixHigh, if (edited) "Edited" else "Adjust"))
        if (controller.mode != CaptureMode.NIGHT && !controller.mode.isVideo) {
            add(Triple(StripItem.HDR, if (controller.hdrEnabled) Icons.Rounded.HdrOn else Icons.Rounded.HdrOff, if (controller.hdrEnabled) "HDR+ ${controller.hdrStrength}" else "HDR off"))
        }
        if (controller.mode == CaptureMode.PORTRAIT) {
            add(Triple(StripItem.BLUR, Icons.Rounded.BlurOn, "Blur ${(controller.portraitBlurStrength * 100).toInt()}"))
        }
        add(Triple(StripItem.ZOOM, Icons.Rounded.ZoomIn, String.format(Locale.US, "%.1f×", controller.zoomRatio)))
        add(Triple(StripItem.EV, Icons.Rounded.Exposure, if (s.exposureCompensation == 0) "±0" else String.format(Locale.US, "%+d", s.exposureCompensation)))
        if (controller.engine.supportsManualExposure) {
            add(Triple(StripItem.SHUTTER, Icons.Rounded.ShutterSpeed, if (manualExposure) shutterSpeedLabel(s.exposureTimeNanos) else "Auto"))
            add(Triple(StripItem.ISO, Icons.Rounded.Iso, if (manualExposure) s.iso.toString() else "Auto"))
        }
        add(Triple(StripItem.WB, Icons.Rounded.WbSunny, s.whiteBalanceMode.label.lowercase().replaceFirstChar { it.uppercase() }))
        if (controller.engine.supportsManualFocus) {
            add(Triple(StripItem.FOCUS, Icons.Rounded.CenterFocusStrong, if (manualFocus) "Manual" else "Auto"))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((item, icon, value) in items) {
            val isSelected = item == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .width(58.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = value, tint = if (isSelected) Color.Black else Color.White, modifier = Modifier.size(26.dp).keepUpright())
                Text(value, color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ControlsContent(controller: CameraController, strip: StripItem, snapshot: () -> Bitmap?) {
    val settings = controller.proSettings
    val inPro = controller.mode == CaptureMode.PRO
    val manualExposure = inPro && settings.manualExposureEnabled
    val manualFocus = inPro && settings.manualFocusEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(PANEL_COLOR)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (strip) {
            StripItem.FILTER -> {
                val frame by produceState<ImageBitmap?>(initialValue = null) {
                    value = try { snapshot()?.asImageBitmap() } catch (e: Exception) { null }
                }
                FilterPanel(controller.look, { controller.look = it }, frame, controller)
            }
            StripItem.ADJUST -> AdjustPanel(controller.look) { controller.look = it }
            StripItem.HDR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProToggleChip("Off", !controller.hdrEnabled) { controller.hdrEnabled = false }
                    ProToggleChip("On", controller.hdrEnabled) { controller.hdrEnabled = true }
                }
                WheelRow(
                    label = "Strength",
                    valueText = "${controller.hdrStrength}",
                    count = 5,
                    index = controller.hdrStrength - 1,
                    enabled = controller.hdrEnabled,
                    onIndexChange = { controller.hdrStrength = it + 1 },
                )
                Text(
                    "Like Pixel's HDR+: the shutter grabs a quick burst and returns at once; the burst is aligned, merged and tone-mapped in the background. Strength sets how hard shadows are lifted. Not used with flash or manual exposure.",
                    color = CameraTheme.TEXT_SECONDARY,
                    fontSize = 11.sp,
                )
            }
            StripItem.BLUR -> {
                FloatWheelRow(
                    label = "Blur",
                    value = controller.portraitBlurStrength,
                    min = 0.1f,
                    max = 1f,
                    steps = 18,
                    format = { "${(it * 100).roundToInt()}" },
                ) { controller.portraitBlurStrength = it }
                Text("How strong the background blur is. The look (Natural, Studio, Soft, Bokeh, Stage, Mono) is chosen above the shutter. Works on people and on objects that are in focus; if no subject is found the photo is left as is.", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp)
            }
            StripItem.ZOOM -> {
                val maxZoom = controller.engine.maxZoomRatio().coerceAtLeast(1f)
                val minZoom = controller.engine.minZoomRatio()
                FloatWheelRow(
                    label = "Zoom",
                    value = controller.zoomRatio.coerceIn(minZoom, maxZoom),
                    min = minZoom,
                    max = maxZoom.coerceAtLeast(minZoom + 0.1f),
                    steps = ((maxZoom - minZoom) * 10f).roundToInt().coerceIn(1, 190),
                    format = { String.format(Locale.US, "%.1f×", it) },
                ) { controller.setZoom(it) }
            }
            StripItem.EV -> {
                val evRange = controller.engine.exposureCompensationRange
                if (evRange == null || evRange.lower == evRange.upper) {
                    Text("Exposure compensation isn't available on this camera", color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp)
                } else {
                    WheelRow(
                        label = "Exposure compensation",
                        valueText = String.format(Locale.US, "%+d", settings.exposureCompensation),
                        count = evRange.upper - evRange.lower + 1,
                        index = settings.exposureCompensation - evRange.lower,
                        onIndexChange = { controller.updateProSettings { s -> s.copy(exposureCompensation = evRange.lower + it) } },
                    )
                    if (manualExposure) {
                        Text("Manual exposure is on: compensation has no effect", color = CameraTheme.WARN, fontSize = 11.sp)
                    }
                }
            }
            StripItem.SHUTTER, StripItem.ISO -> {
                AutoManualRow(manualExposure) { wantManual ->
                    if (wantManual) controller.selectMode(CaptureMode.PRO)
                    controller.updateProSettings { it.copy(manualExposureEnabled = wantManual) }
                }
                if (manualExposure) {
                    val isoRange = controller.engine.isoRange
                    val exposureRange = controller.engine.exposureTimeRange
                    if (strip == StripItem.ISO && isoRange != null) {
                        val isoSteps = remember(isoRange) { standardIsoValues(isoRange.lower, isoRange.upper) }
                        ProDiscreteRow("ISO", settings.iso.toString(), isoSteps.indexOf(settings.iso).coerceAtLeast(0), isoSteps.size) { index ->
                            controller.updateProSettings { it.copy(iso = isoSteps[index]) }
                        }
                    }
                    if (strip == StripItem.SHUTTER && exposureRange != null) {
                        val shutterSteps = remember(exposureRange) { standardShutterSpeedsNanos(exposureRange.lower, exposureRange.upper) }
                        val currentIndex = shutterSteps.indexOf(settings.exposureTimeNanos).coerceAtLeast(0)
                        ProDiscreteRow("Shutter", shutterSpeedLabel(settings.exposureTimeNanos), currentIndex, shutterSteps.size) { index ->
                            controller.updateProSettings { it.copy(exposureTimeNanos = shutterSteps[index]) }
                        }
                    }
                }
            }
            StripItem.WB -> {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (wb in WhiteBalanceMode.entries) {
                        ChipText(wb.label, settings.whiteBalanceMode == wb) {
                            controller.updateProSettings { it.copy(whiteBalanceMode = wb) }
                        }
                    }
                }
            }
            StripItem.FOCUS -> {
                AutoManualRow(manualFocus) { wantManual ->
                    if (wantManual) controller.selectMode(CaptureMode.PRO)
                    controller.updateProSettings { it.copy(manualFocusEnabled = wantManual) }
                }
                if (manualFocus) {
                    FloatWheelRow(
                        label = "Focus distance",
                        value = settings.focusDistance,
                        min = 0f,
                        max = controller.engine.minFocusDistance.coerceAtLeast(0.01f),
                        steps = 40,
                        format = { if (it < 0.05f) "Infinity" else String.format(Locale.US, "%.0f cm", 100f / it) },
                    ) { controller.updateProSettings { s -> s.copy(focusDistance = it) } }
                }
            }
        }
    }
}

@Composable
private fun AutoManualRow(manual: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProToggleChip("Auto", !manual) { onChange(false) }
        ProToggleChip("Manual", manual) { onChange(true) }
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
    WheelRow(label = label, valueText = valueLabel, count = stepCount, index = index, onIndexChange = onIndexChange)
}
