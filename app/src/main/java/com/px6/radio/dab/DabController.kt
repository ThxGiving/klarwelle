package com.px6.radio.dab

import com.px6.radio.diag.Diag
import com.px6.radio.diag.DiagFile
import android.content.Context
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import android.util.Log
import com.px6.radio.audio.DabAudioSink
import com.px6.radio.model.Band
import com.px6.radio.model.DAB_BAND_III
import com.px6.radio.model.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.omri.radio.Radio
import org.omri.radio.RadioErrorCode
import org.omri.radio.RadioStatusListener
import org.omri.radio.impl.RadioImpl
import org.omri.radio.impl.RadioServiceDabImpl
import org.omri.radioservice.RadioService
import org.omri.radioservice.RadioServiceAudiodataListener
import org.omri.radioservice.RadioServiceDab
import org.omri.radioservice.RadioServiceFm
import org.omri.radioservice.metadata.Textual
import org.omri.radioservice.metadata.TextualDabDynamicLabel
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusContentType
import org.omri.radioservice.metadata.TextualMetadataListener
import org.omri.radioservice.metadata.TextualType
import org.omri.radioservice.metadata.Visual
import org.omri.radioservice.metadata.VisualMetadataListener
import org.omri.radioservice.metadata.VisualType
import org.omri.tuner.ReceptionQuality
import org.omri.tuner.Tuner
import org.omri.tuner.TunerListener
import org.omri.tuner.TunerStatus
import org.omri.tuner.TunerType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/** DAB->FM link (FIG 0/6 PI + FIG 0/21 frequency) for service following. */
data class FmLink(val freqKhz: Int, val pi: Int?)

/** Live DAB state exposed to the ViewModel. */
data class DabState(
    val tunerPresent: Boolean = false,
    val scanning: Boolean = false,
    val scanProgress: Int = 0,
    val stations: List<Station> = emptyList(),
    val nowPlayingId: String? = null,
    /** The service whose FIRST audio frame has actually played — i.e. it is now audible, not just
     *  selected/tuning. Lags [nowPlayingId] by the (cross-ensemble) tuner acquisition; drives the
     *  "tuning…" loader in the UI. */
    val audibleId: String? = null,
    val dls: String? = null,
    val dlTitle: String? = null,
    val dlArtist: String? = null,
    val slideshow: ByteArray? = null,
    val signalBars: Int = 0,
    /** EIds of ensembles observed to carry EWS (a FIG 0/15) — recorded during scanning and listening
     *  (ETSI TS 104 089 §7.2.3). Used for the "ASA" station badge and for parking the idle tuner. */
    val ewsEnsembleIds: Set<Int> = emptySet(),
    /** EId of the ensemble the tuner is physically on right now — the played DAB service, or the
     *  ensemble it's silently parked on for EWS while FM/Internet plays. Lets the ASA pill show amber
     *  (known EWS ensemble but heartbeat currently missing) vs red (not on an EWS ensemble at all). */
    val currentTunerEnsembleId: Int? = null,
    /** Native error surfaced to the UI (the C side now throws instead of crashing). */
    val error: String? = null,
)

/**
 * Bridges the ported omri-usb backend to the app. Owns the OMRI [Radio] lifecycle, drives
 * tuner init + service scan, tunes services, and fans metadata (DLS/DL+, slideshow) + PCM
 * (into [DabAudioSink]) out as [DabState].
 *
 * On the emulator there is no 16C0:05DC stick, so [DabState.tunerPresent] stays false and the
 * ViewModel keeps demo data. On the real Vivid with the stick, this delivers live services.
 */
