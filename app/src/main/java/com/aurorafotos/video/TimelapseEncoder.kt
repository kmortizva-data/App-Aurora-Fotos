package com.aurorafotos.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * Turns a sequence of bitmaps into an MP4 (HEVC, falling back to AVC) using the encoder's
 * input Surface. Presentation timestamps are rewritten from the frame index so the video
 * plays at exactly [fps] regardless of how fast frames were produced.
 */
class TimelapseEncoder(
    val width: Int = 3840,
    val height: Int = 2160,
    val fps: Int = 24,
    val bitrate: Int = 40_000_000,
) {
    private val tag = "TimelapseEncoder"

    /**
     * [frameCount] frames are pulled lazily through [frameAt] (return null to skip a frame).
     * The bitmap is cropped to the output aspect ratio (centre crop) and scaled.
     */
    fun encode(
        fd: FileDescriptor,
        frameCount: Int,
        frameAt: (Int) -> Bitmap?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val (codec, mime) = createEncoder()
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        var outFrames = 0L
        val frameUs = 1_000_000L / fps
        val info = MediaCodec.BufferInfo()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            info.presentationTimeUs = outFrames * frameUs
                            muxer.writeSampleData(track, buf, info)
                            outFrames++
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            codec.start()
            val dst = Rect(0, 0, width, height)
            for (i in 0 until frameCount) {
                val bm = frameAt(i) ?: continue
                try {
                    val src = centreCrop(bm.width, bm.height, width, height).toRect()
                    val canvas: Canvas = runCatching { surface.lockHardwareCanvas() }.getOrElse { surface.lockCanvas(null) }
                    try {
                        canvas.drawColor(0xFF000000.toInt())
                        canvas.drawBitmap(bm, src, dst, paint)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                } finally {
                    bm.recycle()
                }
                drain(false)
                onProgress(i + 1, frameCount)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
        Log.i(tag, "encoded $outFrames frames @ $fps fps ($mime)")
    }

    private fun createEncoder(): Pair<MediaCodec, String> {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            val c = runCatching { MediaCodec.createEncoderByType(mime) }.getOrNull() ?: continue
            val caps = runCatching { c.codecInfo.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            if (caps != null && !caps.isSizeSupported(width, height)) {
                c.release(); continue
            }
            return c to mime
        }
        error("No hay codificador de video para ${width}x${height}")
    }

    companion object {
        /** Plain crop rectangle (no Android classes) so the math is unit-testable on the JVM. */
        data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            val width: Int get() = right - left
            val height: Int get() = bottom - top
            fun toRect(): Rect = Rect(left, top, right, bottom)
        }

        fun centreCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
            val srcAspect = srcW.toDouble() / srcH
            val dstAspect = dstW.toDouble() / dstH
            return if (srcAspect > dstAspect) {
                val w = (srcH * dstAspect).toInt()
                val x = (srcW - w) / 2
                Crop(x, 0, x + w, srcH)
            } else {
                val h = (srcW / dstAspect).toInt()
                val y = (srcH - h) / 2
                Crop(0, y, srcW, y + h)
            }
        }

        /** Picks 4K UHD when the source is at least that wide, otherwise 1080p. */
        fun outputSizeFor(srcW: Int): Pair<Int, Int> = if (srcW >= 3840) 3840 to 2160 else 1920 to 1080
    }
}
