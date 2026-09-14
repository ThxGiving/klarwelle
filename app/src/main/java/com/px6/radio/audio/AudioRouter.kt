package com.px6.radio.audio

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The one audio source that may be audible. */
enum class AudioSource {
    DAB,

    /** The analog tuner — FM or AM, whichever frequency it currently sits on. */
    ANALOG,

    /** An internet radio stream (RadioDNS IP simulcast) via [com.px6.radio.audio.IpPlayer]. */
    IP,

    /**
     * Everything deliberately silenced — the user pressed stop, or there is no reception.
     *
     * Distinct from a `null` [AudioRouter.current], which means "nothing has claimed the amplifier
     * yet" (the start-up case). Conflating the two was a real bug: [AudioRouter.silenceAll] set
     * `current = null`, and that null is exactly the sentinel the ViewModel uses to decide "nobody
     * owns the amp, grab it for DAB". On DAB, stop therefore un-stopped itself within a second —
     * the next DabState emission (a DLS line, a slideshow image, a reception report) re-granted DAB
     * while the UI still showed stopped.
     */
    SILENCED,
}

/**
 * The single gate for audio output — a mutex over the two audio paths.
 *
 * There are three bands but only two paths: DAB plays through the Android output (an AudioTrack
 * fed by the decoder), while **FM and AM share the box's one analog tuner** and its amplifier
 * route (`av_channel_enter=fm`) — you can never hear both, it is the same chip on a different
 * frequency. Nothing in the hardware stops the two paths from sounding at once, and they did:
 * switching band without shutting the other path down left the DAB PCM running while the amplifier
 * still carried the analog tuner.
 *
 * So there is exactly one place that grants audio, and it is mutually exclusive by construction.
 * Every caller — a manual band switch, service following, a scan — goes through [toDab]/[toAnalog];
 * granting one source always silences the other first, and a coroutine [Mutex] serialises the
 * grants so two can never interleave (following handing over to the analog tuner while the user
 * taps a DAB station, say). The mutex is fair, so rapid taps resolve in the order they were made.
 *
 * The router is deliberately built from three plain functions rather than the two controllers, so
 * the whole mutex can be exercised in a JVM unit test with no Android and no hardware. The factory
 * [from] wires it to the real [com.px6.radio.dab.DabController] /
 * [com.px6.radio.fm.FmController].
 */
