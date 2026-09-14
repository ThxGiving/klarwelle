package com.px6.radio.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which arrangement the radio screen uses. Both are built in and read the same tokens, so every
 * skin styles both. A skin may pin one, or leave it to the user.
 */
enum class LayoutMode {
    /** Station list left, now-playing right. */
    SPLIT,

    /** Big centred station name, a row of favourite tiles, function bar at the bottom. */
    TILES,
}

/** How panels, rows and buttons are drawn — the strongest single lever on a skin's character. */
enum class SurfaceStyle {
    /** No fill, no border: elements are separated by whitespace alone. */
    FLAT,

    /** Border only, transparent fill — technical, instrument-like. */
    OUTLINE,

    /** Filled panels without borders — soft, app-like. */
    FILLED,

    /** Filled *and* bordered — the most defined look. */
    CARD,
}

/** How the station list separates its entries. */
enum class ListStyle {
    /** Each row is its own rounded surface. */
    CARDS,

    /** Rows sit directly on the background, separated by hairlines. */
    DIVIDED,

    /** Rows sit directly on the background with nothing between them. */
    PLAIN,
}

/** Where a preset tile shows its station name. */
enum class TileLabel { BELOW, INSIDE, NONE }

/**
 * Everything about a skin that is not a colour.
 *
 * The point of this set is that two skins can look genuinely *different*, not merely recoloured:
 * density, shape, borders, type scale, surface treatment and which elements exist at all are all
 * skin-controlled. What a skin can never do is carry code or define free-form layouts — an
 * infotainment system in a moving car is no place to run third-party logic, and it would make the
 * app unauditable.
 */
data class SkinMetrics(
    /* -- shape ------------------------------------------------------------ */
    /** Corner radius of buttons and small controls. */
    val cornerSmall: Dp = 12.dp,
    /** Corner radius of rows, panels and tiles. */
    val cornerLarge: Dp = 14.dp,
    /** Border / hairline width. Set to 0 for a borderless skin. */
    val border: Dp = 1.dp,
    /** How surfaces are filled and outlined. */
    val surface: SurfaceStyle = SurfaceStyle.CARD,

    /* -- density ---------------------------------------------------------- */
    /**
     * Multiplier on every internal padding and gap. 0.6 is tight and information-dense, 1.6 is
     * generous. Touch targets are *not* scaled by this — they stay at the physical minimum.
     */
    val density: Float = 1.0f,
    /** Extra height added to list rows and tiles beyond the touch minimum. */
    val rowExtra: Dp = 0.dp,

    /* -- type ------------------------------------------------------------- */
    /** Multiplier on every font size, applied on top of the individual sizes below. */
    val fontScale: Float = 1.0f,
    val titleSize: TextUnit = 30.sp,
    val titleWeight: FontWeight = FontWeight.Bold,
    /** Letter spacing of the big station name, in ems. */
    val titleTracking: Float = 0.0f,
    val titleUppercase: Boolean = false,
    val bodySize: TextUnit = 16.sp,
    val labelSize: TextUnit = 13.sp,
    val labelUppercase: Boolean = false,
    val labelTracking: Float = 0.0f,

    /* -- composition ------------------------------------------------------ */
    val listStyle: ListStyle = ListStyle.CARDS,
    val tileAspect: Float = 1.0f,
    val tileLabel: TileLabel = TileLabel.BELOW,
    /** Draw an accent bar under the active preset / selected row. */
    val activeUnderline: Boolean = false,

    /* -- optional elements ------------------------------------------------ */
    val showLogos: Boolean = true,
    val showBandBadges: Boolean = true,
    val showSignal: Boolean = true,
    val showStatusPills: Boolean = true,
    /** Slideshow / cover artwork in the now-playing pane. */
    val showArtwork: Boolean = true,
) {
    /** A padding in skin units: `pad(12)` honours the skin's density. */
    fun pad(dp: Int): Dp = (dp * density).dp

    fun font(size: TextUnit): TextUnit = (size.value * fontScale).sp

    /** True when surfaces should paint a background fill. */
    val filled: Boolean get() = surface == SurfaceStyle.FILLED || surface == SurfaceStyle.CARD

    /** True when surfaces should paint a border. */
    val outlined: Boolean
        get() = border.value > 0f && (surface == SurfaceStyle.OUTLINE || surface == SurfaceStyle.CARD)

    companion object {
        val Default = SkinMetrics()
    }
}

/**
 * A complete look: identity, palette and metrics.
 *
 * Built-in skins are [ModernDarkSkin] and [ModernLightSkin]. Further skins are plain JSON files
 * the user drops into [SkinLoader.userDir]; every field is optional and falls back to the built-in
 * value, so a two-line file is a valid skin and a broken one can never make the app unusable.
 */
data class Skin(
    val id: String,
    val name: String,
    val dark: Boolean,
    val colors: RadioColors,
    val metrics: SkinMetrics = SkinMetrics.Default,
    /** Layout this skin insists on, or null to leave the choice to the user. */
    val layout: LayoutMode? = null,
    /** True for the skins compiled into the app. */
    val builtIn: Boolean = true,
)

