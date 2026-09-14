package com.px6.radio.vm

import com.px6.radio.fm.FmState
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Station
import com.px6.radio.model.TuningProfile

/**
 * A hardware command the reducer wants carried out. Kept as plain data so the state transitions
 * can be tested without any tuner: the ViewModel turns each of these into a real call.
 */
sealed interface AudioCmd {
    /** Route audio to DAB and (re)start the given service. */
    data class PlayDab(val stationId: String) : AudioCmd

    /** Route audio to the analog tuner and, if a frequency is given, tune there. */
    data class PlayAnalog(val tuneKhz: Int?) : AudioCmd

    /** Route audio to the internet-stream player (band IP). No tuner, no following. */
    data class PlayIp(val url: String) : AudioCmd
}

/** The outcome of a state transition: the new state and what to tell the hardware. */
data class Reduction(val state: RadioUiState, val commands: List<AudioCmd> = emptyList())

/**
 * The pure state logic of the radio — every transition that used to live tangled up with the
 * tuners in the ViewModel, now as functions of (state) -> (state, commands). No Android, no
 * coroutines, no hardware, so all of it is unit-tested.
 *
 * The ViewModel stays thin: it calls these, applies the returned state, and runs the commands.
 */
object RadioLogic {

    /**
     * Switch band, choosing which station to land on.
     *
     * The rule, decided with the user:
     *  1. **Follow the programme** — when leaving DAB for FM and the current DAB service links to
     *     an FM station ([linkedFm]), tune that frequency and keep the programme name (WDR 2 DAB →
     *     WDR 2 FM). Unlike service following this is a deliberate move, so it does not spring back.
     *  2. **Per-band memory** — otherwise return to the station last heard on the target band.
     *  3. **Fallback** — first stored preset, else first known station, else a fresh FM/AM entry
     *     at a sensible frequency so there is always something shown and storable.
     *
     * [currentName] is the DAB service name, carried onto the FM entry when following the programme.
     */
    fun selectBand(
        state: RadioUiState,
        band: Band,
        region: String,
        linkedFm: FmLink? = null,
        currentName: String? = null,
    ): Reduction {
        if (state.selectedBand == band) return Reduction(state)
        val base = state.copy(selectedBand = band, manualFrequencyKhz = null)

        val (withStation, station) = resolveTarget(base, band, region, linkedFm, currentName)
            ?: return Reduction(base)   // e.g. DAB with no services yet
        return play(withStation, station)
    }

    /** A DAB→FM service link: the frequency (kHz) and RDS PI of the linked FM station. */
    data class FmLink(val freqKhz: Int, val pi: Int?)

    private fun resolveTarget(
        state: RadioUiState,
        band: Band,
        region: String,
        linkedFm: FmLink?,
        currentName: String?,
    ): Pair<RadioUiState, Station>? {
        // 1. Follow the programme onto FM.
        if (band == Band.FM && linkedFm != null) {
            return ensureAnalogStation(state, Band.FM, linkedFm.freqKhz, currentName, linkedFm.pi)
        }
        // 2. Last station heard on this band.
        state.lastStationPerBand[band]
            ?.let { id -> state.stations.firstOrNull { it.id == id } }
            ?.let { return state to it }
        // 3. Fallbacks.
        return when (band) {
            Band.DAB -> state.stations.firstOrNull { it.band == Band.DAB }?.let { state to it }
            Band.FM, Band.AM -> analogEntryStation(state, band, region)
            Band.IP -> state.stations.firstOrNull { it.band == Band.IP }?.let { state to it }
        }
    }

    /**
     * Tune the analog band to [khz], creating/refreshing the station entry so the display and the
     * preset system have something concrete to point at even without RDS feedback.
     */
    fun tuneAnalog(state: RadioUiState, khz: Int, region: String): Reduction {
        if (state.selectedBand == Band.DAB) return Reduction(state)
        val (withStation, station) = ensureAnalogStation(state, state.selectedBand, khz)
        return play(withStation, station)
    }

