package com.px6.radio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.px6.radio.logo.LocalLogoStore
import com.px6.radio.logo.LocalLogoVersion
import com.px6.radio.model.Station
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.theme.skin

/**
 * A station's logo, or its coloured initials when none is stored — shared by both frontends so
 * they show downloaded and broadcaster logos the same way. Bitmaps come from the cache, decoded
 * once and kept; decoding inside a scrolling list would cost frames.
 */
@Composable
fun StationLogo(station: Station, modifier: Modifier) {
    val store = LocalLogoStore.current
    val version = LocalLogoVersion.current
    val bmp = remember(station.id, version) { store?.bitmap(station) }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = station.name,
            // Same rounding as the initials fallback below, and as the tile it sits in. Without it a
            // full-bleed square logo (RFI) kept its hard corners inside a rounded tile, and the
            // tuning overlay's rounded scrim then left those corners sticking out undimmed.
            modifier = modifier.clip(RoundedCornerShape(skin.cornerSmall)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier.clip(RoundedCornerShape(skin.cornerSmall)).background(appColors.softBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                station.logoInitials, color = appColors.text,
                fontSize = skin.font(skin.bodySize), fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * A "tuning…" loader shown OVER a station's logo while the station is selected but not yet audible
 * (the slow IP→DAB / cross-ensemble-DAB switch, or a buffering stream). A dimming scrim plus the
 * app's shared wait mark, so the target reads as "coming up" the instant it is tapped, before any
 * audio. [ring] is kept as the parameter name and now sizes the dots, so callers need no change.
 */
@Composable
fun TuningOverlay(modifier: Modifier = Modifier, ring: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(
        // The skin's scrim twice over. Once was enough for the old ring, which was a thin stroke
        // AROUND the centre; the dots sit exactly where a tile's initials are, and at 55 % dim the
        // text read straight through them. While a station is coming up the logo has no job to do.
        modifier.clip(RoundedCornerShape(skin.cornerSmall))
            .background(appColors.scrim)
            .background(appColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        PulsingDots(color = appColors.accent, dot = ring * 0.36f)
    }
}

/**
 * Signal-strength bars, 0..4. Off by default and shown only for DAB, where reception is reported
 * continuously — FM only sends a value while seeking, so a live meter there would be stale.
 */
@androidx.compose.runtime.Composable
fun SignalBars(active: Int) {
    val heights = listOf(6, 10, 14, 18)
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        heights.forEachIndexed { i, h ->
            Box(
                androidx.compose.ui.Modifier
                    .padding(end = 3.dp)
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    // Neutral, not the accent: red bars at full strength read as a warning.
                    .background(if (i < active) appColors.text else appColors.signalOff)
            )
        }
    }
}
