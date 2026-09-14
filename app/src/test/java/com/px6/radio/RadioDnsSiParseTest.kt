package com.px6.radio

import eu.hradio.core.radiodns.PxRadioDnsLookup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RadioDNS-Testset (Desk, kein Auto): lässt den **echten** SI-Parser + die **echte** Mapping-Logik
 * (`PxRadioDnsLookup.parseSi`) auf eine **echte** eingefangene WDR-SI.xml los. Damit sehen wir
 * ohne Head-Unit, ob pro Service Logos + Streams extrahiert werden — genau der Punkt, an dem am
 * Gerät nur 1 von 46 Logos ankam.
 *
 * SI.xml stammt von `http://dewdr.radiodns.ard.de/radiodns/spi/3.1/SI.xml` (WDR-Ensemble 10fa).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RadioDnsSiParseTest {

    private fun openSi() =
        javaClass.classLoader!!.getResourceAsStream("wdr_si.xml")
            ?: error("wdr_si.xml resource missing")

    @Test
    fun wdrSi_extracts_logos_and_streams_per_service() {
        val r = openSi().use { PxRadioDnsLookup.parseSi(it) }

        var svcWithLogo = 0
        var svcWithStream = 0
        var totalLogos = 0
        println("=== WDR SI.xml: ${r.services.size} Services ===")
        for (s in r.services) {
            val sid = "0x%04x".format(s.sid)
            totalLogos += s.logos.size
            if (s.logos.isNotEmpty()) svcWithLogo++
            if (s.streams.isNotEmpty()) svcWithStream++
            val bestLogo = s.logos.maxByOrNull { it.width * it.height }?.url ?: "—"
            println("  $sid  logos=${s.logos.size} streams=${s.streams.size}  best=$bestLogo")
        }
        println("=== Summe: $totalLogos Logos · $svcWithLogo Services mit Logo · $svcWithStream mit Stream ===")

        // Kernaussage: der Parser MUSS pro Service Logos liefern (SI hat 22 Services, alle mit
        // <mediaDescription><multimedia/>). Wenn das hier 0/1 ist, liegt der Bug im Parser/Mapping,
        // NICHT an DNS/HTTP am Gerät.
        assertTrue("Es wurden Services geparst", r.services.isNotEmpty())
        assertTrue("Services mit Logo (erwartet >1): $svcWithLogo", svcWithLogo > 1)
    }
}