    /** Step one raster step on an analog band; on DAB, step through the station list. */
    /**
     * The reverse of service following: given the FM we're on, is there a DAB+ service that links to
     * it (FIG 0/6 PI or 0/21 frequency, stored on the station at scan time)? Returns that DAB station,
     * so we can offer/switch to the same programme in better quality. Pure — the signal check is separate.
     */
    fun findDabForFm(stations: List<Station>, fmKhz: Int, fmPi: Int?): Station? =
        stations.firstOrNull { st ->
            st.band == Band.DAB &&
                (st.linkedFmFrequencyKhz == fmKhz || (fmPi != null && st.linkedFmPi == fmPi))
        }

    /**
     * Fill each DAB station's FM link by German-style **implicit** service linking (ETSI TS 103 176):
     * a DAB service and an FM station are the same programme when the **DAB SId equals the FM RDS PI**
     * (German broadcasters assign SIds to match the FM PI, and don't broadcast FIG 0/6+0/21). This
     * replaces the always-empty omri broadcast link. Secondary match: exact normalised station name,
     * for the moment an FM frequency is known but its PI hasn't been learned from RDS yet.
     *
     * The FM frequency comes from our own analog list — a station's PI arrives via RDS only while it
     * is tuned, so the map fills in as FM is scanned/listened to. Recompute this on every FM (RDS-PI)
     * or DAB change to keep the assignment current; unchanged stations keep their identity (no churn).
     */
    fun linkDabToFm(stations: List<Station>): List<Station> {
        val fm = stations.filter { it.band == Band.FM && (it.frequencyKhz ?: 0) > 0 }
        if (fm.isEmpty()) return stations
        val byPi = HashMap<Int, Station>()
        for (s in fm) s.piCode?.let { byPi.putIfAbsent(it, s) }
        return stations.map { st ->
            if (st.band != Band.DAB) return@map st
            val sid = dabServiceId(st.id)
            // Primary: SId == PI. Secondary: name match tolerant of RDS PS abbreviations.
            val match = sid?.let { byPi[it] } ?: fm.firstOrNull { nameMatches(it.name, st.name) }
            val freq = match?.frequencyKhz ?: return@map st
            val pi = match.piCode ?: sid
            if (st.linkedFmFrequencyKhz == freq && st.linkedFmPi == pi) st
            else st.copy(linkedFmFrequencyKhz = freq, linkedFmPi = pi)
        }
    }

    /** The 16-bit DAB Service Id from a station id formatted "ensembleId.serviceId" (4 hex each). */
    private fun dabServiceId(id: String): Int? = id.substringAfter('.', "").toIntOrNull(16)

    /**
     * Whether an FM RDS name and a DAB label denote the same station, tolerant of the 8-char RDS PS
     * abbreviations: exact after normalisation ("1LIVE"=="1LIVE"), or the same number of words with
     * each FM word a prefix of the DAB word ("ANT UNNA" ↔ "Antenne Unna", "BR KLASSIK" ↔ "BR-Klassik").
     */
    private fun nameMatches(fmName: String, dabName: String): Boolean {
        val na = normalizeName(fmName)
        if (na.length >= 3 && na == normalizeName(dabName)) return true
        val a = tokenizeName(fmName)
        val b = tokenizeName(dabName)
        if (a.isEmpty() || a.size != b.size) return false
        return a.indices.all { i -> b[i].startsWith(a[i]) && (a[i] == b[i] || a[i].length >= 2) }
    }

    /** Lowercase, letters/digits only: "WDR 2" and "wdr2" collapse onto the same key. */
    private fun normalizeName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private fun tokenizeName(name: String): List<String> =
        name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun stepAnalog(state: RadioUiState, up: Boolean, region: String): Reduction {
        val profile = TuningProfile.forBand(state.selectedBand, region) as? TuningProfile.Continuous
            ?: return Reduction(state)
        val current = state.manualFrequencyKhz
            ?: state.nowPlaying?.station?.takeIf { it.band == state.selectedBand }?.frequencyKhz
            ?: profile.minKhz
        var next = current + if (up) profile.stepKhz else -profile.stepKhz
        if (next > profile.maxKhz) next = profile.minKhz
        if (next < profile.minKhz) next = profile.maxKhz
        return tuneAnalog(state, profile.snap(next), region)
    }

