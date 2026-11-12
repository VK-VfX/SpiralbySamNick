package com.nikoezveracamspiral

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun lookSliderColors() = SliderDefaults.colors(
    thumbColor = CameraTheme.ACCENT,
    activeTrackColor = CameraTheme.ACCENT,
    inactiveTrackColor = CameraTheme.HAIRLINE,
)

@Composable
internal fun rememberLutImporter(controller: CameraController): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { controller.importLut(uri) }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Filter picker shared by the camera and the editor: category tabs (including My LUTs for
 * imported .cube files), a row of live thumbnails, and the look's own strength slider. Entirely
 * separate from the develop sliders and from HDR. */
@Composable
internal fun FilterPanel(look: Look, onLook: (Look) -> Unit, frame: ImageBitmap?, controller: CameraController) {
    var tab by remember { mutableStateOf(if (look.lut != null) "LUT" else look.filter.category.name) }
    val importLut = rememberLutImporter(controller)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChipText("All", tab == "ALL") { tab = "ALL" }
            for (c in FilterCategory.entries) ChipText(c.label, tab == c.name) { tab = c.name }
            ChipText("My LUTs", tab == "LUT") { tab = "LUT" }
        }
        if (tab == "LUT") {
            if (controller.luts.isEmpty()) {
                Text(
                    "Import a 3D .cube LUT from your files to use it as a filter. It applies to photos and the editor exactly, and to video and the live preview as a close colour match.",
                    color = CameraTheme.TEXT_SECONDARY,
                    fontSize = 11.sp,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { importLut() }) {
                        Box(
                            modifier = Modifier
                                .size(width = 62.dp, height = 82.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF303030)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+", color = Color.White, fontSize = 28.sp)
                        }
                        Text("Import", color = CameraTheme.TEXT_SECONDARY, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(controller.luts.toList()) { lut ->
                    val selected = look.lut?.name == lut.name
                    FilterThumb(
                        label = lut.name,
                        matrix = remember(lut) { Look(lut = lut).toColorMatrix() },
                        selected = selected,
                        frame = frame,
                    ) { onLook(look.copy(lut = lut, filter = PhotoFilter.NATURAL, strength = 1f)) }
                }
            }
            if (look.lut != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipText("Delete ${look.lut.name}", false) {
                        val current = look.lut
                        controller.deleteLut(current)
                        onLook(look.copy(lut = null))
                    }
                }
            }
        } else {
            val filters = PhotoFilter.entries.filter { tab == "ALL" || it.category.name == tab }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(filters) { filter ->
                    FilterThumb(
                        label = filter.label,
                        matrix = remember(filter) { Look(filter = filter).toColorMatrix() },
                        selected = look.lut == null && filter == look.filter,
                        frame = frame,
                    ) { onLook(look.copy(filter = filter, lut = null, strength = if (filter == look.filter && look.lut == null) look.strength else 1f)) }
                }
            }
        }
        val strengthEnabled = look.lut != null || look.filter != PhotoFilter.NATURAL
        FloatWheelRow(
            label = "Strength",
            value = look.strength,
            min = 0f,
            max = 1f,
            steps = 20,
            format = { "${(it * 100).roundToInt()}%" },
            enabled = strengthEnabled,
        ) { onLook(look.copy(strength = it)) }
    }
}

@Composable
private fun FilterThumb(label: String, matrix: android.graphics.ColorMatrix, selected: Boolean, frame: ImageBitmap?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .size(width = 62.dp, height = 82.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF303030))
                .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(14.dp)),
        ) {
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix.array)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            label,
            color = if (selected) Color.White else CameraTheme.TEXT_SECONDARY,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).width(66.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The develop sliders (exposure, contrast, saturation, warmth, tint, highlights, shadows,
 * vignette, grain, sharpness): pick one, drag it, reset it. */
@Composable
internal fun AdjustPanel(look: Look, onLook: (Look) -> Unit) {
    var selected by remember { mutableStateOf(LookParam.EXPOSURE) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (param in LookParam.entries) {
                val changed = param.get(look) != 0f
                ChipText(param.label + if (changed) " •" else "", param == selected) { selected = param }
            }
        }
        FloatWheelRow(
            label = selected.label,
            value = selected.get(look),
            min = selected.min,
            max = selected.max,
            steps = 40,
            format = { selected.display(selected.set(look, it)) },
        ) { onLook(selected.set(look, it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipText("Reset ${selected.label}", false) { onLook(selected.set(look, 0f)) }
            ChipText("Reset all", false) { onLook(Look(filter = look.filter, strength = look.strength)) }
        }
    }
}

@Composable
internal fun ChipText(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else CameraTheme.SURFACE_RAISED)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
