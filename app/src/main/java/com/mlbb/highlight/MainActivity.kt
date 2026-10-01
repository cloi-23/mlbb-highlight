package com.mlbb.highlight

import android.Manifest
import android.os.Bundle
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.mlbb.highlight.recording.ScreenCaptureService
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.settings.SettingsRepository
import com.mlbb.highlight.storage.HighlightRepository
import com.mlbb.highlight.ui.HighlightsScreen
import com.mlbb.highlight.ui.RecorderBottomNavigation
import com.mlbb.highlight.ui.RecorderDestination
import com.mlbb.highlight.ui.RecorderHomeScreen
import com.mlbb.highlight.ui.RecorderTheme
import com.mlbb.highlight.ui.RecordingSettingsScreen
import com.mlbb.highlight.ui.EffectsScreen
import com.mlbb.highlight.ui.TrimScreen
import com.mlbb.highlight.ui.VideoEffectOptions
import com.mlbb.highlight.ui.VideoEffectPreset
import com.mlbb.highlight.ui.createSceneVideoEffects
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val highlightRepository = remember { HighlightRepository(applicationContext) }
                    val settingsRepository = remember { SettingsRepository(applicationContext) }
                    var settings by remember { mutableStateOf(settingsRepository.load()) }
                    var isCapturing by rememberSaveable { mutableStateOf(ScreenCaptureService.isCapturing) }
                    var isPaused by rememberSaveable { mutableStateOf(ScreenCaptureService.isPaused) }
                    var recordingSeconds by rememberSaveable { mutableStateOf(0) }
                    var statusMessage by rememberSaveable {
                        mutableStateOf(
                            if (ScreenCaptureService.isCapturing) {
                                if (ScreenCaptureService.isPaused) "Recording paused" else "Recording"
                            } else {
                                "Ready to record"
                            }
                        )
                    }
                    var shouldStartAfterNotificationPermission by rememberSaveable {
                        mutableStateOf(false)
                    }
                    var shouldStartAfterAudioPermission by rememberSaveable {
                        mutableStateOf(false)
                    }
                    var highlights by remember { mutableStateOf(highlightRepository.listHighlights()) }
                    var selectedDestination by rememberSaveable {
                        mutableStateOf(RecorderDestination.HOME.name)
                    }
                    var showVideos by rememberSaveable { mutableStateOf(false) }
                    var selectedVideoUri by rememberSaveable { mutableStateOf<String?>(null) }
                    var trimStartMs by rememberSaveable { mutableStateOf(0L) }
                    var trimEndMs by rememberSaveable { mutableStateOf(0L) }
                    var sourceDurationMs by rememberSaveable { mutableStateOf(0L) }
                    var effectStartMs by rememberSaveable { mutableStateOf(0L) }
                    var effectEndMs by rememberSaveable { mutableStateOf(0L) }
                    var effectOptions by remember { mutableStateOf(VideoEffectOptions()) }
                    var appliedEffectOptions by remember { mutableStateOf(VideoEffectOptions()) }
                    var appliedEffectStartMs by rememberSaveable { mutableStateOf(0L) }
                    var appliedEffectEndMs by rememberSaveable { mutableStateOf(0L) }
                    var isEffectPreviewApplied by rememberSaveable { mutableStateOf(false) }
                    var previewRequestId by rememberSaveable { mutableStateOf(0) }
                    var isExporting by remember { mutableStateOf(false) }
                    var exportProgress by remember { mutableStateOf(0f) }
                    var exportMessage by remember { mutableStateOf<String?>(null) }
                    val activeTransformer = remember { AtomicReference<Transformer?>(null) }
                    val activeOutputFile = remember { AtomicReference<File?>(null) }
                    val progressHolder = remember { ProgressHolder() }

                    val videoPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (exception: SecurityException) {
                                statusMessage = "Video selected, but Android could not keep access after closing the app"
                            }
                            selectedVideoUri = uri.toString()
                            trimStartMs = 0L
                            trimEndMs = 0L
                            sourceDurationMs = 0L
                            effectStartMs = 0L
                            effectEndMs = 0L
                            effectOptions = VideoEffectOptions()
                            appliedEffectOptions = VideoEffectOptions()
                            appliedEffectStartMs = 0L
                            appliedEffectEndMs = 0L
                            isEffectPreviewApplied = false
                            exportMessage = null
                        }
                    }
                    val folderPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                                val updated = settings.copy(saveLocationUri = uri.toString())
                                settings = updated
                                settingsRepository.save(updated)
                            } catch (exception: SecurityException) {
                                statusMessage = "Android did not grant access to that folder"
                            }
                        }
                    }

                    fun selectEditorVideo(uri: Uri) {
                        selectedVideoUri = uri.toString()
                        trimStartMs = 0L
                        trimEndMs = 0L
                        sourceDurationMs = 0L
                        effectStartMs = 0L
                        effectEndMs = 0L
                        effectOptions = VideoEffectOptions()
                        appliedEffectOptions = VideoEffectOptions()
                        appliedEffectStartMs = 0L
                        appliedEffectEndMs = 0L
                        isEffectPreviewApplied = false
                        exportMessage = null
                    }

                    fun startExport() {
                        val inputUri = selectedVideoUri?.let(Uri::parse)
                        val clipDurationMs = trimEndMs - trimStartMs
                        val safeEffectStartMs = effectStartMs.coerceIn(0L, clipDurationMs)
                        val safeEffectEndMs = effectEndMs.coerceIn(safeEffectStartMs, clipDurationMs)
                        if (
                            inputUri == null ||
                            trimEndMs <= trimStartMs ||
                            safeEffectEndMs <= safeEffectStartMs ||
                            isExporting
                        ) {
                            exportMessage = "Choose a video and set a valid trim range before exporting"
                            return
                        }

                        val outputFile = highlightRepository.createEditedOutputFile()
                        val sceneStartUs = safeEffectStartMs * 1_000L
                        val sceneEndUs = safeEffectEndMs * 1_000L
                        val sceneEffects = createSceneVideoEffects(
                            effectOptions,
                            sceneStartUs,
                            sceneEndUs
                        )
                        val wantsSlowMotion =
                            effectOptions.slowMotionEnabled ||
                                effectOptions.preset == VideoEffectPreset.SLOW_MOTION
                        val mediaItem = MediaItem.Builder()
                            .setUri(inputUri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(trimStartMs)
                                    .setEndPositionMs(trimEndMs)
                                    .build()
                            )
                            .build()
                        val editedItemBuilder = EditedMediaItem.Builder(mediaItem)
                            .setEffects(Effects(emptyList(), sceneEffects))
                        if (wantsSlowMotion) {
                            val speed = effectOptions.slowMotionSpeed.coerceIn(0.25f, 0.9f)
                            editedItemBuilder.setSpeed(
                                object : SpeedProvider {
                                    override fun getSpeed(timeUs: Long): Float = when {
                                        timeUs < sceneStartUs -> 1f
                                        timeUs < sceneEndUs -> speed
                                        else -> 1f
                                    }

                                    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = when {
                                        timeUs < sceneStartUs -> sceneStartUs
                                        timeUs < sceneEndUs -> sceneEndUs
                                        else -> C.TIME_UNSET
                                    }
                                }
                            )
                        }
                        val editedItem = editedItemBuilder.build()

                        val transformer = Transformer.Builder(applicationContext)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(
                                object : Transformer.Listener {
                                    override fun onCompleted(
                                        composition: androidx.media3.transformer.Composition,
                                        exportResult: ExportResult
                                    ) {
                                        activeTransformer.set(null)
                                        activeOutputFile.set(null)
                                        isExporting = false
                                        exportProgress = 1f
                                        if (!outputFile.isFile || outputFile.length() == 0L) {
                                            exportMessage = "Export finished without a playable output file"
                                            return
                                        }
                                        highlights = highlightRepository.listHighlights()
                                        exportMessage = "Export saved to My Videos"
                                        if (settings.autoSave && settings.saveLocationUri != null) {
                                            try {
                                                highlightRepository.copyToFolderTree(
                                                    outputFile,
                                                    Uri.parse(settings.saveLocationUri)
                                                )
                                                exportMessage = "Export saved to My Videos and copied to the selected folder"
                                            } catch (exception: IOException) {
                                                exportMessage =
                                                    "Export saved to My Videos, but the selected-folder copy failed: " +
                                                        (exception.localizedMessage ?: "storage error")
                                            } catch (exception: SecurityException) {
                                                exportMessage =
                                                    "Export saved to My Videos, but Android denied access to the selected folder"
                                            } catch (exception: IllegalArgumentException) {
                                                exportMessage =
                                                    "Export saved to My Videos, but the selected folder is no longer available"
                                            }
                                        }
                                    }

                                    override fun onError(
                                        composition: androidx.media3.transformer.Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException
                                    ) {
                                        activeTransformer.set(null)
                                        activeOutputFile.set(null)
                                        isExporting = false
                                        outputFile.delete()
                                        exportMessage =
                                            "Export failed (${exportException.errorCodeName}): " +
                                                (exportException.cause?.localizedMessage
                                                    ?: exportException.localizedMessage
                                                    ?: "unsupported video or effect")
                                    }
                                }
                            )
                            .build()

                        activeTransformer.set(transformer)
                        activeOutputFile.set(outputFile)
                        isExporting = true
                        exportProgress = 0f
                        exportMessage = "Preparing MP4 export…"
                        try {
                            transformer.start(editedItem, outputFile.absolutePath)
                        } catch (exception: IllegalArgumentException) {
                            activeTransformer.set(null)
                            activeOutputFile.set(null)
                            isExporting = false
                            outputFile.delete()
                            exportMessage = "Could not export this video: ${exception.localizedMessage ?: "invalid input"}"
                        } catch (exception: IllegalStateException) {
                            activeTransformer.set(null)
                            activeOutputFile.set(null)
                            isExporting = false
                            outputFile.delete()
                            exportMessage =
                                "Could not start export: ${exception.localizedMessage ?: "exporter is unavailable"}"
                        }
                    }

                    fun refreshCaptureUiState() {
                        isCapturing = ScreenCaptureService.isCapturing
                        isPaused = ScreenCaptureService.isPaused
                        statusMessage = when {
                            isPaused -> "Recording paused"
                            isCapturing -> "Recording"
                            else -> "Ready to record"
                        }
                    }

                    val projectionManager = remember {
                        getSystemService(MediaProjectionManager::class.java)
                    }

                    val projectionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                            ContextCompat.startForegroundService(
                                this,
                                ScreenCaptureService.startIntent(
                                    this,
                                    result.resultCode,
                                    result.data ?: Intent(),
                                    settings
                                )
                            )
                            isCapturing = true
                            isPaused = false
                            statusMessage = "Recording"
                            recordingSeconds = 0
                        } else {
                            isCapturing = false
                            recordingSeconds = 0
                            statusMessage = "Capture permission denied"
                        }
                    }

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) {
                        if (shouldStartAfterNotificationPermission) {
                            shouldStartAfterNotificationPermission = false
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    }

                    val audioPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (shouldStartAfterAudioPermission) {
                            shouldStartAfterAudioPermission = false
                            if (granted) {
                                if (shouldRequestNotificationPermission()) {
                                    shouldStartAfterNotificationPermission = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }
                            } else {
                                statusMessage = "Microphone permission denied; choose Silent or allow microphone access"
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        refreshCaptureUiState()
                    }

                    LaunchedEffect(selectedDestination) {
                        if (selectedDestination == RecorderDestination.TRIM.name) {
                            highlights = highlightRepository.listHighlights()
                        }
                    }

                    LaunchedEffect(isCapturing, isPaused) {
                        while (isCapturing && !isPaused) {
                            delay(1_000L)
                            recordingSeconds += 1
                        }
                    }

                    LaunchedEffect(isExporting) {
                        while (isExporting) {
                            activeTransformer.get()?.let { transformer ->
                                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    exportProgress = progressHolder.progress / 100f
                                }
                            }
                            delay(250L)
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            activeTransformer.getAndSet(null)?.cancel()
                            activeOutputFile.getAndSet(null)?.delete()
                        }
                    }

                    DisposableEffect(Unit) {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                when (intent?.action) {
                                    ScreenCaptureService.ACTION_STATUS_CHANGED -> {
                                        val wasCapturing = isCapturing
                                        isCapturing = intent.getBooleanExtra(
                                            ScreenCaptureService.EXTRA_IS_CAPTURING,
                                            false
                                        )
                                        isPaused = intent.getBooleanExtra(
                                            ScreenCaptureService.EXTRA_IS_PAUSED,
                                            false
                                        )
                                        if (isCapturing && !wasCapturing) {
                                            recordingSeconds = 0
                                        }
                                        if (!isCapturing) {
                                            ScreenCaptureService.setCapturing(false)
                                            recordingSeconds = 0
                                            highlights = highlightRepository.listHighlights()
                                        }
                                        val error = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE)
                                        statusMessage = error ?: when {
                                            isPaused -> "Recording paused"
                                            isCapturing -> "Recording"
                                            else -> "Ready to record"
                                        }
                                    }
                                }
                            }
                        }

                        val filter = IntentFilter().apply {
                            addAction(ScreenCaptureService.ACTION_STATUS_CHANGED)
                        }

                        ContextCompat.registerReceiver(
                            this@MainActivity,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )

                        onDispose {
                            unregisterReceiver(receiver)
                        }
                    }

                    val scheme = darkColorScheme(
                        primary = RecorderTheme.blue,
                        onPrimary = Color.White,
                        background = RecorderTheme.background,
                        onBackground = RecorderTheme.textPrimary,
                        surface = RecorderTheme.surface,
                        onSurface = RecorderTheme.textPrimary,
                        secondary = RecorderTheme.cyan
                    )

                    val currentDestination = RecorderDestination.entries.firstOrNull {
                        it.name == selectedDestination
                    } ?: RecorderDestination.HOME
                    val selectedTab = when {
                        showVideos -> RecorderDestination.VIDEOS
                        currentDestination == RecorderDestination.SETTINGS -> RecorderDestination.PROFILE
                        else -> RecorderDestination.HOME
                    }

                    MaterialTheme(colorScheme = scheme) {
                        Scaffold(
                            containerColor = RecorderTheme.background,
                            bottomBar = {
                                RecorderBottomNavigation(
                                    selected = selectedTab,
                                    onSelect = {
                                        when (it) {
                                            RecorderDestination.HOME -> {
                                                showVideos = false
                                                selectedDestination = RecorderDestination.HOME.name
                                            }
                                            RecorderDestination.VIDEOS -> showVideos = true
                                            RecorderDestination.PROFILE -> {
                                                showVideos = false
                                                selectedDestination = RecorderDestination.SETTINGS.name
                                            }
                                            else -> Unit
                                        }
                                    }
                                )
                            }
                        ) { contentPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                if (showVideos) {
                                    com.mlbb.highlight.ui.EditorScreenHeader(
                                        title = "My videos",
                                        onBack = { showVideos = false }
                                    )
                                    HighlightsScreen(
                                        highlights = highlights,
                                        onPlay = { entity ->
                                            val file = java.io.File(entity.filePath)
                                            if (!file.exists() || !file.isFile || file.length() <= 0L) {
                                                statusMessage = "Recording file is missing or empty"
                                                return@HighlightsScreen
                                            }
                                            val uri = FileProvider.getUriForFile(
                                                this@MainActivity,
                                                "$packageName.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "video/mp4")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(intent)
                                        },
                                        onDelete = { entity ->
                                            highlightRepository.deleteHighlight(entity)
                                            highlights = highlightRepository.listHighlights()
                                        },
                                        onShare = { entity ->
                                            val uri = FileProvider.getUriForFile(
                                                this@MainActivity,
                                                "$packageName.fileprovider",
                                                java.io.File(entity.filePath)
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "video/mp4"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT, entity.title)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(Intent.createChooser(shareIntent, "Share recording"))
                                        }
                                    )
                                } else {
                                    when (currentDestination) {
                                        RecorderDestination.HOME -> RecorderHomeScreen(
                                            isCapturing = isCapturing,
                                            isPaused = isPaused,
                                            recordingSeconds = recordingSeconds,
                                            statusMessage = statusMessage,
                                            settings = settings,
                                            onStart = {
                                                val needsAudioPermission =
                                                    settings.audioSource != RecordingAudioSource.SILENT ||
                                                        settings.voiceCommandsEnabled
                                                if (
                                                    needsAudioPermission &&
                                                    ContextCompat.checkSelfPermission(
                                                        this@MainActivity,
                                                        Manifest.permission.RECORD_AUDIO
                                                    ) != PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    shouldStartAfterAudioPermission = true
                                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                } else if (shouldRequestNotificationPermission()) {
                                                    shouldStartAfterNotificationPermission = true
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                                }
                                            },
                                            onPauseResume = {
                                                startService(ScreenCaptureService.pauseResumeIntent(this@MainActivity))
                                            },
                                            onStop = {
                                                startService(ScreenCaptureService.stopIntent(this@MainActivity))
                                            },
                                            onOpenTrim = {
                                                selectedDestination = RecorderDestination.TRIM.name
                                            },
                                            onOpenEffects = {
                                                selectedDestination = RecorderDestination.EFFECTS.name
                                            },
                                            onOpenSettings = {
                                                selectedDestination = RecorderDestination.SETTINGS.name
                                            }
                                        )

                                        RecorderDestination.TRIM -> TrimScreen(
                                            videoUri = selectedVideoUri,
                                            startMs = trimStartMs,
                                            endMs = trimEndMs,
                                            durationMs = sourceDurationMs,
                                            savedVideos = highlights,
                                            onPickVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                                            onSelectSavedVideo = { entity ->
                                                val file = java.io.File(entity.filePath)
                                                if (!file.isFile || file.length() <= 0L) {
                                                    exportMessage = "This saved video is missing or empty"
                                                } else {
                                                    selectEditorVideo(
                                                        FileProvider.getUriForFile(
                                                            this@MainActivity,
                                                            "$packageName.fileprovider",
                                                            file
                                                        )
                                                    )
                                                }
                                            },
                                            onTrimChanged = { start, end ->
                                                isEffectPreviewApplied = false
                                                trimStartMs = start.coerceAtLeast(0L)
                                                trimEndMs = end.coerceAtLeast(trimStartMs)
                                                effectStartMs = 0L
                                                effectEndMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                            },
                                            onDurationChanged = { duration ->
                                                if (duration > 0L && duration != sourceDurationMs) {
                                                    sourceDurationMs = duration
                                                    if (trimEndMs == 0L || trimEndMs > duration) {
                                                        trimEndMs = duration
                                                    }
                                                    if (effectEndMs == 0L) {
                                                        effectStartMs = 0L
                                                        effectEndMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                    }
                                                }
                                            },
                                            onBack = {
                                                selectedDestination = RecorderDestination.HOME.name
                                            },
                                            onNext = {
                                                selectedDestination = RecorderDestination.EFFECTS.name
                                            }
                                        )

                                        RecorderDestination.EFFECTS -> EffectsScreen(
                                            videoUri = selectedVideoUri,
                                            startMs = trimStartMs,
                                            endMs = trimEndMs,
                                            effectStartMs = effectStartMs,
                                            effectEndMs = effectEndMs,
                                            isPreviewApplied = isEffectPreviewApplied,
                                            appliedEffectOptions = appliedEffectOptions,
                                            appliedEffectStartMs = appliedEffectStartMs,
                                            appliedEffectEndMs = appliedEffectEndMs,
                                            previewRequestId = previewRequestId,
                                            onEffectRangeChanged = { start, end ->
                                                isEffectPreviewApplied = false
                                                val clipDuration = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                effectStartMs = start.coerceIn(0L, clipDuration)
                                                effectEndMs = end.coerceIn(
                                                    effectStartMs,
                                                    clipDuration
                                                )
                                            },
                                            onDurationChanged = { duration ->
                                                if (duration > 0L && duration != sourceDurationMs) {
                                                    sourceDurationMs = duration
                                                    if (trimEndMs == 0L || trimEndMs > duration) {
                                                        trimEndMs = duration
                                                    }
                                                    if (effectEndMs == 0L) {
                                                        effectStartMs = 0L
                                                        effectEndMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                    }
                                                }
                                            },
                                            options = effectOptions,
                                            onOptionsChanged = {
                                                isEffectPreviewApplied = false
                                                effectOptions = it
                                            },
                                            onApplyPreview = {
                                                appliedEffectOptions = effectOptions
                                                appliedEffectStartMs = effectStartMs
                                                appliedEffectEndMs = effectEndMs
                                                isEffectPreviewApplied = true
                                                previewRequestId += 1
                                                exportMessage = "Effects applied to preview. Export when you are ready."
                                            },
                                            isExporting = isExporting,
                                            exportProgress = exportProgress,
                                            exportMessage = exportMessage,
                                            onExport = ::startExport,
                                            onGoToTrim = {
                                                selectedDestination = RecorderDestination.TRIM.name
                                            },
                                            onBack = {
                                                selectedDestination = RecorderDestination.TRIM.name
                                            },
                                            onOpenVideos = {
                                                showVideos = true
                                                selectedDestination = RecorderDestination.HOME.name
                                            }
                                        )

                                        RecorderDestination.SETTINGS -> {
                                            com.mlbb.highlight.ui.EditorScreenHeader(
                                                title = "Settings",
                                                onBack = {
                                                    selectedDestination = RecorderDestination.HOME.name
                                                }
                                            )
                                            RecordingSettingsScreen(
                                                settings = settings,
                                                onSettingsChange = { updated ->
                                                    settings = updated
                                                    settingsRepository.save(updated)
                                                },
                                                saveLocationLabel = if (settings.saveLocationUri == null) {
                                                    "App videos folder"
                                                } else {
                                                    "Selected folder"
                                                },
                                                appVersion = packageManager.getPackageInfo(packageName, 0).versionName
                                                    ?: "Unknown",
                                                onChooseSaveLocation = { folderPickerLauncher.launch(null) },
                                                onResetSettings = {
                                                    settings = AppSettings()
                                                    settingsRepository.save(settings)
                                                }
                                            )
                                        }

                                        RecorderDestination.VIDEOS, RecorderDestination.PROFILE -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun formatElapsed(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d".format(minutes, remainingSeconds)
    }
}
