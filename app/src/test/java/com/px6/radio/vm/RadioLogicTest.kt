package com.px6.radio.vm

import com.px6.radio.model.Band
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the radio state logic — the transitions behind the bugs found on the device: switching
 * band must change the shown station, FM stations must be storable, presets must not duplicate.
 */
class RadioLogicTest {

    private val region = "Europa"

    private fun dab(id: String, name: String, ensemble: String = "WDR") =
        Station(id, name, Band.DAB, "", name.take(2), 0, 0, ensemble = ensemble, bitrateKbps = 96)

    private fun fm(id: String, name: String, khz: Int) =
        Station(id, name, Band.FM, "", "FM", 0, 0, frequencyKhz = khz)

    private fun playing(state: RadioUiState, station: Station) =
        state.copy(nowPlaying = NowPlaying(station, bandLine = ""))

    // --- the reported bug: DAB -> FM leaves the DAB station showing ---------

    @Test
    fun `switching DAB to FM changes the displayed station`() {
        val d = dab("d1", "WDR 2")
        var state = RadioUiState(stations = listOf(d), selectedBand = Band.DAB, fmAvailable = true)
        state = playing(state, d)

        val r = RadioLogic.selectBand(state, Band.FM, region)

        assertEquals(Band.FM, r.state.selectedBand)
        // Now-playing must no longer be the DAB station.
        assertEquals(Band.FM, r.state.nowPlaying?.station?.band)
        assertTrue(r.commands.any { it is AudioCmd.PlayAnalog })
    }

    @Test
    fun `switching to FM with no FM stations synthesises one so it is shown and storable`() {
        val d = dab("d1", "WDR 2")
        var state = RadioUiState(stations = listOf(d), selectedBand = Band.DAB, fmAvailable = true)
        state = playing(state, d)

        val r = RadioLogic.selectBand(state, Band.FM, region)

        val np = r.state.nowPlaying
        assertNotNull("must have a now-playing FM station", np)
        assertEquals(Band.FM, np!!.station.band)
        // And that station is in the list, so a preset can point at it.
        assertTrue(r.state.stations.any { it.id == np.station.id })
    }

    @Test
    fun `switching to a band already selected does nothing`() {
        val d = dab("d1", "WDR 2")
        val state = playing(
            RadioUiState(stations = listOf(d), selectedBand = Band.DAB), d,
        )
        val r = RadioLogic.selectBand(state, Band.DAB, region)
        assertTrue(r.commands.isEmpty())
        assertEquals(state, r.state)
    }

    @Test
    fun `switching to FM prefers a stored FM preset`() {
        val a = fm("fm.98500", "Eins", 98_500)
        val b = fm("fm.101200", "Zwei", 101_200)
        var state = RadioUiState(
            stations = listOf(a, b), selectedBand = Band.DAB, fmAvailable = true,
            presets = listOf(PresetSlot(1, "fm.101200")),
        )
        state = playing(state, dab("d1", "X"))

        val r = RadioLogic.selectBand(state, Band.FM, region)
        assertEquals("fm.101200", r.state.nowPlaying?.station?.id)
    }

    // --- the reported bug: cannot store an FM station -----------------------

    @Test
    fun `an FM station can be assigned to a preset`() {
        val f = fm("fm.98500", "Eins", 98_500)
        val state = playing(
            RadioUiState(
                stations = listOf(f), selectedBand = Band.FM,
                presets = (1..6).map { PresetSlot(it, null) },
            ),
            f,
        )
        val after = RadioLogic.assignPreset(state, 3)
        assertEquals("fm.98500", after.presets.first { it.index == 3 }.stationId)
    }

    @Test
    fun `assigning a station frees it from any other preset`() {
        val f = fm("fm.98500", "Eins", 98_500)
        val state = playing(
            RadioUiState(
                stations = listOf(f), selectedBand = Band.FM,
                presets = listOf(PresetSlot(1, "fm.98500"), PresetSlot(2, null)),
            ),
            f,
        )
        val after = RadioLogic.assignPreset(state, 2)
        assertNull("old preset must be freed", after.presets.first { it.index == 1 }.stationId)
        assertEquals("fm.98500", after.presets.first { it.index == 2 }.stationId)
    }

    @Test
    fun `assigning with nothing playing is a no-op`() {
        val state = RadioUiState(presets = listOf(PresetSlot(1, null)))
        assertEquals(state, RadioLogic.assignPreset(state, 1))
    }

    // --- manual tuning ------------------------------------------------------

