package com.px6.radio.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.ui.frontend.TilesFrontend
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two frontends on a portrait tablet (800×1280 dp) — Tiles has a real portrait arrangement. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-mdpi")
class PortraitProbeTest {
    private val fx = ScreenshotTest()
    private val state get() = fx.base(com.px6.radio.model.FollowingState.DAB_PRIMARY)

    @Test fun portrait() {
        captureRoboImage(filePath = "build/outputs/roborazzi/portrait_split.png") {
            Px6RadioTheme(ModernDarkSkin) { SplitFrontend.Content(state, fx.noop) }
        }
        captureRoboImage(filePath = "build/outputs/roborazzi/portrait_tiles.png") {
            Px6RadioTheme(ModernDarkSkin) { TilesFrontend.Content(state, fx.noop) }
        }
    }
}
