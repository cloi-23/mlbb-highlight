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
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mlbb.highlight.MainActivity
import com.mlbb.highlight.R
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.storage.HighlightRepository
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: ScreenRecorder? = null
    private var recordingDescriptor: ParcelFileDescriptor? = null
    private var recordingDocumentUri: Uri? = null
    private var recordingDisplayName: String? = null
    private var recordingSettings = AppSettings()
    private var isReleasing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture(stopProjection = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
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
                recordingSettings = intent.toRecordingSettings()

                if (recordingSettings.saveLocationUri == null) {
                    sendCaptureStatus(errorMessage = "Choose a gallery save folder in Settings before recording")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundForProjection()
                startProjection(resultCode, resultData)
            }

            ACTION_PREPARE -> {
                if (isReleasing || mediaProjection != null) return START_NOT_STICKY
                recordingSettings = intent.toRecordingSettings()
                if (recordingSettings.saveLocationUri == null) {
                    sendCaptureStatus(errorMessage = "Choose a gallery save folder in Settings before recording")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundForProjection()
                prepareProjection(resultCode, resultData)
            }

            ACTION_START_PREPARED -> {
                val projection = mediaProjection
                if (projection == null || !isProjectionReady) {
                    sendCaptureStatus(errorMessage = "Open the recorder and prepare floating capture first")
                    return START_NOT_STICKY
                }
                if (isReleasing || isCapturing) return START_NOT_STICKY
                startPreparedRecording(projection)
            }

            ACTION_STOP -> {
                releaseCapture(stopProjection = true)
            }

            ACTION_PAUSE_RESUME -> {
                togglePause()
            }

            ACTION_CANCEL_PREPARED -> {
                if (!isCapturing && mediaProjection != null) {
                    releaseCapture(stopProjection = true)
                }
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
        isProjectionReady = true
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

    private fun prepareProjection(resultCode: Int, resultData: Intent) {
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
        if (projection == null) {
            sendCaptureStatus(errorMessage = "Screen-capture permission could not be prepared")
            stopSelf()
            return
        }
        mediaProjection = projection
        isProjectionReady = true
        isCapturing = false
        isPaused = false
        projection.registerCallback(projectionCallback, null)
        sendCaptureStatus()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startPreparedRecording(projection: MediaProjection) {
        isCapturing = true
        isPaused = false
        sendCaptureStatus()
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
        val folderUri = Uri.parse(checkNotNull(recordingSettings.saveLocationUri))
        val repository = HighlightRepository(this)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
        val displayName = "recording_$timestamp.mp4"
        val documentUri = repository.createVideoDocument(folderUri, displayName)
        val descriptor = try {
            contentResolver.openFileDescriptor(documentUri, "rwt")
                ?: throw IOException("Android could not open the selected gallery video")
        } catch (error: IOException) {
            deleteIncompleteVideo(documentUri, error)
            throw error
        } catch (error: SecurityException) {
            deleteIncompleteVideo(documentUri, error)
            throw error
        } catch (error: IllegalArgumentException) {
            deleteIncompleteVideo(documentUri, error)
            throw error
        }
        val recorder = try {
            ScreenRecorder(
                this,
                size.width,
                size.height,
                outputFile = null,
                frameRate = recordingSettings.frameRate,
                audioSource = recordingSettings.audioSource,
                projection = projection,
                outputFileDescriptor = descriptor.fileDescriptor
            )
        } catch (error: RuntimeException) {
            try {
                descriptor.close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
            deleteIncompleteVideo(documentUri, error)
            throw error
        }
        recordingDocumentUri = documentUri
        recordingDescriptor = descriptor
        recordingDisplayName = displayName
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
        isProjectionReady = false
        sendCaptureStatus()

        val display = virtualDisplay
        virtualDisplay = null
        display?.setSurface(null)

        val projection = mediaProjection
        mediaProjection = null
        projection?.unregisterCallback(projectionCallback)

        val recorder = screenRecorder
        screenRecorder = null
        val outputDescriptor = recordingDescriptor
        recordingDescriptor = null
        val outputDocumentUri = recordingDocumentUri
        recordingDocumentUri = null
        val displayName = recordingDisplayName
        recordingDisplayName = null

        Thread {
            var savedUri: Uri? = null
            val statusMessages = mutableListOf<String>()
            if (recorder != null) {
                try {
                    recorder.stop()
                    outputDescriptor?.close()
                    val size = outputDocumentUri?.let { uri ->
                        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    } ?: 0L
                    if (size > 0L) {
                        savedUri = outputDocumentUri
                    } else {
                        statusMessages += "The recording was empty and was not saved"
                    }
                } catch (error: IllegalStateException) {
                    statusMessages += error.message ?: "Recording could not be finalized"
                } catch (error: IOException) {
                    statusMessages += error.message ?: "Recording could not be saved to the selected folder"
                } catch (error: RuntimeException) {
                    statusMessages += error.localizedMessage ?: "Recording could not be finalized"
                } finally {
                    try {
                        outputDescriptor?.close()
                    } catch (error: IOException) {
                        statusMessages += error.message ?: "Could not close the recording file"
                    }
                }
            }
            if (savedUri == null && outputDocumentUri != null) {
                try {
                    if (!DocumentsContract.deleteDocument(contentResolver, outputDocumentUri)) {
                        statusMessages += "Could not remove the incomplete video from the selected folder"
                    }
                } catch (error: IOException) {
                    statusMessages += error.message ?: "Could not remove the incomplete video"
                } catch (error: SecurityException) {
                    statusMessages += "Android denied access to remove the incomplete video"
                } catch (error: IllegalArgumentException) {
                    statusMessages += "The incomplete video could not be found for cleanup"
                }
            }
            display?.release()
            if (stopProjection) {
                projection?.stop()
            }
            if (savedUri != null) {
                showRecordingSavedNotification(
                    savedUri,
                    displayName ?: "Recording saved"
                )
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
                recordingUri = savedUri,
                errorMessage = statusMessages.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    ?: if (savedUri == null && recorder != null) {
                    "Recording could not be saved"
                } else null
            )
            if (shouldStopSelf) {
                stopSelf()
            }
        }.start()
    }

    private fun deleteIncompleteVideo(uri: Uri, originalError: Exception) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (error: IOException) {
            originalError.addSuppressed(error)
        } catch (error: SecurityException) {
            originalError.addSuppressed(error)
        } catch (error: IllegalArgumentException) {
            originalError.addSuppressed(error)
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
        val longEdge = screenWidth.coerceAtLeast(screenHeight)
        val shortEdge = screenWidth.coerceAtMost(screenHeight)
        val scale = (requestedShortEdge.toFloat() / shortEdge).coerceAtMost(1f)
        val landscapeWidth = makeEven((longEdge * scale).toInt().coerceAtLeast(2))
        val landscapeHeight = makeEven((shortEdge * scale).toInt().coerceAtLeast(2))
        return RecordingSize(landscapeWidth, landscapeHeight)
    }

    private fun makeEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }

    private fun sendCaptureStatus(
        recordingUri: android.net.Uri? = null,
        errorMessage: String? = null
    ) {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_CAPTURING, isCapturing)
                .putExtra(EXTRA_IS_PAUSED, isPaused)
                .putExtra(EXTRA_IS_PREPARED, isProjectionReady)
                .putExtra(EXTRA_RECORDING_URI, recordingUri?.toString())
                .putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        )
    }

    private fun showRecordingSavedNotification(uri: Uri, fileName: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("Recording saved")
            .setContentText(fileName)
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

    private fun startForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (recordingSettings.audioSource == RecordingAudioSource.MICROPHONE ||
                        recordingSettings.audioSource == RecordingAudioSource.DEVICE_AND_MICROPHONE)
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
            .setContentText(
                when {
                    isPaused -> "Recording is paused"
                    isCapturing -> "Screen recording is running"
                    isProjectionReady -> "Floating recorder is ready"
                    else -> "Screen recorder is ready"
                }
            )
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
        private const val VIRTUAL_DISPLAY_NAME = "MLBBHighlightCapture"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_RESOLUTION_SHORT_EDGE = "extra_resolution_short_edge"
        private const val EXTRA_FRAME_RATE = "extra_frame_rate"
        private const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        private const val EXTRA_SAVE_LOCATION_URI = "extra_save_location_uri"
        const val EXTRA_IS_CAPTURING = "extra_is_capturing"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_IS_PREPARED = "extra_is_prepared"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_RECORDING_URI = "extra_recording_uri"

        const val ACTION_START = "com.mlbb.highlight.recording.action.START"
        const val ACTION_STOP = "com.mlbb.highlight.recording.action.STOP"
        const val ACTION_PAUSE_RESUME = "com.mlbb.highlight.recording.action.PAUSE_RESUME"
        const val ACTION_PREPARE = "com.mlbb.highlight.recording.action.PREPARE"
        const val ACTION_START_PREPARED = "com.mlbb.highlight.recording.action.START_PREPARED"
        const val ACTION_CANCEL_PREPARED = "com.mlbb.highlight.recording.action.CANCEL_PREPARED"
        const val ACTION_STATUS_CHANGED = "com.mlbb.highlight.recording.action.STATUS_CHANGED"

        @Volatile
        var isCapturing: Boolean = false
            private set
        @Volatile
        var isPaused: Boolean = false
            private set
        @Volatile
        var isProjectionReady: Boolean = false
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
                putExtra(EXTRA_SAVE_LOCATION_URI, settings.saveLocationUri)
            }
        }

        fun prepareIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            settings: AppSettings
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ACTION_PREPARE
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_RESOLUTION_SHORT_EDGE, settings.resolutionShortEdge)
            putExtra(EXTRA_FRAME_RATE, settings.frameRate)
            putExtra(EXTRA_AUDIO_SOURCE, settings.audioSource.name)
            putExtra(EXTRA_SAVE_LOCATION_URI, settings.saveLocationUri)
        }

        fun startPreparedIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START_PREPARED)

        fun cancelPreparedIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_CANCEL_PREPARED)

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

    }

    private data class RecordingSize(
        val width: Int,
        val height: Int
    )

    private fun Intent.toRecordingSettings() = AppSettings(
        resolutionShortEdge = getIntExtra(
            EXTRA_RESOLUTION_SHORT_EDGE,
            AppSettings().resolutionShortEdge
        ),
        frameRate = getIntExtra(EXTRA_FRAME_RATE, AppSettings().frameRate),
        audioSource = getStringExtra(EXTRA_AUDIO_SOURCE)
            ?.let { value -> RecordingAudioSource.entries.firstOrNull { it.name == value } }
            ?: AppSettings().audioSource,
        saveLocationUri = getStringExtra(EXTRA_SAVE_LOCATION_URI)
    )
}
