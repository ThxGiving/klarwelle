package com.px6.radio.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.ews.Place
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ASA location-code helper at the real head-unit size. It cannot be reached in the emulator —
 * the ASA settings are gated on a DAB tuner being present and there is no stick on a dev machine —
 * so this harness is the only way to review the layout, which is exactly what was reported as cut
 * off before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h480dp-land-hdpi")
class PlaceSearchScreenshotTest {

    private val hits = listOf(
        Place("Unna, Nordrhein-Westfalen, Deutschland", 51.5380, 7.6890),
        Place("Hauptstraße 1, 50667 Köln, Nordrhein-Westfalen, Deutschland", 50.9375, 6.9603),
        Place("München, Bayern, Deutschland", 48.1372, 11.5756),
    )

    private fun shoot(name: String, results: List<Place>, searching: Boolean) =
        captureRoboImage(filePath = "build/outputs/roborazzi/place_$name.png") {
            Px6RadioTheme(ModernDarkSkin) {
                PlaceSearchCard(results, searching, {}, {}, {})
            }
        }

    /** Results with their derived codes — the user sees what they are about to store. */
    @Test fun withResults() = shoot("results", hits, false)

    /** While the lookup runs. */
    @Test fun whileSearching() = shoot("searching", emptyList(), true)
}
