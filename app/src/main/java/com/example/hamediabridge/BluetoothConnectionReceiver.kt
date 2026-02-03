package com.example.hamediabridge

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Receives Bluetooth audio device connection events and wakes up the MediaButtonService.
 * This is how Spotify intercepts media buttons even when "off" - it listens for
 * Bluetooth device connections and immediately claims the media session.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.d(TAG, "Received action: $action")

        val settingsManager = SettingsManager(context)
        if (!settingsManager.serviceEnabled || !settingsManager.isAuthenticated) {
            Log.d(TAG, "Service not enabled or not authenticated, ignoring")
            return
        }

        when (action) {
            // Bluetooth A2DP (audio streaming) connection state changed
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Log.d(TAG, "A2DP state changed: $state, device: ${device?.name}")

                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Bluetooth A2DP connected, starting service")
                    MediaButtonService.start(context)
                }
            }

            // Bluetooth headset (HFP) connection state changed
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Log.d(TAG, "Headset state changed: $state, device: ${device?.name}")

                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Bluetooth headset connected, starting service")
                    MediaButtonService.start(context)
                }
            }

            // Audio becoming noisy (headphones unplugged) - wake up to potentially claim route
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                Log.d(TAG, "Audio becoming noisy, refreshing service")
                MediaButtonService.start(context)
            }

            // SCO audio state changed (call audio)
            AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                Log.d(TAG, "SCO audio state: $state")
                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    // After a call ends, reclaim media session
                    Log.d(TAG, "SCO disconnected, reclaiming media session")
                    MediaButtonService.start(context)
                }
            }

            // Generic Bluetooth device connected (catches car Bluetooth etc.)
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Log.d(TAG, "Bluetooth ACL connected: ${device?.name}")
                // Start service to claim media button routing
                MediaButtonService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothConnReceiver"
    }
}
