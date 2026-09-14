package com.px6.radio.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
import org.junit.Test

/** Tests accent selection and how it is applied to a skin. */
class AccentColorTest {

    @Test
    fun `byId falls back to red for unknown or null`() {
        assertSame(AccentColor.RED, AccentColor.byId(null))
        assertSame(AccentColor.RED, AccentColor.byId("does-not-exist"))
        assertSame(AccentColor.CYAN, AccentColor.byId("cyan"))
    }

    @Test
    fun `default accent is red`() {
        // The stored default in Settings is "red"; byId must resolve it to the red entry.
        assertSame(AccentColor.RED, AccentColor.byId("red"))
    }

    @Test
    fun `applying an accent replaces the skin accent slots`() {
        val themed = ModernDarkSkin.withAccent(AccentColor.GREEN)
        assertEquals(AccentColor.GREEN.value, themed.colors.accent)
        assertEquals(AccentColor.GREEN.value, themed.colors.accentBorder)
        // The rest of the palette is untouched.
        assertEquals(ModernDarkSkin.colors.bg, themed.colors.bg)
        assertEquals(ModernDarkSkin.colors.text, themed.colors.text)
    }

    @Test
    fun `the skin accent is left alone when SKIN is chosen`() {
        val themed = ModernDarkSkin.withAccent(AccentColor.SKIN)
        assertEquals(ModernDarkSkin.colors.accent, themed.colors.accent)
    }

    @Test
    fun `soft accent variants are derived, not identical to the accent`() {
        val themed = ModernLightSkin.withAccent(AccentColor.BLUE)
        // The soft fill and border are mixed toward the panel, so they must differ from the accent
        // itself — otherwise a chip would be a solid block of colour.
        assertNotEquals(themed.colors.accent, themed.colors.accentSoft)
        assertNotEquals(themed.colors.accent, themed.colors.accentSoftBorder)
    }

    @Test
    fun `on-accent text is near-black or near-white, never the accent itself`() {
        // Whatever the accent, the text on top of it must be a high-contrast neutral, so a label
        // on an accent button stays legible.
        AccentColor.entries.filter { it.value != null }.forEach { accent ->
            val onAccent = ModernDarkSkin.withAccent(accent).colors.onAccent
            assertNotEquals("onAccent must differ from the accent for ${accent.id}",
                accent.value, onAccent)
            val lum = onAccent.luminance()
            assertTrue(
                "onAccent for ${accent.id} should be near-black or near-white (was $lum)",
                lum < 0.1f || lum > 0.9f,
            )
        }
    }
}
