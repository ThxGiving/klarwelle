package com.px6.radio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the per-band tuning profiles — the figures taken from the head unit's own ROM. */
class TuningProfileTest {

    @Test
    fun `fm europe steps in 50 kHz`() {
        val fm = TuningProfile.forBand(Band.FM, "Europa") as TuningProfile.Continuous
        assertEquals(87_500, fm.minKhz)
        assertEquals(108_000, fm.maxKhz)
        assertEquals(50, fm.stepKhz)
    }

    @Test
    fun `fm snaps a raw frequency onto the raster`() {
        val fm = TuningProfile.forBand(Band.FM, "Europa") as TuningProfile.Continuous
        // 98,537 is not a station; the nearest 50 kHz step is 98,550.
        assertEquals(98_550, fm.snap(98_537))
        // Already on the raster stays put.
        assertEquals(98_500, fm.snap(98_500))
    }

    @Test
    fun `snap stays within the band`() {
        val fm = TuningProfile.forBand(Band.FM, "Europa") as TuningProfile.Continuous
        assertEquals(fm.minKhz, fm.snap(1_000))
        assertEquals(fm.maxKhz, fm.snap(200_000))
    }

    @Test
    fun `medium wave is 9 kHz in europe and 10 in the US`() {
        val eu = TuningProfile.forBand(Band.AM, "Europa") as TuningProfile.Continuous
        assertEquals(9, eu.stepKhz)
        assertEquals(522, eu.minKhz)
        assertEquals(1_620, eu.maxKhz)

        val us = TuningProfile.forBand(Band.AM, "US") as TuningProfile.Continuous
        assertEquals(10, us.stepKhz)
    }

    @Test
    fun `am formats as kilohertz, fm as megahertz`() {
        val am = TuningProfile.forBand(Band.AM, "Europa")
        assertEquals("1422 kHz", am.format(1_422))
        val fm = TuningProfile.forBand(Band.FM, "Europa")
        assertTrue(fm.format(98_500).endsWith("MHz"))
    }

    @Test
    fun `dab is a channel table, not a continuum`() {
        val dab = TuningProfile.forBand(Band.DAB, "Europa")
        assertTrue(dab is TuningProfile.Channels)
        assertEquals(174_928, dab.minKhz)  // 5A
        assertEquals(239_200, dab.maxKhz)  // 13F
    }

    @Test
    fun `dab table has all 38 band III channels`() {
        assertEquals(38, DAB_BAND_III.size)
        assertEquals("5A", DAB_BAND_III.first().name)
        assertEquals("13F", DAB_BAND_III.last().name)
        // Strictly ascending in frequency — the scale relies on this order.
        DAB_BAND_III.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.name} < ${b.name}", a.khz < b.khz)
        }
    }

    @Test
    fun `dab nearest snaps to the closest channel`() {
        val dab = TuningProfile.forBand(Band.DAB, "Europa") as TuningProfile.Channels
        // WDR's real 11D is 222 064 kHz; a reading a little off must land on 11D.
        assertEquals("11D", dab.nearest(222_000).name)
        assertEquals("5A", dab.nearest(0).name)
        assertEquals("13F", dab.nearest(999_999).name)
    }
}
