package com.px6.radio.audio

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/**
 * Plays an internet radio stream — the internet-radio band and the DAB→FM→IP fallback tier. Built on
 * **media3/ExoPlayer**: it reads the stream's "now playing" title from the *same* play connection
 * (ICY `StreamTitle` for MP3/AAC/Icecast, in-band ID3 for HLS) and handles HLS (BBC) that the old
 * `MediaPlayer` choked on — no second metadata connection, so no start-up "wabbern".
 *
 * ExoPlayer is single-threaded: it is created and driven on the main Looper, so every call marshals
 * there via [main]. The public surface (play/stop/setVolume/currentUrl/isPlaying) is unchanged, so
 * [AudioRouter] keeps wiring it exactly as before.
 */
@OptIn(UnstableApi::class)
class IpPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    private var volume: Float = 1f

    @Volatile
    private var playing: Boolean = false

    /**
     * Even out loudness between stations via Android's [LoudnessEnhancer] on the player's audio
     * session. On by default (good results); needs a moment to settle at the start of a stream.
     */
    @Volatile
    var normalizeLoudness: Boolean = true

    /** Fade in/out on switch/stop. Mirrors the "Weiches Überblenden" setting (also gates DAB fades). */
    @Volatile
    var fadeEnabled: Boolean = true

    /**
     * Per-station loudness matching, done by the [LoudnessEnhancer]'s target gain — which can *boost*
     * (player volume can only attenuate, so it can't lift a quiet station). A [LoudnessMeter] reads
     * each stream's raw RMS; loud stations keep the [BASELINE_GAIN_MB] the user already liked, quieter
     * ones get proportionally more boost so they come UP to the loud reference — nothing is pulled
     * down, so the overall level stays where it was. The learned target (mB) is applied instantly on
     * the next play and reported via [onLoudnessLearned] to be persisted.
     */
    @Volatile
    var stationTargetGainMb: Int = BASELINE_GAIN_MB

    /** (streamUrl, learned LoudnessEnhancer target in mB) — persisted by the caller. */
    @Volatile
    var onLoudnessLearned: ((String, Int) -> Unit)? = null

    private val meter = LoudnessMeter()

    /** Invoked (main thread) with the stream's current track title, or null to clear. */
    @Volatile
    var onMetadata: ((String?) -> Unit)? = null

    /** Last title surfaced, to suppress the ~2 Hz ICY repeats of the same string. */
    @Volatile
    private var lastTitle: String? = null

    /**
     * Whether audio is really coming out (main thread), as opposed to whether it was asked for.
     *
     * The status pill used to be driven by the play/pause INTENT alone, so a stream that had died
     * and was retrying still showed green, while nothing could be heard. The player is the only
     * thing that knows, so it says so.
     */
    @Volatile
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /** Main thread: the stream was given up on after the last retry (dead URL, no network). */
    var onFailed: ((String?) -> Unit)? = null

    /** Diagnostic sink (main thread): "play …", "error …", "retry …", "playing" — the ViewModel
     *  mirrors it to a file so a cold-boot stream failure is visible on the device. */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    /** Auto-retry the same stream on error/stall — mainly the **cold-boot race** (DAB stick up before
     *  Wi-Fi/data connects, so the first load fails) and transient CDN drops. Reset on a clean start
     *  and when a different station is chosen. */
    @Volatile private var retryCount = 0
    private var lastFadeInMs = 700L

    fun play(url: String, fadeInMs: Long = 700, onReady: () -> Unit = {}, onError: () -> Unit = {}) {
        main.post {
            val newStation = url != currentUrl
            if (newStation) retryCount = 0
            lastFadeInMs = fadeInMs
            onEvent?.invoke(if (retryCount > 0) "retry #$retryCount $url" else "play $url")
            // Fade the previous stream OUT (rather than cutting it) before the new one fades in.
            releaseLoudness()
            fadeOutAndRelease(player)
            currentUrl = url
            lastTitle = null
            // ExoPlayer with our loudness meter spliced into the audio pipeline (before volume/effects).
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(meter))
                    .build()
            }
            val p = ExoPlayer.Builder(context, renderersFactory).build()
            player = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && player === p) {
                        retryCount = 0                                  // clean start
                        onEvent?.invoke("playing $url")
                        setupLoudness(p.audioSessionId)
                        if (fadeEnabled) fadeIn(p, fadeInMs) else runCatching { p.volume = volume }
                        onReady()
                        scheduleLoudnessLearning(p, url)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== p) return
                    playing = isPlaying
                    onPlayingChanged?.invoke(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "ExoPlayer error: ${error.errorCodeName} ($url)")
                    onEvent?.invoke("error ${error.errorCodeName} $url")
                    // Retry the same stream (cold-boot network not up yet, or a transient drop) with
                    // backoff, up to a cap; only while this is still the current, wanted station.
                    if (player === p && currentUrl == url && retryCount < MAX_RETRIES) {
                        retryCount += 1
                        val delay = (2000L * retryCount).coerceAtMost(8000L)
                        onEvent?.invoke("retry in ${delay}ms (#$retryCount)")
                        releaseLoudness()
                        runCatching { p.release() }
                        if (player === p) { player = null; playing = false; onPlayingChanged?.invoke(false) }
                        main.postDelayed({
                            if (currentUrl == url) play(url, lastFadeInMs, onReady, onError)
                        }, delay)
                    } else {
                        fail(p, onError)
                    }
                }

                override fun onMetadata(metadata: Metadata) {
                    if (player !== p) return
                    // ICY fires this every metadata block (~2 Hz) with the same title — only surface a
                    // real change, so the UI isn't re-composed on every repeat.
                    val title = extractTitle(metadata)
                    if (title != null && title != lastTitle) {
                        lastTitle = title
                        onMetadata?.invoke(title)
                    }
                }
            })
            p.volume = 0f
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        main.post { player?.volume = volume }
    }

    val isPlaying: Boolean get() = playing

    fun stop(fadeMs: Long = 360) {
        currentUrl = null
        playing = false
        retryCount = 0
        onPlayingChanged?.invoke(false)
        main.post {
            releaseLoudness()
            val p = player
            player = null
            fadeOutAndRelease(p, fadeMs)
        }
    }

    /** Pull the current title from an ICY block (`StreamTitle`) or an ID3 `TIT2` frame (HLS). */
    private fun extractTitle(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            when (val entry = metadata[i]) {
                is IcyInfo -> sanitizeTitle(entry.title)?.let { return it }
                is TextInformationFrame ->
                    if (entry.id == "TIT2") sanitizeTitle(entry.values.firstOrNull())?.let { return it }
            }
        }
        return null
    }

    private fun fail(p: ExoPlayer, onError: () -> Unit) {
        val url = currentUrl
        if (player === p) { player = null; currentUrl = null; playing = false; onPlayingChanged?.invoke(false) }
        releaseLoudness()
        runCatching { p.release() }
        main.post { onError(); onFailed?.invoke(url) }
    }

    /** Ramp volume 0 -> [volume] so a handover isn't an abrupt cut. */
    private fun fadeIn(p: ExoPlayer, durationMs: Long) {
        val steps = 10
        val target = volume
        for (i in 0..steps) {
            main.postDelayed({
                if (player === p) p.volume = target * i / steps
            }, durationMs * i / steps)
        }
    }

    /**
     * After a stream settles, read its raw loudness once and derive the LoudnessEnhancer target that
     * brings it UP to the loud reference: a loud stream needs only the baseline (kept as the user
     * liked it), a quiet stream gets that plus the extra dB it is short — so quiet stations rise to
     * meet the loud ones instead of everything being pulled down. Applied live + reported to persist.
     */
    private fun scheduleLoudnessLearning(p: ExoPlayer, url: String) {
        // Reset the accumulator after the fade-in so the measurement isn't biased by the ramp-up.
        main.postDelayed({ if (player === p) meter.readRmsAndReset() }, 1_200)
        main.postDelayed({
            if (player !== p) return@postDelayed
            val rms = meter.readRmsAndReset()
            if (rms < 1.0) return@postDelayed                       // silence / no PCM measured
            // How many dB this stream is short of the loud reference; loud streams => 0 => natural.
            val shortfallDb = (20.0 * kotlin.math.log10(REFERENCE_RMS / rms)).coerceAtLeast(0.0)
            val targetMb = (shortfallDb * 100).toInt().coerceIn(0, MAX_GAIN_MB)
            Log.i(TAG, "loudness $url rms=${rms.toInt()} -> +${shortfallDb.toInt()}dB (${targetMb}mB)")
            if (targetMb != stationTargetGainMb) {
                stationTargetGainMb = targetMb
                setupLoudness(p.audioSessionId)     // (re)attach with the new target, or detach if 0
            }
            onLoudnessLearned?.invoke(url, targetMb)
        }, 11_000)
    }

    /** Ramp [p]'s volume down, then release it — a fade-out instead of an abrupt cut on switch/stop. */
    private fun fadeOutAndRelease(p: ExoPlayer?, durationMs: Long = 360) {
        if (p == null) return
        if (!fadeEnabled) { runCatching { p.release() }; return }
        val steps = 8
        val start = runCatching { p.volume }.getOrDefault(1f)
        for (i in 1..steps) {
            main.postDelayed({
                runCatching { p.volume = start * (steps - i) / steps }
                if (i == steps) runCatching { p.release() }
            }, durationMs * i / steps)
        }
    }

    private fun setupLoudness(sessionId: Int) {
        releaseLoudness()
        val mb = stationTargetGainMb
        // Loud stations (target 0) play natural — no effect attached, no boost, no attenuation.
        if (!normalizeLoudness || mb <= 0 || sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            loudness = LoudnessEnhancer(sessionId).apply {
                setTargetGain(mb)
                enabled = true
            }
        }.onFailure { Log.d(TAG, "LoudnessEnhancer unavailable: ${it.message}") }
    }

    private fun releaseLoudness() {
        loudness?.let { runCatching { it.release() } }
        loudness = null
    }

    companion object {
        private const val TAG = "IpPlayer"

        /**
         * A clean track title, or null.
         *
         * Broadcasters push more than a song title down the same field. iHeart stations (Z100) send
         * attribute soup — `Artist - Title - text="…" song_spot="T" amgTrackId="…" length="…"` — and
         * during an ad break the human part is empty, leaving only markers like `Spot Block End`. Shown
         * verbatim that reads like raw XML on the now-playing line. So: cut everything from the first
         * `key="` attribute, keep what stood before it, and fall back to `title=`/`text=` only when that
         * is a real title rather than a scheduling marker. Markup and JSON payloads are rejected outright.
         */
        internal fun sanitizeTitle(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (t.startsWith("<") || t.startsWith("{") || t.startsWith("[")) return null
            if (t.contains("</") || Regex("<[a-zA-Z!?/]").containsMatchIn(t)) return null   // any tag/markup
            if (t.length > 400) return null

            val attr = Regex("""\b[A-Za-z_][A-Za-z0-9_]*="([^"]*)"""")
            val first = attr.find(t) ?: return t.takeIf { it.length <= 200 }

            // Everything before the first attribute is the human "Artist - Title", when there is one.
            val head = t.substring(0, first.range.first).trim().trim('-', '·', '|').trim()
            if (head.isNotEmpty()) return head.takeIf { it.length <= 200 }

            // Otherwise fall back to the named fields, preferring an explicit artist/title pair.
            val fields = attr.findAll(t).associate { m ->
                m.value.substringBefore('=').lowercase() to m.groupValues[1].trim()
            }
            val title = fields["title"].orEmpty()
            val artist = fields["artist"].orEmpty()
            if (title.isNotEmpty()) return if (artist.isNotEmpty()) "$artist — $title" else title

            val text = fields["text"].orEmpty()
            // Scheduling markers, not music: an ad break start/end carries no programme information, and
            // showing it is worse than showing nothing (the UI then keeps the station name).
            if (text.isEmpty() || SCHEDULING_MARKER.containsMatchIn(text)) return null
            return text.takeIf { it.length <= 200 }
        }

        /** Ad-break / scheduling markers some US stations put in `text=` instead of a song title. */
        private val SCHEDULING_MARKER = Regex(
            """^(spot block (start|end)|unknown|commercial|advertisement|break)$""",
            RegexOption.IGNORE_CASE,
        )

        /** Retry the same stream this many times on error (backoff 2..8 s ≈ up to ~50 s) — enough to
         *  cover Wi-Fi coming up after a cold boot — then give up (a genuinely dead URL). */
        private const val MAX_RETRIES = 10

        /** The "full/good" raw RMS (16-bit scale ~0..32768) that quieter stations are boosted UP to —
         *  roughly the level of a loud station like Heart, which should stay natural (target 0). */
        private const val REFERENCE_RMS = 5100.0

        /** Loud stations get this LoudnessEnhancer target (0 = off = natural, "Heart 1.0"). */
        private const val BASELINE_GAIN_MB = 0

        /** Cap the boost for very quiet streams so we don't pump up noise/distortion (+15 dB). */
        private const val MAX_GAIN_MB = 1500
    }
}
