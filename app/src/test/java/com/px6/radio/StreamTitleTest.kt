package com.px6.radio

import com.px6.radio.audio.IpPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What broadcasters actually put in the ICY `StreamTitle` field. Every string below was captured
 * live from the station named in the comment — not invented — because the failure this guards was
 * exactly that: metadata that looks like markup reached the now-playing line verbatim.
 */
class StreamTitleTest {

    private fun clean(s: String?) = IpPlayer.sanitizeTitle(s)

    /** The reported bug: Z100 during an ad break. Only scheduling markers, no programme at all. */
    @Test fun z100AdBreakShowsNothing() {
        assertNull(clean(""" - text="Spot Block End" amgTrackId="9876543" length="00:00:00""""))
        assertNull(clean("""text="Spot Block Start" song_spot="F""""))
    }

    /** Z100 with a real song: the human part stands before the attributes and is the whole title. */
    @Test fun z100SongKeepsOnlyTheHumanPart() {
        assertEquals(
            "Sabrina Carpenter - Manchild",
            clean("""Sabrina Carpenter - Manchild - text="Manchild" song_spot="T" MediaBaseId="0""""),
        )
    }

    /** An advertiser's name, which is all iHeart sends for a spot, is still better than nothing. */
    @Test fun namedSpotFallsBackToItsText() {
        assertEquals("Famous Footwear",
            clean("""text="Famous Footwear" song_spot="T" MediaBaseId="0""""))
    }

    /** The structured artist/title pair some Shoutcast servers send instead of a plain string. */
    @Test fun structuredArtistTitlePair() {
        assertEquals("""Doechii — Booty Drop""",
            clean("""artist="Doechii" title="Booty Drop" duration="180""""))
    }

    /** Plain titles are the common case and must pass through untouched. */
    @Test fun plainTitlesSurvive() {
        assertEquals("TLC - No Scrubs", clean("TLC - No Scrubs"))
        assertEquals("Die Reportage", clean("  Die Reportage  "))
    }

    /** Markup and JSON payloads must never surface as a song title. */
    @Test fun markupIsRejected() {
        assertNull(clean("""<?xml version="1.0"?><song>x</song>"""))
        assertNull(clean("""{"title":"x"}"""))
        assertNull(clean("<br>"))
        assertNull(clean(""))
        assertNull(clean(null))
    }
}
