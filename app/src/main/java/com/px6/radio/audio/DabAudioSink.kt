package com.px6.radio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * PCM sink for DAB+ audio — quality first.
 *
 * OMRI decodes DAB+ AAC access units to 16-bit PCM (via MediaCodec) and hands us raw PCM
 * through RadioServiceAudiodataListener. We play it verbatim:
 *
 *  - **No resampling / no re-encoding.** The [AudioTrack] is built at the *exact* sample rate
 *    and channel count the decoder reports; if the broadcast changes config we rebuild. Any
 *    unavoidable rate conversion to the device rate is left to the audio HAL (high quality),
 *    never done by us.
 *  - **16-bit PCM**, matching the AAC decoder output — no bit-depth juggling.
 *  - **Generous buffer** (~300 ms, several × the HAL minimum) so a hiccup in USB/decoding never
 *    causes an underrun (audible dropouts are the #1 quality killer for streamed radio).
 *  - **MODE_STREAM**, MEDIA/MUSIC attributes, and *not* low-latency performance mode (that would
 *    shrink the buffer and risk glitches — we already accept DAB's inherent latency).
 *
 * [setVolume] is used by the handover/fade logic; normal playback stays at unity gain.
 */
class DabAudioSink {

    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0
    private var gain = 1.0f
    private val lock = Any()

    /** Bumped on every flush/fade so a stale fade thread from a previous switch bows out. */
    @Volatile private var fadeToken = 0

    /** Invoked once at the first audible frame of a newly-started service (switch-latency timing). */
    @Volatile var onAudibleFrame: (() -> Unit)? = null

    /** Write one PCM buffer; (re)configures the track if rate/channels changed. */
    fun write(pcm: ByteArray, numChannels: Int, samplingRate: Int) {
        val t = synchronized(lock) {
            if (track == null || numChannels != channels || samplingRate != rate) {
                reconfigure(numChannels, samplingRate)
            }
            track
        } ?: return
        try {
            var offset = 0
            while (offset < pcm.size) {
                val written = t.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack write failed: ${e.message}")
        }
    }

    private fun reconfigure(numChannels: Int, samplingRate: Int) {
        release()
        rate = samplingRate
        channels = numChannels
        val channelMask =
            if (numChannels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioTrack.getMinBufferSize(samplingRate, channelMask, encoding)
            .coerceAtLeast(1)
        // ~300 ms of audio, and at least 4× the HAL minimum — smooth over USB/decode jitter.
        val bytesPerSec = samplingRate * numChannels * 2
        val bufferSize = maxOf(minBuf * 4, (bytesPerSec * 300) / 1000)

        track = buildTrack(samplingRate, channelMask, encoding, bufferSize)?.also {
            it.setVolume(gain)
            it.play()
        }
        Log.i(TAG, "AudioTrack ready: ${samplingRate}Hz, ${numChannels}ch, buf=$bufferSize")
    }

    private fun buildTrack(
        samplingRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSize: Int,
    ): AudioTrack? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(samplingRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, samplingRate, channelMask, encoding,
                bufferSize, AudioTrack.MODE_STREAM,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack build failed: ${e.message}")
        null
    }

    /** Linear gain 0.0..1.0 (used for handover fades). */
    fun setVolume(volume: Float) {
        gain = volume.coerceIn(0f, 1f)
        synchronized(lock) { track?.setVolume(gain) }
    }

    /**
     * Drop everything still queued in the track so a service switch cannot replay the tail of the
     * old service (the ~300 ms buffer) as the new one fades in. Any running fade is cancelled.
     */
    fun flush() {
        fadeToken++
        synchronized(lock) {
            track?.let {
                try {
                    it.pause(); it.flush(); it.play()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "flush failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Fade the output up from silence to unity — called on the **first audio frame of a new
     * service**, so a switch fades the new station in only once it is actually decoding (not during
     * the silent tuner acquisition). Runs on a short throwaway thread; a newer flush/fade wins.
     */
    fun fadeIn(durationMs: Long = 700L) {
        // First audible frame of the new service — the end of the perceptible switch latency.
        runCatching { onAudibleFrame?.invoke() }
        val mine = ++fadeToken
        setVolume(0f)
        Thread {
            val steps = 16                                    // more steps = smoother over 700 ms
            for (i in 1..steps) {
                if (mine != fadeToken) return@Thread          // superseded by a newer switch
                setVolume(i.toFloat() / steps)
                try {
                    Thread.sleep(durationMs / steps)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun release() = synchronized(lock) {
        track?.let {
            try {
                it.pause(); it.flush(); it.release()
            } catch (_: Exception) {
            }
        }
        track = null
        rate = 0
        channels = 0
    }

    private companion object {
        const val TAG = "DabAudioSink"
    }
}
