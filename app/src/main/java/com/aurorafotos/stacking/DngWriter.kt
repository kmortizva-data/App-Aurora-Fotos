package com.aurorafotos.stacking

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.util.Size
import java.io.OutputStream
import java.nio.ByteBuffer

object DngWriter {
    /** Writes a RAW_SENSOR [Image] straight to DNG. */
    fun write(
        out: OutputStream,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        orientation: Int,
        description: String? = null,
    ) {
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(orientation)
            if (description != null) dng.setDescription(description)
            dng.writeImage(out, image)
        }
    }

    /** Writes a tightly packed little-endian RAW16 buffer (e.g. a stacked result) to DNG. */
    fun write(
        out: OutputStream,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        orientation: Int,
        description: String? = null,
    ) {
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(orientation)
            if (description != null) dng.setDescription(description)
            dng.writeByteBuffer(out, Size(width, height), pixels, 0)
        }
    }
}
