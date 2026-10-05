package com.aurorafotos.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aurorafotos.R
import com.aurorafotos.capture.CaptureService
import com.aurorafotos.capture.SessionState
import com.aurorafotos.diag.DiagnosticsActivity
import com.aurorafotos.presets.Module
import com.aurorafotos.presets.Preset
import com.aurorafotos.presets.PresetStore
import com.aurorafotos.util.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var store: PresetStore
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var tabs: LinearLayout
    private lateinit var moduleHint: TextView
    private var module: Module = Module.PHOTO
    private var selectedId: String = ""
    private val scope = MainScope()
    private var service: CaptureService? = null
    private var observe: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as CaptureService.LocalBinder).service
            observe?.cancel()
            observe = scope.launch {
                service!!.progress.collect { p ->
                    val active = p.isActive
                    Ui.show(status, active || p.state == SessionState.DONE || p.state == SessionState.ERROR)
                    status.text = when (p.state) {
                        SessionState.COUNTDOWN -> getString(R.string.status_countdown, p.countdown)
                        SessionState.CAPTURING -> getString(R.string.status_running, p.shot, if (p.totalShots > 0) p.totalShots.toString() else "∞", p.frame) + " · " + Fmt.seconds(p.elapsedMs)
                        SessionState.PROCESSING -> getString(R.string.status_processing) + " " + p.message
                        SessionState.DONE -> getString(R.string.status_done, p.sessionName, p.message)
                        SessionState.ERROR -> getString(R.string.status_error, p.error ?: "?")
                        else -> ""
                    }
                    btnStart.text = if (active) "Ver sesión en curso" else getString(R.string.btn_frame)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PresetStore(this)
        list = findViewById(R.id.presetList)
        status = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        tabs = findViewById(R.id.moduleTabs)
        moduleHint = findViewById(R.id.moduleHint)
        module = store.lastModule
        selectedId = store.lastSelectedId
        if (store.get(selectedId).module != module) selectedId = store.all().first { it.module == module }.id

        btnStart.setOnClickListener { withCameraPermission(REQ_CAPTURE) { openCapture() } }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PRESET_ID, selectedId))
        }
        findViewById<Button>(R.id.btnDiag).setOnClickListener { withCameraPermission(REQ_DIAG) { openDiagnostics() } }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        findViewById<TextView>(R.id.versionText).text = "v$version · Galaxy S24 Ultra · Camera2"
    }

    override fun onResume() {
        super.onResume()
        renderTabs()
        renderPresets()
        bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        observe?.cancel()
        runCatching { unbindService(connection) }
        service = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openCapture() {
        startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_PRESET_ID, selectedId))
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }

    /**
     * Runs [action] once the camera permission is granted. Notifications are requested at the
     * same time but are optional (the capture service works without them, just silently).
     */
    private fun withCameraPermission(requestCode: Int, action: () -> Unit) {
        if (Ui.hasPermissions(this, arrayOf(android.Manifest.permission.CAMERA))) {
            action(); return
        }
        requestPermissions(Ui.REQUIRED_PERMISSIONS, requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Ui.hasPermissions(this, arrayOf(android.Manifest.permission.CAMERA))) {
            Ui.show(status, false)
            when (requestCode) {
                REQ_CAPTURE -> openCapture()
                REQ_DIAG -> openDiagnostics()
            }
            return
        }
        // Denied. If Android will not show the dialog again, send the user to the app settings.
        val permanently = !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)
        status.text = getString(R.string.perm_needed) + if (permanently) " Toca aquí para abrir los ajustes de la app y activar Cámara." else ""
        Ui.show(status, true)
        status.setOnClickListener {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }
    }

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_DIAG = 2
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for (m in Module.values()) {
            val b = Button(this).apply {
                text = m.title
                isAllCaps = false
                textSize = 14f
                background = getDrawable(if (m == module) R.drawable.bg_button else R.drawable.bg_button_secondary)
                setTextColor(getColor(if (m == module) R.color.bg else R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 44), 1f).apply {
                    marginEnd = Ui.dp(this@MainActivity, 6)
                }
                setOnClickListener {
                    module = m
                    store.lastModule = m
                    selectedId = store.all().first { it.module == m }.id
                    store.lastSelectedId = selectedId
                    renderTabs(); renderPresets()
                }
            }
            tabs.addView(b)
        }
        moduleHint.text = when (module) {
            Module.PHOTO -> "Una sola toma apilada: DNG de 16 bits + JPEG. Elige y pulsa Encuadrar."
            Module.TIMELAPSE -> "Secuencias de tomas apiladas → JPEG por toma + MP4. Star trails incluido."
            Module.VIDEO -> "Grabación real con exposición manual (sin audio). 24 fps o obturador lento a 9 fps."
        }
    }

    private fun renderPresets() {
        list.removeAllViews()
        for (p in store.all().filter { it.module == module }) {
            val card = Ui.card(this, p.id == selectedId)
            card.addView(Ui.label(this, p.name, size = 18f, bold = true))
            card.addView(Ui.label(this, p.description, dim = true, size = 13f))
            card.addView(Ui.label(this, summary(p), dim = false, size = 12f).apply {
                setTextColor(getColor(R.color.accent))
            })
            card.setOnClickListener {
                selectedId = p.id
                store.lastSelectedId = p.id
                renderPresets()
            }
            list.addView(card)
        }
    }

    private fun summary(p: Preset): String {
        val exp = if (p.exposureNs == Preset.MAX_EXPOSURE) "exp. máx" else Fmt.exposure(p.exposureNs)
        if (p.isVideo) {
            val dur = if (p.durationMs > 0) Fmt.seconds(p.durationMs) else "hasta detener"
            return "VIDEO · ISO ${p.iso} · $exp · ${p.videoFps} fps · $dur → MP4 4K"
        }
        val frames = if (p.totalExposureNs > 0) "≈${Fmt.exposure(p.totalExposureNs)} totales por toma" else "${p.framesPerShot} frame(s)/toma"
        val b = StringBuilder("ISO ${p.iso} · $exp · $frames")
        if (p.stackMode.name != "NONE") b.append(" · ${p.stackMode.name.lowercase()}")
        if (p.intervalMs > 0) b.append(" · cada ${Fmt.seconds(p.intervalMs)}")
        if (p.totalShots > 0) b.append(" · ${p.totalShots} toma(s)")
        else if (p.durationMs > 0) b.append(" · ${Fmt.seconds(p.durationMs)}")
        else b.append(" · hasta detener")
        val outs = ArrayList<String>()
        if (p.saveRaw) outs += "DNG"
        if (p.saveJpeg) outs += "JPEG"
        if (p.makeVideo) outs += "MP4 ${p.videoFps}fps"
        b.append(" → ").append(outs.joinToString("+"))
        return b.toString()
    }
}
