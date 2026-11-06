package com.px6.radio.ui

import com.px6.radio.model.PresetSlot
import com.px6.radio.ui.frontend.TilesFrontend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which preset page a station lives on. The pages follow the playing station, because it can
 * change with nobody touching the screen — steering wheel, knob, service following — and the page
 * carrying its button is the one worth showing.
 */
class PresetPageFollowTest {

    private val presets = listOf(
        PresetSlot(1, "a"), PresetSlot(6, "b"),       // first page: buttons 1-6
        PresetSlot(7, "c"), PresetSlot(12, "d"),      // second page: 7-12
        PresetSlot(13, "e"), PresetSlot(18, "f"),     // third page: 13-18
        PresetSlot(3, null),                          // an empty button never matches
    )

    @Test fun `each button belongs to its page`() {
        assertEquals(0, TilesFrontend.presetGroupOf(presets, "a"))
        assertEquals(0, TilesFrontend.presetGroupOf(presets, "b"))
        assertEquals(1, TilesFrontend.presetGroupOf(presets, "c"))
        assertEquals(1, TilesFrontend.presetGroupOf(presets, "d"))
        assertEquals(2, TilesFrontend.presetGroupOf(presets, "e"))
        assertEquals(2, TilesFrontend.presetGroupOf(presets, "f"))
    }

    @Test fun `a station on no button moves no page`() {
        assertNull(TilesFrontend.presetGroupOf(presets, "unknown"))
        assertNull("nothing playing", TilesFrontend.presetGroupOf(presets, null))
        assertNull("an empty button is not a station", TilesFrontend.presetGroupOf(presets, null))
    }

    @Test fun `a button beyond the last page is ignored`() {
        // Presets stored by an older version, or a longer list than the pages show.
        assertNull(TilesFrontend.presetGroupOf(listOf(PresetSlot(19, "x")), "x"))
    }

    @Test fun `the first button wins when a station is stored twice`() {
        val twice = listOf(PresetSlot(2, "a"), PresetSlot(14, "a"))
        assertEquals(0, TilesFrontend.presetGroupOf(twice, "a"))
    }
}
