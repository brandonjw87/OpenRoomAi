package com.openroomai.app.engine

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Minimal animated-GIF writer: one global 256-colour palette (median cut over sampled frames),
 * LZW-compressed frames, infinite loop. Pure Kotlin so it runs anywhere (and in unit tests).
 */
class GifEncoder(private val width: Int, private val height: Int, private val out: OutputStream) {
    private var palette = IntArray(0)
    private val lut = IntArray(32768) { -1 }
    private var started = false

    /** Builds the palette from a few representative frames (ARGB pixels). Call before [addFrame]. */
    fun buildPalette(samples: List<IntArray>) {
        val hist = IntArray(32768)
        // Full-precision colour sums per 5-bit bucket, so palette entries are true averages.
        val sr = LongArray(32768); val sg = LongArray(32768); val sb = LongArray(32768)
        for (f in samples) {
            var i = 0
            while (i < f.size) {
                val c = f[i]; val k = to15(c)
                hist[k]++; sr[k] += (c shr 16 and 0xFF).toLong(); sg[k] += (c shr 8 and 0xFF).toLong(); sb[k] += (c and 0xFF).toLong()
                i += 3
            }
        }
        palette = medianCut(hist, sr, sg, sb, 256)
    }

    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size == width * height)
        if (!started) writeHeader()
        // Graphic control extension: delay, no transparency.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        le16(delayCs)
        out.write(0); out.write(0)
        // Image descriptor (full frame, uses global table).
        out.write(0x2C); le16(0); le16(0); le16(width); le16(height); out.write(0)
        val idx = ByteArray(argb.size)
        for (i in argb.indices) idx[i] = indexOf(argb[i]).toByte()
        lzw(idx)
    }

    fun finish() {
        if (!started) writeHeader()
        out.write(0x3B)
        out.flush()
    }

    private fun writeHeader() {
        started = true
        if (palette.isEmpty()) palette = IntArray(256) { (it shl 16) or (it shl 8) or it }
        out.write("GIF89a".toByteArray())
        le16(width); le16(height)
        out.write(0xF7) // global colour table, 8 bits/colour, 256 entries
        out.write(0); out.write(0)
        for (k in 0 until 256) {
            val c = if (k < palette.size) palette[k] else 0
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        // NETSCAPE2.0: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun to15(c: Int) = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)

    private fun indexOf(c: Int): Int {
        val k = to15(c)
        val cached = lut[k]
        if (cached >= 0) return cached
        val r = (k shr 10 and 31) * 255 / 31
        val g = (k shr 5 and 31) * 255 / 31
        val b = (k and 31) * 255 / 31
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = (p shr 16 and 0xFF) - r
            val dg = (p shr 8 and 0xFF) - g
            val db = (p and 0xFF) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        lut[k] = best
        return best
    }

    private fun medianCut(hist: IntArray, sr: LongArray, sg: LongArray, sb: LongArray, n: Int): IntArray {
        val colors = (0 until 32768).filter { hist[it] > 0 }.toIntArray()
        if (colors.isEmpty()) return IntArray(1)
        val boxes = ArrayList<IntArray>()
        boxes += colors
        while (boxes.size < n) {
            // Split the box with the widest channel range (weighted by population).
            var bi = -1
            var bestScore = 0L
            var bestCh = 0
            for ((i, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                for (ch in 0..2) {
                    var lo = 31; var hi = 0
                    for (c in box) { val v = chan(c, ch); if (v < lo) lo = v; if (v > hi) hi = v }
                    val pop = box.sumOf { hist[it].toLong() }
                    val score = (hi - lo).toLong() * (1 + kotlin.math.sqrt(pop.toDouble()).toLong())
                    if (score > bestScore) { bestScore = score; bi = i; bestCh = ch }
                }
            }
            if (bi < 0) break
            val box = boxes.removeAt(bi)
            val sorted = box.sortedBy { chan(it, bestCh) }
            val total = sorted.sumOf { hist[it].toLong() }
            var acc = 0L
            var cut = 1
            for ((i, c) in sorted.withIndex()) { acc += hist[c]; if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break } }
            boxes += sorted.subList(0, cut).toIntArray()
            boxes += sorted.subList(cut, sorted.size).toIntArray()
        }
        return boxes.map { box ->
            var r = 0L; var g = 0L; var b = 0L; var w = 0L
            for (c in box) { r += sr[c]; g += sg[c]; b += sb[c]; w += hist[c] }
            if (w == 0L) w = 1
            val rr = ((r + w / 2) / w).toInt(); val gg = ((g + w / 2) / w).toInt(); val bb = ((b + w / 2) / w).toInt()
            (rr shl 16) or (gg shl 8) or bb
        }.toIntArray()
    }

    private fun chan(c15: Int, ch: Int) = when (ch) { 0 -> c15 shr 10 and 31; 1 -> c15 shr 5 and 31; else -> c15 and 31 }

    private fun le16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }

    /** Variable-width LZW as specified by GIF89a, min code size 8. */
    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val block = ByteArrayOutputStream()
        var bitBuf = 0
        var bitCount = 0
        var codeSize = minCode + 1
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block.write(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }
        // Dictionary: (prefix code << 8 | byte) → code, open addressing on primitive arrays.
        val keys = IntArray(TABLE)   // key + 1, 0 = empty
        val vals = IntArray(TABLE)
        fun slot(key: Int): Int {
            var h = (key * -0x61c88647) ushr (32 - TABLE_BITS)
            while (true) {
                val k = keys[h]
                if (k == 0 || k == key + 1) return h
                h = (h + 1) and (TABLE - 1)
            }
        }
        var next = eoi + 1
        emit(clear)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val sl = slot(key)
            if (keys[sl] != 0) { prefix = vals[sl]; continue }
            emit(prefix)
            if (next < 4096) {
                keys[sl] = key + 1; vals[sl] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear)
                java.util.Arrays.fill(keys, 0); next = eoi + 1; codeSize = minCode + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(eoi)
        if (bitCount > 0) block.write(bitBuf and 0xFF)
        val bytes = block.toByteArray()
        var p = 0
        while (p < bytes.size) {
            val len = minOf(255, bytes.size - p)
            out.write(len)
            out.write(bytes, p, len)
            p += len
        }
        out.write(0)
    }

    private companion object {
        const val TABLE_BITS = 14
        const val TABLE = 1 shl TABLE_BITS
    }
}
