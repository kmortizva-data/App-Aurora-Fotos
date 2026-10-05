package com.aurorafotos.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.aurorafotos.camera.CameraController
import com.aurorafotos.camera.CameraInfo
import com.aurorafotos.camera.ExposureParams
import com.aurorafotos.presets.Preset
import com.aurorafotos.presets.StackMode
import com.aurorafotos.stacking.DngPatcher
import com.aurorafotos.stacking.DngWriter
import com.aurorafotos.stacking.RawRender
import com.aurorafotos.stacking.RawStacker
import com.aurorafotos.stacking.StackResult
import com.aurorafotos.storage.MediaSaver
import com.aurorafotos.util.Fmt
import com.aurorafotos.video.TimelapseEncoder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Runs one unattended session for a [Preset]. RAW only: every frame goes straight into a
 * 32-bit Bayer accumulator, so a "30 s" shot on a phone that only allows 1/9 s per frame is
 * simply ~270 summed frames. Outputs per shot: a 16-bit DNG that Lightroom renders as the
 * long exposure it is (BaselineExposure = log2(frames)) and a JPEG rendered on-device.
 *
 * Stacking semantics:
 *  - NONE: every frame is saved on its own.
 *  - AVERAGE / ADD: frames of a shot are merged into one image (same DNG data; AVERAGE renders
 *    at one frame's brightness, ADD at the summed brightness).
 *  - LIGHTEN: each shot is an ADD stack, and shots are merged with per-pixel maximum across
 *    the session (star trails); a frame of the running result is kept per shot for the video.
 */
class CaptureEngine(private val context: Context) {
    private val tag = "CaptureEngine"
    val progress = MutableStateFlow(SessionProgress())
    @Volatile var stopRequested = false
        private set

    fun requestStop() {
        stopRequested = true
    }

    private fun update(f: (SessionProgress) -> SessionProgress) {
        progress.value = f(progress.value)
    }

    /** [deviceRotation] is Surface.ROTATION_* at start, used for JPEG/DNG orientation. */
    suspend fun run(preset: Preset, deviceRotation: Int) = withContext(Dispatchers.Default) {
        stopRequested = false
        val sessionName = "${Fmt.sessionStamp()}_${preset.id}"
        update { SessionProgress(state = SessionState.COUNTDOWN, presetName = preset.name, sessionName = sessionName, countdown = preset.countdownSec) }

        val info = CameraInfo.load(context, preset.cameraId)
        val controller = CameraController(context, info)
        val saver = MediaSaver(context, sessionName)
        val startedAt = SystemClock.elapsedRealtime()
        val log = StringBuilder()
        val videoUris = ArrayList<Uri>()
        val rolling = preset.stackMode == StackMode.LIGHTEN
        var rollingStack: StackResult? = null
        var lastResult: TotalCaptureResult? = null
        // Video frames must not flicker: exposure gain and white balance are locked on the first shot.
        var lockedGain: Double? = null
        var lockedResult: TotalCaptureResult? = null

        val requestedExposure = if (preset.exposureNs == Preset.MAX_EXPOSURE) info.maxExposureNs else preset.exposureNs
        val iso = info.clampIso(preset.iso)
        val orientation = jpegOrientation(info, deviceRotation)
        val cfa = RawRender.cfaOf(info.characteristics)
        val params = ExposureParams(
            iso = iso, exposureNs = requestedExposure, focusDiopters = preset.focusDiopters,
            awbMode = CameraInfo.awbModeForKelvin(preset.wbKelvin, info.awbModes),
            jpegOrientation = orientation, clampToRange = !preset.forceExposure,
        )
        var appliedExposure = 0L
        var framesPerShot = if (preset.stackMode == StackMode.NONE) 1 else preset.effectiveFrames(info.clampExposure(requestedExposure))

        log.appendLine("Aurora Fotos · sesión $sessionName")
        log.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        log.appendLine("Preset: ${preset.name} [${preset.id}]")
        log.appendLine("Cámara: ${preset.cameraId}  RAW ${controller.rawSize}  CFA $cfa  negro ${info.blackLevel} blanco ${info.whiteLevel}")
        log.appendLine("ISO pedido ${preset.iso} → aplicado $iso; exposición pedida ${Fmt.exposure(requestedExposure)} (forzar: ${preset.forceExposure}); máx. declarado ${Fmt.exposure(info.maxExposureNs)}")
        log.appendLine("Objetivo por toma ${Fmt.exposure(preset.totalExposureNs)}, apilado ${preset.stackMode}, intervalo ${preset.intervalMs} ms, duración ${preset.durationMs} ms, tomas ${preset.totalShots}")
        log.appendLine("Enfoque ${preset.focusDiopters} dpt, WB ${preset.wbKelvin} K, orientación $orientation°")

        try {
            if (!info.hasRaw) error("La cámara ${preset.cameraId} no expone RAW a apps de terceros")

            for (s in preset.countdownSec downTo 1) {
                if (stopRequested) throw StopException()
                update { it.copy(state = SessionState.COUNTDOWN, countdown = s) }
                delay(1000)
            }

            controller.open()
            controller.configure(null, wantRaw = true, wantJpeg = false, rawBuffers = 6)
            update { it.copy(state = SessionState.CAPTURING, exposureNs = requestedExposure, iso = iso, framesPerShot = framesPerShot, totalShots = preset.totalShots) }

            val stacker = RawStacker(controller.rawSize.width, controller.rawSize.height, info.blackLevel, info.whiteLevel)
            val sessionStart = SystemClock.elapsedRealtime()
            var shot = 0
            var frameCounter = 0
            while (true) {
                coroutineContext.ensureActive()
                if (stopRequested) break
                if (preset.totalShots > 0 && shot >= preset.totalShots) break
                if (preset.totalShots == 0 && preset.durationMs > 0 &&
                    SystemClock.elapsedRealtime() - sessionStart >= preset.durationMs) break

                if (preset.intervalMs > 0 && shot > 0) {
                    val due = sessionStart + shot * preset.intervalMs
                    var remaining = due - SystemClock.elapsedRealtime()
                    while (remaining > 0 && !stopRequested) {
                        val step = minOf(remaining, 500L)
                        delay(step); remaining -= step
                    }
                    if (stopRequested) break
                }
                shot++
                val shotIdx = shot
                update { it.copy(shot = shotIdx, frame = 0, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
                stacker.reset()

                // The first frame of the session tells us what exposure the HAL really applies.
                var toCapture = framesPerShot
                if (appliedExposure == 0L) {
                    val probe = controller.captureRawBurst(params, 1, { false }) { _, img ->
                        val pl = img.planes[0]; stacker.add(pl.buffer, pl.rowStride, pl.pixelStride)
                    }
                    frameCounter++
                    lastResult = probe.lastResult
                    appliedExposure = probe.appliedExposureNs
                    if (preset.stackMode != StackMode.NONE) framesPerShot = preset.effectiveFrames(appliedExposure)
                    toCapture = framesPerShot - 1
                    val fps = framesPerShot
                    update { it.copy(exposureNs = appliedExposure, framesPerShot = fps, frame = 1) }
                    log.appendLine("Exposición aplicada por el sensor: ${Fmt.exposure(appliedExposure)} → $framesPerShot frames/toma ≈ ${Fmt.exposure(framesPerShot * appliedExposure)}")
                }
                if (toCapture > 0) {
                    var lastUi = 0L
                    val out = controller.captureRawBurst(params, toCapture, { stopRequested }) { i, img ->
                        val pl = img.planes[0]; stacker.add(pl.buffer, pl.rowStride, pl.pixelStride)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 250) {
                            lastUi = now
                            val f = stacker.frames
                            update { it.copy(frame = f, elapsedMs = now - startedAt) }
                        }
                    }
                    frameCounter += out.frames
                    out.lastResult?.let { lastResult = it }
                }
                val f = stacker.frames
                update { it.copy(frame = f, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
                if (stacker.frames == 0) continue

                val shotStack = stacker.result()
                val lr = lastResult
                if (preset.makeVideo && lockedGain == null) {
                    lockedGain = RawRender.autoGainFor(shotStack, cfa, if (preset.stackMode == StackMode.AVERAGE) shotStack.frames else 1)
                    lockedResult = lr
                    log.appendLine("Ganancia de render fijada para el video: ${String.format(java.util.Locale.US, "%.2f", lockedGain)}×")
                }
                if (rolling) {
                    val acc = rollingStack
                    if (acc == null) rollingStack = shotStack else acc.lightenInPlace(shotStack)
                    if (preset.makeVideo) {
                        val bm = renderJpegBitmap(rollingStack!!, cfa, lockedResult ?: lr, StackMode.ADD, preset.videoHalfRes, orientation, lockedGain)
                        videoUris += saver.saveJpeg("${sessionName}_trail_${Fmt.frameIndex(shotIdx)}.jpg", RawRender.jpeg(bm))
                        bm.recycle()
                    }
                } else {
                    val base = if (preset.stackMode == StackMode.NONE) "${sessionName}_${Fmt.frameIndex(frameCounter)}" else "${sessionName}_shot${Fmt.frameIndex(shotIdx)}"
                    if (preset.saveRaw && lr != null) saveStackDng(saver, log, info, lr, shotStack, preset.stackMode, orientation, "$base.dng", preset.name)
                    if (preset.saveJpeg || preset.makeVideo) {
                        // Timelapse frames use the locked gain/WB; single stills get their own auto level.
                        val bm = if (preset.makeVideo) renderJpegBitmap(shotStack, cfa, lockedResult ?: lr, preset.stackMode, preset.videoHalfRes, orientation, lockedGain)
                        else renderJpegBitmap(shotStack, cfa, lr, preset.stackMode, false, orientation, null)
                        val uri = saver.saveJpeg("$base.jpg", RawRender.jpeg(bm))
                        bm.recycle()
                        if (preset.makeVideo) videoUris += uri
                    }
                }
                log.appendLine("Toma $shotIdx: ${shotStack.frames} frames, terminada a +${SystemClock.elapsedRealtime() - sessionStart} ms")
            }

            if (rolling) {
                val acc = rollingStack
                val lr = lastResult
                if (acc != null && lr != null) {
                    if (preset.saveRaw) saveStackDng(saver, log, info, lr, acc, StackMode.LIGHTEN, orientation, "${sessionName}_startrails.dng", preset.name)
                    if (preset.saveJpeg) {
                        val bm = renderJpegBitmap(acc, cfa, lr, StackMode.ADD, false, orientation, null)
                        saver.saveJpeg("${sessionName}_startrails.jpg", RawRender.jpeg(bm))
                        bm.recycle()
                    }
                }
            }
            update { it.copy(state = SessionState.PROCESSING, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
            controller.close()

            if (preset.makeVideo && videoUris.size >= 2) makeVideo(saver, sessionName, preset.videoFps, videoUris)
            if ((!preset.saveJpeg || rolling) && preset.makeVideo) {
                videoUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            }
            val summary = "$frameCounter frames × ${Fmt.exposure(appliedExposure)} = ${Fmt.exposure(frameCounter * appliedExposure)} de exposición, ISO $iso, ${Fmt.seconds(SystemClock.elapsedRealtime() - startedAt)} en total"
            log.appendLine("Resumen: $summary (tomas: $shot)")
            runCatching { saver.saveText("${sessionName}_info.txt", log.toString()) }
            update { it.copy(state = SessionState.DONE, elapsedMs = SystemClock.elapsedRealtime() - startedAt, message = summary) }
        } catch (e: StopException) {
            update { it.copy(state = SessionState.DONE, message = "Detenido antes de empezar") }
        } catch (t: Throwable) {
            Log.e(tag, "session failed", t)
            runCatching { saver.saveText("${sessionName}_error.txt", log.toString() + "\n" + Log.getStackTraceString(t)) }
            update { it.copy(state = SessionState.ERROR, error = t.message ?: t.toString()) }
        } finally {
            controller.close()
        }
    }

    private fun renderJpegBitmap(stack: StackResult, cfa: Int, result: TotalCaptureResult?, mode: StackMode, halfRes: Boolean, orientation: Int, gain: Double?): Bitmap {
        val exposureFrames = if (mode == StackMode.AVERAGE) stack.frames else 1
        val bm = RawRender.render(stack, cfa, result, exposureFrames, halfRes, gainOverride = gain)
        return RawRender.rotated(bm, orientation)
    }

    /** Writes the stack as a 16-bit DNG with its own levels and BaselineExposure; never fatal. */
    private fun saveStackDng(
        saver: MediaSaver, log: StringBuilder, info: CameraInfo, result: TotalCaptureResult,
        stack: StackResult, mode: StackMode, orientation: Int, name: String, presetName: String,
    ) {
        try {
            val d16 = stack.toDng16()
            val bos = ByteArrayOutputStream(stack.pixelCount * 2 + 65536)
            DngWriter.write(
                bos, info.characteristics, result, d16.toByteBuffer(), stack.width, stack.height,
                exifOrientation(orientation), "Aurora Fotos $presetName · ${stack.frames} frames $mode"
            )
            val raw = bos.toByteArray()
            val ev = if (mode == StackMode.AVERAGE) 0.0 else Math.log(stack.frames.toDouble()) / Math.log(2.0)
            val patched = try {
                DngPatcher.patch(raw, d16.whiteLevel, 0, ev)
            } catch (t: Throwable) {
                log.appendLine("AVISO: no se pudo ajustar niveles del DNG $name ($t); se guarda sin ajustar")
                raw
            }
            saver.saveDng(name) { it.write(patched) }
        } catch (t: Throwable) {
            Log.e(tag, "DNG $name failed", t)
            log.appendLine("AVISO: no se pudo escribir $name: $t")
            update { it.copy(message = "DNG falló: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private fun makeVideo(saver: MediaSaver, sessionName: String, fps: Int, frames: List<Uri>) {
        val first = saver.readBytes(frames[0])
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(first, 0, first.size, o)
        val (vw, vh) = TimelapseEncoder.outputSizeFor(o.outWidth, o.outHeight)
        val encoder = TimelapseEncoder(vw, vh, fps)
        val (uri, pfd) = saver.openVideo("${sessionName}_timelapse_${fps}fps.mp4")
        var ok = false
        try {
            pfd.use {
                encoder.encode(it.fileDescriptor, frames.size, { i ->
                    val bytes = saver.readBytes(frames[i])
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }) { done, total ->
                    update { p -> p.copy(message = "Video $done/$total") }
                }
            }
            ok = true
        } finally {
            saver.finishVideo(uri, ok)
        }
    }

    private class StopException : RuntimeException("stopped")

    companion object {
        /** Degrees to rotate the sensor image clockwise so it is upright on screen. */
        fun jpegOrientation(info: CameraInfo, deviceRotation: Int): Int {
            val deviceDeg = when (deviceRotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            return if (info.facingBack) (info.sensorOrientation - deviceDeg + 360) % 360
            else (info.sensorOrientation + deviceDeg) % 360
        }

        fun exifOrientation(degrees: Int): Int = when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
