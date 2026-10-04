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
                        SessionState.DONE -> getString(R.string.status_done, p.sessionName)
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
        selectedId = store.lastSelectedId

        btnStart.setOnClickListener {
            if (!Ui.hasPermissions(this, Ui.REQUIRED_PERMISSIONS)) {
                requestPermissions(Ui.REQUIRED_PERMISSIONS, 1)
                return@setOnClickListener
            }
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_PRESET_ID, selectedId))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PRESET_ID, selectedId))
        }
        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            if (!Ui.hasPermissions(this, Ui.REQUIRED_PERMISSIONS)) requestPermissions(Ui.REQUIRED_PERMISSIONS, 2)
            else startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        findViewById<TextView>(R.id.versionText).text = "v$version · Galaxy S24 Ultra · Camera2"
    }

    override fun onResume() {
        super.onResume()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!Ui.hasPermissions(this, Ui.REQUIRED_PERMISSIONS)) {
            status.text = getString(R.string.perm_needed); Ui.show(status, true)
        } else if (requestCode == 2) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun renderPresets() {
        list.removeAllViews()
        for (p in store.all()) {
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
        val b = StringBuilder("ISO ${p.iso} · $exp · ${p.framesPerShot} frame(s)/toma")
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
