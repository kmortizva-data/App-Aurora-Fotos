package com.aurorafotos.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.aurorafotos.R
import com.aurorafotos.camera.CameraInfo
import com.aurorafotos.presets.Preset
import com.aurorafotos.presets.PresetStore
import com.aurorafotos.presets.StackMode
import java.util.Locale

/** Plain form to tweak a preset. Values are validated on save and persisted per preset. */
class SettingsActivity : Activity() {
    private lateinit var store: PresetStore
    private lateinit var preset: Preset
    private lateinit var form: LinearLayout

    private lateinit var iso: EditText
    private lateinit var exposure: EditText
    private lateinit var frames: EditText
    private lateinit var totalExposure: EditText
    private lateinit var stack: Spinner
    private lateinit var interval: EditText
    private lateinit var duration: EditText
    private lateinit var shots: EditText
    private lateinit var saveRaw: CheckBox
    private lateinit var saveJpeg: CheckBox
    private lateinit var makeVideo: CheckBox
    private lateinit var fps: EditText
    private lateinit var focus: EditText
    private lateinit var wb: EditText
    private lateinit var camera: Spinner
    private lateinit var countdown: EditText
    private lateinit var forceExposure: CheckBox
    private lateinit var videoHalfRes: CheckBox
    private var cameraIds: List<String> = listOf("0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)
        store = PresetStore(this)
        preset = store.get(intent.getStringExtra(EXTRA_PRESET_ID) ?: store.lastSelectedId)
        findViewById<TextView>(R.id.title).text = getString(R.string.settings_title, preset.name)
        findViewById<TextView>(R.id.subtitle).text = preset.description
        form = findViewById(R.id.form)
        build()
        val buttons = findViewById<LinearLayout>(R.id.buttons)
        buttons.addView(Ui.button(this, getString(R.string.btn_save), primary = true) { save() })
        buttons.addView(Ui.button(this, getString(R.string.btn_reset)) {
            store.reset(preset.id); preset = store.get(preset.id); form.removeAllViews(); build()
            Toast.makeText(this, "Preset restablecido", Toast.LENGTH_SHORT).show()
        })
    }

    private fun field(label: String, value: String, numeric: Boolean = true, help: String? = null): EditText {
        form.addView(Ui.label(this, label, size = 13f, bold = true).apply { setPadding(0, Ui.dp(this@SettingsActivity, 12), 0, 0) })
        if (help != null) form.addView(Ui.label(this, help, dim = true, size = 12f))
        val e = EditText(this).apply {
            setText(value)
            setTextColor(getColor(R.color.text))
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
        }
        form.addView(e)
        return e
    }

    private fun check(label: String, value: Boolean): CheckBox {
        val c = CheckBox(this).apply { text = label; isChecked = value; setTextColor(getColor(R.color.text)) }
        form.addView(c)
        return c
    }

