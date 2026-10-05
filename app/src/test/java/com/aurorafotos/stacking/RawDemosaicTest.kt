package com.aurorafotos.stacking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDemosaicTest {
    @Test
    fun siteColorsFollowThePattern() {
        // RGGB: row 0 = R G, row 1 = G B
        assertEquals(0, RawDemosaic.siteColor(RawDemosaic.RGGB, 0, 0))
        assertEquals(1, RawDemosaic.siteColor(RawDemosaic.RGGB, 1, 0))
        assertEquals(1, RawDemosaic.siteColor(RawDemosaic.RGGB, 0, 1))
        assertEquals(2, RawDemosaic.siteColor(RawDemosaic.RGGB, 1, 1))
        assertEquals(2, RawDemosaic.siteColor(RawDemosaic.BGGR, 0, 0))
        assertEquals(0, RawDemosaic.siteColor(RawDemosaic.GRBG, 1, 0))
        assertEquals(0, RawDemosaic.siteColor(RawDemosaic.GBRG, 0, 1))
    }

    @Test
    fun flatGreyRendersGreyAtBothResolutions() {
        val w = 4; val h = 4
        val signal = IntArray(w * h) { 500 }
        val p = RawDemosaic.Params(white = 1000.0, gains = floatArrayOf(1f, 1f, 1f, 1f), colorMatrix = null, gain = 1.0)
        val full = RawDemosaic.renderFull(signal, w, h, RawDemosaic.RGGB, p)
        val half = RawDemosaic.renderHalf(signal, w, h, RawDemosaic.RGGB, p)
        for (px in full + half) {
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            assertEquals(r, g); assertEquals(g, b)
            assertTrue("sRGB of 0.5 linear is ~188, got $r", r in 185..190)
        }
    }

    @Test
    fun pureRedSceneRendersRed() {
        val w = 4; val h = 4
        val signal = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (RawDemosaic.siteColor(RawDemosaic.RGGB, x, y) == 0) 1000 else 0
        }
        val p = RawDemosaic.Params(white = 1000.0, gains = floatArrayOf(1f, 1f, 1f, 1f))
        val half = RawDemosaic.renderHalf(signal, w, h, RawDemosaic.RGGB, p)
        val px = half[0]
        assertEquals(255, (px shr 16) and 0xFF)
        assertEquals(0, (px shr 8) and 0xFF)
        assertEquals(0, px and 0xFF)
        val full = RawDemosaic.renderFull(signal, w, h, RawDemosaic.RGGB, p)
        assertEquals(255, (full[0] shr 16) and 0xFF) // R site keeps its own value
        assertEquals(0, full[0] and 0xFF)
    }

    @Test
    fun autoGainBrightensDarkScenesButIsCapped() {
        val w = 8; val h = 8
        val dark = IntArray(w * h) { 10 } // 1% of white
        val g = RawDemosaic.autoGain(dark, w, h, RawDemosaic.RGGB, white = 1000.0, maxGain = 8.0)
        assertEquals(8.0, g, 1e-9)
        val bright = IntArray(w * h) { 900 }
        assertEquals(1.0, RawDemosaic.autoGain(bright, w, h, RawDemosaic.RGGB, 1000.0), 1e-9)
    }
}
