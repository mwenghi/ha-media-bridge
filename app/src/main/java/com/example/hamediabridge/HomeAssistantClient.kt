package com.example.hamediabridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeAssistantClient(private val settingsManager: SettingsManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fireEvent(eventType: String, additionalData: Map<String, Any> = emptyMap()): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = settingsManager.homeAssistantUrl.trimEnd('/')
                val accessToken = settingsManager.accessToken

                if (baseUrl.isEmpty()) {
                    return@withContext Result.failure(Exception("Home Assistant URL not configured"))
                }

                if (accessToken.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                val url = "$baseUrl/api/events/$eventType"

                val eventData = JSONObject().apply {
                    put("source", "android_media_bridge")
                    put("timestamp", getCurrentTimestamp())
                    additionalData.forEach { (key, value) ->
                        put(key, value)
                    }
                }

                val requestBody = eventData.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Firing event: $eventType to $url")

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.d(TAG, "Event fired successfully: $eventType")
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to fire event: ${response.code} - $errorBody")
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception firing event: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun firePlayEvent() = fireEvent("android_media_play")
    suspend fun firePauseEvent() = fireEvent("android_media_pause")
    suspend fun fireNextEvent() = fireEvent("android_media_next")
    suspend fun firePreviousEvent() = fireEvent("android_media_previous")
    suspend fun fireStopEvent() = fireEvent("android_media_stop")

    suspend fun testConnection(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = settingsManager.homeAssistantUrl.trimEnd('/')
                val accessToken = settingsManager.accessToken

                if (baseUrl.isEmpty()) {
                    return@withContext Result.failure(Exception("Home Assistant URL not configured"))
                }

                if (accessToken.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return dateFormat.format(Date())
    }

    companion object {
        private const val TAG = "HomeAssistantClient"
    }
}
