package com.px6.radio.data

import com.px6.radio.model.Band
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Station

/**
 * Placeholder data reproducing the Golf-7 mockup (mockups/golf7-radio.html, Screen 1).
 *
 * This is the seam where the real backend plugs in later:
 *   - DAB stations  <- omri-usb (RadioServiceDab, ensemble/bitrate, DLS/DL+, slideshow)
 *   - FM stations   <- Microntek CarManager (ctl_radio_*, RDS PI/PS/RT)
 *   - following link <- RadioImpl.getFollowingServices() (FIG 0/6 + 0/21)
 */
object FakeRadioRepository {

    // Real Deutschlandradio DAB identities (EId 0x10BC Bundesmux · SId), so a debug build on the
    // emulator (which has internet but no DAB stick) can exercise the *real* RadioDNS logo fetch
    // and the *real* IP simulcast — validated live, see the project memory `radiodns-viable-validated`.
    private val dlf = Station(
        id = "10bc.d210",
        name = "Deutschlandfunk",
        band = Band.DAB,
        subtitle = "läuft · DAB+ 104 kbit/s",
        logoInitials = "DF",
        logoStart = 0xFFF2A33C, logoEnd = 0xFFB06A12,
        ensemble = "Bundesmux", bitrateKbps = 104,
        linkedFmFrequencyKhz = 98800, linkedFmPi = 0xD210,
    )

    private val stations = listOf(
        dlf,
        Station("10bc.d220", "Deutschlandfunk Kultur", Band.DAB, "Ensemble Bundesmux", "DK",
            0xFF37C7F2, 0xFF1665A3, ensemble = "Bundesmux", bitrateKbps = 104),
        Station("10bc.d230", "Deutschlandfunk Nova", Band.DAB, "Ensemble Bundesmux", "DN",
            0xFF25C26A, 0xFF0E7A44, ensemble = "Bundesmux", bitrateKbps = 96),
        Station("koeln", "Radio Köln", Band.FM, "FM · 107.1 MHz · PI 0xD3C1", "R1",
            0xFFFF6B6B, 0xFFA52020, frequencyKhz = 107100, piCode = 0xD3C1),
        Station("bayern3", "Bayern 3", Band.FM, "FM · 97.3 MHz", "BB",
            0xFF9B8CFF, 0xFF5236B5, frequencyKhz = 97300),
    )

    private val nowPlaying = NowPlaying(
        station = dlf,
        bandLine = "DAB+ · Ensemble Bundesmux · 104 kbit/s AAC+",
        dlsText = "Deutschlandfunk — Nachrichten",
        dlTitle = "Nachrichten",
        dlArtist = "Deutschlandfunk",
        slideshowCaption = "Slideshow",
        hasSlideshow = false,
    )

    fun initialState() = RadioUiState(
        clock = "14:32",
        selectedBand = Band.DAB,
        stations = stations,
        nowPlaying = nowPlaying,
        signalBars = 4,
        isPlaying = true,
        // Demo simulates a fully equipped head unit; the real controllers override these on a
        // live device (false on non-Microntek hardware -> pure DAB+, no cluster).
        fmAvailable = true,
        // 18 station buttons in three groups of six; the demo fills the first four.
        presets = (1..18).map { i ->
            PresetSlot(i, listOf("10bc.d210", "10bc.d220", "10bc.d230", "koeln").getOrNull(i - 1))
        },
    )
}
