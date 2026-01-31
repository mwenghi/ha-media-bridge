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

    suspend fun fireDeviceOnEvent(entityId: String) =
        fireEvent("android_device_on", mapOf("entity_id" to entityId))

    suspend fun fireDeviceOffEvent(entityId: String) =
        fireEvent("android_device_off", mapOf("entity_id" to entityId))

    suspend fun fireDeviceVolumeEvent(entityId: String, volume: Int) =
        fireEvent("android_device_volume", mapOf(
            "entity_id" to entityId,
            "volume" to volume
        ))

    data class EntityState(
        val entityId: String,
        val state: String,
        val brightness: Int? = null
    )

    // Fetch device list from Home Assistant input_text helper
    suspend fun getDeviceList(): Result<List<Device>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = settingsManager.homeAssistantUrl.trimEnd('/')
                val accessToken = settingsManager.accessToken

                if (baseUrl.isEmpty() || accessToken.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Not configured"))
                }

                val url = "$baseUrl/api/states/input_text.android_media_bridge_devices"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                Log.d(TAG, "Fetching device list from HA")

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val state = json.getString("state")

                    if (state.isBlank() || state == "unknown" || state == "unavailable") {
                        return@withContext Result.success(emptyList())
                    }

                    val devices = parseDeviceListJson(state)
                    Log.d(TAG, "Fetched ${devices.size} devices from HA")
                    Result.success(devices)
                } else if (response.code == 404) {
                    // Entity doesn't exist yet
                    Log.d(TAG, "Device list entity not found in HA")
                    Result.success(emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching device list: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    // Push device list to Home Assistant input_text helper
    suspend fun setDeviceList(devices: List<Device>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = settingsManager.homeAssistantUrl.trimEnd('/')
                val accessToken = settingsManager.accessToken

                if (baseUrl.isEmpty() || accessToken.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Not configured"))
                }

                val url = "$baseUrl/api/services/input_text/set_value"

                val deviceListJson = buildDeviceListJson(devices)

                val requestData = JSONObject().apply {
                    put("entity_id", "input_text.android_media_bridge_devices")
                    put("value", deviceListJson)
                }

                val requestBody = requestData.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Pushing device list to HA: $deviceListJson")

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.d(TAG, "Device list pushed to HA successfully")
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to push device list: ${response.code} - $errorBody")
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception pushing device list: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private fun parseDeviceListJson(json: String): List<Device> {
        return try {
            val jsonArray = org.json.JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                // Support both short keys (n, e) and long keys (name, entityId)
                val name = obj.optString("n", obj.optString("name", ""))
                val entityId = obj.optString("e", obj.optString("entityId", ""))
                Device(name, entityId)
            }.filter { it.name.isNotEmpty() && it.entityId.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device list JSON: ${e.message}")
            emptyList()
        }
    }

    private fun buildDeviceListJson(devices: List<Device>): String {
        // Use short keys to fit more devices in 255 char limit
        val jsonArray = org.json.JSONArray()
        devices.forEach { device ->
            val obj = JSONObject().apply {
                put("n", device.name)
                put("e", device.entityId)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    suspend fun getEntityState(entityId: String): Result<EntityState> {
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

                val url = "$baseUrl/api/states/$entityId"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build()

                Log.d(TAG, "Fetching entity state: $entityId from $url")

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val state = json.getString("state")

                    // Try to get brightness from attributes
                    var brightness: Int? = null
                    val attributes = json.optJSONObject("attributes")
                    if (attributes != null) {
                        // brightness is 0-255 in HA, convert to 0-100
                        if (attributes.has("brightness")) {
                            val rawBrightness = attributes.optInt("brightness", -1)
                            if (rawBrightness >= 0) {
                                brightness = (rawBrightness * 100 / 255)
                            }
                        }
                    }

                    Log.d(TAG, "Entity state: $entityId = $state, brightness = $brightness")
                    Result.success(EntityState(entityId, state, brightness))
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to get entity state: ${response.code} - $errorBody")
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception getting entity state: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

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
