package com.px6.radio.ews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance tests for [EwsMatcher] using the worked examples in ETSI TS 104 089 (annex A.3 and the
 * §7.5.4 location-match example). These vectors come straight from the standard, so passing them is
 * the correctness proof for the receiver location handling.
 */
class EwsMatcherTest {

    // Annex A.3 EXAMPLE 1: BBC Broadcasting House, presentation 2366-7443-8484 → Z10:B736BB.
    @Test fun receiverCode_bbcBroadcastingHouse() {
        val c = EwsMatcher.parseReceiverCode("2366-7443-8484")
        requireNotNull(c)
        assertEquals(10, c.zone)
        // B736BB = digits B,7,3,6,B,B
        assertEquals(listOf(0xB, 0x7, 0x3, 0x6, 0xB, 0xB), c.digits)
    }

    // Annex A.3 EXAMPLE 2: Svalbard Museum, presentation 1116-3388-7268 → Z0:152FF1.
    @Test fun receiverCode_svalbardMuseum() {
        val c = EwsMatcher.parseReceiverCode("1116-3388-7268")
        requireNotNull(c)
        assertEquals(0, c.zone)
        assertEquals(listOf(0x1, 0x5, 0x2, 0xF, 0xF, 0x1), c.digits)
    }

    @Test fun receiverCode_acceptsNoHyphens() {
        assertEquals(EwsMatcher.parseReceiverCode("2366-7443-8484"),
            EwsMatcher.parseReceiverCode("236674438484"))
    }

    @Test fun receiverCode_rejectsBadChecksum() {
        // Flip the last symbol → checksum must fail.
        assertNull(EwsMatcher.parseReceiverCode("2366-7443-8485"))
    }

    @Test fun receiverCode_rejectsWrongLength() {
        assertNull(EwsMatcher.parseReceiverCode("2366-7443-848"))
        assertNull(EwsMatcher.parseReceiverCode(""))
    }

    // §7.5.4 EXAMPLE: alert set Z1:91F, Z1:92C, Z1:953, Z1:960; receiver Z1:92CB81 → match on Z1:92C.
    @Test fun locationMatch_specExample() {
        val receiver = EwsMatcher.Code(1, listOf(0x9, 0x2, 0xC, 0xB, 0x8, 0x1))
        val set = listOf("1:91f", "1:92c", "1:953", "1:960")
        // First code (91F) does not match; second (92C) does.
        assertFalse(EwsMatcher.locationMatches(receiver, EwsMatcher.parseAlertCode("1:91f")!!))
        assertTrue(EwsMatcher.locationMatches(receiver, EwsMatcher.parseAlertCode("1:92c")!!))
        // And the overall decision plays (a Level 1 Start alert with this set).
        assertTrue(EwsMatcher.shouldPlay(0, false, set, receiver))
    }

    @Test fun locationMatch_differentZoneFails() {
        val receiver = EwsMatcher.Code(1, listOf(0x9, 0x2, 0xC, 0xB, 0x8, 0x1))
        assertFalse(EwsMatcher.locationMatches(receiver, EwsMatcher.parseAlertCode("2:92c")!!))
    }

    @Test fun locationMatch_outsideAreaFails() {
        val receiver = EwsMatcher.Code(1, listOf(0x9, 0x2, 0xC, 0xB, 0x8, 0x1))
        val set = listOf("1:91f", "1:953", "1:960")   // none is a left-prefix of 92CB81
        assertFalse(EwsMatcher.shouldPlay(0, false, set, receiver))
    }

    @Test fun wholeEnsembleAlert_alwaysPlays() {
        // No location codes → whole-ensemble → plays even without a receiver code (§7.5.4).
        assertTrue(EwsMatcher.shouldPlay(0, false, emptyList(), null))
    }

    @Test fun noReceiverCode_onlyWholeEnsemble() {
        // §7.2.3: without a receiver location code, a located alert must NOT play.
        assertFalse(EwsMatcher.shouldPlay(0, false, listOf("1:92c"), null))
    }

    @Test fun testStage_gatedByToggle() {
        val receiver = EwsMatcher.Code(1, listOf(0x9, 0x2, 0xC, 0xB, 0x8, 0x1))
        // Test stage (7): negative by default, positive when test alerts are enabled.
        assertFalse(EwsMatcher.shouldPlay(EwsMatcher.STAGE_TEST, false, emptyList(), receiver))
        assertTrue(EwsMatcher.shouldPlay(EwsMatcher.STAGE_TEST, true, emptyList(), receiver))
    }

    @Test fun noStatus_neverPlays() {
        assertFalse(EwsMatcher.shouldPlay(EwsMatcher.STAGE_NONE, true, emptyList(), null))
    }
}
