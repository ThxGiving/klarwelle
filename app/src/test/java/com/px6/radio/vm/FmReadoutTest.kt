package com.px6.radio.vm

import com.px6.radio.fm.FmState
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desk tests for the FM/AM now-playing readout ([RadioLogic.fmNowPlaying]) — the logic behind
 * "RDS + frequency while manually tuning FM", verified without a CarManager/tuner (the emulator
 * has none). Reproduces the reported bugs: RDS not shown when parked on a station, and the big
 * frequency not tracking the tune.
 */
class FmReadoutTest {

    private val dab = Station("10bc.d210", "Deutschlandfunk", Band.DAB, "Ensemble", "DF", 0, 0,
        ensemble = "Bundesmux")

    private fun onFm(band: Band = Band.FM, np: Station? = dab, manual: Int? = null,
                     following: FollowingState = FollowingState.DAB_PRIMARY,
                     stations: List<Station> = emptyList()) =
        RadioUiState(
            selectedBand = band, demoMode = false,
            nowPlaying = np?.let { NowPlaying(it, bandLine = "x") },
            manualFrequencyKhz = manual, following = following, stations = stations,
        )

    private fun fm(khz: Int = 98_700, ps: String? = null, rt: String? = null,
                   pi: Int? = null, seeking: Boolean = false) =
        FmState(available = true, freqKhz = khz, ps = ps, rt = rt, pi = pi, seeking = seeking)

    // ---- the bug: manual FM tune while a DAB service was "playing" ----------

    @Test fun manualTune_adoptsFmStation_evenIfDabWasPlaying() {
        // On FM band, DAB still the last now-playing, tuner reports 98.7 — must switch to FM.
        val np = RadioLogic.fmNowPlaying(onFm(band = Band.FM, np = dab, manual = 98_700), fm(98_700))
        assertTrue("now-playing must become FM", np?.station?.band == Band.FM)
        assertEquals(98_700, np?.station?.frequencyKhz)
    }

    @Test fun manualTune_noRds_showsFrequency() {
        val np = RadioLogic.fmNowPlaying(onFm(manual = 98_700), fm(98_700, ps = null))
        assertEquals("FM %.1f".format(98.7), np?.station?.name)   // locale-aware (DE: comma)
    }

    @Test fun onStation_rdsPs_isShown() {
        val np = RadioLogic.fmNowPlaying(onFm(manual = 98_700), fm(98_700, ps = "1LIVE"))
        assertEquals("1LIVE", np?.station?.name)
    }

    @Test fun onStation_radioText_becomesDls() {
        val np = RadioLogic.fmNowPlaying(onFm(manual = 98_700), fm(98_700, ps = "1LIVE", rt = "Now: some song"))
        assertEquals("Now: some song", np?.dlsText)
    }

    @Test fun onStation_piCarried() {
        val np = RadioLogic.fmNowPlaying(onFm(manual = 98_700), fm(98_700, pi = 0xD391))
        assertEquals(0xD391, np?.station?.piCode)
    }

    // ---- the bug: the big frequency must track the manual scale ------------

    @Test fun bigFrequency_followsManualScale_notLaggingTuner() {
        // User stepped to 99.2 (manual scale), but the tuner still reports the old 98.7 for a moment.
        val np = RadioLogic.fmNowPlaying(onFm(manual = 99_200), fm(98_700, ps = null))
        assertEquals("big readout follows the intended 99.2, not the lagging 98.7",
            "FM %.1f".format(99.2), np?.station?.name)
        assertEquals(99_200, np?.station?.frequencyKhz)
    }

    // ---- fallback + guards -------------------------------------------------

    @Test fun fmFallback_keepsLinkedStationName() {
        val linked = Station("fm.98700", "WDR 2", Band.FM, "", "W2", 0, 0, frequencyKhz = 98_700)
        val s = onFm(band = Band.DAB, np = linked, following = FollowingState.FM_FALLBACK)
        val np = RadioLogic.fmNowPlaying(s, fm(98_700, ps = null))
        assertEquals("WDR 2", np?.station?.name)   // keeps the linked name, not "FM 98.7"
    }

    @Test fun onDabBand_notFallback_returnsNull() {
        assertNull(RadioLogic.fmNowPlaying(onFm(band = Band.DAB, np = dab), fm(98_700)))
    }

    @Test fun seeking_doesNotAdoptStation() {
        // Mid-seek the frequency is meaningless, so don't jump now-playing onto it.
        assertNull(RadioLogic.fmNowPlaying(onFm(band = Band.FM, np = dab), fm(98_700, seeking = true)))
    }

    @Test fun reusesExistingStationFromList() {
        val known = Station("fm.98700", "1LIVE", Band.FM, "", "1L", 0, 0, frequencyKhz = 98_700, piCode = 0xD391)
        val np = RadioLogic.fmNowPlaying(onFm(manual = 98_700, stations = listOf(known)), fm(98_700, ps = "1LIVE"))
        assertEquals("1LIVE", np?.station?.name)
        assertEquals("fm.98700", np?.station?.id)
    }
}
