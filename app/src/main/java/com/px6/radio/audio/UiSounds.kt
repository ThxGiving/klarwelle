package com.px6.radio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.px6.radio.R

/**
 * Short interface sounds — a tick on a key, a two-tone when a preset is stored, a cue when service
 * following moves the audio, and the attention signal ahead of an emergency warning.
 *
 * All clips are synthesised (see design-qa/sounds) and shipped in res/raw, so there is nothing to
 * license. Played through [SoundPool] with USAGE_ASSISTANCE_SONIFICATION: no audio focus request,
 * so the cue never ducks or interrupts the radio itself. Every cue is gated by the caller — this
 * class only knows how to make the noise.
 */
class UiSounds(context: Context) {

    enum class Cue(val res: Int, val volume: Float) {
        /** A key was pressed: preset, tile, band, function bar. */
        TICK(R.raw.ui_tick, 0.6f),
        /** A long press stored a preset. */
        CONFIRM(R.raw.ui_confirm, 0.6f),
        /** Service following left DAB for FM/Internet. */
        FOLLOW_DOWN(R.raw.ui_follow_down, 0.5f),
        /** Service following brought the audio back to DAB. */
        FOLLOW_UP(R.raw.ui_follow_up, 0.5f),
        /** A station scan finished. */
        SCAN_DONE(R.raw.ui_scan_done, 0.6f),
        /** Attention signal ahead of an ASA/EWS alert (ETSI TS 104 089 §7.6 presentation). */
        ALERT(R.raw.ui_alert, 1.0f),
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = Cue.values().associateWith { pool.load(context, it.res, 1) }

    fun play(cue: Cue) {
        val id = ids[cue] ?: return
        pool.play(id, cue.volume, cue.volume, if (cue == Cue.ALERT) 2 else 1, 0, 1f)
    }

    fun release() = pool.release()
}
