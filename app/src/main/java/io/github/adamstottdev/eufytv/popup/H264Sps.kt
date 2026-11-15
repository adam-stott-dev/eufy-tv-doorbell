package io.github.adamstottdev.eufytv.popup

/** Reads the picture size from an H.264 SPS NAL unit (without its start code). */
object H264Sps {
    fun size(nal: ByteArray, offset: Int = 0, length: Int = nal.size - offset): Pair<Int, Int>? = try {
        val r = BitReader(unescape(nal, offset + 1, length - 1)) // skip NAL header
        val profile = r.bits(8)
        r.bits(16) // constraint flags + level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue() // bit depths
            r.bits(1)
            if (r.bits(1) == 1) {
                for (i in 0 until if (chromaFormat != 3) 8 else 12) {
                    if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.bits(1) // max_num_ref_frames, gaps
        val mbW = r.ue() + 1
        val mbH = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var w = mbW * 16
        var h = mbH * 16 * (2 - frameMbsOnly)
        if (r.bits(1) == 1) { // cropping
            val l = r.ue(); val rt = r.ue(); val t = r.ue(); val b = r.ue()
            val cx = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
            val cy = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (l + rt) * cx
            h -= (t + b) * cy
        }
        w to h
    } catch (e: Exception) {
        null
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation-prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        var n = 0
        var zeros = 0
        for (i in off until off + len) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out[n++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    private class BitReader(private val d: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) zeros++
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
