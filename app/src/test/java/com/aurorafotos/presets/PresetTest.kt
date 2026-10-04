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
        assertTrue(Presets.ALL.all { it.framesPerShot in 1..128 })
    }
}
