package com.px6.radio.following

import android.util.Log
import com.px6.radio.audio.AudioRouter
import com.px6.radio.model.FollowingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows about following: current mode, a short handover log, and reception. */
data class FollowingUiState(
    val following: FollowingState = FollowingState.DAB_PRIMARY,
    val log: List<String> = emptyList(),
    /**
     * Muted because the DAB service is too weak and no linked FM station could take over. The
     * original does exactly this rather than playing noise.
     */
    val muted: Boolean = false,
    /** No DAB reception at all here — worth telling the user, it is not our fault. */
    val noDabCoverage: Boolean = false,
    /**
     * On the FM fallback but the linked station won't lock its RDS — i.e. FM reception here is too
     * poor to be useful (a DAB-linked FM station always carries RDS, so "no lock" means bad SNR, not
     * "no RDS"). Surfaced so the UI can say "kein Empfang" instead of playing static silently.
     */
    val fmWeak: Boolean = false,
)

/**
 * DAB+ -> FM service following.
 *
 * Watches DAB reception; when it drops below the configured threshold (with hysteresis +
 * debounce against ping-pong) it looks up the FM service linked via FIG 0/6 (PI) + FIG 0/21
 * (frequency) through [DabController.getLinkedFm], tunes the CarManager FM tuner there, confirms
 * the RDS-PI matches, and crossfades (fading the DAB audio out via [DabController.setVolume] while
 * FM takes over the amp). When DAB recovers it fades back.
 *
 * **Gated on FM availability**: without a bound [FmController] (pure DAB+ device) following is
 * inert — [configure] with a null/unavailable FM keeps it on DAB forever.
 *
 * Content is not time-aligned (DAB is delayed vs. FM); the switch is deliberately made during the
 * DAB dropout, where the jump is least audible. See docs/findings-omri.md.
 */
