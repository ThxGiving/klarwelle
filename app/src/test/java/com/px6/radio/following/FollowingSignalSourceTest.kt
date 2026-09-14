package com.px6.radio.following

import com.px6.radio.audio.AudioRouter
import com.px6.radio.dab.DabState
import com.px6.radio.dab.FmLink
import com.px6.radio.fm.FmState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The service-following debounce exists to stop a single reception dip from throwing the radio onto
 * FM and straight back ("ping-pong"). It counts *readings*, so the engine must be driven by actual
 * reception reports — not by every [DabState] emission.
 *
 * It used to be driven by every emission. `DabState` changes for all sorts of unrelated reasons (a
 * new DLS line, a slideshow image, scan progress), each carrying the same momentary `signalBars`, so
 * one weak reading plus two DLS updates in the same second satisfied a debounce of 2 and handed over
 * while reception was fine. The existing simulator could not catch this: it pushed a fresh state per
 * reading, so in the harness every emission genuinely *was* a reading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowingSignalSourceTest {

    private class FakeDab : DabFollowSource {
        // Start on GOOD reception: a StateFlow replays its current value to a new collector, and
        // signalBars defaults to 0 — which the decider would rightly count as a first weak reading.
        val flow = MutableStateFlow(DabState(nowPlayingId = "1001.d001", signalBars = 4))
        override val state: StateFlow<DabState> = flow
        private val reports = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 64)
        override val signalReports: kotlinx.coroutines.flow.SharedFlow<Int> = reports
        override fun getLinkedDab() = emptyList<String>()
        override fun getLinkedFm() = FmLink(freqKhz = 98_500, pi = 0xD3B2)
        override fun tune(stationId: String) {}

        /** A genuine reception report — exactly what the tuner callback emits. */
        fun reading(bars: Int) {
            flow.value = flow.value.copy(signalBars = bars)
            reports.tryEmit(bars)
        }

        /** Anything else that makes DabState change — must NOT count as a reading. */
        fun nonReading(dls: String) {
            flow.value = flow.value.copy(dls = dls)
        }
    }

    private class FakeFm : FmFollowSource {
        override val available = true
        override val state: StateFlow<FmState> = MutableStateFlow(FmState(available = true))
        override fun tune(khz: Int) {}
    }

    private fun engineWith(dab: FakeDab, actions: MutableList<String>): ServiceFollowingEngine {
        val router = AudioRouter(
            setDabVolume = { v -> actions += if (v == 0f) "DAB.mute" else "DAB.full" },
            enterAnalog = { actions += "FM.enter" },
            exitAnalog = { actions += "FM.exit" },
        )
        return ServiceFollowingEngine(dab, FakeFm(), router, kotlinx.coroutines.MainScope())
    }

    /**
     * One weak reading, then a flurry of unrelated state changes. With a debounce of 2 the engine
     * must still be on DAB: the non-readings are not evidence about reception.
     */
    @Test
    fun nonReadingEmissionsDoNotAdvanceTheDebounce() = runTest(UnconfinedTestDispatcher()) {
        val dab = FakeDab()
        val actions = mutableListOf<String>()
        val engine = engineWith(dab, actions)
        engine.configure(enabled = true, threshold = 2, softFade = false, dabDab = false)
        backgroundScope.launch { engine.run() }

        dab.reading(1)                       // one weak reading — below threshold
        dab.nonReading("Now playing: A")     // same stale bars, must not count
        dab.nonReading("Now playing: B")
        dab.nonReading("Now playing: C")

        assertEquals(
            "a single dip plus unrelated updates must not hand over",
            com.px6.radio.model.FollowingState.DAB_PRIMARY,
            engine.state.value.following,
        )
        assertEquals("no analog grab expected", false, actions.contains("FM.enter"))
    }

    /** Two genuine weak readings in a row are what the debounce is for — that one must hand over. */
    @Test
    fun twoRealWeakReadingsDoHandOver() = runTest(UnconfinedTestDispatcher()) {
        val dab = FakeDab()
        val actions = mutableListOf<String>()
        val engine = engineWith(dab, actions)
        engine.configure(enabled = true, threshold = 2, softFade = false, dabDab = false)
        backgroundScope.launch { engine.run() }

        dab.reading(1)
        dab.reading(1)

        assertEquals("two weak readings must hand over to FM", true, actions.contains("FM.enter"))
    }
}
