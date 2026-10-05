package com.aurorafotos.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Manual exposure parameters for one frame. */
data class ExposureParams(
    val iso: Int,
    val exposureNs: Long,
    val focusDiopters: Float,
    val awbMode: Int,
    val jpegOrientation: Int = 0,
    val noiseReductionOff: Boolean = true,
    /**
     * false = request [exposureNs] as-is even above SENSOR_INFO_EXPOSURE_TIME_RANGE. Samsung
     * engineers have said the HAL may honour it; the applied value comes back in the result.
     */
    val clampToRange: Boolean = true,
)

/** What a RAW burst delivered. */
class BurstOutcome(val frames: Int, val lastResult: TotalCaptureResult?, val appliedExposureNs: Long)

/** One captured frame: the RAW image (owned by the caller, must be closed), the JPEG bytes and metadata. */
class Frame(
    val raw: Image?,
    val jpeg: ByteArray?,
    val result: TotalCaptureResult,
) {
    fun close() {
        raw?.close()
    }
}

/**
 * Thin Camera2 wrapper that does exactly one thing well: full-manual still captures with
 * RAW_SENSOR + JPEG outputs (and an optional preview surface), as coroutines.
 */
class CameraController(private val context: Context, val info: CameraInfo) {
    private val tag = "CameraController"
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera-${info.id}").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private val lock = Any()
    @Volatile private var closed = false

    val rawSize: Size = info.largestRaw ?: Size(0, 0)
    val jpegSize: Size = info.largestJpeg ?: Size(0, 0)

