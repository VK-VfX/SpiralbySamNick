package com.samnick.neverspiral

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A labeled slider row for a settings panel -- shared by every mode's tunable-settings panel. */
@Composable
fun SettingSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(96.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = VisualizerTheme.ACCENT,
                activeTrackColor = VisualizerTheme.ACCENT,
                inactiveTrackColor = VisualizerTheme.HAIRLINE,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.2f".format(value),
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
    }
}

/** A labeled row of mutually-exclusive choice chips, for discrete (not continuous) settings. */
@Composable
fun <T> SettingChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(96.dp),
        )
        Row {
            for ((value, optionLabel) in options) {
                val isSelected = value == selected
                Text(
                    text = optionLabel,
                    color = if (isSelected) VisualizerTheme.PANEL else VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) VisualizerTheme.ACCENT else VisualizerTheme.PANEL_RAISED)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