    private fun build() {
        val p = preset
        if (p.isVideo) form.addView(Ui.label(this, "Preset de VIDEO: se usan ISO, exposición por frame (no puede superar 1/fps), FPS, duración, WB, foco, lente y cuenta atrás. Los campos de apilado no aplican.", size = 13f).apply {
            setTextColor(getColor(R.color.accent)); setPadding(0, 0, 0, Ui.dp(this@SettingsActivity, 8))
        })
        iso = field("ISO", p.iso.toString(), help = "El S24 Ultra suele exponer 50–3200 a terceros; se recorta al rango real.")
        exposure = field("Exposición por frame (s)", if (p.exposureNs == Preset.MAX_EXPOSURE) "max" else fmt(p.exposureNs / 1e9), numeric = false,
            help = "Escribe \"max\" para usar la máxima que el teléfono declara a apps de terceros (1/9 s en el S24 Ultra).")
        forceExposure = check("Forzar la exposición aunque supere el límite declarado (experimental)", p.forceExposure)
        form.addView(Ui.label(this, "Pide el valor de arriba tal cual; el sensor puede ignorarlo. Comprueba primero con el Diagnóstico → Probar exposición forzada.", dim = true, size = 12f))
        totalExposure = field("Exposición total objetivo por toma (s)", fmt(p.totalExposureNs / 1e9),
            help = "Si es > 0, la app calcula cuántos frames hacen falta con la exposición real del teléfono (p. ej. 30 s ÷ 3.9 s = 8 frames). 0 = usar el número fijo de abajo.")
        frames = field("Frames por toma (si el objetivo es 0)", p.framesPerShot.toString(), help = "Se apilan según el modo de abajo (1–${Preset.MAX_FRAMES}).")
        form.addView(Ui.label(this, "Apilado", size = 13f, bold = true).apply { setPadding(0, Ui.dp(this@SettingsActivity, 12), 0, 0) })
        form.addView(Ui.label(this, "ADD: suma los frames = exposición larga real (recomendado) · AVERAGE: mismos datos pero el JPEG/DNG se ven con el brillo de un solo frame · LIGHTEN: cada toma se suma y las tomas se fusionan con el máximo (star trails) · NONE: guarda cada frame suelto", dim = true, size = 12f))
        stack = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, StackMode.values().map { it.name })
            setSelection(p.stackMode.ordinal)
        }
        form.addView(stack)
        interval = field("Intervalo entre tomas (s)", fmt(p.intervalMs / 1000.0), help = "0 = una toma tras otra sin esperar.")
        duration = field("Duración de la sesión (min)", fmt(p.durationMs / 60000.0), help = "0 = hasta que pulses Detener. Se ignora si hay número de tomas.")
        shots = field("Número de tomas", p.totalShots.toString(), help = "0 = usar la duración.")
        saveRaw = check("Guardar DNG (RAW)", p.saveRaw)
        saveJpeg = check("Guardar JPEG", p.saveJpeg)
        makeVideo = check("Montar video MP4 (timelapse)", p.makeVideo)
        fps = field("FPS del video", p.videoFps.toString())
        videoHalfRes = check("Frames de video a media resolución (más rápido, 1080p)", p.videoHalfRes)
        focus = field("Enfoque (dioptrías, 0 = infinito)", fmt(p.focusDiopters.toDouble()), help = "Si las estrellas salen desenfocadas prueba 0.1–0.3.")
        wb = field("Balance de blancos (K, 0 = auto)", p.wbKelvin.toString(), help = "Solo afecta al JPEG; el DNG se ajusta después.")
        form.addView(Ui.label(this, getString(R.string.lens_label), size = 13f, bold = true).apply { setPadding(0, Ui.dp(this@SettingsActivity, 12), 0, 0) })
        val cams = runCatching { CameraInfo.loadAll(this).filter { it.facingBack } }.getOrDefault(emptyList())
        cameraIds = if (cams.isEmpty()) listOf(p.cameraId) else cams.map { it.id }
        val labels = if (cams.isEmpty()) listOf(p.cameraId) else cams.map { c ->
            val f = c.focalLengths.firstOrNull()?.let { String.format(Locale.US, "%.1f mm", it) } ?: "?"
            "${c.id} · $f · RAW ${if (c.hasRaw) "sí" else "no"} · máx ${com.aurorafotos.util.Fmt.exposure(c.maxExposureNs)}"
        }
        camera = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(maxOf(0, cameraIds.indexOf(p.cameraId)))
        }
        form.addView(camera)
        countdown = field("Cuenta atrás antes de empezar (s)", p.countdownSec.toString())
    }

    private fun save() {
        try {
            val expText = exposure.text.toString().trim().lowercase(Locale.US)
            val expNs = if (expText == "max" || expText.isEmpty()) Preset.MAX_EXPOSURE
            else (expText.replace(',', '.').toDouble() * 1e9).toLong().also { require(it > 0) { "exposición inválida" } }
            val np = preset.copy(
                iso = iso.text.toString().toInt().coerceIn(1, 100_000),
                exposureNs = expNs,
                framesPerShot = frames.text.toString().toInt().coerceIn(1, Preset.MAX_FRAMES),
                totalExposureNs = (totalExposure.text.toString().replace(',', '.').toDouble() * 1e9).toLong().coerceAtLeast(0),
                stackMode = StackMode.values()[stack.selectedItemPosition],
                intervalMs = (interval.text.toString().replace(',', '.').toDouble() * 1000).toLong().coerceAtLeast(0),
                durationMs = (duration.text.toString().replace(',', '.').toDouble() * 60000).toLong().coerceAtLeast(0),
                totalShots = shots.text.toString().toInt().coerceAtLeast(0),
                saveRaw = saveRaw.isChecked,
                saveJpeg = saveJpeg.isChecked,
                makeVideo = makeVideo.isChecked,
                videoFps = fps.text.toString().toInt().coerceIn(1, 120),
                focusDiopters = focus.text.toString().replace(',', '.').toFloat().coerceAtLeast(0f),
                wbKelvin = wb.text.toString().toInt().coerceAtLeast(0),
                cameraId = cameraIds.getOrElse(camera.selectedItemPosition) { preset.cameraId },
                countdownSec = countdown.text.toString().toInt().coerceIn(0, 120),
                forceExposure = forceExposure.isChecked,
                videoHalfRes = videoHalfRes.isChecked,
            )
            store.save(np)
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, "Valor inválido: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    companion object {
        const val EXTRA_PRESET_ID = "preset_id"
    }
}
