package com.aurorafotos.stacking

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sums 16-bit Bayer RAW frames (ImageFormat.RAW_SENSOR) without alignment. On a tripod the
 * sum of N short frames is, photon for photon, an N-times longer exposure, and summing in a
 * 32-bit accumulator means there is no cap on the number of frames (Samsung limits
 * third-party apps to ~1/9 s per frame on the S24 Ultra, so a 30 s shot is ~270 frames).
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
class RawStacker(
    val width: Int,
    val height: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
) {
    val pixelCount = width * height
    private val sum = IntArray(pixelCount)
    var frames = 0
        private set

    val rangePerFrame: Int get() = whiteLevel - blackLevel

    /** Adds one frame. [buffer] is the RAW16 plane; strides in bytes. */
    fun add(buffer: ByteBuffer, rowStride: Int, pixelStride: Int = 2) {
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sum = sum
        for (y in 0 until height) {
            val rowBase = y * rowStride
            val dstBase = y * width
            if (pixelStride == 2) {
                src.limit(src.capacity())
                src.position(rowBase)
                val row = src.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (x in 0 until width) sum[dstBase + x] += row.get(x).toInt() and 0xFFFF
            } else {
                for (x in 0 until width) {
                    val p = rowBase + x * pixelStride
                    sum[dstBase + x] += (src.get(p).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8)
                }
            }
        }
        frames++
    }

    /** Convenience for tests and for frames already copied to a ShortArray. */
    fun add(pixels: ShortArray) {
        require(pixels.size >= pixelCount)
        val bb = ByteBuffer.allocate(pixelCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pixels, 0, pixelCount)
        add(bb, width * 2, 2)
    }

    fun reset() {
        java.util.Arrays.fill(sum, 0)
        frames = 0
    }

    /** Signal above black for every pixel (never negative). */
    fun signal(): IntArray {
        val black = frames.toLong() * blackLevel
        val out = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val v = sum[i] - black
            out[i] = if (v > 0) v.toInt() else 0
        }
        return out
    }

    fun result(): StackResult = StackResult(width, height, signal(), frames, rangePerFrame)
}

/**
 * A stacked image: black-subtracted signal sums over [frames] frames, each of which could
 * hold at most [rangePerFrame] counts. [maxSignal] is therefore the clipping point of the
 * whole stack.
 */
class StackResult(
    val width: Int,
    val height: Int,
    val signal: IntArray,
    val frames: Int,
    val rangePerFrame: Int,
) {
    val pixelCount: Int get() = width * height
    val maxSignal: Long get() = frames.toLong() * rangePerFrame

    /** Per-pixel maximum of two stacks with the same geometry (star trails). */
    fun lightenInPlace(other: StackResult) {
        require(other.pixelCount == pixelCount)
        val a = signal
        val b = other.signal
        for (i in 0 until pixelCount) if (b[i] > a[i]) a[i] = b[i]
    }

    fun copy(): StackResult = StackResult(width, height, signal.copyOf(), frames, rangePerFrame)

    /**
     * Scales the signal into 16 bits for a DNG whose BlackLevel is 0 and WhiteLevel is
     * [Dng16.whiteLevel], preserving the whole dynamic range of the stack.
     */
    fun toDng16(): Dng16 {
        val max = maxOf(1L, maxSignal)
        val white = minOf(65535L, max).toInt()
        val scale = white.toDouble() / max
        val data = ShortArray(pixelCount)
        val s = signal
        for (i in 0 until pixelCount) {
            val v = (s[i] * scale + 0.5).toInt().coerceIn(0, white)
            data[i] = v.toShort()
        }
        return Dng16(data, white, scale)
    }
}

/** 16-bit linear data (little-endian when written), with the white level it was scaled to. */
class Dng16(val data: ShortArray, val whiteLevel: Int, val scale: Double) {
    fun toByteBuffer(): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(data)
        bb.rewind()
        return bb
    }
}
