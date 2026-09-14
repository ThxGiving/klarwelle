package com.px6.radio.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one and only "please wait" mark: three dots breathing in sequence.
 *
 * Every wait uses this — the startup placeholder while the persisted state is decoded, and the
 * tuning overlay on a station that is selected but not yet audible. One mark for one meaning, so a
 * wait is recognisable as a wait wherever it turns up, rather than a spinning ring here and
 * something else there.
 *
 * A ring implies measurable progress it does not have: nobody knows how long a stream will buffer.
 * Dots only claim "working", which is the honest claim, and they sit quieter on a dark car screen at
 * night than a rotating stroke.
 *
 * @param dot diameter of a single dot; the whole row is roughly five times as wide.
 */
@Composable
fun PulsingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dot: Dp = 9.dp,
) {
    // One shared clock rather than three staggered animations: each dot derives its phase from it,
    // so they can never drift apart and there is a single animation to schedule.
    val phase by rememberInfiniteTransition(label = "wait").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(dot * 0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            // Distance from this dot's turn, wrapped so dot 0 is as close to phase 2.9 as to 0.1.
            val raw = (phase - i + 3f) % 3f
            val distance = if (raw > 1.5f) 3f - raw else raw
            val weight = (1f - distance).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(dot)
                    .scale(0.62f + 0.38f * weight)
                    .alpha(0.35f + 0.65f * weight)
                    .background(color, CircleShape),
            )
        }
    }
}

private const val CYCLE_MS = 1_100
