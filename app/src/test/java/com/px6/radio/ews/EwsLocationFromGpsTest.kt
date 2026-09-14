package com.px6.radio.ews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Annex F of ETSI TS 104 089: deriving the receiver's own DAB location code from a WGS84 position.
 *
 * Safety-relevant arithmetic, so it is pinned to the worked example in the standard itself — if this
 * drifts, the radio silently stops matching real alerts for where it actually is.
 */
class EwsLocationFromGpsTest {

    /** Annex F.4, worked example: BBC Broadcasting House, London → Z10:B736BB. */
    @Test
    fun bbcBroadcastingHouseMatchesTheSpecExample() {
        val code = EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571)
        assertEquals("zone", 10, code?.zone)
        assertEquals(
            "digits B7 36 BB",
            listOf(0xB, 0x7, 0x3, 0x6, 0xB, 0xB),
            code?.digits,
        )
    }

    /** The alert code the broadcaster would send for that area, at every resolution, must match. */
    @Test
    fun everyPrefixOfTheDerivedCodeMatches() {
        val me = EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571)!!
        for (n in 1..6) {
            val area = EwsMatcher.parseAlertCode("10:" + "B736BB".substring(0, n))!!
            assertEquals("prefix of length $n must match", true, EwsMatcher.locationMatches(me, area))
        }
    }

    /** A different zone must never match, however similar the digits. */
    @Test
    fun anotherZoneDoesNotMatch() {
        val me = EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571)!!
        assertEquals(false, EwsMatcher.locationMatches(me, EwsMatcher.parseAlertCode("11:B7")!!))
    }

    /** Zone arithmetic from F.3: zone 1 is (36,0)–(72,36); zone 11 starts at the equator. */
    @Test
    fun zoneNumbering() {
        assertEquals(1, EwsMatcher.codeFromCoordinates(50.0, 10.0)?.zone)     // Germany-ish
        assertEquals(11, EwsMatcher.codeFromCoordinates(10.0, 10.0)?.zone)    // just north of equator
        assertEquals(10, EwsMatcher.codeFromCoordinates(50.0, -10.0)?.zone)   // west of Greenwich
    }

    /** Polar zones use a different first division (F.5) — refuse rather than return a wrong code. */
    @Test
    fun polarZonesAreRefused() {
        assertNull(EwsMatcher.codeFromCoordinates(85.0, 10.0))
        assertNull(EwsMatcher.codeFromCoordinates(-80.0, 10.0))
    }
}
