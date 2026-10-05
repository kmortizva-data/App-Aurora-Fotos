package com.aurorafotos.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraInfoTest {
    @Test
    fun picksLargest16by9UpTo4k() {
        val sizes = listOf(7680 to 4320, 4000 to 3000, 3840 to 2160, 1920 to 1080, 1280 to 720)
        assertEquals(3840 to 2160, CameraInfo.pickVideoSize(sizes))
    }

    @Test
    fun fallsBackWhenNo16by9() {
        assertEquals(3200 to 2400, CameraInfo.pickVideoSize(listOf(3200 to 2400, 640 to 480)))
        assertEquals(1920 to 1080, CameraInfo.pickVideoSize(emptyList()))
    }

    @Test
    fun awbPresetNearestKelvin() {
        val all = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(2, CameraInfo.awbModeForKelvin(2900, all)) // incandescent
        assertEquals(1, CameraInfo.awbModeForKelvin(0, all))    // auto
    }
}
