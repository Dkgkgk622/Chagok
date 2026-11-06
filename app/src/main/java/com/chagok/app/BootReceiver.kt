package com.chagok.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot and app-update broadcasts start the process (ChagokApp.onCreate starts the vehicle
 * listeners) and are among the few moments Android allows a background app to start
 * a foreground service, so the recorder service is started here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as ChagokApp).startRecorderService("broadcast ${intent.action}")
    }
}
