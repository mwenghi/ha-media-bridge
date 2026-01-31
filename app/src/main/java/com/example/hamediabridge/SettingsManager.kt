package com.example.hamediabridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class Device(val name: String, val entityId: String)

class SettingsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val regularPrefs: SharedPreferences = context.getSharedPreferences(
        "app_prefs",
        Context.MODE_PRIVATE
    )

    var homeAssistantUrl: String
        get() = regularPrefs.getString(KEY_HA_URL, "") ?: ""
        set(value) = regularPrefs.edit().putString(KEY_HA_URL, value).apply()

    var accessToken: String?
        get() = securePrefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = securePrefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpirationTime: Long
        get() = securePrefs.getLong(KEY_TOKEN_EXPIRATION, 0)
        set(value) = securePrefs.edit().putLong(KEY_TOKEN_EXPIRATION, value).apply()

    var serviceEnabled: Boolean
        get() = regularPrefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = regularPrefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var deviceList: List<Device>
        get() {
            val json = regularPrefs.getString(KEY_DEVICE_LIST, "[]") ?: "[]"
            return try {
                val jsonArray = JSONArray(json)
                (0 until jsonArray.length()).map { i ->
                    val obj = jsonArray.getJSONObject(i)
                    Device(obj.getString("name"), obj.getString("entityId"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonArray = JSONArray()
            value.forEach { device ->
                val obj = JSONObject().apply {
                    put("name", device.name)
                    put("entityId", device.entityId)
                }
                jsonArray.put(obj)
            }
            regularPrefs.edit().putString(KEY_DEVICE_LIST, jsonArray.toString()).apply()
        }

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrEmpty()

    val isTokenExpired: Boolean
        get() = System.currentTimeMillis() >= tokenExpirationTime

    fun clearTokens() {
        securePrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRATION)
            .apply()
    }

    companion object {
        private const val KEY_HA_URL = "ha_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_DEVICE_LIST = "device_list"
    }
}
