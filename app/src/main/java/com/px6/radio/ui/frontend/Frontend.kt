package com.px6.radio.ui.frontend

import androidx.compose.runtime.Composable
import com.px6.radio.model.Band
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.ViewMode

/**
 * Everything a user interface may ask the radio to do — the complete command half of the contract
 * between backend and frontend.
 *
 * The backend (tuners, service following, cluster, persistence) lives behind this interface and
 * [RadioUiState]. A frontend receives exactly these two things and has no other way into the app,
 * which is what makes swapping the interface cheap and keeps the engine testable without any UI.
 */
interface RadioActions {
    fun selectBand(band: Band)
    fun selectStation(station: Station)
    fun next()
    fun prev()
    fun tunePreset(index: Int)
    fun assignPreset(index: Int)
    fun scanDab()
    fun scanFm()

    /** Clear one station button, or all of them when [index] is null. */
    fun clearPreset(index: Int?)

    /**
     * Freeze the currently displayed name of [station] so a long scrolling name stops changing;
     * calling it again releases it.
     */
    fun toggleFixedName(station: Station)

    /** Switch what the lower half shows (station buttons, station info, radio text, slideshow). */
    fun setViewMode(mode: ViewMode)
    /** Open the station list (e.g. from the hardware "list" key). Default no-op for test fakes. */
    fun openStationList() {}

    /** Order the station list alphabetically or by group. */
    fun setSortMode(mode: SortMode)

    /** One tuning step up or down — the arrow tapped briefly. */
    fun tuneStep(up: Boolean)

    /** Seek to the next receivable station — the arrow held down. */
    fun seekStation(up: Boolean)

    /** Jump straight to a frequency in kHz — the slider dragged along the band. */
    fun tuneFrequency(khz: Int)

    /** Fetch station logos (user-triggered — it uses the network). */
    fun downloadLogos()

    /** Throw the stored logos away. */
    fun clearLogos()

    /** Manually refresh RadioDNS data (re-harvest RadioVIS bearers + logos). No-op by default. */
    fun refreshRadioDns() {}

    /** Internet radio (band IP): search radio-browser (results land in [RadioUiState.internetResults]),
     *  add a found station to the list, or remove one the user no longer wants. Default no-ops so
     *  preview/test stubs of this interface need not implement them. */
    fun searchInternet(query: String) {}
    fun addInternetStation(station: Station) {}
    fun removeInternetStation(stationId: String) {}

    /** Add an internet station by hand from a name + stream URL (settings → Internet-Radio). */
    fun addManualStream(name: String, url: String) {}
    fun openSettings()
    fun closeSettings()
    fun updateSettings(block: (Settings) -> Settings)
    fun resetBackends()
    fun dismissErrors()
    /** Dismiss the active ASA/EWS alert overlay by hand (§7.6.4). Default no-op for test fakes. */
    fun dismissEwsAlert() {}

    /** FM → DAB offer modal ("same station on DAB+"): switch / dismiss / never again for this station. */
    fun acceptDabOffer()
    fun declineDabOffer()
    fun ignoreDabOffer()

    /** Hardware "back" on the main screen: ask before quitting (a running radio shouldn't die on a
     *  stray back press). */
    fun requestExit()
    /** Confirmed quit: tear down every backend + resource and finish the app. */
    fun confirmExit()
    fun cancelExit()
}

/**
 * A complete radio interface.
 *
 * Frontends are compiled into the app rather than loaded at runtime: a head unit in a moving car
 * must not execute third-party code, and Android cannot sandbox loaded code well enough to make
 * that safe. Visual variation at runtime is the job of skins, which are pure data — a frontend is
 * for structurally different interfaces, a skin for a different look on one.
 *
 * A frontend must render [RadioUiState] and drive [RadioActions]; how it arranges anything is
 * entirely its own business.
 */
interface RadioFrontend {
    /** Stable identifier, persisted in the settings. */
    val id: String

    /** Name shown in the settings (string resource, so it follows the app language). */
    @get:androidx.annotation.StringRes
    val nameRes: Int

    /** One line describing the arrangement, shown under the name (string resource). */
    @get:androidx.annotation.StringRes
    val descriptionRes: Int

    @Composable
    fun Content(state: RadioUiState, actions: RadioActions)
}

/**
 * The frontends built into this build. The first entry is the fallback whenever the persisted
 * choice names a frontend that no longer exists.
 */
object Frontends {

    private val registry = mutableListOf<RadioFrontend>()

    fun register(frontend: RadioFrontend) {
        if (registry.none { it.id == frontend.id }) registry += frontend
    }

    fun all(): List<RadioFrontend> = registry.toList()

    fun byId(id: String?): RadioFrontend =
        registry.firstOrNull { it.id == id }
            // firstOrNull, not first: an empty registry would otherwise throw here, and this is the
            // one call every screen goes through — the whole UI would fail to build rather than fall
            // back. The registry is a fixed list today, so this is insurance, not a live bug.
            ?: registry.firstOrNull() ?: TilesFrontend
}