    /** Play a station already in the list (or step target). Remembers it as the band's last. */
    fun play(state: RadioUiState, station: Station): Reduction {
        val s = state.copy(
            selectedBand = station.band,
            nowPlaying = nowPlayingFor(state, station),
            manualFrequencyKhz = if (station.band == Band.DAB) state.manualFrequencyKhz
                else station.frequencyKhz,
            lastStationPerBand = state.lastStationPerBand + (station.band to station.id),
        )
        val cmd = when (station.band) {
            Band.DAB -> AudioCmd.PlayDab(station.id)
            // Always carry a frequency so the router's fm.tune() (which re-arms RDS) is never skipped.
            Band.FM, Band.AM -> AudioCmd.PlayAnalog(station.frequencyKhz ?: state.manualFrequencyKhz)
            Band.IP -> AudioCmd.PlayIp(station.streamUrl.orEmpty())
        }
        // An internet station with no URL is a broken entry — nothing to route.
        if (station.band == Band.IP && station.streamUrl.isNullOrBlank()) return Reduction(s)
        return Reduction(s, listOf(cmd))
    }

    /**
     * Merge a fresh batch of live DAB services into the existing list without ever making it
     * shrink — the bug the user saw: during a rescan omri briefly reports only a few services, and
     * replacing the list with that batch emptied it (presets included) until it filled again.
     *
     * So: FM/AM entries are always kept (they never come from omri), existing DAB entries are
     * updated in place and keep their favourite flag, new ones are appended, and none are removed.
     * The order stays stable. An empty batch changes nothing.
     */
    fun mergeDabStations(
        existing: List<Station>,
        liveDab: List<Station>,
    ): List<Station> {
        if (liveDab.isEmpty()) return existing
        val liveById = liveDab.associateBy { it.id }
        val result = ArrayList<Station>(existing.size + liveDab.size)
        val placed = HashSet<String>()

        for (st in existing) {
            if (st.band != Band.DAB) { result += st; continue }   // keep FM/AM untouched
            val live = liveById[st.id]
            result += when {
                live == null -> st                                // no live update: keep the old one
                // Live data replaces the entry, BUT a computed FM link (from PI/name matching) must
                // survive: a live service that declares no native FIG 0/6 link would otherwise wipe
                // the link we derived — leaving "kein FM-Link" the moment DAB data arrives. Native
                // link wins; ours fills the gap so a fallback stays available instead of nothing.
                live.linkedFmFrequencyKhz == null && st.linkedFmFrequencyKhz != null ->
                    live.copy(
                        linkedFmFrequencyKhz = st.linkedFmFrequencyKhz,
                        linkedFmPi = st.linkedFmPi ?: live.linkedFmPi,
                    )
                else -> live
            }
            placed += st.id
        }
        for (st in liveDab) {
            if (st.id !in placed) {
                result += st
                placed += st.id
            }
        }
        return result
    }

    /**
     * Put the current station on preset [index], freeing it from any other preset first so the
     * same station never sits on two buttons.
     */
    fun assignPreset(state: RadioUiState, index: Int): RadioUiState {
        val cur = state.nowPlaying?.station?.id ?: return state
        val mapped = state.presets.map {
            when {
                it.index == index -> it.copy(stationId = cur)
                it.stationId == cur -> it.copy(stationId = null)
                else -> it
            }
        }
        // The slot may not exist yet — on a fresh install the preset list starts empty and only fills
        // as buttons are assigned. Create the target slot instead of silently dropping the assignment
        // (the "long-press stores nothing" bug: mapping over an empty list produced no change).
        val presets = if (mapped.any { it.index == index }) mapped
        else (mapped + PresetSlot(index, cur)).sortedBy { it.index }
        return state.copy(presets = presets)
    }

