package com.example.hamediabridge

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
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
    private lateinit var deviceListView: ListView
    private lateinit var noDevicesText: TextView
    private lateinit var addDeviceButton: Button
    private lateinit var syncDevicesButton: Button
    private lateinit var syncStatusText: TextView
    private lateinit var deviceListAdapter: DeviceListAdapter

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
        checkAndRequestPermissions()

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
        deviceListView = findViewById(R.id.deviceListView)
        noDevicesText = findViewById(R.id.noDevicesText)
        addDeviceButton = findViewById(R.id.addDeviceButton)
        syncDevicesButton = findViewById(R.id.syncDevicesButton)
        syncStatusText = findViewById(R.id.syncStatusText)

        deviceListAdapter = DeviceListAdapter()
        deviceListView.adapter = deviceListAdapter
    }

    private fun loadSettings() {
        urlEditText.setText(settingsManager.homeAssistantUrl)
        tokenEditText.setText(settingsManager.accessToken ?: "")
        serviceSwitch.isChecked = settingsManager.serviceEnabled && isServiceRunning()
        updateDeviceList()
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

        addDeviceButton.setOnClickListener {
            showAddDeviceDialog()
        }

        syncDevicesButton.setOnClickListener {
            syncWithHomeAssistant()
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = mutableListOf<String>()

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request runtime permissions if needed
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                RC_PERMISSIONS
            )
        } else {
            // Check battery optimization after runtime permissions
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    requestBatteryOptimizationExemption()
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to battery optimization settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    R.string.battery_settings_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_PERMISSIONS) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isNotEmpty()) {
                if (deniedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(
                        this,
                        R.string.notification_permission_required,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Check battery optimization after handling permissions
            checkBatteryOptimization()
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

    private fun updateDeviceList() {
        val devices = settingsManager.deviceList
        deviceListAdapter.updateDevices(devices)

        if (devices.isEmpty()) {
            noDevicesText.visibility = View.VISIBLE
            deviceListView.visibility = View.GONE
        } else {
            noDevicesText.visibility = View.GONE
            deviceListView.visibility = View.VISIBLE
            setListViewHeightBasedOnChildren(deviceListView)
        }
    }

    private fun setListViewHeightBasedOnChildren(listView: ListView) {
        val adapter = listView.adapter ?: return

        var totalHeight = 0
        for (i in 0 until adapter.count) {
            val listItem = adapter.getView(i, null, listView)
            listItem.measure(
                View.MeasureSpec.makeMeasureSpec(listView.width, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            totalHeight += listItem.measuredHeight
        }

        val params = listView.layoutParams
        params.height = totalHeight + (listView.dividerHeight * (adapter.count - 1))
        listView.layoutParams = params
        listView.requestLayout()
    }

    private fun showAddDeviceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null)
        val deviceNameEditText = dialogView.findViewById<EditText>(R.id.deviceNameEditText)
        val entityIdEditText = dialogView.findViewById<EditText>(R.id.entityIdEditText)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_device_title)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = deviceNameEditText.text.toString().trim()
                val entityId = entityIdEditText.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.device_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (entityId.isEmpty()) {
                    Toast.makeText(this, R.string.entity_id_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val devices = settingsManager.deviceList.toMutableList()
                devices.add(Device(name, entityId))
                settingsManager.deviceList = devices
                updateDeviceList()
                pushDeviceListToHA()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDevice(position: Int) {
        val devices = settingsManager.deviceList.toMutableList()
        if (position < devices.size) {
            devices.removeAt(position)
            settingsManager.deviceList = devices
            updateDeviceList()
            pushDeviceListToHA()
        }
    }

    private fun syncWithHomeAssistant() {
        if (!settingsManager.isAuthenticated) {
            Toast.makeText(this, "Please save settings first", Toast.LENGTH_SHORT).show()
            return
        }

        syncStatusText.text = getString(R.string.syncing)
        syncDevicesButton.isEnabled = false

        scope.launch {
            val result = haClient.getDeviceList()
            if (result.isSuccess) {
                val devices = result.getOrNull() ?: emptyList()
                if (devices.isNotEmpty()) {
                    settingsManager.deviceList = devices
                    updateDeviceList()
                    syncStatusText.text = getString(R.string.sync_success, devices.size)
                } else {
                    // No devices in HA, push local list to HA
                    val localDevices = settingsManager.deviceList
                    if (localDevices.isNotEmpty()) {
                        val pushResult = haClient.setDeviceList(localDevices)
                        if (pushResult.isSuccess) {
                            syncStatusText.text = getString(R.string.sync_pushed)
                        } else {
                            syncStatusText.text = getString(R.string.sync_no_entity)
                        }
                    } else {
                        syncStatusText.text = getString(R.string.sync_no_entity)
                    }
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                syncStatusText.text = getString(R.string.sync_failed, error)
            }
            syncDevicesButton.isEnabled = true
        }
    }

    private fun pushDeviceListToHA() {
        if (!settingsManager.isAuthenticated) return

        scope.launch {
            val devices = settingsManager.deviceList
            haClient.setDeviceList(devices)
        }
    }

    private inner class DeviceListAdapter : BaseAdapter() {
        private var devices: List<Device> = emptyList()

        fun updateDevices(newDevices: List<Device>) {
            devices = newDevices
            notifyDataSetChanged()
        }

        override fun getCount(): Int = devices.size

        override fun getItem(position: Int): Device = devices[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_device, parent, false)

            val device = devices[position]
            view.findViewById<TextView>(R.id.deviceNameText).text = device.name
            view.findViewById<TextView>(R.id.deviceEntityIdText).text = device.entityId
            view.findViewById<ImageButton>(R.id.deleteButton).setOnClickListener {
                deleteDevice(position)
            }

            return view
        }
    }

    companion object {
        private const val RC_PERMISSIONS = 101
    }
}
