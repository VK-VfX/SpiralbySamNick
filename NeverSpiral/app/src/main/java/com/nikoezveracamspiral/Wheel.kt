package com.nikoezveracamspiral

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal ruler-style wheel: drag it and ticks slide under a fixed centre mark, snapping to
 * each step with a haptic tick, like a camera dial. [count] steps, [index] selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WheelPicker(
    count: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val itemWidth = 14.dp
    val safeIndex = index.coerceIn(0, (count - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    val currentIndex by rememberUpdatedState(safeIndex)
    val onChange by rememberUpdatedState(onIndexChange)

    fun centeredIndex(): Int {
        val info = state.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: safeIndex
    }

    // The wheel moved because of the user: report each new step with a tick.
    LaunchedEffect(state, count) {
        snapshotFlow { centeredIndex() }.collect { centered ->
            if (state.isScrollInProgress && centered != currentIndex) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(centered)
            }
        }
    }
    // The value changed from outside (a reset, a preset): move the wheel to it.
    LaunchedEffect(safeIndex) {
        if (!state.isScrollInProgress && centeredIndex() != safeIndex) state.scrollToItem(safeIndex)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(40.dp).alpha(if (enabled) 1f else 0.35f)) {
        val pad = (maxWidth - itemWidth) / 2
        LazyRow(
            state = state,
            userScrollEnabled = enabled,
            contentPadding = PaddingValues(horizontal = pad),
            flingBehavior = rememberSnapFlingBehavior(state),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(count) { i ->
                val major = i % 5 == 0
                Box(modifier = Modifier.width(itemWidth).height(40.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (major) 22.dp else 12.dp)
                            .background(Color.White.copy(alpha = if (major) 0.75f else 0.4f)),
                    )
                }
            }
        }
        // Fixed centre mark.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(34.dp)
                .background(Color.White),
        )
    }
}

/** A labelled wheel: name on the left, current value on the right, the wheel below. */
@Composable
internal fun WheelRow(
    label: String,
    valueText: String,
    count: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CameraTheme.TEXT_SECONDARY, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        WheelPicker(count = count, index = index, onIndexChange = onIndexChange, enabled = enabled)
    }
}

/** A wheel over a float range split into [steps] equal steps. */
@Composable
internal fun FloatWheelRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    format: (Float) -> String,
    enabled: Boolean = true,
    onValue: (Float) -> Unit,
) {
    val span = (max - min).coerceAtLeast(1e-6f)
    val index = (((value - min) / span) * steps).roundToInt().coerceIn(0, steps)
    WheelRow(
        label = label,
        valueText = format(value),
        count = steps + 1,
        index = index,
        enabled = enabled,
        onIndexChange = { onValue(min + span * it / steps) },
    )
}
