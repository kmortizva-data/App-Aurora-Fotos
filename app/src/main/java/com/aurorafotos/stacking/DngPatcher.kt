package com.aurorafotos.stacking

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites a few IFD tags of a DNG produced by [android.hardware.camera2.DngCreator] so a
 * stacked image can carry its own levels and exposure:
 *
 *  - WhiteLevel (50717) and BlackLevel (50714): the stack is written as 16-bit linear data
 *    scaled to its own white point, so the camera's 10-bit levels no longer apply.
 *  - BaselineExposure (50730): log2 of the number of summed frames, so Lightroom/Camera Raw
 *    renders the stack as the long exposure it is while keeping highlights recoverable.
 *
 * Only the tags above are touched. The patched IFD is appended at the end of the file and
 * the pointer to it is updated, so existing data offsets remain valid. Pure JVM code.
 */
object DngPatcher {
    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_SUB_IFDS = 330
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_BASELINE_EXPOSURE = 50730

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int, val raw: ByteArray)

    class PatchException(msg: String) : RuntimeException(msg)

    /**
     * @param baselineExposureEv value for BaselineExposure (e.g. log2(frames)); null leaves it out.
     */
    fun patch(dng: ByteArray, whiteLevel: Int, blackLevel: Int, baselineExposureEv: Double?): ByteArray {
        val order = when {
            dng.size > 8 && dng[0] == 'I'.code.toByte() && dng[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            dng.size > 8 && dng[0] == 'M'.code.toByte() && dng[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw PatchException("not a TIFF/DNG")
        }
        val buf = ByteBuffer.wrap(dng).order(order)
        if (buf.getShort(2).toInt() != 42) throw PatchException("bad TIFF magic")
        val ifd0 = buf.getInt(4)

        // Locate the IFD holding the raw image (NewSubFileType == 0): IFD0 or one of its SubIFDs.
        var rawIfd = ifd0
        var pointerPos = 4 // where the offset of rawIfd is stored
        val ifd0Entries = readEntries(buf, ifd0)
        val subfile0 = ifd0Entries.firstOrNull { it.tag == TAG_NEW_SUBFILE_TYPE }?.let { readLongValue(buf, it, 0) } ?: 0L
        if (subfile0 != 0L) {
            val sub = ifd0Entries.firstOrNull { it.tag == TAG_SUB_IFDS } ?: throw PatchException("thumbnail IFD without SubIFDs")
            // First SubIFD pointer: inline when count == 1, otherwise at its offset.
            pointerPos = if (sub.count * typeSize(sub.type) <= 4) ifd0 + 2 + ifd0Entries.indexOf(sub) * 12 + 8 else sub.valueOrOffset
            rawIfd = buf.getInt(pointerPos)
        }

        val entries = readEntries(buf, rawIfd)
        val nextIfd = buf.getInt(rawIfd + 2 + entries.size * 12)

        // Rewrite levels first: values stored at an offset are patched in place inside [dng].
        val newEntries = ArrayList<Entry>()
        for (e in entries) {
            when (e.tag) {
                TAG_WHITE_LEVEL -> newEntries += rewriteIntegers(buf, e, IntArray(e.count) { whiteLevel }, order)
                TAG_BLACK_LEVEL -> newEntries += rewriteIntegers(buf, e, IntArray(e.count) { blackLevel }, order)
                TAG_BASELINE_EXPOSURE -> if (baselineExposureEv == null) newEntries += e // replaced below otherwise
                else -> newEntries += e
            }
        }

        val out = java.io.ByteArrayOutputStream(dng.size + 64)
        out.write(dng)
        var eof = dng.size
        fun align() { if (eof % 2 != 0) { out.write(0); eof++ } }

        if (baselineExposureEv != null) {
            // SRATIONAL data lives at an offset: append it.
            align()
            val dataOffset = eof
            val num = Math.round(baselineExposureEv * 10000).toInt()
            val data = ByteBuffer.allocate(8).order(order).putInt(num).putInt(10000).array()
            out.write(data); eof += 8
            newEntries += makeEntry(TAG_BASELINE_EXPOSURE, TYPE_SRATIONAL, 1, dataOffset, order)
        }
        newEntries.sortBy { it.tag }

        // Append the rebuilt IFD and repoint.
        align()
        val newIfdOffset = eof
        val ifdBytes = ByteBuffer.allocate(2 + newEntries.size * 12 + 4).order(order)
        ifdBytes.putShort(newEntries.size.toShort())
        for (e in newEntries) ifdBytes.put(e.raw)
        ifdBytes.putInt(nextIfd)
        out.write(ifdBytes.array()); eof += ifdBytes.capacity()

        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(order).putInt(pointerPos, newIfdOffset)
        return result
    }

    private fun readEntries(buf: ByteBuffer, ifd: Int): List<Entry> {
        if (ifd <= 0 || ifd + 2 > buf.capacity()) throw PatchException("bad IFD offset $ifd")
        val n = buf.getShort(ifd).toInt() and 0xFFFF
        val list = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val p = ifd + 2 + i * 12
            val raw = ByteArray(12)
            buf.position(p); buf.get(raw); buf.position(0)
            val tag = buf.getShort(p).toInt() and 0xFFFF
            val type = buf.getShort(p + 2).toInt() and 0xFFFF
            val count = buf.getInt(p + 4)
            val v = buf.getInt(p + 8)
            list += Entry(tag, type, count, v, raw)
        }
        return list
    }

    private fun readLongValue(buf: ByteBuffer, e: Entry, index: Int): Long {
        val inline = e.count * typeSize(e.type) <= 4
        val base = e.valueOrOffset
        return when (e.type) {
            TYPE_SHORT -> if (inline) readInlineShort(buf, e, index).toLong() else (buf.getShort(base + index * 2).toInt() and 0xFFFF).toLong()
            TYPE_LONG -> if (inline) e.valueOrOffset.toLong() and 0xFFFFFFFFL else buf.getInt(base + index * 4).toLong() and 0xFFFFFFFFL
            else -> 0L
        }
    }

    private fun readInlineShort(buf: ByteBuffer, e: Entry, index: Int): Int {
        val bb = ByteBuffer.wrap(e.raw).order(buf.order())
        return bb.getShort(8 + index * 2).toInt() and 0xFFFF
    }

    /** Returns a copy of [e] whose integer values are replaced (in place when they live at an offset). */
    private fun rewriteIntegers(buf: ByteBuffer, e: Entry, values: IntArray, order: ByteOrder): Entry {
        val size = typeSize(e.type)
        val inline = e.count * size <= 4
        if (inline) {
            val raw = e.raw.copyOf()
            val bb = ByteBuffer.wrap(raw).order(order)
            when (e.type) {
                TYPE_SHORT -> for (i in values.indices) bb.putShort(8 + i * 2, values[i].toShort())
                TYPE_LONG -> bb.putInt(8, values[0])
                else -> throw PatchException("unsupported inline type ${e.type} for tag ${e.tag}")
            }
            return Entry(e.tag, e.type, e.count, bb.getInt(8), raw)
        }
        // Values live at an offset inside the original file: overwrite them there.
        val p = e.valueOrOffset
        when (e.type) {
            TYPE_SHORT -> for (i in values.indices) buf.putShort(p + i * 2, values[i].toShort())
            TYPE_LONG -> for (i in values.indices) buf.putInt(p + i * 4, values[i])
            TYPE_RATIONAL -> for (i in values.indices) { buf.putInt(p + i * 8, values[i]); buf.putInt(p + i * 8 + 4, 1) }
            else -> throw PatchException("unsupported type ${e.type} for tag ${e.tag}")
        }
        return e
    }

    private fun makeEntry(tag: Int, type: Int, count: Int, valueOrOffset: Int, order: ByteOrder): Entry {
        val raw = ByteBuffer.allocate(12).order(order)
            .putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(valueOrOffset).array()
        return Entry(tag, type, count, valueOrOffset, raw)
    }

    /** Reads back an integer tag (first value) for verification; null if absent. */
    fun readTag(dng: ByteArray, tag: Int): Long? {
        val order = if (dng[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(dng).order(order)
        var ifd = buf.getInt(4)
        var entries = readEntries(buf, ifd)
        val subfile = entries.firstOrNull { it.tag == TAG_NEW_SUBFILE_TYPE }?.let { readLongValue(buf, it, 0) } ?: 0L
        if (subfile != 0L) {
            val sub = entries.firstOrNull { it.tag == TAG_SUB_IFDS } ?: return null
            ifd = if (sub.count * typeSize(sub.type) <= 4) sub.valueOrOffset else buf.getInt(sub.valueOrOffset)
            entries = readEntries(buf, ifd)
        }
        val e = entries.firstOrNull { it.tag == tag } ?: return null
        return when (e.type) {
            TYPE_SHORT, TYPE_LONG -> readLongValue(buf, e, 0)
            TYPE_RATIONAL, TYPE_SRATIONAL -> buf.getInt(e.valueOrOffset).toLong()
            else -> null
        }
    }

    /** Reads BaselineExposure as a double, or null. */
    fun readBaselineExposure(dng: ByteArray): Double? {
        val order = if (dng[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(dng).order(order)
        val entries = readEntries(buf, buf.getInt(4))
        val e = entries.firstOrNull { it.tag == TAG_BASELINE_EXPOSURE } ?: return null
        val num = buf.getInt(e.valueOrOffset)
        val den = buf.getInt(e.valueOrOffset + 4)
        return num.toDouble() / den
    }
}
