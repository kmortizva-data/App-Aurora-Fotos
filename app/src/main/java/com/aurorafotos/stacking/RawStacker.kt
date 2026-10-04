package com.aurorafotos.stacking

import com.aurorafotos.presets.StackMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accumulates 16-bit Bayer RAW frames (as delivered by ImageFormat.RAW_SENSOR) without
 * alignment. On a tripod this is a faithful "digital long exposure":
 *
 *  - AVERAGE: mean of the frames (noise drops by sqrt(n), brightness unchanged).
 *  - ADD:     sum of the frames above black level, clamped to the white level
 *             (brightness of an n-times longer exposure).
 *  - LIGHTEN: per-pixel maximum (star trails).
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
class RawStacker(
    val width: Int,
    val height: Int,
    val mode: StackMode,
    val blackLevel: Int,
    val whiteLevel: Int,
) {
    private val n = width * height
    private val sum: IntArray? = if (mode == StackMode.AVERAGE || mode == StackMode.ADD) IntArray(n) else null
    private val max: ShortArray? = if (mode == StackMode.LIGHTEN) ShortArray(n) else null
    var frames = 0
        private set

    /** Adds one frame. [buffer] is the RAW16 plane; [rowStride] in bytes; [pixelStride] in bytes. */
    fun add(buffer: ByteBuffer, rowStride: Int, pixelStride: Int = 2) {
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sum = sum
        val max = max
        for (y in 0 until height) {
            val rowBase = y * rowStride
            val dstBase = y * width
            if (pixelStride == 2) {
                src.position(rowBase)
                val row = src.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                if (sum != null) {
                    for (x in 0 until width) sum[dstBase + x] += row.get(x).toInt() and 0xFFFF
                } else if (max != null) {
                    for (x in 0 until width) {
                        val v = row.get(x).toInt() and 0xFFFF
                        val i = dstBase + x
                        if (v > (max[i].toInt() and 0xFFFF)) max[i] = v.toShort()
                    }
                }
            } else {
                for (x in 0 until width) {
                    val p = rowBase + x * pixelStride
                    val v = (src.get(p).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8)
                    val i = dstBase + x
                    if (sum != null) sum[i] += v
                    else if (max != null && v > (max[i].toInt() and 0xFFFF)) max[i] = v.toShort()
                }
            }
        }
        frames++
    }

    /** Convenience for tests and for frames already copied to a ShortArray. */
    fun add(pixels: ShortArray) {
        require(pixels.size >= n)
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pixels, 0, n)
        add(bb, width * 2, 2)
    }

    /** Resulting RAW16 plane, little-endian, tightly packed (rowStride = width*2). */
    fun result(): ByteBuffer {
        check(frames > 0) { "no frames" }
        val out = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        val sb = out.asShortBuffer()
        when (mode) {
            StackMode.AVERAGE, StackMode.NONE -> {
                val s = sum!!
                for (i in 0 until n) sb.put(i, ((s[i] + frames / 2) / frames).toShort())
            }
            StackMode.ADD -> {
                val s = sum!!
                // Sum of signal above black, re-add black once, clamp to white.
                val blackTotal = blackLevel.toLong() * frames
                for (i in 0 until n) {
                    val v = s[i] - blackTotal + blackLevel
                    sb.put(i, v.coerceIn(0L, whiteLevel.toLong()).toInt().toShort())
                }
            }
            StackMode.LIGHTEN -> {
                val m = max!!
                for (i in 0 until n) sb.put(i, m[i])
            }
        }
        out.rewind()
        return out
    }

    fun resultPixels(): ShortArray {
        val r = result()
        val arr = ShortArray(n)
        r.asShortBuffer().get(arr)
        return arr
    }
}
