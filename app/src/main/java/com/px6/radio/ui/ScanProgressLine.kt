package com.px6.radio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.px6.radio.ui.theme.appColors

/**
 * A hairline of progress along the top edge of the radio screen while a scan runs — so the user
 * can leave the settings, keep listening, and still see the scan is alive. DAB reports a
 * percentage and gets a filling line; the FM/AM sweep reports nothing but "busy" and gets a
 * light travelling along the line. Drawn over whatever frontend is active; never takes touch.
 */
@Composable
fun ScanProgressLine(dabScanning: Boolean, dabPercent: Int, fmSeeking: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = dabScanning || fmSeeking,
        enter = fadeIn(tween(200)), exit = fadeOut(tween(600)),
        modifier = modifier,
    ) {
        val accent = appColors.accent
        val track = accent.copy(alpha = 0.18f)
        // Eased so each 2.4 % step (one of 41 channels) glides instead of jumping.
        val fill by animateFloatAsState(if (dabScanning) dabPercent / 100f else 0f, tween(500), label = "scanFill")
        val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweepPos",
        )
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRect(track)
            if (dabScanning) {
                drawRect(accent, size = Size(size.width * fill, size.height))
            } else {
                // A soft light, a quarter of the width, travelling left to right.
                val w = size.width * 0.25f
                val x = -w + (size.width + w) * sweep
                drawRect(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f)), startX = x, endX = x + w),
                    topLeft = Offset(x, 0f), size = Size(w, size.height),
                )
            }
        }
    }
}
