package com.mlbb.highlight.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mlbb.highlight.MainActivity
import com.mlbb.highlight.R
import com.mlbb.highlight.recording.VoiceCommandActivity
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.storage.HighlightRepository
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    private lateinit var recordingDirectory: File
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: ScreenRecorder? = null
    private var recordingSettings = AppSettings()
    private var isReleasing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture(stopProjection = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        recordingDirectory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings")
        recordingDirectory.mkdirs()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isReleasing) {
                    sendCaptureStatus(errorMessage = "Wait for the current recording to finish saving")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                recordingSettings = AppSettings(
                    resolutionShortEdge = intent.getIntExtra(
                        EXTRA_RESOLUTION_SHORT_EDGE,
                        AppSettings().resolutionShortEdge
                    ),
                    frameRate = intent.getIntExtra(EXTRA_FRAME_RATE, AppSettings().frameRate),
                    audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                        ?.let { value -> RecordingAudioSource.entries.firstOrNull { it.name == value } }
                        ?: AppSettings().audioSource,
                    voiceCommandsEnabled = intent.getBooleanExtra(EXTRA_VOICE_COMMANDS_ENABLED, false),
                    saveLocationUri = intent.getStringExtra(EXTRA_SAVE_LOCATION_URI),
                    autoSave = intent.getBooleanExtra(EXTRA_AUTO_SAVE, true)
                )

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundForProjection()
                startProjection(resultCode, resultData)
            }

            ACTION_STOP -> {
                releaseCapture(stopProjection = true)
            }

            ACTION_PAUSE_RESUME -> {
                togglePause()
            }

            ACTION_VOICE_COMMAND -> {
                handleVoiceCommand(intent.getStringExtra(EXTRA_VOICE_COMMAND))
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!isReleasing) {
            releaseCapture(stopProjection = false, shouldStopSelf = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            stopSelf()
            return
        }

        mediaProjection = projection
        isCapturing = true
        isPaused = false
        sendCaptureStatus()
        projection.registerCallback(projectionCallback, null)
        try {
            createVirtualDisplay(projection)
        } catch (error: Exception) {
            releaseCapture(stopProjection = true)
            sendCaptureStatus(errorMessage = error.message ?: "Could not start recording with these settings")
        }
    }

    private fun createVirtualDisplay(projection: MediaProjection) {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect().also { getSystemService(WindowManager::class.java).defaultDisplay.getRectSize(it) }
        }

        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val densityDpi = resources.displayMetrics.densityDpi
        val size = calculateRecordingSize(width, height, recordingSettings.resolutionShortEdge)
        startSegmentCapture(projection, size, densityDpi)
    }

    private fun startSegmentCapture(projection: MediaProjection, size: RecordingSize, densityDpi: Int) {
        recordingDirectory.mkdirs()
        val recorder = ScreenRecorder(
            this,
            size.width,
            size.height,
            createRecordingFile(),
            recordingSettings.frameRate,
            recordingSettings.audioSource,
            projection
        )
        screenRecorder = recorder

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            size.width,
            size.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.inputSurface,
            null,
            null
        )
    }

    private fun releaseCapture(stopProjection: Boolean, shouldStopSelf: Boolean = true) {
        if (isReleasing) return
        isReleasing = true

        isCapturing = false
        isPaused = false
        sendCaptureStatus()

        val display = virtualDisplay
        virtualDisplay = null
        display?.setSurface(null)

        val projection = mediaProjection
        mediaProjection = null
        projection?.unregisterCallback(projectionCallback)

        val recorder = screenRecorder
        screenRecorder = null

        Thread {
            var savedFile: File? = null
            val statusMessages = mutableListOf<String>()
            if (recorder != null) {
                try {
                    savedFile = saveRecording(recorder)
                } catch (error: IllegalStateException) {
                    statusMessages += error.message ?: "Recording could not be finalized"
                }
            }
            display?.release()
            if (stopProjection) {
                projection?.stop()
            }
            if (savedFile != null) {
                if (recordingSettings.autoSave && recordingSettings.saveLocationUri != null) {
                    try {
                        HighlightRepository(this).copyToFolderTree(
                            savedFile,
                            Uri.parse(recordingSettings.saveLocationUri)
                        )
                    } catch (error: IOException) {
                        statusMessages +=
                            "Recording saved in My Videos, but copying to the selected folder failed: " +
                                (error.localizedMessage ?: "storage error")
                    } catch (error: SecurityException) {
                        statusMessages +=
                            "Recording saved in My Videos, but Android denied access to the selected folder"
                    } catch (error: IllegalArgumentException) {
                        statusMessages +=
                            "Recording saved in My Videos, but the selected folder is no longer available"
                    }
                }
                showRecordingSavedNotification(savedFile)
            }
            if (
                recorder != null &&
                recordingSettings.audioSource != RecordingAudioSource.SILENT &&
                !recorder.audioInputDetected
            ) {
                statusMessages +=
                    "No audio signal was detected. Android/game may block game-audio capture; try Microphone."
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            isReleasing = false
            sendCaptureStatus(
                recordingFile = savedFile,
                errorMessage = statusMessages.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    ?: if (savedFile == null && recorder != null) {
                    "Recording could not be saved"
                } else null
            )
            if (shouldStopSelf) {
                stopSelf()
            }
        }.start()
    }

    private fun saveRecording(recorder: ScreenRecorder): File? {
        val output = recorder.stop()
        return output.takeIf { it.isFile && it.length() > 0L }
    }

    private fun launchVoiceCommand() {
        if (!recordingSettings.voiceCommandsEnabled) return
        startActivity(
            Intent(this, VoiceCommandActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun handleVoiceCommand(command: String?) {
        when (command?.trim()?.lowercase(Locale.ROOT)) {
            "pause", "pause recording", "pause the recording" -> if (!isPaused) togglePause()
            "resume", "resume recording", "continue recording" -> if (isPaused) togglePause()
            "stop", "stop recording", "finish recording" -> releaseCapture(stopProjection = true)
            else -> sendCaptureStatus(errorMessage = "Command not recognized. Say Pause, Resume, or Stop.")
        }
    }

    private fun togglePause() {
        if (!isCapturing || isReleasing) return
        val recorder = screenRecorder ?: return
        val display = virtualDisplay ?: return

        if (isPaused) {
            recorder.resume()
            display.setSurface(recorder.inputSurface)
            isPaused = false
        } else {
            recorder.pause()
            display.setSurface(null)
            isPaused = true
        }

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        sendCaptureStatus()
    }

    private fun calculateRecordingSize(
        screenWidth: Int,
        screenHeight: Int,
        requestedShortEdge: Int
    ): RecordingSize {
        val shortEdge = screenWidth.coerceAtMost(screenHeight)
        val scale = (requestedShortEdge.toFloat() / shortEdge).coerceAtMost(1f)
        val width = makeEven((screenWidth * scale).toInt().coerceAtLeast(2))
        val height = makeEven((screenHeight * scale).toInt().coerceAtLeast(2))
        return RecordingSize(width, height)
    }

    private fun makeEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }

    private fun sendCaptureStatus(
        recordingFile: File? = null,
        recordingUri: android.net.Uri? = null,
        errorMessage: String? = null
    ) {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_CAPTURING, isCapturing)
                .putExtra(EXTRA_IS_PAUSED, isPaused)
                .putExtra(EXTRA_RECORDING_URI, recordingUri?.toString())
                .putExtra(EXTRA_RECORDING_PATH, recordingFile?.absolutePath)
                .putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        )
    }

    private fun showRecordingSavedNotification(recordingFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            recordingFile
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("Recording saved")
            .setContentText(recordingFile.name)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    3,
                    viewIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(RECORDING_SAVED_NOTIFICATION_ID, notification)
    }

    private fun createRecordingFile(): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
        return File(recordingDirectory, "recording_$timestamp.mp4")
    }

    private fun startForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (recordingSettings.audioSource == RecordingAudioSource.MICROPHONE ||
                        recordingSettings.audioSource == RecordingAudioSource.DEVICE_AND_MICROPHONE ||
                        recordingSettings.voiceCommandsEnabled)
                ) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                serviceTypes
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("MLBB Gameplay Recorder")
            .setContentText(if (isPaused) "Recording is paused" else "Screen recording is running")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                if (isPaused) "Resume" else "Pause",
                PendingIntent.getService(
                    this,
                    PAUSE_ACTION_REQUEST_CODE,
                    pauseResumeIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    STOP_ACTION_REQUEST_CODE,
                    stopIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .apply {
                if (recordingSettings.voiceCommandsEnabled) {
                    addAction(
                        0,
                        "Voice",
                        PendingIntent.getActivity(
                            this@ScreenCaptureService,
                            VOICE_ACTION_REQUEST_CODE,
                            Intent(this@ScreenCaptureService, VoiceCommandActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val RECORDING_SAVED_NOTIFICATION_ID = 1003
        private const val PAUSE_ACTION_REQUEST_CODE = 1004
        private const val STOP_ACTION_REQUEST_CODE = 1005
        private const val VOICE_ACTION_REQUEST_CODE = 1006
        private const val VIRTUAL_DISPLAY_NAME = "MLBBHighlightCapture"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_RESOLUTION_SHORT_EDGE = "extra_resolution_short_edge"
        private const val EXTRA_FRAME_RATE = "extra_frame_rate"
        private const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        private const val EXTRA_VOICE_COMMANDS_ENABLED = "extra_voice_commands_enabled"
        private const val EXTRA_SAVE_LOCATION_URI = "extra_save_location_uri"
        private const val EXTRA_AUTO_SAVE = "extra_auto_save"
        const val EXTRA_VOICE_COMMAND = "extra_voice_command"
        const val EXTRA_IS_CAPTURING = "extra_is_capturing"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_RECORDING_URI = "extra_recording_uri"
        const val EXTRA_RECORDING_PATH = "extra_recording_path"

        const val ACTION_START = "com.mlbb.highlight.recording.action.START"
        const val ACTION_STOP = "com.mlbb.highlight.recording.action.STOP"
        const val ACTION_PAUSE_RESUME = "com.mlbb.highlight.recording.action.PAUSE_RESUME"
        const val ACTION_VOICE_COMMAND = "com.mlbb.highlight.recording.action.VOICE_COMMAND"
        const val ACTION_STATUS_CHANGED = "com.mlbb.highlight.recording.action.STATUS_CHANGED"

        @Volatile
        var isCapturing: Boolean = false
            private set
        @Volatile
        var isPaused: Boolean = false
            private set

        fun setCapturing(value: Boolean) {
            isCapturing = value
        }

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            settings: AppSettings
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_RESOLUTION_SHORT_EDGE, settings.resolutionShortEdge)
                putExtra(EXTRA_FRAME_RATE, settings.frameRate)
                putExtra(EXTRA_AUDIO_SOURCE, settings.audioSource.name)
                putExtra(EXTRA_VOICE_COMMANDS_ENABLED, settings.voiceCommandsEnabled)
                putExtra(EXTRA_SAVE_LOCATION_URI, settings.saveLocationUri)
                putExtra(EXTRA_AUTO_SAVE, settings.autoSave)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun pauseResumeIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_PAUSE_RESUME
            }
        }

        fun voiceCommandIntent(context: Context, command: String): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_VOICE_COMMAND
                putExtra(EXTRA_VOICE_COMMAND, command)
            }
        }
    }

    private data class RecordingSize(
        val width: Int,
        val height: Int
    )
}
