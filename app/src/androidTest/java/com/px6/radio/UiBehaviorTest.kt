package com.px6.radio

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Screen
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.ViewMode
import com.px6.radio.ui.SettingsScreen
import com.px6.radio.ui.SplitFrontend
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.frontend.RadioFrontend
import com.px6.radio.ui.frontend.TilesFrontend
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behaviour suite: drives the real frontends and the settings screen with fixture state and a
 * recording [RadioActions], then asserts the right action fired / the right thing shows. Runs on
 * the emulator. Complements the JVM screenshot harness (ScreenshotTest) — that checks how states
 * look, this checks what interactions do.
 */
class UiBehaviorTest {

    @get:Rule
    val rule = createComposeRule()

    /** Records every action so a test can assert which one an interaction triggered. */
    private class Rec : RadioActions {
        val calls = mutableListOf<String>()
        var lastBand: Band? = null
        var settings = Settings()
        override fun selectBand(band: Band) { calls += "selectBand"; lastBand = band }
        override fun selectStation(station: Station) { calls += "selectStation:${station.id}" }
        override fun next() { calls += "next" }
        override fun prev() { calls += "prev" }
        override fun tunePreset(index: Int) { calls += "tunePreset:$index" }
        override fun assignPreset(index: Int) { calls += "assignPreset:$index" }
        override fun scanDab() { calls += "scanDab" }
        override fun scanFm() { calls += "scanFm" }
        override fun clearPreset(index: Int?) { calls += "clearPreset:$index" }
        override fun toggleFixedName(station: Station) { calls += "toggleFixedName" }
        override fun setViewMode(mode: ViewMode) { calls += "setViewMode:$mode" }
        override fun setSortMode(mode: SortMode) { calls += "setSortMode:$mode" }
        override fun tuneStep(up: Boolean) { calls += "tuneStep:$up" }
        override fun seekStation(up: Boolean) { calls += "seekStation:$up" }
        override fun tuneFrequency(khz: Int) { calls += "tuneFrequency:$khz" }
        override fun downloadLogos() { calls += "downloadLogos" }
        override fun clearLogos() { calls += "clearLogos" }
        override fun openSettings() { calls += "openSettings" }
        override fun closeSettings() { calls += "closeSettings" }
        override fun updateSettings(block: (Settings) -> Settings) { calls += "updateSettings"; settings = block(settings) }
        override fun resetBackends() { calls += "resetBackends" }
        override fun dismissErrors() { calls += "dismissErrors" }
        override fun acceptDabOffer() { calls += "acceptDabOffer" }
        override fun declineDabOffer() { calls += "declineDabOffer" }
        override fun ignoreDabOffer() { calls += "ignoreDabOffer" }
        override fun requestExit() { calls += "requestExit" }
        override fun confirmExit() { calls += "confirmExit" }
        override fun cancelExit() { calls += "cancelExit" }
    }

    // ---- fixtures ----------------------------------------------------------

    private fun dab(id: String, name: String, ens: String = "Bundesmux", link: Int? = null) =
        Station(id, name, Band.DAB, "Ensemble $ens", name.take(2).uppercase(), 0, 0,
            ensemble = ens, bitrateKbps = 104, linkedFmFrequencyKhz = link)

    private fun fm(id: String, name: String, khz: Int) =
        Station(id, name, Band.FM, "%.1f MHz".format(khz / 1000.0), "FM", 0, 0, frequencyKhz = khz)

    private val stations = listOf(
        dab("10bc.d210", "Deutschlandfunk", link = 98_800),
        dab("10bc.d220", "Deutschlandfunk Kultur"),
        dab("10bc.d230", "Deutschlandfunk Nova"),
        fm("fm.987", "1LIVE", 98_700),
    )

