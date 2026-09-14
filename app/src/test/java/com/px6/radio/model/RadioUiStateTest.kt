package com.px6.radio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the pure derivations on the UI state — list filtering, sorting, stepping, name display. */
class RadioUiStateTest {

    private fun dab(id: String, name: String, ensemble: String? = null) =
        Station(id, name, Band.DAB, "", name.take(2), 0, 0, ensemble = ensemble)

    private fun fm(id: String, name: String, khz: Int) =
        Station(id, name, Band.FM, "", name.take(2), 0, 0, frequencyKhz = khz)

    @Test
    fun `the list shows only the selected band`() {
        val stations = listOf(dab("d1", "DLF"), fm("f1", "WDR2", 99_200))
        val state = RadioUiState(stations = stations, selectedBand = Band.FM, fmAvailable = true)
        assertEquals(listOf("f1"), state.visibleStations.map { it.id })

        // Switching the band tab shows the other band.
        assertEquals(listOf("d1"), state.copy(selectedBand = Band.DAB).visibleStations.map { it.id })
    }

    @Test
    fun `alphabetical sort orders by name`() {
        val stations = listOf(dab("d1", "Cosmo"), dab("d2", "1LIVE"), dab("d3", "ARD"))
        val state = RadioUiState(
            stations = stations, selectedBand = Band.DAB, sortMode = SortMode.ALPHABET,
        )
        assertEquals(listOf("1LIVE", "ARD", "Cosmo"), state.visibleStations.map { it.name })
    }

    @Test
    fun `group sort keeps an ensemble together`() {
        val stations = listOf(
            dab("d1", "B", ensemble = "WDR"),
            dab("d2", "A", ensemble = "Bundesmux"),
            dab("d3", "A", ensemble = "WDR"),
        )
        val state = RadioUiState(
            stations = stations, selectedBand = Band.DAB, sortMode = SortMode.GROUP,
        )
        // Bundesmux before WDR, and within WDR the two stay adjacent.
        assertEquals(listOf("Bundesmux", "WDR", "WDR"), state.visibleStations.map { it.ensemble })
    }

    @Test
    fun `stepping walks stored stations when set to STORED`() {
        val stations = listOf(dab("d1", "A"), dab("d2", "B"), dab("d3", "C"))
        val state = RadioUiState(
            stations = stations,
            presets = listOf(PresetSlot(1, "d3"), PresetSlot(2, "d1")),
            settings = Settings(stepMode = StepMode.STORED),
        )
        assertEquals(listOf("d3", "d1"), state.steppableStations.map { it.id })
    }

    @Test
    fun `stepping walks the whole band when set to RECEIVABLE`() {
        val stations = listOf(dab("d1", "A"), fm("f1", "X", 99_000))
        val state = RadioUiState(
            stations = stations, selectedBand = Band.DAB,
            settings = Settings(stepMode = StepMode.RECEIVABLE),
        )
        assertEquals(listOf("d1"), state.steppableStations.map { it.id })
    }

    @Test
    fun `display name marks the FM fallback`() {
        val station = dab("d1", "WDR 2")
        val np = NowPlaying(station = station, bandLine = "")
        val onDab = RadioUiState(
            stations = listOf(station), nowPlaying = np, following = FollowingState.DAB_PRIMARY,
        )
        assertEquals("WDR 2", onDab.displayName(station))

        val onFm = onDab.copy(following = FollowingState.FM_FALLBACK)
        assertEquals("WDR 2 (FM)", onFm.displayName(station))
    }

    @Test
    fun `a fixed name overrides the broadcast one`() {
        val station = dab("d1", "Ein sehr langer durchlaufender Sendername")
        val state = RadioUiState(
            stations = listOf(station),
            fixedNames = mapOf("d1" to "Kurz"),
        )
        assertEquals("Kurz", state.displayName(station))
    }

    @Test
    fun `station lookup is null-safe`() {
        val state = RadioUiState(stations = listOf(dab("d1", "A")))
        assertEquals("A", state.station("d1")?.name)
        assertTrue(state.station("nope") == null)
        assertTrue(state.station(null) == null)
    }
}
