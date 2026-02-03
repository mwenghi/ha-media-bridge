package com.example.hamediabridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaButtonService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var settingsManager: SettingsManager
    private lateinit var haClient: HomeAssistantClient
    private lateinit var audioManager: AudioManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentDeviceIndex: Int = 0
    private var currentVolume: Int = 50
    private var isPlaying: Boolean = false
    private var volumeProvider: VolumeProviderCompat? = null
    private var lastVolumeEventTime: Long = 0
    private var volumeEventCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        settingsManager = SettingsManager(this)
        haClient = HomeAssistantClient(settingsManager)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        setupMediaSession()
        // Fetch initial device state from Home Assistant
        fetchAndUpdateDeviceState()
        // Start periodic session refresh to prevent stale state
        startPeriodicSessionRefresh()
        // Schedule WorkManager for background persistence (Spotify-like)
        MediaSessionRefreshWorker.schedule(this)
        // Schedule alarm-based restart as backup
        scheduleRestartAlarm()
    }

    private fun startPeriodicSessionRefresh() {
        serviceScope.launch {
            while (true) {
                delay(60000) // Every 60 seconds
                Log.d(TAG, "Periodic session refresh")
                // Only refresh if session might be stale (no recent volume events)
                if (System.currentTimeMillis() - lastVolumeEventTime > 30000) {
                    refreshMediaSession()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, action: ${intent?.action}")

        // Handle media button intent from Bluetooth/external sources
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            Log.d(TAG, "MEDIA_BUTTON intent received! KeyEvent: ${keyEvent?.keyCode}, action: ${keyEvent?.action}")
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_STICKY
        }

        // Handle any other media intents
        if (intent != null) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        // Handle notification button clicks
        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "Play action received")
                mediaSession.controller.transportControls.play()
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "Pause action received")
                mediaSession.controller.transportControls.pause()
            }
            ACTION_NEXT -> {
                Log.d(TAG, "Next action received")
                mediaSession.controller.transportControls.skipToNext()
            }
            ACTION_PREVIOUS -> {
                Log.d(TAG, "Previous action received")
                mediaSession.controller.transportControls.skipToPrevious()
            }
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Request audio focus to receive media button events
        requestAudioFocus()

        // Force the system to recognize us as the active media session
        // by toggling active state and updating playback state
        mediaSession.isActive = false
        mediaSession.isActive = true

        // Update playback state to claim media button routing
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    30000,
                    1f,
                    System.currentTimeMillis()
                )
                .build()
        )

        // Play brief silent audio to claim media button routing
        playSilentAudio()
        Log.d(TAG, "Refreshed media session active state and played silent audio")

        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "onGetRoot from: $clientPackageName")
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren: $parentId")
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        cancelRestartAlarm()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Service onTaskRemoved - scheduling restart")
        // When user swipes app from recents, schedule restart
        if (settingsManager.serviceEnabled && settingsManager.isAuthenticated) {
            scheduleRestartAlarm()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MediaButtonService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MediaButtonService::class.java).setAction(
                if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaButtonService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val device = getCurrentDevice()
        val title = device?.name ?: getString(R.string.notification_title)
        val text = device?.entityId ?: getString(R.string.notification_text)

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(R.drawable.ic_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun setupMediaSession() {
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(this, MediaButtonReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setMediaButtonReceiver(pendingIntent)

            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession onPlay callback")
                    isPlaying = true
                    updatePlaybackState()
                    updateNotification()
                    val device = getCurrentDevice()
                    if (device != null) {
                        fireEvent("device_on") { haClient.fireDeviceOnEvent(device.entityId) }
                    } else {
                        fireEvent("play") { haClient.firePlayEvent() }
                    }
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession onPause callback")
                    isPlaying = false
                    updatePlaybackState()
                    updateNotification()
                    val device = getCurrentDevice()
                    if (device != null) {
                        fireEvent("device_off") { haClient.fireDeviceOffEvent(device.entityId) }
                    } else {
                        fireEvent("pause") { haClient.firePauseEvent() }
                    }
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession onSkipToNext callback")
                    val devices = settingsManager.deviceList
                    if (devices.isNotEmpty()) {
                        currentDeviceIndex = (currentDeviceIndex + 1) % devices.size
                        fetchAndUpdateDeviceState()
                    } else {
                        fireEvent("next") { haClient.fireNextEvent() }
                    }
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession onSkipToPrevious callback")
                    val devices = settingsManager.deviceList
                    if (devices.isNotEmpty()) {
                        currentDeviceIndex = if (currentDeviceIndex > 0) currentDeviceIndex - 1 else devices.size - 1
                        fetchAndUpdateDeviceState()
                    } else {
                        fireEvent("previous") { haClient.firePreviousEvent() }
                    }
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession onStop callback")
                    fireEvent("stop") { haClient.fireStopEvent() }
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    Log.d(TAG, "MediaSession onMediaButtonEvent: ${mediaButtonEvent?.action}")
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    Log.d(TAG, "KeyEvent code: ${keyEvent?.keyCode}, action: ${keyEvent?.action}")

                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                onPlay()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                onPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                onPlay() // or toggle
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                onSkipToNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                onSkipToPrevious()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                onStop()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "HA Media Bridge")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Ready for commands")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Home Assistant")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 360000) // 6 minutes fake duration
                    .build()
            )

            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                    )
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        30000, // Position at 30 seconds
                        1f,
                        System.currentTimeMillis()
                    )
                    .build()
            )

            // Set up volume provider BEFORE activating session
            val service = this@MediaButtonService
            volumeProvider = object : VolumeProviderCompat(
                VOLUME_CONTROL_ABSOLUTE,
                100,  // max volume
                service.currentVolume
            ) {
                override fun onSetVolumeTo(volume: Int) {
                    val now = System.currentTimeMillis()
                    service.volumeEventCount++
                    Log.d(TAG, "VolumeProvider onSetVolumeTo: $volume, count: ${service.volumeEventCount}")
                    service.currentVolume = volume
                    setCurrentVolume(volume)
                    Log.d(TAG, "VolumeProvider currentVolume now: ${getCurrentVolume()}")

                    // Setting volume turns on the device, update play state
                    service.isPlaying = true
                    service.updatePlaybackState()
                    service.updateCurrentDeviceMetadata()
                    service.updateNotification()

                    // Refresh session periodically to prevent stale state
                    if (now - service.lastVolumeEventTime > 30000) {
                        Log.d(TAG, "Refreshing media session after volume gap")
                        service.refreshMediaSession()
                    }
                    service.lastVolumeEventTime = now

                    val device = service.getCurrentDevice()
                    if (device != null) {
                        service.fireEvent("device_volume") { haClient.fireDeviceVolumeEvent(device.entityId, volume) }
                    }
                }

                override fun onAdjustVolume(direction: Int) {
                    val now = System.currentTimeMillis()
                    service.volumeEventCount++
                    Log.d(TAG, "VolumeProvider onAdjustVolume: $direction, current: ${service.currentVolume}, count: ${service.volumeEventCount}")

                    val newVolume = when {
                        direction > 0 -> (service.currentVolume + 5).coerceAtMost(100)
                        direction < 0 -> (service.currentVolume - 5).coerceAtLeast(0)
                        else -> service.currentVolume
                    }

                    // Always update even if value is same (to keep session alive)
                    service.currentVolume = newVolume
                    setCurrentVolume(newVolume)
                    Log.d(TAG, "VolumeProvider set to: $newVolume, getCurrentVolume: ${getCurrentVolume()}")

                    // Setting volume turns on the device, update play state
                    service.isPlaying = true
                    service.updatePlaybackState()
                    service.updateCurrentDeviceMetadata()
                    service.updateNotification()

                    // Refresh session periodically to prevent stale state
                    if (now - service.lastVolumeEventTime > 30000) {
                        Log.d(TAG, "Refreshing media session after volume gap")
                        service.refreshMediaSession()
                    }
                    service.lastVolumeEventTime = now

                    val device = service.getCurrentDevice()
                    if (device != null) {
                        service.fireEvent("device_volume") { haClient.fireDeviceVolumeEvent(device.entityId, newVolume) }
                    }
                }
            }
            setPlaybackToRemote(volumeProvider!!)

            isActive = true
        }

        sessionToken = mediaSession.sessionToken
        Log.d(TAG, "MediaSession setup complete, active: ${mediaSession.isActive}")
    }

    fun refreshDeviceList() {
        fetchAndUpdateDeviceState()
    }

    private fun refreshMediaSession() {
        Log.d(TAG, "Refreshing media session...")
        try {
            // Re-activate the session
            mediaSession.isActive = false
            mediaSession.isActive = true

            // Re-apply the volume provider
            volumeProvider?.let {
                it.setCurrentVolume(currentVolume)
                mediaSession.setPlaybackToRemote(it)
            }

            // Update playback state
            updatePlaybackState()

            // Re-request audio focus
            requestAudioFocus()

            // Play brief silent audio to ensure we have media button routing
            playSilentAudio()

            Log.d(TAG, "Media session refreshed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh media session", e)
        }
    }

    private fun fetchAndUpdateDeviceState() {
        val device = getCurrentDevice()
        if (device == null) {
            updateCurrentDeviceMetadata()
            updateNotification()
            return
        }

        serviceScope.launch {
            val result = haClient.getEntityState(device.entityId)
            if (result.isSuccess) {
                val state = result.getOrNull()!!
                // Update isPlaying based on entity state
                isPlaying = state.state == "on"

                // Update volume/brightness if available
                if (state.brightness != null) {
                    currentVolume = state.brightness
                    volumeProvider?.setCurrentVolume(currentVolume)
                }

                Log.d(TAG, "Device ${device.entityId} state: ${state.state}, brightness: ${state.brightness}, isPlaying: $isPlaying")
            } else {
                Log.e(TAG, "Failed to fetch device state: ${result.exceptionOrNull()?.message}")
            }

            // Update UI on main thread
            updatePlaybackState()
            updateCurrentDeviceMetadata()
            updateNotification()
        }
    }

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Audio focus changed: $focusChange")
                // Re-request focus if lost, to maintain media button priority
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    Log.d(TAG, "Lost audio focus, re-requesting...")
                    audioFocusRequest?.let { req ->
                        audioManager.requestAudioFocus(req)
                    }
                }
            }
            .build()

        audioFocusRequest?.let {
            val result = audioManager.requestAudioFocus(it)
            Log.d(TAG, "Audio focus request result: $result")
        }

        // Also register using legacy API for older Bluetooth devices
        val mediaButtonReceiverComponent = ComponentName(this, MediaButtonReceiver::class.java)
        @Suppress("DEPRECATION")
        audioManager.registerMediaButtonEventReceiver(mediaButtonReceiverComponent)
        Log.d(TAG, "Registered legacy media button receiver")
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        // Unregister legacy media button receiver
        val mediaButtonReceiverComponent = ComponentName(this, MediaButtonReceiver::class.java)
        @Suppress("DEPRECATION")
        audioManager.unregisterMediaButtonEventReceiver(mediaButtonReceiverComponent)
    }

    private fun scheduleRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, RestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            RESTART_ALARM_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Schedule alarm to restart service in 1 minute if it dies
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60000,
            pendingIntent
        )
        Log.d(TAG, "Restart alarm scheduled")
    }

    private fun cancelRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, RestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            RESTART_ALARM_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            Log.d(TAG, "Restart alarm cancelled")
        }
    }

    private fun playSilentAudio() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(mediaSession.controller.playbackState?.let {
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    } ?: AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build()

                // Play 100ms of silence
                val silentBuffer = ShortArray(sampleRate / 10) // 100ms of silence
                audioTrack.play()
                audioTrack.write(silentBuffer, 0, silentBuffer.size)
                delay(150)
                audioTrack.stop()
                audioTrack.release()
                Log.d(TAG, "Silent audio played successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play silent audio", e)
            }
        }
    }

    private fun fireEvent(eventName: String, eventAction: suspend () -> kotlin.Result<Unit>) {
        Log.d(TAG, "Firing event: $eventName")
        serviceScope.launch {
            val result = eventAction()

            Log.d(TAG, "Event $eventName result: ${if (result.isSuccess) "success" else "failed: ${result.exceptionOrNull()?.message}"}")

            val broadcastIntent = Intent(ACTION_MEDIA_EVENT).apply {
                putExtra(EXTRA_EVENT_NAME, eventName)
                putExtra(EXTRA_EVENT_SUCCESS, result.isSuccess)
                result.exceptionOrNull()?.let { error ->
                    putExtra(EXTRA_EVENT_ERROR, error.message)
                }
            }
            LocalBroadcastManager.getInstance(this@MediaButtonService)
                .sendBroadcast(broadcastIntent)
        }
    }

    private fun getCurrentDevice(): Device? {
        val devices = settingsManager.deviceList
        return if (devices.isNotEmpty() && currentDeviceIndex < devices.size) {
            devices[currentDeviceIndex]
        } else {
            null
        }
    }

    private fun updateCurrentDeviceMetadata() {
        val devices = settingsManager.deviceList
        if (devices.isEmpty()) {
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "HA Media Bridge")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "No devices configured")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Add devices in app")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 360000)
                    .build()
            )
            return
        }

        if (currentDeviceIndex >= devices.size) {
            currentDeviceIndex = 0
        }

        val device = devices[currentDeviceIndex]
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, device.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Vol: $currentVolume%")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "${currentDeviceIndex + 1}/${devices.size} · ${device.entityId}")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 360000)
                .build()
        )
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, 30000, 1f, System.currentTimeMillis())
                .build()
        )
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    companion object {
        private const val TAG = "MediaButtonService"
        private const val CHANNEL_ID = "media_button_service"
        private const val NOTIFICATION_ID = 1
        private const val MEDIA_ROOT_ID = "ha_media_bridge_root"
        private const val RESTART_ALARM_REQUEST_CODE = 12345

        const val ACTION_PLAY = "com.example.hamediabridge.PLAY"
        const val ACTION_PAUSE = "com.example.hamediabridge.PAUSE"
        const val ACTION_NEXT = "com.example.hamediabridge.NEXT"
        const val ACTION_PREVIOUS = "com.example.hamediabridge.PREVIOUS"

        const val ACTION_MEDIA_EVENT = "com.example.hamediabridge.MEDIA_EVENT"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_EVENT_SUCCESS = "event_success"
        const val EXTRA_EVENT_ERROR = "event_error"

        fun start(context: Context) {
            val intent = Intent(context, MediaButtonService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaButtonService::class.java)
            context.stopService(intent)
        }
    }
}
