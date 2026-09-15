package com.px6.radio.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Scales the whole interface to the screen.
 *
 * The layouts are drawn for the 1280×720 @ 240 dpi head unit they were designed on — an 853×480 dp
 * frame. A bigger screen that reports the same density (a 1920×720 ultrawide unit, a 10" 1280×800
 * tablet at 160 dpi) would otherwise show that frame at the same physical size with empty space
 * around it, and the emergency-warning text would sit small in a huge box. So the density is raised
 * by how much larger the screen is than the reference — the smaller of the width and height ratio,
 * so an ultrawide screen is not squeezed vertically — capped at [MAX] so a 4K panel does not become
 * a toy interface with three giant tiles.
 *
 * Never below 1: a screen smaller than the reference (1024×600) keeps its layout as is. Font scale
 * (the accessibility setting) is left untouched.
 */
@Composable
fun ScreenScale(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val factor = minOf(cfg.screenWidthDp / REF_WIDTH_DP, cfg.screenHeightDp / REF_HEIGHT_DP)
        .coerceIn(1f, MAX)
    if (factor == 1f) return content()
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * factor, base.fontScale),
        content = content,
    )
}

private const val REF_WIDTH_DP = 853f
private const val REF_HEIGHT_DP = 480f
private const val MAX = 1.6f
