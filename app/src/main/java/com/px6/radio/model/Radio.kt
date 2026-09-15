package com.px6.radio.model

/** Band / source of a station in the unified list. IP = internet radio (an ordinary band, but with
 *  no service-following — the user picks the stream directly; the switch still goes through the same
 *  AudioRouter mutex as DAB/FM so paths never overlap). */
enum class Band { DAB, FM, AM, IP }

/**
 * One entry in the unified FM+DAB station list.
 *
 * Mirrors what OMRI gives us: DAB services carry ensemble/bitrate, FM services carry
 * frequency + RDS-PI. The [linkedFm*] fields model the FIG 0/6 + 0/21 service link that
 * drives DAB->FM following (getFollowingServices() -> RadioServiceFm.getFrequency()/getRdsPiCode()).
 */
data class Station(
    val id: String,
    val name: String,
    val band: Band,
    val subtitle: String,
    val logoInitials: String,
    val logoStart: Long,
    val logoEnd: Long,
    // DAB
    val ensemble: String? = null,
    /** Extended Country Code (FIG 0/9). With the SId's country nibble it forms the RadioDNS gcc,
     *  so logo/stream lookup works in any country — no hardcoded "de0". Null/0xFF = unknown. */
    val ecc: Int? = null,
    val bitrateKbps: Int? = null,
    // FM
    val frequencyKhz: Int? = null,
    val piCode: Int? = null,
    // IP (internet radio): the stream URL to hand to the IpPlayer, and the station homepage
    // (used to fetch an official logo via its declared site icon, as we do for FM/DAB).
    val streamUrl: String? = null,
    val homepage: String? = null,
    /**
     * RadioDNS bearer in slash form (`fm/<gcc>/<pi>/<freq5>` or `dab/<gcc>/<eid>/<sid>/<scids>`),
     * e.g. BBC Radio 1 `fm/ce1/c201/09880`. Present for IP stations that are also broadcast and run
     * a RadioVIS server — lets us pull now-playing text + slideshow for streams with no in-band ICY.
     */
    val radioDnsBearer: String? = null,
    // DAB->FM link (service following)
    val linkedFmFrequencyKhz: Int? = null,
    val linkedFmPi: Int? = null,
    /**
     * Labels of secondary audio components carried by this DAB service — the "Zusatzsender" the
     * original marks with a symbol (e.g. an extra sports channel during an event). Empty for a
     * plain service, and for FM/AM.
     */
    val secondaryLabels: List<String> = emptyList(),
)

/** Now-playing metadata: DLS/DL+ text, slideshow (SLS/MOT), station. */
data class NowPlaying(
    val station: Station,
    val bandLine: String,
    val dlsText: String? = null,
    val dlTitle: String? = null,
    val dlArtist: String? = null,
    val slideshowCaption: String? = null,
    val hasSlideshow: Boolean = false,
    /** Raw slideshow image bytes (SLS/MOT, JPEG/PNG) from DAB, decoded in the UI. */
    val slideshowImage: ByteArray? = null,
)

/** DAB primary vs. FM fallback (service following). */
enum class FollowingState { DAB_PRIMARY, FM_FALLBACK, IP_FALLBACK }

/**
 * What the `<` `>` arrows step through. One setting for every band, as in the original.
 */
enum class StepMode {
    /** Only stations stored on a station button. */
    STORED,

    /** Every receivable station of the current band. */
    RECEIVABLE,
}

/** A preset slot holds a station (or is empty). Tap to tune, long-press to assign. */
data class PresetSlot(val index: Int, val stationId: String?)

/**
 * What the lower half of the tile interface shows — the original calls this "Ansicht" and offers
 * it in DAB mode, where there is a slideshow to show.
 */
enum class ViewMode {
    /** Station buttons. */
    PRESETS,

    /** Radio text and slideshow together, instead of the station buttons. */
    STATION_INFO,

    /** Radio text only. */
    RADIO_TEXT,

    /** Slideshow across the whole screen. */
    SLIDESHOW,
}

/** How the station list is ordered. */
enum class SortMode { ALPHABET, GROUP }

/** Which top-level screen is showing. */
enum class Screen { RADIO, SETTINGS }

/** How the light/dark appearance is chosen. */
enum class ThemeMode { DARK, LIGHT, TIME, HEADLIGHT }

