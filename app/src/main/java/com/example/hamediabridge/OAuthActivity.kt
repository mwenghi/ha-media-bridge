package com.example.hamediabridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OAuthActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager(this)

        val action = intent.action
        val data = intent.data

        Log.d(TAG, "OAuthActivity started with action: $action, data: $data")

        when {
            action == ACTION_START_AUTH -> {
                startAuthorization()
            }
            Intent.ACTION_VIEW == action && data != null -> {
                handleRedirect(data)
            }
            else -> {
                Log.e(TAG, "Unknown action or missing data")
                finish()
            }
        }
    }

    private fun startAuthorization() {
        val haUrl = settingsManager.homeAssistantUrl.trimEnd('/')

        if (haUrl.isEmpty()) {
            Toast.makeText(this, R.string.url_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val authUrl = Uri.parse("$haUrl/auth/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .build()

        Log.d(TAG, "Starting authorization: $authUrl")

        val browserIntent = Intent(Intent.ACTION_VIEW, authUrl)
        startActivity(browserIntent)
    }

    private fun handleRedirect(uri: Uri) {
        Log.d(TAG, "Handling redirect: $uri")

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        when {
            error != null -> {
                Log.e(TAG, "Authorization error: $error")
                Toast.makeText(this, "${getString(R.string.oauth_error)}: $error", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
            code != null -> {
                Log.d(TAG, "Got authorization code, exchanging for token")
                exchangeCodeForToken(code)
            }
            else -> {
                Log.e(TAG, "No code or error in redirect")
                Toast.makeText(this, R.string.oauth_error, Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun exchangeCodeForToken(code: String) {
        val haUrl = settingsManager.homeAssistantUrl.trimEnd('/')
        val tokenUrl = "$haUrl/auth/token"

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val formBody = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("client_id", CLIENT_ID)
                        .build()

                    val request = Request.Builder()
                        .url(tokenUrl)
                        .post(formBody)
                        .build()

                    Log.d(TAG, "Requesting token from $tokenUrl")

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    Log.d(TAG, "Token response: ${response.code} - $responseBody")

                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val accessToken = json.getString("access_token")
                        val refreshToken = json.optString("refresh_token", null)
                        val expiresIn = json.optLong("expires_in", 0)

                        Triple(accessToken, refreshToken, expiresIn)
                    } else {
                        throw Exception("Token request failed: ${response.code}")
                    }
                }

                val (accessToken, refreshToken, expiresIn) = result

                settingsManager.accessToken = accessToken
                if (refreshToken != null) {
                    settingsManager.refreshToken = refreshToken
                }
                if (expiresIn > 0) {
                    settingsManager.tokenExpirationTime = System.currentTimeMillis() + (expiresIn * 1000)
                }

                Log.d(TAG, "Token saved successfully")
                Toast.makeText(this@OAuthActivity, R.string.oauth_success, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()

            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                Toast.makeText(this@OAuthActivity, "${getString(R.string.oauth_error)}: ${e.message}", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "OAuthActivity"
        private const val CLIENT_ID = "http://hamediabridge.local"
        private const val REDIRECT_URI = "http://hamediabridge.local"

        const val ACTION_START_AUTH = "com.example.hamediabridge.START_AUTH"

        fun startAuth(activity: AppCompatActivity, requestCode: Int) {
            val intent = Intent(activity, OAuthActivity::class.java).apply {
                action = ACTION_START_AUTH
            }
            activity.startActivityForResult(intent, requestCode)
        }
    }
}
