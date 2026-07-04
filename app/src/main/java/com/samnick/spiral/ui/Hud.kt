package com.samnick.spiral.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samnick.spiral.game.GameSnapshot
import com.samnick.spiral.game.Phase
import com.samnick.spiral.ui.theme.HudDim
import com.samnick.spiral.ui.theme.PlayerAmber
import com.samnick.spiral.ui.theme.PlayerReversalWhiteGold
import com.samnick.spiral.ui.theme.ReversalOuter
import androidx.compose.material3.Text

@Composable
fun GameHud(snapshot: GameSnapshot, modifier: Modifier = Modifier) {
    val accent = if (snapshot.phase == Phase.REVERSAL) PlayerReversalWhiteGold else PlayerAmber
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "SCORE ${snapshot.score}",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            val phaseLabel = when (snapshot.phase) {
                Phase.CHASE -> "CHASE  TIER ${snapshot.difficultyTier}"
                Phase.REVERSAL -> "REVERSAL  ${"%.1f".format(snapshot.reversalTimeLeft.coerceAtLeast(0f))}s"
            }
            Text(
                text = phaseLabel,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        val meterLabel = if (snapshot.phase == Phase.REVERSAL) "CLOSENESS" else "GAP"
        val meterValue = if (snapshot.phase == Phase.REVERSAL) snapshot.closeness else snapshot.gap
        val meterColor = if (snapshot.phase == Phase.REVERSAL) ReversalOuter else PlayerAmber
        HudBar(label = meterLabel, value = meterValue, color = meterColor)
        HudBar(label = "ENERGY", value = snapshot.energy, color = PlayerAmber)
    }
}

@Composable
private fun HudBar(label: String, value: Float, color: Color) {
    Column {
        Text(
            text = label,
            color = HudDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
    }
}

@Composable
fun GameOverOverlay(score: Long, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "THE LIGHT CAUGHT YOU",
                color = PlayerAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Text(
                text = "SCORE $score",
                color = HudDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "TAP TO RUN AGAIN",
                color = HudDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