/** User-configurable behaviour. Later persisted (DataStore) and read by the engines. */
/**
 * Which fallback DAB reaches for first when its own signal fails. FM is free, instant and needs no
 * data; the internet stream stays digital but costs mobile data and takes a moment to start. Which
 * of those matters more is the driver's call, not ours — DAB itself stays the anchor either way.
 */
enum class FallbackOrder { FM_FIRST, IP_FIRST }

data class Settings(
    val preferDab: Boolean = true,
    val serviceFollowing: Boolean = true,
    val handoverThreshold: Int = 2,      // switch to FM below this many signal bars (1..4)
    val softFade: Boolean = true,        // fade audio on DAB<->FM handover
    /**
     * Write diagnostics files (klarwelle-*.txt) to the app directory and any USB stick. Off by
     * default: they contain GPS-derived location codes and listening history — a movement profile.
     */
    val diagnostics: Boolean = false,
    val steeringWheelKeys: Boolean = true,
    /** What the stepping arrows walk through — stored stations or all receivable ones. */
    val stepMode: StepMode = StepMode.RECEIVABLE,
    /** Follow a service to another ensemble while staying on DAB (separate from DAB->FM). */
    val dabDabFollowing: Boolean = true,
    /** Assign a stored logo automatically when a station is put on a station button. */
    val autoAssignLogos: Boolean = true,
    val fmRegion: String = "Europa",
    /** Appearance: fixed dark/light, by time of day, or follow the car's headlights. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Which interface arrangement to render (see `Frontends`). */
    val frontendId: String = "tiles",
    /**
     * Skins used for the dark and the light appearance. Keeping both means the automatic
     * switching by time or headlights keeps working even with custom skins.
     */
    val skinDarkId: String = "modern-dark",
    val skinLightId: String = "modern-light",
    /** Accent colour id (see `AccentColor`); overrides whatever the skin brings. */
    val accentId: String = "red",
    /** Show a signal-strength meter. Off by default — the original has none. */
    val showSignalStrength: Boolean = false,
    /**
     * Master switch for RadioDNS: official broadcaster logos + the IP simulcast addresses for the
     * DAB->FM->IP fallback. Needs internet and uses a little data, so it can be turned off on a
     * limited data plan. On by default — RadioDNS is an open, free standard used as intended (a
     * hybrid-radio client), the same way omri and other DAB apps use it; attribution/notice lives in
     * the licence screen, so no consent gate is needed.
     */
    val radioDnsEnabled: Boolean = true,
    /** Live now-playing text + slideshow over RadioDNS/RadioVIS for streams with no in-band ICY
     *  (e.g. BBC). Requires [radioDnsEnabled]. */
    val radioVisEnabled: Boolean = true,
    /** Also fetch the RadioVIS slideshow images (not just the text). Off = text only, saves data. */
    val radioVisSlideshow: Boolean = true,
    /** Allow the internet stream to take over when both DAB and FM are too weak (DAB->FM->IP). */
    /** Which fallback DAB reaches for first — see [FallbackOrder]. */
    val fallbackOrder: FallbackOrder = FallbackOrder.FM_FIRST,
    val ipFallbackEnabled: Boolean = true,
    /** Only use the internet-stream fallback on an unmetered/Wi-Fi connection (data saver). */
    val ipFallbackWifiOnly: Boolean = false,
    /** Pull official RadioDNS logos automatically after a DAB scan / at startup (else manual only). */
    val autoFetchLogos: Boolean = true,
    /**
     * Use the Media Broadcast DAB-Logoservice as a logo source (official DAB logos matched by SId).
     * It costs a ~23 MB download on the manual "Logos laden" run, so it can be turned off if the
     * user prefers RadioDNS/on-air logos only.
     */
    val mediaBroadcastLogos: Boolean = true,
    /** Even out the loudness of internet streams (per-station learned gain via LoudnessEnhancer).
     *  On by default — quiet stations are lifted, loud ones stay natural. See IpPlayer/LoudnessMeter. */
    val normalizeStreamLoudness: Boolean = true,
    /** Key ticks, preset-stored two-tone, following cues, scan-finished chime. */
    val uiSounds: Boolean = false,
    /** Float the "now playing" mini-player over other apps while the radio plays in the background.
     *  Still needs the "draw over other apps" permission; this switch lets the user turn the behaviour
     *  off even when the permission is granted. See MainActivity.onStop / MiniPlayerOverlay. */
    val miniPlayerOverlay: Boolean = true,
    /** DAB Emergency Warning System (ASA, ETSI TS 104 089). Master switch for receiving alerts. */
    val asaEnabled: Boolean = true,
    /** Evaluate Test-stage alerts (the ASA home-test). Off by default — per Table 1 the Test stage
     *  matches Negative for normal receivers; this makes it Positive so the home-test can be tried. */
    val asaTestAlerts: Boolean = false,
    /** Attention signal before an alert is presented. */
    val asaAttentionTone: Boolean = true,
    /** The receiver's 12-digit DAB location codes (asa.radio), for alert geo-matching. EMPTY by
     *  default and user-entered — never a shipped preset (they identify home addresses). Without any
     *  of them only whole-ensemble alerts (no location codes) are evaluated (§7.2.3).
     *
     *  A LIST, because a car radio has two legitimate interests at once: the area it is driving
     *  through, and the area it came from (home, family). An alert plays if it covers any of them —
     *  widening the receiver's area can add an alert but never suppress one. */
    val asaLocationCodes: List<String> = emptyList(),
    /** Also derive a location code from GPS and match against it, so the alert area follows the car
     *  (ETSI TS 104 089 annex F). Independent of the fixed codes above — both apply together. */
    val asaFollowGps: Boolean = false,
)

