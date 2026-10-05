package com.aurorafotos.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time video encoder fed by the camera through its input Surface. Presentation
 * timestamps come from the camera, so a 9 fps "slow shutter" recording plays back at 9 fps.
 * Output goes to an MP4 on the given file descriptor. No audio (aurora sessions are silent).
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val orientationHint: Int,
) {
    private val tag = "VideoEncoder"
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var drain: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var error: Throwable? = null
    val framesEncoded = AtomicLong(0)
    lateinit var inputSurface: Surface
        private set
    var mime: String = MediaFormat.MIMETYPE_VIDEO_HEVC
        private set

    fun start(fd: FileDescriptor) {
        val (c, m) = createEncoder()
        codec = c; mime = m
        val format = MediaFormat.createVideoFormat(m, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(orientationHint)
        }
        c.start()
        drain = Thread({ drainLoop() }, "video-drain").apply { start() }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val mx = muxer ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false
        var lastPts = -1L
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 100_000L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (stopRequested && !started) continue
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mx.addTrack(c.outputFormat)
                        mx.start(); started = true
                    }
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            // Muxer requires monotonic timestamps.
                            if (info.presentationTimeUs <= lastPts) info.presentationTimeUs = lastPts + 1
                            lastPts = info.presentationTimeUs
                            mx.writeSampleData(track, buf, info)
                            framesEncoded.incrementAndGet()
                        }
                        c.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "drain failed", t)
            error = t
        }
    }

    /** Call after the camera has stopped sending frames. Blocks until the file is finalized. */
    fun stop() {
        stopRequested = true
        runCatching { codec?.signalEndOfInputStream() }
        drain?.join(10_000)
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface.release() }
        error?.let { throw it }
    }

    private fun createEncoder(): Pair<MediaCodec, String> {
        for (m in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            val c = runCatching { MediaCodec.createEncoderByType(m) }.getOrNull() ?: continue
            val caps = runCatching { c.codecInfo.getCapabilitiesForType(m).videoCapabilities }.getOrNull()
            if (caps != null && !caps.isSizeSupported(width, height)) { c.release(); continue }
            return c to m
        }
        error("No hay codificador de video para ${width}x${height}")
    }

    companion object {
        /** Night video is noisy: give it bitrate. ~0.3 bit/pixel/frame, 20–120 Mbps. */
        fun bitrateFor(width: Int, height: Int, fps: Int): Int =
            (width.toLong() * height * fps * 0.3).toLong().coerceIn(20_000_000L, 120_000_000L).toInt()
    }
}
