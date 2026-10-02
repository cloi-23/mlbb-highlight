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
import android.provider.Settings
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
import com.mlbb.highlight.recording.FloatingControlService
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
import com.mlbb.highlight.ui.SceneEffect
import com.mlbb.highlight.ui.usesSlowMotion
import com.mlbb.highlight.ui.createSceneVideoEffects
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private var overlayPermissionRequested = false

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this,
                FloatingControlService.startIntent(this)
            )
        } else if (!overlayPermissionRequested) {
            overlayPermissionRequested = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun videoUriFor(location: String): Uri {
        val uri = Uri.parse(location)
        if (uri.scheme == "content") return uri
        val file = File(location)
        require(file.isFile && file.length() > 0L) { "Video file is missing or empty" }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

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
                    var shouldPrepareFloatingRecorder by rememberSaveable {
                        mutableStateOf(false)
                    }
                    var highlights by remember {
                        mutableStateOf(
                            highlightRepository.listHighlights(settings.saveLocationUri?.let(Uri::parse))
                        )
                    }
                    var selectedDestination by rememberSaveable {
                        mutableStateOf(RecorderDestination.HOME.name)
                    }
                    var showVideos by rememberSaveable { mutableStateOf(false) }
                    var videoLibraryMessage by rememberSaveable { mutableStateOf<String?>(null) }
                    var playbackVideoUri by rememberSaveable { mutableStateOf<String?>(null) }
                    var playbackVideoTitle by rememberSaveable { mutableStateOf("") }
                    var selectedVideoUri by rememberSaveable { mutableStateOf<String?>(null) }
                    var trimStartMs by rememberSaveable { mutableStateOf(0L) }
                    var trimEndMs by rememberSaveable { mutableStateOf(0L) }
                    var sourceDurationMs by rememberSaveable { mutableStateOf(0L) }
                    var effectStartMs by rememberSaveable { mutableStateOf(0L) }
                    var effectEndMs by rememberSaveable { mutableStateOf(0L) }
                    var effectOptions by remember { mutableStateOf(VideoEffectOptions()) }
                    var sceneEffects by remember { mutableStateOf(emptyList<SceneEffect>()) }
                    var appliedSceneEffects by remember { mutableStateOf(emptyList<SceneEffect>()) }
                    var selectedSceneId by rememberSaveable { mutableStateOf("") }
                    var playheadMs by rememberSaveable { mutableStateOf(0L) }
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
                            sceneEffects = emptyList()
                            appliedSceneEffects = emptyList()
                            selectedSceneId = ""
                            playheadMs = 0L
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
                                highlights = highlightRepository.listHighlights(uri)
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
                        sceneEffects = emptyList()
                        appliedSceneEffects = emptyList()
                        selectedSceneId = ""
                        playheadMs = 0L
                        isEffectPreviewApplied = false
                        exportMessage = null
                    }

                    fun startExport() {
                        val inputUri = selectedVideoUri?.let(Uri::parse)
                        val clipDurationMs = trimEndMs - trimStartMs
                        val exportScenes = appliedSceneEffects.filter {
                            it.endMs > it.startMs && it.options.preset != VideoEffectPreset.NONE
                        }.map { scene ->
                            scene.copy(
                                startMs = scene.startMs.coerceIn(0L, clipDurationMs),
                                endMs = scene.endMs.coerceIn(0L, clipDurationMs)
                            )
                        }.filter { it.endMs > it.startMs }
                        if (
                            inputUri == null ||
                            trimEndMs <= trimStartMs ||
                            settings.saveLocationUri == null ||
                            exportScenes.isEmpty() ||
                            !isEffectPreviewApplied ||
                            isExporting
                        ) {
                            exportMessage = if (settings.saveLocationUri == null) {
                                "Choose a gallery folder in Settings before exporting"
                            } else {
                                "Preview a scene effect before exporting"
                            }
                            return
                        }

                        val outputFile = highlightRepository.createEditedOutputFile()
                        val videoEffects = exportScenes.flatMap { scene ->
                            createSceneVideoEffects(
                                scene.options,
                                scene.startMs * 1_000L,
                                scene.endMs * 1_000L,
                                (scene.eventTimeMs ?: (scene.startMs + 200L)) * 1_000L
                            )
                        }
                        val slowMotionScenes = exportScenes.filter { it.options.usesSlowMotion() }
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
                            .setEffects(Effects(emptyList(), videoEffects))
                        if (slowMotionScenes.isNotEmpty()) {
                            editedItemBuilder.setSpeed(
                                object : SpeedProvider {
                                    override fun getSpeed(timeUs: Long): Float =
                                        slowMotionScenes
                                            .filter { timeUs >= it.startMs * 1_000L && timeUs < it.endMs * 1_000L }
                                            .minOfOrNull { it.options.slowMotionSpeed.coerceIn(0.25f, 0.9f) }
                                            ?: 1f

                                    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                        slowMotionScenes
                                            .flatMap { listOf(it.startMs * 1_000L, it.endMs * 1_000L) }
                                            .filter { it > timeUs }
                                            .minOrNull() ?: C.TIME_UNSET
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
                                        try {
                                            val folderUri = settings.saveLocationUri?.let(Uri::parse)
                                                ?: throw IOException("Choose a gallery folder in Settings")
                                            highlightRepository.copyToFolderTree(outputFile, folderUri)
                                            if (!outputFile.delete()) {
                                                exportMessage = "Export saved to the selected folder, but its temporary copy could not be removed"
                                            } else {
                                                exportMessage = "Export saved to the selected gallery folder"
                                            }
                                            highlights = highlightRepository.listHighlights(folderUri)
                                        } catch (exception: IOException) {
                                            val temporaryCopyRemoved = outputFile.delete()
                                            exportMessage =
                                                "Could not save export to the selected folder: " +
                                                    (exception.localizedMessage ?: "storage error") +
                                                    if (temporaryCopyRemoved) "" else ". Temporary export could not be removed"
                                        } catch (exception: SecurityException) {
                                            val temporaryCopyRemoved = outputFile.delete()
                                            exportMessage =
                                                "Could not save export: Android denied access to the selected folder" +
                                                    if (temporaryCopyRemoved) "" else ". Temporary export could not be removed"
                                        } catch (exception: IllegalArgumentException) {
                                            val temporaryCopyRemoved = outputFile.delete()
                                            exportMessage =
                                                "Could not save export: the selected folder is no longer available" +
                                                    if (temporaryCopyRemoved) "" else ". Temporary export could not be removed"
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
                            val resultData = result.data ?: return@rememberLauncherForActivityResult
                            if (shouldPrepareFloatingRecorder) {
                                shouldPrepareFloatingRecorder = false
                                ContextCompat.startForegroundService(
                                    this,
                                    ScreenCaptureService.prepareIntent(
                                        this,
                                        result.resultCode,
                                        resultData,
                                        settings
                                    )
                                )
                                statusMessage = "Floating recorder is ready. Start recording from its overlay."
                            } else {
                                ContextCompat.startForegroundService(
                                    this,
                                    ScreenCaptureService.startIntent(
                                        this,
                                        result.resultCode,
                                        resultData,
                                        settings
                                    )
                                )
                                isCapturing = true
                                isPaused = false
                                statusMessage = "Recording"
                                recordingSeconds = 0
                            }
                        } else {
                            shouldPrepareFloatingRecorder = false
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

                    fun requestRecordingStart() {
                        shouldPrepareFloatingRecorder = false
                        if (settings.saveLocationUri == null) {
                            statusMessage = "Choose a gallery save folder in Settings before recording"
                            selectedDestination = RecorderDestination.SETTINGS.name
                            return
                        }
                        val needsAudioPermission = settings.audioSource != RecordingAudioSource.SILENT
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
                    }

                    fun prepareFloatingRecorder() {
                        if (settings.saveLocationUri == null) {
                            statusMessage = "Choose a gallery save folder in Settings before preparing capture"
                            return
                        }
                        if (isCapturing) {
                            statusMessage = "Stop the current recording before preparing floating capture"
                            return
                        }
                        shouldPrepareFloatingRecorder = true
                        val needsAudioPermission = settings.audioSource != RecordingAudioSource.SILENT
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
                    }

                    LaunchedEffect(Unit) {
                        refreshCaptureUiState()
                    }

                    LaunchedEffect(selectedDestination) {
                        if (selectedDestination == RecorderDestination.TRIM.name) {
                            highlights = highlightRepository.listHighlights(settings.saveLocationUri?.let(Uri::parse))
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
                                            highlights = highlightRepository.listHighlights(settings.saveLocationUri?.let(Uri::parse))
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
                                if (playbackVideoUri == null) {
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
                            }
                        ) { contentPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding)
                                    .padding(
                                        horizontal = if (playbackVideoUri == null) 20.dp else 0.dp,
                                        vertical = if (playbackVideoUri == null) 18.dp else 0.dp
                                    )
                            ) {
                                if (playbackVideoUri != null) {
                                    com.mlbb.highlight.ui.VideoPlaybackScreen(
                                        title = playbackVideoTitle,
                                        videoUri = playbackVideoUri!!,
                                        onBack = { playbackVideoUri = null },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (showVideos) {
                                    com.mlbb.highlight.ui.EditorScreenHeader(
                                        title = "My videos",
                                        onBack = { showVideos = false }
                                    )
                                    HighlightsScreen(
                                        highlights = highlights,
                                        message = videoLibraryMessage,
                                        modifier = Modifier.weight(1f),
                                        onPlay = { entity ->
                                            try {
                                                playbackVideoTitle = entity.title
                                                playbackVideoUri = videoUriFor(entity.filePath).toString()
                                            } catch (exception: IllegalArgumentException) {
                                                videoLibraryMessage = exception.localizedMessage
                                                    ?: "Recording file is missing or empty"
                                            }
                                        },
                                        onDelete = { entity ->
                                            try {
                                                highlightRepository.deleteHighlight(entity)
                                                highlights = highlightRepository.listHighlights(
                                                    settings.saveLocationUri?.let(Uri::parse)
                                                )
                                                videoLibraryMessage = null
                                            } catch (exception: IOException) {
                                                videoLibraryMessage = "Could not delete video: " +
                                                    (exception.localizedMessage ?: "storage error")
                                            } catch (exception: SecurityException) {
                                                videoLibraryMessage = "Android denied access to delete this video"
                                            } catch (exception: IllegalArgumentException) {
                                                videoLibraryMessage = "Could not delete video: " +
                                                    (exception.localizedMessage ?: "storage error")
                                            }
                                        }
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(18.dp)
                                    ) {
                                    when (currentDestination) {
                                        RecorderDestination.HOME -> RecorderHomeScreen(
                                            isCapturing = isCapturing,
                                            isPaused = isPaused,
                                            recordingSeconds = recordingSeconds,
                                            statusMessage = statusMessage,
                                            settings = settings,
                                            onStart = ::requestRecordingStart,
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
                                                try {
                                                    selectEditorVideo(videoUriFor(entity.filePath))
                                                } catch (exception: IllegalArgumentException) {
                                                    exportMessage = "This saved video is unavailable"
                                                }
                                            },
                                            onTrimChanged = { start, end ->
                                                isEffectPreviewApplied = false
                                                trimStartMs = start.coerceAtLeast(0L)
                                                trimEndMs = end.coerceAtLeast(trimStartMs)
                                                effectStartMs = 0L
                                                effectEndMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                sceneEffects = emptyList()
                                                appliedSceneEffects = emptyList()
                                                selectedSceneId = ""
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
                                            previewRequestId = previewRequestId,
                                            sceneEffects = sceneEffects,
                                            selectedSceneId = selectedSceneId,
                                            appliedSceneEffects = appliedSceneEffects,
                                            playheadMs = playheadMs,
                                            onEffectRangeChanged = { start, end ->
                                                isEffectPreviewApplied = false
                                                val clipDuration = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                effectStartMs = start.coerceIn(0L, clipDuration)
                                                effectEndMs = end.coerceIn(
                                                    effectStartMs,
                                                    clipDuration
                                                )
                                                sceneEffects = sceneEffects.map { scene ->
                                                    if (scene.id == selectedSceneId) {
                                                        scene.copy(
                                                            startMs = effectStartMs,
                                                            endMs = effectEndMs,
                                                            eventTimeMs = scene.eventTimeMs?.coerceIn(
                                                                effectStartMs,
                                                                effectEndMs
                                                            )
                                                        )
                                                    } else scene
                                                }
                                            },
                                            onSceneEffectsChanged = {
                                                isEffectPreviewApplied = false
                                                sceneEffects = it
                                                it.firstOrNull { scene -> scene.id == selectedSceneId }?.let { scene ->
                                                    effectStartMs = scene.startMs
                                                    effectEndMs = scene.endMs
                                                    effectOptions = scene.options
                                                }
                                            },
                                            onSceneSelected = { scene ->
                                                selectedSceneId = scene.id
                                                effectStartMs = scene.startMs
                                                effectEndMs = scene.endMs
                                                effectOptions = scene.options
                                                isEffectPreviewApplied = false
                                            },
                                            onPlayheadChanged = { playheadMs = it },
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
                                                sceneEffects = sceneEffects.map { scene ->
                                                    if (scene.id == selectedSceneId) scene.copy(options = it) else scene
                                                }
                                            },
                                            onApplyPreview = {
                                                if (sceneEffects.none {
                                                        it.endMs > it.startMs &&
                                                            it.options.preset != VideoEffectPreset.NONE
                                                    }
                                                ) {
                                                    exportMessage = "Choose an effect for a scene before previewing"
                                                    return@EffectsScreen
                                                }
                                                appliedSceneEffects = sceneEffects
                                                isEffectPreviewApplied = true
                                                previewRequestId += 1
                                                exportMessage = "Previewing all scene effects. Export when you are ready."
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
                                                    "No folder selected"
                                                } else {
                                                    "Selected folder"
                                                },
                                                appVersion = packageManager.getPackageInfo(packageName, 0).versionName
                                                    ?: "Unknown",
                                                onChooseSaveLocation = { folderPickerLauncher.launch(null) },
                                                onRequestOverlayPermission = {
                                                    startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            Uri.parse("package:$packageName")
                                                        )
                                                    )
                                                },
                                                onPrepareFloatingRecorder = ::prepareFloatingRecorder,
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
