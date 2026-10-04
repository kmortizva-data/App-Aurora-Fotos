package com.aurorafotos.stacking

import com.aurorafotos.presets.StackMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawStackerTest {
    private fun frame(vararg v: Int): ShortArray = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun averageIsRoundedMean() {
        val s = RawStacker(2, 2, StackMode.AVERAGE, blackLevel = 64, whiteLevel = 1023)
        s.add(frame(100, 200, 300, 400))
        s.add(frame(101, 202, 303, 404))
        s.add(frame(102, 204, 306, 408))
        assertEquals(3, s.frames)
        assertArrayEquals(frame(101, 202, 303, 404), s.resultPixels())
    }

    @Test
    fun addSumsSignalAboveBlackAndClampsToWhite() {
        val s = RawStacker(2, 1, StackMode.ADD, blackLevel = 64, whiteLevel = 1023)
        s.add(frame(164, 900))
        s.add(frame(164, 900))
        s.add(frame(164, 900))
        // (164-64)*3 + 64 = 364 ; 900 -> (836*3)+64 clamps to 1023
        assertArrayEquals(frame(364, 1023), s.resultPixels())
    }

    @Test
    fun lightenKeepsPerPixelMaximum() {
        val s = RawStacker(3, 1, StackMode.LIGHTEN, blackLevel = 0, whiteLevel = 4095)
        s.add(frame(10, 4000, 7))
        s.add(frame(4095, 5, 7))
        assertArrayEquals(frame(4095, 4000, 7), s.resultPixels())
    }

    @Test
    fun honoursRowStridePadding() {
        // 2 px wide rows padded to 8 bytes (rowStride) instead of 4.
        val s = RawStacker(2, 2, StackMode.AVERAGE, 0, 1023)
        val bb = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        sb.put(0, 1); sb.put(1, 2); sb.put(2, 999); sb.put(3, 999) // row 0 + padding
        sb.put(4, 3); sb.put(5, 4); sb.put(6, 999); sb.put(7, 999) // row 1 + padding
        s.add(bb, rowStride = 8, pixelStride = 2)
        assertArrayEquals(frame(1, 2, 3, 4), s.resultPixels())
    }

    @Test
    fun valuesAbove32767AreTreatedAsUnsigned() {
        val s = RawStacker(1, 1, StackMode.AVERAGE, 0, 65535)
        s.add(frame(60000))
        s.add(frame(60000))
        assertEquals(60000, s.resultPixels()[0].toInt() and 0xFFFF)
    }
}