    private fun state(
        following: FollowingState = FollowingState.DAB_PRIMARY,
        band: Band = Band.DAB,
        st: List<Station> = stations,
        np: Station? = stations[0],
        muted: Boolean = false,
        scanning: Boolean = false,
        noCov: Boolean = false,
        fmAvail: Boolean = true,
        presets: List<PresetSlot> = (1..18).map { PresetSlot(it, st.getOrNull(it - 1)?.id) },
    ) = RadioUiState(
        selectedBand = band, stations = st,
        nowPlaying = np?.let { NowPlaying(it, bandLine = "DAB+ · Ensemble Bundesmux", dlsText = "Nachrichten") },
        following = following, demoMode = false, dabPresent = true, fmAvailable = fmAvail,
        signalBars = if (following == FollowingState.DAB_PRIMARY) 4 else 1,
        mutedNoReception = muted, dabScanning = scanning, scanProgress = if (scanning) 40 else 0,
        noDabCoverage = noCov, presets = presets, viewMode = ViewMode.PRESETS,
    )

    private fun render(frontend: RadioFrontend, s: RadioUiState, rec: RadioActions) {
        rule.setContent { Px6RadioTheme(ModernDarkSkin) { frontend.Content(s, rec) } }
    }

    private fun renderSettings(rec: Rec, s: RadioUiState = state()) {
        rule.setContent {
            Px6RadioTheme(ModernDarkSkin) {
                SettingsScreen(
                    settings = rec.settings, dabPresent = true, dabScanning = false, scanProgress = 0,
                    fmAvailable = true, fmSeeking = false, headlightOn = null,
                    onScanDab = { rec.scanDab() }, onScanFm = { rec.scanFm() },
                    skins = emptyList(), frontends = listOf(TilesFrontend, SplitFrontend),
                    onClearPreset = { rec.clearPreset(it) }, presets = emptyList(),
                    logoCount = 3, logoDownloading = false, logoStatus = null,
                    onDownloadLogos = { rec.downloadLogos() }, onClearLogos = { rec.clearLogos() },
                    onRefreshRadioDns = {}, onResetBackends = {}, onDeleteDiagnostics = {},
                    internetStations = emptyList(), internetResults = emptyList(),
                    internetSearching = false, onSearchInternet = {}, onAddInternet = {},
                    onRemoveInternet = {}, onAddManualStream = { _, _ -> },
                    placeResults = emptyList(), placeSearching = false, onSearchPlace = {},
                    onBack = { rec.closeSettings() },
                    onChange = { rec.updateSettings(it) },
                )
            }
        }
    }

    private fun click(text: String) = rule.onAllNodesWithText(text, substring = true).onFirst().performClick()
    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()

    // ==== SPLIT frontend ====================================================

    @Test fun split_tapFmBand_selectsFm() {
        val r = Rec(); render(SplitFrontend, state(), r)
        rule.onNodeWithText("FM").performClick()
        assertTrue("selectBand" in r.calls); assertEquals(Band.FM, r.lastBand)
    }

    @Test fun split_tapAmBand_selectsAm() {
        val r = Rec(); render(SplitFrontend, state(), r)
        rule.onNodeWithText("AM").performClick()
        assertEquals(Band.AM, r.lastBand)
    }

    @Test fun split_tapStation_selectsIt() {
        val r = Rec(); render(SplitFrontend, state(), r)
        click("Deutschlandfunk Kultur")
        assertTrue(r.calls.any { it.startsWith("selectStation:") })
    }

    @Test fun split_dabPrimaryWithLink_showsFollowingReady() {
        render(SplitFrontend, state(), Rec()); shown("Following bereit")
    }

    @Test fun split_fmFallback_showsFmHandover() {
        render(SplitFrontend, state(FollowingState.FM_FALLBACK), Rec()); shown("DAB schwach")
    }

    @Test fun split_ipFallback_showsInternet() {
        render(SplitFrontend, state(FollowingState.IP_FALLBACK), Rec()); shown("Internet")
    }

    @Test fun split_emptyList_showsHint() {
        render(SplitFrontend, state(st = emptyList(), np = null), Rec()); shown("Keine Sender")
    }

    @Test fun split_noFmLink_whenTuningStationWithoutLink() {
        render(SplitFrontend, state(np = stations[1]), Rec()); shown("kein FM-Link")
    }

    // ==== TILES frontend ====================================================

    @Test fun tiles_bottomNav_hasSettings() {
        render(TilesFrontend, state(), Rec()); shown("Einstellungen")
    }

