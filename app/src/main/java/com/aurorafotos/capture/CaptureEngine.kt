package com.aurorafotos.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.aurorafotos.camera.CameraController
import com.aurorafotos.camera.CameraInfo
import com.aurorafotos.camera.ExposureParams
import com.aurorafotos.camera.Frame
import com.aurorafotos.presets.Preset
import com.aurorafotos.presets.StackMode
import com.aurorafotos.stacking.BitmapStacker
import com.aurorafotos.stacking.DngWriter
import com.aurorafotos.stacking.RawStacker
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
 * Runs one unattended session for a [Preset]: countdown, N shots (each a burst of frames,
 * optionally stacked), saving DNG/JPEG as it goes, then the timelapse MP4.
 *
 * Stacking semantics:
 *  - AVERAGE / ADD: frames of each shot are merged into one image per shot.
 *  - LIGHTEN: one rolling stack across the whole session (star trails); a JPEG of the
 *    running stack is saved per frame so the video shows the trails growing.
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
        val jpegUris = ArrayList<Uri>()
        val rolling = preset.stackMode == StackMode.LIGHTEN
        var rollingRaw: RawStacker? = null
        var rollingBmp: BitmapStacker? = null
        var lastResult: android.hardware.camera2.TotalCaptureResult? = null

        val wantRaw = preset.saveRaw && info.hasRaw
        val exposureNs = if (preset.exposureNs == Preset.MAX_EXPOSURE) info.maxExposureNs else info.clampExposure(preset.exposureNs)
        val iso = info.clampIso(preset.iso)
        val jpegOrientation = jpegOrientation(info, deviceRotation)
        val params = ExposureParams(
            iso = iso, exposureNs = exposureNs, focusDiopters = preset.focusDiopters,
            awbMode = CameraInfo.awbModeForKelvin(preset.wbKelvin, info.awbModes),
            jpegOrientation = jpegOrientation,
        )
        log.appendLine("Aurora Fotos · sesión $sessionName")
        log.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        log.appendLine("Preset: ${preset.name} [${preset.id}]")
        log.appendLine("Cámara: ${preset.cameraId}  RAW: $wantRaw (${controller.rawSize})  JPEG: ${controller.jpegSize}")
        log.appendLine("ISO pedido ${preset.iso} → aplicado $iso; exposición pedida ${Fmt.exposure(preset.exposureNs)} → aplicada ${Fmt.exposure(exposureNs)} (máx. dispositivo ${Fmt.exposure(info.maxExposureNs)})")
        log.appendLine("Frames/toma ${preset.framesPerShot}, apilado ${preset.stackMode}, intervalo ${preset.intervalMs} ms, duración ${preset.durationMs} ms, tomas ${preset.totalShots}")
        log.appendLine("Enfoque ${preset.focusDiopters} dioptrías, WB ${preset.wbKelvin} K, orientación JPEG $jpegOrientation")

        try {
            // Countdown so the hand leaves the phone.
            for (s in preset.countdownSec downTo 1) {
                if (stopRequested) throw StopException()
                update { it.copy(state = SessionState.COUNTDOWN, countdown = s) }
                delay(1000)
            }

            controller.open()
            controller.configure(null, wantRaw = wantRaw, wantJpeg = true, rawBuffers = 3)
            update { it.copy(state = SessionState.CAPTURING, exposureNs = exposureNs, iso = iso, framesPerShot = preset.framesPerShot, totalShots = preset.totalShots) }

            val sessionStart = SystemClock.elapsedRealtime()
            var shot = 0
            var frameCounter = 0
            while (true) {
                coroutineContext.ensureActive()
                if (stopRequested) break
                if (preset.totalShots > 0 && shot >= preset.totalShots) break
                if (preset.totalShots == 0 && preset.durationMs > 0 &&
                    SystemClock.elapsedRealtime() - sessionStart >= preset.durationMs) break

                // Keep the interval cadence anchored to the session start.
                if (preset.intervalMs > 0 && shot > 0) {
                    val due = sessionStart + shot * preset.intervalMs
                    val wait = due - SystemClock.elapsedRealtime()
                    if (wait > 0) {
                        var remaining = wait
                        while (remaining > 0 && !stopRequested) {
                            val step = minOf(remaining, 500L)
                            delay(step); remaining -= step
                        }
                        if (stopRequested) break
                    }
                }
                shot++
                val shotIdx = shot
                update { it.copy(shot = shotIdx, frame = 0, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }

                val perShotStack = preset.stackMode == StackMode.AVERAGE || preset.stackMode == StackMode.ADD
                val rawStack: RawStacker? = if (perShotStack && wantRaw) RawStacker(controller.rawSize.width, controller.rawSize.height, preset.stackMode, info.blackLevel, info.whiteLevel) else null
                var bmpStack: BitmapStacker? = null

                controller.captureBurst(params, preset.framesPerShot, wantRaw = wantRaw, wantJpeg = true) { fi, frame ->
                    try {
                        frameCounter++
                        lastResult = frame.result
                        update { it.copy(frame = fi + 1, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
                        val jpeg = frame.jpeg ?: error("sin JPEG")
                        val (jw, jh) = BitmapStacker.jpegSize(jpeg)

                        when {
                            rolling -> {
                                if (wantRaw && frame.raw != null) {
                                    val r = rollingRaw ?: RawStacker(controller.rawSize.width, controller.rawSize.height, StackMode.LIGHTEN, info.blackLevel, info.whiteLevel).also { rollingRaw = it }
                                    val plane = frame.raw.planes[0]
                                    r.add(plane.buffer, plane.rowStride, plane.pixelStride)
                                }
                                val b = rollingBmp ?: BitmapStacker(jw, jh, StackMode.LIGHTEN).also { rollingBmp = it }
                                b.addJpeg(jpeg)
                                if (preset.saveJpeg || preset.makeVideo) {
                                    val bm = b.result()
                                    val uri = saver.saveJpeg("${sessionName}_trail_${Fmt.frameIndex(frameCounter)}.jpg", compress(bm))
                                    bm.recycle()
                                    jpegUris += uri
                                }
                            }
                            perShotStack -> {
                                if (rawStack != null && frame.raw != null) {
                                    val plane = frame.raw.planes[0]
                                    rawStack.add(plane.buffer, plane.rowStride, plane.pixelStride)
                                }
                                val b = bmpStack ?: BitmapStacker(jw, jh, preset.stackMode).also { bmpStack = it }
                                b.addJpeg(jpeg)
                            }
                            else -> {
                                val base = "${sessionName}_${Fmt.frameIndex(frameCounter)}"
                                if (wantRaw && frame.raw != null) {
                                    saver.saveDng("$base.dng") { out ->
                                        DngWriter.write(out, info.characteristics, frame.result, frame.raw, exifOrientation(jpegOrientation), "Aurora Fotos ${preset.name}")
                                    }
                                }
                                if (preset.saveJpeg || preset.makeVideo) jpegUris += saver.saveJpeg("$base.jpg", jpeg)
                            }
                        }
                    } finally {
                        frame.close()
                    }
                }

                if (perShotStack) {
                    val base = "${sessionName}_shot${Fmt.frameIndex(shotIdx)}"
                    val rs = rawStack
                    val lr = lastResult
                    if (rs != null && lr != null && rs.frames > 0) {
                        saver.saveDng("$base.dng") { out ->
                            DngWriter.write(out, info.characteristics, lr, rs.result(), rs.width, rs.height, exifOrientation(jpegOrientation), "Aurora Fotos ${preset.name} · ${rs.frames}×${Fmt.exposure(exposureNs)} ${preset.stackMode}")
                        }
                    }
                    val bs = bmpStack
                    if (bs != null && bs.frames > 0 && (preset.saveJpeg || preset.makeVideo)) {
                        val bm = bs.result()
                        jpegUris += saver.saveJpeg("$base.jpg", compress(bm))
                        bm.recycle()
                    }
                    bmpStack = null
                }
                log.appendLine("Toma $shotIdx terminada a +${SystemClock.elapsedRealtime() - sessionStart} ms")
            }

            // Final rolling stack outputs (star trails).
            if (rolling) {
                val lr = lastResult
                val rr = rollingRaw
                if (rr != null && lr != null && rr.frames > 0 && wantRaw) {
                    saver.saveDng("${sessionName}_startrails.dng") { out ->
                        DngWriter.write(out, info.characteristics, lr, rr.result(), rr.width, rr.height, exifOrientation(jpegOrientation), "Aurora Fotos star trails · ${rr.frames} frames")
                    }
                }
                val rb = rollingBmp
                if (rb != null && rb.frames > 0 && preset.saveJpeg) {
                    val bm = rb.result()
                    saver.saveJpeg("${sessionName}_startrails.jpg", compress(bm))
                    bm.recycle()
                }
            }
            update { it.copy(state = SessionState.PROCESSING, elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
            controller.close()

            if (preset.makeVideo && jpegUris.size >= 2) {
                makeVideo(saver, sessionName, preset.videoFps, jpegUris)
            }
            if (!preset.saveJpeg && preset.makeVideo) {
                // JPEGs were only kept to build the video.
                jpegUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            }
            log.appendLine("Frames capturados: $frameCounter, tomas: $shot, duración ${Fmt.seconds(SystemClock.elapsedRealtime() - startedAt)}")
            runCatching { saver.saveText("${sessionName}_info.txt", log.toString()) }
            update { it.copy(state = SessionState.DONE, elapsedMs = SystemClock.elapsedRealtime() - startedAt, message = saver.relativePath) }
        } catch (e: StopException) {
            update { it.copy(state = SessionState.DONE, message = saver.relativePath) }
        } catch (t: Throwable) {
            Log.e(tag, "session failed", t)
            runCatching { saver.saveText("${sessionName}_error.txt", log.toString() + "\n" + Log.getStackTraceString(t)) }
            update { it.copy(state = SessionState.ERROR, error = t.message ?: t.toString()) }
        } finally {
            controller.close()
        }
    }

    private fun makeVideo(saver: MediaSaver, sessionName: String, fps: Int, frames: List<Uri>) {
        val first = saver.readBytes(frames[0])
        val (w, _) = BitmapStacker.jpegSize(first)
        val (vw, vh) = TimelapseEncoder.outputSizeFor(w)
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

    private fun compress(bm: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream(bm.byteCount / 8)
        bm.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        return bos.toByteArray()
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