class AudioRouter(
    private val setDabVolume: (Float) -> Unit,
    private val enterAnalog: () -> Unit,
    private val exitAnalog: () -> Unit,
    /** Drop queued DAB PCM so a switch can't replay the old service's buffered tail. No-op in tests. */
    private val flushDab: () -> Unit = {},
    /** Arm DAB's fade-in so the new service fades up on its first frame, not during acquisition. */
    private val primeDabFadeIn: () -> Unit = {},
    /** Start the RadioDNS IP simulcast (the DAB->FM->IP fallback tier). No-op in tests / no player. */
    private val startIp: (String) -> Unit = {},
    /** Stop the IP simulcast, fading out over the given ms. Matched to the incoming source so a switch
     *  cross-fades cleanly (to DAB) or clears fast (to the instant analog tuner) instead of overlapping
     *  at mismatched lengths. Called whenever audio routes back to DAB or the analog tuner. */
    private val stopIp: (Long) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile
    var current: AudioSource? = null
        private set

    /**
     * Make DAB the audible source and run [tune] (start/switch the DAB service) while holding the
     * lock, so no analog grant can slip in between silencing the tuner and starting DAB.
     */
    /**
     * @param deferIpStop keep a playing internet stream RUNNING through the (silent) DAB acquisition,
     *   so the caller can cross-fade it out only once DAB actually produces its first frame — no
     *   ~3 s silence gap on a cross-ensemble retune. The caller MUST then stop IP on the first DAB
     *   frame (see RadioViewModel.onDab). Only meaningful when coming from IP with fade.
     */
    suspend fun toDab(fade: Boolean = false, deferIpStop: Boolean = false, tune: () -> Unit) = mutex.withLock {
        val wasDab = current == AudioSource.DAB
        // On a DAB→DAB switch, fade the old service out first so the change isn't an abrupt cut.
        if (wasDab && fade) rampDab(1f, 0f)
        if (current != AudioSource.DAB) {
            // Take the amplifier off the analog tuner. For IP: either cross-fade it out now (matched to
            // the DAB fade-in), OR — when deferring — leave it PLAYING so the caller can cross-fade it
            // against DAB's real first frame instead of leaving a silent acquisition gap.
            runCatching { exitAnalog() }.onFailure { Log.w(TAG, "exitAnalog: ${it.message}") }
            if (!deferIpStop) runCatching { stopIp(if (fade) DABFADEIN_MS else QUICK_MS) }
            current = AudioSource.DAB
        }
        // Drop the old service's queued PCM so its buffered tail can't replay as the new one comes up.
        runCatching { flushDab() }
        if (fade) {
            // Stay silent through the (up-to-several-second) cross-ensemble tuner acquisition and let
            // DAB fade the new service in on its first decoded frame — a real switch fade, no glitch,
            // no ramping the volume up over the buffered old audio while the tuner is still retuning.
            runCatching { setDabVolume(0f) }
            runCatching { primeDabFadeIn() }
            runCatching { tune() }.onFailure { Log.w(TAG, "dab tune: ${it.message}") }
        } else {
            runCatching { setDabVolume(1f) }
            runCatching { tune() }.onFailure { Log.w(TAG, "dab tune: ${it.message}") }
        }
    }

    /** Ramp the DAB output volume from → to over the switch fade (~360 ms, matched to the internet
     *  stream's fade-out so DAB station switches feel the same). */
    private suspend fun rampDab(from: Float, to: Float) {
        val steps = 18
        for (i in 0..steps) {
            runCatching { setDabVolume(from + (to - from) * i / steps) }
            delay(FADE_STEP_MS)
        }
    }

    /**
     * Make the analog tuner (FM or AM — the same chip) the audible source and run [tune] while
     * holding the lock.
     */
    suspend fun toAnalog(tune: () -> Unit) = mutex.withLock {
        if (current != AudioSource.ANALOG) {
            // Silence the DAB output and stop any IP stream before the amp is handed to the tuner. FM
            // comes up instantly, so clear IP quickly to avoid an audible overlap.
            runCatching { setDabVolume(0f) }
            runCatching { stopIp(QUICK_MS) }
            runCatching { enterAnalog() }.onFailure { Log.w(TAG, "enterAnalog: ${it.message}") }
            current = AudioSource.ANALOG
        }
        runCatching { tune() }.onFailure { Log.w(TAG, "analog tune: ${it.message}") }
    }

    /**
     * Make the RadioDNS internet stream the audible source: mute the DAB output and take the amp
     * off the analog tuner (the DAB tuner keeps running, silently, so following can still see when
     * DAB reception recovers), then start the [url] stream. The IP tier of DAB->FM->IP following.
     */
    suspend fun toIp(url: String) = mutex.withLock {
        runCatching { setDabVolume(0f) }
        runCatching { exitAnalog() }.onFailure { Log.w(TAG, "exitAnalog: ${it.message}") }
        current = AudioSource.IP
        runCatching { startIp(url) }.onFailure { Log.w(TAG, "startIp: ${it.message}") }
    }

    /** DAB audio level without changing the source — used by the following crossfade. */
    suspend fun setDabVolumeIfActive(v: Float) = mutex.withLock {
        if (current == AudioSource.DAB) runCatching { setDabVolume(v) }
    }

    /** Silence everything (mute / no reception). */
    suspend fun silenceAll() = mutex.withLock {
        runCatching { exitAnalog() }
        runCatching { stopIp(QUICK_MS) }
        runCatching { setDabVolume(0f) }
        current = AudioSource.SILENCED
    }

    companion object {
        private const val TAG = "AudioRouter"
        private const val FADE_STEP_MS = 18L   // 8 steps ≈ 145 ms per fade leg
        private const val DABFADEIN_MS = 700L  // matches DabAudioSink.fadeIn — IP fades out over the same
        private const val QUICK_MS = 140L      // clearing IP for an instant source (FM) or a hard mute

        /** Wires a router to the real controllers; either may be null on hardware that lacks it. */
        /**
         * The controllers are passed as SUPPLIERS, not values, so the router can be built before they
         * exist and still reach them once they do.
         *
         * It used to capture them by value, which forced the router to be created inside the DAB
         * start-up stage — and until then every audio command was silently dropped on a null router.
         * Combined with the DAB stage waiting up to 30 s for the USB permission dialog, that meant no
         * sound at all on start-up, not even an internet stream that needed nothing from DAB.
         */
        fun from(
            dab: () -> com.px6.radio.dab.DabController?,
            fm: () -> com.px6.radio.fm.FmController?,
            ip: IpPlayer? = null,
        ): AudioRouter = AudioRouter(
            setDabVolume = { v -> dab()?.setVolume(v) },
            enterAnalog = { fm()?.enterFm() },
            exitAnalog = { fm()?.exitFm() },
            flushDab = { dab()?.flushAudio() },
            primeDabFadeIn = { dab()?.primeFadeIn() },
            startIp = { url -> ip?.play(url) },
            stopIp = { ms -> ip?.stop(ms) },
        )
    }
}
