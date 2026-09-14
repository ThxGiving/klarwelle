package com.px6.radio.ews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The place-search helper hands the user a ready-made 12-symbol code. That code has to survive the
 * very parser the app validates hand-typed entries with — same digits, same zone, and a checksum
 * that verifies. If the encoding drifted, the helper would quietly produce codes that match nothing.
 */
class PlaceCodeRoundTripTest {

    private fun roundTrip(lat: Double, lon: Double) {
        val place = Place("test", lat, lon)
        val presentation = PlaceSearch.presentationCodeFor(place)
        assertNotNull("no code for ($lat, $lon)", presentation)
        assertEquals("12 symbols", 12, presentation!!.length)
        assertEquals("only symbols 1..8", 12, presentation.count { it in '1'..'8' })

        val parsed = EwsMatcher.parseReceiverCode(presentation)
        assertNotNull("the app's own parser must accept it (checksum!)", parsed)
        assertEquals(EwsMatcher.codeFromCoordinates(lat, lon), parsed)
    }

    @Test fun londonRoundTrips() = roundTrip(51.5187412, -0.1434571)

    @Test fun severalPlacesRoundTrip() {
        roundTrip(51.5380, 7.6890)      // Unna
        roundTrip(48.1372, 11.5756)     // München
        roundTrip(-33.8688, 151.2093)   // Sydney — southern hemisphere, east of 144
        roundTrip(40.7128, -74.0060)    // New York — western longitudes wrap via +360
        roundTrip(0.0, 0.0)             // equator / Greenwich corner case
    }

    /** The worked example from annex F must come back as the very code the spec prints. */
    @Test fun londonKeepsTheSpecDigits() {
        val parsed = EwsMatcher.parseReceiverCode(
            PlaceSearch.presentationCodeFor(Place("BBC", 51.5187412, -0.1434571))!!
        )!!
        assertEquals(10, parsed.zone)
        assertEquals(listOf(0xB, 0x7, 0x3, 0x6, 0xB, 0xB), parsed.digits)
    }

    /** Polar positions are declined rather than guessed — the helper must not invent a code. */
    @Test fun polarPlacesYieldNoCode() {
        assertNull(PlaceSearch.presentationCodeFor(Place("north", 88.0, 10.0)))
    }
}