    // ---- lifecycle -------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun open() {
        if (device != null) return
        device = suspendCancellableCoroutine { cont ->
            try {
                manager.openCamera(info.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        // If the caller was cancelled meanwhile, do not leak the device.
                        if (cont.isActive) cont.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(tag, "camera disconnected")
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Cámara desconectada"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Error de cámara $error"))
                    }
                })
            } catch (e: CameraAccessException) {
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * (Re)creates the capture session. [preview] may be null for headless capture.
     * [wantRaw]/[wantJpeg] choose the still outputs.
     */
    suspend fun configure(preview: Surface?, wantRaw: Boolean, wantJpeg: Boolean, rawBuffers: Int = 6) {
        val dev = device ?: error("camera not open")
        session?.close(); session = null
        rawReader?.close(); rawReader = null
        jpegReader?.close(); jpegReader = null
        previewSurface = preview

        val outputs = ArrayList<OutputConfiguration>()
        if (preview != null) outputs += OutputConfiguration(preview)
        if (wantRaw && rawSize.width > 0) {
            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, rawBuffers)
            outputs += OutputConfiguration(rawReader!!.surface)
        }
        if (wantJpeg && jpegSize.width > 0) {
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, rawBuffers)
            outputs += OutputConfiguration(jpegReader!!.surface)
        }
        require(outputs.isNotEmpty()) { "no outputs" }

        session = suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("No se pudo configurar la sesión de cámara"))
                    }
                })
            dev.createCaptureSession(config)
        }
    }

    fun close() {
        closed = true
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        thread.quitSafely()
    }

    // ---- requests --------------------------------------------------------------------

    private fun applyManual(b: CaptureRequest.Builder, p: ExposureParams, forPreview: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_LOCK, false)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, info.clampIso(p.iso))
        val exp = if (p.clampToRange) info.clampExposure(p.exposureNs) else maxOf(p.exposureNs, info.minExposureNs)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        val maxFrame = info.maxFrameDurationNs ?: Long.MAX_VALUE
        val frameDuration = maxOf(exp, 33_333_333L)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (p.clampToRange) minOf(frameDuration, maxFrame) else frameDuration)

        if (info.minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, p.focusDiopters.coerceIn(0f, info.minFocusDiopters))
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, p.awbMode)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (!forPreview) {
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, p.jpegOrientation)
            if (p.noiseReductionOff && info.hasManualPostProcessing) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
        }
    }

    /** Starts a repeating manual preview (long-ish exposure so stars show up on screen). */
    fun startPreview(p: ExposureParams) {
        val s = session ?: error("session not configured")
        val surface = previewSurface ?: error("no preview surface")
        val b = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        // Preview exposure capped at ~1/4 s so the UI stays responsive.
        applyManual(b, p.copy(exposureNs = minOf(p.exposureNs, 250_000_000L)), forPreview = true)
        s.setRepeatingRequest(b.build(), null, handler)
    }

    fun stopPreview() {
        runCatching { session?.stopRepeating() }
    }

    /**
     * Captures [count] frames back-to-back with the same manual parameters. Each frame's RAW
     * [Image] is delivered through [onFrame] and must be closed by the consumer (the reader
     * only holds a few buffers). Returns when all frames were delivered.
     */
    suspend fun captureBurst(
        p: ExposureParams,
        count: Int,
        wantRaw: Boolean,
        wantJpeg: Boolean,
        onFrame: suspend (index: Int, frame: Frame) -> Unit,
    ) {
        val s = session ?: error("session not configured")
        val raw = if (wantRaw) rawReader ?: error("RAW output not configured") else null
        val jpg = if (wantJpeg) jpegReader ?: error("JPEG output not configured") else null

        val expNs = info.clampExposure(p.exposureNs)
        val perFrameTimeoutMs = (expNs / 1_000_000L) * 3 + 8_000L

        for (i in 0 until count) {
            if (closed) throw IllegalStateException("camera closed")
            val rawDeferred = CompletableDeferred<Image>()
            val jpgDeferred = CompletableDeferred<ByteArray>()
            val resultDeferred = CompletableDeferred<TotalCaptureResult>()

            raw?.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage()
                if (img != null && !rawDeferred.complete(img)) img.close()
            }, handler)
            jpg?.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    jpgDeferred.complete(bytes)
                } finally {
                    img.close()
                }
            }, handler)

            val b = s.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            raw?.let { b.addTarget(it.surface) }
            jpg?.let { b.addTarget(it.surface) }
            applyManual(b, p, forPreview = false)

            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    resultDeferred.complete(result)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val e = IllegalStateException("Captura fallida (reason=${failure.reason})")
                    resultDeferred.completeExceptionally(e)
                    rawDeferred.completeExceptionally(e)
                    jpgDeferred.completeExceptionally(e)
                }
            }, handler)

            val frame = try {
                withTimeout(perFrameTimeoutMs) {
                    val result = resultDeferred.await()
                    val rawImg = if (raw != null) rawDeferred.await() else null
                    val jpgBytes = if (jpg != null) jpgDeferred.await() else null
                    Frame(rawImg, jpgBytes, result)
                }
            } catch (t: Throwable) {
                // Release a RAW buffer that may have arrived before the failure.
                if (rawDeferred.isCompleted) runCatching { rawDeferred.await().close() }
                throw t
            }
            onFrame(i, frame)
        }
    }

    /**
     * Pipelined RAW-only burst: requests are submitted in small batches so the HAL keeps the
     * sensor busy, and every image is handed to [onImage] on the camera thread (synchronously;
     * it is closed afterwards). Returns the last capture result and the exposure the HAL
     * reports having applied, which can differ from the requested one.
     */
    suspend fun captureRawBurst(
        p: ExposureParams,
        count: Int,
        shouldStop: () -> Boolean = { false },
        onImage: (index: Int, image: Image) -> Unit,
    ): BurstOutcome {
        val s = session ?: error("session not configured")
        val reader = rawReader ?: error("RAW output not configured")
        val batch = maxOf(1, minOf(4, reader.maxImages - 2))
        val expNs = if (p.clampToRange) info.clampExposure(p.exposureNs) else p.exposureNs
        var delivered = 0
        var lastResult: TotalCaptureResult? = null
        var applied = 0L
        var failure: Throwable? = null
        var remaining = count

        while (remaining > 0) {
            if (closed) throw IllegalStateException("camera closed")
            if (shouldStop()) break
            val n = minOf(batch, remaining)
            val done = CompletableDeferred<Unit>()
            var got = 0
            var results = 0
            fun maybeDone() {
                if (got >= n && results >= n) done.complete(Unit)
            }
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    if (failure == null) onImage(delivered, img)
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    img.close()
                }
                delivered++; got++
                if (failure != null) done.completeExceptionally(failure!!) else maybeDone()
            }, handler)

            val requests = ArrayList<CaptureRequest>(n)
            repeat(n) {
                val b = s.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                applyManual(b, p, forPreview = false)
                requests += b.build()
            }
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    lastResult = result
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { applied = it }
                    results++
                    maybeDone()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, f: CaptureFailure) {
                    done.completeExceptionally(IllegalStateException("Captura fallida (reason=${f.reason})"))
                }
            }, handler)

            val timeoutMs = (expNs / 1_000_000L) * (n + 3) + 15_000L
            withTimeout(timeoutMs) { done.await() }
            remaining -= n
        }
        reader.setOnImageAvailableListener(null, null)
        return BurstOutcome(delivered, lastResult, if (applied > 0) applied else expNs)
    }

    /** Captures a single frame and returns it (caller closes the RAW image). */
    suspend fun captureOne(p: ExposureParams, wantRaw: Boolean, wantJpeg: Boolean): Frame {
        var out: Frame? = null
        captureBurst(p, 1, wantRaw, wantJpeg) { _, f -> out = f }
        return out!!
    }
}
