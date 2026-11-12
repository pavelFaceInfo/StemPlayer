package com.stemplayer.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedInfo(val sampleRate: Int, val frames: Long)

/**
 * Decodes any audio file Android can read (mp3, ogg, flac, wav, m4a...) into raw
 * 16-bit little-endian stereo PCM. Mono is duplicated to both channels; extra channels are dropped.
 */
object AudioDecoder {

    fun decodeToPcm(context: Context, uri: Uri, out: File): DecodedInfo {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("No audio track found")
        }
        extractor.selectTrack(trackIndex)
        val inFormat = extractor.getTrackFormat(trackIndex)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var frames = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        try {
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                while (true) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    } else if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())

                        val bytesPerSample = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            else -> 2
                        }
                        val n = info.size / (bytesPerSample * channels)
                        if (scratch.capacity() < n * 4) {
                            scratch = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                        }
                        scratch.clear()
                        for (i in 0 until n) {
                            val l = readSample(buf, encoding)
                            val r = if (channels >= 2) readSample(buf, encoding) else l
                            for (c in 2 until channels) readSample(buf, encoding) // skip extras
                            scratch.putShort(l)
                            scratch.putShort(r)
                        }
                        os.write(scratch.array(), 0, n * 4)
                        frames += n
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return DecodedInfo(sampleRate, frames)
    }

    private fun readSample(b: ByteBuffer, encoding: Int): Short = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT ->
            (b.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        AudioFormat.ENCODING_PCM_32BIT -> (b.int shr 16).toShort()
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            b.get() // drop least significant byte
            val lo = b.get().toInt() and 0xFF
            val hi = b.get().toInt()
            ((hi shl 8) or lo).toShort()
        }
        AudioFormat.ENCODING_PCM_8BIT -> (((b.get().toInt() and 0xFF) - 128) shl 8).toShort()
        else -> b.short
    }
}
