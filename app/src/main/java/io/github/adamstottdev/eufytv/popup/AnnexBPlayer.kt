package io.github.adamstottdev.eufytv.popup

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes a raw Annex-B H.264 or H.265 elementary stream (as delivered by the
 * Eufy P2P livestream) straight to a [Surface] with MediaCodec.
 *
 * Feed bytes with [feed] from any thread. Set [paceFps] for file playback so it
 * plays at normal speed; leave it 0 for live video so frames show immediately.
 */
class AnnexBPlayer(
    private val surface: Surface,
    private val hevc: Boolean = false,
    private val paceFps: Int = 0,
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onFirstFrame: () -> Unit = {},
) {
    private val units = LinkedBlockingQueue<ByteArray>(240)
    private val splitter = NalSplitter { units.put(it) }
    @Volatile private var running = true
    private var firstShown = false
    private val thread = Thread(::decodeLoop, "AnnexBPlayer").apply { start() }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (running) splitter.feed(data, offset, length)
    }

    fun release() {
        running = false
        thread.interrupt()
    }

    private fun nalType(unit: ByteArray): Int {
        val h = unit[startCodeLength(unit)].toInt() and 0xFF
        return if (hevc) (h ushr 1) and 0x3F else h and 0x1F
    }

    private fun isConfig(type: Int) = if (hevc) type in 32..34 else type == 7 || type == 8
    private fun isFrame(type: Int) = if (hevc) type in 0..31 else type in 1..5

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            // Collect parameter sets until the first frame.
            val config = mutableMapOf<Int, ByteArray>()
            val pending = ByteArrayOutputStream()
            var firstFrame: ByteArray? = null
            while (running && firstFrame == null) {
                val unit = units.take()
                val type = nalType(unit)
                when {
                    isConfig(type) -> config[type] = unit
                    isFrame(type) && config.size >= (if (hevc) 3 else 2) -> firstFrame = unit
                }
            }
            if (!running || firstFrame == null) return

            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val sps = config[if (hevc) 33 else 7]!!
            val size = if (hevc) null else H264Sps.size(sps, startCodeLength(sps))
            val (w, h) = size ?: (1920 to 1080)
            onVideoSize(w, h)
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                if (hevc) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(config[32]!! + config[33]!! + config[34]!!))
                } else {
                    setByteBuffer("csd-0", ByteBuffer.wrap(config[7]!!))
                    setByteBuffer("csd-1", ByteBuffer.wrap(config[8]!!))
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            val frameUs = 1_000_000L / (if (paceFps > 0) paceFps else 15)
            var frameIndex = 0L
            val startNs = System.nanoTime()
            var next: ByteArray? = firstFrame

            while (running) {
                val unit = next ?: units.poll(20, TimeUnit.MILLISECONDS)
                next = null
                if (unit != null) {
                    val type = nalType(unit)
                    if (!isFrame(type)) {
                        pending.write(unit) // SEI / repeated SPS+PPS travel with the next frame
                    } else {
                        pending.write(unit)
                        val au = pending.toByteArray()
                        pending.reset()
                        val idx = codec.dequeueInputBuffer(100_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(au, 0, minOf(au.size, buf.capacity()))
                            codec.queueInputBuffer(idx, 0, minOf(au.size, buf.capacity()), frameIndex * frameUs, 0)
                            frameIndex++
                        }
                    }
                }
                // Drain whatever is decoded.
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        val cw = f.getInteger(MediaFormat.KEY_WIDTH)
                        val ch = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (size == null) onVideoSize(cw, ch)
                        continue
                    }
                    if (out < 0) break
                    if (paceFps > 0) {
                        val dueNs = startNs + info.presentationTimeUs * 1000
                        val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                        if (waitMs > 0) Thread.sleep(waitMs)
                    }
                    codec.releaseOutputBuffer(out, true)
                    if (!firstShown) { firstShown = true; onFirstFrame() }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "Decoder stopped", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
        }
    }

    private companion object {
        const val TAG = "AnnexBPlayer"
        fun startCodeLength(u: ByteArray) = if (u.size > 3 && u[2].toInt() == 1) 3 else 4
    }
}

/** Splits an Annex-B byte stream into NAL units, each including its start code. */
class NalSplitter(private val emit: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream()
    private var data = ByteArray(0)

    fun feed(src: ByteArray, off: Int, len: Int) {
        buf.write(src, off, len)
        data = buf.toByteArray()
        var start = findStart(data, 0)
        if (start < 0) return
        while (true) {
            val next = findStart(data, start + 3)
            if (next < 0) break
            emit(data.copyOfRange(start, next))
            start = next
        }
        buf.reset()
        buf.write(data, start, data.size - start)
    }

    /** Index of the next 00 00 01 or 00 00 00 01 at or after [from]. */
    private fun findStart(d: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) return if (i > 0 && d[i - 1].toInt() == 0) i - 1 else i
            }
            i++
        }
        return -1
    }
}
