package com.px6.radio.ui.theme

import android.util.TypedValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic palette for the radio UI. Two variants — **Modern Dark** and **Modern Light** — so the
 * same composables work in both without any hard-coded colours.
 */
data class RadioColors(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val muted2: Color,
    val softBg: Color,
    val softBorder: Color,
    val accent: Color,
    val accentBorder: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentSoftBorder: Color,
    val accentText: Color,
    val dab: Color,
    val onDab: Color,
    val fm: Color,
    val onFm: Color,
    val star: Color,
    val okSoft: Color,
    val okBorder: Color,
    val okText: Color,
    val dangerSoft: Color,
    val dangerBorder: Color,
    val dangerText: Color,
    val signalOff: Color,
    val rowSelected: Color,
    val rowSelectedBorder: Color,
    val transportBg: Color,
    val logoText: Color,
    val scrim: Color,
    val bodyText: Color,
    val coverA: Color,
    val coverB: Color,
    val coverC: Color,
)

val ModernDark = RadioColors(
    bg = Color(0xFF05080B),
    panel = Color(0xFF0E141B),
    panelAlt = Color(0xFF0A1015),
    line = Color(0xFF1E2933),
    text = Color(0xFFEEF4F8),
    muted = Color(0xFF8697A4),
    muted2 = Color(0xFF5D6C78),
    softBg = Color(0xFF101821),
    softBorder = Color(0xFF223039),
    accent = Color(0xFF37C7F2),
    accentBorder = Color(0xFF5FD8F8),
    onAccent = Color(0xFF04222F),
    accentSoft = Color(0xFF13303E),
    accentSoftBorder = Color(0xFF1D4B5D),
    accentText = Color(0xFFA9D9EC),
    dab = Color(0xFF25C26A),
    onDab = Color(0xFF0A3D21),
    fm = Color(0xFFF2A33C),
    onFm = Color(0xFF3D2705),
    star = Color(0xFFF2C94C),
    okSoft = Color(0xFF0F2519),
    okBorder = Color(0xFF1C5C39),
    okText = Color(0xFFBFEED4),
    dangerSoft = Color(0xFF2A1113),
    dangerBorder = Color(0xFF5C1F22),
    dangerText = Color(0xFFFF8A8A),
    signalOff = Color(0xFF274654),
    rowSelected = Color(0xFF10222C),
    rowSelectedBorder = Color(0xFF1D3B49),
    transportBg = Color(0xFF0B1116),
    logoText = Color(0xFF04141B),
    scrim = Color(0x8C000000),
    bodyText = Color(0xFFDBE7EF),
    coverA = Color(0xFF1D6FB0),
    coverB = Color(0xFF0C2F4D),
    coverC = Color(0xFF123A2C),
)

val ModernLight = RadioColors(
    bg = Color(0xFFF4F7FA),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFFFFFFF),
    line = Color(0xFFDCE3EB),
    text = Color(0xFF0F1720),
    muted = Color(0xFF5A6875),
    muted2 = Color(0xFF8A97A4),
    softBg = Color(0xFFEDF1F6),
    softBorder = Color(0xFFD5DEE8),
    accent = Color(0xFF0E8FBF),
    accentBorder = Color(0xFF0E8FBF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE1F2F9),
    accentSoftBorder = Color(0xFFA9D9EC),
    accentText = Color(0xFF0B6E93),
    dab = Color(0xFF1E9E57),
    onDab = Color(0xFFFFFFFF),
    fm = Color(0xFFD98218),
    onFm = Color(0xFFFFFFFF),
    star = Color(0xFFE0A800),
    okSoft = Color(0xFFE4F6EC),
    okBorder = Color(0xFFA9DCC0),
    okText = Color(0xFF17663C),
    dangerSoft = Color(0xFFFDECEC),
    dangerBorder = Color(0xFFF0B4B4),
    dangerText = Color(0xFFB3261E),
    signalOff = Color(0xFFC3CDD8),
    rowSelected = Color(0xFFE1F2F9),
    rowSelectedBorder = Color(0xFFA9D9EC),
    transportBg = Color(0xFFFFFFFF),
    logoText = Color(0xFF0B1A22),
    scrim = Color(0x66000000),
    bodyText = Color(0xFF25313C),
    coverA = Color(0xFF4FA8DA),
    coverB = Color(0xFF2E7BB0),
    coverC = Color(0xFF3E9E7A),
)

val LocalRadioColors = staticCompositionLocalOf { ModernDark }

/** Palette of the active theme — usable directly as `appColors.text` inside composables. */
val appColors: RadioColors
    @Composable @ReadOnlyComposable get() = LocalRadioColors.current

val LocalSkinMetrics = staticCompositionLocalOf { SkinMetrics.Default }

/**
 * Non-colour tokens of the active skin — `skin.pad(12)`, `skin.cornerLarge`, `skin.showLogos`.
 * Everything structural a composable does should come from here rather than a literal, otherwise
 * skins can only recolour and end up looking alike.
 */
val skin: SkinMetrics
    @Composable @ReadOnlyComposable get() = LocalSkinMetrics.current

/** Minimum touch target — at least 1 cm physically, for use in a moving car. */
val LocalTouchMin = staticCompositionLocalOf { 64.dp }

val touchMin: Dp
    @Composable @ReadOnlyComposable get() = LocalTouchMin.current

@Composable
private fun oneCentimetre(): Dp {
    val density = LocalDensity.current
    val metrics = LocalContext.current.resources.displayMetrics
    // Real physical millimetres; head units often misreport dpi, so clamp to a sane range.
    val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 10f, metrics)
    val dp = with(density) { px.toDp() }
    return dp.coerceIn(64.dp, 96.dp)
}

@Composable
fun Px6RadioTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) =
    Px6RadioTheme(if (darkTheme) ModernDarkSkin else ModernLightSkin, content)

/**
 * Applies a skin: its palette, its metrics and a matching Material scheme. Every composable below
 * reads [appColors] and [skin], so switching the skin restyles the whole app in one recomposition.
 */
@Composable
fun Px6RadioTheme(activeSkin: Skin, content: @Composable () -> Unit) {
    val colors = activeSkin.colors
    val darkTheme = activeSkin.dark
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.panel, onSurface = colors.text,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.panel, onSurface = colors.text,
        )
    }
    CompositionLocalProvider(
        LocalRadioColors provides colors,
        LocalSkinMetrics provides activeSkin.metrics,
        LocalTouchMin provides oneCentimetre(),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
