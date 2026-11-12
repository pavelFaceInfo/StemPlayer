package com.stemplayer.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * Plays several stereo 16-bit PCM files in perfect sync by mixing them
 * sample-by-sample into a single AudioTrack. Gains can change live.
 */
class MixerPlayer(pcmFiles: List<File>, private val sampleRate: Int) {

    private val stems: List<ShortBuffer> = pcmFiles.map { f ->
        RandomAccessFile(f, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    /** Length of the longest stem, in frames. Shorter stems are padded with silence. */
    val totalFrames: Long = stems.maxOfOrNull { it.capacity() / 2L } ?: 0L

    /** Per-stem linear gain (0 = silent). Replaced as a whole array so the audio thread sees a consistent copy. */
    @Volatile var gains: FloatArray = FloatArray(stems.size) { 1f }

    @Volatile var isPlaying = false
        private set

    /** Frame position of the audio most recently handed to the output. */
    @Volatile var positionFrames: Long = 0
        private set

    var onPlayingChanged: ((Boolean) -> Unit)? = null

    @Volatile private var pendingSeek: Long = -1
    @Volatile private var running = true
    private val lock = Object()

    private val chunkFrames = 1024
    private val track: AudioTrack = run {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, chunkFrames * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private val thread = Thread({ audioLoop() }, "StemMixer").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    fun play() {
        if (positionFrames >= totalFrames) seekTo(0)
        synchronized(lock) {
            isPlaying = true
            lock.notifyAll()
        }
        onPlayingChanged?.invoke(true)
    }

    fun pause() {
        isPlaying = false
        onPlayingChanged?.invoke(false)
    }

    fun seekTo(frame: Long) {
        val f = frame.coerceIn(0, totalFrames)
        pendingSeek = f
        positionFrames = f
    }

    fun positionMs(): Long = positionFrames * 1000 / sampleRate
    fun durationMs(): Long = totalFrames * 1000 / sampleRate
    fun seekToMs(ms: Long) = seekTo(ms * sampleRate / 1000)

    fun release() {
        running = false
        isPlaying = false
        synchronized(lock) { lock.notifyAll() }
        thread.join(1000)
        track.release()
    }

    private fun audioLoop() {
        val out = ShortArray(chunkFrames * 2)
        val mix = FloatArray(chunkFrames * 2)
        var pos = 0L
        var trackPlaying = false

        while (running) {
            val seek = pendingSeek
            if (seek >= 0) {
                pendingSeek = -1
                pos = seek
                // Drop audio already queued so the jump is immediate.
                if (trackPlaying) track.pause()
                track.flush()
                trackPlaying = false
            }

            if (!isPlaying) {
                if (trackPlaying) {
                    track.pause()
                    trackPlaying = false
                }
                synchronized(lock) {
                    while (running && !isPlaying && pendingSeek < 0) lock.wait(200)
                }
                continue
            }

            if (pos >= totalFrames) {
                isPlaying = false
                onPlayingChanged?.invoke(false)
                continue
            }

            if (!trackPlaying) {
                track.play()
                trackPlaying = true
            }

            val n = minOf(chunkFrames.toLong(), totalFrames - pos).toInt()
            val g = gains
            java.util.Arrays.fill(mix, 0, n * 2, 0f)
            for (s in stems.indices) {
                val gain = g.getOrElse(s) { 0f }
                if (gain <= 0f) continue
                val buf = stems[s]
                val start = (pos * 2).toInt()
                val avail = minOf(n * 2, buf.capacity() - start)
                for (i in 0 until avail) mix[i] += buf.get(start + i) * gain
            }
            for (i in 0 until n * 2) {
                out[i] = mix[i].coerceIn(-32768f, 32767f).toInt().toShort()
            }

            pos += n
            positionFrames = pos
            track.write(out, 0, n * 2) // blocks until there is room: paces the loop
        }
        if (trackPlaying) track.pause()
    }
}
