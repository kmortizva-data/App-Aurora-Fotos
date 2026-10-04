package com.aurorafotos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AuroraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_CAPTURE,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progreso de la sesión de captura"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_CAPTURE = "capture"
    }
}
