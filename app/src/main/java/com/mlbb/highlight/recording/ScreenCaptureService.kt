package com.mlbb.highlight.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mlbb.highlight.MainActivity
import com.mlbb.highlight.R
import com.mlbb.highlight.highlight.ClipBuilder
import com.mlbb.highlight.highlight.ManualHighlightService
import java.io.File

class ScreenCaptureService : Service() {
    private lateinit var segmentDirectory: File
    private lateinit var replayBuffer: ReplayBuffer
    private lateinit var segmentManager: SegmentManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: ScreenRecorder? = null
    private var isReleasing = false
    private var recordingSize: RecordingSize? = null
    private val segmentRotationHandler = Handler(Looper.getMainLooper())
    private var segmentRotationRunnable: Runnable? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture(stopProjection = false, shouldStopSelf = false)
            isCapturing = false
            sendCaptureStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        segmentDirectory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Segments")
        replayBuffer = ReplayBuffer(maxDurationMs = 30_000L, segmentDurationMs = 5_000L)
        segmentManager = SegmentManager(
            baseDirectory = segmentDirectory,
            segmentDurationMs = 5_000L,
            replayBuffer = replayBuffer
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundForProjection()
                startProjection(resultCode, resultData)
            }

            ACTION_STOP -> {
                releaseCapture(stopProjection = true)
                stopSelf()
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
        sendCaptureStatus()
        projection.registerCallback(projectionCallback, null)
        createVirtualDisplay(projection)
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
        val size = calculateRecordingSize(width, height)
        recordingSize = size

        startSegmentCapture(projection, size, densityDpi)
        scheduleSegmentRotation()
    }

    private fun startSegmentCapture(projection: MediaProjection, size: RecordingSize, densityDpi: Int) {
        val previousDisplay = virtualDisplay
        if (previousDisplay != null) {
            previousDisplay.release()
        }

        val previousRecorder = screenRecorder
        if (previousRecorder != null) {
            saveFinalRecordingSegment(previousRecorder)
        }

        segmentDirectory.mkdirs()
        val segmentFile = segmentManager.createSegmentFile()
        val recorder = ScreenRecorder(this, size.width, size.height, segmentFile)
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

    private fun scheduleSegmentRotation() {
        segmentRotationRunnable?.let { segmentRotationHandler.removeCallbacks(it) }
        segmentRotationRunnable = object : Runnable {
            override fun run() {
                if (!isCapturing || mediaProjection == null) return
                val projection = mediaProjection ?: return
                val size = recordingSize ?: return
                val densityDpi = resources.displayMetrics.densityDpi
                startSegmentCapture(projection, size, densityDpi)
                scheduleSegmentRotation()
            }
        }
        segmentRotationHandler.postDelayed(segmentRotationRunnable!!, 5_000L)
    }

    private fun releaseCapture(stopProjection: Boolean, shouldStopSelf: Boolean = true) {
        if (isReleasing) return
        isReleasing = true

        segmentRotationRunnable?.let { segmentRotationHandler.removeCallbacks(it) }
        segmentRotationRunnable = null

        isCapturing = false
        sendCaptureStatus()

        virtualDisplay?.release()
        virtualDisplay = null

        val projection = mediaProjection
        mediaProjection = null
        projection?.unregisterCallback(projectionCallback)
        if (stopProjection) {
            projection?.stop()
        }

        val recorder = screenRecorder
        screenRecorder = null

        Thread {
            if (recorder != null) {
                saveFinalRecordingSegment(recorder)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            isReleasing = false
            if (shouldStopSelf) {
                stopSelf()
            }
        }.start()
    }

    private fun saveFinalRecordingSegment(recorder: ScreenRecorder?) {
        val recordingFile = recorder?.stop() ?: return
        if (!recordingFile.exists() || recordingFile.length() <= 0L) return

        segmentManager.registerSegment(recordingFile)
        val recordingUri = publishRecording(recordingFile)
        sendCaptureStatus(recordingFile, recordingUri)
        if (recordingUri != null) {
            showRecordingSavedNotification(recordingUri, recordingFile.name)
        }
    }

    private fun calculateRecordingSize(screenWidth: Int, screenHeight: Int): RecordingSize {
        val longEdge = screenWidth.coerceAtLeast(screenHeight)
        val scale = (MAX_RECORDING_LONG_EDGE.toFloat() / longEdge).coerceAtMost(1f)
        val width = makeEven((screenWidth * scale).toInt().coerceAtLeast(2))
        val height = makeEven((screenHeight * scale).toInt().coerceAtLeast(2))
        return RecordingSize(width, height)
    }

    private fun makeEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }

    private fun publishRecording(recordingFile: File): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, recordingFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIRECTORY")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val recordingUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return runCatching {
            resolver.openOutputStream(recordingUri)?.use { output ->
                recordingFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open published recording")

            resolver.update(
                recordingUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            recordingUri
        }.getOrElse {
            resolver.delete(recordingUri, null, null)
            null
        }
    }

    private fun showRecordingSavedNotification(recordingUri: android.net.Uri, recordingName: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(recordingUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("Recording saved")
            .setContentText(recordingName)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    viewIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(RECORDING_SAVED_NOTIFICATION_ID, notification)
    }

    private fun sendCaptureStatus(recordingFile: File? = null, recordingUri: android.net.Uri? = null) {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_CAPTURING, isCapturing)
                .putExtra(EXTRA_RECORDING_URI, recordingUri?.toString())
                .putExtra(EXTRA_RECORDING_PATH, recordingFile?.absolutePath)
        )
    }

    private fun startForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("MLBB Highlight")
            .setContentText("Screen capture is running")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
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
        private const val RECORDING_SAVED_NOTIFICATION_ID = 1002
        private const val VIRTUAL_DISPLAY_NAME = "MLBBHighlightCapture"
        private const val MAX_RECORDING_LONG_EDGE = 1280
        private const val OUTPUT_DIRECTORY = "MLBBHighlight"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_IS_CAPTURING = "extra_is_capturing"
        const val EXTRA_RECORDING_URI = "extra_recording_uri"
        const val EXTRA_RECORDING_PATH = "extra_recording_path"

        const val ACTION_START = "com.mlbb.highlight.recording.action.START"
        const val ACTION_STOP = "com.mlbb.highlight.recording.action.STOP"
        const val ACTION_STATUS_CHANGED = "com.mlbb.highlight.recording.action.STATUS_CHANGED"

        @Volatile
        var isCapturing: Boolean = false
            private set

        fun setCapturing(value: Boolean) {
            isCapturing = value
        }

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun createManualHighlight(context: Context): File? {
            val segmentDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Segments")
            val highlightDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Highlights")
            if (!segmentDir.exists()) return null

            val segments = segmentDir.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
                ?.sortedBy { it.lastModified() }
                ?.map { file -> SegmentFile(file = file, createdAtMs = file.lastModified(), durationMs = 5_000L) }
                ?: return null

            if (segments.isEmpty()) return null

            val outputFile = ClipBuilder(highlightDir).buildFromSegments(
                segments,
                "highlight_${System.currentTimeMillis()}.mp4"
            )
            return outputFile
        }
    }

    private data class RecordingSize(
        val width: Int,
        val height: Int
    )
}
