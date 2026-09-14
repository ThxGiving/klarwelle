package com.px6.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.px6.radio.model.Band
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.ViewMode
import com.px6.radio.ui.SplitFrontend
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import com.px6.radio.vm.RadioLogic
import org.junit.Rule
import org.junit.Test

/**
 * UI + backend combined: a tap in the interface runs through the real [RadioLogic] and the UI must
 * reflect the new state. The controller is the same transition logic the ViewModel uses, minus the
 * tuners — so these prove the whole loop (gesture → logic → recomposition) without a car.
 */
class IntegrationUiTest {

    @get:Rule
    val rule = createComposeRule()

    /** Drives RadioActions through RadioLogic over an observable state — a headless ViewModel. */
    private class Controller(initial: RadioUiState) : RadioActions {
        var state by mutableStateOf(initial)
            private set

        private val region get() = state.settings.fmRegion

        override fun selectBand(band: Band) {
            state = RadioLogic.selectBand(state, band, region).state
        }
        override fun selectStation(station: Station) {
            state = RadioLogic.play(state, station).state
        }
        override fun tunePreset(index: Int) {
            state.presets.firstOrNull { it.index == index }?.stationId
                ?.let { id -> state.stations.firstOrNull { it.id == id } }
                ?.let { state = RadioLogic.play(state, it).state }
        }
        override fun assignPreset(index: Int) {
            state = RadioLogic.assignPreset(state, index)
        }

        override fun cancelExit() {}
        override fun confirmExit() {}
        override fun requestExit() {}
        override fun next() {}
        override fun prev() {}
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
        override fun updateSettings(block: (Settings) -> Settings) {
            state = state.copy(settings = block(state.settings))
        }
        override fun resetBackends() {}
        override fun dismissErrors() {}
        override fun acceptDabOffer() {}
        override fun declineDabOffer() {}
        override fun ignoreDabOffer() {}
    }

    private fun dab(id: String, name: String, ensemble: String = "WDR") =
        Station(id, name, Band.DAB, "Ensemble $ensemble", name.take(2), 0, 0,
            ensemble = ensemble, bitrateKbps = 96)

    private fun fm(id: String, name: String, khz: Int) =
        Station(id, name, Band.FM, "%.1f MHz".format(khz / 1000.0), "FM", 0, 0, frequencyKhz = khz)

    @Composable
    private fun Harness(controller: Controller) {
        Px6RadioTheme(ModernDarkSkin) {
            SplitFrontend.Content(controller.state, controller)
        }
    }

    /* ------------------------------------------------------------------ tests */

    @Test
    fun switchingToFmBand_showsAnFmStation() {
        val d = dab("d1", "WDR 2")
        val f = fm("fm.98500", "Eins Live", 98_500)
        val controller = Controller(
            RadioLogic.play(
                RadioUiState(
                    stations = listOf(d, f), selectedBand = Band.DAB, demoMode = false,
                    fmAvailable = true,
                ),
                d,
            ).state
        )
        rule.setContent { Harness(controller) }

        // The FM tab exists; tapping it must route through selectBand and show the FM station.
        rule.onNodeWithText("FM").performClick()
        rule.onAllNodesWithText("Eins Live").onFirst().assertIsDisplayed()
    }

    @Test
    fun tappingAStation_makesItNowPlaying() {
        val a = dab("d1", "WDR 2")
        val b = dab("d2", "1LIVE")
        val controller = Controller(
            RadioLogic.play(
                RadioUiState(
                    stations = listOf(a, b), selectedBand = Band.DAB, demoMode = false,
                ),
                a,
            ).state
        )
        rule.setContent { Harness(controller) }

        rule.onAllNodesWithText("1LIVE").onFirst().performClick()
        // The now-playing pane shows the tapped station's name (upper-cased by the pane).
        rule.onAllNodesWithText("1LIVE").onFirst().assertIsDisplayed()
        assert(controller.state.nowPlaying?.station?.id == "d2")
    }

    @Test
    fun switchingBandAndBack_returnsToTheSameDabStation() {
        val a = dab("d1", "WDR 2")
        val b = dab("d2", "1LIVE")
        val f = fm("fm.98500", "Eins Live", 98_500)
        val controller = Controller(
            RadioLogic.play(
                RadioUiState(
                    stations = listOf(a, b, f), selectedBand = Band.DAB, demoMode = false,
                    fmAvailable = true,
                ),
                b,   // hearing 1LIVE
            ).state
        )
        rule.setContent { Harness(controller) }

        rule.onNodeWithText("FM").performClick()
        rule.onNodeWithText("DAB+").performClick()
        // Per-band memory: back on DAB we are on 1LIVE, not the first station.
        assert(controller.state.nowPlaying?.station?.id == "d2")
    }
}
