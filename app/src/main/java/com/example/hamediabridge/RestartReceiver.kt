package com.example.hamediabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver that restarts the MediaButtonService when triggered by alarm.
 * This provides an extra layer of persistence - if the service is killed,
 * the alarm will trigger a restart.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "RestartReceiver triggered")

        val settingsManager = SettingsManager(context)

        if (settingsManager.serviceEnabled && settingsManager.isAuthenticated) {
            Log.d(TAG, "Restarting MediaButtonService")
            MediaButtonService.start(context)
        } else {
            Log.d(TAG, "Service not enabled or not authenticated, skipping restart")
        }
    }

    companion object {
        private const val TAG = "RestartReceiver"
    }
}
