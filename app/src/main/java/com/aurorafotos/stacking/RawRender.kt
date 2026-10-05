package com.aurorafotos.stacking

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import java.io.ByteArrayOutputStream

/** Android glue around [RawDemosaic]: pulls WB/colour data from Camera2 and builds Bitmaps. */
object RawRender {
    fun cfaOf(characteristics: CameraCharacteristics): Int =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: RawDemosaic.RGGB

    fun gainsOf(result: CaptureResult?): FloatArray {
        val g: RggbChannelVector = result?.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return floatArrayOf(2.0f, 1f, 1f, 1.7f)
        return floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
    }

    fun matrixOf(result: CaptureResult?): FloatArray? {
        val t: ColorSpaceTransform = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        val m = FloatArray(9)
        for (r in 0..2) for (c in 0..2) m[r * 3 + c] = t.getElement(c, r).toFloat()
        return m
    }

    /**
     * Renders a stack. [exposureFrames] = how many frames' worth of signal should map to white
     * before auto gain: 1 renders the stack as a long exposure (N× brighter than one frame),
     * [StackResult.frames] renders it like the average of its frames.
     */
    fun render(
        stack: StackResult,
        cfa: Int,
        result: CaptureResult?,
        exposureFrames: Int,
        halfRes: Boolean,
        autoGainMax: Double = 8.0,
    ): Bitmap {
        val white = maxOf(1.0, exposureFrames.toDouble() * stack.rangePerFrame)
        val gain = RawDemosaic.autoGain(stack.signal, stack.width, stack.height, cfa, white, maxGain = autoGainMax)
        val params = RawDemosaic.Params(white = white, gains = gainsOf(result), colorMatrix = matrixOf(result), gain = gain)
        return if (halfRes) {
            val px = RawDemosaic.renderHalf(stack.signal, stack.width, stack.height, cfa, params)
            Bitmap.createBitmap(px, stack.width / 2, stack.height / 2, Bitmap.Config.ARGB_8888)
        } else {
            val px = RawDemosaic.renderFull(stack.signal, stack.width, stack.height, cfa, params)
            Bitmap.createBitmap(px, stack.width, stack.height, Bitmap.Config.ARGB_8888)
        }
    }

    fun rotated(bm: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bm
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        if (out !== bm) bm.recycle()
        return out
    }

    fun jpeg(bm: Bitmap, quality: Int = 94): ByteArray {
        val bos = ByteArrayOutputStream(bm.byteCount / 10)
        bm.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }
}
