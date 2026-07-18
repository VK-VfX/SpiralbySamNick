package com.samnick.neverspiral

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * A compact transport bar for whichever media session [NowPlayingController] currently considers
 * "now playing" -- title/artist, source player app, album art, a draggable seek bar with elapsed/
 * remaining time, and prev/play-pause/next controls, so switching or scrubbing tracks never
 * requires leaving the visualizer. Renders nothing while no session is active, so callers can
 * unconditionally place it in the layout.
 */
@Composable
fun NowPlayingBar(modifier: Modifier = Modifier) {
    val snapshot by NowPlayingController.nowPlaying.collectAsState()
    val current = snapshot ?: return

    var displayPositionMs by remember { mutableLongStateOf(current.positionMs) }
    var userSeekMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(current.positionMs, current.positionAnchorRealtimeMs, current.isPlaying, current.playbackSpeed) {
        userSeekMs = null
        if (!current.isPlaying) {
            displayPositionMs = current.positionMs
            return@LaunchedEffect
        }
        while (isActive) {
            val elapsedSincePublish = SystemClock.elapsedRealtime() - current.positionAnchorRealtimeMs
            val extrapolated = current.positionMs + (elapsedSincePublish * current.playbackSpeed).toLong()
            displayPositionMs = if (current.durationMs > 0) extrapolated.coerceIn(0L, current.durationMs) else extrapolated.coerceAtLeast(0L)
            delay(200)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VisualizerTheme.PANEL_RAISED)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(current.albumArt)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.title,
                    color = VisualizerTheme.TEXT_PRIMARY,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (current.artist.isNotBlank()) "${current.artist} · ${current.appLabel}" else current.appLabel
                Text(
                    text = subtitle,
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            TransportButton(glyph = "⏮", onClick = NowPlayingController::skipPrevious)
            Spacer(modifier = Modifier.width(4.dp))
            TransportButton(
                glyph = if (current.isPlaying) "⏸" else "▶",
                accent = true,
                onClick = NowPlayingController::playPause,
            )
            Spacer(modifier = Modifier.width(4.dp))
            TransportButton(glyph = "⏭", onClick = NowPlayingController::skipNext)
        }

        if (current.durationMs > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(userSeekMs ?: displayPositionMs),
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp),
                )
                Slider(
                    value = (userSeekMs ?: displayPositionMs).toFloat().coerceIn(0f, current.durationMs.toFloat()),
                    valueRange = 0f..current.durationMs.toFloat(),
                    onValueChange = { userSeekMs = it.toLong() },
                    onValueChangeFinished = {
                        userSeekMs?.let { seekTarget ->
                            NowPlayingController.seekTo(seekTarget)
                            displayPositionMs = seekTarget
                        }
                        userSeekMs = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = VisualizerTheme.ACCENT,
                        activeTrackColor = VisualizerTheme.ACCENT,
                        inactiveTrackColor = VisualizerTheme.HAIRLINE,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Text(
                    text = formatDuration(current.durationMs),
                    color = VisualizerTheme.TEXT_SECONDARY,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumArt(bitmap: android.graphics.Bitmap?) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(VisualizerTheme.PANEL),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Text(text = "♪", color = VisualizerTheme.TEXT_SECONDARY, fontSize = 18.sp)
        }
    }
}

@Composable
private fun TransportButton(glyph: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (accent) VisualizerTheme.ACCENT else VisualizerTheme.PANEL)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (accent) VisualizerTheme.PANEL else VisualizerTheme.ACCENT,
            fontSize = 13.sp,
        )
    }
}

/** A slim, dismissible prompt shown in place of [NowPlayingBar] when media controls are enabled
 * but Notification Access hasn't been granted yet -- explains the one-time permission it needs
 * and jumps straight to the system settings screen to grant it. */
@Composable
fun NotificationAccessPrompt(onGrantAccess: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VisualizerTheme.PANEL_RAISED)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = "Enable Notification Access for Media Controls",
                color = VisualizerTheme.TEXT_PRIMARY,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Lets Sam's Music Viz see and control whatever's playing, in any app.",
                color = VisualizerTheme.TEXT_SECONDARY,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = "ENABLE",
            color = VisualizerTheme.ACCENT,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onGrantAccess() }.padding(end = 12.dp),
        )
        Text(
            text = "✕",
            color = VisualizerTheme.TEXT_SECONDARY,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