    /* ---------------------------------------------------------------- helpers */

    /**
     * The station to land on when switching to an analog band: the first stored one, else the
     * first known one, else a fresh entry at a sensible frequency — so there is always something
     * to show and to save.
     */
    private fun analogEntryStation(
        state: RadioUiState,
        band: Band,
        region: String,
    ): Pair<RadioUiState, Station> {
        val storedId = state.presets.mapNotNull { it.stationId }
            .firstOrNull { id -> state.stations.firstOrNull { it.id == id }?.band == band }
        val existing = storedId?.let { id -> state.stations.first { it.id == id } }
            ?: state.stations.firstOrNull { it.band == band }
        if (existing != null) return state to existing

        // Nothing known yet — synthesise one at the last hand-tuned frequency or the band start.
        val profile = TuningProfile.forBand(band, region) as? TuningProfile.Continuous
        val khz = state.manualFrequencyKhz ?: profile?.minKhz ?: 87_500
        return ensureAnalogStation(state, band, khz)
    }

    /**
     * Finds the analog station for [khz], or adds a fresh one, returning it and the new state.
     * [name]/[pi] are used when following a DAB programme onto FM, so the entry reads "WDR 2"
     * rather than a bare frequency.
     */
    private fun ensureAnalogStation(
        state: RadioUiState,
        band: Band,
        khz: Int,
        name: String? = null,
        pi: Int? = null,
    ): Pair<RadioUiState, Station> {
        val id = (if (band == Band.AM) "am." else "fm.") + khz
        val label = if (band == Band.AM) "$khz kHz" else "%.1f MHz".format(khz / 1000.0)
        state.stations.firstOrNull { it.id == id }?.let { existing ->
            // Improve a bare entry with the programme name when we now know it.
            if (name != null && existing.name == existing.subtitle) {
                val better = existing.copy(name = name, piCode = pi ?: existing.piCode)
                return state.copy(stations = state.stations.map { if (it.id == id) better else it }) to better
            }
            return state to existing
        }
        val station = Station(
            id = id,
            name = name ?: label,
            band = band,
            subtitle = label,
            logoInitials = (name ?: if (band == Band.AM) "AM" else "FM")
                .filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "FM" },
            logoStart = 0xFFF2A33CL, logoEnd = 0xFFB86E12L,
            frequencyKhz = khz,
            piCode = pi,
        )
        return state.copy(stations = state.stations + station) to station
    }

    private fun nowPlayingFor(state: RadioUiState, station: Station): NowPlaying {
        state.nowPlaying?.let { if (it.station.id == station.id) return it }
        return when (station.band) {
            Band.DAB -> NowPlaying(
                station = station,
                bandLine = "DAB+ · Ensemble ${station.ensemble ?: "?"} · " +
                    "${station.bitrateKbps ?: 0} kbit/s AAC+",
                dlsText = "Now Playing · DLS",
                hasSlideshow = true,
                slideshowCaption = "Slideshow",
            )
            Band.FM -> NowPlaying(
                station = station,
                bandLine = "FM · %.1f MHz".format((station.frequencyKhz ?: 0) / 1000.0) +
                    (station.piCode?.let { " · RDS-PI 0x%04X".format(it) } ?: ""),
                dlsText = null,
                hasSlideshow = false,
            )
            Band.AM -> NowPlaying(
                station = station,
                bandLine = "AM · ${station.frequencyKhz ?: 0} kHz",
                hasSlideshow = false,
            )
            Band.IP -> NowPlaying(
                station = station,
                bandLine = station.subtitle.ifBlank { "Internet-Radio" },
                dlsText = null,
                hasSlideshow = false,
            )
        }
    }

    private val FM_PALETTE = 0xFFF2A33CL to 0xFFB86E12L

    /** Build the Station for an analog frequency (id keyed by frequency, RDS name/PI folded in). */
    fun buildAnalogStation(band: Band, khz: Int, name: String?, pi: Int?): Station {
        val id = if (band == Band.AM) "am.$khz" else "fm.$khz"
        val freqLabel = if (band == Band.AM) "$khz kHz" else "%.1f MHz".format(khz / 1000.0)
        val display = name?.trim()?.takeIf { it.isNotEmpty() } ?: freqLabel
        val (a, b) = FM_PALETTE
        return Station(
            id = id,
            name = display,
            band = band,
            subtitle = freqLabel + (pi?.let { " · RDS-PI 0x%04X".format(it) } ?: ""),
            logoInitials = display.filter { it.isLetterOrDigit() }.take(2).uppercase()
                .ifBlank { if (band == Band.AM) "AM" else "FM" },
            logoStart = a, logoEnd = b,
            frequencyKhz = khz,
            piCode = pi,
        )
    }

    /**
     * Pure derivation of the FM/AM now-playing readout from a tuner report — the testable core of
     * the ViewModel's `onFm`. Returns the new [NowPlaying], or null to leave now-playing unchanged.
     *
     * Key behaviours (exercised by FmReadoutTest, so they can be verified without a car):
     *  - When the analog band is selected and the tuner reports a real frequency, the station it
     *    sits on becomes now-playing — even if a DAB service was playing before (manual FM tuning).
     *    This is what makes RDS (PS/RT), the PI and the frequency actually appear while tuning.
     *  - The big readout follows the *intended* frequency ([RadioUiState.manualFrequencyKhz], the
     *    manual scale) so a manual step moves it immediately; RDS PS names the station once it locks.
     *  - The DAB->FM fallback keeps its linked-station context and name.
     *  - On DAB (not in FM fallback) it returns null — FM reports don't touch the DAB readout.
     *
     * @param s state already updated with the tuner frequency (manualFrequencyKhz) and field strength.
     */
    fun fmNowPlaying(s: RadioUiState, f: FmState): NowPlaying? {
        val onAnalog = s.selectedBand == Band.FM || s.selectedBand == Band.AM
        val existingNp = s.nowPlaying
        val np = when {
            existingNp?.station?.band == Band.FM || existingNp?.station?.band == Band.AM -> existingNp
            s.following == FollowingState.FM_FALLBACK -> existingNp
            onAnalog && f.freqKhz > 0 && !f.seeking -> {
                val id = if (s.selectedBand == Band.AM) "am.${f.freqKhz}" else "fm.${f.freqKhz}"
                val st = s.stations.firstOrNull { it.id == id }
                    ?: buildAnalogStation(if (s.selectedBand == Band.AM) Band.AM else Band.FM, f.freqKhz, f.ps, f.pi)
                NowPlaying(st, bandLine = "FM · %.1f MHz".format(f.freqKhz / 1000.0))
            }
            else -> existingNp
        }
        val fmActive = s.following == FollowingState.FM_FALLBACK ||
            np?.station?.band == Band.FM || np?.station?.band == Band.AM
        if (!fmActive || np == null) return null

        val freqKhz = if (f.freqKhz > 0) f.freqKhz else np.station.frequencyKhz ?: 0
        val shownKhz = (s.manualFrequencyKhz?.takeIf { it > 0 } ?: freqKhz).takeIf { it > 0 } ?: freqKhz
        val fallback = s.following == FollowingState.FM_FALLBACK
        val name = f.ps?.takeIf { it.isNotBlank() }
            ?: np.station.name.takeIf { fallback }
            ?: "FM %.1f".format(shownKhz / 1000.0)
        return np.copy(
            station = np.station.copy(
                name = name,
                frequencyKhz = shownKhz.takeIf { it > 0 } ?: np.station.frequencyKhz,
                piCode = f.pi ?: np.station.piCode,
            ),
            bandLine = "FM · %.1f MHz".format(shownKhz / 1000.0) +
                (f.pi?.let { " · RDS-PI 0x%04X".format(it) } ?: "") +
                (if (fallback) " · Fallback von DAB+" else ""),
            dlsText = f.rt?.takeIf { it.isNotBlank() } ?: np.dlsText,
            hasSlideshow = false,
        )
    }
}
