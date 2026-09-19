package com.px6.radio.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.px6.radio.BuildConfig
import com.px6.radio.R
import com.px6.radio.car.CarInfo
import com.px6.radio.audio.AudioRouter
import com.px6.radio.audio.AudioSource
import com.px6.radio.car.HctCanBus
import com.px6.radio.media.MediaSessionController
import com.px6.radio.dab.DabController
import com.px6.radio.dab.DabState
import com.px6.radio.data.FakeRadioRepository
import com.px6.radio.data.Persisted
import com.px6.radio.data.PersistedAnalog
import com.px6.radio.data.RadioStore
import com.px6.radio.diag.BootGuard
import com.px6.radio.diag.Diag
import com.px6.radio.diag.DiagFile
import com.px6.radio.logo.LogoPack
import com.px6.radio.logo.MediaBroadcastLogos
import com.px6.radio.logo.LogoSource
import com.px6.radio.logo.RadioDnsLogos
import com.px6.radio.logo.LogoStore
import com.px6.radio.net.RadioVisClient
import eu.hradio.core.radiodns.PxRadioDnsLookup
import com.px6.radio.fm.FmController
import com.px6.radio.fm.FmState
import com.px6.radio.fm.SwcKey
import com.px6.radio.following.ServiceFollowingEngine
import com.px6.radio.internet.InternetRadio
import com.px6.radio.ews.EwsAlertEngine
import com.px6.radio.ews.EwsMatcher
import com.px6.radio.model.AsaStatus
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Screen
import com.px6.radio.model.RadioDnsBearer
import com.px6.radio.model.stationNameKey
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.TuningProfile
import com.px6.radio.model.ViewMode
import androidx.compose.ui.graphics.asAndroidBitmap
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.theme.Skin
import com.px6.radio.ui.theme.BuiltInSkins
import com.px6.radio.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the Golf-7 radio screen and owns the backends.
 *
 * Every risky subsystem — omri DAB (native), FM/canbus (ROM code) — is brought up as a
 * **guarded stage** ([BootGuard]): a native crash that Java can't catch is detected on the next
 * launch, that stage is disabled, and the error is shown instead of a crash-loop. Java failures
 * are caught and surfaced the same way. Demo data only appears in debug builds.
 */
class RadioViewModel(app: Application) : AndroidViewModel(app), RadioActions {

    private val appContext = app.applicationContext

    private val _state = MutableStateFlow(
        if (BuildConfig.DEBUG) FakeRadioRepository.initialState()
        else RadioUiState(demoMode = false)
    )
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private val store = RadioStore(appContext)
    private val guard = BootGuard(appContext)
    /** Vehicle-position location code for ASA (annex F). Matched ALONGSIDE the fixed codes, never
     *  instead of them: the car moves, but the user may still want their home area watched. */
    private val gpsLocation = com.px6.radio.ews.GpsLocationCode(appContext)


    /**
     * Selectable looks. Deliberately a fixed, built-in set: skins ship with the app so every
     * combination is one we have actually seen render, and nothing unreviewed reaches the screen
     * of a car.
     */
    /** Station-logo cache; the UI reads bitmaps from it directly. */
    val logoStore = LogoStore(appContext)

    /** The internet-stream player (internet-radio band + the DAB->FM->IP fallback tier), wired into
     *  the AudioRouter. media3/ExoPlayer: it surfaces the stream's track title itself (ICY/ID3) via
     *  [onMetadata], so no separate metadata connection is needed. */
    private val ipPlayer = com.px6.radio.audio.IpPlayer(appContext).apply {
        onMetadata = { title -> applyStreamTitle(title) }
        // The pill shows what is AUDIBLE, so it needs the player's own verdict, not the play/pause
        // intent: a dropped stream retrying in the background must not keep showing green.
        onPlayingChanged = { live ->
            _state.update {
                if (it.ipStreamLive == live && !(live && it.ipStreamFailed)) it
                else it.copy(ipStreamLive = live, ipStreamFailed = it.ipStreamFailed && !live)
            }
        }
        onLoudnessLearned = { url, gain -> rememberIpGain(url, gain) }
        // Mirror IP playback events (play/error/retry/playing) to a file — so a cold-boot stream
        // failure ("selected but silent after restart") is visible on the device without adb.
        onFailed = { url ->
            // Given up: the "tuning…" dots must not keep pulsing on a station that will never play.
            clearTuning(null)
            _state.update { if (it.ipStreamFailed) it else it.copy(ipStreamFailed = true) }
            Diag.write(appContext, DiagFile.IP, "${currentClock()} stream aufgegeben: $url\n", append = true)
        }
        onEvent = { msg ->
            if (msg.startsWith("play ")) _state.update { if (it.ipStreamFailed) it.copy(ipStreamFailed = false) else it }
            runCatching {
                Diag.write(appContext, DiagFile.IP, "${currentClock()} $msg\n", append = true)
            }
            // Mirror stream events into the switch timeline too — so "stream unstable at the start"
            // (buffering/retries) is visible against the handover timing.
            runCatching { com.px6.radio.diag.SwitchTiming.mark(appContext, "ip: $msg") }
            // Stream is audible now → clear the IP "tuning…" loader.
            if (msg.startsWith("playing")) {
                val t = _state.value.tuningStationId
                if (t != null && t.startsWith("ip.")) {
                    _state.update { it.copy(tuningStationId = null) }
                    runCatching { com.px6.radio.diag.SwitchTiming.done(appContext, "IP stream audible") }
                }
            }
        }
    }

    /** Learned per-stream loudness boost (stream URL -> LoudnessEnhancer target in mB; 0 = loud/natural).
     *  Applied before each IP play so a station starts already levelled; refined each play by the meter. */
    @Volatile
    private var ipGains: Map<String, Int> = emptyMap()

    private fun rememberIpGain(url: String, targetMb: Int) {
        if (ipGains[url] == targetMb) return
        ipGains = ipGains + (url to targetMb)
        persistDebounced()
    }

    /** DAB service id -> best IP simulcast URL, discovered via RadioDNS. Fed to the DAB->FM->IP
     *  fallback tier (Stufe 3). Populated by [downloadLogos] and the post-scan auto-fetch. */
    @Volatile
    private var radioDnsStreams: Map<String, String> = emptyMap()

    /**
     * Latest-wins audio-switch queue. A station change publishes UI state instantly, then drops the
     * actual tune here; a single worker ([startAudioWorker]) drains it. Because it is **conflated**,
     * tapping through stations faster than the tuner can retune coalesces to the newest target — the
     * tuner never walks a backlog of stale stations, and only ever runs one switch at a time (so the
     * async decoupling can't cause interleaving/"komische Sachen"). The fair router Mutex guards the
     * remaining concurrency with following/scan grants.
     */
    private val audioCmds =
        kotlinx.coroutines.channels.Channel<AudioCmd>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Tracks the DAB scanning flag so we can fire the RadioDNS auto-fetch on the scan's completion. */
    @Volatile
    private var dabWasScanning = false
    /** EWS-capable ensemble EIds as last read from / written to the store — the guard that keeps
     *  persistence off the per-emission path. See RadioStore.ewsEnsembleIds. */
    private var restoredEwsEnsembleIds: Set<Int> = emptySet()
    @Volatile
    private var radioDnsAutoJob: kotlinx.coroutines.Job? = null

    /** In-flight internet-radio search, cancelled when a new query supersedes it. */
    @Volatile
    private var internetSearchJob: kotlinx.coroutines.Job? = null

    /** Apply the track title the media3 player read from the internet stream (ICY/ID3) to the current
     *  IP station's now-playing line — the equivalent of DLS on DAB. Ignored off the IP band. */
    private fun applyStreamTitle(title: String?) {
        _state.update { s ->
            val np = s.nowPlaying ?: return@update s
            if (np.station.band != Band.IP) return@update s
            s.copy(nowPlaying = np.copy(dlsText = title, dlTitle = title))
        }
    }

    /** May the IP simulcast fallback play right now? Master + IP toggle, and — if "only on WLAN" is
     *  set — only on an unmetered connection. Checked live so toggling WLAN takes effect at once. */
    private fun ipStreamAllowedNow(): Boolean {
        val s = _state.value.settings
        if (!s.radioDnsEnabled || !s.ipFallbackEnabled) return false
        if (!s.ipFallbackWifiOnly) return true
        return !isMeteredConnection()
    }

