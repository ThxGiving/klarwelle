package com.px6.radio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * A pass-through media3 [AudioProcessor] that measures the loudness (RMS) of the decoded PCM — used
 * to level internet stations against each other. It sits in ExoPlayer's audio pipeline **before**
 * the player volume and the session effects, so it sees each stream's *raw* level regardless of any
 * gain we then apply. It never alters the audio (writes the same bytes straight through); a failure
 * to interpret a format just disables measurement, not playback.
 *
 * No microphone/Visualizer (which would need RECORD_AUDIO) — this is an in-process tap.
 */
@UnstableApi
class LoudnessMeter : BaseAudioProcessor() {

    @Volatile private var sumSquares = 0.0
    @Volatile private var sampleCount = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only 16-bit PCM is measured; anything else passes through unmeasured (return format as-is
        // isn't allowed for unsupported encodings, so we simply don't throw and treat it as passthrough
        // by returning the same format — media3 hands us 16-bit PCM for AAC/MP3, which is our case).
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size <= 0) return

        // Measure 16-bit little-endian samples without disturbing the buffer position.
        if (encoding16Bit) {
            var i = position
            var s = 0.0
            var n = 0L
            while (i + 1 < limit) {
                val lo = inputBuffer.get(i).toInt() and 0xFF
                val hi = inputBuffer.get(i + 1).toInt()          // signed high byte
                val sample = (hi shl 8) or lo
                s += sample.toDouble() * sample
                n++
                i += 2
            }
            sumSquares += s
            sampleCount += n
        }

        // Pass the audio through unchanged.
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }

    private val encoding16Bit: Boolean
        get() = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT

    /** Mean RMS since the last reset (0..32768 scale), then clears the accumulator. */
    fun readRmsAndReset(): Double {
        val c = sampleCount
        val s = sumSquares
        sumSquares = 0.0
        sampleCount = 0
        return if (c > 0) sqrt(s / c) else 0.0
    }

    override fun onReset() {
        sumSquares = 0.0
        sampleCount = 0
    }
}