    @Test
    fun `stepping up on FM moves by the raster and updates the display`() {
        val state = RadioUiState(selectedBand = Band.FM, manualFrequencyKhz = 98_500)
        val r = RadioLogic.stepAnalog(state, up = true, region)
        // Europe raster is 50 kHz.
        assertEquals(98_550, r.state.manualFrequencyKhz)
        assertEquals(98_550, (r.commands.first() as AudioCmd.PlayAnalog).tuneKhz)
        assertEquals(Band.FM, r.state.nowPlaying?.station?.band)
    }

    @Test
    fun `stepping wraps around the band`() {
        val state = RadioUiState(selectedBand = Band.FM, manualFrequencyKhz = 108_000)
        val r = RadioLogic.stepAnalog(state, up = true, region)
        assertEquals(87_500, r.state.manualFrequencyKhz)
    }

    @Test
    fun `tuning creates the station entry only once`() {
        var state = RadioUiState(selectedBand = Band.FM)
        state = RadioLogic.tuneAnalog(state, 95_000, region).state
        state = RadioLogic.tuneAnalog(state, 95_000, region).state
        assertEquals(1, state.stations.count { it.id == "fm.95000" })
    }

    @Test
    fun `tuning on DAB is ignored`() {
        val state = RadioUiState(selectedBand = Band.DAB)
        val r = RadioLogic.tuneAnalog(state, 95_000, region)
        assertTrue(r.commands.isEmpty())
        assertEquals(state, r.state)
    }

    // --- programme following on a manual band switch ------------------------

    @Test
    fun `manual DAB to FM follows the programme onto the linked frequency`() {
        val d = dab("d1", "WDR 2")
        var state = RadioUiState(stations = listOf(d), selectedBand = Band.DAB, fmAvailable = true)
        state = playing(state, d)

        val link = RadioLogic.FmLink(freqKhz = 100_400, pi = 0xD2C2)
        val r = RadioLogic.selectBand(state, Band.FM, region, linkedFm = link, currentName = "WDR 2")

        val np = r.state.nowPlaying!!
        assertEquals(Band.FM, np.station.band)
        assertEquals(100_400, np.station.frequencyKhz)
        // Keeps the programme name, not a bare frequency.
        assertEquals("WDR 2", np.station.name)
    }

    @Test
    fun `without a link a bare FM entry shows its frequency as the name`() {
        val d = dab("d1", "Nur DAB")
        var state = RadioUiState(stations = listOf(d), selectedBand = Band.DAB, fmAvailable = true)
        state = playing(state, d)

        val r = RadioLogic.selectBand(state, Band.FM, region)   // no link
        val np = r.state.nowPlaying!!
        assertTrue("name is the frequency", np.station.name.endsWith("MHz"))
    }

    // --- per-band memory ----------------------------------------------------

    @Test
    fun `switching away and back returns to the last station of each band`() {
        val d1 = dab("d1", "WDR 2")
        val d2 = dab("d2", "1LIVE")
        val f = fm("fm.98500", "Eins", 98_500)
        var state = RadioUiState(
            stations = listOf(d1, d2, f), selectedBand = Band.DAB, fmAvailable = true,
        )
        // Hear 1LIVE on DAB.
        state = RadioLogic.play(state, d2).state
        assertEquals("d2", state.nowPlaying?.station?.id)

        // Switch to FM (last FM is the only FM station).
        state = RadioLogic.selectBand(state, Band.FM, region).state
        assertEquals(Band.FM, state.nowPlaying?.station?.band)

        // Back to DAB must return to 1LIVE, not the first DAB station.
        state = RadioLogic.selectBand(state, Band.DAB, region).state
        assertEquals("d2", state.nowPlaying?.station?.id)
    }

    @Test
    fun `programme following wins over per-band memory`() {
        val d = dab("d1", "WDR 2")
        val oldFm = fm("fm.95000", "Alt", 95_000)
        var state = RadioUiState(
            stations = listOf(d, oldFm), selectedBand = Band.FM, fmAvailable = true,
            lastStationPerBand = mapOf(Band.FM to "fm.95000"),
        )
        state = RadioLogic.play(state, d).state   // now on DAB WDR 2

        val link = RadioLogic.FmLink(100_400, 0xD2C2)
        val r = RadioLogic.selectBand(state, Band.FM, region, linkedFm = link, currentName = "WDR 2")
        // Follows WDR 2 to 100.4, not back to the remembered 95.0.
        assertEquals(100_400, r.state.nowPlaying?.station?.frequencyKhz)
    }

    // --- station-list merge (the "list goes empty during a rescan" bug) -----

    @Test
    fun `merging a partial rescan batch does not shrink the list`() {
        val existing = listOf(dab("d1", "A"), dab("d2", "B"), dab("d3", "C"))
        // A rescan momentarily reports only one service.
        val merged = RadioLogic.mergeDabStations(existing, listOf(dab("d1", "A")))
        assertEquals(3, merged.size)   // still all three, not one
    }

