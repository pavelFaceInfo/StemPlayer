package com.stemplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the playback notification (song name, progress bar, play/pause) and keeps the
 * app alive while playing in the background.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        Playback.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "StemPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Playback.play()
                override fun onPause() = Playback.pause()
                override fun onStop() = Playback.pause()
                override fun onSeekTo(pos: Long) = Playback.seekToMs(pos)
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        scope.launch {
            combine(Playback.current, Playback.playing, Playback.changes) { song, playing, _ -> song to playing }
                .collect { (song, playing) -> refresh(song, playing) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Playback.toggle()
        }
        // startForegroundService() requires a startForeground() call every time.
        refresh(Playback.current.value, Playback.playing.value, forceForeground = intent?.action == null)
        return START_NOT_STICKY
    }

    private fun refresh(song: Song?, playing: Boolean, forceForeground: Boolean = false) {
        if (song == null) {
            stopSelf()
            return
        }
        val player = Playback.player
        val duration = player?.durationMs() ?: song.durationMs
        val position = player?.positionMs() ?: 0L

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.stems.joinToString(" · ") { it.name })
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO
                )
                // The system moves the progress bar on its own from this position and speed.
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position, if (playing) 1f else 0f, SystemClock.elapsedRealtime(),
                )
                .build()
        )

        val notification = buildNotification(song, playing)
        if (playing || forceForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForeground = true
            } catch (e: Exception) {
                // Not allowed to go foreground right now; still show the notification.
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
        if (!playing) {
            // While paused the notification stays, but can be swiped away.
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(song: Song, playing: Boolean): Notification {
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            if (playing) "Pause" else "Play",
            toggle,
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song.name)
            .setContentText(song.stems.joinToString(" · ") { it.name })
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "com.stemplayer.app.TOGGLE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
