package com.stemplayer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Owns the currently open song and its player for the whole app process, so playback
 * survives the screen being closed and can be driven from the notification.
 */
object Playback {
    private lateinit var app: Context

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** Bumped on every play/pause/seek/song change so the notification can refresh its progress. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    var player: MixerPlayer? = null
        private set

    // Pause when headphones are unplugged.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pause()
    }
    private var receiverRegistered = false

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun open(song: Song, dir: File) {
        close()
        val p = MixerPlayer(song.stems.map { File(dir, it.file) }, song.sampleRate)
        p.onPlayingChanged = { playing ->
            _playing.value = playing
            _changes.value++
        }
        player = p
        _current.value = song
        applyGains(song)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        _changes.value++
    }

    fun close() {
        player?.release()
        player = null
        _playing.value = false
        _current.value = null
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(noisyReceiver) }
            receiverRegistered = false
        }
        PlaybackService.stop(app)
    }

    fun play() {
        val p = player ?: return
        p.play()
        // Can be refused when the app is in the background; the service still updates the notification.
        runCatching { PlaybackService.start(app) }
    }

    fun pause() {
        player?.pause()
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) pause() else play()
    }

    fun seekToMs(ms: Long) {
        player?.seekToMs(ms)
        _changes.value++
    }

    /** Called when stem settings (volume, mute, names) change. */
    fun update(song: Song) {
        if (_current.value?.id != song.id) return
        _current.value = song
        applyGains(song)
    }

    private fun applyGains(song: Song) {
        // Squared slider value feels more natural to the ear than a linear one.
        player?.gains = FloatArray(song.stems.size) { i ->
            val s = song.stems[i]
            if (s.muted) 0f else s.volume * s.volume
        }
    }
}
