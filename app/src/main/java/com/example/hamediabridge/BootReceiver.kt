package com.example.hamediabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed received")

            val settingsManager = SettingsManager(context)

            if (settingsManager.serviceEnabled && settingsManager.isAuthenticated) {
                Log.d(TAG, "Starting MediaButtonService after boot")
                MediaButtonService.start(context)
            } else {
                Log.d(TAG, "Service not enabled or not authenticated, skipping auto-start")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
