package com.px6.radio.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioDnsBearerTest {

    @Test
    fun `dab gcc is sid country nibble plus ecc`() {
        assertEquals("de0", RadioDnsBearer.dabGcc(0xD3C2, 0xE0))   // WDR: SId D3C2, ECC E0
        assertEquals("1e0", RadioDnsBearer.dabGcc(0x1234, 0xE0))   // 0x1xxx German SIds need "1e0"
        assertEquals("ce1", RadioDnsBearer.dabGcc(0xC221, 0xE1))   // BBC
    }

    @Test
    fun `fm gcc is pi country nibble plus ecc`() {
        assertEquals("de0", RadioDnsBearer.fmGcc(0xD392, 0xE0))
        assertEquals("5e0", RadioDnsBearer.fmGcc(0x5201, 0xE0))    // Italy shares ECC E0
        assertEquals("ce1", RadioDnsBearer.fmGcc(0xC201, 0xE1))
    }

    @Test
    fun `unknown ecc falls back to germany, short ids do not break the nibble`() {
        assertEquals("de0", RadioDnsBearer.fmGcc(0xD392, null))
        assertEquals("0e0", RadioDnsBearer.fmGcc(0x0392, null))    // the old first-hex-char trick got this wrong
    }

    @Test
    fun `station name key folds case, spaces and punctuation`() {
        assertEquals("wdr2", "WDR 2".stationNameKey())
        assertEquals("wdr2", "wdr-2".stationNameKey())
        assertEquals("1live", "1LIVE".stationNameKey())
    }
}
