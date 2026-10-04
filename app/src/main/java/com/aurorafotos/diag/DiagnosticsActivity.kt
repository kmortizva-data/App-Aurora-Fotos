package com.aurorafotos.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import com.aurorafotos.R
import com.aurorafotos.camera.CameraController
import com.aurorafotos.camera.CameraInfo
import com.aurorafotos.camera.ExposureParams
import com.aurorafotos.capture.CaptureEngine
import com.aurorafotos.presets.Presets
import com.aurorafotos.stacking.DngWriter
import com.aurorafotos.storage.MediaSaver
import com.aurorafotos.ui.Ui
import com.aurorafotos.util.Fmt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shows exactly what Samsung exposes to third-party apps on this phone and lets the user
 * shoot a test DNG at the maximum exposure to confirm RAW is not corrupted.
 */
class DiagnosticsActivity : Activity() {
    private lateinit var text: TextView
    private val scope = MainScope()
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)
        findViewById<TextView>(R.id.title).text = getString(R.string.diag_title)
        findViewById<TextView>(R.id.subtitle).text = getString(R.string.diag_hint)
        val form = findViewById<LinearLayout>(R.id.form)
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
        }
        form.addView(text)
        val buttons = findViewById<LinearLayout>(R.id.buttons)
        buttons.addView(Ui.button(this, getString(R.string.btn_test_dng), primary = true) { testCapture() })
        buttons.addView(Ui.button(this, getString(R.string.btn_copy)) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diag", report))
        })
        buttons.addView(Ui.button(this, getString(R.string.btn_share)) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Diagnóstico"))
        })
        report = buildReport()
        text.text = report
    }

    override fun onDestroy() {
        scope.cancel(); super.onDestroy()
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · build ${Build.DISPLAY}")
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        sb.appendLine("Cámaras visibles para terceros: ${ids.joinToString()}")
        for (id in ids) {
            val c = runCatching { CameraInfo.load(this, id) }.getOrNull() ?: continue
            sb.appendLine()
            sb.appendLine("== Cámara $id (${if (c.facingBack) "trasera" else "frontal"}) · nivel ${c.hardwareLevelName()}")
            sb.appendLine("   Focal ${c.focalLengths.joinToString()} mm · f/${c.apertures.joinToString()} · orientación sensor ${c.sensorOrientation}°")
            sb.appendLine("   Exposición: ${c.exposureRange?.let { Fmt.exposure(it.lower) + " – " + Fmt.exposure(it.upper) } ?: "sin control manual"}")
            sb.appendLine("   ISO: ${c.isoRange?.let { "${it.lower} – ${it.upper}" } ?: "?"} (analógico máx ${c.maxAnalogIso ?: "?"})")
            sb.appendLine("   Frame máx: ${c.maxFrameDurationNs?.let { Fmt.exposure(it) } ?: "?"}")
            sb.appendLine("   RAW: ${if (c.hasRaw) "sí " + (c.largestRaw ?: "") else "NO"} · manual sensor: ${c.hasManualSensor} · manual post-proceso: ${c.hasManualPostProcessing}")
            sb.appendLine("   JPEG máx: ${c.largestJpeg} · niveles: negro ${c.blackLevel} blanco ${c.whiteLevel}")
            sb.appendLine("   Enfoque: mín ${c.minFocusDiopters} dpt · calibración ${focusCal(c.focusCalibration)}")
            sb.appendLine("   Lógica multicámara: ${c.isLogicalMultiCamera} · físicas: ${c.physicalIds.joinToString().ifEmpty { "-" }}")
            sb.appendLine("   AWB modos: ${c.awbModes.joinToString()}")
            val ext = runCatching {
                val ec = cm.getCameraExtensionCharacteristics(id)
                ec.supportedExtensions.joinToString { extName(it) }
            }.getOrDefault("?")
            sb.appendLine("   Extensiones (Night/HDR/…): ${ext.ifEmpty { "ninguna" }}")
        }
        sb.appendLine()
        sb.appendLine("Nota: la app de Samsung (Pro / Expert RAW) tiene acceso privilegiado y llega a 30 s; lo de arriba es lo que esta app puede usar.")
        return sb.toString()
    }

    private fun focusCal(v: Int) = when (v) {
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrado"
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "aproximado"
        else -> "no calibrado"
    }

    private fun extName(v: Int) = when (v) {
        0 -> "AUTO"; 1 -> "FACE_RETOUCH"; 2 -> "BOKEH"; 3 -> "HDR"; 4 -> "NIGHT"; else -> v.toString()
    }

    private fun testCapture() {
        text.text = report + "\n\nCapturando DNG de prueba (exposición máxima, ISO 800)…"
        scope.launch {
            val id = Presets.AURORA_PHOTO.cameraId
            var controller: CameraController? = null
            try {
                val info = CameraInfo.load(this@DiagnosticsActivity, id)
                controller = CameraController(this@DiagnosticsActivity, info)
                controller.open()
                controller.configure(null, wantRaw = info.hasRaw, wantJpeg = true)
                val rot = display?.rotation ?: Surface.ROTATION_0
                val orientation = CaptureEngine.jpegOrientation(info, rot)
                val p = ExposureParams(iso = info.clampIso(800), exposureNs = info.maxExposureNs, focusDiopters = 0f,
                    awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO, jpegOrientation = orientation)
                val t0 = System.nanoTime()
                val frame = controller.captureOne(p, wantRaw = info.hasRaw, wantJpeg = true)
                val dt = (System.nanoTime() - t0) / 1e9
                val saver = MediaSaver(this@DiagnosticsActivity, "diag_${Fmt.sessionStamp()}")
                val lines = StringBuilder()
                try {
                    val appliedExp = frame.result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    val appliedIso = frame.result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    lines.appendLine("Resultado: exposición aplicada ${appliedExp?.let { Fmt.exposure(it) } ?: "?"}, ISO $appliedIso, tiempo total ${String.format(java.util.Locale.US, "%.1f", dt)} s")
                    if (frame.raw != null) {
                        val plane = frame.raw.planes[0]
                        lines.appendLine("RAW ${frame.raw.width}x${frame.raw.height} rowStride ${plane.rowStride} pixelStride ${plane.pixelStride}")
                        saver.saveDng("test.dng") { out ->
                            DngWriter.write(out, info.characteristics, frame.result, frame.raw, CaptureEngine.exifOrientation(orientation), "Aurora Fotos test")
                        }
                        lines.appendLine("DNG guardado en ${saver.relativePath}/test.dng → ábrelo en Lightroom/Galería y comprueba que no sale rayado.")
                    } else lines.appendLine("Sin RAW en esta cámara.")
                    frame.jpeg?.let { saver.saveJpeg("test.jpg", it); lines.appendLine("JPEG guardado (${it.size / 1024} KB).") }
                } finally {
                    frame.close()
                }
                report += "\n\n" + lines.toString()
                saver.saveText("diag.txt", report)
                text.text = report
            } catch (t: Throwable) {
                report += "\n\nERROR en captura de prueba: $t"
                text.text = report
            } finally {
                controller?.close()
            }
        }
    }
}