class ServiceFollowingEngine(
    private val dab: DabFollowSource,
    private val fm: FmFollowSource?,
    private val router: AudioRouter,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FollowingUiState())
    val state: StateFlow<FollowingUiState> = _state.asStateFlow()

    // The tested decision logic; this class only carries out what it returns.
    private val decider = FollowingDecider(DEBOUNCE)
    private var deadCount = 0
    // Separate mute path for a pure-DAB device (no FM to hand over to): mute only on a truly dead
    // signal, not on a merely weak one, since there is no better alternative to switch to.
    private var noFmMuteCount = 0

    @Volatile private var enabled = false
    @Volatile private var threshold = 2
    @Volatile private var softFade = true
    @Volatile private var dabDab = true
    @Volatile private var serviceFollowingToFm = false
    // IP simulcast fallback (DAB->FM->IP). Active when service following is on and RadioDNS is
    // enabled (the data-saver setting). The stream URL per DAB station comes from [streamProvider],
    // set by the ViewModel from the RadioDNS-resolved bearers.
    @Volatile private var serviceFollowingToIp = false
    @Volatile private var streamProvider: (String) -> String? = { null }
    // FM-quality gate for the "IP before *weak* FM" refinement: after handing over to FM we watch
    // whether it locks the expected RDS PI within a short settle window; if not, FM is weak/wrong
    // and — when an IP simulcast exists — we escalate FM->IP for constant digital quality.
    @Volatile private var expectedFmPi: Int? = null
    private var fmSettle = 0
    // The band the user is on. Following only owns the tuner while the user is on DAB; once they
    // pick FM/AM by hand, the analog tuner is theirs and following must not retune it underneath.
    @Volatile private var userOnDab = true
    // Ensembles already tried in the current weak spell, so DAB->DAB does not cycle the same
    // alternatives forever. Cleared once reception is good again.
    private val triedDabAlts = mutableSetOf<String>()

    val fmAvailable: Boolean get() = fm?.available == true

    /** Apply settings. If following is switched off (or FM gone) while on FM, revert to DAB. */
    /**
     * Apply settings. Following is active when either DAB->FM (needs FM) or DAB->DAB is enabled;
     * DAB->DAB works on a pure-DAB device too, so it does not depend on the FM tuner.
     */
    fun configure(
        enabled: Boolean,
        threshold: Int,
        softFade: Boolean,
        dabDab: Boolean = this.dabDab,
        ipEnabled: Boolean = this.serviceFollowingToIp,
        ipBeforeFm: Boolean = decider.ipBeforeFm,
    ) {
        decider.ipBeforeFm = ipBeforeFm
        this.dabDab = dabDab
        this.serviceFollowingToFm = enabled && fmAvailable
        this.serviceFollowingToIp = enabled && ipEnabled
        // Following runs if it can hand over anywhere (FM or IP) or do DAB->DAB.
        this.enabled = (enabled && (fmAvailable || this.serviceFollowingToIp)) || dabDab
        this.threshold = threshold.coerceIn(1, 4)
        this.softFade = softFade
        if (!this.enabled && decider.mode != FollowingDecider.Mode.ON_DAB) {
            scope.launch { revertImmediately() }
        }
    }

    /** Supplies the RadioDNS IP simulcast URL for a DAB station id (null = none). */
    fun setStreamProvider(provider: (String) -> String?) { streamProvider = provider }

    /**
     * The user switched bands by hand. Reset the state machine so a later return to DAB starts
     * clean, and — when they leave DAB — clear any FM-fallback readout the engine had set. Never
     * touches the router: the manual `play`/`selectBand` already routed audio where it belongs.
     */
    fun setUserBand(isDab: Boolean) {
        userOnDab = isDab
        decider.reset()
        triedDabAlts.clear()
        if (_state.value.following != FollowingState.DAB_PRIMARY || _state.value.muted || _state.value.fmWeak) {
            _state.update { it.copy(following = FollowingState.DAB_PRIMARY, muted = false, fmWeak = false) }
        }
    }

    /** Collect DAB reception forever; drives the state machine. */
    suspend fun run() {
        // One tick per RECEPTION REPORT, not per state emission. DabState changes for all sorts of
        // reasons (a DLS line, a slideshow image, scan progress); feeding those to the decider let a
        // single momentary dip be counted as several "consecutive weak readings" and hand over to FM
        // while reception was actually fine — then bounce straight back.
        dab.signalReports.collect { bars -> onSignal(bars, dab.state.value.nowPlayingId) }
    }

    /** Test-only trace hook (null in production): every reading with the flags that drove it. */
    var debugTrace: ((String) -> Unit)? = null

    private suspend fun onSignal(bars: Int, dabNowId: String?) {
        debugTrace?.invoke("onSignal bars=$bars id=$dabNowId userOnDab=$userOnDab enabled=$enabled toFm=$serviceFollowingToFm toIp=$serviceFollowingToIp mode=${decider.mode}")
        // Hands off while the user is on a manually chosen analog station — following must not seize
        // or retune the tuner they picked. Resumes the moment they go back to DAB (setUserBand).
        if (!userOnDab) return
        trackCoverage(bars, dabNowId)
        // Bail when following is off or there is no DAB service playing; a dead signal still mutes.
        if (!enabled || dabNowId == null) {
            if (dabNowId != null) muteOrRecover(bars)
            return
        }
        // Only look up alternatives while on DAB — that is the only state a switch can start from.
        val onDab = decider.mode == FollowingDecider.Mode.ON_DAB
        val dabAlt = if (onDab && dabDab) {
            dab.getLinkedDab().firstOrNull { it != dabNowId && it !in triedDabAlts }
        } else null
        val fmLink = if (onDab && serviceFollowingToFm) dab.getLinkedFm() else null
        // IP availability is needed both to hand over from DAB and to escalate from a poor FM, so
        // it is not gated on being on DAB.
        val ipUrl = if (serviceFollowingToIp) streamProvider(dabNowId) else null
        val fmPoor = fmTurnedPoor()
        // Display proxy for "kein Empfang" on the FM fallback. fmPoor already means: on FM, settled,
        // and the expected RDS PI never locked. We additionally require *mono* — a well-received FM
        // that simply carries no RDS still comes in stereo, so it is NOT flagged as dead (the
        // "Antenne Unna sendet evtl. kein RDS" case). Mono + no PI = genuinely unreceivable static.
        val fmWeak = fmPoor && fm?.state?.value?.stereo != true
        if (_state.value.fmWeak != fmWeak) _state.update { it.copy(fmWeak = fmWeak) }

        val decision = decider.onSignal(
            bars, threshold,
            hasDabAlternative = dabAlt != null,
            hasLinkedFm = fmLink != null,
            hasIpStream = ipUrl != null,
            fmPoor = fmPoor,
        )
        when (decision) {
            FollowingDecider.Decision.STAY -> {
                // Good reception ends the weak spell; forget which alternatives we tried.
                if (bars > threshold && triedDabAlts.isNotEmpty()) triedDabAlts.clear()
            }
            FollowingDecider.Decision.SWITCH_DAB -> switchDab(dabAlt!!)
            FollowingDecider.Decision.HANDOVER -> handover(fmLink!!)
            FollowingDecider.Decision.HANDOVER_IP -> handoverIp(ipUrl!!)
            FollowingDecider.Decision.RETURN_TO_DAB -> returnToDab()
            FollowingDecider.Decision.MUTE -> mute()
            FollowingDecider.Decision.UNMUTE -> unmute()
        }
    }

    /**
     * IP tier: DAB is too weak and no usable FM link exists, but the station has a RadioDNS internet
     * simulcast. Fade DAB down and hand over to the stream (the DAB tuner keeps monitoring so we can
     * return once reception recovers). Higher latency than DAB<->FM, so this is the last audible tier.
     */
    /**
     * True once we've been on FM long enough to judge it and it still hasn't locked the expected
     * RDS PI — a reliable, scale-independent proxy for weak/wrong FM (RDS decodes only at decent
     * SNR, and every DAB-linked FM station carries it). Drives the FM->IP escalation.
     *
     * Note: we can't use raw field strength here. The MCU only attaches `strength` to `seek_found`/
     * `seek_found_auto` events (confirmed in the factory HCT4Radio RadioService — it stores it per
     * scanned preset to sort by signal); there is NO continuous RSSI while tuned, and our fallback
     * direct-tunes rather than seeks, so no `strength` ever arrives. RDS-lock (+ mono, for display)
     * is the only signal we actually get.
     */
    private fun fmTurnedPoor(): Boolean {
        if (decider.mode != FollowingDecider.Mode.ON_FM) { fmSettle = 0; return false }
        if (++fmSettle < FM_SETTLE) return false        // give FM a moment to lock RDS
        val piOk = expectedFmPi != null && fm?.state?.value?.pi == expectedFmPi
        return !piOk
    }

    private suspend fun handoverIp(url: String) {
        val fromFm = _state.value.following == FollowingState.FM_FALLBACK
        if (_state.value.muted) unmute()
        log(if (fromFm) "FM schwach → Internet-Stream (RadioDNS)" else "DAB schwach → Internet-Stream (RadioDNS)")
        if (softFade) fadeDab(1f, 0f)
        router.toIp(url)
        _state.update { it.copy(following = FollowingState.IP_FALLBACK, fmWeak = false) }
    }

    /**
     * DAB→DAB: follow the same programme into another ensemble, staying digital. We cannot know in
     * advance whether it receives better (single tuner), so we tune there and keep watching; if it
     * is no better the next weak reading tries the next alternative, and when they are exhausted
     * the decider falls through to FM or silence.
     */
    private suspend fun switchDab(stationId: String) {
        triedDabAlts.add(stationId)
        log("DAB schwach → gleiches Programm in anderem Ensemble")
        router.toDab { dab.tune(stationId) }
        _state.update { it.copy(following = FollowingState.DAB_PRIMARY) }
    }

    private suspend fun handover(link: com.px6.radio.dab.FmLink) {
        if (_state.value.muted) unmute()
        // Arm the FM-quality watch: we expect this PI to lock shortly; if it doesn't, FM is poor.
        expectedFmPi = link.pi
        fmSettle = 0
        log("DAB schwach → FM ${fmtFreq(link.freqKhz)} (PI ${fmtPi(link.pi)})")
        // Fade DAB down while it is still the audible source, then let the router hand the
        // amplifier to FM. The router is the mutex: after this, DAB cannot be heard.
        if (softFade) fadeDab(1f, 0f)
        router.toAnalog { fm?.tune(link.freqKhz) }
        _state.update { it.copy(following = FollowingState.FM_FALLBACK) }
        confirmPi(link.pi)
    }

    private suspend fun returnToDab() {
        triedDabAlts.clear()
        expectedFmPi = null
        fmSettle = 0
        log("DAB erholt → zurück auf DAB")
        // The DAB service kept running the whole time, only silenced — the router takes the amp
        // back from FM and makes DAB audible again.
        router.toDab { }
        _state.update { it.copy(following = FollowingState.DAB_PRIMARY, fmWeak = false) }
    }

    /**
     * Silence when nothing is receivable, sound again when the signal comes back. Used on the
     * path where following cannot help — no FM tuner, or no linked station.
     */
    private suspend fun muteOrRecover(bars: Int) {
        if (bars <= 0) {
            if (++noFmMuteCount >= DEBOUNCE) mute()
        } else {
            noFmMuteCount = 0
            unmute()
        }
    }

    private suspend fun mute() {
        if (_state.value.muted) return
        router.setDabVolumeIfActive(0f)
        log("Stumm – weder DAB noch FM empfangbar")
        _state.update { it.copy(muted = true) }
    }

    private suspend fun unmute() {
        if (!_state.value.muted) return
        router.setDabVolumeIfActive(1f)
        log("Empfang zurück – Ton wieder an")
        _state.update { it.copy(muted = false) }
    }

    /**
     * No DAB reception at all, sustained. Distinct from a weak service: it means this area has no
     * coverage, which the original also shows as its own symbol.
     */
    private fun trackCoverage(bars: Int, dabNowId: String?) {
        if (bars <= 0 && dabNowId != null) {
            if (++deadCount >= COVERAGE_DEBOUNCE && !_state.value.noDabCoverage) {
                _state.update { it.copy(noDabCoverage = true) }
            }
        } else {
            deadCount = 0
            if (_state.value.noDabCoverage) _state.update { it.copy(noDabCoverage = false) }
        }
    }

    private suspend fun revertImmediately() {
        router.toDab { }
        decider.reset()
        triedDabAlts.clear()
        _state.update { it.copy(following = FollowingState.DAB_PRIMARY) }
    }

    /** Confirm the FM RDS-PI matches the DAB-linked PI ("same station"). Best-effort. */
    private fun confirmPi(expected: Int?) {
        expected ?: return
        val actual = fm?.state?.value?.pi ?: return
        log(
            if (actual == expected) "PI-Match ✓ (0x%04X)".format(actual)
            else "PI weicht ab: FM 0x%04X ≠ DAB 0x%04X".format(actual, expected)
        )
    }

    private suspend fun fadeDab(from: Float, to: Float) {
        val steps = 10
        for (i in 0..steps) {
            router.setDabVolumeIfActive(from + (to - from) * i / steps)
            delay(FADE_STEP_MS)
        }
    }

    private fun fmtFreq(khz: Int) = "%.1f MHz".format(khz / 1000.0)
    private fun fmtPi(pi: Int?) = pi?.let { "0x%04X".format(it) } ?: "—"

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update { it.copy(log = (it.log + msg).takeLast(6)) }
    }

    private companion object {
        const val TAG = "ServiceFollowing"
        const val DEBOUNCE = 2       // consecutive readings before switching (anti ping-pong)
        const val FM_SETTLE = 3      // readings on FM before judging it poor (RDS-lock grace window)
        const val COVERAGE_DEBOUNCE = 6  // longer: "no coverage here" should not flicker
        const val FADE_STEP_MS = 30L
    }
}
