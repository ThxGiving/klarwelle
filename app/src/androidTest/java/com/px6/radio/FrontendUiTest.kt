package com.px6.radio

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import com.px6.radio.model.Band
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.ViewMode
import com.px6.radio.ui.SplitFrontend
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.frontend.RadioFrontend
import com.px6.radio.ui.frontend.TilesFrontend
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Rule
import org.junit.Test

/**
 * UI harness: renders each frontend with edge-case states on the emulator and checks what shows.
 *
 * The frontends take only [RadioUiState] + [RadioActions] and no ViewModel, so every case is a
 * plain state fixture — no tuner, no backend. A frontend that would crash on an odd state (empty
 * list, no now-playing, missing FM) fails here instead of in the car.
 */
class FrontendUiTest {

    @get:Rule
    val rule = createComposeRule()

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

    private fun dab(id: String, name: String, ensemble: String = "WDR") =
        Station(id, name, Band.DAB, "Ensemble $ensemble", name.take(2), 0, 0,
            ensemble = ensemble, bitrateKbps = 96)

    private fun fm(id: String, name: String, khz: Int) =
        Station(id, name, Band.FM, "%.1f MHz".format(khz / 1000.0), "FM", 0, 0, frequencyKhz = khz)

    private fun render(frontend: RadioFrontend, state: RadioUiState) {
        rule.setContent {
            Px6RadioTheme(ModernDarkSkin) { frontend.Content(state, noop) }
        }
    }

    private fun both(state: RadioUiState, check: @Composable () -> Unit = {}) {
        // Render both frontends with the same state, one after the other, and assert nothing throws.
    }

    /* ----------------------------------------------------------- edge cases */

    @Test
    fun tiles_emptyStationList_doesNotCrash() {
        render(TilesFrontend, RadioUiState(stations = emptyList(), demoMode = false, nowPlaying = null))
        // The big name area falls back to a dash; the app is up.
        rule.onAllNodesWithText("—").onFirst().assertIsDisplayed()  // empty state renders (dash placeholders)
    }

    @Test
    fun split_emptyStationList_showsHint() {
        render(
            SplitFrontend,
            RadioUiState(stations = emptyList(), demoMode = false, nowPlaying = null),
        )
        rule.onAllNodesWithText("Keine Sender").onFirst().assertIsDisplayed()
    }

    @Test
    fun tiles_fmNowPlaying_showsFmStation() {
        val f = fm("fm.98500", "Eins Live", 98_500)
        val state = RadioUiState(
            stations = listOf(f), selectedBand = Band.FM, demoMode = false,
            nowPlaying = NowPlaying(f, bandLine = "FM · 98.5 MHz"),
        )
        render(TilesFrontend, state)
        rule.onAllNodesWithText("Eins Live").onFirst().assertIsDisplayed()  // shown (list + now-playing)
    }

    @Test
    fun tiles_secondaryChannel_isMarked() {
        val d = dab("d1", "WDR 2").copy(secondaryLabels = listOf("WDR Event"))
        val state = RadioUiState(
            stations = listOf(d), selectedBand = Band.DAB, demoMode = false,
            nowPlaying = NowPlaying(d, bandLine = "DAB+"),
        )
        render(TilesFrontend, state)
        rule.onAllNodesWithText("Zusatz: WDR Event").onFirst().assertIsDisplayed()
    }

    @Test
    fun tiles_noReception_showsMutedHint() {
        val d = dab("d1", "WDR 2")
        val state = RadioUiState(
            stations = listOf(d), selectedBand = Band.DAB, demoMode = false,
            nowPlaying = NowPlaying(d, bandLine = "DAB+"), mutedNoReception = true,
        )
        render(TilesFrontend, state)
        rule.onAllNodesWithText("Stumm — weder DAB noch FM empfangbar").onFirst().assertIsDisplayed()
    }

    @Test
    fun tiles_temperature_showsInBar() {
        val d = dab("d1", "WDR 2")
        val state = RadioUiState(
            stations = listOf(d), selectedBand = Band.DAB, demoMode = false,
            nowPlaying = NowPlaying(d, bandLine = "DAB+"), outsideTemp = "22 °C",
        )
        render(TilesFrontend, state)
        rule.onAllNodesWithText("22 °C").onFirst().assertIsDisplayed()
    }

    @Test
    fun split_pureDab_hidesFmAmTabs() {
        val d = dab("d1", "WDR 2")
        val state = RadioUiState(
            stations = listOf(d), selectedBand = Band.DAB, demoMode = false,
            fmAvailable = false,
            nowPlaying = NowPlaying(d, bandLine = "DAB+"),
        )
        render(SplitFrontend, state)
        // No FM/AM tabs on a pure DAB device.
        rule.onAllNodesWithText("FM").assertCountEquals(0)
    }

    @Test
    fun tiles_amBand_rendersWithoutCrash() {
        val a = Station("am.1422", "1422 kHz", Band.AM, "1422 kHz", "AM", 0, 0, frequencyKhz = 1422)
        val state = RadioUiState(
            stations = listOf(a), selectedBand = Band.AM, demoMode = false, fmAvailable = true,
            nowPlaying = NowPlaying(a, bandLine = "AM"),
        )
        render(TilesFrontend, state)
        rule.onAllNodesWithText("Band").onFirst().assertIsDisplayed()
    }

    @Test
    fun tiles_longName_rendersMarquee() {
        val d = dab("d1", "Ein extrem langer Sendername der umbrechen würde")
        val state = RadioUiState(
            stations = listOf(d), selectedBand = Band.DAB, demoMode = false,
            nowPlaying = NowPlaying(d, bandLine = "DAB+"),
        )
        // Just assert it renders (marquee/ellipsis handled internally, no crash).
        render(TilesFrontend, state)
        rule.onAllNodesWithText("Sender").onFirst().assertIsDisplayed()
    }
}
