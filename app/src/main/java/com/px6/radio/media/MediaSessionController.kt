package com.px6.radio.media

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * The app's MediaSession: what is playing, published the standard Android way.
 *
 * Two things ride on it, and both are plain platform behaviour, not anything vendor-specific:
 *
 *  - **Media keys in.** The session's callback receives play/pause/stop/next/previous from wherever
 *    the platform routes them — a steering wheel, a Bluetooth headset, a launcher widget.
 *  - **Now-playing out.** Anything that reads the active session (a launcher widget, Android Auto, a
 *    navigation app's mini-player, and on Microntek/HCT head units the instrument cluster's media
 *    page) shows our station, subtitle and artwork.
 *
 * This used to be called "ClusterController" and sat behind a "show station in the cluster" switch,
 * as if the cluster were a feature of ours. It never was: the head unit mirrors the active session
 * by itself, the way it does for Bluetooth music, and the switch was literally `session.isActive`.
 * Turning it off also turned the media keys off. So the session is simply always active now, and
 * the class is named for what it is.
 */
class MediaSessionController(
    context: Context,
    private val onPlay: () -> Unit,
    private val onStop: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
) {
    private val session = MediaSessionCompat(context.applicationContext, "Klarwelle").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = this@MediaSessionController.onPlay()
            // Live radio has no pause — a hardware play/pause key maps its "pause" to stop, so it
            // behaves sensibly whatever the source sends.
            override fun onStop() = this@MediaSessionController.onStop()
            override fun onPause() = this@MediaSessionController.onStop()
            override fun onSkipToNext() = this@MediaSessionController.onNext()
            override fun onSkipToPrevious() = this@MediaSessionController.onPrev()
        })
        isActive = true
    }

    fun update(stationName: String, subtitle: String?, playing: Boolean, art: Bitmap? = null) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, stationName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationName)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle.orEmpty())
                .apply {
                    // Station logo (or the live DAB slideshow) as album art — so a launcher widget,
                    // Android Auto and a cluster media page show our artwork instead of the generic
                    // vinyl disc. All three art keys are set because different renderers read
                    // different ones.
                    if (art != null) {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                    }
                }
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    // Live radio: playing vs STOPPED (not PAUSED) — the widget shows Play/Stop, no pause.
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .setActions(
                    // PAUSE/PLAY_PAUSE are advertised even though live radio cannot resume where it
                    // left off: the single play/pause key is what a steering wheel and a Bluetooth
                    // headset actually send, and without these bits the framework drops the press
                    // outright (verified — KEYCODE_MEDIA_PLAY_PAUSE did nothing while STOP worked).
                    // Both land on onStop(), so the effect is "silence now", which is what the user
                    // wants from that button.
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
