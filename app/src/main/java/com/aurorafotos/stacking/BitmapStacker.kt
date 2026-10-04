package com.aurorafotos.stacking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aurorafotos.presets.StackMode

/**
 * Same idea as [RawStacker] but on decoded JPEGs (sRGB, 8-bit). Used to produce the
 * viewable JPEG of a stacked shot and the running star-trail image for the video.
 * Averaging in gamma space is not physically exact but looks right for previews.
 *
 * Memory: sums are kept in ShortArrays (max 128 frames of 255), maxima in ByteArrays,
 * so a 12 MP stack costs ~72 MB (sum) or ~36 MB (lighten).
 */
class BitmapStacker(val width: Int, val height: Int, val mode: StackMode) {
    private val n = width * height
    private val lighten = mode == StackMode.LIGHTEN
    private val sum: ShortArray? = if (lighten) null else ShortArray(n * 3)
    private val max: ByteArray? = if (lighten) ByteArray(n * 3) else null
    private val px = IntArray(width) // scratch row
    var frames = 0
        private set

    fun add(bitmap: Bitmap) {
        require(bitmap.width == width && bitmap.height == height) { "size mismatch" }
        check(frames < MAX_FRAMES) { "too many frames for a 16-bit sum" }
        val sum = sum
        val max = max
        for (y in 0 until height) {
            bitmap.getPixels(px, 0, width, 0, y, width, 1)
            var i = y * width * 3
            for (x in 0 until width) {
                val c = px[x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                if (max != null) {
                    if (cr > (max[i].toInt() and 0xFF)) max[i] = cr.toByte()
                    if (cg > (max[i + 1].toInt() and 0xFF)) max[i + 1] = cg.toByte()
                    if (cb > (max[i + 2].toInt() and 0xFF)) max[i + 2] = cb.toByte()
                } else if (sum != null) {
                    sum[i] = (sum[i] + cr).toShort()
                    sum[i + 1] = (sum[i + 1] + cg).toShort()
                    sum[i + 2] = (sum[i + 2] + cb).toShort()
                }
                i += 3
            }
        }
        frames++
    }

    fun addJpeg(jpeg: ByteArray) {
        val bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("JPEG decode failed")
        try {
            add(bm)
        } finally {
            bm.recycle()
        }
    }

    fun result(): Bitmap {
        check(frames > 0)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val sum = sum
        val max = max
        for (y in 0 until height) {
            var i = y * width * 3
            for (x in 0 until width) {
                val cr: Int
                val cg: Int
                val cb: Int
                if (max != null) {
                    cr = max[i].toInt() and 0xFF
                    cg = max[i + 1].toInt() and 0xFF
                    cb = max[i + 2].toInt() and 0xFF
                } else {
                    val sr = sum!![i].toInt()
                    val sg = sum[i + 1].toInt()
                    val sb = sum[i + 2].toInt()
                    if (mode == StackMode.ADD) {
                        cr = minOf(255, sr); cg = minOf(255, sg); cb = minOf(255, sb)
                    } else {
                        cr = (sr + frames / 2) / frames
                        cg = (sg + frames / 2) / frames
                        cb = (sb + frames / 2) / frames
                    }
                }
                row[x] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                i += 3
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    companion object {
        const val MAX_FRAMES = 128

        fun jpegSize(jpeg: ByteArray): Pair<Int, Int> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            return o.outWidth to o.outHeight
        }
    }
}
