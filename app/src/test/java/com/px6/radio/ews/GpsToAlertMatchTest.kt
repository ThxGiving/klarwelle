package com.px6.radio.ews

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The whole chain for one real address, end to end: a GPS fix goes in, and what comes out has to be
 * both the code the standard prescribes AND the thing the alert matcher actually consults.
 *
 * The address is Brandenburger Tor, Pariser Platz, 10117 Berlin — WGS84 (52,5162746 / 13,3777041).
 * Its expected code was worked out independently from annex F rather than read off our own code:
 *
 *   SE   = 90 − 52,5162746 = 37,4837254
 *   EE   = 13,3777041
 *   Zone = 10 × int((37,4837254 − 18)/36) + int(13,3777041/36) + 1 = 1
 *   SC   = int(frac((37,4837254 − 18)/36) × 4096) = 2216
 *   EC   = int(frac(13,3777041/36)        × 4096) = 1522
 *   CC   = interleave(SC, EC) 2 bits at a time, south first  →  91BB82
 *
 * The same arithmetic reproduces the standard's own worked example (BBC Broadcasting House →
 * Z10:B736BB), which [EwsLocationFromGpsTest] pins separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsToAlertMatchTest {

    private val lat = 52.5162746
    private val lon = 13.3777041
    private val expected = "Z1:91BB82"

    private fun text(c: EwsMatcher.Code) =
        "Z${c.zone}:" + c.digits.joinToString("") { it.toString(16).uppercase() }

    /** A position fed through the real LocationManager path becomes the expected code. */
    @Test fun aFixAtBrandenburgerTorYieldsTheSpecCode() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // The grant is what the app now asks for when the setting is switched on. Without it
        // GpsLocationCode deliberately does nothing — which is exactly how this test first failed,
        // and how the feature behaved on the device before that request existed.
        Shadows.shadowOf(ctx as android.app.Application)
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        fun fix(la: Double, lo: Double) = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = la; longitude = lo; time = System.currentTimeMillis()
        }

        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(lat, lon))
        val gps = GpsLocationCode(ctx)
        gps.start()

        assertNotNull("a fix must produce a code", gps.code)
        assertEquals(expected, text(gps.code!!))
        assertEquals("the LocationManager path and the direct call must agree",
            EwsMatcher.codeFromCoordinates(lat, lon), gps.code)
        assertEquals("and the position it came from is kept for the panel",
            lat to lon, gps.lastFix)

        // Driving out of the cell changes the code — the whole point of following the vehicle.
        // (Its listener sits on the main looper, which Robolectric keeps paused.)
        shadow.simulateLocation(fix(52.0, 13.0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("a fix in another cell moves the area", "Z1:91F81F", text(gps.code!!))

        gps.stop()
    }

    /**
     * "Is it monitored?" — the derived code has to be what an incoming alert is matched against, at
     * every broadcast resolution from the whole zone down to the full six digits.
     */
    @Test fun anAlertForThatAreaIsPlayed() {
        val me = EwsMatcher.codeFromCoordinates(lat, lon)!!
        for (n in 1..6) {
            val area = "1:" + "91BB82".substring(0, n)
            assertTrue("an alert for $area covers Brandenburger Tor",
                EwsMatcher.locationMatches(me, EwsMatcher.parseAlertCode(area)!!))
        }
    }

    /** And an alert for somewhere else must NOT be played, or the warning means nothing. */
    @Test fun anAlertForAnotherAreaIsNotPlayed() {
        val me = EwsMatcher.codeFromCoordinates(lat, lon)!!
        listOf(
            "1:92",        // neighbouring cell, same zone
            "1:91BB83",    // adjacent cell at full resolution
            "10:91BB82",   // same digits, wrong zone (London's zone)
            "2:9",         // different zone entirely
        ).forEach { code ->
            assertFalse("$code must not match", EwsMatcher.locationMatches(me, EwsMatcher.parseAlertCode(code)!!))
        }
    }

    /**
     * What the user actually sees. Codes are entered and stored as the 12-symbol presentation form
     * (annex A.3), so the derived one has to be shown the same way — and it must survive the round
     * trip back through the parser, checksum included.
     */
    @Test fun theDerivedCodeRoundTripsThroughItsPresentationForm() {
        val me = EwsMatcher.codeFromCoordinates(lat, lon)!!
        val shown = EwsMatcher.presentationOf(me)
        assertEquals("twelve symbols", 12, shown.length)
        assertTrue("only the digits 1..8 exist in this format — no 9, no 0",
            shown.all { it in '1'..'8' })
        assertEquals("parsing it back must give the same code", me, EwsMatcher.parseReceiverCode(shown))
    }

    /** The standard's own worked example, so the rendering is pinned to the norm and not to us. */
    @Test fun theSpecExampleRendersAsPrinted() {
        val bbc = EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571)!!
        assertEquals("236674438484", EwsMatcher.presentationOf(bbc))
    }

    /** And grouped for reading, exactly as annex A.3 prints it. */
    @Test fun groupingMatchesHowTheStandardPrintsIt() {
        val bbc = EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571)!!
        assertEquals("2366-7443-8484", EwsMatcher.grouped(EwsMatcher.presentationOf(bbc)))
        // Already grouped, or not a 12-symbol code at all: left alone rather than mangled.
        assertEquals("2366-7443-8484", EwsMatcher.grouped("2366-7443-8484"))
        assertEquals("nonsense", EwsMatcher.grouped("nonsense"))
    }
}
