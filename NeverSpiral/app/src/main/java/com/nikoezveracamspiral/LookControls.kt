package com.nikoezveracamspiral

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

/** Filter picker shared by the camera and the editor: category tabs, a row of live thumbnails
 * (a frame with each look applied), and the filter's own strength slider. Entirely separate from
 * the develop sliders and from HDR. */
@Composable
internal fun FilterPanel(look: Look, onLook: (Look) -> Unit, frame: ImageBitmap?) {
    var category by remember { mutableStateOf<FilterCategory?>(look.filter.category) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChipText("All", category == null) { category = null }
            for (c in FilterCategory.entries) ChipText(c.label, category == c) { category = c }
        }
        val filters = PhotoFilter.entries.filter { category == null || it.category == category }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(filters) { filter ->
                val selected = filter == look.filter
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onLook(look.copy(filter = filter, strength = if (filter == look.filter) look.strength else 1f)) },
                ) {
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
                                contentDescription = filter.label,
                                contentScale = ContentScale.Crop,
                                colorFilter = ColorFilter.colorMatrix(ColorMatrix(Look(filter = filter).toColorMatrix().array)),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        filter.label,
                        color = if (selected) Color.White else CameraTheme.TEXT_SECONDARY,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp).width(66.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
            Slider(
                value = look.strength,
                valueRange = 0f..1f,
                onValueChange = { onLook(look.copy(strength = it)) },
                enabled = look.filter != PhotoFilter.NATURAL,
                colors = lookSliderColors(),
                modifier = Modifier.weight(1f),
            )
            Text("${(look.strength * 100).toInt()}", color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = selected.get(look),
                valueRange = selected.min..selected.max,
                onValueChange = { onLook(selected.set(look, it)) },
                colors = lookSliderColors(),
                modifier = Modifier.weight(1f),
            )
            Text(selected.display(look), color = CameraTheme.TEXT_SECONDARY, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        }
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
