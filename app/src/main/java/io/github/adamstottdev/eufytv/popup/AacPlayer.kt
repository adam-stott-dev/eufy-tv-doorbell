package io.github.adamstottdev.eufytv.popup

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays an ADTS AAC stream (the doorbell's microphone) with MediaCodec and
 * AudioTrack. Feed bytes with [feed] from any thread.
 */
class AacPlayer(private val volume: Float = 1f) {
    private val frames = LinkedBlockingQueue<ByteArray>(200)
    private val pending = ByteArrayOutputStream()
    @Volatile private var running = true
    private val thread = Thread(::loop, "AacPlayer").apply { start() }

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        synchronized(pending) {
            pending.write(data, offset, length)
            val buf = pending.toByteArray()
            var i = 0
            while (i + 7 <= buf.size) {
                // ADTS sync word 0xFFF
                if ((buf[i].toInt() and 0xFF) != 0xFF || (buf[i + 1].toInt() and 0xF0) != 0xF0) { i++; continue }
                val len = ((buf[i + 3].toInt() and 0x03) shl 11) or
                    ((buf[i + 4].toInt() and 0xFF) shl 3) or
                    ((buf[i + 5].toInt() and 0xE0) ushr 5)
                if (len < 7) { i++; continue }
                if (i + len > buf.size) break
                frames.offer(buf.copyOfRange(i, i + len)) // drop audio rather than block if we fall behind
                i += len
            }
            pending.reset()
            pending.write(buf, i, buf.size - i)
        }
    }

    fun release() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val first = frames.take()
            val profile = ((first[2].toInt() and 0xC0) ushr 6) + 1 // audio object type
            val freqIndex = (first[2].toInt() and 0x3C) ushr 2
            val channels = ((first[2].toInt() and 0x01) shl 2) or ((first[3].toInt() and 0xC0) ushr 6)
            val rate = RATES.getOrElse(freqIndex) { 16000 }
            val ch = channels.coerceIn(1, 2)
            val csd = byteArrayOf(
                ((profile shl 3) or (freqIndex ushr 1)).toByte(),
                (((freqIndex and 1) shl 7) or (ch shl 3)).toByte(),
            )
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
            val channelMask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setVolume(volume)
            track.play()

            val info = MediaCodec.BufferInfo()
            var next: ByteArray? = first
            var pcm = ByteArray(8192)
            while (running) {
                val frame = next ?: frames.poll(20, TimeUnit.MILLISECONDS)
                next = null
                if (frame != null) {
                    // Strip the ADTS header (7 bytes, or 9 with CRC); the decoder gets raw AAC.
                    val header = if ((frame[1].toInt() and 0x01) == 0) 9 else 7
                    val idx = codec.dequeueInputBuffer(50_000)
                    if (idx >= 0 && frame.size > header) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        buf.put(frame, header, frame.size - header)
                        codec.queueInputBuffer(idx, 0, frame.size - header, 0, 0)
                    }
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    val ob = codec.getOutputBuffer(out)!!
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.get(pcm, 0, info.size)
                    codec.releaseOutputBuffer(out, false)
                    track.write(pcm, 0, info.size)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "Audio stopped", e)
        } finally {
            runCatching { track?.stop() }
            track?.release()
            runCatching { codec?.stop() }
            codec?.release()
        }
    }

    private companion object {
        const val TAG = "AacPlayer"
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
