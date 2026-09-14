package com.px6.radio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.px6.radio.data.PersistedAnalog
import com.px6.radio.data.RadioStore
import com.px6.radio.model.Band
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the "everything vanishes on restart" family of bugs is gone: presets, sort order, frozen
 * names and the FM/AM stations must survive a write → read round-trip through the real DataStore.
 * (sortMode/fixedNames used to never be written at all; FM/AM stations existed only in memory.)
 */
@RunWith(AndroidJUnit4::class)
class StorePersistenceTest {

    private val store = RadioStore(ApplicationProvider.getApplicationContext())

    @Test
    fun presetsSortFixedNamesAndStations_surviveAWriteReadRoundTrip() = runBlocking {
        val presets = listOf(PresetSlot(1, "d1.abcd"), PresetSlot(2, null))
        val fixedNames = mapOf("d1.abcd" to "WDR 2", "fm.98500" to "1LIVE")
        val analog = listOf(
            PersistedAnalog(Band.FM, 98_500, "1LIVE", 0xD3C2),
            PersistedAnalog(Band.AM, 1422, "1422 kHz", null),
        )

        val dabStations = listOf(
            com.px6.radio.model.Station(
                id = "d1.abcd", name = "WDR 2", band = Band.DAB, subtitle = "Kanal 11D · WDR",
                logoInitials = "WD", logoStart = 1L, logoEnd = 2L, ensemble = "WDR",
                bitrateKbps = 96, frequencyKhz = 216_928, linkedFmFrequencyKhz = 99_200,
                linkedFmPi = 0xD3C2, secondaryLabels = listOf("WDR Event"),
            ),
        )

        val perBand = mapOf(Band.DAB to "d1.abcd", Band.FM to "fm.98500")
        store.write(
            settings = Settings(),
            presets = presets,
            sortMode = SortMode.ALPHABET,
            fixedNames = fixedNames,
            fmNames = emptyMap(),
            analogStations = analog,
            dabStations = dabStations,
            ipStations = emptyList(),
            ipGains = emptyMap(),
            radioDnsStreams = mapOf("d1.abcd" to "https://example.invalid/simulcast.mp3"),
            ipSeedVersion = 0,
            lastPlayedId = "d1.abcd",
            lastStationPerBand = perBand,
        )

        val back = store.read()!!
        // The RadioDNS lookup needs a live network when it runs, so a simulcast address that was
        // found once must survive the restart — otherwise the internet fallback is a matter of luck.
        assertEquals("IP simulcast survives a restart",
            "https://example.invalid/simulcast.mp3", back.radioDnsStreams["d1.abcd"])
        assertEquals("sort order persists", SortMode.ALPHABET, back.sortMode)
        assertEquals("frozen names persist", fixedNames, back.fixedNames)
        assertEquals("assigned preset persists", "d1.abcd", back.presets[1])
        assertTrue("empty preset stays empty", 2 !in back.presets)
        // FM/AM stations survive with name + PI, so a saved FM tile works without a rescan.
        assertEquals("analog stations persist", analog.toSet(), back.analogStations.toSet())
        // DAB stations survive so the list + DAB tiles appear at once, without a rescan.
        assertEquals("dab stations persist", dabStations.toSet(), back.dabStations.toSet())
        // Last-played + per-band memory survive, so a restart resumes where you were.
        assertEquals("last-played persists", "d1.abcd", back.lastPlayedId)
        assertEquals("per-band memory persists", perBand, back.lastStationPerBand)
    }
}
