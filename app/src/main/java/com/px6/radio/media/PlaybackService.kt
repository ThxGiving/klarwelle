package com.px6.radio.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.px6.radio.MainActivity
import com.px6.radio.R

/**
 * Keeps the radio playing while the app is not on screen.
 *
 * Android only protects audio that belongs to a **foreground service**. Without one the radio
 * survives on a head unit, where our app stays resident, but on an ordinary tablet the process is
 * a cached one the moment the user leaves the app: the system throttles it and kills it under
 * memory pressure, and the music stops for no reason the user can see.
 *
 * The service does **not** own the playback — [com.px6.radio.vm.RadioViewModel] still does, with
 * its tuners, its router and its single [MediaSessionController]. All this does is hold the process
 * in the foreground for as long as something is playing and show the notification Android demands
 * in return. The notification's content comes from the media session itself (one source of truth:
 * station, subtitle, artwork and the ‹ › keys are the session's, as on the cluster page), so there
 * is no second path that could drift from what the screen says.
 */
class PlaybackService : Service() {

    private var controller: MediaControllerCompat? = null
    private var started = false

    private val sessionWatcher = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: android.support.v4.media.MediaMetadataCompat?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = refresh()
        override fun onSessionDestroyed() { stopNow() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = sessionToken
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // A notification button (or anything else routing a media key here): hand the key to the
            // session, which is where the one playback callback lives.
            val key = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key != null) controller?.dispatchMediaButtonEvent(key)
            return START_NOT_STICKY
        }
        if (token == null) {
            // Nothing to show and nothing to protect — do not sit in the foreground for nothing.
            stopNow()
            return START_NOT_STICKY
        }
        if (controller == null) {
            controller = runCatching { MediaControllerCompat(this, token) }.getOrNull()
                ?.also { it.registerCallback(sessionWatcher) }
        }
        // Within five seconds of starting, or Android kills us with an ANR-style crash.
        goForeground(notification())
        return START_NOT_STICKY        // playback lives in the ViewModel; never resurrect alone
    }

    override fun onDestroy() {
        controller?.unregisterCallback(sessionWatcher)
        controller = null
        started = false
        super.onDestroy()
    }

    private fun refresh() {
        if (!started) return
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification())
        }
    }

    private fun goForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            started = true
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun stopNow() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        started = false
        stopSelf()
    }

    private fun notification(): Notification {
        createChannel()
        val meta = controller?.metadata
        val title = meta?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE)
            ?: getString(R.string.app_name)
        val subtitle = meta?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST)
        val art = meta?.getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(subtitle.orEmpty())
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // Previous / stop / next, routed through the session — the same keys a steering wheel sends.
        builder.addAction(mediaAction(android.R.drawable.ic_media_previous, R.string.media_previous, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
        builder.addAction(mediaAction(android.R.drawable.ic_media_pause, R.string.media_stop, PlaybackStateCompat.ACTION_STOP))
        builder.addAction(mediaAction(android.R.drawable.ic_media_next, R.string.media_next, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        sessionToken?.let {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(it)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun mediaAction(icon: Int, labelRes: Int, action: Long): NotificationCompat.Action =
        NotificationCompat.Action(
            icon, getString(labelRes),
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, action),
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = getString(R.string.notification_channel_playback_hint)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
        )
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        /** The one session the notification mirrors; set by the ViewModel when it builds it. */
        @Volatile private var sessionToken: MediaSessionCompat.Token? = null

        /**
         * Follow the playing state: hold the process in the foreground while something plays, let it
         * go when it does not. Safe to call on every state change — starting an already running
         * service only refreshes it, stopping a stopped one does nothing.
         *
         * Never throws: Android 12 and newer refuse a foreground start from the background in some
         * situations, and a refused notification must not take the radio down with it.
         */
        fun sync(context: Context, token: MediaSessionCompat.Token?, playing: Boolean) {
            sessionToken = token
            val intent = Intent(context, PlaybackService::class.java)
            runCatching {
                if (playing && token != null) ContextCompat.startForegroundService(context, intent)
                else context.stopService(intent)
            }.onFailure { Log.w(TAG, "sync(playing=$playing) failed: ${it.message}") }
        }
    }
}