/**
 * Whether the ASA/EWS function can currently receive alerts (ETSI TS 104 089 §5.3 / §7.2.1: the user
 * must be told when a service selection makes EWS inoperable).
 */
enum class AsaStatus {
    /** ASA switched off in settings — nothing shown. */
    OFF,
    /** Monitoring an EWS ensemble — a FIG 0/15 heartbeat arrived very recently. Alerts can be received. */
    ACTIVE,
    /** We were receiving EWS heartbeats but they have briefly lapsed (a reception dip): still within
     *  the grace window before the ensemble is declared non-EWS (§7.2.2.3). Shown amber. */
    DEGRADED,
    /** ASA on, but the current selection can't receive alerts — FM/AM/internet, or a DAB ensemble
     *  that carries no FIG 0/15 (heartbeat gone for good). The mandatory "EWS inoperable" state. */
    INACTIVE,
}

/**
 * A currently active, matched EWS alert to present to the user (ETSI TS 104 089 §7.6). M2 shows it as
 * an overlay; audio handover is a later milestone. Cleared on the End phase or a safety timeout.
 */
data class EwsAlertUi(
    /** Human stage name, e.g. "Warnung" / "Kritisch" / "Test". */
    val stageName: String,
    val isTest: Boolean,
    val incidentId: Int,
    /** Sub-channel of the alert audio in the tuned ensemble, or -1 for an other-ensemble alert. */
    val subChId: Int,
    val otherEnsemble: Boolean,
    /** Service label of the alert service (§7.6.2 — shall be displayed), or null if not yet known. */
    val serviceLabel: String? = null,
    /** Decoder one-line summary (for the diagnostics-style detail line). */
    val description: String,
    /** The alert's dynamic-label (DLS) text from the alert sub-channel's PAD — the actual broadcast
     *  message — or null if none/not yet received (§7.6.2). Updated live while the alert audio plays. */
    val messageText: String? = null,
    /** The alert's SlideShow image (MOT) from the alert sub-channel, or null. */
    val slideshow: ByteArray? = null,
    /**
     * The broadcast has reached its End phase (§7.6.4): the alert audio is over and playback has
     * already returned to the previous source, but the text stays up so it can still be read. The
     * remaining time is a real, fixed deadline — no further Trigger/Sustain can push it back — so
     * the overlay shows its bar straight away instead of waiting out the "still on air" grace.
     */
    val ended: Boolean = false,
    /** [android.os.SystemClock.elapsedRealtime] when the presentation timeout was last (re)armed, and
     *  how long it runs. The overlay draws a drain bar from these so the user can SEE when the window
     *  will close. Re-armed on every repeated Trigger/Sustain — the overlay uses the gap since the
     *  last re-arm to tell "still being broadcast" (bar hidden) from "counting down now" (bar shown
     *  and draining). 0 = no timeout running. */
    val timeoutArmedAtMs: Long = 0L,
    val timeoutMs: Long = 0L,
)

