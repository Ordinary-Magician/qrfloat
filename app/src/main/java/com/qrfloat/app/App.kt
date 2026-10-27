package com.qrfloat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    companion object {
        lateinit var instance: App
        lateinit var relay: RelayClient
        const val CHANNEL_ID = "qr_transfer"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        relay = RelayClient()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "码传", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
