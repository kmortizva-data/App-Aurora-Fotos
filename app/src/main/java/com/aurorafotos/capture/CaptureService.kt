package com.aurorafotos.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.aurorafotos.AuroraApp
import com.aurorafotos.R
import com.aurorafotos.presets.PresetStore
import com.aurorafotos.ui.CaptureActivity
import com.aurorafotos.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service (type camera) that owns the [CaptureEngine] so a session keeps
 * running with the screen off or the activity gone. Started while the app is visible,
 * as Android 14+ requires for camera foreground services.
 */
class CaptureService : Service() {
    private val tag = "CaptureService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine by lazy { CaptureEngine(applicationContext) }
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val binder = LocalBinder()
    val progress: StateFlow<SessionProgress> get() = engine.progress
    val isRunning: Boolean get() = job?.isActive == true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val presetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: return START_NOT_STICKY
                val rotation = intent.getIntExtra(EXTRA_ROTATION, 0)
                start(presetId, rotation)
            }
            ACTION_STOP -> engine.requestStop()
        }
        return START_NOT_STICKY
    }

    private fun start(presetId: String, rotation: Int) {
        if (isRunning) {
            Log.w(tag, "session already running"); return
        }
        val preset = PresetStore(this).get(presetId)
        startForeground(NOTIF_ID, buildNotification("Preparando…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuroraFotos:capture").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        job = scope.launch {
            val notifier = launch {
                engine.progress.collect { p ->
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(describe(p)))
                }
            }
            try {
                engine.run(preset, rotation)
            } finally {
                notifier.cancel()
                releaseWakeLock()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(describe(engine.progress.value)))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun describe(p: SessionProgress): String = when (p.state) {
        SessionState.IDLE -> getString(R.string.status_idle)
        SessionState.COUNTDOWN -> getString(R.string.status_countdown, p.countdown)
        SessionState.CAPTURING -> getString(R.string.status_running, p.shot, if (p.totalShots > 0) p.totalShots.toString() else "∞", p.frame) +
            " · " + Fmt.exposure(p.exposureNs) + " ISO " + p.iso + " · " + Fmt.seconds(p.elapsedMs)
        SessionState.PROCESSING -> getString(R.string.status_processing) + (if (p.message.isNotEmpty()) " " + p.message else "")
        SessionState.DONE -> getString(R.string.status_done, p.sessionName)
        SessionState.ERROR -> getString(R.string.status_error, p.error ?: "?")
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, AuroraApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun requestStop() = engine.requestStop()

    override fun onDestroy() {
        engine.requestStop()
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aurorafotos.action.START"
        const val ACTION_STOP = "com.aurorafotos.action.STOP"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_ROTATION = "rotation"
        const val NOTIF_ID = 1

        fun startIntent(context: Context, presetId: String, rotation: Int): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PRESET_ID, presetId).putExtra(EXTRA_ROTATION, rotation)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
