package com.aurorafotos.video

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapseEncoderTest {
    @Test
    fun centreCropFrom4by3To16by9CutsTopAndBottom() {
        val r = TimelapseEncoder.centreCrop(4000, 3000, 3840, 2160)
        assertEquals(0, r.left); assertEquals(4000, r.right)
        assertEquals(2250, r.height)
        assertEquals(375, r.top)
    }

    @Test
    fun centreCropFromWideSourceCutsSides() {
        val r = TimelapseEncoder.centreCrop(4000, 1000, 1920, 1080)
        assertEquals(1000, r.height)
        assertEquals(1777, r.width)
    }

    @Test
    fun picks4kOnlyWhenSourceIsWideEnough() {
        assertEquals(3840 to 2160, TimelapseEncoder.outputSizeFor(4000))
        assertEquals(1920 to 1080, TimelapseEncoder.outputSizeFor(3000))
    }
}
