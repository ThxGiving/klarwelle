package com.px6.radio.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.ViewMode
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.frontend.RadioFrontend
import com.px6.radio.ui.frontend.TilesFrontend
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Desk UI harness: renders the real Compose radio screen to PNG on the JVM via Robolectric —
 * **no emulator, no car** — so the look of every state can be inspected off-device (the visual
 * counterpart to the following simulator). Lives in the test source set, never in the APK.
 *
 * Record the images:
 *   ./gradlew :app:testReleaseUnitTest --tests 'ScreenshotTest' -Proborazzi.test.record=true
 * Output PNGs land in app/build/outputs/roborazzi/ (one per state).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
class ScreenshotTest {

    private val noop = object : RadioActions {
        override fun selectBand(band: Band) {}
        override fun selectStation(station: Station) {}
        override fun next() {}
        override fun prev() {}
        override fun tunePreset(index: Int) {}
        override fun assignPreset(index: Int) {}
        override fun scanDab() {}
        override fun scanFm() {}
        override fun clearPreset(index: Int?) {}
        override fun toggleFixedName(station: Station) {}
        override fun setViewMode(mode: ViewMode) {}
        override fun setSortMode(mode: SortMode) {}
        override fun tuneStep(up: Boolean) {}
        override fun seekStation(up: Boolean) {}
        override fun tuneFrequency(khz: Int) {}
        override fun downloadLogos() {}
        override fun clearLogos() {}
        override fun openSettings() {}
        override fun closeSettings() {}
        override fun updateSettings(block: (Settings) -> Settings) {}
        override fun resetBackends() {}
        override fun dismissErrors() {}
        override fun acceptDabOffer() {}
        override fun declineDabOffer() {}
        override fun ignoreDabOffer() {}
        override fun requestExit() {}
        override fun confirmExit() {}
        override fun cancelExit() {}
    }

    private fun dab(id: String, name: String, ens: String = "WDR", linkedFm: Int? = null) =
        Station(id, name, Band.DAB, "Ensemble $ens", name.take(2).uppercase(), 0, 0,
            ensemble = ens, bitrateKbps = 96, linkedFmFrequencyKhz = linkedFm)

    private val stations = listOf(
        dab("10bc.d210", "Dlf", "Bundesmux", linkedFm = 98_800),
        dab("10bc.d220", "Dlf Kultur", "Bundesmux"),
        dab("d3d1.d385", "N-JOY", "NDR"),
        dab("d0c9.d47e", "ANT UNNA", "NRW", linkedFm = 102_300),
        dab("10bc.15dc", "SUNSHINE LIVE", "Bundesmux"),
        dab("10bc.15dd", "RADIO BOB!", "Bundesmux"),
    )

    private fun base(following: FollowingState) = RadioUiState(
        selectedBand = Band.DAB,
        stations = stations,
        nowPlaying = NowPlaying(stations[0], bandLine = "DAB+ · Ensemble Bundesmux · 96 kbit/s AAC+",
            dlsText = "Deutschlandfunk – Nachrichten"),
        following = following,
        demoMode = false,
        dabPresent = true,
        fmAvailable = true,
        signalBars = if (following == FollowingState.DAB_PRIMARY) 4 else 1,
        viewMode = ViewMode.PRESETS,
        settings = Settings(),
        outsideTemp = "18 °C",
        // Dlf + ANT UNNA got a RadioDNS simulcast -> the "Internet" availability pill shows.
        ipStreamStationIds = setOf("10bc.d210", "d0c9.d47e"),
    )

    private fun shoot(frontend: RadioFrontend, name: String, state: RadioUiState) {
        // Activity-less capture: renders the composable directly (no ComponentActivity needed under
        // Robolectric), sized to the @Config device qualifiers.
        captureRoboImage(filePath = "build/outputs/roborazzi/${frontend.id}_$name.png") {
            Px6RadioTheme(ModernDarkSkin) { frontend.Content(state, noop) }
        }
    }

