package com.px6.radio.vm

import com.px6.radio.model.Band
import com.px6.radio.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The ViewModel skips the DAB<->FM link rebuild when a cheap signature over the station list is
 * unchanged, because the real computation is an O(nDAB x nFM) regex cross product that used to run on
 * the main thread for every DAB state emission.
 *
 * That is only safe if the signature covers every field the linking actually reads. These tests pin
 * the contract from the other side: fields the linker ignores must not change its result (so skipping
 * on them is correct), and fields it uses must change it (so they have to be in the signature).
 */
class DabFmLinkInputsTest {

    private fun dab(id: String, name: String, bitrate: Int? = null, ensemble: String? = null) =
        Station(id, name, Band.DAB, "", "XX", 0, 0, ensemble = ensemble, bitrateKbps = bitrate)

    private fun fm(id: String, name: String, khz: Int, pi: Int?) =
        Station(id, name, Band.FM, "", "XX", 0, 0, frequencyKhz = khz, piCode = pi)

    private val base = listOf(
        dab("10bc.d391", "1LIVE"),
        fm("fm.1067", "1LIVE", 106_700, 0xD391),
    )

    private fun linkOf(stations: List<Station>) =
        RadioLogic.linkDabToFm(stations).first { it.band == Band.DAB }
            .let { it.linkedFmFrequencyKhz to it.linkedFmPi }

    @Test
    fun linksTheDabServiceToItsFmStation() {
        assertEquals(106_700 to 0xD391, linkOf(base))
    }

    /** Bitrate, ensemble name and the like are not inputs — the signature may ignore them. */
    @Test
    fun fieldsOutsideTheSignatureDoNotChangeTheResult() {
        val noisy = listOf(
            dab("10bc.d391", "1LIVE", bitrate = 96, ensemble = "WDR"),
            fm("fm.1067", "1LIVE", 106_700, 0xD391),
        )
        assertEquals("bitrate/ensemble must not affect linking", linkOf(base), linkOf(noisy))
    }

    /** Everything the linker reads must be in the signature — prove each one matters. */
    @Test
    fun fieldsInsideTheSignatureDoChangeTheResult() {
        // FM frequency
        assertNotEquals(
            linkOf(base),
            linkOf(listOf(dab("10bc.d391", "1LIVE"), fm("fm.1067", "1LIVE", 101_300, 0xD391))),
        )
        // FM PI (falls back to a name match, which still resolves — but the PI carried changes)
        assertNotEquals(
            linkOf(base),
            linkOf(listOf(dab("10bc.d391", "1LIVE"), fm("fm.1067", "1LIVE", 106_700, 0xD000))),
        )
        // DAB id carries the SId used for the primary SId==PI match
        assertNotEquals(
            linkOf(base),
            linkOf(listOf(dab("10bc.dfff", "Nothing Alike"), fm("fm.1067", "1LIVE", 106_700, 0xD391))),
        )
        // Names drive the secondary match when no PI matches
        val byName = listOf(dab("10bc.aaaa", "Antenne Unna"), fm("fm.900", "ANT UNNA", 90_000, null))
        val renamed = listOf(dab("10bc.aaaa", "Nothing Alike"), fm("fm.900", "ANT UNNA", 90_000, null))
        assertNotEquals(linkOf(byName), linkOf(renamed))
    }

    /** Order matters (first PI wins, first name match wins), so the signature must be order-sensitive. */
    @Test
    fun orderOfFmEntriesCanChangeTheResult() {
        val a = listOf(
            dab("10bc.aaaa", "WDR"),
            fm("fm.1", "WDR", 100_000, null),
            fm("fm.2", "WDR", 200_000, null),
        )
        val b = listOf(
            dab("10bc.aaaa", "WDR"),
            fm("fm.2", "WDR", 200_000, null),
            fm("fm.1", "WDR", 100_000, null),
        )
        assertNotEquals("first match wins — order is an input", linkOf(a), linkOf(b))
    }
}
