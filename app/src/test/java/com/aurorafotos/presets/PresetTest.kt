package com.aurorafotos.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {
    @Test
    fun roundTripPreservesEveryField() {
        for (p in Presets.ALL) {
            val back = Preset.deserialize(p.serialize(), Presets.AURORA_PHOTO)
            assertEquals(p, back)
        }
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val p = Preset.deserialize("iso=50\nstackMode=BOGUS\n", Presets.MILKY_WAY)
        assertEquals(50, p.iso)
        assertEquals(Presets.MILKY_WAY.framesPerShot, p.framesPerShot)
        assertEquals(Presets.MILKY_WAY.stackMode, p.stackMode)
    }

    @Test
    fun presetIdsAreUnique() {
        assertEquals(Presets.ALL.size, Presets.ALL.map { it.id }.toSet().size)
        assertTrue(Presets.ALL.all { it.framesPerShot in 1..Preset.MAX_FRAMES })
    }

    @Test
    fun effectiveFramesFollowsTheRealExposure() {
        val le = Presets.LONG_EXPOSURE // 30 s target
        assertEquals(8, le.effectiveFrames(3_900_000_000L))   // S24 Ultra expected cap
        assertEquals(60, le.effectiveFrames(500_000_000L))    // older Samsung cap
        assertEquals(1, le.effectiveFrames(30 * Preset.SEC))
        assertEquals(Preset.MAX_FRAMES, le.effectiveFrames(1_000_000L))
        assertEquals(271, le.effectiveFrames(111_111_111L))   // the S24 Ultra's real cap (ceil)
        val fixed = Presets.STAR_TRAILS.copy(totalExposureNs = 0, framesPerShot = 5)
        assertEquals(5, fixed.effectiveFrames(3_900_000_000L)) // no target: fixed frames
    }
}
