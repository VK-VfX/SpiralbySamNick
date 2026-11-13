package com.nikoezveracamspiral

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorTab(val label: String, val icon: ImageVector) {
    FILTER("Filters", Icons.Rounded.Palette),
    ADJUST("Adjust", Icons.Rounded.AutoFixHigh),
    CROP("Crop", Icons.Rounded.Crop),
    PORTRAIT("Portrait", Icons.Rounded.BlurOn),
    DETAILS("Details", Icons.Rounded.Info),
}

private fun readBytes(context: Context, uri: Uri): ByteArray? =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

/**
 * Post-capture editor: opens the last photo, lets the person change its filter, develop sliders
 * and crop/rotation with a live preview (hold the photo to compare with the original), and saves
 * the result as a new file, leaving the original untouched.
 */
@Composable
fun EditorScreen(uri: Uri, controller: CameraController, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var look by remember { mutableStateOf(Look()) }
    var transform by remember { mutableStateOf(PhotoProcessor.Transform()) }
    var tab by remember { mutableStateOf(EditorTab.FILTER) }
    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Portrait photos keep the picture from before the blur and what was found out about it, so
    // the blur can be changed here: its strength, its style and which thing is the subject.
    val stamp = remember(uri) { CaptureStore.stampOf(context, uri) }
    val portrait by produceState<PortraitStore.Saved?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { PortraitStore.load(context, stamp) }
    }
    val portraitBytes by produceState<ByteArray?>(initialValue = null, portrait) {
        value = portrait?.let { saved -> withContext(Dispatchers.IO) { try { saved.preBlur.readBytes() } catch (e: Exception) { null } } }
    }
    var pStyle by remember { mutableStateOf(PortraitStyle.NATURAL) }
    var pStrength by remember { mutableStateOf(0.5f) }
    var pTap by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var pApplied by remember { mutableStateOf<ByteArray?>(null) }
    var pBusy by remember { mutableStateOf(false) }
    LaunchedEffect(portrait) {
        portrait?.let {
            pStrength = it.strength
        }
    }

    // Undo/redo: a new history entry is committed once the edit settles (the wheels change
    // continuously, so each tick isn't its own step).
    val history = remember { mutableStateListOf(Look() to PhotoProcessor.Transform()) }
    var historyIndex by remember { mutableIntStateOf(0) }
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(look, transform) {
        if (restoring) {
            restoring = false
            return@LaunchedEffect
        }
        delay(600)
        val current = history[historyIndex]
        if (current.first != look || current.second != transform) {
            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
            history.add(look to transform)
            historyIndex = history.size - 1
        }
    }
    fun jumpTo(index: Int) {
        val target = history[index]
        if (target.first != look || target.second != transform) restoring = true
        historyIndex = index
        look = target.first
        transform = target.second
    }

    LaunchedEffect(uri) {
        try {
            source = withContext(Dispatchers.IO) {
                readBytes(context, uri)?.let { PhotoProcessor.decodeUpright(it, 2_500_000) }
            }
            if (source == null) loadError = "Couldn't open this photo"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't open this photo: ${e.message ?: e::class.simpleName}"
        }
    }

    val thumb: ImageBitmap? = remember(source) {
        source?.let { bmp ->
            val w = 180
            val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, h, true).asImageBitmap()
        }
    }

    val rendered by produceState<ImageBitmap?>(initialValue = null, source, look, transform, showOriginal) {
        val src = source ?: return@produceState
        delay(40)
        value = withContext(Dispatchers.Default) {
            val transformed = PhotoProcessor.transform(src, transform)
            val out = if (showOriginal) transformed else LookProcessor.render(transformed, look)
            if (transformed !== src && out !== transformed) transformed.recycle()
            out.asImageBitmap()
        }
    }

    // A live preview of the re-done blur while the Portrait tab is open (at a reduced size, so it is quick).
    val portraitPreview by produceState<ImageBitmap?>(initialValue = null, portrait, portraitBytes, pStyle, pStrength, pTap, tab) {
        val saved = portrait
        val bytes = portraitBytes
        if (tab != EditorTab.PORTRAIT || saved == null || bytes == null) {
            value = null
            return@produceState
        }
        delay(250)
        value = try {
            withContext(Dispatchers.Default) {
                PhotoProcessor.portraitPreview(bytes, saved.analysis, pStyle, pStrength, pTap, 900_000)?.asImageBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraTheme.BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorIconButton(Icons.Rounded.Close, "Close", onClick = onClose)
            Spacer(modifier = Modifier.width(10.dp))
            EditorIconButton(Icons.Rounded.Undo, "Undo", enabled = historyIndex > 0) { jumpTo(historyIndex - 1) }
            Spacer(modifier = Modifier.width(6.dp))
            EditorIconButton(Icons.Rounded.Redo, "Redo", enabled = historyIndex < history.size - 1) { jumpTo(historyIndex + 1) }
            Spacer(modifier = Modifier.weight(1f))
            EditorIconButton(Icons.Rounded.PhotoLibrary, "Open in gallery") {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    message = "No gallery app found"
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (saving || source == null) Color(0xFF555555) else Color.White)
                    .clickable(enabled = !saving && source != null) {
                        scope.launch {
                            saving = true
                            try {
                                val bytes = pApplied ?: withContext(Dispatchers.IO) { readBytes(context, uri) }
                                    ?: throw IllegalStateException("Couldn't read the original")
                                val out = withContext(Dispatchers.Default) {
                                    PhotoProcessor.processEdit(bytes, look, transform, controller.jpegQuality)
                                } ?: throw IllegalStateException("Couldn't decode the original")
                                val saved = withContext(Dispatchers.IO) { CaptureStore.saveJpeg(context, out, meta = PhotoMeta(software = "Niko's Cam ${UpdateManager.installedVersionName(context)}", description = "Quality ${controller.jpegQuality}; Edited; ${look.lut?.name ?: look.filter.label}")) }
                                if (saved != null) {
                                    controller.publishCapture(saved)
                                    message = "Saved as a new photo"
                                } else {
                                    message = "Save failed"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Save failed: ${e.message ?: e::class.simpleName}"
                            } catch (e: OutOfMemoryError) {
                                message = "Save failed: not enough memory for this photo"
                            } finally {
                                saving = false
                            }
                        }
                    }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (saving) "Saving..." else "Save copy", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(tab, source, transform) {
                    detectTapGestures(
                        onPress = {
                            // Holding shows the original, except on the Portrait tab, where a tap picks the subject.
                            if (tab != EditorTab.PORTRAIT) {
                                showOriginal = true
                                tryAwaitRelease()
                                showOriginal = false
                            }
                        },
                        onTap = { tapAt ->
                            val bmp = source
                            if (tab == EditorTab.PORTRAIT && portrait != null && bmp != null) {
                                if (transform != PhotoProcessor.Transform()) {
                                    message = "Reset rotate and crop first to pick a new subject"
                                } else {
                                    // Where the picture sits inside the box (it is fitted, with 8 dp at each side).
                                    val pad = 8.dp.toPx()
                                    val availW = size.width - 2 * pad
                                    val fit = minOf(availW / bmp.width, size.height.toFloat() / bmp.height)
                                    val shownW = bmp.width * fit
                                    val shownH = bmp.height * fit
                                    val nx = (tapAt.x - (size.width - shownW) / 2f) / shownW
                                    val ny = (tapAt.y - (size.height - shownH) / 2f) / shownH
                                    if (nx in 0f..1f && ny in 0f..1f) pTap = Pair(nx, ny)
                                }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val image = if (tab == EditorTab.PORTRAIT && portraitPreview != null) portraitPreview else rendered
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                )
            } else {
                Text(loadError ?: "Loading...", color = CameraTheme.TEXT_SECONDARY, fontSize = 14.sp)
            }
            Text(
                text = message ?: if (showOriginal) "Original" else "Hold to compare",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x99000000))
                    .clickable { message = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xEB161616))
                .heightIn(max = 280.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (tab) {
                EditorTab.FILTER -> FilterPanel(look, { look = it }, thumb, controller)
                EditorTab.ADJUST -> AdjustPanel(look) { look = it }
                EditorTab.CROP -> CropPanel(transform) { transform = it }
                EditorTab.PORTRAIT -> PortraitPanel(
                    strength = pStrength, onStrength = { pStrength = it },
                    refocused = pTap != null, onResetFocus = { pTap = null },
                    busy = pBusy, previewing = portraitPreview != null,
                    onApply = {
                        val saved = portrait
                        val bytes = portraitBytes
                        if (saved != null && bytes != null && !pBusy) {
                            scope.launch {
                                pBusy = true
                                try {
                                    val out = withContext(Dispatchers.Default) {
                                        PhotoProcessor.portraitRerender(
                                            bytes, saved.analysis, pStyle, pStrength, pTap, controller.jpegQuality,
                                            controller.watermark.takeIf { it.on }, if (controller.watermark.on && controller.watermark.logo) Watermark.logo(context) else null, saved.details,
                                        )
                                    } ?: throw IllegalStateException("there is nothing to blur with this subject")
                                    val decoded = withContext(Dispatchers.Default) { PhotoProcessor.decodeUpright(out, 2_500_000) }
                                        ?: throw IllegalStateException("couldn't read the result")
                                    pApplied = out
                                    source = decoded
                                    message = "Blur updated. Save copy keeps it."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = "Couldn't change the blur: ${e.message ?: e::class.simpleName}"
                                } catch (e: OutOfMemoryError) {
                                    message = "Couldn't change the blur: not enough memory for this photo"
                                } finally {
                                    pBusy = false
                                }
                            }
                        }
                    },
                )
                EditorTab.DETAILS -> DetailsPanel(uri)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (entry in EditorTab.entries.filter { it != EditorTab.PORTRAIT || portrait != null }) {
                val selected = entry == tab
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .clickable { tab = entry }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(entry.icon, contentDescription = null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                    Text(entry.label, color = if (selected) Color.Black else Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Change a Portrait's blur after the shot: its style and strength, and tap the photo to pick another subject. */
@Composable
private fun PortraitPanel(
    strength: Float,
    onStrength: (Float) -> Unit,
    refocused: Boolean,
    onResetFocus: () -> Unit,
    busy: Boolean,
    previewing: Boolean,
    onApply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FloatWheelRow(
            label = "Blur",
            value = strength,
            min = 0f,
            max = 1f,
            steps = 20,
            format = { "${(it * 100).toInt()}" },
        ) { onStrength(it) }
        Text(
            if (refocused) "New subject picked. Tap the photo to pick another." else "Tap the photo to pick a different subject.",
            color = CameraTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (busy || !previewing) Color(0xFF555555) else Color.White)
                    .clickable(enabled = !busy && previewing) { onApply() }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (busy) "Applying..." else "Apply blur", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (refocused) ChipText("Original subject", false) { onResetFocus() }
        }
    }
}

@Composable
private fun EditorIconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CameraTheme.SURFACE)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CropPanel(transform: PhotoProcessor.Transform, onChange: (PhotoProcessor.Transform) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(Icons.Rounded.RotateRight, "Rotate") { onChange(transform.copy(quarterTurns = (transform.quarterTurns + 1) % 4)) }
        ActionChip(Icons.Rounded.Flip, "Flip") { onChange(transform.copy(flipH = !transform.flipH)) }
        ChipText("Reset", false) { onChange(PhotoProcessor.Transform()) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipText("Original", transform.crop == null) { onChange(transform.copy(crop = null)) }
        for (option in AspectOption.entries) {
            ChipText(option.label, transform.crop == option) { onChange(transform.copy(crop = option)) }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(CameraTheme.SURFACE_RAISED)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailsPanel(uri: Uri) {
    val context = LocalContext.current
    val details by produceState<PhotoDetails?>(initialValue = null, uri) {
        value = try {
            withContext(Dispatchers.IO) { PhotoDetails.load(context, uri) }
        } catch (e: Exception) {
            null
        }
    }
    val d = details
    if (d == null) {
        Text("Reading details...", color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow("File", d.name)
        DetailRow("Resolution", "${d.width} × ${d.height}  (${String.format(java.util.Locale.US, "%.1f", d.megapixels)} MP)")
        DetailRow("Size", d.sizeLabel)
        DetailRow("Quality", d.qualityLabel)
        DetailRow("Compression", d.bitsPerPixel)
        DetailRow("Format", "JPEG")
        DetailRow("Camera", d.camera)
        DetailRow("Exposure", d.exposure)
        DetailRow("Taken", d.taken)
        if (d.processing.isNotEmpty()) DetailRow("Processing", d.processing)
        DetailRow("Software", d.software)
        DetailRow("Location", "Not recorded yet")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.width(92.dp))
        Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
