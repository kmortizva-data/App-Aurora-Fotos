package com.aurorafotos.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.aurorafotos.R
import com.aurorafotos.camera.CameraController
import com.aurorafotos.camera.CameraInfo
import com.aurorafotos.camera.ExposureParams
import com.aurorafotos.capture.CaptureService
import com.aurorafotos.capture.SessionProgress
import com.aurorafotos.capture.SessionState
import com.aurorafotos.presets.Preset
import com.aurorafotos.presets.PresetStore
import com.aurorafotos.util.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Two modes in one screen:
 *  1. Framing: live manual-exposure preview so the user can compose and check focus.
 *  2. Session: the foreground service is running; show progress, dim the screen, allow Stop.
 */
class CaptureActivity : Activity() {
    private val tag = "CaptureActivity"
    private lateinit var preview: TextureView
    private lateinit var presetName: TextView
    private lateinit var hint: TextView
    private lateinit var bigStatus: TextView
    private lateinit var status: TextView
    private lateinit var params: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var preset: Preset
    private val scope = MainScope()
    private var previewController: CameraController? = null
    private var previewJob: Job? = null
    private var service: CaptureService? = null
    private var observe: Job? = null
    private var sessionMode = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as CaptureService.LocalBinder).service
            if (service!!.isRunning) enterSessionMode()
            observe?.cancel()
            observe = scope.launch { service!!.progress.collect { render(it) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        preview = findViewById(R.id.preview)
        presetName = findViewById(R.id.presetName)
        hint = findViewById(R.id.hint)
        bigStatus = findViewById(R.id.bigStatus)
        status = findViewById(R.id.status)
        params = findViewById(R.id.params)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        val store = PresetStore(this)
        val id = intent.getStringExtra(EXTRA_PRESET_ID) ?: store.lastSelectedId
        preset = store.get(id)
        presetName.text = preset.name
        params.text = paramsLine(preset)

        btnStart.setOnClickListener { startSession() }
        btnStop.setOnClickListener {
            service?.requestStop() ?: startService(CaptureService.stopIntent(this))
            btnStop.isEnabled = false
            status.text = "Deteniendo… se guardará lo capturado"
        }
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!sessionMode) startFramingPreview()
    }

    override fun onPause() {
        super.onPause()
        stopFramingPreview()
        observe?.cancel()
        runCatching { unbindService(connection) }
        service = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- framing -------------------------------------------------------------------------

    private fun startFramingPreview() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (preview.isAvailable) openPreview(preview.surfaceTexture!!)
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = openPreview(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun openPreview(st: SurfaceTexture) {
        if (sessionMode || previewJob?.isActive == true) return
        previewJob = scope.launch {
            try {
                val info = CameraInfo.load(this@CaptureActivity, preset.cameraId)
                val size = choosePreviewSize(info.previewSizes)
                st.setDefaultBufferSize(size.width, size.height)
                val c = CameraController(this@CaptureActivity, info)
                previewController = c
                c.open()
                c.configure(Surface(st), wantRaw = false, wantJpeg = false)
                // Bright preview: high ISO, 1/4 s, same focus as the preset.
                c.startPreview(
                    ExposureParams(
                        iso = info.clampIso(maxOf(preset.iso, 3200)), exposureNs = 250_000_000L,
                        focusDiopters = preset.focusDiopters,
                        awbMode = CameraInfo.awbModeForKelvin(preset.wbKelvin, info.awbModes),
                    )
                )
                val exp = if (preset.exposureNs == Preset.MAX_EXPOSURE) info.maxExposureNs else info.clampExposure(preset.exposureNs)
                val frames = preset.effectiveFrames(exp)
                status.text = "Exposición real por frame: ${Fmt.exposure(exp)} (máx. del dispositivo ${Fmt.exposure(info.maxExposureNs)}) · ISO ${info.clampIso(preset.iso)}\n" +
                    "Cada toma: $frames frames ≈ ${Fmt.exposure(frames * exp)} de exposición"
            } catch (t: Throwable) {
                Log.e(tag, "preview failed", t)
                status.text = getString(R.string.status_error, t.message)
            }
        }
    }

    private fun stopFramingPreview() {
        previewJob?.cancel(); previewJob = null
        previewController?.let { runCatching { it.stopPreview() }; runCatching { it.close() } }
        previewController = null
    }

    private fun choosePreviewSize(sizes: List<Size>): Size {
        val fourThirds = sizes.filter { Math.abs(it.width * 3 - it.height * 4) < 8 && it.width <= 1920 }
        return fourThirds.maxByOrNull { it.width } ?: sizes.minByOrNull { Math.abs(it.width - 1440) } ?: Size(1440, 1080)
    }

    // ---- session -------------------------------------------------------------------------

    private fun startSession() {
        if (!Ui.hasPermissions(this, arrayOf(android.Manifest.permission.CAMERA))) {
            requestPermissions(Ui.REQUIRED_PERMISSIONS, 1); return
        }
        stopFramingPreview()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        startForegroundService(CaptureService.startIntent(this, preset.id, rotation))
        enterSessionMode()
    }

    private fun enterSessionMode() {
        sessionMode = true
        stopFramingPreview()
        Ui.show(preview, false)
        Ui.show(btnStart, false)
        Ui.show(btnStop, true)
        btnStop.isEnabled = true
        Ui.show(bigStatus, true)
        hint.text = "Puedes apagar la pantalla: la captura sigue en segundo plano. Para parar, usa Detener aquí o en la notificación."
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.03f }
    }

    private fun render(p: SessionProgress) {
        if (!sessionMode && p.isActive) enterSessionMode()
        when (p.state) {
            SessionState.COUNTDOWN -> {
                bigStatus.text = p.countdown.toString()
                status.text = getString(R.string.status_countdown, p.countdown)
            }
            SessionState.CAPTURING -> {
                bigStatus.text = if (p.totalShots > 0) "${p.shot}/${p.totalShots}" else "${p.shot}"
                status.text = getString(R.string.status_running, p.shot, if (p.totalShots > 0) p.totalShots.toString() else "∞", p.frame) +
                    "\n${Fmt.exposure(p.exposureNs)} · ISO ${p.iso} · ${Fmt.seconds(p.elapsedMs)}"
            }
            SessionState.PROCESSING -> {
                bigStatus.text = "…"
                status.text = getString(R.string.status_processing) + " " + p.message
            }
            SessionState.DONE -> {
                bigStatus.text = "✓"
                status.text = getString(R.string.status_done, p.sessionName, p.message)
                finishSession()
            }
            SessionState.ERROR -> {
                bigStatus.text = "!"
                status.text = getString(R.string.status_error, p.error ?: "?")
                finishSession()
            }
            SessionState.IDLE -> {}
        }
    }

    private fun finishSession() {
        Ui.show(btnStop, false)
        btnStart.text = "Volver"
        Ui.show(btnStart, true)
        btnStart.setOnClickListener { finish() }
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun paramsLine(p: Preset): String {
        val exp = if (p.exposureNs == Preset.MAX_EXPOSURE) "exp. máx" else Fmt.exposure(p.exposureNs)
        val frames = if (p.totalExposureNs > 0) "hasta ${Fmt.exposure(p.totalExposureNs)}" else "× ${p.framesPerShot}"
        return "ISO ${p.iso} · $exp $frames · ${p.stackMode.name.lowercase()} · cámara ${p.cameraId} · foco ${p.focusDiopters} dpt"
    }

    companion object {
        const val EXTRA_PRESET_ID = "preset_id"
    }
}
