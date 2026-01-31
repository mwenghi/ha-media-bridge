package com.example.hamediabridge

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var haClient: HomeAssistantClient
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var urlEditText: EditText
    private lateinit var tokenEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var authStatusText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var serviceStatusText: TextView
    private lateinit var testEventButton: Button
    private lateinit var lastEventText: TextView

    private val mediaEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val eventName = intent.getStringExtra(MediaButtonService.EXTRA_EVENT_NAME)
            val success = intent.getBooleanExtra(MediaButtonService.EXTRA_EVENT_SUCCESS, false)
            val error = intent.getStringExtra(MediaButtonService.EXTRA_EVENT_ERROR)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val status = if (success) "OK" else "ERR: $error"
            lastEventText.text = "Last event: $eventName at $timestamp $status"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        haClient = HomeAssistantClient(settingsManager)

        initViews()
        loadSettings()
        setupListeners()
        requestNotificationPermission()

        // Auto-start service if it was enabled
        if (settingsManager.serviceEnabled && settingsManager.isAuthenticated) {
            if (!isServiceRunning()) {
                MediaButtonService.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthStatus()
        updateServiceStatus()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            mediaEventReceiver,
            IntentFilter(MediaButtonService.ACTION_MEDIA_EVENT)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediaEventReceiver)
    }

    private fun initViews() {
        urlEditText = findViewById(R.id.urlEditText)
        tokenEditText = findViewById(R.id.tokenEditText)
        saveButton = findViewById(R.id.saveButton)
        authStatusText = findViewById(R.id.authStatusText)
        serviceSwitch = findViewById(R.id.serviceSwitch)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        testEventButton = findViewById(R.id.testEventButton)
        lastEventText = findViewById(R.id.lastEventText)
    }

    private fun loadSettings() {
        urlEditText.setText(settingsManager.homeAssistantUrl)
        tokenEditText.setText(settingsManager.accessToken ?: "")
        serviceSwitch.isChecked = settingsManager.serviceEnabled && isServiceRunning()
    }

    private fun setupListeners() {
        saveButton.setOnClickListener {
            val url = urlEditText.text.toString().trim()
            val token = tokenEditText.text.toString().trim()

            if (url.isEmpty()) {
                Toast.makeText(this, R.string.url_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (token.isEmpty()) {
                Toast.makeText(this, "Please enter access token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            settingsManager.homeAssistantUrl = url
            settingsManager.accessToken = token

            // Test connection
            scope.launch {
                authStatusText.text = "Testing connection..."
                val result = haClient.testConnection()
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "Connected successfully!", Toast.LENGTH_SHORT).show()
                    updateAuthStatus()
                } else {
                    Toast.makeText(this@MainActivity, "Connection failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    updateAuthStatus()
                }
            }
        }

        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!settingsManager.isAuthenticated) {
                    Toast.makeText(this, "Please save settings first", Toast.LENGTH_SHORT).show()
                    serviceSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                startMediaService()
            } else {
                stopMediaService()
            }
        }

        testEventButton.setOnClickListener {
            if (!settingsManager.isAuthenticated) {
                Toast.makeText(this, "Please save settings first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scope.launch {
                lastEventText.text = "Firing test event..."
                val result = haClient.fireEvent("android_media_test", mapOf("test" to true))
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                if (result.isSuccess) {
                    lastEventText.text = "Last event: test at $timestamp OK"
                    Toast.makeText(this@MainActivity, "Event fired successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    lastEventText.text = "Last event: test at $timestamp ERR: ${result.exceptionOrNull()?.message}"
                    Toast.makeText(this@MainActivity, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    RC_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Notification permission required for service",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (MediaButtonService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateAuthStatus() {
        if (settingsManager.isAuthenticated) {
            authStatusText.text = getString(R.string.status_connected)
            authStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            authStatusText.text = getString(R.string.status_disconnected)
            authStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun updateServiceStatus() {
        val running = isServiceRunning()
        serviceSwitch.isChecked = running

        if (running) {
            serviceStatusText.text = getString(R.string.status_service_running)
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            serviceStatusText.text = getString(R.string.status_service_stopped)
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun startMediaService() {
        settingsManager.serviceEnabled = true
        MediaButtonService.start(this)
        updateServiceStatus()
    }

    private fun stopMediaService() {
        settingsManager.serviceEnabled = false
        MediaButtonService.stop(this)
        updateServiceStatus()
    }

    companion object {
        private const val RC_NOTIFICATION_PERMISSION = 101
    }
}