class DabController(private val appContext: Context) :
    RadioStatusListener, TunerListener, com.px6.radio.following.DabFollowSource {

    private val radio: Radio = Radio.getInstance()
    /** For the scan watchdog only — cancelled in [release]. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val sink = DabAudioSink()
    private val services = ConcurrentHashMap<String, RadioServiceDab>()

    private val _state = MutableStateFlow(DabState())
    /** FIG 0/15 alert events (non-heartbeat) for the EWS engine — an event stream, not state, so the
     *  ViewModel can run matching + lifecycle without a heartbeat re-triggering it. extraBuffer so an
     *  emit from the omri callback thread never suspends/drops. */
    private val _ewsEvents = MutableSharedFlow<org.omri.tuner.DabEwsAlert>(extraBufferCapacity = 16)
    val ewsEvents: SharedFlow<org.omri.tuner.DabEwsAlert> = _ewsEvents.asSharedFlow()

    /**
     * One event per RECEPTION REPORT from the tuner, carrying the bar count.
     *
     * Service following debounces over "N weak readings in a row", so it needs to see each actual
     * measurement — including repeats of the same value. But this must NOT be a DabState field: the
     * reports stream continuously on DAB, and a field that changes every time makes the StateFlow
     * emit every time, which drags the ViewModel's whole onDab pipeline (link rebuild over the full
     * station list, autoplay, band reconcile) onto the main thread at that rate. That cost an ANR on
     * the device. Same reasoning as [ewsLastFrameElapsedMs]; an event stream is what this always was.
     * signalBars stays in DabState for the UI, where conflating equal values is exactly right.
     */
    private val _signalReports = MutableSharedFlow<Int>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val signalReports: SharedFlow<Int> = _signalReports.asSharedFlow()
    override val state: StateFlow<DabState> = _state.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var currentService: RadioServiceDab? = null

    /** The one service [serviceListener] is currently subscribed to, so it can be dropped again.
     *  Tracked explicitly because the service objects are process-global and outlive this
     *  controller — an un-dropped subscription keeps a released instance decoding. */
    @Volatile private var subscribedService: RadioServiceDab? = null
    // Set by the router just before a faded switch; the next PCM frame of the new service fades in.
    @Volatile private var fadeInArmed = false
    /** The station whose first PCM frame we are waiting for, or null. Its arrival means that station
     *  is audible, which clears the UI "tuning…" loader — independent of whether a fade is armed. Holding the ID (rather than a
     *  bare flag plus a read of nowPlayingId at frame time) is what makes the handshake race-free. */
    @Volatile private var awaitingFirstFrameFor: String? = null
    // App-computed DAB->FM links (implicit SId==PI linking), keyed by DAB station id. Fed by the VM
    // from the FM RDS data — this is how following gets an FM target in Germany, where the broadcast
    // FIG 0/6+0/21 link is absent. Kept current: the VM re-pushes on every RDS-PI / DAB change.
    @Volatile private var fmLinks: Map<String, FmLink> = emptyMap()
    /** elapsedRealtime of the last EWS *heartbeat* written to the diag file (see EWS_HEARTBEAT_LOG_
     *  INTERVAL_MS). Only ever touched from the omri callback thread. */
    @Volatile private var lastEwsHeartbeatLogMs = 0L
    /** Last copy of the native omri log to the stick — see rebuildStations. */
    @Volatile private var lastOmriLogCopyMs = 0L
    @Volatile private var ewsLastFrameMs = 0L

    /**
     * SystemClock.elapsedRealtime() of the last FIG 0/15 (DAB EWS) seen on the tuned ensemble, or 0
     * if none since the last tune. Deliberately NOT part of [DabState]: on an EWS ensemble it changes
     * once a second, and emitting a new state for it drove the ViewModel's whole onDab pipeline —
     * link rebuild, autoplay, band reconcile — at 1 Hz on the main thread. Nothing renders the raw
     * timestamp; the ASA status ticker polls it, so a field is all it ever needed to be.
     */
    val ewsLastFrameElapsedMs: Long get() = ewsLastFrameMs

    /** Log an omri step to logcat AND the diagnostics file on the stick, so the LAST step reached is
     *  visible after a native crash (a SIGSEGV kills the process before any Kotlin handler runs). */
    private fun omriStep(msg: String) {
        Log.i(TAG, "omri-step: $msg")
        runCatching {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            // writeNow: the point of this log is the LAST step before a native crash — a queued write
            // would still be in flight when the SIGSEGV takes the process down.
            Diag.writeNow(appContext, DiagFile.OMRI_INIT, "$ts $msg\n", append = true)
        }
    }

    /** Blocks up to ~5 s (NTP + logo restore) — call from a background thread. */
    fun initializeBlocking() {
        if (initialized) return
        omriStep("=== initializeBlocking begin (v${runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()}) ===")
        // First audible frame of a service → mark it audible (clears the UI "tuning…" loader) and
        // close the switch-timing timeline.
        sink.onAudibleFrame = {
            _state.update { it.copy(audibleId = it.nowPlayingId) }
            com.px6.radio.diag.SwitchTiming.done(appContext, "first DAB PCM frame — audible")
        }
        omriStep("radio.initialize() — native lib load + USB scan")
        val rc = radio.initialize(appContext)
        omriStep("initialize returned $rc")
        radio.registerRadioStatusListener(this)
        initialized = rc == RadioErrorCode.ERROR_INIT_OK
        omriStep("setupTuners (requests USB permission)")
        dabTuners().forEach(::setupTuner)   // triggers USB permission dialog; opens device async
        refreshTunerPresent()
        // Warm restart: on a relaunch with the process still alive the stick may already be open, so
        // no fresh INITIALIZED callback fires — the station list would stay empty until a manual scan.
        // Rebuild from any already-initialized tuner now (and scan only if it truly has no services).
        dabTuners().filter { it.tunerStatus == TunerStatus.TUNER_STATUS_INITIALIZED }.forEach { t ->
            rebuildStations(t)
            if (t.radioServices.isEmpty()) runCatching { t.startRadioServiceScan() }
        }
        omriStep("init done — waiting for tuner INITIALIZED before scan")
    }

    /** Scan only a fully opened (INITIALIZED) tuner — native scan on an unopened device crashes. */
    fun scan() {
        val tuner = dabTuners().firstOrNull { it.tunerStatus == TunerStatus.TUNER_STATUS_INITIALIZED }
        if (tuner == null) {
            omriStep("scan skipped — no INITIALIZED tuner yet (waiting for USB permission)")
            return
        }
        omriStep("startRadioServiceScan — FIC decode (incl. FIG 0/15) begins")
        try {
            tuner.startRadioServiceScan()
        } catch (t: Throwable) {
            reportError("Suchlauf: ${t.message}")
        }
    }

    private fun reportError(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(error = msg) }
    }

    /** True once the native tuner actually knows this service (scanned or restored) — i.e. it can play. */
    fun canTune(stationId: String): Boolean = services.containsKey(stationId)

    override fun tune(stationId: String) {
        val svc = services[stationId] ?: return
        // Cross-ensemble = the tuner must retune to a different DAB block (the slow case); same-ensemble
        // is just a service reselect. Time the omri call so we can see which dominates the handover.
        val prev = _state.value.nowPlayingId
        val crossEnsemble = prev == null || prev.substringBefore('.') != stationId.substringBefore('.')
        currentService = svc
        // Drop the previous service's subscription before taking a new one. Only the service we are
        // actually playing needs to feed us PCM/PAD, but nothing used to unsubscribe the old ones —
        // so every station ever tuned kept the listener. That leaks across ViewModels, because the
        // service objects are process-global (RadioServiceManager hands out the same instances): a
        // released controller stayed subscribed, its sink.write() found a released AudioTrack and
        // rebuilt one, and the same PCM played through two uncoordinated tracks.
        subscribedService?.takeIf { it !== svc }?.let { runCatching { it.unsubscribe(serviceListener) } }
        svc.subscribe(serviceListener)
        subscribedService = svc
        // Arm the first-frame handshake and publish the target BEFORE starting the service.
        //
        // This used to happen AFTER startRadioService returned, and it left a race the user actually
        // saw: PCM starts flowing on the omri read thread the moment the service starts, so a frame
        // could land in the window between `awaitingFirstFrame = true` and this state update. The
        // handler then stamped `audibleId = it.nowPlayingId` — still the OLD station — and consumed
        // the flag. Nothing retried, so the "tuning…" spinner sat on the tile until the 12 s safety
        // timeout while the new station was already audible. Remembering WHICH station we are waiting
        // for (instead of reading nowPlayingId at frame time) removes the race entirely.
        awaitingFirstFrameFor = stationId
        _state.update {
            // audibleId cleared: selected/tuning now, becomes audible on the first PCM frame.
            it.copy(nowPlayingId = stationId, audibleId = null,
                dls = null, dlTitle = null, dlArtist = null, slideshow = null,
                currentTunerEnsembleId = stationId.substringBefore('.').toIntOrNull(16))
        }
        com.px6.radio.diag.SwitchTiming.mark(appContext,
            "omri.startRadioService begin — ${if (crossEnsemble) "CROSS-ENSEMBLE retune" else "same ensemble"} (ens=${stationId.substringBefore('.')})")
        val t = android.os.SystemClock.elapsedRealtime()
        try {
            radio.startRadioService(svc)
        } catch (t2: Throwable) {
            reportError("Wiedergabe: ${t2.message}")
        }
        com.px6.radio.diag.SwitchTiming.mark(appContext,
            "omri.startRadioService returned after ${android.os.SystemClock.elapsedRealtime() - t}ms (audio follows on first PCM frame)")
        // On a cross-ensemble retune the EWS capability must be re-proven by the new ensemble's
        // FIG 0/15; a same-ensemble reselect keeps it (heartbeats refresh it within ~1 s anyway).
        if (crossEnsemble) ewsLastFrameMs = 0L
    }

    /**
     * The FM service linked to the currently playing DAB service via FIG 0/6 (PI) + FIG 0/21
     * (frequency) — the basis for DAB->FM service following. Returns null if nothing is linked
     * (or OMRI doesn't surface an FM entry for this ensemble — a device-verify point).
     */
    /** Update the app-computed DAB->FM links (implicit SId==PI). Called by the VM on every change. */
    fun setFmLinks(links: Map<String, FmLink>) { fmLinks = links }

    override fun getLinkedFm(): FmLink? {
        val svc = currentService ?: return null
        // Primary: the app's implicit SId==PI link (present in Germany, kept current from RDS).
        fmLinks[dabId(svc)]?.let { return it }
        // Opportunistic: omri's broadcast FIG 0/6+0/21 link — empty in Germany, may exist elsewhere.
        val impl = radio as? RadioImpl ?: return null
        val linked = try {
            impl.getFollowingServices(svc)
        } catch (t: Throwable) {
            Log.w(TAG, "getFollowingServices failed: ${t.message}"); return null
        } ?: return null
        val fm = linked.filterIsInstance<RadioServiceFm>().firstOrNull() ?: return null
        return FmLink(
            freqKhz = fm.frequency / 1000,               // OMRI reports Hz (99300000 = 99.3 MHz)
            pi = fm.rdsPiCode.let { if (it == -1) null else it },
        )
    }

    /**
     * The same programme carried in **other DAB ensembles** — the basis for DAB→DAB following.
     *
     * OMRI's `getFollowingServices` matches a DAB service to every other by Service Id + ECC
     * (`equalsRadioService`), across ensembles, so this is every alternative found during the
     * scan. Whether an alternative actually receives better here can only be known by tuning to
     * it — with a single tuner there is no parallel measurement — so the engine tries them one at
     * a time and moves on if a tried one is no better. Each entry is a tuneable station id.
     */
    override fun getLinkedDab(): List<String> {
        val svc = currentService ?: return emptyList()
        val impl = radio as? RadioImpl ?: return emptyList()
        val linked = try {
            impl.getFollowingServices(svc)
        } catch (t: Throwable) {
            Log.w(TAG, "getFollowingServices(DAB) failed: ${t.message}"); return emptyList()
        } ?: return emptyList()
        return linked.filterIsInstance<RadioServiceDab>().map { dabId(it) }
    }

    fun stop() {
        // Unsubscribe what we actually subscribed, not "whatever nowPlayingId names" — the two drift
        // apart (a silent EWS park moves the tuner without touching nowPlayingId).
        subscribedService?.let { runCatching { it.unsubscribe(serviceListener) } }
        subscribedService = null
        dabTuners().forEach { it.stopRadioService() }
        sink.release()
    }

    /** Handover fade hook (0.0..1.0). */
    fun setVolume(volume: Float) = sink.setVolume(volume)

    /** Drop queued PCM so a switch can't replay the old service's buffered tail (router calls this). */
    fun flushAudio() = sink.flush()

    /** Arm the fade-in: the next service's first PCM frame fades up from silence (router calls this). */
    fun primeFadeIn() { fadeInArmed = true }

    /**
     * Silently measure a service's reception without disturbing playback — used while FM is on (the
     * DAB tuner is free) to check whether a linked DAB+ station is strongly receivable before
     * offering/switching to it. Keeps the DAB output muted (FM owns the amp) and does NOT touch
     * [DabState.nowPlayingId] or subscribe metadata, so our logical "now playing" is unchanged.
     */
    /**
     * The station id (eid.sid) of the service in the CURRENTLY tuned ensemble whose audio component
     * sits on [subChId] — the EWS alert audio sub-channel (ETSI TS 104 089 §7.6.2). Returns null if
     * no known service carries it (then the caller keeps normal playback and only shows the overlay,
     * so a missing alert service never silences the radio). Restricted to the current ensemble so a
     * same-numbered sub-channel in another (scanned) ensemble can't be picked by mistake.
     */
    fun findServiceBySubChannel(subChId: Int): String? {
        // A sub-channel id is only meaningful WITHIN the ensemble the tuner is on, so the search has
        // to be scoped to that ensemble — and the authority for it is currentTunerEnsembleId (set by
        // tune/retuneSilently, confirmed by FIG 0/15's own tunedEnsembleId), NOT nowPlayingId.
        // nowPlayingId is the LOGICAL station and is deliberately left untouched while the tuner is
        // parked for EWS monitoring, so scoping by it searched the wrong ensemble and handed the
        // audio to an unrelated service that merely reused the same sub-channel number. Seen on air
        // (klarwelle-ews.txt, 15:29): parked on ensemble 10bc, alert on subch 13, handover went to
        // 10ec.db94 — a WDR 4 service in a completely different ensemble.
        val curEid = _state.value.currentTunerEnsembleId?.let { "%04x".format(it) }
            ?: _state.value.nowPlayingId?.substringBefore('.')
        return services.entries.firstOrNull { (id, svc) ->
            (curEid == null || id.substringBefore('.') == curEid) &&
                runCatching { svc.serviceComponents.any { it.subchannelId == subChId } }.getOrDefault(false)
        }?.key
    }

    /** Re-tune the native DAB tuner to a service without changing our logical now-playing/UI. */
    fun retuneSilently(serviceId: String) {
        val svc = services[serviceId] ?: return
        runCatching { setVolume(0f) }
        currentService = svc
        // Physically move the tuner but keep the logical now-playing untouched; record the ensemble so
        // the ASA pill reflects the parked (monitoring) ensemble even while FM/Internet is audible.
        _state.update { it.copy(currentTunerEnsembleId = serviceId.substringBefore('.').toIntOrNull(16)) }
        runCatching { radio.startRadioService(svc) }
    }

    fun release() {
        scope.cancel()
        // Full teardown, not just the AudioTrack: stop the omri DAB service + tuners so the native
        // decoder actually stops. Releasing only the sink left omri decoding on — the audio kept
        // playing after the Activity was gone, and a relaunch started a second service on top of it
        // (the "Sender läuft doppelt" bug).
        runCatching { stop() }
        // Drop the TUNER subscription too, not just the radio-status listener: FIG 0/15 (heartbeats
        // and EWS alerts), reception statistics and the scan callbacks all arrive through
        // TunerListener, which setupTuner subscribed. Leaving it subscribed kept a released
        // controller fully alive — after the Activity was finished (BACK) and a fresh ViewModel built
        // a second controller, BOTH were listening on the one stick. On the device that showed up as
        // heartbeats logged twice a minute in klarwelle-ews.txt (two independent 60 s throttles), and the
        // stale instance could still push EWS alerts into a UI that no longer existed.
        runCatching { dabTuners().forEach { it.unsubscribe(this) } }
        runCatching { radio.unregisterRadioStatusListener(this) }
        // NOT radio.deInitialize() here, deliberately.
        //
        // It looks like the right call: RadioImpl.initialize() is not idempotent (it appends another
        // TunerUsbImpl for the same stick on every call) and only deInitialize() clears that list.
        // But deInitialize() → deInitializeTuner() → UsbHelper.stopService() + removeDevice() tears
        // down NATIVE usb state, while UsbHelper itself stays a process-wide singleton. This runs on
        // every BACK, and whether the native side survives being re-attached afterwards cannot be
        // verified from here — no stick on the build machine. It was tried in v2.7.37 and DAB stopped
        // coming up on the device, so the cost of being wrong is the whole feature.
        //
        // The two unsubscribes above already fix the symptom that mattered (a released controller
        // still receiving callbacks and decoding). A growing tuner list is untidy, not audible.
    }

    private fun dabTuners(): List<Tuner> =
        radio.getAvailableTuners(TunerType.TUNER_TYPE_DAB) ?: emptyList()

    private fun setupTuner(tuner: Tuner) {
        tuner.subscribe(this)
        radio.initializeTuner(tuner)   // async; readiness arrives via tunerStatusChanged
    }

    private fun refreshTunerPresent() =
        _state.update { it.copy(tunerPresent = dabTuners().isNotEmpty()) }

    private fun rebuildStations(tuner: Tuner) {
        val dab = tuner.radioServices.filterIsInstance<RadioServiceDab>()
        dab.forEach { services[dabId(it)] = it }
        // Two services can carry the same ensemble+service id (a secondary component, or the same
        // service surfaced twice) — keep the first. A duplicate id would crash the LazyColumn,
        // which requires unique keys (seen on the device: "Key 11f7.1259 was already used").
        val built = dab.map { s ->
            val st = s.toStation()
            // Broadcast DAB->FM link, resolved natively at scan time (FIG 0/6 SId->PI joined with
            // FIG 0/21 PI->FM frequency, both in kHz). Empty in Germany (implicit SId==PI linking is
            // not broadcast) — there the app fills the link itself via RadioLogic.linkDabToFm. Where a
            // broadcaster does signal it (UK/CH), this is the authoritative link.
            val nativeImpl = s as? RadioServiceDabImpl
            val fmFreqKhz = nativeImpl?.linkedFmFrequency ?: 0
            val fmPi = nativeImpl?.linkedFmPi ?: 0
            if (fmFreqKhz > 0 || fmPi > 0) st.copy(
                linkedFmFrequencyKhz = fmFreqKhz.takeIf { it > 0 },
                linkedFmPi = fmPi.takeIf { it > 0 },
            ) else st
        }.distinctBy { it.id }
        _state.update { it.copy(stations = built) }
        // Log which services carry a *native broadcast* FIG 0/6+0/21 FM link (JNI bridge). This is 0
        // in Germany; the app's heuristic SId==PI links are logged separately in klarwelle-links.txt.
        runCatching {
            val withFm = built.filter { it.linkedFmFrequencyKhz != null }
            val text = buildString {
                append("NATIV (Broadcast FIG 0/6+0/21) DAB services: ").append(built.size)
                append(" · mit nativem FM-Link: ").append(withFm.size).append('\n')
                withFm.forEach { st ->
                    append("  ").append(st.name).append(" -> FM ")
                    append("%.1f".format((st.linkedFmFrequencyKhz ?: 0) / 1000.0))
                    st.linkedFmPi?.let { append(" · PI 0x%04X".format(it)) }
                    append('\n')
                }
            }
            Diag.write(appContext, DiagFile.DABLINKS, text)
        }
        // Copy the native omri log (std::cout tee, written to internal storage) onto the stick, so the
        // FIC/FIG/tuner/scan native output is readable without adb.
        //
        // Throttled, because rebuildStations runs from tunerScanServiceFound — once per service, i.e.
        // 200+ times during a band scan — and this reads the WHOLE native log into a String on the
        // omri USB read thread, the same loop that pulls FIC/MSC data off the stick. That is the
        // thread whose starvation caused the original ANR. In a debug build the native log is capped
        // at 8 MB, so it was up to 8 MB read per found service.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOmriLogCopyMs >= OMRI_LOG_COPY_INTERVAL_MS) {
            lastOmriLogCopyMs = now
            runCatching {
                val internal = java.io.File(appContext.filesDir, DiagFile.OMRI.fileName)
                if (internal.exists()) Diag.write(appContext, DiagFile.OMRI, internal.readText())
            }
        }
    }

    // ---- RadioStatusListener ----
    override fun tunerAttached(attachedTuner: Tuner) {
        if (attachedTuner.tunerType == TunerType.TUNER_TYPE_DAB) setupTuner(attachedTuner)
        refreshTunerPresent()
    }

    override fun tunerDetached(detachedTuner: Tuner) = refreshTunerPresent()

    /** The one automatic scan of a session (see [tunerStatusChanged]). */
    @Volatile private var autoScanned = false

    // ---- TunerListener ----
    override fun tunerStatusChanged(tuner: Tuner, newStatus: TunerStatus) {
        Log.i(TAG, "omri-step: tunerStatusChanged -> $newStatus")
        if (newStatus == TunerStatus.TUNER_STATUS_INITIALIZED) {
            // Device is open now — safe to read services and (auto) scan.
            rebuildStations(tuner)
            // First start with an empty tuning memory: scan once, on our own. Once only — an empty
            // RESULT (no antenna, no coverage) must not trigger the next scan from inside the
            // end-of-scan callback; that re-entry raced the tuner's thread hand-over and aborted
            // the process. The user can rescan from the settings at any time.
            if (tuner.radioServices.isEmpty() && !autoScanned) {
                autoScanned = true
                try {
                    tuner.startRadioServiceScan()
                } catch (t: Throwable) {
                    reportError("Auto-Suchlauf: ${t.message}")
                }
            }
        }
    }

    /**
     * Restore the remembered EWS-capable ensembles (see RadioStore.ewsEnsembleIds) so the "ASA" badge
     * and idle-tuner parking are live immediately after a restart. Only seeds — never overwrites what
     * this session has already observed on air, which is always the better evidence.
     */
    fun seedEwsEnsembleIds(ids: Set<Int>) =
        _state.update { it.copy(ewsEnsembleIds = it.ewsEnsembleIds + ids) }

    /**
     * Stop a scan the tuner has gone quiet on: no progress step or found service for
     * [SCAN_STALL_MS]. Left alone, a stalled omri scan kept `scanning` true forever — button
     * disabled, pill lit, nothing happening. The user can start again; the tuner stays usable.
     */
    fun cancelScan(reason: String) {
        omriStep("scan cancelled: $reason")   // the why goes to the diag file, not the screen
        val tuner = dabTuners().firstOrNull()
        runCatching { tuner?.stopRadioServiceScan() }
        _state.update { it.copy(scanning = false) }
        reportError("Suchlauf abgebrochen")
    }

    private var scanWatchdog: kotlinx.coroutines.Job? = null
    private fun kickScanWatchdog() {
        scanWatchdog?.cancel()
        scanWatchdog = scope.launch {
            kotlinx.coroutines.delay(SCAN_STALL_MS)
            if (_state.value.scanning) cancelScan("keine Rückmeldung vom Tuner seit ${SCAN_STALL_MS / 1000} s")
        }
    }

    override fun tunerScanStarted(tuner: Tuner) =
        // A scan re-derives the ensemble landscape, so the EWS set is rebuilt with it: an ensemble
        // that no longer signals FIG 0/15 must be able to drop out again, which a purely additive set
        // (restored from disk, then only ever added to) could never do.
        _state.update { it.copy(scanning = true, scanProgress = 0, ewsEnsembleIds = emptySet()) }.also { kickScanWatchdog() }

    override fun tunerScanProgress(tuner: Tuner, percentScanned: Int) {
        _state.update { it.copy(scanProgress = percentScanned) }
        kickScanWatchdog()
    }

    override fun tunerScanFinished(tuner: Tuner) {
        scanWatchdog?.cancel()
        _state.update { it.copy(scanning = false) }
        rebuildStations(tuner)
    }

    override fun tunerScanServiceFound(tuner: Tuner, foundService: RadioService) {
        rebuildStations(tuner)
        kickScanWatchdog()
    }

    override fun radioServiceStarted(tuner: Tuner, startedRadioService: RadioService) {
        // Listener subscription happens in tune().
    }

    override fun radioServiceStopped(tuner: Tuner, stoppedRadioService: RadioService) {}

    override fun tunerReceptionStatistics(tuner: Tuner, rfLock: Boolean, quality: ReceptionQuality) {
        val bars = quality.toBars()
        // Conflated: only a CHANGED bar count redraws the meter.
        _state.update { it.copy(signalBars = bars) }
        // Every report, changed or not — following counts measurements, not changes.
        _signalReports.tryEmit(bars)
    }

    override fun tunerRawData(tuner: Tuner, data: ByteArray) {}

    /**
     * FIG 0/15 — DAB Emergency Warning System (ASA). M1: decode-and-log only, so we can capture what
     * EWS signalling actually arrives on the device (heartbeats now, real alerts once ASA DE goes
     * live) as ground truth before the alert engine is built. No playback/handover happens yet.
     */
    override fun tunerDabEwsAlert(tuner: Tuner, alert: org.omri.tuner.DabEwsAlert) {
        // Any FIG 0/15 (heartbeat included) proves the tuned ensemble participates in the EWS — stamp
        // the time so the ViewModel can show the mandatory "ASA active/inactive" status (§7.2.1/§5.3),
        // and record the ensemble as EWS-capable (§7.2.3) if not already known (0xFFFF = EId unknown).
        val eid = alert.tunedEnsembleId
        ewsLastFrameMs = android.os.SystemClock.elapsedRealtime()
        _state.update {
            val ids = if (eid != 0xFFFF && eid !in it.ewsEnsembleIds) it.ewsEnsembleIds + eid else it.ewsEnsembleIds
            // tunedEnsembleId is the authoritative "where the tuner really is" — trust it over tune().
            val cur = if (eid != 0xFFFF) eid else it.currentTunerEnsembleId
            // Same-instance return when nothing changed: StateFlow then emits nothing, so the steady
            // heartbeat stream costs no recomposition at all (see [ewsLastFrameElapsedMs]).
            if (ids === it.ewsEnsembleIds && cur == it.currentTunerEnsembleId) it
            else it.copy(ewsEnsembleIds = ids, currentTunerEnsembleId = cur)
        }
        // Heartbeats are frequent (once per second on an EWS ensemble). Logging every one is what made
        // this callback expensive: it runs on the omri USB read thread — the same loop that pulls FIC
        // and MSC data off the stick — so a per-second diagnostic write starved the very heartbeats it
        // was recording (the ASA pill never settled green) and, through Diag's process-wide lock, hung
        // the UI thread into an ANR. Keep the ground truth without the flood: every alert is logged,
        // heartbeats at most once a minute (the first one lands immediately, so "we are hearing an EWS
        // ensemble" is still visible in the file).
        val heartbeat = alert.isHeartbeat
        val now = android.os.SystemClock.elapsedRealtime()
        val logHeartbeat = heartbeat && now - lastEwsHeartbeatLogMs >= EWS_HEARTBEAT_LOG_INTERVAL_MS
        if (logHeartbeat) lastEwsHeartbeatLogMs = now
        if (!heartbeat || logHeartbeat) {
            runCatching {
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                Diag.write(appContext, DiagFile.EWS, "$ts ${alert.description}\n", append = true)
            }
        }
        if (!heartbeat) {
            Log.i(TAG, "EWS FIG0/15: ${alert.description}")
            _ewsEvents.tryEmit(alert)   // hand real alerts (Trigger/Sustain/End) to the EWS engine
        }
    }

    // ---- Service metadata + audio ----
    private val serviceListener = object :
        RadioServiceAudiodataListener, TextualMetadataListener, VisualMetadataListener {

        override fun pcmAudioData(pcmData: ByteArray, numChannels: Int, samplingRate: Int) {
            // First real frame of a freshly-tuned service → it is now audible: clear the UI loader and
            // close the switch timing. Independent of the fade (which may be off), so the "tuning…"
            // spinner no longer lingers until the safety timeout while sound is already playing.
            val target = awaitingFirstFrameFor
            if (target != null) {
                awaitingFirstFrameFor = null
                _state.update { it.copy(audibleId = target) }
                com.px6.radio.diag.SwitchTiming.done(appContext, "first DAB PCM frame — audible")
            }
            // First real frame of a freshly-tuned service — fade it in now (not during the silent
            // tuner acquisition, which is why the ramp is armed here rather than run at switch time).
            if (fadeInArmed) {
                fadeInArmed = false
                sink.fadeIn()
            }
            sink.write(pcmData, numChannels, samplingRate)
        }

        override fun newTextualMetadata(textualMetadata: Textual) {
            if (textualMetadata.type != TextualType.METADATA_TEXTUAL_TYPE_DAB_DLS) return
            var title: String? = null
            var artist: String? = null
            (textualMetadata as? TextualDabDynamicLabel)?.dlPlusItems?.forEach { item ->
                when (item.dynamicLabelPlusContentType) {
                    TextualDabDynamicLabelPlusContentType.ITEM_TITLE -> title = item.dlPlusContentText
                    TextualDabDynamicLabelPlusContentType.ITEM_ARTIST -> artist = item.dlPlusContentText
                    else -> {}
                }
            }
            _state.update { it.copy(dls = textualMetadata.text, dlTitle = title, dlArtist = artist) }
        }

        override fun newVisualMetadata(visualMetadata: Visual) {
            if (visualMetadata.visualType == VisualType.METADATA_VISUAL_TYPE_DAB_SLS) {
                _state.update { it.copy(slideshow = visualMetadata.visualData) }
            }
        }
    }

    private companion object {
        const val TAG = "DabController"
        /** A healthy scan steps every ~2 s; this much silence means the tuner is stuck. */
        const val SCAN_STALL_MS = 25_000L

        /** How often an EWS *heartbeat* may be written to klarwelle-ews.txt. Alerts are never rate-limited;
         *  heartbeats arrive ~1/s and only need to prove the ensemble is still signalling. */
        /** At most one native-log copy per this interval; a scan otherwise did it 200+ times. */
        const val OMRI_LOG_COPY_INTERVAL_MS = 30_000L
        const val EWS_HEARTBEAT_LOG_INTERVAL_MS = 60_000L

        val PALETTE = listOf(
            0xFF25C26AL to 0xFF0E7A44L,
            0xFF37C7F2L to 0xFF1665A3L,
            0xFFF2A33CL to 0xFFB06A12L,
            0xFFFF6B6BL to 0xFFA52020L,
            0xFF9B8CFFL to 0xFF5236B5L,
        )

        fun dabId(s: RadioServiceDab): String = "%04x.%04x".format(s.ensembleId, s.serviceId)

        fun RadioServiceDab.toStation(): Station {
            val id = dabId(this)
            val label = serviceLabel?.trim().orEmpty()
            val ens = ensembleLabel?.trim().orEmpty()
            val bitrate = serviceComponents.firstOrNull { it.isPrimary }?.bitrate
                ?: serviceComponents.firstOrNull()?.bitrate
            // Secondary components that carry their own label are the extra channels ("Zusatz-
            // sender"). Data-only components (slideshow, EPG) have no label and are left out.
            val secondaries = serviceComponents
                .filterNot { it.isPrimary }
                .mapNotNull { it.label?.trim()?.takeIf { l -> l.isNotEmpty() } }
                .distinct()
            val (a, b) = PALETTE[id.hashCode().absoluteValue % PALETTE.size]
            // The multiplex frequency lets us place the service on the real channel table
            // (5A..13F) instead of guessing where it sits on the band.
            val freq = runCatching { ensembleFrequency }.getOrDefault(0).takeIf { it > 0 }
            val channel = freq?.let { f -> DAB_BAND_III.minBy { c -> kotlin.math.abs(c.khz - f) } }
            return Station(
                id = id,
                name = label.ifEmpty { "DAB $id" },
                band = Band.DAB,
                subtitle = buildString {
                    channel?.let { append("Kanal ").append(it.name).append(" · ") }
                    append("Ensemble ").append(ens.ifEmpty { "?" })
                    bitrate?.let { append(" · ").append(it).append(" kbit/s") }
                },
                logoInitials = label.take(2).uppercase().ifBlank { "DA" },
                logoStart = a, logoEnd = b,
                ensemble = ens.ifEmpty { null },
                // Extended Country Code (FIG 0/9) for the RadioDNS gcc — kept so logo lookup works
                // abroad, not just in Germany. omri defaults it to 0xFF when unknown.
                ecc = runCatching { ensembleEcc }.getOrNull()?.takeIf { it in 0..0xFE },
                bitrateKbps = bitrate,
                frequencyKhz = freq,
                secondaryLabels = secondaries,
            )
        }

        fun ReceptionQuality.toBars(): Int = when (this) {
            ReceptionQuality.NO_SIGNAL -> 0
            ReceptionQuality.BAD -> 1
            ReceptionQuality.POOR -> 1
            ReceptionQuality.OKAY -> 2
            ReceptionQuality.GOOD -> 3
            ReceptionQuality.BEST -> 4
        }
    }
}
