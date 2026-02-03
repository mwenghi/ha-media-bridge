package com.example.hamediabridge

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that keeps the MediaButtonService alive and refreshes
 * the media session to maintain priority for media button routing.
 *
 * This is similar to what Spotify does - it periodically "wakes up" to
 * ensure it can still capture media button events.
 */
class MediaSessionRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "MediaSessionRefreshWorker running")

        val settingsManager = SettingsManager(applicationContext)

        if (!settingsManager.serviceEnabled || !settingsManager.isAuthenticated) {
            Log.d(TAG, "Service not enabled or not authenticated, skipping refresh")
            return Result.success()
        }

        // Start/refresh the service
        try {
            MediaButtonService.start(applicationContext)
            Log.d(TAG, "Service refresh triggered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh service", e)
            return Result.retry()
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "SessionRefreshWorker"
        private const val WORK_NAME = "media_session_refresh"

        /**
         * Schedule periodic refresh work. Called when service is enabled.
         * Runs every 15 minutes to keep the service warm.
         */
        fun schedule(context: Context) {
            Log.d(TAG, "Scheduling periodic refresh work")

            val workRequest = PeriodicWorkRequestBuilder<MediaSessionRefreshWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES  // Flex interval
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Periodic refresh work scheduled")
        }

        /**
         * Cancel periodic refresh work. Called when service is disabled.
         */
        fun cancel(context: Context) {
            Log.d(TAG, "Cancelling periodic refresh work")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