val ModernDarkSkin = Skin("modern-dark", "Modern Dark", dark = true, colors = ModernDark)
val ModernLightSkin = Skin("modern-light", "Modern Light", dark = false, colors = ModernLight)

/**
 * Instrument look: hard edges, outlines instead of fills, capital labels with wide tracking and a
 * warm signal accent — the sober, technical end of the range, made for the tile layout.
 */
private val InstrumentMetrics = SkinMetrics(
    cornerSmall = 2.dp,
    cornerLarge = 2.dp,
    border = 1.dp,
    surface = SurfaceStyle.OUTLINE,
    density = 0.9f,
    titleSize = 42.sp,
    titleWeight = FontWeight.Light,
    titleTracking = 0.05f,
    titleUppercase = true,
    bodySize = 15.sp,
    labelSize = 12.sp,
    labelUppercase = true,
    labelTracking = 0.08f,
    listStyle = ListStyle.DIVIDED,
    tileAspect = 1.5f,
    tileLabel = TileLabel.BELOW,
    activeUnderline = true,
    showStatusPills = false,
)

private val InstrumentDark = ModernDark.copy(
    bg = Color(0xFF07090B),
    panel = Color(0xFF0C0F12),
    panelAlt = Color(0xFF0A0D10),
    transportBg = Color(0xFF0C0F12),
    line = Color(0xFF23282D),
    softBg = Color(0xFF111417),
    softBorder = Color(0xFF2A3036),
    text = Color(0xFFF2F5F7),
    muted = Color(0xFF9BA3AA),
    muted2 = Color(0xFF6B747C),
    accent = Color(0xFFE8442F),
    accentBorder = Color(0xFFE8442F),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF1A0F0D),
    accentSoftBorder = Color(0xFF5A2018),
    accentText = Color(0xFFF09A8B),
    rowSelected = Color(0xFF15191D),
    rowSelectedBorder = Color(0xFF2A3036),
    signalOff = Color(0xFF2A3036),
)

private val InstrumentLight = ModernLight.copy(
    bg = Color(0xFFF7F8F9),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFFFFFFF),
    transportBg = Color(0xFFFFFFFF),
    line = Color(0xFFD8DDE2),
    softBg = Color(0xFFEEF1F4),
    softBorder = Color(0xFFCBD2D9),
    text = Color(0xFF14181C),
    muted = Color(0xFF5A646D),
    muted2 = Color(0xFF8C959D),
    accent = Color(0xFFC7301C),
    accentBorder = Color(0xFFC7301C),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFFCEAE7),
    accentSoftBorder = Color(0xFFEBB4AA),
    accentText = Color(0xFF9C2415),
    rowSelected = Color(0xFFF0F2F4),
    rowSelectedBorder = Color(0xFFCBD2D9),
    signalOff = Color(0xFFC3CDD8),
)

val InstrumentDarkSkin = Skin(
    "instrument-dark", "Instrument Dunkel", dark = true,
    colors = InstrumentDark, metrics = InstrumentMetrics,
)

val InstrumentLightSkin = Skin(
    "instrument-light", "Instrument Hell", dark = false,
    colors = InstrumentLight, metrics = InstrumentMetrics,
)

val BuiltInSkins = listOf(
    ModernDarkSkin, ModernLightSkin, InstrumentDarkSkin, InstrumentLightSkin,
)

/**
 * Selectable accent colour.
 *
 * The accent carries every active marker in the app — the underline beneath the playing station
 * button, the current function, check marks. It is deliberately separate from the skin so the
 * layout and the signal colour can be chosen independently; [SKIN] keeps whatever the skin brings.
 */
enum class AccentColor(val id: String, val label: String, val value: Color?) {
    SKIN("skin", "Wie im Skin", null),
    RED("red", "Rot", Color(0xFFE8442F)),
    ORANGE("orange", "Orange", Color(0xFFF07B1F)),
    AMBER("amber", "Bernstein", Color(0xFFF2B705)),
    GREEN("green", "Grün", Color(0xFF25C26A)),
    CYAN("cyan", "Türkis", Color(0xFF37C7F2)),
    BLUE("blue", "Blau", Color(0xFF3B82F6)),
    VIOLET("violet", "Violett", Color(0xFF8B5CF6)),
    WHITE("white", "Neutral", Color(0xFFDDE3E8));

    companion object {
        fun byId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: RED
    }
}

/**
 * Returns this skin with [accent] applied to every accent slot. The soft variants are mixed
 * towards the panel colour so they stay readable in both the dark and the light palette.
 */
fun Skin.withAccent(accent: AccentColor): Skin {
    val c = accent.value ?: return this
    val onAccent = if (c.luminance() > 0.55f) Color(0xFF0B0E11) else Color(0xFFFFFFFF)
    return copy(
        colors = colors.copy(
            accent = c,
            accentBorder = c,
            onAccent = onAccent,
            accentSoft = c.copy(alpha = if (dark) 0.16f else 0.12f).compositeOver(colors.panel),
            accentSoftBorder = c.copy(alpha = if (dark) 0.45f else 0.40f).compositeOver(colors.panel),
            accentText = c.copy(alpha = if (dark) 0.85f else 1f).compositeOver(colors.panel),
        )
    )
}
