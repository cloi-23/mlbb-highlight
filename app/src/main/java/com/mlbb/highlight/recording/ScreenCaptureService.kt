package com.mlbb.highlight.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mlbb.highlight.MainActivity
import com.mlbb.highlight.R
import com.mlbb.highlight.highlight.ClipBuilder
import com.mlbb.highlight.highlight.HighlightRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private lateinit var segmentDirectory: File
    private lateinit var highlightDirectory: File
    private lateinit var replayBuffer: ReplayBuffer
    private lateinit var segmentManager: SegmentManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: ScreenRecorder? = null
    private var currentSegmentStartedAtMs: Long = 0L
    private var isReleasing = false
    private var recordingSize: RecordingSize? = null
    private val segmentRotationHandler = Handler(Looper.getMainLooper())
    private var segmentRotationRunnable: Runnable? = null
    private val highlightInProgress = AtomicBoolean(false)
    private val protectedSegmentPaths = mutableSetOf<String>()

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
        highlightDirectory = File(getExternalFilesDir(null), "Highlights")
        replayBuffer = ReplayBuffer(
            maxDurationMs = REPLAY_BUFFER_DURATION_MS,
            segmentDurationMs = SEGMENT_DURATION_MS,
            onSegmentRemoved = { segment ->
                if (segment.file.exists() && !isSegmentProtected(segment.file)) {
                    segment.file.delete()
                }
            }
        )
        segmentManager = SegmentManager(
            baseDirectory = segmentDirectory,
            segmentDurationMs = SEGMENT_DURATION_MS,
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

            ACTION_MANUAL_HIGHLIGHT -> {
                requestManualHighlight()
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
        currentSegmentStartedAtMs = System.currentTimeMillis()
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
        // Android 14+ does not allow repeatedly creating VirtualDisplay instances from
        // the same MediaProjection consent token. Keep this disabled until segmenting is
        // moved behind a single stable capture surface.
        segmentRotationRunnable?.let { segmentRotationHandler.removeCallbacks(it) }
        segmentRotationRunnable = null
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
                saveFinalRecordingSegment(recorder, currentSegmentStartedAtMs)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            isReleasing = false
            if (shouldStopSelf) {
                stopSelf()
            }
        }.start()
    }

    private fun saveFinalRecordingSegment(recorder: ScreenRecorder?, startedAtMs: Long = currentSegmentStartedAtMs) {
        val recordingFile = recorder?.stop() ?: return
        if (!recordingFile.exists() || recordingFile.length() <= 0L) return

        segmentManager.registerSegment(recordingFile, startedAtMs)
        sendCaptureStatus(recordingFile = recordingFile)
    }

    private fun requestManualHighlight() {
        if (!isCapturing || mediaProjection == null) {
            sendHighlightStatus("Start capture before saving a highlight")
            return
        }

        if (!highlightInProgress.compareAndSet(false, true)) {
            sendHighlightStatus("Highlight already in progress")
            return
        }

        val request = HighlightRequest(triggerTimeMs = System.currentTimeMillis())
        val preEventSegments = replayBuffer.getSegmentsForWindow(request.startTimeMs, request.triggerTimeMs)
        protectSegments(preEventSegments)
        sendHighlightStatus("Saving highlight: 10s before + 2s after")
        segmentRotationHandler.postDelayed(
            {
                finalizeManualHighlight(request, preEventSegments)
            },
            request.postEventDurationMs
        )
    }

    private fun finalizeManualHighlight(request: HighlightRequest, preEventSegments: List<SegmentFile>) {
        val projection = mediaProjection
        val recorder = screenRecorder
        val activeSegmentStartedAtMs = currentSegmentStartedAtMs
        if (!isCapturing || projection == null || recorder == null) {
            unprotectSegments(preEventSegments)
            highlightInProgress.set(false)
            sendHighlightStatus("Capture stopped before highlight was ready")
            return
        }

        virtualDisplay?.release()
        virtualDisplay = null
        screenRecorder = null
        mediaProjection = null
        isCapturing = false
        sendCaptureStatus()

        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }

        Thread {
            try {
                val recordingFile = recorder.stop()
                val activeSegment = if (recordingFile.exists() && recordingFile.length() > 0L) {
                    segmentManager.registerSegment(
                        file = recordingFile,
                        startedAtMs = activeSegmentStartedAtMs,
                        finishedAtMs = System.currentTimeMillis()
                    )
                } else {
                    null
                }

                val selectedSegments = (
                    preEventSegments +
                        listOfNotNull(activeSegment) +
                        replayBuffer.getSegmentsForWindow(request.triggerTimeMs, request.endTimeMs)
                    ).distinctBy { it.file.absolutePath }

                if (selectedSegments.isEmpty()) {
                    sendHighlightStatus("No replay footage available for highlight")
                    return@Thread
                }

                val outputFile = ClipBuilder(highlightDirectory).buildWindowFromSegments(
                    selectedSegments,
                    "highlight_${request.timestampName()}.mp4",
                    request.startTimeMs,
                    request.endTimeMs
                )
                sendHighlightStatus("Highlight saved. Capture stopped.", outputFile)
                showHighlightSavedNotification(outputFile)
            } catch (error: Exception) {
                sendHighlightStatus("Highlight failed: ${error.message ?: "unknown error"}")
            } finally {
                unprotectSegments(preEventSegments)
                highlightInProgress.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun protectSegments(segments: List<SegmentFile>) {
        synchronized(protectedSegmentPaths) {
            segments.forEach { protectedSegmentPaths.add(it.file.absolutePath) }
        }
    }

    private fun unprotectSegments(segments: List<SegmentFile>) {
        val currentPaths = replayBuffer.currentSegments().mapTo(mutableSetOf()) { it.file.absolutePath }
        synchronized(protectedSegmentPaths) {
            segments.forEach { segment ->
                protectedSegmentPaths.remove(segment.file.absolutePath)
                if (segment.file.absolutePath !in currentPaths && segment.file.exists()) {
                    segment.file.delete()
                }
            }
        }
    }

    private fun isSegmentProtected(file: File): Boolean {
        return synchronized(protectedSegmentPaths) {
            file.absolutePath in protectedSegmentPaths
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

    private fun sendCaptureStatus(recordingFile: File? = null, recordingUri: android.net.Uri? = null) {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_CAPTURING, isCapturing)
                .putExtra(EXTRA_RECORDING_URI, recordingUri?.toString())
                .putExtra(EXTRA_RECORDING_PATH, recordingFile?.absolutePath)
        )
    }

    private fun sendHighlightStatus(message: String, highlightFile: File? = null) {
        sendBroadcast(
            Intent(ACTION_HIGHLIGHT_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_HIGHLIGHT_MESSAGE, message)
                .putExtra(EXTRA_HIGHLIGHT_PATH, highlightFile?.absolutePath)
                .putExtra(EXTRA_IS_HIGHLIGHT_IN_PROGRESS, highlightInProgress.get())
        )
    }

    private fun showHighlightSavedNotification(highlightFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            highlightFile
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("Highlight saved")
            .setContentText(highlightFile.name)
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

        getSystemService(NotificationManager::class.java).notify(HIGHLIGHT_SAVED_NOTIFICATION_ID, notification)
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
        private const val HIGHLIGHT_SAVED_NOTIFICATION_ID = 1003
        private const val VIRTUAL_DISPLAY_NAME = "MLBBHighlightCapture"
        private const val MAX_RECORDING_LONG_EDGE = 1280
        private const val SEGMENT_DURATION_MS = 5_000L
        private const val REPLAY_BUFFER_DURATION_MS = 30_000L
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_IS_CAPTURING = "extra_is_capturing"
        const val EXTRA_RECORDING_URI = "extra_recording_uri"
        const val EXTRA_RECORDING_PATH = "extra_recording_path"
        const val EXTRA_HIGHLIGHT_PATH = "extra_highlight_path"
        const val EXTRA_HIGHLIGHT_MESSAGE = "extra_highlight_message"
        const val EXTRA_IS_HIGHLIGHT_IN_PROGRESS = "extra_is_highlight_in_progress"

        const val ACTION_START = "com.mlbb.highlight.recording.action.START"
        const val ACTION_STOP = "com.mlbb.highlight.recording.action.STOP"
        const val ACTION_MANUAL_HIGHLIGHT = "com.mlbb.highlight.recording.action.MANUAL_HIGHLIGHT"
        const val ACTION_STATUS_CHANGED = "com.mlbb.highlight.recording.action.STATUS_CHANGED"
        const val ACTION_HIGHLIGHT_STATUS_CHANGED = "com.mlbb.highlight.recording.action.HIGHLIGHT_STATUS_CHANGED"

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

        fun manualHighlightIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_MANUAL_HIGHLIGHT
            }
        }
    }

    private data class RecordingSize(
        val width: Int,
        val height: Int
    )
}