    @Test
    fun `merging keeps FM and AM stations`() {
        val existing = listOf(dab("d1", "A"), fm("fm.98500", "Eins", 98_500))
        val merged = RadioLogic.mergeDabStations(existing, listOf(dab("d1", "A"), dab("d2", "Neu")))
        assertTrue(merged.any { it.id == "fm.98500" })   // FM survived the DAB update
        assertTrue(merged.any { it.id == "d2" })          // new DAB added
    }

    @Test
    fun `merging updates a service and appends new ones`() {
        val existing = listOf(dab("d1", "A"))
        val merged = RadioLogic.mergeDabStations(existing, listOf(dab("d1", "A"), dab("d2", "B")))
        assertTrue(merged.any { it.id == "d1" })
        assertTrue(merged.any { it.id == "d2" })
        assertEquals(2, merged.size)
    }

    @Test
    fun `an empty batch leaves the list unchanged`() {
        val existing = listOf(dab("d1", "A"), dab("d2", "B"))
        assertEquals(existing, RadioLogic.mergeDabStations(existing, emptyList()))
    }

    // --- FM -> DAB reverse lookup (offer the DAB+ version of the FM you're on) ---------------

    @Test
    fun `findDabForFm matches a DAB station by linked frequency or PI`() {
        val d1 = dab("d1", "WDR 2").copy(linkedFmFrequencyKhz = 99_200, linkedFmPi = 0xD3C2)
        val d2 = dab("d2", "1LIVE")
        val stations = listOf(d1, d2, fm("fm.99200", "WDR 2", 99_200))
        assertEquals("d1", RadioLogic.findDabForFm(stations, 99_200, null)?.id)   // by frequency
        assertEquals("d1", RadioLogic.findDabForFm(stations, 88_000, 0xD3C2)?.id) // by PI
        assertNull(RadioLogic.findDabForFm(stations, 87_500, 0x1234))             // no match
    }

    // --- implicit DAB<->FM linking (SId == PI), the German service-following path ---------------

    private fun fmPi(id: String, name: String, khz: Int, pi: Int) =
        Station(id, name, Band.FM, "", "FM", 0, 0, frequencyKhz = khz, piCode = pi)

    @Test
    fun `linkDabToFm links a DAB service to the FM station whose PI equals its SId`() {
        // DAB station id is "ensembleId.serviceId"; serviceId 0xd3c2 must match the FM PI 0xD3C2.
        val d = dab("11a2.d3c2", "WDR 2")
        val stations = listOf(d, fmPi("fm.99200", "WDR 2", 99_200, 0xD3C2))
        val linked = RadioLogic.linkDabToFm(stations).first { it.id == "11a2.d3c2" }
        assertEquals(99_200, linked.linkedFmFrequencyKhz)
        assertEquals(0xD3C2, linked.linkedFmPi)
    }

    @Test
    fun `linkDabToFm falls back to an exact name match when no PI is known yet`() {
        val d = dab("11a2.d3c2", "WDR 2")
        // FM station has the frequency but its PI hasn't been learned from RDS yet.
        val stations = listOf(d, fm("fm.99200", "WDR 2", 99_200))
        val linked = RadioLogic.linkDabToFm(stations).first { it.id == "11a2.d3c2" }
        assertEquals("name match gives the frequency", 99_200, linked.linkedFmFrequencyKhz)
    }

    @Test
    fun `linkDabToFm matches an abbreviated RDS PS name to the full DAB label`() {
        // FM RDS PS is 8 chars ("ANT UNNA"); DAB carries the full label; no PI learned yet.
        val d = dab("11a2.beef", "Antenne Unna")
        val stations = listOf(d, fm("fm.99900", "ANT UNNA", 99_900))
        val linked = RadioLogic.linkDabToFm(stations).first { it.id == "11a2.beef" }
        assertEquals(99_900, linked.linkedFmFrequencyKhz)
    }

    @Test
    fun `linkDabToFm leaves a DAB service without any FM counterpart unlinked`() {
        val d = dab("11a2.beef", "Nur-DAB-Sender")
        val stations = listOf(d, fmPi("fm.99200", "WDR 2", 99_200, 0xD3C2))
        val linked = RadioLogic.linkDabToFm(stations).first { it.id == "11a2.beef" }
        assertNull(linked.linkedFmFrequencyKhz)
    }

    @Test
    fun `linkDabToFm with no FM stations returns the list unchanged`() {
        val stations = listOf(dab("11a2.d3c2", "WDR 2"))
        assertEquals(stations, RadioLogic.linkDabToFm(stations))
    }
}