    /** Render every interesting state through BOTH frontends, to hunt UI bugs off-device. */
    private fun sweep(frontend: RadioFrontend) {
        shoot(frontend, "dab_primary", base(FollowingState.DAB_PRIMARY))
        shoot(frontend, "fm_fallback", base(FollowingState.FM_FALLBACK))
        shoot(frontend, "ip_fallback", base(FollowingState.IP_FALLBACK))
        shoot(frontend, "muted", base(FollowingState.DAB_PRIMARY).copy(mutedNoReception = true, noDabCoverage = true, signalBars = 0))
        shoot(frontend, "scanning", base(FollowingState.DAB_PRIMARY).copy(dabScanning = true, scanProgress = 42))
        shoot(frontend, "empty", RadioUiState(stations = emptyList(), demoMode = false, nowPlaying = null, dabPresent = true, fmAvailable = true))
        shoot(frontend, "fm_band", base(FollowingState.DAB_PRIMARY).copy(
            selectedBand = Band.FM,
            stations = listOf(Station("fm.987", "1LIVE", Band.FM, "98.7 MHz", "1L", 0, 0, frequencyKhz = 98_700, piCode = 0xD391)),
            nowPlaying = NowPlaying(Station("fm.987", "1LIVE", Band.FM, "98.7 MHz", "1L", 0, 0, frequencyKhz = 98_700), bandLine = "FM · 98.7 MHz"),
        ))
        shoot(frontend, "dab_offer", base(FollowingState.DAB_PRIMARY).copy(dabOffer = stations[3]))
    }

    @Test fun tiles() = sweep(TilesFrontend)
    @Test fun split() = sweep(SplitFrontend)

    /**
     * Reproduce the "tiles start big while empty, shrink once stations are in them" report: render
     * the SAME skin with empty presets vs. a full first group, so the tile heights can be compared
     * pixel-for-pixel. If they differ, the size depends on tile content (a bug); if not, the shrink
     * comes from the skin/scale loading from DataStore, not from the stations.
     */
    @Test fun tileSize() {
        val empty = base(FollowingState.DAB_PRIMARY).copy(presets = emptyList())
        val full = base(FollowingState.DAB_PRIMARY).copy(
            presets = stations.mapIndexed { i, st -> com.px6.radio.model.PresetSlot(i + 1, st.id) },
        )
        shoot(TilesFrontend, "size_empty", empty)
        shoot(TilesFrontend, "size_full", full)
    }

    /**
     * Same full-preset state, but with real logo bitmaps in the cache — to check the "maybe with a
     * logo?" hypothesis: does an actual logo Image lay out differently than the "—"/initials
     * placeholder and change the tile size? (Architecturally it can't — the logo is fillMaxSize
     * inside an aspectRatio box — but seeing beats arguing.)
     */
    @Test fun tileSizeWithLogos() {
        val store = com.px6.radio.logo.LogoStore(
            org.robolectric.RuntimeEnvironment.getApplication()
        )
        val colors = intArrayOf(
            0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFF2E7D32.toInt(),
            0xFFEF6C00.toInt(), 0xFFC62828.toInt(), 0xFF00838F.toInt(),
        )
        stations.forEachIndexed { i, st ->
            store.put(store.key(st), solidPng(colors[i % colors.size]), com.px6.radio.logo.LogoSource.RADIODNS)
        }
        val full = base(FollowingState.DAB_PRIMARY).copy(
            presets = stations.mapIndexed { i, st -> com.px6.radio.model.PresetSlot(i + 1, st.id) },
        )
        captureRoboImage(filePath = "build/outputs/roborazzi/tiles_size_logos.png") {
            androidx.compose.runtime.CompositionLocalProvider(
                com.px6.radio.logo.LocalLogoStore provides store,
                com.px6.radio.logo.LocalLogoVersion provides 1,
            ) {
                Px6RadioTheme(ModernDarkSkin) { TilesFrontend.Content(full, noop) }
            }
        }
    }

    /** Render the SAME full-preset state under Modern vs Instrument skin — shows why the tiles look
     *  different sizes (tileAspect 1.0 square vs 1.5 flat, density 0.9), i.e. why they "shrink" when
     *  the persisted Instrument skin loads over the default Modern one. */
    @Test fun tileSizePerSkin() {
        val full = base(FollowingState.DAB_PRIMARY).copy(
            presets = stations.mapIndexed { i, st -> com.px6.radio.model.PresetSlot(i + 1, st.id) },
        )
        captureRoboImage(filePath = "build/outputs/roborazzi/tiles_skin_modern.png") {
            Px6RadioTheme(ModernDarkSkin) { TilesFrontend.Content(full, noop) }
        }
        captureRoboImage(filePath = "build/outputs/roborazzi/tiles_skin_instrument.png") {
            Px6RadioTheme(com.px6.radio.ui.theme.InstrumentDarkSkin) { TilesFrontend.Content(full, noop) }
        }
    }

    /** A solid-colour PNG the LogoStore will accept and decode (like a real broadcaster logo). */
    private fun solidPng(color: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
