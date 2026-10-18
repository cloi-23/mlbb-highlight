package com.mlbb.highlight

import android.Manifest
import android.os.Bundle
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.mlbb.highlight.recording.ScreenCaptureService
import com.mlbb.highlight.recording.FloatingControlService
import com.mlbb.highlight.detection.HudCalibrationStore
import com.mlbb.highlight.detection.HudRegion
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.settings.SettingsRepository
import com.mlbb.highlight.storage.HighlightRepository
import com.mlbb.highlight.highlight.AutoHighlightEngine
import com.mlbb.highlight.highlight.HighlightAspectRatio
import com.mlbb.highlight.highlight.HighlightEvent
import com.mlbb.highlight.highlight.HighlightEventOrigin
import com.mlbb.highlight.highlight.HighlightEventType
import com.mlbb.highlight.highlight.HighlightResolution
import com.mlbb.highlight.highlight.HighlightStyle
import com.mlbb.highlight.highlight.HighlightTimeline
import com.mlbb.highlight.highlight.buildHighlightComposition
import com.mlbb.highlight.highlight.effectPreset
import com.mlbb.highlight.ui.AutoHighlightScreen
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
import com.mlbb.highlight.ui.VideoEditState
import com.mlbb.highlight.ui.EffectTimestampBasis
import com.mlbb.highlight.ui.buildVideoEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

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
                    var videoEditState by remember { mutableStateOf(VideoEditState()) }
                    val selectedVideoUri = videoEditState.sourceVideoUri
                    val trimStartMs = videoEditState.trimStartMs
                    val trimEndMs = videoEditState.trimEndMs
                    val sceneEffects = videoEditState.sceneEffects
                    var sourceDurationMs by rememberSaveable { mutableStateOf(0L) }
                    var effectStartMs by rememberSaveable { mutableStateOf(0L) }
                    var effectEndMs by rememberSaveable { mutableStateOf(0L) }
                    var effectOptions by remember { mutableStateOf(VideoEffectOptions()) }
                    var selectedSceneId by rememberSaveable { mutableStateOf("") }
                    var playheadMs by rememberSaveable { mutableStateOf(0L) }
                    var isEffectPreviewApplied by rememberSaveable { mutableStateOf(false) }
                    var previewRequestId by rememberSaveable { mutableStateOf(0) }
                    var highlightEvents by remember { mutableStateOf(emptyList<HighlightEvent>()) }
                    var highlightScanResult by remember {
                        mutableStateOf<AutoHighlightEngine.HighlightScanResult?>(null)
                    }
                    var highlightTimeline by remember { mutableStateOf<HighlightTimeline?>(null) }
                    var highlightStyle by rememberSaveable { mutableStateOf(HighlightStyle.CINEMATIC) }
                    var isAnalyzingHighlights by remember { mutableStateOf(false) }
                    var highlightAnalysisMessage by remember { mutableStateOf<String?>(null) }
                    var isExporting by remember { mutableStateOf(false) }
                    var exportProgress by remember { mutableStateOf(0f) }
                    var exportMessage by remember { mutableStateOf<String?>(null) }
                    var previewOutputFile by remember { mutableStateOf<File?>(null) }
                    val activeTransformer = remember { AtomicReference<Transformer?>(null) }
                    val activeOutputFile = remember { AtomicReference<File?>(null) }
                    val progressHolder = remember { ProgressHolder() }
                    val screenScope = rememberCoroutineScope()
                    val autoHighlightEngine = remember { AutoHighlightEngine() }
                    val hudCalibrationStore = remember { HudCalibrationStore(applicationContext) }
                    var hudRegion by remember { mutableStateOf(hudCalibrationStore.load()) }

                    fun selectEditorVideo(uri: Uri) {
                        videoEditState = VideoEditState(sourceVideoUri = uri.toString())
                        highlightEvents = emptyList()
                        highlightScanResult = null
                        highlightTimeline = null
                        highlightAnalysisMessage = null
                        highlightStyle = HighlightStyle.CINEMATIC
                        sourceDurationMs = 0L
                        effectStartMs = 0L
                        effectEndMs = 0L
                        effectOptions = VideoEffectOptions()
                        selectedSceneId = ""
                        playheadMs = 0L
                        isEffectPreviewApplied = false
                        exportMessage = null
                        screenScope.launch(Dispatchers.IO) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(applicationContext, uri)
                                val duration = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull() ?: 0L
                                retriever.release()
                                if (duration > 0L) {
                                    withContext(Dispatchers.Main) {
                                        sourceDurationMs = duration
                                        videoEditState = videoEditState.copy(
                                            trimStartMs = 0L,
                                            trimEndMs = duration
                                        )
                                        effectEndMs = duration
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

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
                            selectEditorVideo(uri)
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
                    val musicPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                highlightTimeline = highlightTimeline?.copy(
                                    musicUri = uri.toString(),
                                    musicVolume = highlightTimeline?.musicVolume ?: 0.2f
                                )
                                isEffectPreviewApplied = false
                                exportMessage = "Music track added. Preview the highlight before exporting."
                            } catch (exception: SecurityException) {
                                exportMessage = "Android could not keep access to that music file"
                            }
                        }
                    }

                    fun analyzeAutoHighlight(style: HighlightStyle) {
                        val source = selectedVideoUri?.let(Uri::parse)
                        if (source == null) {
                            highlightAnalysisMessage = "Choose a video first"
                            return
                        }
                        var effectiveStartMs = trimStartMs
                        var effectiveEndMs = trimEndMs
                        if (effectiveEndMs <= effectiveStartMs) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(applicationContext, source)
                                val duration = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull() ?: 0L
                                retriever.release()
                                if (duration > 0L) {
                                    effectiveStartMs = 0L
                                    effectiveEndMs = duration
                                    sourceDurationMs = duration
                                    videoEditState = videoEditState.copy(
                                        trimStartMs = 0L,
                                        trimEndMs = duration
                                    )
                                    effectEndMs = duration
                                }
                            } catch (_: Exception) {}
                        }
                        if (effectiveEndMs <= effectiveStartMs) {
                            highlightAnalysisMessage = "Could not determine video duration"
                            return
                        }
                        highlightStyle = style
                        isAnalyzingHighlights = true
                        highlightScanResult = null
                        highlightEvents = emptyList()
                        highlightAnalysisMessage =
                            "Analyzing the calibrated K/D/A region and kill-feed context…"
                        screenScope.launch {
                            try {
                                val scanResult = autoHighlightEngine.analyze(
                                    context = applicationContext,
                                    sourceUri = source,
                                    trimStartMs = effectiveStartMs,
                                    trimEndMs = effectiveEndMs,
                                    hudRegion = hudRegion
                                )
                                highlightScanResult = scanResult
                                val events = scanResult.events
                                highlightEvents = events
                                val previous = highlightTimeline
                                highlightTimeline = autoHighlightEngine.createTimeline(
                                    sourceUri = source.toString(),
                                    trimStartMs = effectiveStartMs,
                                    trimEndMs = effectiveEndMs,
                                    events = events,
                                    style = style
                                ).copy(
                                    aspectRatio = previous?.aspectRatio ?: HighlightAspectRatio.VERTICAL,
                                    resolution = previous?.resolution ?: HighlightResolution.P1080,
                                    musicUri = previous?.musicUri,
                                    musicVolume = previous?.musicVolume ?: 0.2f
                                )
                                isEffectPreviewApplied = false
                                val baseline = scanResult.baselineKda
                                val current = scanResult.finalKda
                                val kdaMessage = if (baseline != null && current != null) {
                                    "My K/D/A: ${baseline.kills}/${baseline.deaths}/${baseline.assists} → " +
                                        "${current.kills}/${current.deaths}/${current.assists}. "
                                } else {
                                    "Could not establish a clear personal K/D/A baseline. "
                                }
                                highlightAnalysisMessage = when {
                                    events.isNotEmpty() ->
                                        "$kdaMessage Found ${events.size} moment(s) only after my kill count increased."
                                    baseline == null ->
                                        "Could not confidently read all three K/D/A values in the upper-right HUD. " +
                                            "Shop gold and kill-feed banners are ignored; mark moments manually."
                                    else ->
                                        "$kdaMessage My kill count never increased. " +
                                            "Death/assist changes and kill-feed banners did not create highlights."
                                }
                                selectedDestination = RecorderDestination.AUTO_HIGHLIGHT.name
                            } catch (exception: IllegalArgumentException) {
                                highlightAnalysisMessage =
                                    "Could not analyze this trim range: ${exception.localizedMessage ?: "invalid range"}"
                            } catch (exception: IOException) {
                                highlightAnalysisMessage =
                                    "Could not read the selected video: ${exception.localizedMessage ?: "storage error"}"
                            } catch (exception: SecurityException) {
                                highlightAnalysisMessage = "Android denied access to the selected video"
                            } catch (exception: com.google.mlkit.common.MlKitException) {
                                highlightAnalysisMessage =
                                    "OCR analysis failed: ${exception.localizedMessage ?: "recognizer error"}"
                            } finally {
                                isAnalyzingHighlights = false
                            }
                        }
                    }

                    fun addManualHighlightMoment(sourceTimeMs: Long) {
                        val source = selectedVideoUri
                        val effectiveStartMs = trimStartMs
                        val effectiveEndMs = if (trimEndMs > trimStartMs) trimEndMs else (sourceDurationMs.takeIf { it > 0L } ?: (sourceTimeMs + 10_000L))
                        if (source == null || effectiveEndMs <= effectiveStartMs) return
                        val eventTimeMs = sourceTimeMs.coerceIn(effectiveStartMs, effectiveEndMs - 1L)
                        val event = HighlightEvent(
                            id = UUID.randomUUID().toString(),
                            type = HighlightEventType.MANUAL_MOMENT,
                            sourceTimeMs = eventTimeMs,
                            confidence = 1f,
                            origin = HighlightEventOrigin.MANUAL
                        )
                        val events = (highlightEvents + event).sortedBy(HighlightEvent::sourceTimeMs)
                        highlightEvents = events
                        val previous = highlightTimeline
                        highlightTimeline = autoHighlightEngine.createTimeline(
                            sourceUri = source,
                            trimStartMs = effectiveStartMs,
                            trimEndMs = effectiveEndMs,
                            events = events,
                            style = highlightStyle
                        ).copy(
                            aspectRatio = previous?.aspectRatio ?: HighlightAspectRatio.LANDSCAPE,
                            resolution = previous?.resolution ?: HighlightResolution.P1080,
                            musicUri = previous?.musicUri,
                            musicVolume = previous?.musicVolume ?: 0.13f
                        )
                        highlightAnalysisMessage = "Moment marked manually. It is not an automatic event detection."
                        isEffectPreviewApplied = false
                    }

                    fun startHighlightPreview() {
                        val timeline = highlightTimeline?.takeIf {
                            it.sourceUri == selectedVideoUri &&
                                it.sourceTrimStartMs == trimStartMs &&
                                it.sourceTrimEndMs == trimEndMs
                        }
                        if (timeline == null || isExporting) {
                            exportMessage = "Generate at least one valid moment before previewing"
                            return
                        }
                        val composition = try {
                            val comp = buildHighlightComposition(timeline, settings)
                            val validation = com.mlbb.highlight.highlight.validateHighlightComposition(timeline, comp, settings)
                            if (!validation.isValid) {
                                exportMessage = "Preview validation: ${validation.issues.joinToString()}"
                            }
                            comp
                        } catch (exception: IllegalArgumentException) {
                            exportMessage = "Could not prepare preview: " +
                                (exception.localizedMessage ?: "invalid timeline")
                            return
                        }
                        val outputFile = highlightRepository.createPreviewOutputFile()
                        val transformer = Transformer.Builder(applicationContext)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(
                                object : Transformer.Listener {
                                    override fun onCompleted(
                                        composition: Composition,
                                        exportResult: ExportResult
                                    ) {
                                        activeTransformer.set(null)
                                        activeOutputFile.set(null)
                                        isExporting = false
                                        if (!outputFile.isFile || outputFile.length() == 0L) {
                                            outputFile.delete()
                                            exportMessage = "Preview finished without a playable video"
                                            return
                                        }
                                        try {
                                            previewOutputFile?.delete()
                                            previewOutputFile = outputFile
                                            playbackVideoTitle = "Auto Highlight preview"
                                            playbackVideoUri = videoUriFor(outputFile.absolutePath).toString()
                                            isEffectPreviewApplied = true
                                            exportMessage =
                                                "Preview complete. Review it, then return to edit or export."
                                        } catch (exception: IllegalArgumentException) {
                                            outputFile.delete()
                                            exportMessage = "Could not open the rendered preview: " +
                                                (exception.localizedMessage ?: "invalid output")
                                        }
                                    }

                                    override fun onError(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException
                                    ) {
                                        activeTransformer.set(null)
                                        activeOutputFile.set(null)
                                        isExporting = false
                                        outputFile.delete()
                                        exportMessage =
                                            "Preview failed (${exportException.errorCodeName}): " +
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
                        isEffectPreviewApplied = false
                        exportProgress = 0f
                        exportMessage = "Rendering full highlight preview…"
                        try {
                            transformer.start(composition, outputFile.absolutePath)
                        } catch (exception: IllegalArgumentException) {
                            activeTransformer.set(null)
                            activeOutputFile.set(null)
                            isExporting = false
                            outputFile.delete()
                            exportMessage = "Could not render preview: " +
                                (exception.localizedMessage ?: "invalid timeline")
                        } catch (exception: IllegalStateException) {
                            activeTransformer.set(null)
                            activeOutputFile.set(null)
                            isExporting = false
                            outputFile.delete()
                            exportMessage = "Could not start preview: " +
                                (exception.localizedMessage ?: "exporter is unavailable")
                        }
                    }

                    fun startExport() {
                        val inputUri = videoEditState.sourceVideoUri?.let(Uri::parse)
                        val exportScenes = videoEditState.exportableSceneEffects
                        val isAutoHighlightExport =
                            selectedDestination == RecorderDestination.AUTO_HIGHLIGHT.name
                        val currentTimeline = highlightTimeline?.takeIf {
                            it.sourceUri == selectedVideoUri &&
                                it.sourceTrimStartMs == trimStartMs &&
                                it.sourceTrimEndMs == trimEndMs
                        }
                        if (isExporting) return
                        if (settings.saveLocationUri == null) {
                            exportMessage = "Choose a gallery folder in Settings before exporting"
                            return
                        }
                        if (inputUri == null || trimEndMs <= trimStartMs) {
                            exportMessage = "Choose a video and a valid trim range before exporting"
                            return
                        }
                        if (isAutoHighlightExport && currentTimeline == null) {
                            exportMessage = "Generate or mark at least one highlight moment first"
                            return
                        }
                        if (!isAutoHighlightExport && exportScenes.isEmpty()) {
                            exportMessage = "Choose an effect for a scene before exporting"
                            return
                        }
                        if (!isEffectPreviewApplied) {
                            exportMessage = if (settings.saveLocationUri == null) {
                                "Choose a gallery folder in Settings before exporting"
                            } else {
                                "Preview the edit before exporting"
                            }
                            return
                        }

                        val autoComposition = if (isAutoHighlightExport) {
                            try {
                                val timelineToExport = currentTimeline ?: throw IllegalArgumentException(
                                    "Generate or mark at least one highlight moment first"
                                )
                                val comp = buildHighlightComposition(timelineToExport, settings)
                                val validation = com.mlbb.highlight.highlight.validateHighlightComposition(timelineToExport, comp, settings)
                                if (!validation.isValid) {
                                    exportMessage = "Validation issue: ${validation.issues.joinToString()}"
                                    return
                                }
                                comp
                            } catch (exception: IllegalArgumentException) {
                                exportMessage = "Could not prepare highlight export: " +
                                    (exception.localizedMessage ?: "invalid timeline")
                                return
                            }
                        } else {
                            null
                        }
                        val outputFile = highlightRepository.createEditedOutputFile()
                        val editedItem = if (autoComposition == null) {
                            val effectPipeline = buildVideoEffects(
                                videoEditState,
                                EffectTimestampBasis.CLIPPED_VIDEO
                            )
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
                                .setEffects(Effects(emptyList(), effectPipeline.videoEffects))
                            effectPipeline.speedProvider?.let(editedItemBuilder::setSpeed)
                            editedItemBuilder.build()
                        } else {
                            null
                        }

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
                            if (autoComposition != null) {
                                transformer.start(autoComposition, outputFile.absolutePath)
                            } else {
                                transformer.start(
                                    editedItem ?: throw IllegalStateException("Missing export item"),
                                    outputFile.absolutePath
                                )
                            }
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
                            projectionLauncher.launch(createScreenCaptureConsentIntent(projectionManager))
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
                                    projectionLauncher.launch(createScreenCaptureConsentIntent(projectionManager))
                                }
                            } else {
                                statusMessage = "Microphone permission denied; choose Silent or allow microphone access"
                            }
                        }
                    }

                    fun requestRecordingStart() {
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
                            projectionLauncher.launch(createScreenCaptureConsentIntent(projectionManager))
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
                            previewOutputFile?.delete()
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
                                        onBack = {
                                            playbackVideoUri = null
                                            previewOutputFile?.let { previewFile ->
                                                if (!previewFile.delete()) {
                                                    exportMessage =
                                                        "Preview closed, but its temporary video could not be removed"
                                                }
                                            }
                                            previewOutputFile = null
                                        },
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
                                            onOpenAutoHighlight = {
                                                selectedDestination = RecorderDestination.AUTO_HIGHLIGHT.name
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
                                                highlightEvents = emptyList()
                                                highlightScanResult = null
                                                highlightTimeline = null
                                                highlightAnalysisMessage = null
                                                videoEditState = videoEditState.copy(
                                                    trimStartMs = start.coerceAtLeast(0L),
                                                    trimEndMs = end.coerceAtLeast(start.coerceAtLeast(0L)),
                                                    sceneEffects = emptyList()
                                                )
                                                effectStartMs = 0L
                                                effectEndMs = (videoEditState.trimEndMs - videoEditState.trimStartMs).coerceAtLeast(0L)
                                                selectedSceneId = ""
                                            },
                                            onDurationChanged = { duration ->
                                                if (duration > 0L && duration != sourceDurationMs) {
                                                    sourceDurationMs = duration
                                                    if (trimEndMs == 0L || trimEndMs > duration) {
                                                        videoEditState = videoEditState.copy(trimEndMs = duration)
                                                    }
                                                    if (effectEndMs == 0L) {
                                                        effectStartMs = 0L
                                                        effectEndMs = (videoEditState.trimEndMs - videoEditState.trimStartMs).coerceAtLeast(0L)
                                                    }
                                                }
                                            },
                                            onBack = {
                                                selectedDestination = RecorderDestination.HOME.name
                                            },
                                            onNext = {
                                                selectedDestination = RecorderDestination.EFFECTS.name
                                            },
                                            onAutoHighlight = {
                                                highlightAnalysisMessage = null
                                                selectedDestination = RecorderDestination.AUTO_HIGHLIGHT.name
                                            }
                                        )

                                        RecorderDestination.AUTO_HIGHLIGHT -> AutoHighlightScreen(
                                            videoUri = selectedVideoUri,
                                            trimStartMs = trimStartMs,
                                            trimEndMs = trimEndMs,
                                            timeline = highlightTimeline,
                                            detectedEvents = highlightEvents,
                                            scanResult = highlightScanResult,
                                            hudRegion = hudRegion,
                                            isAnalyzing = isAnalyzingHighlights,
                                            analysisMessage = highlightAnalysisMessage,
                                            isPreviewReady = isEffectPreviewApplied,
                                            isExporting = isExporting,
                                            exportProgress = exportProgress,
                                            exportMessage = exportMessage,
                                            onAnalyze = ::analyzeAutoHighlight,
                                            onHudRegionChanged = { region ->
                                                hudRegion = region
                                                hudCalibrationStore.save(region)
                                                highlightScanResult = null
                                                highlightAnalysisMessage =
                                                    "HUD region updated. Run Auto Highlight to rescan using this calibration."
                                            },
                                            onManualMark = ::addManualHighlightMoment,
                                            onAcceptReview = { review ->
                                                val source = selectedVideoUri
                                                val effectiveStartMs = trimStartMs
                                                val effectiveEndMs = if (trimEndMs > trimStartMs) trimEndMs else (sourceDurationMs.takeIf { it > 0L } ?: (review.sourceTimeMs + 10_000L))
                                                if (source != null && effectiveEndMs > effectiveStartMs) {
                                                    val eventTimeMs = review.sourceTimeMs.coerceIn(effectiveStartMs, effectiveEndMs - 1L)
                                                    val event = HighlightEvent(
                                                        id = UUID.randomUUID().toString(),
                                                        type = HighlightEventType.KILL,
                                                        sourceTimeMs = eventTimeMs,
                                                        confidence = review.confidence,
                                                        origin = HighlightEventOrigin.OCR
                                                    )
                                                    val events = (highlightEvents + event).sortedBy(HighlightEvent::sourceTimeMs)
                                                    highlightEvents = events
                                                    val previous = highlightTimeline
                                                    highlightTimeline = autoHighlightEngine.createTimeline(
                                                        sourceUri = source,
                                                        trimStartMs = effectiveStartMs,
                                                        trimEndMs = effectiveEndMs,
                                                        events = events,
                                                        style = highlightStyle
                                                    ).copy(
                                                        aspectRatio = previous?.aspectRatio ?: HighlightAspectRatio.VERTICAL,
                                                        resolution = previous?.resolution ?: HighlightResolution.P1080,
                                                        musicUri = previous?.musicUri,
                                                        musicVolume = previous?.musicVolume ?: 0.2f
                                                    )
                                                    highlightScanResult = highlightScanResult?.let { result ->
                                                        result.copy(
                                                            reviewDetections = result.reviewDetections.filterNot { it.id == review.id }
                                                        )
                                                    }
                                                    highlightAnalysisMessage = "Reviewed moment added to highlight montage."
                                                    isEffectPreviewApplied = false
                                                }
                                            },
                                            onDismissReview = { reviewId ->
                                                highlightScanResult = highlightScanResult?.let { result ->
                                                    result.copy(
                                                        reviewDetections = result.reviewDetections
                                                            .filterNot { it.id == reviewId }
                                                    )
                                                }
                                            },
                                            onClipChanged = { updated ->
                                                highlightTimeline = highlightTimeline?.let { timeline ->
                                                    timeline.copy(
                                                        clips = timeline.clips.map { clip ->
                                                            if (clip.id == updated.id) updated else clip
                                                        }
                                                    )
                                                }
                                                isEffectPreviewApplied = false
                                            },
                                            onClipRemoved = { removed ->
                                                highlightTimeline = highlightTimeline?.let { timeline ->
                                                    timeline.copy(
                                                        clips = timeline.clips.filterNot { it.id == removed.id }
                                                    )
                                                }
                                                isEffectPreviewApplied = false
                                            },
                                            onStyleChanged = { style ->
                                                highlightStyle = style
                                                highlightTimeline = highlightTimeline?.let { timeline ->
                                                    timeline.copy(
                                                        style = style,
                                                        clips = timeline.clips.map { clip ->
                                                            clip.primaryEvent?.let { event ->
                                                                clip.copy(effectPreset = event.type.effectPreset(style))
                                                            } ?: clip
                                                        }
                                                    )
                                                }
                                                isEffectPreviewApplied = false
                                            },
                                            onAspectRatioChanged = { ratio ->
                                                highlightTimeline = highlightTimeline?.copy(aspectRatio = ratio)
                                                isEffectPreviewApplied = false
                                            },
                                            onResolutionChanged = { resolution ->
                                                highlightTimeline = highlightTimeline?.copy(resolution = resolution)
                                                isEffectPreviewApplied = false
                                            },
                                            onChooseMusic = {
                                                musicPickerLauncher.launch(arrayOf("audio/*"))
                                            },
                                            onMusicVolumeChanged = { volume ->
                                                highlightTimeline = highlightTimeline?.copy(
                                                    musicVolume = volume.coerceIn(0f, 1f)
                                                )
                                                isEffectPreviewApplied = false
                                            },
                                            onPreview = ::startHighlightPreview,
                                            onExport = ::startExport,
                                            onBack = {
                                                selectedDestination = RecorderDestination.TRIM.name
                                            }
                                        )

                                        RecorderDestination.EFFECTS -> EffectsScreen(
                                            editState = videoEditState,
                                            effectStartMs = effectStartMs,
                                            effectEndMs = effectEndMs,
                                            isPreviewApplied = isEffectPreviewApplied,
                                            previewRequestId = previewRequestId,
                                            selectedSceneId = selectedSceneId,
                                            playheadMs = playheadMs,
                                            onEffectRangeChanged = { start, end ->
                                                isEffectPreviewApplied = false
                                                val clipDuration = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                                                effectStartMs = start.coerceIn(0L, clipDuration)
                                                effectEndMs = end.coerceIn(
                                                    effectStartMs,
                                                    clipDuration
                                                )
                                                videoEditState = videoEditState.copy(sceneEffects = sceneEffects.map { scene ->
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
                                                })
                                            },
                                            onSceneEffectsChanged = {
                                                isEffectPreviewApplied = false
                                                videoEditState = videoEditState.copy(sceneEffects = it)
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
                                                        videoEditState = videoEditState.copy(trimEndMs = duration)
                                                    }
                                                    if (effectEndMs == 0L) {
                                                        effectStartMs = 0L
                                                        effectEndMs = (videoEditState.trimEndMs - videoEditState.trimStartMs).coerceAtLeast(0L)
                                                    }
                                                }
                                            },
                                            options = effectOptions,
                                            onOptionsChanged = {
                                                isEffectPreviewApplied = false
                                                effectOptions = it
                                                videoEditState = videoEditState.copy(sceneEffects = sceneEffects.map { scene ->
                                                    if (scene.id == selectedSceneId) scene.copy(options = it) else scene
                                                })
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

    private fun createScreenCaptureConsentIntent(
        projectionManager: MediaProjectionManager
    ): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
    }

    private fun formatElapsed(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d".format(minutes, remainingSeconds)
    }

}
