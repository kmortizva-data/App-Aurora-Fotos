package com.aurorafotos.stacking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawStackerTest {
    private fun frame(vararg v: Int): ShortArray = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun signalIsSumAboveBlack() {
        val s = RawStacker(2, 2, blackLevel = 64, whiteLevel = 1023)
        s.add(frame(100, 200, 300, 64))
        s.add(frame(100, 200, 300, 60)) // below black clamps to 0 overall
        s.add(frame(100, 200, 300, 64))
        assertEquals(3, s.frames)
        assertArrayEquals(intArrayOf(108, 408, 708, 0), s.signal())
        val r = s.result()
        assertEquals(3L * 959, r.maxSignal)
    }

    @Test
    fun lightenKeepsPerPixelMaximum() {
        val a = StackResult(3, 1, intArrayOf(10, 4000, 7), 1, 959)
        val b = StackResult(3, 1, intArrayOf(4095, 5, 7), 1, 959)
        a.lightenInPlace(b)
        assertArrayEquals(intArrayOf(4095, 4000, 7), a.signal)
    }

    @Test
    fun honoursRowStridePadding() {
        val s = RawStacker(2, 2, 0, 1023)
        val bb = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        sb.put(0, 1); sb.put(1, 2); sb.put(2, 999); sb.put(3, 999)
        sb.put(4, 3); sb.put(5, 4); sb.put(6, 999); sb.put(7, 999)
        s.add(bb, rowStride = 8, pixelStride = 2)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), s.signal())
    }

    @Test
    fun valuesAbove32767AreTreatedAsUnsigned() {
        val s = RawStacker(1, 1, 0, 65535)
        s.add(frame(60000))
        assertEquals(60000, s.signal()[0])
    }

    @Test
    fun dng16ScalesWholeStackIntoSixteenBits() {
        // 270 frames of 10-bit: max signal 270*959 = 258930 > 65535 → scaled down.
        val n = 270
        val r = StackResult(2, 1, intArrayOf(258930, 129465), n, 959)
        val d = r.toDng16()
        assertEquals(65535, d.whiteLevel)
        assertEquals(65535, d.data[0].toInt() and 0xFFFF)
        assertEquals(32768, d.data[1].toInt() and 0xFFFF)
        // A single frame is scaled up to use the 16 bits.
        val one = StackResult(1, 1, intArrayOf(959), 1, 959).toDng16()
        assertEquals(959, one.whiteLevel)
        assertEquals(959, one.data[0].toInt())
    }

    @Test
    fun manyFramesDoNotOverflow() {
        val s = RawStacker(1, 1, 64, 1023)
        repeat(5000) { s.add(frame(1023)) }
        assertEquals(5000 * 959, s.signal()[0])
    }
}
