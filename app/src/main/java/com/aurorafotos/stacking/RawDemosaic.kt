package com.aurorafotos.stacking

/**
 * Minimal Bayer → sRGB renderer for the stacked signal, so the app can produce JPEGs and
 * video frames without asking the camera for a JPEG per frame. Pure JVM: outputs packed
 * ARGB ints; the Android wrapper turns them into a Bitmap.
 *
 * Pipeline: bilinear demosaic (or 2×2 superpixel at half resolution) → white-balance gains →
 * 3×3 colour matrix (sensor → linear sRGB) → exposure gain → sRGB gamma.
 */
object RawDemosaic {
    /** CFA layout as in CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. */
    const val RGGB = 0
    const val GRBG = 1
    const val GBRG = 2
    const val BGGR = 3

    class Params(
        /** Signal value that maps to 1.0 before [gain] (e.g. one frame's range for an N× "long exposure"). */
        val white: Double,
        /** R, G(even row), G(odd row), B gains as in RggbChannelVector. */
        val gains: FloatArray = floatArrayOf(2.0f, 1f, 1f, 1.6f),
        /** Row-major 3×3 sensor-RGB → linear sRGB; null = identity. */
        val colorMatrix: FloatArray? = null,
        val gain: Double = 1.0,
    )

    private val gammaLut: IntArray by lazy {
        IntArray(4097) { i ->
            val c = i / 4096.0
            val s = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
            (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    /** Exposure gain so that the given percentile of the green signal lands at [target]. */
    fun autoGain(signal: IntArray, width: Int, height: Int, cfa: Int, white: Double, percentile: Double = 0.995, target: Double = 0.85, maxGain: Double = 64.0): Double {
        val bins = 4096
        val hist = IntArray(bins)
        var total = 0
        val (gx, gy) = greenOffset(cfa)
        var y = gy
        while (y < height) {
            var x = gx
            val base = y * width
            while (x < width) {
                val v = signal[base + x] / white
                hist[(v * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
                total++
                x += 4
            }
            y += 3
        }
        if (total == 0) return 1.0
        var acc = 0
        var bin = bins - 1
        for (i in 0 until bins) {
            acc += hist[i]
            if (acc >= total * percentile) { bin = i; break }
        }
        val p = maxOf(bin, 1) / (bins - 1).toDouble()
        return (target / p).coerceIn(1.0, maxGain)
    }

    private fun greenOffset(cfa: Int): Pair<Int, Int> = when (cfa) {
        RGGB, BGGR -> 1 to 0
        else -> 0 to 0
    }

    /** Colour of the CFA site at (x, y): 0 = R, 1 = G, 2 = B. */
    fun siteColor(cfa: Int, x: Int, y: Int): Int {
        val xo = x and 1
        val yo = y and 1
        return when (cfa) {
            RGGB -> if (yo == 0) (if (xo == 0) 0 else 1) else (if (xo == 0) 1 else 2)
            GRBG -> if (yo == 0) (if (xo == 0) 1 else 0) else (if (xo == 0) 2 else 1)
            GBRG -> if (yo == 0) (if (xo == 0) 1 else 2) else (if (xo == 0) 0 else 1)
            else -> if (yo == 0) (if (xo == 0) 2 else 1) else (if (xo == 0) 1 else 0) // BGGR
        }
    }

    /** Full-resolution bilinear demosaic. Returns width*height ARGB pixels. */
    fun renderFull(signal: IntArray, width: Int, height: Int, cfa: Int, p: Params): IntArray {
        val out = IntArray(width * height)
        val m = p.colorMatrix
        val kR = p.gains[0] * p.gain / p.white
        val kG0 = p.gains[1] * p.gain / p.white
        val kG1 = p.gains[2] * p.gain / p.white
        val kB = p.gains[3] * p.gain / p.white
        val lut = gammaLut
        val w = width
        val h = height
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else y + 1
            val yp = if (y < h - 1) y + 1 else y - 1
            val row = y * w
            val rowM = ym * w
            val rowP = yp * w
            val kG = if ((y and 1) == 0) kG0 else kG1
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x + 1
                val xp = if (x < w - 1) x + 1 else x - 1
                val c = signal[row + x].toDouble()
                val hAvg = (signal[row + xm] + signal[row + xp]) * 0.5
                val vAvg = (signal[rowM + x] + signal[rowP + x]) * 0.5
                val dAvg = (signal[rowM + xm] + signal[rowM + xp] + signal[rowP + xm] + signal[rowP + xp]) * 0.25
                val r: Double
                val g: Double
                val b: Double
                when (siteColor(cfa, x, y)) {
                    0 -> { r = c; g = (hAvg + vAvg) * 0.5; b = dAvg }
                    2 -> { b = c; g = (hAvg + vAvg) * 0.5; r = dAvg }
                    else -> {
                        g = c
                        // Neighbours left/right share the row: their colour is the other non-green of this row.
                        val rowHasRed = siteColor(cfa, xm, y) == 0
                        if (rowHasRed) { r = hAvg; b = vAvg } else { b = hAvg; r = vAvg }
                    }
                }
                out[row + x] = pack(r * kR, g * kG, b * kB, m, lut)
            }
        }
        return out
    }

    /** Half-resolution 2×2 superpixel render (fast; for video frames). Returns (w/2)*(h/2) pixels. */
    fun renderHalf(signal: IntArray, width: Int, height: Int, cfa: Int, p: Params): IntArray {
        val hw = width / 2
        val hh = height / 2
        val out = IntArray(hw * hh)
        val m = p.colorMatrix
        val kR = p.gains[0] * p.gain / p.white
        val kG = (p.gains[1] + p.gains[2]) * 0.5 * p.gain / p.white
        val kB = p.gains[3] * p.gain / p.white
        val lut = gammaLut
        // Offsets of the R, B and two G sites inside each 2×2 block.
        var rOff = 0; var bOff = 0
        val gOffs = IntArray(2); var gi = 0
        for (yy in 0..1) for (xx in 0..1) {
            val off = yy * width + xx
            when (siteColor(cfa, xx, yy)) {
                0 -> rOff = off
                2 -> bOff = off
                else -> gOffs[gi++] = off
            }
        }
        val g1Off = gOffs[0]; val g2Off = gOffs[1]
        for (y in 0 until hh) {
            val src = (y * 2) * width
            val dst = y * hw
            for (x in 0 until hw) {
                val i = src + x * 2
                val r = signal[i + rOff].toDouble()
                val g = (signal[i + g1Off] + signal[i + g2Off]) * 0.5
                val b = signal[i + bOff].toDouble()
                out[dst + x] = pack(r * kR, g * kG, b * kB, m, lut)
            }
        }
        return out
    }

    private fun pack(r0: Double, g0: Double, b0: Double, m: FloatArray?, lut: IntArray): Int {
        var r = r0; var g = g0; var b = b0
        if (m != null) {
            val nr = m[0] * r + m[1] * g + m[2] * b
            val ng = m[3] * r + m[4] * g + m[5] * b
            val nb = m[6] * r + m[7] * g + m[8] * b
            r = nr; g = ng; b = nb
        }
        val ri = lut[(r * 4096).toInt().coerceIn(0, 4096)]
        val gi = lut[(g * 4096).toInt().coerceIn(0, 4096)]
        val bi = lut[(b * 4096).toInt().coerceIn(0, 4096)]
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
