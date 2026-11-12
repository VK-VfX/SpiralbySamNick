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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Flip
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
            EditorIconButton(Icons.Rounded.Close, "Close", onClose)
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
                                val bytes = withContext(Dispatchers.IO) { readBytes(context, uri) }
                                    ?: throw IllegalStateException("Couldn't read the original")
                                val out = withContext(Dispatchers.Default) {
                                    PhotoProcessor.processEdit(bytes, look, transform, controller.jpegQuality)
                                } ?: throw IllegalStateException("Couldn't decode the original")
                                val saved = withContext(Dispatchers.IO) { CaptureStore.saveJpeg(context, out) }
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
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val image = rendered
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (tab) {
                EditorTab.FILTER -> FilterPanel(look, { look = it }, thumb, controller)
                EditorTab.ADJUST -> AdjustPanel(look) { look = it }
                EditorTab.CROP -> CropPanel(transform) { transform = it }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (entry in EditorTab.entries) {
                val selected = entry == tab
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .clickable { tab = entry }
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(entry.icon, contentDescription = null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                    Text(entry.label, color = if (selected) Color.Black else Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CameraTheme.SURFACE)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
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
