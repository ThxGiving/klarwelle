package com.px6.radio.ews

import com.px6.radio.model.EwsAlertUi
import com.px6.radio.model.Settings
import com.px6.radio.model.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.omri.tuner.DabEwsAlert

/**
 * The ASA / EWS alert lifecycle (ETSI TS 104 089 §7.5 matching + §7.6 presentation): which FIG 0/15
 * frames become an alert on screen, when the audio hands over to the alert sub-channel and back, how
 * a user dismissal and the End phase are honoured, and when a silent broadcast times out.
 *
 * Pure state machine over a narrow [Host] — the ViewModel implements the host with the real tuner,
 * router and state flow; tests drive it with a fake and a virtual clock. Everything here runs on the
 * caller's thread (the tuner callback) and touches the UI only through [Host.updateAlert].
 */
class EwsAlertEngine(
    private val scope: CoroutineScope,
    private val host: Host,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val historyMax: Int = DEFAULT_HISTORY_MAX,
    /** Monotonic milliseconds — `SystemClock.elapsedRealtime()` in the app, a counter in tests. */
    private val now: () -> Long,
) {

    /** What the engine needs from the radio. Every member is cheap and non-blocking. */
    interface Host {
        val settings: Settings
        val stations: List<Station>
        /** Location code derived from GPS, or null when unknown/off. */
        val gpsCode: EwsMatcher.Code?
        /** The alert currently on screen, if any. */
        val alert: EwsAlertUi?
        /** Atomically replace the alert on screen (null = none). */
        fun updateAlert(transform: (EwsAlertUi?) -> EwsAlertUi?)
        /** Prepend one line to the presentation history, keeping at most [historyMax]. */
        fun addHistory(line: String, max: Int)
        /** Id of the station the user is listening to, or null. */
        val nowPlayingStationId: String?
        /** Resolve a tuned-ensemble sub-channel to a station id from tuning memory. */
        fun findServiceBySubChannel(subCh: Int): String?
        /** True when [serviceId] is what the amplifier is actually playing right now (not just tuned). */
        fun isAudiblyPlaying(serviceId: String): Boolean
        /** Route audio to the alert service and hold it there (no following pulls away). */
        fun playAlertService(serviceId: String)
        /** Ordinary play path — restores band and route after an alert. */
        fun playStation(station: Station)
        /** Bring the app to the front for a new alert. */
        fun bringToForeground()
        /** Localised stage name for the overlay ("Warnung", "Kritisch", "Test"). */
        fun stageName(stage: Int, isTest: Boolean): String
        /** Append one line to the EWS diagnostics channel; the host prefixes the wall clock. */
        fun log(text: String)
        /** Wall-clock text for the history ("HH:mm"). */
        fun clockText(): String
    }

    // The single active alert's sub-channel (-1 for an other-ensemble alert, -2 = none).
    @Volatile private var activeSubCh = -2
    // Identity (subCh + incident) of the alert on screen, to coalesce repeated Trigger FIGs.
    @Volatile private var shownKey = -1
    // Raw stage of the alert on screen — remembered so an ESCALATION of the same incident can still
    // break through a user dismissal.
    @Volatile private var shownStage = -1
    // What the user closed by hand (§7.6.4), and at which stage. A Trigger repeats 10-30x/s, so
    // without this the overlay sprang back on the very next FIG and could not be got rid of during a
    // burst. Forgotten once the alert really ends (End phase) or stops being broadcast (timeout).
    @Volatile private var dismissedKey = -1
    @Volatile private var dismissedStage = -1
    // Last time the clear-timeout was (re)armed — throttles the per-repeat cancel/relaunch to ≤1/s.
    @Volatile private var lastArmMs = 0L
    private var clearJob: Job? = null
    /** Last alert key already explained as a non-match, so the ~10-30/s repeat rate logs once. */
    @Volatile private var rejectedKey = Int.MIN_VALUE
    /** Whether "ASA is switched off" has already been recorded, so it is said once, not per frame. */
    @Volatile private var offLogged = false
    // Audio handover (§7.6): whether we switched playback to the alert sub-channel, and the station to
    // return to when the alert ends. Empty string = nothing was playing.
    @Volatile private var handoverDone = false
    @Volatile private var savedStationId: String? = null

    /** The alert service we handed over to — while it plays, its DLS/SlideShow is the alert message. */
    @Volatile var alertServiceId: String? = null
        private set

    fun onAlert(alert: DabEwsAlert) {
        val s = host.settings
        if (!s.asaEnabled) {
            // Say it once per run. Silently dropping a warning because a setting is off is a
            // legitimate outcome, but an unrecorded one is indistinguishable from a broken decoder —
            // and the decoder-level line says an alert DID arrive. Now the file says why it went no further.
            if (!offLogged) {
                offLogged = true
                host.log("verworfen: Katastrophenwarnung ist in den Einstellungen aus")
            }
            return
        }
        offLogged = false
        // Pre-trigger is inter-ensemble timing only — consumer receivers ignore it (§7.2.2.3).
        when (alert.form) {
            DabEwsAlert.FORM_TRIGGER -> onTrigger(alert, s)
            DabEwsAlert.FORM_SUSTAIN -> {
                // Continuation of the active alert — refresh the safety timeout (§7.6.2).
                if (host.alert != null && (activeSubCh == -1 || alert.subChannelId == activeSubCh)) {
                    if (!reviveEnded(alert)) armClearTimeout()
                }
            }
            DabEwsAlert.FORM_END -> end()   // §7.6.4
        }
    }

    private fun onTrigger(alert: DabEwsAlert, s: Settings) {
        // Receivability (§7.5.2): a tuned-ensemble alert is on the ensemble we're tuned to
        // (positive); an OE alert only if that ensemble is in tuning memory (a known station).
        val receivable = if (alert.isOtherEnsembleAlert) {
            host.stations.any {
                it.band == com.px6.radio.model.Band.DAB &&
                    it.id.substringBefore('.').toIntOrNull(16) == alert.ensembleId
            }
        } else true
        if (!receivable) return
        // The fixed codes the user entered PLUS the live one derived from where the car is.
        // Any of them matching is a match — a wider receiver area can add an alert, never
        // hide one, which is the safe direction for §7.5.4.
        val receiverCodes = EwsMatcher.parseReceiverCodes(s.asaLocationCodes) +
            listOfNotNull(host.gpsCode.takeIf { s.asaFollowGps }) +
            // Test alerts carry the broadcasters' test area, not the listener's — monitor it too,
            // otherwise switching tests on shows nothing at all (§7.5.4 is about the area, not the stage).
            EwsMatcher.parseReceiverCodes(listOfNotNull(EwsMatcher.TEST_LOCATION_CODE.takeIf { s.asaTestAlerts }))
        val play = EwsMatcher.shouldPlay(alert.stage, s.asaTestAlerts, alert.locationCodes.toList(), receiverCodes)
        val key = keyOf(alert)
        if (!play) {
            // Say WHY, once per alert. A warning that is silently discarded is the worst possible
            // outcome of this whole feature; recording only the positives left no way to tell
            // "correctly ignored" from "broken".
            if (key != rejectedKey) {
                rejectedKey = key
                host.log(buildString {
                    append("kein Treffer: ").append(alert.description).append('\n')
                    append("    Stufe ").append(alert.stage)
                    append(" (Testmeldungen ").append(if (s.asaTestAlerts) "an" else "aus").append(") -> ")
                    append(if (EwsMatcher.stageMatches(alert.stage, s.asaTestAlerts)) "ok" else "abgelehnt")
                    append('\n')
                    append("    Warngebiet: ")
                    append(alert.locationCodes.toList().takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: "ganzes Ensemble")
                    append('\n')
                    append("    Eigene Codes: ")
                    append(receiverCodes.takeIf { it.isNotEmpty() }?.joinToString(", ") { c ->
                        "Z${c.zone}:" + c.digits.joinToString("") { d -> d.toString(16).uppercase() }
                    } ?: "keine")
                    append("  (GPS ").append(if (s.asaFollowGps) "an" else "aus")
                    append(", fest: ").append(s.asaLocationCodes.size).append(")")
                })
            }
            return
        }
        // Coalesce the FIG's high repetition rate (~10–30×/s during a Trigger burst): once an
        // alert is on screen, a repeat of the SAME alert only refreshes the safety timeout —
        // it must not re-run the service/label lookup, state update, MATCH log, foreground grab
        // or handover every frame (that per-repeat work was heavy enough to risk an ANR).
        if (host.alert != null && key == shownKey) {
            if (!reviveEnded(alert)) armClearTimeout()
            return
        }
        // The user closed THIS alert. Honour that: keep only the safety timeout running (so
        // the dismissal is forgotten once the broadcast stops) and do not present again. An
        // escalation of the same incident — a different stage — still gets through.
        if (key == dismissedKey && alert.stage == dismissedStage) {
            armClearTimeout()
            return
        }
        shownKey = key
        shownStage = alert.stage
        activeSubCh = if (alert.isOtherEnsembleAlert) -1 else alert.subChannelId
        // §7.6.2: display the alert service label. For a tuned-ensemble alert we can resolve
        // it from tuning memory via the sub-channel; null when unknown (audio never waits on it).
        val alertServiceId = if (!alert.isOtherEnsembleAlert) host.findServiceBySubChannel(alert.subChannelId) else null
        val label = alertServiceId?.let { id -> host.stations.firstOrNull { it.id == id }?.name }
        val wasShowing = host.alert != null
        val stageName = host.stageName(alert.stage, alert.isTest)
        host.updateAlert {
            EwsAlertUi(
                stageName = stageName,
                isTest = alert.isTest,
                incidentId = alert.incidentId,
                subChId = alert.subChannelId,
                otherEnsemble = alert.isOtherEnsembleAlert,
                description = alert.description,
                serviceLabel = label,
                timeoutArmedAtMs = now(),
                timeoutMs = timeoutMs,
            )
        }
        // A new alert (not a repeated Trigger) must grab the screen even if the app is in the
        // background (§7.6). The process is alive (omri delivered the FIG), so bring our Activity
        // to the front; the overlay is already in the state, so it shows on resume.
        if (!wasShowing) host.bringToForeground()
        // Remember it: this presentation may replace one the user had not finished reading.
        host.addHistory(stageName + (label?.let { " · $it" } ?: "") + " · Vorfall ${alert.incidentId}", historyMax)
        host.log("MATCH → present: ${alert.description}")
        // A genuinely new alert must restart the timeout even if the previous one was armed
        // less than a second ago — clear the throttle stamp so armClearTimeout cannot skip this one.
        lastArmMs = 0L
        armClearTimeout()
        // §7.6.2 audio handover — play the alert sub-channel. Only for a tuned-ensemble alert;
        // an OE alert would need a cross-ensemble retune (kept for a later milestone), so it is
        // shown but not sounded here.
        if (!alert.isOtherEnsembleAlert) maybeStartAudio(alert.subChannelId)
    }

    /**
     * A Trigger or Sustain for the message still standing on screen after its End phase: the
     * broadcast has resumed.
     *
     * §7.6.2 is explicit — "the alert shall continue to be played whilst the Trigger or Sustain
     * phase signalling with the same SubChId is received, unless the alert is terminated by the
     * user" — and the user has NOT terminated it; the window is still up because we stopped closing
     * it ourselves. Without this, the repeat fell into the coalescing branch, was swallowed as
     * "already showing", and the audio stayed on the ordinary station while a warning was on air.
     *
     * Returns true when it handled the frame (so the caller does not arm the timeout twice).
     */
    private fun reviveEnded(alert: DabEwsAlert): Boolean {
        if (host.alert?.ended != true) return false
        host.updateAlert { it?.copy(ended = false) }
        host.log("Warnung wird erneut ausgestrahlt — Ton zurueck auf SubCh=${alert.subChannelId}")
        lastArmMs = 0L          // a resumed alert must not be swallowed by the once-a-second throttle
        armClearTimeout()
        if (!alert.isOtherEnsembleAlert) maybeStartAudio(alert.subChannelId)
        return true
    }

    /**
     * The broadcast reached its End phase (§7.6.4).
     *
     * The spec ends the ALERT MODE here: "audio playback shall be stopped and the receiver shall
     * return to the stored prior functional state". That is all it requires — it says nothing about
     * the message on screen. So the audio goes back immediately, and the text stays until the user
     * closes it. No countdown, because there is no bar to announce one — nothing closes by itself
     * unless a progress bar says it is about to.
     */
    private fun end() {
        val alert = host.alert ?: return
        if (alert.ended) return                     // a stray Sustain/End after the first End
        restoreAudio()                              // §7.6.4: audio back to the previous source, now
        clearJob?.cancel(); clearJob = null
        // timeoutMs = 0 leaves the overlay with no deadline at all, so its bar stays hidden.
        host.updateAlert { it?.copy(ended = true, timeoutArmedAtMs = 0L, timeoutMs = 0L) }
        host.log("End-Phase — Ton zurueck, Meldung bleibt bis der Nutzer schliesst")
    }

    private fun armClearTimeout() {
        // Once the End phase has run, the countdown is final — a late Trigger/Sustain must not push
        // the window back out again.
        if (host.alert?.ended == true) return
        // Throttle: Trigger/Sustain repeat ~10–30×/s, but refreshing a 12 s timeout more than once a
        // second is pointless churn (cancel + relaunch a coroutine each time).
        val t = now()
        if (t - lastArmMs < 1_000L && clearJob?.isActive == true) return
        lastArmMs = t
        clearJob?.cancel()
        // Publish the new deadline so the overlay's drain bar restarts with it.
        host.updateAlert { it?.copy(timeoutArmedAtMs = t, timeoutMs = timeoutMs) }
        // No Trigger/Sustain within this window ⇒ the alert has ceased; drop the overlay.
        clearJob = scope.launch { delay(timeoutMs); clear() }
    }

    /**
     * Switch audio to the alert sub-channel's DAB service (§7.6.2), once per alert. If the service
     * isn't in tuning memory we do nothing — normal playback continues under the overlay, so a missing
     * alert channel never degrades the radio. Remembers the current station to restore afterwards.
     */
    private fun maybeStartAudio(subCh: Int) {
        if (handoverDone) return
        val alertId = host.findServiceBySubChannel(subCh) ?: return   // unknown → overlay only
        handoverDone = true
        alertServiceId = alertId   // its live DLS/SlideShow becomes the alert message in the overlay
        // Already playing the alert audio? Then the receiver is, by definition, already presenting the
        // alert (§7.6.2) — e.g. listening to DokDeb, which IS the home-test channel. Do NOT switch, and
        // crucially do NOT remember a station to "restore" (that would re-tune the same service at the
        // alert's end for no reason). savedStationId stays null → restoreAudio is a no-op.
        // "Already playing" has to mean AUDIBLE, not merely tuned — only the router knows what is on
        // the amplifier, which is why the host answers this, not the tuner's nowPlayingId.
        if (host.isAudiblyPlaying(alertId)) {
            host.log("alert audio already playing (subch=$subCh, service=$alertId) — no switch")
            return
        }
        // Switching from another station/band to the alert audio: remember where to return afterwards.
        savedStationId = host.nowPlayingStationId ?: ""
        host.playAlertService(alertId)
        host.log("audio handover → alert subch=$subCh service=$alertId")
    }

    private fun clear(keepDismissal: Boolean = false) {
        clearJob?.cancel(); clearJob = null
        activeSubCh = -2
        shownKey = -1
        shownStage = -1
        alertServiceId = null
        // Reaching here for any reason OTHER than the user closing it means the alert is over (End
        // phase, or no Trigger/Sustain for the whole timeout). Forget the dismissal so the next
        // alert — or this incident coming back later — is presented normally.
        if (!keepDismissal) { dismissedKey = -1; dismissedStage = -1 }
        host.updateAlert { null }
        restoreAudio()
    }

    /** Return audio to whatever was playing before the alert (§7.6.4). No-op if we never handed over. */
    private fun restoreAudio() {
        if (!handoverDone) return
        handoverDone = false
        val saved = savedStationId; savedStationId = null
        if (saved.isNullOrEmpty()) return
        val st = host.stations.firstOrNull { it.id == saved } ?: return
        host.playStation(st)   // the normal play path restores the correct band + audio route
    }

    /** Dismiss the alert overlay by hand (§7.6.4 user termination). */
    fun dismiss() {
        dismissedKey = shownKey
        dismissedStage = shownStage
        host.log("user closed alert (key=$dismissedKey stage=$dismissedStage)")
        clear(keepDismissal = true)
    }

    private fun keyOf(alert: DabEwsAlert) = (alert.subChannelId shl 4) xor (alert.incidentId and 0xF)

    companion object {
        /** No Trigger/Sustain for this long ⇒ the broadcast has ceased (§7.6.2 safety timeout). */
        const val DEFAULT_TIMEOUT_MS = 12_000L
        /** Presentations remembered in the ASA pill. */
        const val DEFAULT_HISTORY_MAX = 5
    }
}