    private fun isMeteredConnection(): Boolean = runCatching {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /** Ensembles already looked up via RadioDNS this session — makes the auto-fetch "look once"
     *  (no re-query on every scan/startup/tune for an ensemble that has no RadioDNS entry). The
     *  manual button forces a re-check regardless. */
    private val radioDnsTriedEids = java.util.Collections.synchronizedSet(HashSet<Int>())

    private val _skins = MutableStateFlow(BuiltInSkins)
    val skins: StateFlow<List<Skin>> = _skins.asStateFlow()

    // Backends — created lazily inside guarded stages (may be null if disabled/failed).
    private var dab: DabController? = null
    private var fm: FmController? = null
    private var mediaSession: MediaSessionController? = null
    private var hctCanBus: HctCanBus? = null
    private var following: ServiceFollowingEngine? = null
    private var router: AudioRouter? = null
    // Set when a DAB switch left the internet stream PLAYING through DAB acquisition (soft-fade,
    // coming from IP). Cleared by onDab on DAB's first audible frame — where IP is finally faded
    // out, so the two overlap as one crossfade instead of leaving a silent gap. See onDab / PlayDab.
    @Volatile
    private var deferredIpStop = false
    private var carInfo: CarInfo? = null
    // Touched from the scan coroutine (IO), the FM handler thread (onFmScanEnd) and Main
    // (stopFmScanFlag) — @Volatile so a cancel/clear is seen across all three.
    @Volatile private var fmScanJob: kotlinx.coroutines.Job? = null
    /** Completed by the MCU seek-end so the scan ends normally, without cancelling the coroutine. */
    @Volatile private var scanDone: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    // Hard safety cap on stations added per FM scan, so a misbehaving box can never file hundreds.
    @Volatile private var scanHits = 0
    // Last frequency the tuner actually reported. The manual scale is updated only when this
    // CHANGES, so an optimistic value written by tune/step/drag is not clobbered by every unrelated
    // FM emission (RDS/signal) re-reporting the same old frequency.
    @Volatile private var lastFmFreqKhz = 0
    // Debounces the persist triggered by RDS name refreshes during listening (avoids write-thrash).
    @Volatile private var persistDebounce: kotlinx.coroutines.Job? = null
    // Station to auto-play once available after a restart (the last one heard). Cleared once played.
    @Volatile private var pendingAutoplayId: String? = null
    /** When [pendingAutoplayId] was set, so it cannot block band reconciliation for ever. */
    @Volatile private var pendingAutoplaySetAtMs = 0L

    /** True once the user has actively chosen a band or station — after that, band reconciliation
     *  never overrides their choice. Until then the default follows the hardware (DAB with a stick,
     *  else Internet), so a box without a stick opens as a usable internet radio. */
    @Volatile private var userPickedBand = false

    /**
     * Land [RadioUiState.selectedBand] on a band that actually exists. With no DAB stick and no FM
     * tuner the box is a pure internet radio, so the default must be Internet — not the empty DAB tab.
     * Preference: DAB (the app's identity) once its stick is up, otherwise keep a still-valid current
     * band, otherwise the first real band. Skips an explicit user pick and a pending autoplay (which
     * restores the saved station and its band on a configured device).
     */
    private fun reconcileBand() {
        val s = _state.value
        if (s.demoMode) return
        // A pending autoplay defers band reconciliation, because restoring the saved station also
        // restores its band. But the saved station may never turn up — a deleted internet station, a
        // DAB service gone after a rescan — and then this deferral was permanent: the band was never
        // corrected again for the rest of the session. Give it a deadline.
        if (expirePendingAutoplay()) return
        val avail = s.availableBands
        val target = when {
            !userPickedBand && Band.DAB in avail -> Band.DAB
            s.selectedBand in avail -> s.selectedBand
            else -> avail.first()
        }
        if (target == s.selectedBand) return
        apply(RadioLogic.selectBand(s, target, s.settings.fmRegion))
        following?.setUserBand(target == Band.DAB)
    }
    // FM->DAB offer bookkeeping.
    @Volatile private var dabOfferJob: kotlinx.coroutines.Job? = null
    private val dabOfferSuppressed = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var probedFmId: String? = null   // FM station already signal-checked (probe once)
    @Volatile private var noDabCounterpartLogged: String? = null   // logged once per station
    /**
     * The idle DAB tuner has two claimants while FM is audible: the ASA monitor, which parks it on
     * a warning ensemble every 15 s, and the FM->DAB probe, which retunes it to measure a candidate.
     * Neither knew about the other, so the monitor kept re-parking the tuner in the middle of a
     * probe; the probe then read a signal for the wrong ensemble, decided "too weak", and never
     * tried again. This flag makes the probe the short-lived exclusive user.
     */
    @Volatile private var dabProbeInFlight = false
    /** When each FM station was last probed, so a "too weak" verdict is not permanent. */
    private val fmProbedAtMs = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    @Volatile private var dabTunerHome: String? = null // DAB service to retune to after a probe

    /** Only cue a return to DAB after we actually left it, and a scan end after one ran. */
    private var followingSeen = false
    private var scanSeen = false

    init {
        // Debug/demo on the emulator (has internet, but no DAB stick): pull the real RadioDNS logos
        // and IP simulcast URLs for the demo stations, so the logo path + IP fallback can be
        // exercised entirely off-device. No-op in release (demoMode false, real scans drive it).
        if (BuildConfig.DEBUG) viewModelScope.launch(Dispatchers.IO) {
            val dns = RadioDnsLogos.fetch(appContext, _state.value.stations, logoStore, radioDnsTriedEids,
                knownStreams = radioDnsStreams.keys)
            if (dns.streamsByStationId.isNotEmpty()) {
                radioDnsStreams = radioDnsStreams + dns.streamsByStationId
                _state.update { it.copy(ipStreamStationIds = radioDnsStreams.keys.toSet()) }
                persistDebounced()
            }
            if (dns.logosStored > 0) _state.update { it.copy(logoCount = logoStore.count()) }
        }
        // Drains the conflated audio-switch queue for the whole session.
        startAudioWorker()
        // Sound cues for things the driver cannot see: the audio moving to another source and back,
        // and a scan that finished while they looked elsewhere.
        launchCollect {
            _state.map { it.following }.distinctUntilChanged().collect { f ->
                when (f) {
                    FollowingState.FM_FALLBACK, FollowingState.IP_FALLBACK -> cue(com.px6.radio.audio.UiSounds.Cue.FOLLOW_DOWN)
                    FollowingState.DAB_PRIMARY -> if (followingSeen) cue(com.px6.radio.audio.UiSounds.Cue.FOLLOW_UP)
                    else -> {}
                }
                if (f != FollowingState.DAB_PRIMARY) followingSeen = true
            }
        }
        launchCollect {
            _state.map { it.dabScanning }.distinctUntilChanged().collect { scanning ->
                if (!scanning && scanSeen) cue(com.px6.radio.audio.UiSounds.Cue.SCAN_DONE)
                if (scanning) scanSeen = true
            }
        }
        viewModelScope.launch {
            // Clock + appearance ticker — started first so the UI is correct immediately.
            // If the VW profile syncs the vehicle time into the system clock, this is car time.
            launchCollect {
                while (true) {
                    val light = runCatching { fm?.booleanState("headlight") }.getOrNull()
                    _state.update {
                        it.copy(
                            clock = currentClock(),
                            headlightOn = light,
                            darkActive = resolveDark(it.settings.themeMode, light),
                            // Recompute ASA status as heartbeats age (green → amber when a known EWS
                            // ensemble's heartbeat lapses; red when not on an EWS ensemble).
                            asaStatus = dab?.state?.value.let { d ->
                                computeAsaStatus(it.settings, d?.tunerPresent ?: false,
                                    dab?.ewsLastFrameElapsedMs ?: 0L,
                                    d?.currentTunerEnsembleId, d?.ewsEnsembleIds ?: emptySet())
                            },
                        )
                    }
                    delay(EWS_FRESH_MS)
                }
            }
            // Faster ASA-status tick so the green→amber→red heartbeat transition is visible when
            // heartbeats stop (onDab only fires while they arrive). Cheap; runs on an always-powered unit.
            launchCollect {
                while (true) {
                    delay(1_500)
                    if (_state.value.settings.asaEnabled) refreshAsaStatus()
                }
            }
            // Keep the free DAB tuner parked on an EWS ensemble while FM/Internet is audible, even
            // when the app started straight into a stream and DAB was never touched (§7.2.3).
            launchCollect {
                while (true) {
                    delay(EWS_MONITOR_TICK_MS)
                    runCatching { ensureEwsMonitoring() }
                }
            }

            _state.update { it.copy(logoCount = logoStore.count()) }

            // Surface whatever crashed us last time (now disabled).
            guard.disabledStages().forEach {
                addError("„${label(it)}\" ist beim letzten Start abgestürzt und wurde deaktiviert.")
            }

            // Settings first, so the following engine can be configured from them. Read + decode on IO
            // — the persisted store holds hundreds of DAB stations, and parsing that JSON on the main
            // thread was what made the UI "take forever" to appear. Applying the result stays on Main.
            runCatching { withContext(Dispatchers.IO) { store.read() } }.getOrNull().let { p ->
                if (p == null) {
                    // Fresh install: lay down the empty preset slots (applyPersisted does this when
                    // there IS saved data — without it the preset list stays empty and long-press
                    // "store to button" has no slot to write to).
                    _state.update { it.copy(presets = store.presetIndices.map { i -> PresetSlot(i, null) }) }
                    seedInternetStations(); reconcileBand(); persistNow()
                } else applyPersisted(p)
            }
            // Which ensembles carry EWS is knowledge from earlier sessions, not a setting — restore it
            // into the UI right away so the "ASA" station badge is there before the tuner is even up.
            restoredEwsEnsembleIds =
                runCatching { withContext(Dispatchers.IO) { store.ewsEnsembleIds() } }.getOrDefault(emptySet())
            if (restoredEwsEnsembleIds.isNotEmpty()) {
                _state.update { it.copy(ewsEnsembleIds = it.ewsEnsembleIds + restoredEwsEnsembleIds) }
            }

            // The saved skin/scale is now in state -> release the UI. The first frame the user sees
            // renders with the persisted skin, so the tiles never jump size after loading.
            _state.update { it.copy(settingsLoaded = true) }
            syncGpsLocation(_state.value.settings)
            applyDiagnostics(_state.value.settings)

            stage("media") {
                mediaSession = MediaSessionController(
                    context = appContext,
                    onPlay = { if (!_state.value.isPlaying) togglePlay() },
                    // Live radio has no pause — external "stop"/"pause" both stop the audio.
                    onStop = { if (_state.value.isPlaying) togglePlay() },
                    onNext = ::next,
                    onPrev = ::prev,
                )
            }

            // Vehicle broadcasts (outside temperature) — public, no hidden API.
            stage("carinfo") {
                val ci = CarInfo(appContext)
                carInfo = ci
                launchCollect {
                    ci.temperature.collect { t -> _state.update { it.copy(outsideTemp = t) } }
                }
            }

            // CanBus binder FIRST — it is the fallback transport for FM when the ROM blocks
            // hidden-API reflection (CanBusServer.setCanParameters -> CarManager.setParameters,
            // proven on the device: getCanParameters returned the MCU version).
            stage("canbus") {
                hctCanBus = withContext(Dispatchers.IO) { HctCanBus(appContext) }
            }

            stage("fm") {
                // MUST be on a Looper thread (Main): the CarManager constructor creates an
                // internal Handler — building it on a Looper-less IO thread throws. HCT4Radio
                // (the working ROM app) also constructs CarManager on the main thread.
                val sink: ((String) -> Boolean)? =
                    hctCanBus?.let { cb -> { p: String -> cb.setCanParameters(p) } }
                val f = FmController(
                    appContext, onSwc = ::onSwc,
                    onScanHit = ::onFmScanHit, onScanEnd = ::onFmScanEnd,
                    paramSink = sink,
                )
                fm = f
                if (!BuildConfig.DEBUG) _state.update { it.copy(fmAvailable = f.available) }
                // Now that we know whether FM exists, settle the default band (Internet if nothing else).
                reconcileBand()
                // No CarManager at all is simply a device without an FM tuner (a tablet, a phone) —
                // not a problem to show. A CarManager that exists but will not bind IS one: that is
                // a head unit where FM should work.
                if (!f.available) {
                    val line = "FM inaktiv: " + (f.bindError ?: "CarManager nicht gefunden")
                    if (f.carManagerPresent) addError("$line (CarManager IST da)")
                    else Diag.write(appContext, DiagFile.FM, "$line — kein Fahrzeug-Tuner auf diesem Gerät\n", append = true)
                }
                launchCollect { f.state.collect(::onFm) }
                // Ask the MCU to stream vehicle-status frames (temperature etc.) — as the ROM's
                // "Fahrzeug" app does on screen open. A single request only yields ONE frame on this
                // MCU (temperature then never updated — "kommt aber bleibt statisch"), so we re-arm
                // it periodically to keep the status stream flowing.
                launchCollect {
                    while (true) {
                        runCatching { f.requestVehicleData() }
                        delay(20_000)
                    }
                }
            }

            // Audio gate up BEFORE the slow DAB stage, with lazy controller lookups so it picks the
            // DAB/FM controllers up as they appear. Until now it was built inside the DAB stage, so
            // anything queued earlier hit a null router and was dropped without a trace.
            if (router == null) router = AudioRouter.from({ dab }, { fm }, ipPlayer)
            // ...and start the saved station NOW rather than at the very end of start-up. An internet
            // or FM station needs nothing from DAB, yet it used to wait behind the whole DAB stage —
            // including a 30 s wait on the USB permission dialog — so the box stayed silent for half a
            // minute. tryAutoplay is idempotent and refuses a DAB station until the tuner can really
            // play it (onDab retries), so calling it early is safe and the late call below still runs.
            tryAutoplay()

            // Secure USB permission at the APP level before omri touches the stick. If the user
            // denies, we skip DAB cleanly — omri never runs, so there is no native SIGSEGV
            // (opening/scanning a never-opened device). It is simply retried next boot.
            // No stick present → returns true, omri inits as before.
            //
            // Deliberately OUTSIDE stage("dab"): this suspends for up to 30 s waiting for the user to
            // answer the permission dialog, and it is not risky code — but while it ran inside the
            // guarded region, quitting the app during that wait left BootGuard's "dab" breadcrumb on
            // disk. The next start read it as "DAB crashed" and disabled DAB permanently. That is
            // exactly what happened on the device (klarwelle-diag.txt: crashedStage = dab, disabled = [dab],
            // with no crash log and no omri-init entry for the run that supposedly crashed).
            val usbOk = com.px6.radio.dab.DabUsb.ensurePermission(appContext)
            if (!usbOk) {
                android.util.Log.i(TAG, "DAB: USB permission not granted — skipping DAB this session (not disabled)")
            }
            stage("dab") {
                if (usbOk) {
                    val d = DabController(appContext)
                    dab = d
                    // Hand the remembered EWS ensembles over before any state flows: the ASA badge and
                    // the idle-tuner parking are then correct from the first emission, instead of only
                    // after the next ensemble scan (§7.2.3).
                    if (restoredEwsEnsembleIds.isNotEmpty()) d.seedEwsEnsembleIds(restoredEwsEnsembleIds)
                    // The router already exists (built before this stage) and looks its controllers up
                    // lazily, so it picks `d` up by itself. Rebuilding one here would throw away the
                    // live router's `current` — including a deliberate SILENCED — and let the start-up
                    // amp grab in onDab fire again on a radio the user had stopped.
                    launchCollect { d.state.collect(::onDab) }
                    launchCollect { d.ewsEvents.collect(::onEwsAlert) }   // ASA/EWS alert engine
                    // Scanning happens automatically once the tuner reports INITIALIZED (device opened
                    // after permission granted) — scanning a not-yet-opened native device is a SIGSEGV.
                    withContext(Dispatchers.IO) { d.initializeBlocking() }
                }
            }

            // Safety net: the router is normally built inside the DAB stage (so it exists before the
            // first DAB frame). But if BootGuard disabled that stage after a DAB crash, it would never
            // be built — and then FM *and* Internet are silent too (router?.toIp/toAnalog no-op). Build
            // it here regardless; AudioRouter.from is fully null-safe, so DAB-less works fine.
            if (router == null) router = AudioRouter.from({ dab }, { fm }, ipPlayer)

            stage("following") {
                dab?.let { d ->
                    val eng = ServiceFollowingEngine(d, fm, router!!, viewModelScope)
                    following = eng
                    val s = _state.value.settings
                    eng.configure(s.serviceFollowing, s.handoverThreshold, false, s.dabDabFollowing,
                        s.radioDnsEnabled && s.ipFallbackEnabled,
                        ipBeforeFm = s.fallbackOrder == com.px6.radio.model.FallbackOrder.IP_FIRST)
                    // The IP fallback tier needs the RadioDNS-resolved simulcast URL per station —
                    // gated live by the "only on WLAN" data-saver setting.
                    eng.setStreamProvider { id -> if (ipStreamAllowedNow()) radioDnsStreams[id] else null }
                    launchCollect { eng.run() }
                    launchCollect {
                        eng.state.collect { f ->
                            _state.update {
                                it.copy(
                                    following = f.following,
                                    followingLog = f.log,
                                    mutedNoReception = f.muted,
                                    noDabCoverage = f.noDabCoverage,
                                    fmFallbackWeak = f.fmWeak,
                                )
                            }
                            logFollowing(f)
                        }
                    }
                }
            }

            writeDiagnostics()

            // Backends are up and restored FM/AM stations are in the list — resume the last one now.
            // (A last DAB station instead resumes from onDab, once the scan surfaces it.)
            tryAutoplay()

            // Publish now-playing on the media session — for media keys, launcher widgets and
            // whatever else reads the active session (a head unit's cluster page included).
            launchCollect {
                state.collect { s ->
                    s.nowPlaying?.let { np ->
                        val subtitle = when {
                            np.dlArtist != null && np.dlTitle != null -> "${np.dlArtist} – ${np.dlTitle}"
                            !np.dlsText.isNullOrBlank() -> np.dlsText
                            else -> np.bandLine
                        }
                        // Change-guard: only push (and decode artwork) when the shown content changes —
                        // state emits often, decoding the slideshow every time would be wasteful.
                        val slideId = np.slideshowImage?.let { System.identityHashCode(it) } ?: 0
                        val sig = "${np.station.id}|${np.station.name}|$subtitle|${s.isPlaying}|$slideId"
                        if (sig != lastMediaSig) {
                            lastMediaSig = sig
                            mediaSession?.update(np.station.name, subtitle, s.isPlaying, mediaArt(np))
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { sounds.release() }
        // Clear BootGuard's breadcrumb FIRST. Reaching onCleared means we are shutting down in an
        // orderly way — whatever stage was still running was abandoned, not crashed. Without this,
        // quitting during a slow stage (the DAB one waits on the USB permission dialog) left the
        // breadcrumb behind and the next start disabled that stage as if it had crashed. The guard
        // still does its real job: a genuine native crash never reaches this line.
        runCatching { guard.end() }
        runCatching { gpsLocation.stop() }
        // The internet player was missing here: every other backend was torn down, but ExoPlayer was
        // only ever stopped via the router. Swipe the app away from recents (no exit dialog) and the
        // stream kept playing with no UI, and a relaunch built a second player on top of the first —
        // the "Sender läuft doppelt" shape, still open for the IP path after the DAB one was fixed.
        runCatching { ipPlayer.stop(0) }
        runCatching { dab?.release() }
        runCatching { fm?.release() }
        runCatching { mediaSession?.release() }
        runCatching { hctCanBus?.release() }
        runCatching { carInfo?.release() }
        super.onCleared()
    }

    // ---- guarded staging ----

    private suspend fun stage(name: String, block: suspend () -> Unit) {
        if (guard.isDisabled(name)) return
        guard.begin(name)
        try {
            block()
        } catch (t: Throwable) {
            addError("„${label(name)}\" fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
            android.util.Log.e(TAG, "stage '$name' failed", t)
        } finally {
            guard.end()
        }
    }

    /** Interface sounds — see [com.px6.radio.audio.UiSounds]. Gated here by the settings. */
    private val sounds = com.px6.radio.audio.UiSounds(appContext)
    private fun cue(c: com.px6.radio.audio.UiSounds.Cue) {
        if (_state.value.settings.uiSounds) sounds.play(c)
    }

    private fun launchCollect(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "collector failed: ${t.message}")
        }
    }

    private fun addError(msg: String) {
        _state.update { it.copy(backendErrors = (it.backendErrors + msg).distinct()) }
        writeDiagnostics()
    }

    /** Dump backend status to klarwelle-diag.txt on every volume (incl. USB) — no adb needed. */
    private fun writeDiagnostics() {
        val s = _state.value
        val text = buildString {
            append("Klarwelle ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})\n")
            append("ts=${System.currentTimeMillis()}\n\n")
            append("BootGuard.crashedStage(letzter Lauf) = ${guard.crashedStage}\n")
            append("disabled stages = ${guard.disabledStages()}\n\n")
            append("DAB: present=${s.dabPresent} scanning=${s.dabScanning} stations=${s.stations.size}\n")
            // "Stick nicht erkannt" has two very different causes and the diagnosis could not tell
            // them apart: no 16C0:05DC device on the bus at all, versus a device that IS there but
            // whose USB permission we do not hold (the dialog was never answered), which skips the
            // whole DAB stage. Report the USB level directly.
            append(
                com.px6.radio.dab.DabUsb.describe(appContext)?.let {
                    "usb.stick=${it.usbId} permission=${it.hasPermission} " +
                        "product=${it.product ?: "?"} device=${it.deviceName}\n"
                } ?: "usb.stick=NICHT GEFUNDEN (erwartet ${com.px6.radio.dab.DabUsb.expectedUsbId()})\n"
            )
            append("dabStageDisabled=${guard.isDisabled("dab")}\n")
            append("fmAvailable=${s.fmAvailable}\n")
            append("fm.carManagerPresent=${fm?.carManagerPresent} fm.available=${fm?.available}\n")
            append("fm.bindError=${fm?.bindError}\n")
            append("fm.carManagerCtors=${fm?.carManagerCtors}\n")
            append("fm.mode=${fm?.mode}\n")
            // Screen geometry in BOTH units. The layouts are written in dp, but the head unit is
            // specified in pixels — without the density the two cannot be reconciled, and dialogs
            // that looked right on a 1024x600 mdpi emulator came out cut off on the real panel.
            runCatching {
                val m = appContext.resources.displayMetrics
                val c = appContext.resources.configuration
                append("screen=${m.widthPixels}x${m.heightPixels}px density=${m.density} " +
                    "(${c.screenWidthDp}x${c.screenHeightDp}dp) dpi=${m.densityDpi}\n")
            }
            append("hiddenApi=${com.px6.radio.diag.HiddenApi.status}\n")
            append("canbus.available=${hctCanBus?.available}\n\n")
            append("errors:\n")
            s.backendErrors.forEach { append(" - $it\n") }
        }
        Diag.write(appContext, DiagFile.DIAG, text)
    }

    /** Re-enable all backends after a safe-mode disable (needs an app restart to take effect). */
    override fun resetBackends() {
        guard.reset()
        _state.update { it.copy(backendErrors = listOf("Backends zurückgesetzt – App bitte neu starten.")) }
    }

    override fun dismissErrors() {
        _state.update { it.copy(backendErrors = emptyList()) }
    }

    private fun label(name: String): String = when (name) {
        "dab" -> "DAB+ (omri)"
        "fm" -> "FM (CarManager)"
        "media" -> "MediaSession"
        "canbus" -> "CanBus-Binder"
        "following" -> "Service Following"
        else -> name
    }

    // ---- live metadata ----

    /**
     * Build an FM/AM station entry — the single source of truth for the live collector, the band
     * scan and restore-from-store, so all three produce identical entries (id, colours, initials).
     */
    private fun buildAnalogStation(band: Band, khz: Int, name: String?, pi: Int?): Station =
        RadioLogic.buildAnalogStation(band, khz, name, pi)

    /**
     * Adds the frequency the FM tuner has settled on to the station list, or refreshes what we
     * already know about it.
     *
     * This is what "refreshing the FM list" really means: the band is walked and what answers is
     * written down. A station keeps its identity through its frequency, so passing it again later
     * only updates the RDS name rather than creating a duplicate.
     */
    private fun collectFmStation(s: RadioUiState, f: FmState): RadioUiState {
        // Mid-seek readings are meaningless, and a frequency of zero means "no idea yet".
        if (f.seeking || f.freqKhz <= 0) return s
        val band = if (s.selectedBand == Band.AM) Band.AM else Band.FM
        val id = if (band == Band.AM) "am.${f.freqKhz}" else "fm.${f.freqKhz}"
        // Live RDS PS if present, else the remembered name for this frequency (shown instantly on tune,
        // before RDS re-decodes); a real PS that arrives later replaces it.
        val label = f.ps?.trim()?.takeIf { it.isNotEmpty() } ?: rememberedFmName(f.freqKhz)
        val existing = s.stations.firstOrNull { it.id == id }
        if (existing != null) {
            // Only ever improve what we have: an RDS name beats a bare frequency, and RDS text
            // arrives a moment after the tuner locks on.
            val better = existing.copy(
                name = label ?: existing.name,
                piCode = f.pi ?: existing.piCode,
            )
            if (better == existing) return s
            return s.copy(stations = s.stations.map { if (it.id == id) better else it })
        }
        return s.copy(
            stations = s.stations + buildAnalogStation(band, f.freqKhz, label, f.pi),
        )
    }

    /**
     * FM RDS from the MCU (received via CarManager, exactly as HCT4Radio does): station name is the
     * RDS PS, RadioText the DLS equivalent, plus frequency + PI. Drives now-playing whenever FM is
     * the audible source (directly tuned, or as a DAB->FM following fallback).
     */
    private fun onFm(f: FmState) {
        val before = analogSignature(_state.value.stations)
        // A genuine tuner frequency change (seek/tune reaching the MCU) — only then do we move the
        // manual scale, so a manually dialled frequency isn't overwritten by a stale repeat.
        val freqChanged = f.freqKhz > 0 && f.freqKhz != lastFmFreqKhz
        if (f.freqKhz > 0) lastFmFreqKhz = f.freqKhz
        // The MCU sweep reports no percentage, but it walks the band upwards and reports where it
        // is — so the position in the band IS the progress. Never goes backwards within a sweep.
        if (_state.value.fmSeeking && f.freqKhz > 0) {
            if (freqChanged) fmScanLastEventMs = android.os.SystemClock.elapsedRealtime()
            val s = _state.value
            val band = if (s.selectedBand == Band.AM) Band.AM else Band.FM
            val p = TuningProfile.forBand(band, s.settings.fmRegion)
            val span = (p.maxKhz - p.minKhz).coerceAtLeast(1)
            val pct = ((f.freqKhz - p.minKhz) * 100 / span).coerceIn(0, 99)
            if (pct > s.scanProgress) _state.update { it.copy(scanProgress = pct) }
        }
        _state.update { s0 ->
        // FM has no service list to fetch — there are only frequencies. Whatever the tuner lands
        // on gets collected here, so the list fills itself while seeking or scanning the band.
        // BUT NOT during an MCU auto-scan: the box streams a `freq` event for every frequency it
        // sweeps through, which would file hundreds of bogus stations. During a scan the only
        // stations added are the genuine locks reported via onFmScanHit.
        val collected = if (scanDone != null) s0 else collectFmStation(s0, f)
        // Glue the manual scale to the real tuner frequency whenever an analog band is selected,
        // so the < > steps and seeks visibly move the marker — even before any station is playing.
        val onAnalog = collected.selectedBand == Band.FM || collected.selectedBand == Band.AM
        // Follow the tuner frequency on the scale whenever it changes — INCLUDING during a held seek,
        // so the readout visibly spins along and lands on the found station (it used to be frozen
        // while seeking). freqChanged already guards against stale repeats.
        val withFreq = if (onAnalog && freqChanged) {
            collected.copy(manualFrequencyKhz = f.freqKhz)
        } else collected
        // Carry the field strength through when the box reported one.
        val s = if (f.signal >= 0) withFreq.copy(fmSignal = f.signal) else withFreq
        // Derive the FM/AM now-playing readout — pure + unit-tested in RadioLogic.fmNowPlaying so
        // the "RDS/frequency while manually tuning" behaviour is verifiable without a car. Null =
        // this FM report doesn't own now-playing (e.g. on DAB and not in FM fallback).
        RadioLogic.fmNowPlaying(s, f)
            ?.let { s.copy(fmSeeking = f.seeking, nowPlaying = it) }
            ?: s.copy(fmSeeking = f.seeking)
        }
        // A new frequency or an improved RDS name/PI means a saved FM tile must survive a restart.
        // Debounced: RDS PS often flickers through partial names at first, and each change would
        // otherwise rewrite the whole persisted record.
        if (analogSignature(_state.value.stations) != before) {
            // A new/changed FM RDS-PI (or a freshly collected FM station) can create or move a
            // DAB↔FM link — keep the assignment current before persisting.
            refreshDabFmLinks()
            persistDebounced()
        }
        // A new FM frequency invalidates any pending DAB offer / probe for the old station — cancel
        // the running dwell/probe too, else its 30 s job blocks the new station from being probed.
        if (freqChanged) {
            probedFmId = null
            dabOfferJob?.cancel()
            if (_state.value.dabOffer != null) _state.update { it.copy(dabOffer = null) }
        }
        maybeOfferDab()
        // Remember the real RDS PS name for this frequency (HCT4Radio's saveFrequencyPsn) so a later
        // rescan/restart shows it straight away — the station list stays fresh (rescan rebuilds it),
        // only the NAME is remembered, no stale "station corpses".
        if (!f.ps.isNullOrBlank() && f.freqKhz > 0) learnFmName(f.freqKhz, f.ps!!)
        // RDS PS (the real name) is slow on this MCU (~12 s) while the PI arrives fast (~4 s). Resolve
        // the station name from the PI via RadioDNS the moment the PI is known, so a name appears in
        // ~4 s and is remembered per frequency too.
        maybeResolveFmName(f)
    }

    /** The ECC of the country we are in, as reported by the DAB ensembles in the station list. */
    private fun knownEcc(): Int? =
        _state.value.stations.mapNotNull { it.ecc }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** PI→name so we don't hammer RadioDNS: a resolved name is cached and reused for that PI. */
    private val fmNameByPi = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var fmNameLookupPi = 0
    private fun maybeResolveFmName(f: FmState) {
        if (!_state.value.settings.radioDnsEnabled) return
        val pi = f.pi ?: return
        val khz = f.freqKhz.takeIf { it > 0 } ?: return
        // The real RDS PS name is authoritative — once it's here, don't override it with RadioDNS.
        if (!f.ps.isNullOrBlank()) return
        fmNameByPi[pi]?.let { applyFmName(khz, pi, it); return }
        if (fmNameLookupPi == pi) return   // a lookup for this PI is already in flight
        fmNameLookupPi = pi
        viewModelScope.launch(Dispatchers.IO) {
            // The tuner gives us no RDS ECC, so the country comes from the DAB ensembles around us
            // (the ECC every DAB station carries); only with no DAB in the list at all is it Germany.
            val gcc = RadioDnsBearer.fmGcc(pi, knownEcc())
            // RadioDNS FM frequency = units of 10 kHz, 5 digits: 87.8 MHz = 87800 kHz → 8780 → "08780"
            // (verified live: 08780.d392.de0.fm.radiodns.org → dewdr.radiodns.ard.de). Was khz/100.
            val freq5 = "%05d".format(khz / 10)
            val piHex = Integer.toHexString(pi)
            val bearer = "fm/$gcc/$piHex/$freq5"
            // The FM authority (e.g. dewdr.radiodns.ard.de) lists the WHOLE provider's services, so
            // pick the one whose bearer carries THIS PI — not the first (which would be "1LIVE" for
            // every WDR frequency). No PI match → no name (better than a wrong one).
            val name = runCatching { PxRadioDnsLookup.harvestBearers(appContext, bearer) }.getOrNull()
                ?.firstOrNull { svc -> svc.bearer.split("/").any { it.equals(piHex, ignoreCase = true) } }
                ?.names?.firstOrNull { it.isNotBlank() }
            runCatching { Diag.write(appContext, DiagFile.FM,
                "radiodns fm name $bearer -> ${name ?: "none"} (${PxRadioDnsLookup.lastDiag})\n", append = true) }
            if (name != null) fmNameByPi[pi] = name.also { applyFmName(khz, pi, it) }
            // Release the in-flight marker when nothing was found. It used to stay set for the PI,
            // so a lookup that failed because the network was still coming up was never retried
            // while that station stayed tuned — the entry kept its bare frequency for good.
            else if (fmNameLookupPi == pi) fmNameLookupPi = 0
        }
    }

    /** Persistent frequency→name memory, exactly like HCT4Radio's getFrequencyPsn/saveFrequencyPsn:
     *  a learned name (RDS PS or PI→RadioDNS) is remembered per frequency so it survives a rescan
     *  (which wipes the FM list) and a restart — the list stays named without re-learning. */
    @Volatile private var fmNames: MutableMap<Int, String> = java.util.concurrent.ConcurrentHashMap()
    fun learnFmName(khz: Int, name: String) {
        if (khz <= 0 || name.isBlank()) return
        if (fmNames[khz] == name) return
        fmNames[khz] = name
        persistDebounced()
    }
    /** The remembered name for a scanned/tuned FM frequency, if any. */
    fun rememberedFmName(khz: Int): String? = fmNames[khz]

    /** Apply a resolved FM name to the list entry at [khz] while it is still frequency-named (no PS). */
    private fun applyFmName(khz: Int, pi: Int, name: String) {
        learnFmName(khz, name)
        fun needsName(st: Station) = st.band == Band.FM && st.frequencyKhz == khz &&
            (st.name == st.subtitle || st.name.none { it.isLetter() })
        if (_state.value.stations.none(::needsName)) return
        // Apply INSIDE update, against whatever the list is at that moment. This runs on the RadioDNS
        // IO coroutine, so writing back a snapshot taken before the lookup discarded anything Main had
        // merged meanwhile — a finished DAB scan's services simply vanished from the list.
        _state.update { s ->
            s.copy(stations = s.stations.map { st ->
                if (needsName(st)) st.copy(name = name, piCode = st.piCode ?: pi) else st
            })
        }
        refreshDabFmLinks(); persistDebounced()
    }

    /** Signature of the analog (FM/AM) entries — changes only when one is added or its name/PI improves. */
    private fun analogSignature(stations: List<Station>): List<String> =
        stations.filter { it.band != Band.DAB }.map { "${it.id}|${it.name}|${it.piCode}" }

    /** Overlay live DAB state onto the UI state (keeps demo data until services arrive). */
    private fun onDab(d: DabState) {
        d.error?.let { addError("DAB: $it") }
        // omri starts the last DAB service on its own, bypassing the router — so on the very first
        // audible DAB frame (before anything has claimed the amp) grab it for DAB once, or the box
        // keeps an old FM route open and both play at start-up. Gate strictly on `current == null`:
        // once the router has routed anything, following/manual band-switch are authoritative and
        // this must NOT fire — otherwise it rips the amp back to DAB mid-handover (kills FM fallback).
        if (!_state.value.demoMode &&
            _state.value.selectedBand == Band.DAB &&
            d.nowPlayingId != null &&
            router?.current == null &&
            // Don't grab DAB when a saved station is about to be auto-played — tryAutoplay routes the
            // correct band (incl. an internet stream). Grabbing here would race its toIp and win.
            pendingAutoplayId == null
        ) {
            viewModelScope.launch(Dispatchers.IO) { router?.toDab { } }
        }
        // Cross-fade: the DAB service we were tuning is now audible (first frame). If we deferred the
        // internet-stream stop for this switch, fade IP out NOW — it played through the acquisition,
        // so there's no silence gap; IP-out and DAB-in overlap as one smooth crossfade.
        if (deferredIpStop && d.audibleId != null && d.audibleId == _state.value.tuningStationId) {
            deferredIpStop = false
            ipPlayer.stop(CROSSFADE_MS)
            com.px6.radio.diag.SwitchTiming.mark(appContext, "crossfade: DAB audible → fade IP out (${CROSSFADE_MS}ms)")
        }
        _state.update { s ->
        val live = d.stations.isNotEmpty()
        // Merge, never replace: a rescan must not empty the list (and FM/AM entries must survive).
        val stations = if (live) RadioLogic.mergeDabStations(s.stations, d.stations) else s.stations
        val np = if (live && d.nowPlayingId != null) {
            stations.firstOrNull { it.id == d.nowPlayingId }?.let { st -> liveNowPlaying(st, d) }
        } else null
        s.copy(
            stations = stations,
            demoMode = if (live) false else s.demoMode,
            dabPresent = d.tunerPresent,
            dabScanning = d.scanning,
            scanProgress = d.scanProgress,
            scanStartedAtMs = if (d.scanning && !s.dabScanning) android.os.SystemClock.elapsedRealtime() else s.scanStartedAtMs,
            // Clear the "tuning…" loader once the selected DAB service is actually audible.
            tuningStationId = if (d.audibleId != null && d.audibleId == s.tuningStationId) null else s.tuningStationId,
            fmAvailable = if (live && !BuildConfig.DEBUG) (fm?.available == true) else s.fmAvailable,
            signalBars = if (live) d.signalBars else s.signalBars,
            // Only let a background DAB service drive the display when DAB is actually the shown
            // band and we are not in FM fallback — else it clobbers a manually chosen FM station
            // (you'd hear FM but see DAB) or the FM-fallback readout.
            nowPlaying = if (s.selectedBand == Band.DAB && s.following != FollowingState.FM_FALLBACK)
                (np ?: s.nowPlaying) else s.nowPlaying,
            asaStatus = computeAsaStatus(s.settings, d.tunerPresent, dab?.ewsLastFrameElapsedMs ?: 0L,
                d.currentTunerEnsembleId, d.ewsEnsembleIds),
            ewsEnsembleIds = d.ewsEnsembleIds,
            currentTunerEnsembleId = d.currentTunerEnsembleId,
        )
        }
        // Remember newly-learned EWS ensembles across restarts. Only on an actual change — onDab runs
        // on every DAB state emission, and this must not become a write per heartbeat. The empty set a
        // scan starts with is skipped too: it is not knowledge, and persisting it would wipe the badge
        // for the whole scan.
        if (d.ewsEnsembleIds.isNotEmpty() && d.ewsEnsembleIds != restoredEwsEnsembleIds) {
            restoredEwsEnsembleIds = d.ewsEnsembleIds
            viewModelScope.launch { runCatching { store.saveEwsEnsembleIds(d.ewsEnsembleIds) } }
        }

        // A DAB update changed the list — refresh the DAB↔FM assignment for the new services.
        // NOT during a scan: services arrive one at a time, so the list changes on every single
        // callback and the signature guard cannot help. A full band scan finds 200+ of them, which
        // meant 200+ cross products on the main thread — the start-up freeze. One pass at the end
        // (forced below) produces exactly the same result.
        if (!d.scanning) refreshDabFmLinks()
        // A DAB station just appeared — if it's the one we were on last, resume it.
        tryAutoplay()
        // Stick presence may have changed the available bands — settle the default (prefer DAB once
        // its stick is up, unless the user already chose a band).
        reconcileBand()

        // DAB scan just finished (scanning true -> false): auto-fetch official RadioDNS logos +
        // stream URLs for the freshly found services — but only if the user left RadioDNS enabled.
        if (dabWasScanning && !d.scanning) {
            refreshDabFmLinks(force = true)   // the one pass the scan skipped
            autoFetchRadioDnsLogos()
            // A scan re-derives the ensemble landscape, so anything previously written off as "no EWS
            // here" is no longer evidence — let the sweep consider every ensemble again.
            ewsDiscoveryTried.clear()
            parkDabForEwsMonitoring()   // scan just populated EWS-capable ensembles → park if idle
        }
        dabWasScanning = d.scanning

        // While the alert audio (the handed-over service) plays, surface its live DLS + SlideShow as
        // the alert message in the overlay (§7.6.2 — present the PAD of the alert service).
        val svcId = ews.alertServiceId
        if (svcId != null && d.nowPlayingId == svcId) {
            val prevMsg = _state.value.ewsAlert?.messageText
            _state.update { st ->
                val a = st.ewsAlert ?: return@update st
                val msg = d.dls?.takeIf { it.isNotBlank() } ?: a.messageText
                if (msg == a.messageText && d.slideshow === a.slideshow) st
                else st.copy(ewsAlert = a.copy(messageText = msg, slideshow = d.slideshow ?: a.slideshow))
            }
            // Log the actual displayed alert message (DLS) + whether a SlideShow was present, so a
            // test capture on the stick shows WHAT was presented, not just the FIG summary.
            val newMsg = _state.value.ewsAlert?.messageText
            if (newMsg != null && newMsg != prevMsg) {
                runCatching {
                    com.px6.radio.diag.Diag.write(appContext, DiagFile.EWS,
                        "${currentClock()} message: \"$newMsg\"" +
                            (if (d.slideshow != null) " [+SlideShow]" else "") + "\n", append = true)
                }
            }
        }
    }

    /**
     * Quietly pull RadioDNS logos (and IP simulcast URLs) for the current DAB list after a scan.
     * Gated by the RadioDNS setting (data-saver), guarded against overlapping runs, and cheap on
     * repeat: [LogoStore] caches on disk so only stations still missing an official logo are fetched.
     */
    private fun autoFetchRadioDnsLogos() {
        val s = _state.value.settings
        if (!s.radioDnsEnabled || !s.autoFetchLogos) {
            Diag.write(appContext, DiagFile.RADIODNS,
                "auto-fetch übersprungen: radioDnsEnabled=${s.radioDnsEnabled} autoFetchLogos=${s.autoFetchLogos}")
            return
        }
        if (radioDnsAutoJob?.isActive == true) return
        radioDnsAutoJob = viewModelScope.launch(Dispatchers.IO) {
            val stations = _state.value.stations
            val net = hasActiveNetwork()
            val dns = RadioDnsLogos.fetch(appContext, stations, logoStore, radioDnsTriedEids,
                knownStreams = radioDnsStreams.keys)
            if (dns.streamsByStationId.isNotEmpty()) {
                radioDnsStreams = radioDnsStreams + dns.streamsByStationId
                _state.update { it.copy(ipStreamStationIds = radioDnsStreams.keys.toSet()) }
                persistDebounced()
            }
            if (dns.logosStored > 0) _state.update { it.copy(logoCount = logoStore.count()) }
            // Write only when this run actually looked something up. An all-skipped run (every
            // ensemble already tried this session) carries nothing and must not clobber the report
            // of the run that did the work.
            //
            // This used to test `dns.diag.isNotEmpty()`, which never failed: fetch() always appends
            // a closing "Logos: … " summary line, so diag is never empty. The result on the device
            // was a report reading "Ensembles abgefragt: 10 · Streams gefunden: 0" with an empty
            // per-ensemble list — the count was the number of ensembles PRESENT, not queried, and
            // the real report had already been overwritten. Both are fixed: the counts now mean what
            // they say, and this guard reads them.
            val didWork = dns.ensemblesQueried > 0 || dns.fmQueried > 0
            if (didWork || logoStore.count() == 0) Diag.write(appContext, DiagFile.RADIODNS, buildString {
                append("RadioDNS auto-fetch (nach Scan/Start)\n")
                append("Netz aktiv: $net\n")
                append("DAB-Sender: ${stations.count { it.band == Band.DAB }}\n")
                append("Ensembles abgefragt: ${dns.ensemblesQueried}\n")
                append("FM-Sender abgefragt: ${dns.fmQueried}\n")
                append("Logos neu gespeichert: ${dns.logosStored}\n")
                append("Streams gefunden: ${dns.streamsByStationId.size}\n")
                append("LogoStore gesamt: ${logoStore.count()}\n")
                dns.error?.let { append("Fehler: $it\n") }
                if (dns.diag.isNotEmpty()) {
                    append("\nPro-Ensemble (FQDN · welcher Resolver löste auf · Services):\n")
                    dns.diag.forEach { append("  ").append(it).append('\n') }
                }
            })
            // AFTER the report, never before: the report is an overwriting write, so the appended
            // simulcast lines were being erased by the very summary that followed them.
            fillSimulcastGaps(stations)
        }
    }

    private fun hasActiveNetwork(): Boolean = runCatching {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        cm.activeNetwork != null
    }.getOrDefault(false)

    private fun liveNowPlaying(st: Station, d: DabState) = NowPlaying(
        station = st,
        bandLine = "DAB+ · Ensemble ${st.ensemble ?: "?"}" +
            (st.bitrateKbps?.let { " · $it kbit/s AAC+" } ?: ""),
        dlsText = d.dls,
        dlTitle = d.dlTitle,
        dlArtist = d.dlArtist,
        hasSlideshow = d.slideshow != null,
        slideshowImage = d.slideshow,
        slideshowCaption = if (d.slideshow == null) "Slideshow" else null,
    )


    // ---- user actions ----

    override fun selectBand(band: Band) {
        userPickedBand = true
        val prev = _state.value
        if (prev.selectedBand == band) return
        cue(com.px6.radio.audio.UiSounds.Cue.TICK)
        // A band change supersedes any switch in flight — otherwise the loader keeps spinning on a
        // tile in the band we just left, until the safety timeout.
        clearTuning(null)
        // Follow the programme onto FM when the current DAB service links to one.
        val link = if (band == Band.FM && prev.selectedBand == Band.DAB) {
            runCatching { dab?.getLinkedFm() }.getOrNull()
                ?.let { RadioLogic.FmLink(it.freqKhz, it.pi) }
        } else null
        apply(
            RadioLogic.selectBand(
                prev, band, prev.settings.fmRegion,
                linkedFm = link,
                currentName = prev.nowPlaying?.station?.name,
            )
        )
        // Tell following whose tuner it is now: on DAB it may hand over; on FM/AM it must keep off.
        following?.setUserBand(band == Band.DAB)
        refreshAsaStatus()   // leaving DAB → ASA inoperable; back onto an EWS ensemble → active

        // Leaving a band drops any pending FM->DAB offer/probe and resets the "ignored" set (which
        // only lasts until a band change).
        dabOfferJob?.cancel()
        probedFmId = null
        dabOfferSuppressed.clear()
        if (_state.value.dabOffer != null) _state.update { it.copy(dabOffer = null) }
        // FM has no background list; scan the first time the band is asked for and nothing is known.
        if ((band == Band.FM || band == Band.AM) && prev.fmAvailable && !prev.fmSeeking &&
            prev.stations.none { it.band == band }
        ) {
            scanFm()
        }
        // EWS idle-tuner parking: switching to DAB frees the park (selectBand re-tunes DAB itself);
        // switching to FM/AM frees the DAB tuner → park it on an EWS ensemble (after the switch settles).
        if (band == Band.DAB) dabParkedForEws = false
        else viewModelScope.launch { delay(TUNE_SETTLE_MS + 400); parkDabForEwsMonitoring() }
    }

    /** Apply a pure reduction: adopt its state and run its audio commands through the router. */
    private fun apply(reduction: Reduction) {
        // The new state is published immediately (synchronous) so the UI repaints at once — the
        // tapped station highlights and any modal closes on THIS frame.
        _state.value = reduction.state
        // The actual band/station switch (amp handover, DAB/FM tune, fade) is a chain of blocking
        // binder/JNI calls. Hand it to the conflated audio worker instead of running it inline: the
        // UI never blocks on a tune, and switching faster than the tuner can retune coalesces to the
        // newest target (see [audioCmds]).
        //
        // Demo mode (debug build with no DAB stick — the emulator) must not drive hardware it does
        // not have, so PlayDab/PlayAnalog are dropped. PlayIp is NOT: an internet stream needs no
        // tuner, no amp handover and no CarManager, so it is the one source that works off-device.
        // Blocking it too meant a tapped station in the emulator went silent with no error at all —
        // only the tuning spinner timing out after 12 s.
        val demo = _state.value.demoMode
        reduction.commands.forEach { if (!demo || it is AudioCmd.PlayIp) audioCmds.trySend(it) }
    }

    /**
     * The single audio-switch worker. Drains [audioCmds] one at a time on a background thread; while
     * it is busy tuning, newer switch requests conflate down to just the latest, so a fast run
     * through the station list ends on the station the user actually stopped on — never a queue of
     * stale tunes, never two tunes at once.
     */
    private fun startAudioWorker() = viewModelScope.launch(Dispatchers.IO) {
        for (cmd in audioCmds) {
            when (cmd) {
                is AudioCmd.PlayDab -> {
                    val fade = false   // soft crossfade removed — switches are immediate
                    // Coming from a playing internet stream with fade → keep IP running through DAB's
                    // silent acquisition and cross-fade it out on DAB's first frame (onDab), instead of
                    // a ~3 s silence gap. Whichever source we're on, the loader stays until audible.
                    val defer = fade && router?.current == com.px6.radio.audio.AudioSource.IP
                    deferredIpStop = defer
                    com.px6.radio.diag.SwitchTiming.mark(appContext, "audio worker: picked up PlayDab (fade=$fade, deferIpStop=$defer)")
                    router?.toDab(fade = fade, deferIpStop = defer) { dab?.tune(cmd.stationId) }
                    com.px6.radio.diag.SwitchTiming.mark(appContext, "audio worker: toDab() returned (tune queued; audio on first frame)")
                }
                is AudioCmd.PlayAnalog -> router?.toAnalog { cmd.tuneKhz?.let { fm?.tune(it) } }
                is AudioCmd.PlayIp -> if (cmd.url.isNotBlank()) {
                    // Apply the learned loudness boost (0 = loud/natural) before playback starts.
                    ipPlayer.stationTargetGainMb = ipGains[cmd.url] ?: 0
                    router?.toIp(cmd.url)
                }
            }
        }
    }

    override fun selectStation(station: Station) { userTune(station) }

    /** A deliberate station pick by hand: the key tick, then play. */
    private fun userTune(station: Station) {
        cue(com.px6.radio.audio.UiSounds.Cue.TICK)
        play(station)
    }

    private fun play(station: Station) {
        // Any deliberate playback — a tile tap (tunePreset), the list, next/prev, or restoring the
        // last-played station on start — is the user's band choice. Mark it so band reconciliation
        // never yanks it back to DAB underneath (the "internet stream reverts to DAB after a few
        // seconds", and "IP station not restored on start" bugs: those paths call play() but did NOT
        // set this flag, so reconcileBand's DAB-preference overrode them).
        userPickedBand = true
        // A new switch supersedes any pending IP→DAB crossfade whose DAB never became audible — drop
        // the stale flag so onDab can't fade a now-unrelated stream out. PlayDab re-arms it if needed.
        deferredIpStop = false
        val r = RadioLogic.play(_state.value, station)
        apply(Reduction(r.state.copy(isPlaying = true), r.commands))
        // Immediate feedback for the slow switches (IP→DAB, cross-ensemble DAB, stream buffering):
        // mark the target as tuning right now — the UI shows a loader over its logo — and start the
        // switch-timing timeline. Cleared when the first audio frame plays (onDab audibleId / IP
        // "playing"), or by a safety timeout. Analog (FM/AM) is instant, so no loader.
        // No loader for a DAB station the tuner does not actually know: tune() returns silently for
        // an unknown service, nothing ever reports back, and the spinner sat there for the full 12 s
        // safety timeout with no audio coming.
        val needsLoader = station.band == Band.IP ||
            (station.band == Band.DAB && dab?.canTune(station.id) == true)
        if (needsLoader) {
            com.px6.radio.diag.SwitchTiming.start(appContext, router?.current?.name, station.id)
            _state.update { it.copy(tuningStationId = station.id) }
            armTuningTimeout(station.id)
        } else {
            _state.update { it.copy(tuningStationId = null) }
        }
        // Playing an analog station (e.g. a tapped FM favourite) hands the tuner to the user, too.
        following?.setUserBand(station.band == Band.DAB)
        // Playing DAB reclaims the tuner (frees any EWS park); playing FM/Internet frees it → park it
        // on an EWS ensemble so alerts keep coming (§7.2.3). Deferred so the switch settles first.
        if (station.band == Band.DAB) dabParkedForEws = false
        else viewModelScope.launch { delay(TUNE_SETTLE_MS + 400); parkDabForEwsMonitoring() }
        // Live now-playing via RadioVIS (RadioDNS) for streams with no in-band ICY (e.g. BBC HLS).
        // Playback-scoped: any station change restarts it, so the feed always matches what's playing.
        startRadioVis(station)
        // Playing an IP station proves the network is up — the right moment to (once) harvest RadioVIS
        // bearers/logos for a whole broadcaster network. No-op after it has succeeded once.
        if (station.band == Band.IP) harvestRadioVisBearers()
        // Remember it (last-played + per-band) so a restart resumes here.
        persistDebounced()
        // A tuned DAB station still without an official logo — take one look via RadioDNS (the
        // "look once" guard keeps this cheap; nothing happens if RadioDNS is off or already tried).
        if (station.band == Band.DAB &&
            logoStore.sourceRank(logoStore.key(station)) < LogoSource.RADIODNS.rank
        ) autoFetchRadioDnsLogos()
    }

    /** Clear the tuning loader if the audio never becomes audible (no signal / dead stream), so the
     *  spinner can't spin forever. */
    @Volatile private var tuningTimeoutJob: kotlinx.coroutines.Job? = null
    private fun armTuningTimeout(id: String) {
        tuningTimeoutJob?.cancel()
        tuningTimeoutJob = viewModelScope.launch {
            delay(TUNING_TIMEOUT_MS)
            if (_state.value.tuningStationId == id) {
                _state.update { it.copy(tuningStationId = null) }
                runCatching { com.px6.radio.diag.SwitchTiming.done(appContext, "timeout — no audible frame") }
            }
        }
    }

    /** The tuning loader is done — the target is audible (or superseded). */
    private fun clearTuning(id: String?) {
        val cur = _state.value.tuningStationId ?: return
        if (id == null || id == cur) _state.update { it.copy(tuningStationId = null) }
    }

    // ---- RadioVIS (RadioDNS live now-playing) ----

    @Volatile private var visJob: kotlinx.coroutines.Job? = null

    /**
     * Start (or restart) the RadioVIS feed for [station]. Broadcaster-agnostic: it only needs a
     * RadioDNS bearer, then resolves that station's own RadioVIS server and subscribes to its text +
     * image topics. Text flows into the same now-playing field as ICS/ICY metadata; the image is
     * fetched and shown like a DAB slideshow. Cancelled on every station change so it can never show a
     * stale programme. Silent no-op for stations without a bearer (nothing to resolve).
     */
    private fun startRadioVis(station: Station) {
        visJob?.cancel(); visJob = null
        // Only IP streams need this — DAB carries native DLS+slideshow (omri) and FM carries RDS, both
        // better than a second RadioVIS connection. A DAB/FM tune therefore just cancels any prior feed.
        if (station.band != Band.IP) return
        val s = _state.value.settings
        if (!s.radioDnsEnabled || !s.radioVisEnabled) return   // RadioVIS is RadioDNS — honour both switches
        val bearer = radioVisBearer(station) ?: return
        visJob = viewModelScope.launch(Dispatchers.IO) {
            RadioVisClient(
                // Resolve on each attempt (retried by the client) so a cold-boot DNS failure isn't fatal.
                resolve = {
                    val ep = runCatching { PxRadioDnsLookup.lookupVis(appContext, bearer) }.getOrNull()
                    // Log only on success — a cold-boot failure retries every few seconds and would
                    // otherwise spam the diag file.
                    if (ep != null) runCatching { Diag.write(appContext, DiagFile.IP,
                        "radiovis ${station.id} $bearer -> ${PxRadioDnsLookup.lastDiag}\n", append = true) }
                    ep?.let { RadioVisClient.Target(it.host, it.port, it.topicBase) }
                },
                onText = { text ->
                    // Only apply while this station is still the one playing (guards the switch race).
                    if (_state.value.nowPlaying?.station?.id == station.id) applyStreamTitle(text)
                },
                onImage = { url, _ ->
                    if (_state.value.settings.radioVisSlideshow &&
                        _state.value.nowPlaying?.station?.id == station.id) fetchVisImage(url, station.id)
                },
                log = { m -> Diag.write(appContext, DiagFile.IP, "$m\n", append = true) },
            ).run()
        }
    }

    /**
     * Make the running RadioVIS feed agree with the settings that were just changed.
     *
     * Switching RadioVIS (or RadioDNS) off used to leave an established feed running: the only place
     * that ever cancelled the job was a station change, so the connection kept delivering
     * now-playing text and images until the user happened to switch stations. Turning something off
     * has to take effect when you turn it off.
     */
    private fun syncRadioVis(s: Settings) {
        if (!s.radioDnsEnabled || !s.radioVisEnabled) {
            visJob?.cancel(); visJob = null
            return
        }
        // Switched back on while an IP station is already playing: start it now rather than making
        // the user change station to get the feed they just asked for.
        val playing = _state.value.nowPlaying?.station
        if (visJob?.isActive != true && playing != null && playing.band == Band.IP) startRadioVis(playing)
    }

    /**
     * The RadioDNS bearer to look RadioVIS up with. For IP stations this is the explicit
     * [Station.radioDnsBearer] — seeded for broadcast simulcasts (BBC…) or, later, harvested from the
     * station's RadioDNS SI document so no hand-seeding is needed. Broadcaster-agnostic: any bearer
     * resolves that broadcaster's own RadioVIS server.
     */
    private fun radioVisBearer(station: Station): String? = station.radioDnsBearer

    /**
     * Auto-coverage for RadioVIS: from every IP station that already carries a bearer (a seed like
     * BBC Radio 1), fetch that broadcaster's RadioDNS SI once and assign the harvested bearer to every
     * *other* IP station of the same network by name match — so the whole BBC family (Radio 2, 1Xtra,
     * 6 Music, …) gets live now-playing without hand-seeding each one. Runs once (persisted flag),
     * off the main thread; a network failure leaves the flag unset so it retries next start.
     */
    @Volatile private var harvestRunning = false
    private fun harvestRadioVisBearers(force: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (!_state.value.settings.radioDnsEnabled) return@launch
        if (harvestRunning || (!force && store.visHarvested())) return@launch
        val seeds = _state.value.stations
            .filter { it.band == Band.IP && it.radioDnsBearer != null }
            .mapNotNull { it.radioDnsBearer }.distinct()
        if (seeds.isEmpty()) return@launch
        harvestRunning = true
        try {
        val byName = HashMap<String, PxRadioDnsLookup.HarvestedService>()
        // A few attempts with a pause: the trigger fires as playback starts, and the network may take
        // a moment more to be fully up. Give up quietly after that — the next IP play retries (the
        // flag is only set on success), so a bad network never leaves the user stuck.
        var attempt = 0
        while (attempt < 3 && byName.isEmpty()) {
            if (attempt > 0) delay(5_000)
            attempt++
            for (bearer in seeds) {
                val harvested = runCatching { PxRadioDnsLookup.harvestBearers(appContext, bearer) }
                    .getOrNull().orEmpty()
                runCatching { Diag.write(appContext, DiagFile.IP,
                    "harvest[$attempt] $bearer -> ${PxRadioDnsLookup.lastDiag}\n", append = true) }
                for (svc in harvested) for (n in svc.names) byName.putIfAbsent(n.stationNameKey(), svc)
            }
        }
        if (byName.isEmpty()) return@launch   // network still down — retry on next IP play (flag unset)
        var changed = false
        // Collect the resolved bearers BY STATION ID rather than building a replacement list. The loop
        // below does blocking logo downloads (10 s connect + 10 s read, per match), so it can run for
        // tens of seconds — long enough for a DAB scan to finish or the user to add a station. Writing
        // a pre-loop snapshot back dropped exactly those, and persistDebounced() then made the loss
        // permanent across restarts.
        val bearers = HashMap<String, String>()
        _state.value.stations.forEach { st ->
            if (st.band != Band.IP) return@forEach
            val svc = byName[st.name.stationNameKey()] ?: return@forEach
            // Official per-station logo from the SI — replaces the shared broadcaster favicon (why all
            // BBC stations looked identical) with each service's own artwork.
            svc.logoUrl?.let { url ->
                if (logoStore.sourceRank(logoStore.key(st)) < LogoSource.RADIODNS.rank) fetchStoreLogo(url, st)
            }
            if (st.radioDnsBearer == null) {
                changed = true
                bearers[st.id] = svc.bearer
                Diag.write(appContext, DiagFile.IP, "harvest match ${st.name} -> ${svc.bearer}\n", append = true)
            }
        }
        if (changed) {
            _state.update { s ->
                s.copy(stations = s.stations.map { st ->
                    val b = bearers[st.id]
                    if (b != null && st.radioDnsBearer == null) st.copy(radioDnsBearer = b) else st
                })
            }
            persistDebounced()
        }
        store.markVisHarvested()
        } finally { harvestRunning = false }
    }

    /** Fetch a RadioDNS SI logo and store it as this station's artwork (RADIODNS source rank). Runs
     *  inline on the harvest's IO coroutine. */
    private fun fetchStoreLogo(url: String, station: Station) {
        val bytes = runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            c.inputStream.use { it.readBytes() }
        }.getOrNull() ?: return
        runCatching { logoStore.put(logoStore.key(station), bytes, LogoSource.RADIODNS) }
    }

    /** Fetch a RadioVIS SHOW image and show it in the now-playing card (same field as DAB slideshow). */
    private fun fetchVisImage(url: String, stationId: String) = viewModelScope.launch(Dispatchers.IO) {
        val bytes = runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            c.inputStream.use { it.readBytes() }
        }.getOrNull() ?: return@launch
        _state.update { s ->
            val np = s.nowPlaying ?: return@update s
            if (np.station.id != stationId) return@update s
            s.copy(nowPlaying = np.copy(slideshowImage = bytes, hasSlideshow = true))
        }
    }

    // ---- Internet radio (band IP) ----

    /** Favicons from the last search, keyed by result station id, so a logo can be fetched on add. */
    @Volatile
    private var searchFavicons: Map<String, String> = emptyMap()

    /** Add the example seed stations (London + Tbilisi) to the list; called once on a fresh install. */
    private fun seedInternetStations() {
        val seeds = InternetRadio.seedStations()
        _state.update { s ->
            val have = s.stations.map { it.id }.toSet()
            s.copy(stations = s.stations + seeds.filter { it.id !in have })
        }
        searchFavicons = searchFavicons + InternetRadio.seedHits()
            .mapNotNull { h -> h.favicon?.let { h.station.id to it } }
        fetchInternetLogos()
    }

    /** Best-effort logos for every IP station still without one (favicon → homepage icon). */
    private fun fetchInternetLogos() = viewModelScope.launch(Dispatchers.IO) {
        val ip = _state.value.stations.filter { it.band == Band.IP }
        for (st in ip) {
            val key = logoStore.key(st)
            if (logoStore.sourceRank(key) >= LogoSource.PACK.rank) continue
            runCatching {
                LogoPack.downloadDirect(logoStore, key, searchFavicons[st.id], st.homepage)
            }
        }
    }

    /** Look a place up so its DAB location code can be derived (annex F). Runs only on an explicit
     *  search — see PlaceSearch for why this is deliberately not a background lookup. */
    fun searchPlaces(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(placeResults = emptyList(), placeSearching = false) }
            return
        }
        placeSearchJob?.cancel()
        _state.update { it.copy(placeSearching = true) }
        placeSearchJob = viewModelScope.launch(Dispatchers.IO) {
            val hits = runCatching { com.px6.radio.ews.PlaceSearch.search(appContext, q) }
                .getOrDefault(emptyList())
            _state.update { it.copy(placeSearching = false, placeResults = hits) }
        }
    }

    private var placeSearchJob: kotlinx.coroutines.Job? = null

    override fun searchInternet(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(internetResults = emptyList(), internetSearching = false) }
            return
        }
        internetSearchJob?.cancel()
        _state.update { it.copy(internetSearching = true) }
        internetSearchJob = viewModelScope.launch(Dispatchers.IO) {
            val hits = runCatching { InternetRadio.search(q) }.getOrDefault(emptyList())
            // Remember favicons so a later "add" can fetch the logo without another search.
            searchFavicons = searchFavicons + hits.mapNotNull { h -> h.favicon?.let { h.station.id to it } }
            _state.update {
                it.copy(internetSearching = false, internetResults = hits.map { h -> h.station })
            }
        }
    }

    override fun addInternetStation(station: Station) {
        if (station.band != Band.IP || station.streamUrl.isNullOrBlank()) return
        _state.update { s ->
            if (s.stations.any { it.id == station.id }) s
            else s.copy(stations = s.stations + station)
        }
        fetchInternetLogos()
        persistDebounced()
    }

    override fun addManualStream(name: String, url: String) {
        val u = url.trim()
        if (!u.startsWith("http")) return
        val host = runCatching { java.net.URL(u).host }.getOrNull().orEmpty()
        val nm = name.trim().ifBlank { host.ifBlank { "Stream" } }
        // Include the URL in the raw id so two hand-added streams never collide on the same name.
        // City "Manuell" makes them their own group (ensemble = city) in the grouped lists.
        val st = InternetRadio.station(
            rawId = "$nm $u", name = nm, city = "Manuell", country = null, url = u, homepage = null,
        )
        addInternetStation(st)
    }

    override fun removeInternetStation(stationId: String) {
        _state.update { s ->
            s.copy(
                stations = s.stations.filterNot { it.id == stationId },
                // Free any preset that pointed at it, and forget it as the IP band's last station.
                presets = s.presets.map { if (it.stationId == stationId) it.copy(stationId = null) else it },
                lastStationPerBand = s.lastStationPerBand.filterValues { it != stationId },
                internetResults = s.internetResults,
            )
        }
        persistDebounced()
    }

    // ---- FM -> DAB offer (reverse of service following) ----

    /**
     * On a manually-tuned FM station (never the FM fallback — that keeps `selectedBand == DAB`),
     * check whether a linked DAB+ version is strongly receivable using the free DAB tuner, then
     * either switch (preferDab) or raise the offer modal. The `selectedBand == FM` gate plus the
     * signal check make a ping-pong with DAB->FM following impossible.
     */
    private fun maybeOfferDab() {
        val s = _state.value
        if (!s.settings.serviceFollowing) return                 // master "work together" switch off
        if (s.selectedBand != Band.FM || s.dabOffer != null) return
        val np = s.nowPlaying?.station ?: return
        if (np.band != Band.FM) return
        val khz = np.frequencyKhz ?: return
        if (np.id in dabOfferSuppressed) return
        // Probed recently? Wait before disturbing the tuner again. Not "once per session": a car
        // drives, and a candidate that was out of range at the kerb is often solid a few minutes
        // later. probedFmId still stops a second job for the station currently being probed.
        if (np.id == probedFmId) return
        fmProbedAtMs[np.id]?.let { last ->
            if (android.os.SystemClock.elapsedRealtime() - last < FM_DAB_RETRY_MS) return
        }
        if (s.fmSeeking || scanDone != null || s.dabScanning) return  // never probe while seeking/scanning
        val candidate = RadioLogic.findDabForFm(s.stations, khz, np.piCode) ?: run {
            // No DAB counterpart known for this FM station. Worth recording once: it is the
            // difference between "no DAB version exists" and "the link derivation failed".
            if (np.id != noDabCounterpartLogged) {
                noDabCounterpartLogged = np.id
                runCatching {
                    Diag.write(appContext, DiagFile.LINKS,
                        "${currentClock()} FM->DAB: kein DAB-Gegenstueck fuer ${np.name} " +
                            "(${khz / 1000.0} MHz, PI ${np.piCode?.let { "0x%04X".format(it) } ?: "—"})\n",
                        append = true)
                }
            }
            return
        }
        if (dabOfferJob?.isActive == true) return
        probedFmId = np.id
        val fmId = np.id
        dabOfferJob = viewModelScope.launch {
            // Dwell first: only disturb the DAB tuner once you've stayed on this FM station a moment,
            // so stepping through FM never throws the tuner around.
            delay(FM_DAB_DWELL_MS)
            val s1 = _state.value
            if (s1.selectedBand != Band.FM || s1.nowPlaying?.station?.id != fmId ||
                s1.fmSeeking || scanDone != null || s1.dabScanning
            ) return@launch                                      // moved on — leave the tuner alone
            // Remember where the DAB tuner is now, so we can put it back after the probe (the probe
            // retunes it). Falls back to per-band memory; may be null on a fresh FM-first session.
            dabTunerHome = dab?.state?.value?.nowPlayingId ?: s1.lastStationPerBand[Band.DAB]
            // Reception is a property of the ENSEMBLE, not of the single service in it: same
            // multiplex, same frequency. So when the tuner already sits on the candidate's ensemble
            // — which is often the case, because the ASA monitor parks it on one — the reading is
            // already there and no retune is needed at all. That is the cheapest possible answer:
            // no tuner disturbance, no gap in warning coverage, no settle time.
            val onCandidateEnsemble = dab?.state?.value?.currentTunerEnsembleId ==
                com.px6.radio.ews.EwsMonitorPolicy.ensembleOf(candidate)
            val bars = if (onCandidateEnsemble) {
                dab?.state?.value?.signalBars ?: 0
            } else {
                dabProbeInFlight = true
                try {
                    withContext(Dispatchers.IO) {
                        runCatching { dab?.probeSignal(candidate.id) }.getOrNull()
                    } ?: 0
                } finally {
                    dabProbeInFlight = false
                }
            }
            val s2 = _state.value
            val stillHere = s2.selectedBand == Band.FM && s2.nowPlaying?.station?.id == fmId
            val take = bars >= FM_TO_DAB_MIN_BARS && stillHere && fmId !in dabOfferSuppressed
            // Record the outcome. This path was entirely silent, so "it never switched to DAB+"
            // could not be told apart from "it looked and DAB was too weak" — and the probe runs
            // only once per station per session, so there was no second chance to observe either.
            runCatching {
                Diag.write(appContext, DiagFile.LINKS, buildString {
                    append(currentClock()).append(" FM->DAB Probe: ").append(np.name)
                    append(" -> ").append(candidate.name)
                    append("  Balken ").append(bars).append('/').append(FM_TO_DAB_MIN_BARS)
                    if (onCandidateEnsemble) append(" (ohne Umstimmen abgelesen)")
                    append(if (take) "  -> " + (if (s2.settings.preferDab) "umgeschaltet" else "angeboten")
                        else if (!stillHere) "  -> verworfen (Sender gewechselt)"
                        else if (fmId in dabOfferSuppressed) "  -> verworfen (abgelehnt)"
                        else "  -> zu schwach, bleibt auf FM")
                    append('\n')
                }, append = true)
            }
            fmProbedAtMs[fmId] = android.os.SystemClock.elapsedRealtime()
            probedFmId = null            // a later attempt may run once the retry window has passed
            if (take) {
                if (s2.settings.preferDab) play(candidate)       // auto-switch (tuner already on it)
                else _state.update { it.copy(dabOffer = candidate) }
            } else if (!onCandidateEnsemble) {
                restoreDabTuner()                                // weak / moved on → tuner back
            }
        }
    }

    /**
     * Hand the idle DAB tuner back after a probe.
     *
     * Straight to the warning ensemble when ASA monitoring wants it — NOT via the previously heard
     * DAB station. Restoring the home station first meant the tuner sat somewhere unmonitored until
     * the ASA ticker came round again up to 15 s later, so a 1.5 s measurement cost around 17 s of
     * warning coverage. Parking directly cuts that to the measurement itself. When ASA does not want
     * the tuner, the old behaviour stands: back to where the probe found it.
     */
    private fun restoreDabTuner() {
        // Not if DAB has meanwhile become the audible source. The probe runs while FM plays, but the
        // user can tap a DAB station during its settle window: the tuner is then THEIRS. Restoring
        // here would both move it off their station and — because retuneSilently sets the DAB gain to
        // 0 and nothing puts it back — leave the radio silent while the UI shows the station playing.
        if (router?.current == com.px6.radio.audio.AudioSource.DAB) return
        val s = _state.value
        val parked = com.px6.radio.ews.EwsMonitorPolicy.shouldPark(
            asaEnabled = s.settings.asaEnabled,
            band = s.selectedBand,
            following = s.following,
            ewsEnsembleIds = s.ewsEnsembleIds,
            // The tuner is wherever the probe left it, which is by definition not the park target.
            currentDabEnsembleId = dab?.state?.value?.currentTunerEnsembleId,
            allowDiscovery = false,
        )
        if (parked) {
            parkDabForEwsMonitoring()
            return
        }
        (dabTunerHome ?: s.lastStationPerBand[Band.DAB])
            ?.let { id -> viewModelScope.launch(Dispatchers.IO) { runCatching { dab?.retuneSilently(id) } } }
    }

    override fun acceptDabOffer() {
        val cand = _state.value.dabOffer ?: return
        _state.update { it.copy(dabOffer = null) }
        play(cand)                                               // tuner already tuned → instant switch
    }

    override fun declineDabOffer() {
        _state.update { it.copy(dabOffer = null) }
        restoreDabTuner()
    }

    override fun ignoreDabOffer() {
        _state.value.nowPlaying?.station?.id?.let { dabOfferSuppressed.add(it) }
        _state.update { it.copy(dabOffer = null) }
        restoreDabTuner()
    }

    // ---- exit, transport, hardware keys ----

    /** True once the user confirmed quitting — MainActivity observes this and finishes, which runs
     *  [onCleared] and tears every backend down (no leaked DAB audio). */
    private val _shouldFinish = MutableStateFlow(false)
    val shouldFinish: StateFlow<Boolean> = _shouldFinish.asStateFlow()

    override fun requestExit() { _state.update { it.copy(exitConfirm = true) } }
    override fun cancelExit() { _state.update { it.copy(exitConfirm = false) } }
    override fun confirmExit() {
        // Stop the audio right away so "Beenden" is instant, then signal the Activity to finish
        // (onCleared releases everything). Guarded double-release is harmless.
        runCatching { viewModelScope.launch(Dispatchers.IO) { router?.silenceAll() } }
        runCatching { dab?.stop() }
        // Stop the stream DIRECTLY, not only through that launch. viewModelScope is cancelled when
        // the ViewModel is cleared, and silenceAll() first suspends on the router mutex — if the
        // audio worker is mid-switch and holding it, the launch dies there and the stream is never
        // stopped. dab.stop() above is synchronous, which is why only the IP path leaked: the app
        // was gone and the stream played on. Fade 0: the user asked to quit.
        runCatching { ipPlayer.stop(0) }
        _state.update { it.copy(exitConfirm = false) }
        _shouldFinish.value = true
    }

    fun togglePlay() {
        val playing = !_state.value.isPlaying
        _state.update { it.copy(isPlaying = playing) }
        // Stopping cancels a switch in flight; the loader must not outlive it.
        if (!playing) clearTuning(null)
        // Demo mode has no tuner, but it does have the internet player — and that one is genuinely
        // audible on the emulator. Skipping the whole method left the stream playing while the button
        // said "paused". Handle IP here and return before touching hardware that isn't there.
        if (_state.value.demoMode) {
            val st = _state.value.nowPlaying?.station
            if (st?.band == Band.IP) {
                if (!playing) runCatching { ipPlayer.stop() }
                else st.streamUrl?.let { url -> runCatching { ipPlayer.play(url) } }
            }
            return
        }
        // Pause must silence whatever is actually audible, not only DAB: on FM/AM (or during an FM
        // fallback) the amplifier still carries the tuner, so muting the DAB track alone left sound
        // playing. Route through the mutex so it can't interleave with a following/scan grant.
        val s = _state.value
        val onAnalog = s.selectedBand != Band.DAB || s.following == FollowingState.FM_FALLBACK
        viewModelScope.launch(Dispatchers.IO) {
            if (!playing) router?.silenceAll()
            else if (onAnalog) router?.toAnalog { } else router?.toDab { }
        }
    }

    override fun next() = step(+1)
    override fun prev() = step(-1)

    /**
     * Steering-wheel keys from the box. Arrives on Fm's handler thread — hop onto the Main
     * dispatcher (viewModelScope) before touching state, so `play`/`apply` don't race and clobber a
     * concurrent onFm/onDab `_state.update` (which would lose a station-list merge or now-playing).
     */
    private fun onSwc(key: SwcKey) = viewModelScope.launch {
        if (!_state.value.settings.steeringWheelKeys) return@launch
        when (key) {
            SwcKey.NEXT, SwcKey.SEEK_UP -> knobStep(+1)
            SwcKey.PREV, SwcKey.SEEK_DOWN -> knobStep(-1)
            SwcKey.OPEN_LIST -> openStationList()
        }
    }

    // Rotary-knob / wheel de-bounce. A tuning knob with many detents (or a fast spin) fires a burst of
    // NEXT/PREV events; stepping per event switched the station wildly and lagged behind the turn
    // ("ganz wild und sehr verzögert"). So accumulate the NET rotation and step ONCE, shortly after it
    // stops — the list lands on a single station, fast. Slow turns (each detent apart from the next)
    // still resolve one station per detent. Runs on Main (onSwc hops here), so no race on the fields.
    @Volatile private var knobDelta = 0
    @Volatile private var knobEvents = 0
    private var knobJob: kotlinx.coroutines.Job? = null

    private fun knobStep(delta: Int) {
        knobDelta += delta
        knobEvents += 1
        knobJob?.cancel()
        knobJob = viewModelScope.launch {
            delay(KNOB_SETTLE_MS)
            val d = knobDelta
            val n = knobEvents
            knobDelta = 0
            knobEvents = 0
            if (d != 0) {
                // Diagnostic: how many raw detent events collapsed into this one step (confirms the
                // knob spam rate on the device).
                Diag.write(appContext, DiagFile.FM, "knob: $n events -> net $d\n", append = true)
                step(d)
            }
        }
    }

    private fun step(delta: Int) {
        val s = _state.value
        // The arrows walk either the stored stations or every receivable one (setting).
        val list = s.steppableStations.ifEmpty { s.visibleStations }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == s.nowPlaying?.station?.id }.let { if (it < 0) 0 else it }
        userTune(list[((idx + delta) % list.size + list.size) % list.size])
    }

    // ---- scanning, presets, logos ----

    override fun scanDab() {
        // Clear the DAB list first — a rescan starts fresh instead of merging onto the old services
        // (which grew the list across scans). Presets/last-played resolve again once the scan repopulates.
        _state.update { it.copy(stations = it.stations.filter { s -> s.band != Band.DAB }) }
        dab?.setFmLinks(emptyMap())
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dab?.scan() }
                .onFailure { android.util.Log.w(TAG, "DAB scan: ${it.message}") }
        }
    }

    /**
     * FM/AM autostore — hand the whole-band sweep to the MCU (`ctl_radio_seek=auto`, exactly as the
     * factory radio). The box searches in hardware and pushes each station it locks onto ([onFmScanHit])
     * with real field strength, then a seek-end ([onFmScanEnd]). This replaced an app-driven step loop
     * that depended on per-step seek events some boxes never emit — the cause of "FM scan does nothing".
     */
    override fun scanFm() {
        val tuner = fm ?: return
        if (fmScanJob?.isActive == true) return
        stopFmScanFlag()
        // AM uses the very same mechanism. The head unit has no band switch at all — the band
        // follows from the frequency you write, so moving to 522 kHz *is* switching to medium wave.
        val band = if (_state.value.selectedBand == Band.AM) Band.AM else Band.FM
        // Clear the band being scanned first, so the autostore starts from an empty list (the found
        // stations are filed by onFmScanHit). The other bands + DAB are left untouched.
        _state.update { it.copy(stations = it.stations.filter { s -> s.band != band }) }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        scanDone = done
        scanHits = 0
        fmScanJob = viewModelScope.launch(Dispatchers.IO) {
            val profile = TuningProfile.forBand(band, _state.value.settings.fmRegion)
            _state.update { it.copy(fmSeeking = true, scanProgress = 0) }
            try {
                // Route audio to the analog tuner through the mutex, then put it into the band
                // before seeking (otherwise a scan from FM would walk FM and call it medium wave).
                router?.toAnalog {
                    if (tuner.state.value.freqKhz !in profile.minKhz..profile.maxKhz) {
                        tuner.tune(profile.minKhz)
                    }
                }
                delay(TUNE_SETTLE_MS)
                tuner.autoScan()   // hits arrive on onFmScanHit; end signalled via onFmScanEnd → done
                // Wait for the MCU seek-end. Two guards, neither cancels the coroutine (a normal end
                // must not read as an error): a STALL watchdog — no hit and no frequency report for
                // SCAN_STALL_MS means the box stopped sweeping — and a hard ceiling for the whole sweep.
                fmScanLastEventMs = android.os.SystemClock.elapsedRealtime()
                val startedAt = fmScanLastEventMs
                var stalled = false
                while (!done.isCompleted) {
                    delay(500)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - fmScanLastEventMs > SCAN_STALL_MS) { stalled = true; break }
                    if (now - startedAt > AUTOSCAN_TIMEOUT_MS) break
                }
                tuner.cancelAutoScan()
                if (stalled) addError("FM-Suchlauf abgebrochen: keine Rückmeldung vom Tuner seit ${SCAN_STALL_MS / 1000} s")
                persistNow()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t   // a real cancel (manual tune) — not an error
                android.util.Log.w(TAG, "FM scan: ${t.message}")
                addError("FM-Suchlauf abgebrochen: ${t.message}")
            } finally {
                tuner.cancelAutoScan()
                scanDone = null
                _state.update { it.copy(fmSeeking = false, scanProgress = 0) }
            }
        }
    }

    /** Last hit or frequency report while sweeping — the FM scan's stall watchdog reads it. */
    @Volatile private var fmScanLastEventMs = 0L

    /** One station the MCU auto-seek locked onto — add it (name fills in later from RDS). */
    private fun onFmScanHit(khz: Int) {
        fmScanLastEventMs = android.os.SystemClock.elapsedRealtime()
        if (khz <= 0 || scanHits >= MAX_SCAN_STATIONS) return
        val band = if (_state.value.selectedBand == Band.AM) Band.AM else Band.FM
        val id = if (band == Band.AM) "am.$khz" else "fm.$khz"
        _state.update { s ->
            if (s.stations.any { it.id == id }) s
            // Name it straight from the remembered frequency→name store (HCT4Radio's getFrequencyPsn),
            // so a rescan doesn't lose names learned earlier — the list comes back named.
            else { scanHits++; s.copy(stations = s.stations + buildAnalogStation(band, khz, rememberedFmName(khz), null)) }
        }
        val s = _state.value
        if (s.fmSeeking) {
            val p = TuningProfile.forBand(band, s.settings.fmRegion)
            val pct = ((khz - p.minKhz) * 100 / (p.maxKhz - p.minKhz).coerceAtLeast(1)).coerceIn(0, 99)
            if (pct > s.scanProgress) _state.update { it.copy(scanProgress = pct) }
        }
    }

    /** The MCU finished sweeping the band — let the scan coroutine finish normally (no cancel). */
    private fun onFmScanEnd() {
        if (scanDone?.isCompleted == false) cue(com.px6.radio.audio.UiSounds.Cue.SCAN_DONE)
        scanDone?.complete(Unit)
    }

    /** Cancels a running FM scan (e.g. when the user tunes by hand). */
    private fun stopFmScanFlag() {
        fm?.cancelAutoScan()
        fmScanJob?.cancel()
        fmScanJob = null
        if (_state.value.fmSeeking) _state.update { it.copy(fmSeeking = false, scanProgress = 0) }
    }

    override fun clearPreset(index: Int?) {
        _state.update { s ->
            s.copy(presets = s.presets.map {
                if (index == null || it.index == index) it.copy(stationId = null) else it
            })
        }
        persist()
    }

    /**
     * Fetches logos for every station we know. User-triggered only: it costs mobile data and
     * reaches out to a third-party server, so it never happens on its own.
     */
    /**
     * Manual tuning. On FM and AM these go straight to the tuner; on DAB there are no frequencies
     * to step through, so a step means the next service and a hold the next ensemble.
     */
    override fun tuneStep(up: Boolean) {
        stopFmScanFlag()
        val s = _state.value
        if (s.selectedBand == Band.DAB) { step(if (up) +1 else -1); return }
        apply(RadioLogic.stepAnalog(s, up, s.settings.fmRegion))
    }

    override fun seekStation(up: Boolean) {
        stopFmScanFlag()
        val s = _state.value
        if (s.selectedBand == Band.DAB) {
            // Next ensemble: the next service that sits in a different one.
            val list = s.stations.filter { it.band == Band.DAB }
            val current = s.nowPlaying?.station
            val target = list.firstOrNull { it.ensemble != null && it.ensemble != current?.ensemble }
            if (target != null) play(target) else step(if (up) +1 else -1)
            return
        }
        runCatching { if (up) fm?.seekUp() else fm?.seekDown() }
            .onFailure { addError("Sendersuche fehlgeschlagen: ${it.message}") }
    }

    override fun tuneFrequency(khz: Int) {
        stopFmScanFlag()
        val s = _state.value
        if (s.selectedBand == Band.DAB) return
        apply(RadioLogic.tuneAnalog(s, khz, s.settings.fmRegion))
    }

    override fun refreshRadioDns() {
        // Manual "RadioDNS aktualisieren": forget the one-shot harvest so it runs again (recovers a
        // past failure, picks up stations a broadcaster added), and refresh internet logos.
        viewModelScope.launch(Dispatchers.IO) {
            store.clearVisHarvested()
            Diag.write(appContext, DiagFile.IP, "radiodns refresh requested\n", append = true)
        }
        harvestRadioVisBearers(force = true)
        fetchInternetLogos()
    }

    override fun downloadLogos() {
        if (_state.value.logoDownloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(logoDownloading = true, logoStatus = "Lade Senderliste…") }

            // 1) RadioDNS first — official broadcaster logos (highest quality/coverage), cached on
            //    disk so this is a one-time cost per station. Also harvests IP simulcast URLs for the
            //    later DAB->FM->IP fallback. Gated by the data-saver setting.
            var dnsStored = 0
            if (_state.value.settings.radioDnsEnabled) {
                val dns = RadioDnsLogos.fetch(
                    context = appContext,
                    stations = _state.value.stations,
                    store = logoStore,
                    tried = radioDnsTriedEids,
                    knownStreams = radioDnsStreams.keys,
                    force = true,   // manual button: re-check every ensemble, even already-tried ones
                    onProgress = { done, total ->
                        _state.update { it.copy(logoStatus = "RadioDNS: Ensemble $done von $total…") }
                    },
                )
                dnsStored = dns.logosStored
                if (dns.streamsByStationId.isNotEmpty()) {
                    radioDnsStreams = radioDnsStreams + dns.streamsByStationId
                    _state.update { it.copy(ipStreamStationIds = radioDnsStreams.keys.toSet()) }
                    persistDebounced()
                }
            }

            // 1b) Media Broadcast DAB-Logoservice — official DAB logos matched exactly by SId (no
            //     name guessing). One ~23 MB download, so manual-only. Highest DAB rank, so it wins
            //     for DAB where present; RadioDNS/radio-browser still cover FM + what MB lacks.
            val mb = if (_state.value.settings.mediaBroadcastLogos) {
                _state.update { it.copy(logoStatus = "Media Broadcast: DAB-Logos…") }
                MediaBroadcastLogos.download(
                    context = appContext,
                    stations = _state.value.stations,
                    store = logoStore,
                    onProgress = { done, total ->
                        _state.update { it.copy(logoStatus = "Media Broadcast: ZIP $done von $total…") }
                    },
                )
            } else MediaBroadcastLogos.Result(0)

            // 2) radio-browser (+ the station's own homepage icon) fills whatever the official DAB
            //    sources didn't cover (FM, small locals). put() keeps the higher-ranked logo.
            val result = LogoPack.download(
                stations = _state.value.stations,
                store = logoStore,
                onProgress = { done, total ->
                    _state.update { it.copy(logoStatus = "radio-browser: Sender $done von $total…") }
                },
            )
            _state.update {
                it.copy(
                    logoDownloading = false,
                    logoCount = logoStore.count(),
                    logoStatus = result.error
                        ?: "$dnsStored RadioDNS + ${mb.stored} Media Broadcast + ${result.stored} radio-browser · ${logoStore.count()} gesamt",
                )
            }
        }
    }

    override fun clearLogos() {
        logoStore.clear()
        _state.update { it.copy(logoCount = 0, logoStatus = "Alle Logos gelöscht") }
    }

    // ---- debug hooks (emulator / adb) ----

    /**
     * Debug/emulator only (triggered by the DEBUG_IP_PLAY broadcast): play the current DAB station's
     * RadioDNS IP simulcast directly and show the IP-fallback state. On the emulator there is no DAB
     * tuner, so the following engine never runs to cascade DAB->FM->IP on a weak signal — that path
     * is proven in the FollowingSimulator; this just exercises the audible end of it off-device.
     */
    fun debugPlayIp() {
        val id = _state.value.nowPlaying?.station?.id
            ?: _state.value.stations.firstOrNull { it.band == Band.DAB }?.id
        val url = id?.let { radioDnsStreams[it] }
        if (url == null) {
            android.util.Log.w(TAG, "debugPlayIp: no stream for id=$id (have ${radioDnsStreams.keys})")
            return
        }
        android.util.Log.i(TAG, "debugPlayIp: $url")
        ipPlayer.play(url,
            onReady = { android.util.Log.i(TAG, "debugPlayIp: playing") },
            onError = { android.util.Log.w(TAG, "debugPlayIp: stream error") })
        _state.update { it.copy(following = FollowingState.IP_FALLBACK) }
    }

    fun debugStopIp() {
        ipPlayer.stop()
        _state.update { it.copy(following = FollowingState.DAB_PRIMARY) }
    }

    /**
     * Debug-only ASA/EWS simulation. Builds a real [org.omri.tuner.DabEwsAlert] and feeds it to the
     * exact boundary the omri tuner callback uses ([onEwsAlert]) — so the full app-side chain runs
     * unchanged: §7.5 matching, the overlay, the service label, and the §7.6 audio handover attempt.
     * No ASA logic is special-cased for the test; only the event source is faked. See MainActivity's
     * DEBUG_EWS broadcast (registered only in debug builds).
     *
     * [locationCsv] is a comma-separated list of "zone:digithex" codes ("" = whole-ensemble alert).
     */
    /** Debug/emulator only: a fake 41-channel DAB scan (~30 s) that finds a station now and then,
     *  so the progress line and the settings detail block can be seen without a stick. */
    fun debugSimulateScan() {
        if (!BuildConfig.DEBUG || _state.value.dabScanning) return
        viewModelScope.launch {
            // dabPresent too, so the DAB+ settings page (and its detail block) exists on the emulator.
            _state.update { it.copy(dabPresent = true, dabScanning = true, scanProgress = 0, scanStartedAtMs = android.os.SystemClock.elapsedRealtime()) }
            val names = listOf("WDR 2", "1LIVE", "WDR 3", "WDR 4", "WDR 5", "Radio Bochum", "Antenne Unna", "Deutschlandfunk", "DLF Kultur", "DLF Nova")
            var found = 0
            for (i in 1..41) {
                delay(700)
                val add = i % 4 == 0 && found < names.size
                _state.update { s ->
                    val st = if (add) {
                        val ens = if (i < 20) "WDR NRW" else "Bundesmux"
                        s.stations + Station("${100 + i}.d${300 + found}", names[found], Band.DAB, ens, names[found].take(2), 0, 0, ensemble = ens, bitrateKbps = 96)
                    } else s.stations
                    if (add) found++
                    s.copy(scanProgress = i * 100 / 41, stations = st)
                }
            }
            _state.update { it.copy(dabScanning = false, scanProgress = 0) }
        }
    }

    fun debugSimulateEws(
        form: Int, stage: Int, test: Boolean, otherEnsemble: Boolean,
        idValue: Int, incidentId: Int, locationCsv: String, tunedEnsembleId: Int = 0x100C,
        messageText: String? = null,
    ) {
        val locs = locationCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val desc = "SIMULATED FIG0/15 form=$form stage=$stage" +
            (if (test) " TEST" else "") + (if (otherEnsemble) " OE eid=$idValue" else " subch=$idValue")
        val alert = org.omri.tuner.DabEwsAlert(
            form, otherEnsemble, idValue, stage, incidentId, test, false, desc, locs, tunedEnsembleId)
        android.util.Log.i(TAG, "debugSimulateEws: $desc loc=${locs.toList()}")
        onEwsAlert(alert)
        // Simulate the alert service's dynamic-label message (no real DAB audio on the emulator).
        if (!messageText.isNullOrBlank()) {
            _state.update { it.copy(ewsAlert = it.ewsAlert?.copy(messageText = messageText)) }
        }
    }

    // ---- view state, settings, GPS ----

    override fun setViewMode(mode: ViewMode) {
        cue(com.px6.radio.audio.UiSounds.Cue.TICK)
        _state.update { it.copy(viewMode = mode) }
    }

    /** Hardware "list" key → ask the active frontend to open its station list (one-shot signal). */
    override fun openStationList() {
        _state.update { it.copy(openListRequest = it.openListRequest + 1) }
    }

    override fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        persist()
    }

    override fun toggleFixedName(station: Station) {
        _state.update { s ->
            val fixed = s.fixedNames.toMutableMap()
            if (fixed.remove(station.id) == null) fixed[station.id] = station.name
            s.copy(fixedNames = fixed)
        }
        persist()
    }

    override fun tunePreset(index: Int) {
        // Any manual station choice ends the preview scan.
        stopFmScanFlag()
        val s = _state.value
        val st = s.presets.firstOrNull { it.index == index }?.stationId?.let { s.station(it) } ?: return
        userTune(st)
    }

    override fun assignPreset(index: Int) {
        _state.value = RadioLogic.assignPreset(_state.value, index)
        cue(com.px6.radio.audio.UiSounds.Cue.CONFIRM)
        persist()
    }

    override fun tick() = cue(com.px6.radio.audio.UiSounds.Cue.TICK)

    override fun openSettings() {
        cue(com.px6.radio.audio.UiSounds.Cue.TICK)
        _state.update { it.copy(screen = Screen.SETTINGS) }
    }

    override fun closeSettings() {
        _state.update { it.copy(screen = Screen.RADIO) }
    }

    /**
     * Diagnostics files follow the setting; the debug build keeps them on regardless, because the
     * simulation hooks and every desk investigation depend on them.
     */
    private fun applyDiagnostics(s: Settings) {
        Diag.enabled = s.diagnostics || BuildConfig.DEBUG
    }

    /** Delete every diagnostics file on every volume (the "löschen" row in Settings > System). */
    fun deleteDiagnostics() = viewModelScope.launch(Dispatchers.IO) { runCatching { Diag.deleteAll(appContext) } }

    /** Follow (or stop following) the vehicle position, per the ASA setting. */
    private fun syncGpsLocation(s: Settings) {
        if (s.asaEnabled && s.asaFollowGps) {
            gpsLocation.onCodeChanged = { c ->
                val text = gpsCodeText(c)
                _state.update { st -> if (st.asaGpsCode == text) st else st.copy(asaGpsCode = text) }
                runCatching {
                    com.px6.radio.diag.Diag.write(appContext, DiagFile.EWS,
                        "${currentClock()} GPS location code → $text\n", append = true)
                }
            }
            gpsLocation.start()
        } else {
            gpsLocation.onCodeChanged = null
            gpsLocation.stop()
            _state.update { if (it.asaGpsCode == null) it else it.copy(asaGpsCode = null) }
        }
    }

    /**
     * How the derived code is shown. The 12-symbol presentation form (annex A.3), the same notation
     * the user's own entered codes are stored in — the internal "Z1:91BB82" is what the broadcast
     * carries, and putting it beside hand-entered codes made one thing look like two.
     */
    private fun gpsCodeText(c: com.px6.radio.ews.EwsMatcher.Code): String =
        com.px6.radio.ews.EwsMatcher.presentationOf(c)

    override fun updateSettings(block: (Settings) -> Settings) {
        _state.update { it.copy(settings = block(it.settings)) }
        val s = _state.value.settings
        runCatching {
            following?.configure(
                s.serviceFollowing, s.handoverThreshold, false, s.dabDabFollowing,
                s.radioDnsEnabled && s.ipFallbackEnabled,
                ipBeforeFm = s.fallbackOrder == com.px6.radio.model.FallbackOrder.IP_FIRST,
            )
        }
        applyAudioSettings()
        _state.update { it.copy(darkActive = resolveDark(s.themeMode, it.headlightOn)) }
        refreshAsaStatus()   // ASA toggle just changed → reflect it in the status bar immediately
        syncGpsLocation(s)
        syncRadioVis(s)
        applyDiagnostics(s)
        persist()
    }

    /** ASA/EWS status the status bar shows: OFF (disabled), ACTIVE (on an EWS ensemble — a FIG 0/15
     *  seen within [EWS_FRESH_MS]), or INACTIVE (the mandatory "EWS inoperable" state: FM/AM/internet,
     *  or a DAB ensemble carrying no FIG 0/15). ETSI TS 104 089 §5.3 / §7.2.1. */
    private fun computeAsaStatus(
        settings: Settings, dabPresent: Boolean, dabEwsElapsedMs: Long,
        currentTunerEid: Int?, ewsEnsembleIds: Set<Int>,
    ): AsaStatus {
        if (!settings.asaEnabled) return AsaStatus.OFF
        // No DAB tuner → EWS is impossible (it is DAB-only). Hide the pill entirely rather than
        // showing a misleading red "inactive", which would imply ASA is on but merely not receiving.
        if (!dabPresent) return AsaStatus.OFF
        // Band-INDEPENDENT: the DAB USB tuner is separate hardware from the FM chip, and its omri
        // service keeps decoding the FIC even while FM/Internet is the audible source (muted, not
        // stopped). So the status reflects the DAB tuner's EWS reception regardless of the audible band.
        val fresh = dabEwsElapsedMs > 0L &&
            (android.os.SystemClock.elapsedRealtime() - dabEwsElapsedMs) < ASA_HEARTBEAT_FRESH_MS
        if (fresh) return AsaStatus.ACTIVE                                   // heartbeat alive → green
        // Not fresh: amber if the tuner sits on an ensemble we KNOW carries EWS but its heartbeat has
        // timed out (reception lost) — we're still trying; red if it's simply not an EWS ensemble.
        return if (currentTunerEid != null && currentTunerEid in ewsEnsembleIds)
            AsaStatus.DEGRADED else AsaStatus.INACTIVE
    }

    private fun refreshAsaStatus() = _state.update {
        val d = dab?.state?.value
        it.copy(asaStatus = computeAsaStatus(it.settings, d?.tunerPresent ?: false,
            dab?.ewsLastFrameElapsedMs ?: 0L, d?.currentTunerEnsembleId, d?.ewsEnsembleIds ?: emptySet()))
    }

    // ---- ASA monitoring: parking the idle tuner on an EWS-capable ensemble ----

    @Volatile private var dabParkedForEws = false

    /**
     * Park the idle DAB tuner on a known EWS ensemble so alerts are still received while FM/Internet
     * is the audible source (ETSI TS 104 089 §7.2.3). No-op — nothing happens — if ASA is off, DAB is
     * the audible band, service following is using the DAB tuner, the current DAB ensemble already
     * carries EWS, or NO EWS-capable ensemble is known (per the user's rule). The tuner is retuned
     * silently (muted); the logical now-playing is untouched, so returning to DAB restores the station.
     */
    private fun parkDabForEwsMonitoring(allowDiscovery: Boolean = false) {
        val s = _state.value
        val d = dab ?: return
        // A probe owns the tuner for its few seconds — parking now would measure the wrong ensemble.
        // The monitor re-parks on its next tick, so nothing is lost by waiting.
        if (dabProbeInFlight) return
        val policy = com.px6.radio.ews.EwsMonitorPolicy
        // Ask with the AUTHORITATIVE ensemble the tuner physically sits on, not the logical
        // now-playing: a silent park deliberately leaves nowPlayingId alone, so using it here would
        // make the guard "am I already on an EWS ensemble?" permanently false and re-park on every
        // check — harmless when parking was event-driven, an endless retune under the monitor loop.
        if (!policy.shouldPark(
                asaEnabled = s.settings.asaEnabled,
                band = s.selectedBand,
                following = s.following,
                ewsEnsembleIds = s.ewsEnsembleIds,
                currentDabEnsembleId = d.state.value.currentTunerEnsembleId,
                allowDiscovery = allowDiscovery,
            )
        ) return
        val target = policy.pickParkTarget(
            stations = s.stations,
            ewsEnsembleIds = s.ewsEnsembleIds,
            allowDiscovery = allowDiscovery,
            excludeEnsembles = ewsDiscoveryTried.toSet(),
        ) ?: return
        // Already decoding exactly this service — restarting it would only interrupt its own FIC.
        if (d.state.value.currentTunerEnsembleId == com.px6.radio.ews.EwsMonitorPolicy.ensembleOf(target)) return
        // Off the main thread: retuneSilently ends in radio.startRadioService(), a blocking JNI call.
        // Every caller of this sits in viewModelScope (= Main), and since the EWS monitor loop was
        // added it runs every 15 s — a tuner call on Main at that rate is how the UI ends up frozen.
        viewModelScope.launch(Dispatchers.IO) { runCatching { d.retuneSilently(target.id) } }
        dabParkedForEws = true
        ewsParkedAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            val eid = target.id.substringBefore('.')
            val how = if (s.ewsEnsembleIds.isEmpty()) "discover" else "known"
            com.px6.radio.diag.Diag.write(appContext, DiagFile.EWS,
                "${currentClock()} park DAB tuner on ensemble $eid ($how)\n", append = true)
        }
    }

    // Ensembles we parked on that then stayed silent (no FIG 0/15 within the dwell) — skipped by the
    // next discovery attempt so the sweep advances instead of retrying the same dead ensemble.
    private val ewsDiscoveryTried = mutableSetOf<Int>()
    @Volatile private var ewsParkedAtMs = 0L

    /**
     * Keep the DAB tuner actually decoding an EWS ensemble whenever ASA is on and something else is
     * audible — the guarantee behind the ASA badge (ETSI TS 104 089 §7.2.3).
     *
     * This runs on a timer rather than only on events because the interesting failure was a race, not
     * a missing rule: at cold start into an Internet stream the park was attempted ~1 s after
     * autoplay, long before the USB stick had finished opening, so the station list was still empty
     * and the park silently found no target. Nothing ever retried, the tuner stayed idle, no FIG 0/15
     * arrived — and ASA only ever went green after the user had manually tuned a DAB station once,
     * which is exactly the reported symptom. A periodic check closes that race for good, and also
     * recovers from a scan having wiped the learned ensembles.
     */
    private fun ensureEwsMonitoring() {
        val s = _state.value
        val d = dab ?: return
        if (!s.settings.asaEnabled) return
        if (s.selectedBand == Band.DAB) return                       // DAB itself is decoding already
        if (s.following == FollowingState.FM_FALLBACK ||
            s.following == FollowingState.IP_FALLBACK) return        // following owns the tuner
        val now = android.os.SystemClock.elapsedRealtime()
        val last = d.ewsLastFrameElapsedMs
        if (last > 0L && now - last < ASA_HEARTBEAT_FRESH_MS) {
            // Hearing the heartbeat: the tuner is doing its job. Forget the failed candidates so a
            // later re-discovery (after a scan, or a move into a new region) starts from scratch.
            if (ewsDiscoveryTried.isNotEmpty()) ewsDiscoveryTried.clear()
            return
        }
        // Silent. If we have been sitting on this ensemble long enough for a 1 Hz heartbeat to have
        // shown up, take that as proof it does not carry EWS and let the sweep move on.
        val cur = d.state.value.currentTunerEnsembleId
        if (cur != null && ewsParkedAtMs > 0L && now - ewsParkedAtMs > EWS_DISCOVERY_DWELL_MS) {
            ewsDiscoveryTried += cur
        }
        // Only sweep the ensembles once. Trying them forever would retune the stick every tick for
        // no gain on a receiver that simply has no EWS coverage; a scan or a band change re-opens it.
        val all = com.px6.radio.ews.EwsMonitorPolicy.allEnsembles(s.stations)
        if (all.isNotEmpty() && ewsDiscoveryTried.containsAll(all)) return
        parkDabForEwsMonitoring(allowDiscovery = true)
    }

    // ---- ASA / EWS alerts (ETSI TS 104 089) — the lifecycle lives in EwsAlertEngine ----

    /**
     * The engine sees the radio only through this host: state, tuner lookup, audio route, foreground.
     * Everything Android-specific (locale strings, Activity start, diagnostics file) stays here.
     */
    private val ewsHost = object : EwsAlertEngine.Host {
        override val settings get() = _state.value.settings
        override val stations get() = _state.value.stations
        override val gpsCode get() = gpsLocation.code
        override val alert get() = _state.value.ewsAlert
        override fun updateAlert(transform: (com.px6.radio.model.EwsAlertUi?) -> com.px6.radio.model.EwsAlertUi?) {
            _state.update { st ->
                val next = transform(st.ewsAlert)
                if (next === st.ewsAlert) st else st.copy(ewsAlert = next)
            }
        }
        override fun addHistory(line: String, max: Int) {
            _state.update { st -> st.copy(asaHistory = (listOf(currentClock() to line) + st.asaHistory).take(max)) }
        }
        override val nowPlayingStationId get() = _state.value.nowPlaying?.station?.id
        override fun findServiceBySubChannel(subCh: Int): String? = dab?.findServiceBySubChannel(subCh)
        // "Already playing" has to mean AUDIBLE, not merely tuned: nowPlayingId is only ever written
        // by tune() and is never cleared when the audio leaves DAB (toAnalog/toIp just mute the DAB
        // sink and leave the service decoding). Only the router knows what is on the amplifier.
        override fun isAudiblyPlaying(serviceId: String): Boolean =
            router?.current == com.px6.radio.audio.AudioSource.DAB && dab?.state?.value?.nowPlayingId == serviceId
        override fun playAlertService(serviceId: String) {
            val d = dab ?: return
            following?.setUserBand(true)   // keep DAB->FM following from pulling away during the alert
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { router?.toDab(fade = false) { d.tune(serviceId) } }
                    .onFailure { android.util.Log.w(TAG, "EWS audio handover failed: ${it.message}") }
            }
        }
        override fun playStation(station: Station) = play(station)
        /**
         * Bring our Activity to the foreground so a background app still shows the alert (§7.6). Relies
         * on the "draw over other apps" permission (used for the mini-player) to allow a background
         * activity start; best-effort — never throws into the alert path.
         */
        override fun bringToForeground() {
            // §7.6: the attention signal comes first — the driver hears the alert before reading it.
            if (_state.value.settings.asaAttentionTone) sounds.play(com.px6.radio.audio.UiSounds.Cue.ALERT)
            runCatching {
                appContext.startActivity(
                    android.content.Intent(appContext, com.px6.radio.MainActivity::class.java).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }.onFailure { android.util.Log.w(TAG, "EWS foreground bring-up failed: ${it.message}") }
        }
        override fun stageName(stage: Int, isTest: Boolean): String {
            // Resolve in the user-chosen app language (wrap fresh — appContext is not locale-wrapped).
            val ctx = com.px6.radio.i18n.LocaleHelper.wrap(appContext)
            val res = when {
                isTest -> R.string.ews_stage_test
                stage == org.omri.tuner.DabEwsAlert.STAGE_LEVEL1_CRITICAL -> R.string.ews_stage_critical
                else -> R.string.ews_stage_warning
            }
            return ctx.getString(res)
        }
        override fun log(text: String) {
            Diag.write(appContext, DiagFile.EWS, "${currentClock()} $text\n", append = true)
        }
        override fun clockText(): String = currentClock()
    }

    private val ews = EwsAlertEngine(
        scope = viewModelScope, host = ewsHost, now = { android.os.SystemClock.elapsedRealtime() },
    )

    private fun onEwsAlert(alert: org.omri.tuner.DabEwsAlert) = ews.onAlert(alert)

    /** Dismiss the alert overlay by hand (§7.6.4 user termination). */
    override fun dismissEwsAlert() = ews.dismiss()

    /** Push audio-related settings (loudness normalisation, fades) into the internet player. */
    private fun applyAudioSettings() {
        val s = _state.value.settings
        ipPlayer.normalizeLoudness = s.normalizeStreamLoudness
        ipPlayer.fadeEnabled = false   // soft crossfade removed
    }

    // ---- persistence ----

    private fun applyPersisted(p: Persisted) {
        pendingAutoplayId = p.lastPlayedId            // auto-play it once it becomes available
        pendingAutoplaySetAtMs = android.os.SystemClock.elapsedRealtime()
        fmNames.putAll(p.fmNames)                     // restore the learned FM frequency→name memory
        _state.update { s ->
            // Rebuild the saved FM/AM stations so their tiles work at once, before any scan (like the
            // factory radio, which keeps the name keyed by frequency). Don't duplicate what's present.
            val restored = p.analogStations.map { buildAnalogStation(it.band, it.khz, it.name, it.pi) }
            // Restore the saved DAB list too, so DAB tiles/list are populated at once — a fresh scan
            // (or omri's own restored service DB) later replaces these by id via mergeDabStations.
            // Internet stations: the user's saved list, or the example seeds the first time (an older
            // store predating this feature has ipSeeded=false → seed once so London/Tbilisi appear).
            ipGains = p.ipGains
            // Restore the discovered IP simulcasts. The RadioDNS lookup needs a live network at the
            // moment it runs, so re-deriving them at every start made the fallback a matter of luck.
            if (p.radioDnsStreams.isNotEmpty()) {
                radioDnsStreams = p.radioDnsStreams + radioDnsStreams
                _state.update { it.copy(ipStreamStationIds = radioDnsStreams.keys.toSet()) }
            }
            // Start from the user's saved list (or the seeds on a fresh install), then — if this build
            // ships a newer seed set — merge in any seeds the user doesn't already have, without
            // touching their own additions/edits. So an update delivers new example stations too.
            val ipBase = if (p.ipSeeded) p.ipStations else InternetRadio.seedStations()
            val ipList = if (p.ipSeedVersion < InternetRadio.SEED_VERSION) {
                val seeds = InternetRadio.seedStations()
                val seedById = seeds.associateBy { it.id }
                // Backfill the group key (city) onto existing seed stations that predate it, so the
                // whole seed set groups by city — then append seeds the user doesn't have yet.
                val updated = ipBase.map { st ->
                    val seed = seedById[st.id] ?: return@map st
                    // Backfill the group key (city) AND the RadioDNS bearer (RadioVIS now-playing)
                    // onto existing seed stations that predate those fields.
                    val ens = if (st.ensemble != seed.ensemble) seed.ensemble else st.ensemble
                    val bearer = st.radioDnsBearer ?: seed.radioDnsBearer
                    if (ens != st.ensemble || bearer != st.radioDnsBearer)
                        st.copy(ensemble = ens, radioDnsBearer = bearer) else st
                }
                val have = updated.map { it.id }.toSet()
                // Drop retired seeds (e.g. the Russian stations) from existing installs too.
                (updated + seeds.filter { it.id !in have })
                    .filterNot { it.id in InternetRadio.REMOVED_SEED_IDS }
            } else ipBase
            val restoredAll = restored + p.dabStations + ipList
            val existingIds = s.stations.map { it.id }.toSet()
            // Preselect the band of the station we'll auto-play (or the last band used), so the very
            // first revealed frame already shows a populated list — not the default band's empty list
            // that then "fills in" a moment later. And resolve the theme NOW from the saved themeMode
            // (headlight/time fallback) so the UI never flashes dark and then flips to light.
            val autoBand = restoredAll.firstOrNull { it.id == p.lastPlayedId }?.band
                ?: p.lastStationPerBand.keys.firstOrNull()
                ?: s.selectedBand
            s.copy(
                settings = p.settings,
                sortMode = p.sortMode,
                fixedNames = p.fixedNames,
                lastStationPerBand = p.lastStationPerBand,
                selectedBand = autoBand,
                darkActive = resolveDark(p.settings.themeMode, s.headlightOn),
                stations = s.stations + restoredAll.filter { it.id !in existingIds },
                presets = store.presetIndices.map { i -> PresetSlot(i, p.presets[i]) },
            )
        }
        // Fetch official logos for internet stations still missing one (favicon/homepage icon).
        fetchInternetLogos()
        // NB: RadioVIS bearer harvest is triggered lazily on first IP playback (see play()), NOT here
        // — at cold boot the network often isn't up yet (same race that delayed IP streams), so an
        // eager boot harvest would just fail. Playing a stream proves the network is up.
        // Restored FM (with PI) + DAB together — link them right away so following works pre-scan.
        refreshDabFmLinks()
        // Startup: persisted DAB stations won't trigger a scan, so take one RadioDNS look now for any
        // still missing an official logo (gated by the setting, "look once" guarded).
        autoFetchRadioDnsLogos()
        // Settle the initial band from what exists (Internet when there is no stick / no tuner).
        reconcileBand()
        applyAudioSettings()
    }

    /**
     * Second source for the internet simulcast, for the broadcasters RadioDNS does not cover.
     *
     * Most do not: WDR (1LIVE) and every local station publish no RadioDNS records at all — verified
     * by lookup, not assumed. radio-browser knows them, and the app already speaks to it for the
     * station search. Only stations that RadioDNS left without a stream are asked about, so this is
     * a handful of requests once per list, and the result is persisted like any other.
     *
     * Skipped entirely when the user has the internet fallback switched off — no point spending
     * requests on an address that may not be used.
     */
    private suspend fun fillSimulcastGaps(stations: List<Station>) {
        if (!_state.value.settings.ipFallbackEnabled) return
        val missing = stations.filter { it.band == Band.DAB && it.id !in radioDnsStreams }
            .take(SIMULCAST_LOOKUP_LIMIT)
        if (missing.isEmpty()) return
        val found = HashMap<String, String>()
        for (st in missing) {
            val sid = runCatching { st.id.substringAfter('.').toInt(16) }.getOrNull()
            val gcc = sid?.let { RadioDnsBearer.dabGcc(it, st.ecc) }
            val url = InternetRadio.findSimulcast(st.name, InternetRadio.countryForGcc(gcc)) ?: continue
            found[st.id] = url
        }
        // One appended block rather than a line per hit, so the section is recognisable in the file
        // and a run that found nothing says so instead of leaving the reader guessing.
        runCatching {
            Diag.write(appContext, DiagFile.RADIODNS, buildString {
                append("\nSimulcast-Suche (radio-browser), fuer Sender ohne RadioDNS-Stream:\n")
                append("  geprueft: ${missing.size}  gefunden: ${found.size}\n")
                found.forEach { (id, url) -> append("  ").append(id).append(" -> ").append(url).append('\n') }
            }, append = true)
        }
        if (found.isEmpty()) return
        radioDnsStreams = radioDnsStreams + found
        _state.update { it.copy(ipStreamStationIds = radioDnsStreams.keys.toSet()) }
        persistDebounced()
    }

    /**
     * True while a pending autoplay is still worth waiting for; clears it once the grace has passed
     * so nothing stays blocked on a station that is never going to appear.
     */
    private fun expirePendingAutoplay(): Boolean {
        if (pendingAutoplayId == null) return false
        if (android.os.SystemClock.elapsedRealtime() - pendingAutoplaySetAtMs < AUTOPLAY_GRACE_MS) return true
        pendingAutoplayId = null
        return false
    }

    /** After a restart, select + play the station that was playing last — once it's in the list. */
    private fun tryAutoplay() {
        if (!expirePendingAutoplay()) return
        val id = pendingAutoplayId ?: return
        val st = _state.value.stations.firstOrNull { it.id == id } ?: return   // not in the list yet
        // Demo mode: an internet station plays for real, so resume it like anywhere else. A DAB/FM
        // station would drive a tuner that isn't there — leave it selected but silent.
        if (_state.value.demoMode && st.band != Band.IP) return
        // A restored DAB station is in the list for display before the tuner actually knows it — don't
        // consume the pending autoplay (silent tune) until omri can really play it. onDab retries this
        // once the service surfaces. FM/AM can play straight away.
        if (st.band == Band.DAB && dab?.canTune(st.id) != true) return
        pendingAutoplayId = null
        play(st)
    }

    /** Snapshot the service-following state (mode, coverage, the current station's FM link, log) to
     *  the stick — so what following does, and whether a station even carries the link data, is
     *  visible off-device without adb. Overwrites: the engine's log already keeps the last transitions. */
    private fun logFollowing(f: com.px6.radio.following.FollowingUiState) {
        val st = _state.value.nowPlaying?.station
        val link = st?.takeIf { it.band == Band.DAB }?.let {
            (it.linkedFmFrequencyKhz?.let { k -> "FM %.1f".format(k / 1000.0) } ?: "kein FM-Link") +
                (it.linkedFmPi?.let { p -> " · PI 0x%04X".format(p) } ?: "")
        } ?: "—"
        runCatching {
            Diag.write(
                appContext, DiagFile.FOLLOWING,
                buildString {
                    append("following=").append(f.following)
                    append(" · coverage=").append(!f.noDabCoverage)
                    append(" · muted=").append(f.muted).append('\n')
                    append("station=").append(st?.name ?: "—").append(" · link=").append(link).append("\n\n")
                    append(f.log.joinToString("\n"))
                    append('\n')
                },
            )
        }
    }

    /**
     * Recompute the DAB↔FM assignment (implicit SId==PI, secondary name) and push it to the
     * following engine's link source. Called on every change that can affect it — a new/changed FM
     * RDS-PI, an FM or DAB scan, and the restart restore — so the mapping stays continuously current.
     * Cheap: unchanged stations keep their identity, and the StateFlow dedupes an unchanged list.
     */
    /** Inputs the DAB<->FM linking actually depends on. Cheap O(n) hash vs. the O(nDAB x nFM)
     *  regex cross product it guards. */
    private fun linkSignature(stations: List<Station>): Int = stations.fold(7) { acc, st ->
        acc * 31 + when (st.band) {
            Band.DAB -> st.id.hashCode() * 31 + st.name.hashCode()
            Band.FM -> ((st.id.hashCode() * 31 + st.name.hashCode()) * 31 +
                (st.piCode ?: 0)) * 31 + (st.frequencyKhz ?: 0)
            else -> 0
        }
    }

    @Volatile private var lastLinkSignature = 0

    private fun refreshDabFmLinks(force: Boolean = false) {
        // onDab calls this on EVERY DAB state emission — a DLS line, a slideshow image, an audibleId
        // change — and it is anything but cheap: a regex-tokenising cross product over ~250 DAB and
        // ~30 FM entries, a rewrite of the whole station list, and a multi-kilobyte diagnostics
        // report, all on the main thread. Nothing about the linking changes for a new DLS line, so
        // skip unless the stations it reads actually changed. This is a large part of the UI
        // stutter on station changes and during start-up.
        val sig = linkSignature(_state.value.stations)
        if (!force && sig == lastLinkSignature) return
        lastLinkSignature = sig
        val linked = RadioLogic.linkDabToFm(_state.value.stations)
        _state.update { it.copy(stations = linked) }
        val map = linked.asSequence()
            .filter { it.band == Band.DAB && it.linkedFmFrequencyKhz != null }
            .associate { it.id to com.px6.radio.dab.FmLink(it.linkedFmFrequencyKhz!!, it.linkedFmPi) }
        dab?.setFmLinks(map)
        logLinks(linked)
    }

    /**
     * Full picture of the DAB↔FM assignment to the stick — so it is visible *why* following can or
     * can't act: the FM RDS-PI map we've learned, each DAB service's SId, and whether it resolved a
     * link (native broadcast or heuristic SId==PI/name). Complements the native-only klarwelle-dablinks.txt.
     */
    private fun logLinks(stations: List<Station>) {
        val fm = stations.filter { it.band == Band.FM && (it.frequencyKhz ?: 0) > 0 }
        val dab = stations.filter { it.band == Band.DAB }
        val linkedCount = dab.count { it.linkedFmFrequencyKhz != null || it.linkedFmPi != null }
        runCatching {
            Diag.write(appContext, DiagFile.LINKS, buildString {
                append("DAB: ").append(dab.size).append(" · verknüpft: ").append(linkedCount).append('\n')
                append("FM-PI-Karte (").append(fm.count { it.piCode != null }).append(" mit PI):\n")
                fm.forEach { s ->
                    append("  %.1f  PI=%s  %s\n".format(
                        (s.frequencyKhz ?: 0) / 1000.0,
                        s.piCode?.let { "0x%04X".format(it) } ?: "—",
                        s.name,
                    ))
                }
                append("DAB-Verknüpfungen:\n")
                dab.forEach { st ->
                    val sid = st.id.substringAfter('.', "").toIntOrNull(16)
                    append("  ").append(st.name)
                    append("  SId=").append(sid?.let { "0x%04X".format(it) } ?: "?").append(" -> ")
                    if (st.linkedFmFrequencyKhz != null || st.linkedFmPi != null) {
                        append("FM ").append(st.linkedFmFrequencyKhz?.let { "%.1f".format(it / 1000.0) } ?: "—")
                        append(" PI ").append(st.linkedFmPi?.let { "0x%04X".format(it) } ?: "—").append('\n')
                    } else {
                        append("— (kein FM-Gegenstück in der Liste)\n")
                    }
                }
            })
        }
    }

    // Last content pushed to the cluster/launcher MediaSession, so we don't re-decode art each tick.
    @Volatile private var lastMediaSig: String? = null

    /** Artwork for the media session (launcher widget, cluster page, Android Auto): the live DAB slideshow if present, else the
     *  cached station logo (null when we have neither — then the renderer shows its default icon). */
    private fun mediaArt(np: NowPlaying): android.graphics.Bitmap? {
        np.slideshowImage?.let { bytes ->
            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull()?.let { return it }
        }
        return runCatching { logoStore.bitmap(np.station)?.asAndroidBitmap() }.getOrNull()
    }

    private fun persist() = viewModelScope.launch { persistNow() }

    /** Coalesce rapid persist triggers (RDS name flicker) into one write ~1.5 s after the last change. */
    private fun persistDebounced() {
        persistDebounce?.cancel()
        persistDebounce = viewModelScope.launch {
            delay(1500)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val s = _state.value
        // Persist FM/AM stations (freq → name/PI) so saved tiles survive a restart without a rescan.
        val analog = s.stations
            .filter { it.band != Band.DAB && (it.frequencyKhz ?: 0) > 0 }
            .map { PersistedAnalog(it.band, it.frequencyKhz!!, it.name, it.piCode) }
        // Persist the DAB list so tiles/list are there on next open without a rescan. Never in demo
        // mode (would save fake stations); in release demoMode is false and this is the real list.
        val dabStations = if (s.demoMode) emptyList() else s.stations.filter { it.band == Band.DAB }
        // Internet stations are real (seeds + user-added), persist them even in demo mode.
        val ipStations = s.stations.filter { it.band == Band.IP }
        runCatching {
            // During an FM fallback the now-playing station is the FM entry, but the intended station
            // is the DAB one — persist that so a restart resumes on DAB, not the fallback frequency.
            val lastPlayed = if (s.following == FollowingState.FM_FALLBACK)
                s.lastStationPerBand[Band.DAB] ?: s.nowPlaying?.station?.id
            else s.nowPlaying?.station?.id
            store.write(
                s.settings, s.presets, s.sortMode, s.fixedNames, fmNames, analog, dabStations, ipStations,
                ipGains = ipGains,
                radioDnsStreams = radioDnsStreams,
                ipSeedVersion = InternetRadio.SEED_VERSION,
                lastPlayedId = lastPlayed,
                lastStationPerBand = s.lastStationPerBand,
            )
        }
    }

    /** Synthesize now-playing for a tapped station (until real metadata streams in). */
    private fun resolveDark(mode: ThemeMode, headlight: Boolean?): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.TIME -> {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            h < 7 || h >= 19
        }
        // No light info yet -> fall back to the time rule instead of flickering.
        ThemeMode.HEADLIGHT -> headlight ?: resolveDark(ThemeMode.TIME, null)
    }

    private fun currentClock(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date())

    private companion object {

        /** Time for the tuner to reach a freshly written frequency. */
        const val TUNE_SETTLE_MS = 600L

        /** Settle window after the last knob/wheel detent before the station steps (coalesces a spin
         *  into one landing). Short enough that a single press still feels immediate. */
        const val KNOB_SETTLE_MS = 150L
        const val TUNING_TIMEOUT_MS = 12_000L

        /** IP fade-out on the IP→DAB crossfade — matched to DabAudioSink.fadeIn (700 ms) so the
         *  stream ebbs out as the DAB service fades up. */
        const val CROSSFADE_MS = 700L

        /** An EWS ensemble signals FIG 0/15 at least once per second; if none arrives for this long
         *  the ensemble has stopped participating (ETSI TS 104 089 §7.2.2.4) and ASA goes inactive. */
        const val EWS_FRESH_MS = 10_000L

        /** Heartbeats arrive ~1/s; within this window the pill is solid green. Between this and
         *  [EWS_FRESH_MS] a lapse shows amber (a reception dip), before it finally goes red. */
        const val ASA_HEARTBEAT_FRESH_MS = 4_000L

        /** Drop an active alert overlay if no Trigger/Sustain refreshes it within this window (the
         *  alert message has ended without an explicit End phase). */

        /** How many past alerts the ASA info panel lists. Short on purpose — it answers "what did I
         *  miss just now", not "give me a logbook"; the diagnostics file already is the logbook. */

        /** How often the idle-tuner monitoring is re-checked (see [ensureEwsMonitoring]). Slow on
         *  purpose: its job is to close a start-up race and to advance a discovery sweep, neither of
         *  which is urgent, and each attempt physically retunes the stick. */
        const val EWS_MONITOR_TICK_MS = 15_000L

        /** How long a parked ensemble gets to produce a FIG 0/15 heartbeat before the discovery sweep
         *  writes it off as not EWS-capable. Heartbeats come ~1/s, so this is generous — it also has
         *  to cover the tuner actually settling on the new ensemble. */
        const val EWS_DISCOVERY_DWELL_MS = 20_000L

        /** Upper bound for an MCU band sweep if the box never sends a seek-end (safety net). */
        const val AUTOSCAN_TIMEOUT_MS = 90_000L
        /** No hit and no frequency movement for this long ⇒ the sweep is stuck; abort it. */
        const val SCAN_STALL_MS = 25_000L

        /** Listen to an FM station at least this long before the silent DAB probe runs — only offer
         *  when you've genuinely settled, never while tuning around (anti tuner-thrash, non-nagging). */
        /** How long the saved last-played station may hold up band reconciliation while it waits to
         *  appear in the list. Generous — a DAB scan can take a while — but finite. */
        const val AUTOPLAY_GRACE_MS = 90_000L

        const val FM_DAB_DWELL_MS = 30_000L

        /** How long before an FM station is probed for a DAB counterpart again. The car moves: a
         *  candidate that was out of range at the kerb is often solid a few minutes later, so a
         *  single "too weak" must not disable the offer for the rest of the session. */
        const val FM_DAB_RETRY_MS = 5 * 60_000L
        /** Signal bars (0–4) a linked DAB+ must reach before we offer/switch FM → DAB. */
        const val FM_TO_DAB_MIN_BARS = 3

        /** Never file more than this many FM/AM stations in a single scan. */
        const val MAX_SCAN_STATIONS = 60

        /** Cap on radio-browser simulcast lookups per run — one request each, so keep it a handful.
         *  Stations left over are picked up on a later run (the map only ever grows). */
        const val SIMULCAST_LOOKUP_LIMIT = 25

        const val TAG = "RadioViewModel"
    }
}