    @Test fun tiles_showsNowPlayingName() {
        render(TilesFrontend, state(), Rec()); shown("Deutschlandfunk")
    }

    @Test fun tiles_dabPrimaryWithLink_showsFollowingReady() {
        render(TilesFrontend, state(), Rec()); shown("FOLLOWING BEREIT")
    }

    @Test fun tiles_fmFallback_header() {
        render(TilesFrontend, state(FollowingState.FM_FALLBACK), Rec()); shown("DAB SCHWACH")
    }

    @Test fun tiles_ipFallback_header() {
        render(TilesFrontend, state(FollowingState.IP_FALLBACK), Rec()); shown("INTERNET")
    }

    @Test fun tiles_dabBadge_shown() {
        render(TilesFrontend, state(), Rec()); shown("DAB+")
    }

    @Test fun tiles_showsPresetNames() {
        render(TilesFrontend, state(), Rec()); shown("Deutschlandfunk Nova")
    }

    @Test fun tiles_prevArrow_callsPrev() {
        val r = Rec(); render(TilesFrontend, state(), r)
        rule.onNodeWithText("‹").performClick(); assertTrue("prev" in r.calls)
    }

    @Test fun tiles_nextArrow_callsNext() {
        val r = Rec(); render(TilesFrontend, state(), r)
        rule.onNodeWithText("›").performClick(); assertTrue("next" in r.calls)
    }

    @Test fun tiles_noFmAvailable_hidesFollowing() {
        render(TilesFrontend, state(fmAvail = false), Rec())
        rule.onAllNodesWithText("FOLLOWING", substring = true).assertCountEquals(0)
    }

    // ==== state hints =======================================================

    @Test fun tiles_muted_rendersWithoutCrash() {
        render(TilesFrontend, state(muted = true, np = stations[0]), Rec()); shown("Deutschlandfunk")
    }

    @Test fun tiles_scanning_rendersWithoutCrash() {
        render(TilesFrontend, state(scanning = true), Rec()); shown("Deutschlandfunk")
    }

    @Test fun split_scanning_rendersWithoutCrash() {
        render(SplitFrontend, state(scanning = true), Rec()); shown("DAB")
    }

    // ==== SETTINGS ==========================================================

    @Test fun settings_bandsPage_dabHasScanEntry() {
        renderSettings(Rec())
        rule.onNodeWithText("Frequenzbereiche").performClick()
        rule.onNodeWithText("DAB+").performClick()
        shown("Senderliste erneuern")
    }

    /** The one control that must be reachable without reading a menu: the language. */
    @Test fun settings_root_hasLanguage() { renderSettings(Rec()); shown("Sprache") }

    @Test fun settings_root_hasRadioDnsSection() { renderSettings(Rec()); shown("RadioDNS") }

    @Test fun settings_root_hasLogosSection() { renderSettings(Rec()); shown("Senderlogos") }

    @Test fun settings_radioDnsPage_toggleUpdates() {
        val r = Rec(); r.settings = Settings(radioDnsEnabled = true); renderSettings(r)
        rule.onNodeWithText("RadioDNS").performClick()   // open the RadioDNS sub-page
        click("RadioDNS verwenden")                       // toggle the master switch
        assertTrue("updateSettings" in r.calls)
        assertTrue("RadioDNS toggled off", !r.settings.radioDnsEnabled)
    }

    /** Service following is one feature in three tiers — all three live on the playback page now. */
    @Test fun settings_playbackPage_hasIpFallbackToggle() {
        renderSettings(Rec()); rule.onNodeWithText("Wiedergabe").performClick()
        shown("Internet-Stream als Fallback")
    }

    @Test fun settings_playbackPage_hasFollowingToggles() {
        renderSettings(Rec()); rule.onNodeWithText("Wiedergabe").performClick()
        shown("Automatischer Wechsel DAB")
    }

    @Test fun settings_logosPage_downloadButton() {
        val r = Rec(); renderSettings(r)
        rule.onNodeWithText("Senderlogos").performClick()
        rule.onNodeWithText("Laden").performClick()
        assertTrue("downloadLogos" in r.calls)
    }
}
