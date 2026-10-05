package com.aurorafotos.stacking

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DngPatcherTest {
    /** Builds a tiny little-endian TIFF with one IFD: NewSubFileType, BlackLevel (LONG×4 at offset), WhiteLevel (SHORT inline). */
    private fun tinyDng(): ByteArray {
        val bb = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        // IFD at 8: 3 entries
        bb.position(8)
        bb.putShort(3)
        bb.putShort(254).putShort(4).putInt(1).putInt(0)               // NewSubFileType = 0
        bb.putShort(50714.toShort()).putShort(4).putInt(4).putInt(64)   // BlackLevel: 4 LONGs at offset 64
        bb.putShort(50717.toShort()).putShort(3).putInt(1).putInt(1023) // WhiteLevel SHORT inline
        bb.putInt(0) // next IFD
        bb.position(64)
        repeat(4) { bb.putInt(64) }
        return bb.array().copyOf(80)
    }

    @Test
    fun patchesLevelsAndAddsBaselineExposure() {
        val src = tinyDng()
        val out = DngPatcher.patch(src, whiteLevel = 65535, blackLevel = 0, baselineExposureEv = 8.0771)
        assertEquals(65535L, DngPatcher.readTag(out, 50717))
        assertEquals(0L, DngPatcher.readTag(out, 50714))
        val be = DngPatcher.readBaselineExposure(out)
        assertNotNull(be)
        assertEquals(8.0771, be!!, 0.001)
        // The original bytes are still there (data offsets stay valid).
        assertEquals(src[8], out[8])
    }

    @Test
    fun patchWithoutBaselineKeepsEntryCount() {
        val src = tinyDng()
        val out = DngPatcher.patch(src, 4000, 10, null)
        assertEquals(4000L, DngPatcher.readTag(out, 50717))
        assertEquals(10L, DngPatcher.readTag(out, 50714))
        assertEquals(null, DngPatcher.readBaselineExposure(out))
    }
}