/** Full UI state for the Golf-7 radio screen. */
data class RadioUiState(
    val clock: String = "14:32",
    val selectedBand: Band = Band.DAB,
    val stations: List<Station> = emptyList(),
    val nowPlaying: NowPlaying? = null,
    val following: FollowingState = FollowingState.DAB_PRIMARY,
    val followingLog: List<String> = emptyList(),
    val signalBars: Int = 4,
    /** ASA/EWS reception status for the status-bar indicator (see [AsaStatus]). */
    val asaStatus: AsaStatus = AsaStatus.OFF,
    /** EIds of ensembles known to carry EWS — for the "ASA" station badge and idle-tuner parking. */
    val ewsEnsembleIds: Set<Int> = emptySet(),
    /** The ensemble the DAB tuner physically sits on — which may be a silently parked EWS-monitoring
     *  ensemble rather than anything audible. Surfaced so the ASA info panel can name it. */
    val currentTunerEnsembleId: Int? = null,
    /** The location code derived from the vehicle position ("Z10:B736BB"), or null when GPS
     *  following is off or there is no fix yet. Display only — matching uses the parsed code. */
    val asaGpsCode: String? = null,
    /** A matched EWS alert currently being presented (overlay), or null. See [EwsAlertUi]. */
    val ewsAlert: EwsAlertUi? = null,
    /**
     * The last few alerts that were presented, newest first, as (clock time, what it said).
     *
     * A live alert always wins the screen — a current emergency outranks one that is over — so a
     * message can be replaced before it has been read. Rather than queue modal windows at someone who
     * is driving, the ones that scrolled past are listed in the ASA info panel, which is a tap away
     * and interrupts nothing. Display only; matching and dismissal are unaffected.
     */
    val asaHistory: List<Pair<String, String>> = emptyList(),
    /** One-shot counter bumped to request opening the station list (e.g. from the hardware list key);
     *  a frontend watches it and switches to its list view. */
    val openListRequest: Int = 0,
    /**
     * Whether the play/pause control says "play". This is the user's INTENT — it says nothing about
     * whether sound is actually coming out; see [ipStreamLive] for the internet source.
     */
    val isPlaying: Boolean = true,
    /**
     * Whether the internet player is really rendering audio right now, straight from ExoPlayer.
     *
     * The status pill needs this and not [isPlaying]: a stream that had dropped and was retrying
     * still showed green while nothing could be heard, and there was no equivalent of the reception
     * signals that DAB and FM feed into the same pill.
     */
    val ipStreamLive: Boolean = false,
    /** The stream could not be played at all — every retry failed (dead URL, no network). Cleared
     *  the moment a stream is started again or audio arrives. */
    val ipStreamFailed: Boolean = false,
    val presets: List<PresetSlot> = emptyList(),
    val screen: Screen = Screen.RADIO,
    val settings: Settings = Settings(),
    /** True until a real DAB tuner delivers services — then we swap demo data for live. */
    val demoMode: Boolean = true,
    val dabPresent: Boolean = false,
    val dabScanning: Boolean = false,
    val scanProgress: Int = 0,
    /** FM tuner (Microntek CarManager) present? If not, this is a pure DAB+ app. */
    val fmAvailable: Boolean = false,
    val fmSeeking: Boolean = false,
    /** Outside temperature from the vehicle CAN (null if the car profile doesn't send it). */
    val outsideTemp: String? = null,
    /** Effective appearance after applying [Settings.themeMode]. */
    val darkActive: Boolean = true,
    /** Car headlights on (null = unknown/unavailable). */
    val headlightOn: Boolean? = null,
    /** Backend failures/crashes surfaced to the user (safe-mode diagnostics). */
    val backendErrors: List<String> = emptyList(),
    /**
     * Muted because neither the DAB service nor a linked FM station is receivable. The original
     * does exactly this rather than playing noise.
     */
    val mutedNoReception: Boolean = false,
    /** No DAB coverage here at all — shown as a hint next to the band. */
    val noDabCoverage: Boolean = false,
    /**
     * On the DAB->FM fallback but that FM is unreceivable here (no RDS-PI lock AND mono = static, not
     * a legit no-RDS station). Surfaced as "kein Empfang" in the header instead of a green pill.
     */
    val fmFallbackWeak: Boolean = false,
    /**
     * A station the user just selected that is still tuning/buffering and not yet audible — the UI
     * marks it immediately and shows a loader over its logo until the audio actually starts (mainly
     * the slow IP→DAB / cross-ensemble-DAB switches). Null once audible.
     */
    val tuningStationId: String? = null,
    /** On FM with a strongly-received DAB+ version of the same station → offer to switch (modal). */
    val dabOffer: Station? = null,
    /** Hardware-back on the main screen raised the "quit the radio?" confirmation modal. */
    val exitConfirm: Boolean = false,
    /** Station names frozen by the user (station id -> the text at the moment of freezing). */
    val fixedNames: Map<String, String> = emptyMap(),
    /** What the lower half shows (DAB only, as in the original). */
    val viewMode: ViewMode = ViewMode.PRESETS,
    /** Order of the station list. */
    val sortMode: SortMode = SortMode.GROUP,
    /** Frequency last dialled in by hand, so the band display follows the slider immediately. */
    val manualFrequencyKhz: Int? = null,
    /** Last station heard on each band, so switching band returns to it. */
    val lastStationPerBand: Map<Band, String> = emptyMap(),
    /** Station-logo cache: how many are stored, and what the last download did. */
    val logoCount: Int = 0,
    val logoDownloading: Boolean = false,
    val logoStatus: String? = null,
    /** FM/AM field strength as the box reports it (raw scale, -1 = unknown). */
    val fmSignal: Int = -1,
    /**
     * Station ids for which a RadioDNS IP simulcast stream was discovered. Lets the UI show an
     * "Internet-Fallback bereit" pill so the found streams are visible as the third following tier,
     * not hidden in the engine.
     */
    val ipStreamStationIds: Set<String> = emptySet(),
    /** Live radio-browser search for internet stations: in-flight flag + the last result set (each a
     *  ready-to-add [Station] with band=IP and a streamUrl). Empty when no search is active. */
    val internetSearching: Boolean = false,
    val internetResults: List<Station> = emptyList(),
    /** Place-search results for the ASA location-code helper (settings only). */
    val placeSearching: Boolean = false,
    val placeResults: List<com.px6.radio.ews.Place> = emptyList(),
    /**
     * False until the persisted settings have been read once. The UI stays blank (themed background
     * only) until then, so the first *visible* frame already uses the saved skin — otherwise the
     * tiles render with the default skin's aspect and visibly jump size when the real skin loads
     * ("Kacheln starten groß, werden nach der Persistenz klein").
     */
    val settingsLoaded: Boolean = false,
) {
    /**
     * The bands offered as tabs — a band that does not exist on this box is not shown:
     *  - **DAB+** only with a detected stick, a scanned/persisted DAB list, or demo mode. No stick ⇒
     *    no DAB tab and no DAB default (the ViewModel reconciles the selected band to the first real
     *    one — Internet on a box without a tuner/stick).
     *  - **FM/AM** only with the Microntek FM tuner ([fmAvailable]).
     *  - **Internet** always — it needs no hardware, only the network.
     */
    val availableBands: List<Band>
        get() = buildList {
            if (dabPresent || demoMode || stations.any { it.band == Band.DAB }) add(Band.DAB)
            if (fmAvailable) { add(Band.FM); add(Band.AM) }
            add(Band.IP)
        }.ifEmpty { listOf(Band.IP) }

    val visibleStations: List<Station>
        get() {
            // The list shows the selected band (DAB+ / FM / AM); the band tabs pick which one.
            val base = stations.filter { it.band == selectedBand }
            // Defensive against duplicate ids reaching a LazyColumn key (crash-hardening; the
            // real dedup is in DabController, this is the belt to that suspenders).
            val unique = base.distinctBy { it.id }
            return when (sortMode) {
                SortMode.ALPHABET -> unique.sortedBy { it.name.lowercase() }
                // "By group" means by ensemble for DAB and by frequency for FM/AM — the order in
                // which the stations actually sit on the band.
                SortMode.GROUP -> unique.sortedWith(
                    compareBy({ it.ensemble ?: "" }, { it.frequencyKhz ?: 0 }, { it.name.lowercase() })
                )
            }
        }

    fun station(id: String?): Station? = id?.let { sid -> stations.firstOrNull { it.id == sid } }

    /** Stations the `<` `>` arrows walk through, per [Settings.stepMode]. */
    val steppableStations: List<Station>
        get() = when (settings.stepMode) {
            StepMode.STORED -> presets.mapNotNull { slot -> station(slot.stationId) }
            StepMode.RECEIVABLE -> stations.filter { it.band == selectedBand }
        }

    /**
     * What to show as the station name: a frozen name wins over the broadcast one, and a station
     * being followed onto FM is marked, as the original does with "(FM)".
     */
    fun displayName(station: Station): String {
        val base = fixedNames[station.id] ?: station.name
        return if (following == FollowingState.FM_FALLBACK &&
            station.id == nowPlaying?.station?.id
        ) "$base (FM)" else base
    }
}
