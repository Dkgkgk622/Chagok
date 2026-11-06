package com.chagok.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the app process in the foreground state from boot (or first launch) on.
 *
 * Why it does not stop in P: Android 12+ refuses to start a foreground service while
 * the app is in the background (measured on the emulator: ForegroundServiceStartNotAllowedException
 * at trip start). Without it the system may freeze or kill the process between trips.
 * So it starts where Android allows it (boot, app update, app opened) and stays running.
 * It does no work itself: recording happens in TripRecorder via SDK change listeners (no polling).
 */
class RecorderService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this, driving)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 1

        @Volatile var running = false
            private set
        @Volatile private var driving = false

        /** Change the notification text only; never needs a (background) service start. */
        fun setDriving(context: Context, isDriving: Boolean) {
            driving = isDriving
            if (running) {
                context.getSystemService(NotificationManager::class.java)
                    .notify(ID, buildNotification(context, isDriving))
            }
        }

        private fun buildNotification(context: Context, isDriving: Boolean): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "운행 기록", NotificationManager.IMPORTANCE_MIN)
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("차곡")
                .setContentText(if (isDriving) "운행을 기록하고 있어요" else "운행을 기다리는 중")
                .setOngoing(true)
                .build()
        }
    }
}
