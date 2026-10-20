package com.mlbb.highlight.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mlbb.highlight.detection.KdaNumberToken
import com.mlbb.highlight.detection.KdaReading
import com.mlbb.highlight.detection.KdaSpatialParser
import com.mlbb.highlight.detection.OcrDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.mlbb.highlight.highlight.EffectIntensity
import com.mlbb.highlight.highlight.EffectPreset
import com.mlbb.highlight.highlight.HighlightAspectRatio
import com.mlbb.highlight.highlight.HighlightClip
import com.mlbb.highlight.highlight.HighlightEvent
import com.mlbb.highlight.highlight.HighlightEventOrigin
import com.mlbb.highlight.highlight.HighlightResolution
import com.mlbb.highlight.highlight.HighlightStyle
import com.mlbb.highlight.highlight.HighlightTimeline
import com.mlbb.highlight.highlight.AutoHighlightEngine
import com.mlbb.highlight.highlight.buildHighlightSpeedProvider
import com.mlbb.highlight.highlight.withEventTimingOffset
import com.mlbb.highlight.detection.HudRegion
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AutoHighlightScreen(
    videoUri: String?,
    trimStartMs: Long,
    trimEndMs: Long,
    sourceDurationMs: Long,
    onPickVideo: () -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    timeline: HighlightTimeline?,
    detectedEvents: List<HighlightEvent>,
    scanResult: AutoHighlightEngine.HighlightScanResult?,
    scanProgress: AutoHighlightEngine.KdaScanProgress?,
    hudRegion: HudRegion,
    isAnalyzing: Boolean,
    analysisMessage: String?,
    isPreviewReady: Boolean,
    isExporting: Boolean,
    exportProgress: Float,
    exportMessage: String?,
    onAnalyze: (HighlightStyle) -> Unit,
    onHudRegionChanged: (HudRegion) -> Unit,
    onManualMark: (Long) -> Unit,
    onAcceptReview: (AutoHighlightEngine.HighlightReview) -> Unit,
    onDismissReview: (String) -> Unit,
    onClipChanged: (HighlightClip) -> Unit,
    onApplyEffectToAll: (EffectPreset) -> Unit,
    onClipRemoved: (HighlightClip) -> Unit,
    onAspectRatioChanged: (HighlightAspectRatio) -> Unit,
    onResolutionChanged: (HighlightResolution) -> Unit,
    onChooseMusic: () -> Unit,
    onMusicVolumeChanged: (Float) -> Unit,
    onKillSoundEnabledChanged: (Boolean) -> Unit,
    onKillSoundVolumeChanged: (Float) -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    val clipIds = timeline?.clips?.map(HighlightClip::id)
    var selectedClipId by remember(clipIds) {
        mutableStateOf(timeline?.clips?.firstOrNull()?.id.orEmpty())
    }
    var playheadMs by remember(videoUri) { mutableLongStateOf(trimStartMs) }
    var showAdvanced by rememberSaveable(selectedClipId) { mutableStateOf(false) }
    var showDetectionDebug by rememberSaveable { mutableStateOf(false) }
    val selectedClip = timeline?.clips?.firstOrNull { it.id == selectedClipId }
    val trimDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
    val effectiveTimeline = timeline?.takeIf {
        it.sourceUri == videoUri &&
            it.sourceTrimStartMs == trimStartMs &&
            it.sourceTrimEndMs == trimEndMs
    }
    val previewState = remember(videoUri, trimStartMs, trimEndMs, effectiveTimeline) {
        VideoEditState(
            sourceVideoUri = videoUri,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            sceneEffects = effectiveTimeline?.clips.orEmpty().map { clip ->
                SceneEffect(
                    id = clip.id,
                    label = clip.label,
                    eventTimeMs = clip.eventTimeMs.coerceIn(clip.startMs, clip.endMs),
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                    options = clip.effectPreset.toVideoOptions()
                )
            }
        )
    }
    val previewPipeline = remember(previewState) {
        buildVideoEffects(previewState, EffectTimestampBasis.SOURCE_VIDEO)
    }
    val previewSpeedProvider = remember(effectiveTimeline, trimStartMs) {
        effectiveTimeline?.let { buildHighlightSpeedProvider(it, trimStartMs) }
            ?: previewPipeline.speedProvider
    }
    val selectedPreviewStartMs = selectedClip?.let { trimStartMs + it.startMs } ?: trimStartMs
    val selectedPreviewEndMs = selectedClip?.let { trimStartMs + it.endMs } ?: trimEndMs

    var activeStep by rememberSaveable { mutableStateOf("Video") }
    val steps = listOf("Video", "Highlights", "Effects", "Music", "Export")
    val previewStart = when {
        activeStep == "Video" -> 0L
        activeStep == "Effects" && selectedClip != null -> trimStartMs + selectedClip.startMs
        else -> trimStartMs
    }
    val previewEnd = when {
        activeStep == "Video" -> sourceDurationMs.coerceAtLeast(trimEndMs)
        activeStep == "Effects" && selectedClip != null -> trimStartMs + selectedClip.endMs
        else -> trimEndMs
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EditorScreenHeader(title = "Auto Highlight", onBack = onBack)
        if (videoUri == null) {
            EmptyEditorCard(
                title = "Choose a gameplay video",
                message = "Select a video here to start creating highlights.",
                buttonLabel = "Choose video",
                onClick = onPickVideo
            )
        } else {
            OutlinedButton(onClick = onPickVideo, modifier = Modifier.fillMaxWidth()) {
                Text("Choose a different video")
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                steps.forEach { step ->
                    FilterChip(
                        selected = activeStep == step,
                        onClick = { activeStep = step },
                        label = { Text(step) }
                    )
                }
                OutlinedButton(onClick = { activeStep = "Advanced" }) {
                    Text("Advanced")
                }
            }
            VideoPreview(
                videoUri = videoUri,
                startMs = previewStart,
                endMs = previewEnd,
                onDurationChanged = {},
                videoEffects = previewPipeline.videoEffects,
                speedProvider = previewSpeedProvider,
                previewSeekMs = if (activeStep == "Video") trimStartMs
                    else selectedClip?.let { trimStartMs + it.eventTimeMs } ?: trimStartMs,
                onPositionChanged = { playheadMs = it },
                previewMaxHeight = 280.dp
            )

            when (activeStep) {
                "Video" -> {
                    Text("Trim video", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                    VideoTimeline(
                        context = androidx.compose.ui.platform.LocalContext.current,
                        videoUri = videoUri,
                        sourceStartMs = 0L,
                        durationMs = sourceDurationMs.coerceAtLeast(trimEndMs),
                        startMs = trimStartMs,
                        endMs = trimEndMs,
                        onTrimChanged = onTrimChanged,
                        playheadMs = playheadMs.coerceIn(trimStartMs, trimEndMs)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Start · ${formatEditorTime(trimStartMs)}", color = RecorderTheme.textSecondary)
                        Text("End · ${formatEditorTime(trimEndMs)}", color = RecorderTheme.textSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val safeStart = playheadMs.coerceIn(0L, (trimEndMs - 100L).coerceAtLeast(0L))
                                onTrimChanged(safeStart, trimEndMs)
                            },
                            enabled = playheadMs < trimEndMs - 100L,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Set start") }
                        OutlinedButton(
                            onClick = {
                                val safeEnd = playheadMs.coerceIn(trimStartMs + 100L, sourceDurationMs.coerceAtLeast(trimStartMs + 100L))
                                onTrimChanged(trimStartMs, safeEnd)
                            },
                            enabled = playheadMs > trimStartMs + 100L,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Set end") }
                    }
                    Text(
                        "Choose the match range first. Playback controls are directly below the preview.",
                        color = RecorderTheme.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                "Highlights" -> {
                    Text("Find and review moments", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cinematic is applied automatically. Fine-tune speed and shake under Effects → Advanced settings.",
                        color = RecorderTheme.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { onAnalyze(HighlightStyle.CINEMATIC) },
                        enabled = !isAnalyzing && !isExporting,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text(if (isAnalyzing) "Scanning gameplay…" else "Scan for highlights")
                    }
                    if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    analysisMessage?.let { Text(it, color = RecorderTheme.cyan) }
                    scanResult?.reviewDetections?.forEach { review ->
                        ReviewDetectionCard(review = review, onAccept = { onAcceptReview(review) },
                            onDismiss = { onDismissReview(review.id) })
                    }
                    VideoTimeline(
                        context = androidx.compose.ui.platform.LocalContext.current,
                        videoUri = videoUri,
                        sourceStartMs = trimStartMs,
                        durationMs = trimDurationMs,
                        startMs = 0L,
                        endMs = trimDurationMs,
                        onTrimChanged = { _, _ -> },
                        markerTimesMs = detectedEvents.map { it.sourceTimeMs - trimStartMs },
                        playheadMs = (playheadMs - trimStartMs).coerceIn(0L, trimDurationMs)
                    )
                    Text(
                        if (detectedEvents.isEmpty()) "No personal kill increase detected yet. Play to a moment and mark it manually."
                        else "${detectedEvents.count { it.origin == HighlightEventOrigin.OCR }} OCR moments · ${detectedEvents.count { it.origin == HighlightEventOrigin.MANUAL }} manual marks",
                        color = RecorderTheme.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { onManualMark(playheadMs.coerceIn(trimStartMs, trimEndMs)) },
                        enabled = !isAnalyzing && !isExporting,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Add moment at ${formatEditorTime(playheadMs)}") }
                }
                "Effects" -> {
                    Text("Choose a moment to edit", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                    if (timeline?.clips?.isNotEmpty() == true && timeline.sourceUri == videoUri) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            timeline.clips.forEach { clip ->
                                FilterChip(selected = clip.id == selectedClipId,
                                    onClick = { selectedClipId = clip.id },
                                    label = { Text("${clip.label} · ${formatEditorTime(clip.eventTimeMs)}") })
                            }
                        }
                        if (selectedClip != null) {
                            ClipEditor(
                                clip = selectedClip,
                                maxDurationMs = trimDurationMs,
                                advanced = showAdvanced,
                                onAdvancedChanged = { showAdvanced = it },
                                onClipChanged = onClipChanged,
                                onApplyEffectToAll = onApplyEffectToAll,
                                onClipRemoved = {
                                    onClipRemoved(selectedClip)
                                    selectedClipId = timeline.clips.firstOrNull { it.id != selectedClip.id }?.id.orEmpty()
                                }
                            )
                            Text("Current effect · ${selectedClip.effectPreset.name}", color = RecorderTheme.cyan)
                        }
                    } else {
                        Text("Scan for highlights or add a moment manually before editing effects.",
                            color = RecorderTheme.textSecondary)
                    }
                }
                "Music" -> {
                    val currentTimeline = effectiveTimeline
                    if (currentTimeline == null) {
                        Text("Add a highlight moment before setting montage audio.", color = RecorderTheme.textSecondary)
                    } else {
                        Text("Music and moment sound", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (currentTimeline.musicUri == null) "Game audio stays on. Add your own soundtrack if you want background music."
                            else "Custom soundtrack selected · game audio stays on.",
                            color = RecorderTheme.textSecondary
                        )
                        OutlinedButton(onClick = onChooseMusic, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(if (currentTimeline.musicUri == null) "Choose music" else "Change music")
                        }
                        if (currentTimeline.musicUri != null) {
                            Text("Music volume · ${(currentTimeline.musicVolume * 100f).roundToInt()}%", color = RecorderTheme.textSecondary)
                            Slider(value = currentTimeline.musicVolume, onValueChange = onMusicVolumeChanged, valueRange = 0f..1f)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kill sound", modifier = Modifier.weight(1f), color = RecorderTheme.textPrimary)
                            androidx.compose.material3.Switch(checked = currentTimeline.enableKillSound,
                                onCheckedChange = onKillSoundEnabledChanged)
                        }
                        if (currentTimeline.enableKillSound) {
                            Text("Kill sound volume · ${(currentTimeline.killSoundVolume * 100f).roundToInt()}%", color = RecorderTheme.textSecondary)
                            Slider(value = currentTimeline.killSoundVolume, onValueChange = onKillSoundVolumeChanged, valueRange = 0f..1f)
                        }
                    }
                }
                "Export" -> {
                    val currentTimeline = effectiveTimeline
                    if (currentTimeline == null || currentTimeline.clips.isEmpty()) {
                        Text("Create or mark highlights before exporting.", color = RecorderTheme.textSecondary)
                    } else {
                        Text("Review montage", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                        currentTimeline.clips.lastOrNull()?.let { last ->
                            Text("Final moment · ${last.label} · ${formatEditorTime(last.durationMs)}",
                                color = RecorderTheme.cyan)
                        }
                        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !isAnalyzing && !isExporting) { Text("Preview full montage") }
                        Text("Output ratio", color = RecorderTheme.textPrimary)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HighlightAspectRatio.entries.forEach { ratio ->
                                FilterChip(selected = currentTimeline.aspectRatio == ratio,
                                    onClick = { onAspectRatioChanged(ratio) }, label = { Text(ratio.label) })
                            }
                        }
                        Text("Resolution", color = RecorderTheme.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HighlightResolution.entries.forEach { resolution ->
                                FilterChip(selected = currentTimeline.resolution == resolution,
                                    onClick = { onResolutionChanged(resolution) }, label = { Text(resolution.label) })
                            }
                        }
                        Button(onClick = onExport, enabled = isPreviewReady && !isExporting && !isAnalyzing,
                            modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(when { isExporting -> "Exporting…"; isPreviewReady -> "Export highlight MP4"; else -> "Preview before export" })
                        }
                    }
                }
                else -> {
                    Text("Advanced Settings", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Adjust the HUD only if automatic detection is missing or misreading your kill counter.",
                        color = RecorderTheme.textSecondary)
                    HudCalibrationCard(videoUri = videoUri, currentPlayheadMs = playheadMs,
                        region = hudRegion, onRegionChanged = onHudRegionChanged)
                    KdaTrackerDiagnostics(videoUri = videoUri, trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs, scanResult = scanResult, scanProgress = scanProgress,
                        isAnalyzing = isAnalyzing)
                    OutlinedButton(onClick = { showDetectionDebug = !showDetectionDebug },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (showDetectionDebug) "Hide detection details" else "Show detection details")
                    }
                    if (showDetectionDebug) KdaDetectionDebug(scanResult)
                    if (selectedClip != null) {
                        Text("Effect timing · ${formatSignedSeconds(selectedClip.eventTimeMs - selectedClip.detectedEventTimeMs)} from detected point",
                            color = RecorderTheme.textSecondary)
                        Slider(
                            value = ((selectedClip.eventTimeMs - selectedClip.detectedEventTimeMs) / 1_000f).coerceIn(-2f, 2f),
                            onValueChange = { seconds -> onClipChanged(selectedClip.withEventTimingOffset((seconds * 1_000f).roundToLong())) },
                            valueRange = -2f..2f,
                            steps = 15
                        )
                    }
                    Text("Detection confidence rules are fixed in the detector; configurable thresholds are not currently available.",
                        color = RecorderTheme.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (isExporting) LinearProgressIndicator(progress = { exportProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        exportMessage?.let { Text(it, color = RecorderTheme.textSecondary) }
    }
}

private fun formatSignedSeconds(milliseconds: Long): String {
    val seconds = milliseconds / 1_000f
    return "${if (seconds > 0f) "+" else ""}${"%.2f".format(seconds)}s"
}

@Composable
private fun HudCalibrationCard(
    videoUri: String?,
    currentPlayheadMs: Long,
    region: HudRegion,
    onRegionChanged: (HudRegion) -> Unit
) {
    val context = LocalContext.current
    var calibrationTimeMs by remember(videoUri) {
        mutableLongStateOf(currentPlayheadMs.coerceAtLeast(0L))
    }
    val frameBitmap by produceState<Bitmap?>(initialValue = null, key1 = videoUri, key2 = calibrationTimeMs) {
        if (videoUri == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(videoUri))
                val timeUs = calibrationTimeMs * 1_000L
                val bmp = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    1280,
                    720
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                retriever.release()
                bmp
            } catch (_: Exception) {
                null
            }
        }
    }

    val croppedBitmap = remember(frameBitmap, region) {
        val bmp = frameBitmap ?: return@remember null
        val px = region.toPixelRect(bmp.width, bmp.height) ?: return@remember null
        if (px.width() <= 0 || px.height() <= 0) return@remember null
        try {
            Bitmap.createBitmap(bmp, px.left, px.top, px.width(), px.height())
        } catch (_: Exception) {
            null
        }
    }

    var ocrStatus by remember { mutableStateOf("Adjust crop to cover your ⚔ ☠ 🤝 K/D/A") }
    var detectedKda by remember { mutableStateOf<KdaReading?>(null) }
    var verificationRequest by remember(videoUri) { mutableIntStateOf(0) }
    var verificationStatus by remember(videoUri) { mutableStateOf<String?>(null) }
    var isVerifying by remember(videoUri) { mutableStateOf(false) }
    LaunchedEffect(croppedBitmap) {
        val crop = croppedBitmap ?: return@LaunchedEffect
        ocrStatus = "Reading HUD text…"
        withContext(Dispatchers.Default) {
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val scale = 3
                val enlarged = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
                val visionText = recognizer.process(InputImage.fromBitmap(enlarged, 0)).await()
                enlarged.recycle()
                recognizer.close()
                val tokens = visionText.textBlocks.flatMap { it.lines }.flatMap { line ->
                    line.elements.mapNotNull { el ->
                        val b = el.boundingBox ?: return@mapNotNull null
                        KdaNumberToken(
                            text = el.text,
                            bounds = Rect(b.left / scale, b.top / scale, b.right / scale, b.bottom / scale),
                            confidence = el.confidence ?: line.confidence ?: 0.6f,
                            boxLeft = b.left / scale,
                            boxTop = b.top / scale,
                            boxRight = b.right / scale,
                            boxBottom = b.bottom / scale
                        )
                    }
                }
                val parsed = KdaSpatialParser.parseDetailed(tokens, crop.width, crop.height)
                val firstReading = parsed.readings.firstOrNull()
                withContext(Dispatchers.Main) {
                    detectedKda = firstReading?.takeIf { it.isTripletNumericallyValid }
                    ocrStatus = if (firstReading?.isTripletNumericallyValid == true) {
                        "✓ Found K/D/A: ${firstReading.kills} / ${firstReading.deaths} / ${firstReading.assists} " +
                            "(${(firstReading.confidence * 100f).roundToInt()}% confidence)"
                    } else if (firstReading != null) {
                        "OCR found a row, but one or more K/D/A values were not numeric. " +
                            "Choose a clearer frame or adjust the HUD crop."
                    } else {
                        val rawText = visionText.text.replace("\n", " ").trim()
                        if (rawText.isNotEmpty()) {
                            "Detected “$rawText” (need 3 numbers for K/D/A)"
                        } else {
                            "No numbers found inside crop. Move crop over your K/D/A numbers."
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ocrStatus = "OCR error: ${e.localizedMessage ?: "failed"}"
                }
            }
        }
    }
    LaunchedEffect(verificationRequest, videoUri) {
        if (verificationRequest == 0 || videoUri == null) return@LaunchedEffect
        isVerifying = true
        verificationStatus = null
        val result = withContext(Dispatchers.IO) {
            autoDetectHudAcrossFrames(context, videoUri, calibrationTimeMs)
        }
        isVerifying = false
        verificationStatus = result?.let { summary ->
            if (summary.readableFrames >= 2 && summary.positionConsistent && summary.region != null) {
                onRegionChanged(summary.region)
                "HUD found and crop positioned automatically (${summary.readableFrames}/${summary.sampledFrames} frames, " +
                    "${(summary.meanOcrConfidence * 100f).roundToInt()}% mean OCR confidence)."
            } else {
                "HUD not stable enough to lock (${summary.readableFrames}/${summary.sampledFrames} frames). " +
                    "Choose a clearer section and try again."
            }
        } ?: "Could not scan the video. Try another frame."
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("HUD Calibration (Live Video)", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Position the crop box over your top-right K/D/A counters. Scrub to the start of the match (0 kills) to verify 0 / 0 / 0 is recognized.",
                color = RecorderTheme.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            // Video Frame preview with crop overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF07182D))
            ) {
                if (frameBitmap != null) {
                    Image(
                        bitmap = frameBitmap!!.asImageBitmap(),
                        contentDescription = "Gameplay frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading video frame…", color = RecorderTheme.textSecondary)
                    }
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val left = region.left * size.width
                    val top = region.top * size.height
                    val right = region.right * size.width
                    val bottom = region.bottom * size.height

                    // Darken outside the calibration region
                    drawRect(Color(0x88000000), topLeft = Offset.Zero, size = Size(size.width, top))
                    drawRect(Color(0x88000000), topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                    drawRect(Color(0x88000000), topLeft = Offset(0f, top), size = Size(left, bottom - top))
                    drawRect(Color(0x88000000), topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                    // Cyan bounding box
                    drawRect(
                        color = Color(0xFF39D5FF),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Cropped Region magnified view + live OCR status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (detectedKda != null) Color(0xFF103322) else RecorderTheme.surfaceRaised
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (croppedBitmap != null) {
                        Image(
                            bitmap = croppedBitmap.asImageBitmap(),
                            contentDescription = "Cropped HUD",
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillHeight
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (detectedKda != null) "✓ HUD Locked" else "HUD Crop Status",
                            color = if (detectedKda != null) Color(0xFF66FF99) else RecorderTheme.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = ocrStatus,
                            color = if (detectedKda != null) Color(0xFFD4FFE2) else RecorderTheme.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Scrub bar to pick calibration frame
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Calibration frame at ${formatEditorTime(calibrationTimeMs)}",
                    color = RecorderTheme.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { calibrationTimeMs = currentPlayheadMs.coerceAtLeast(0L) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Use current playhead", style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedButton(
                onClick = { verificationRequest += 1 },
                enabled = videoUri != null && !isVerifying,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isVerifying) "Finding K/D/A in video…" else "Auto-detect HUD")
            }
            verificationStatus?.let { status ->
                Text(status, color = RecorderTheme.textSecondary, style = MaterialTheme.typography.bodySmall)
            }

            CalibrationSlider("Left", region.left, 0f..(region.right - HudRegion.MIN_REGION_SIZE)) {
                onRegionChanged(region.copy(left = it))
            }
            CalibrationSlider("Top", region.top, 0f..(region.bottom - HudRegion.MIN_REGION_SIZE)) {
                onRegionChanged(region.copy(top = it))
            }
            CalibrationSlider("Right", region.right, (region.left + HudRegion.MIN_REGION_SIZE)..1f) {
                onRegionChanged(region.copy(right = it))
            }
            CalibrationSlider("Bottom", region.bottom, (region.top + HudRegion.MIN_REGION_SIZE)..1f) {
                onRegionChanged(region.copy(bottom = it))
            }
            OutlinedButton(
                onClick = { onRegionChanged(HudRegion.DEFAULT) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset calibration")
            }
        }
    }
}

private data class HudVerificationSummary(
    val sampledFrames: Int,
    val readableFrames: Int,
    val meanOcrConfidence: Float,
    val positionConsistent: Boolean,
    val region: HudRegion?
)

private suspend fun autoDetectHudAcrossFrames(
    context: android.content.Context,
    videoUri: String,
    centerTimeMs: Long
): HudVerificationSummary? {
    val retriever = MediaMetadataRetriever()
    val detector = OcrDetector(HudRegion.DEFAULT)
    data class FrameReading(val reading: KdaReading, val width: Int, val height: Int)
    val readings = mutableListOf<FrameReading>()
    var sampledFrames = 0
    try {
        retriever.setDataSource(context, Uri.parse(videoUri))
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return null
        if (durationMs <= 0L) return null
        val sampleTimes = listOf(-300L, 0L, 300L)
            .map { (centerTimeMs + it).coerceIn(0L, durationMs - 1L) }
            .distinct()
        for (sampleTimeMs in sampleTimes) {
            val frame = retriever.getScaledFrameAtTime(
                sampleTimeMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                1280,
                720
            ) ?: continue
            sampledFrames += 1
            try {
                val candidate = detector.analyze(frame, sampleTimeMs).kdaCandidates
                    .filter { reading ->
                        reading.isTripletNumericallyValid &&
                            reading.confidence >= 0.45f &&
                            reading.bounds.centerX() > frame.width * 0.50f &&
                            reading.bounds.top < frame.height * 0.75f
                    }
                    .minByOrNull { reading ->
                        val centerX = reading.bounds.centerX().toFloat() / frame.width
                        // Prefer the right-side HUD even when the gameplay
                        // viewport is vertically letterboxed in a portrait file.
                        kotlin.math.abs(centerX - 0.83f)
                    }
                if (candidate != null) {
                    readings += FrameReading(candidate, frame.width, frame.height)
                }
            } finally {
                frame.recycle()
            }
        }
    } catch (_: Exception) {
        return null
    } finally {
        detector.close()
        retriever.release()
    }
    if (sampledFrames == 0) return null
    val positions = readings.map { frameReading ->
        val reading = frameReading.reading
        (reading.bounds.centerX().toFloat() / frameReading.width) to
            (reading.bounds.centerY().toFloat() / frameReading.height)
    }
    val meanX = positions.map { it.first }.average().toFloat().takeIf { it.isFinite() } ?: 0f
    val meanY = positions.map { it.second }.average().toFloat().takeIf { it.isFinite() } ?: 0f
    val positionConsistent = positions.isNotEmpty() && positions.all { position ->
        kotlin.math.hypot(position.first - meanX, position.second - meanY) <= 0.07f
    }
    val meanConfidence = readings.map { it.reading.confidence }
        .average().toFloat().takeIf { it.isFinite() } ?: 0f
    val normalizedBounds = readings.map { frameReading ->
        val bounds = frameReading.reading.bounds
        listOf(
            bounds.left.toFloat() / frameReading.width,
            bounds.top.toFloat() / frameReading.height,
            bounds.right.toFloat() / frameReading.width,
            bounds.bottom.toFloat() / frameReading.height
        )
    }
    fun median(values: List<Float>): Float = values.sorted().let { sorted ->
        sorted[sorted.size / 2]
    }
    val detectedRegion = if (readings.isNotEmpty() && positionConsistent) {
        HudRegion(
            left = (median(normalizedBounds.map { it[0] }) - 0.035f).coerceIn(0f, 0.97f),
            top = (median(normalizedBounds.map { it[1] }) - 0.02f).coerceIn(0f, 0.97f),
            right = (median(normalizedBounds.map { it[2] }) + 0.035f).coerceIn(0.03f, 1f),
            bottom = (median(normalizedBounds.map { it[3] }) + 0.04f).coerceIn(0.03f, 1f)
        ).let { candidate ->
            // Keep the crop valid when OCR returns a very tight glyph box.
            candidate.copy(
                right = maxOf(candidate.right, (candidate.left + HudRegion.MIN_REGION_SIZE).coerceAtMost(1f)),
                bottom = maxOf(candidate.bottom, (candidate.top + HudRegion.MIN_REGION_SIZE).coerceAtMost(1f))
            )
        }
    } else {
        null
    }
    return HudVerificationSummary(
        sampledFrames = sampledFrames,
        readableFrames = readings.size,
        meanOcrConfidence = meanConfidence,
        positionConsistent = positionConsistent,
        region = detectedRegion
    )
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChanged: (Float) -> Unit
) {
    Column {
        Text("$label · ${(value * 100f).roundToInt()}%", color = RecorderTheme.textSecondary)
        Slider(
            value = value,
            onValueChange = onValueChanged,
            valueRange = valueRange
        )
    }
}

@Composable
private fun ReviewDetectionCard(
    review: AutoHighlightEngine.HighlightReview,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF493816)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Review likely kill · ${(review.confidence * 100f).roundToInt()}% OCR confidence",
                color = RecorderTheme.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${review.previousKills} → ${review.currentKills} kills · " +
                    formatEditorTime(review.sourceTimeMs),
                color = RecorderTheme.textSecondary
            )
            Text(review.reason, color = RecorderTheme.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Add highlight") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun KdaDetectionDebug(scanResult: AutoHighlightEngine.HighlightScanResult?) {
    val observations = scanResult?.debugObservations.orEmpty().asReversed().take(12)
    Card(
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "Recent OCR samples · source presentation timestamps",
                color = RecorderTheme.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            if (observations.isEmpty()) {
                Text("Run Auto Highlight to collect sample details.", color = RecorderTheme.textSecondary)
            }
            observations.forEach { observation ->
                Text(
                    "Frame: ${observation.requestedTimeMs} ms · Counter increase confirmed: ${if (observation.counterIncreaseConfirmed) "YES ✓" else "NO"}",
                    color = if (observation.counterIncreaseConfirmed) RecorderTheme.cyan else RecorderTheme.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Raw KDA OCR: “${observation.rawOcrText ?: "N/A"}” · First Digit (K): “${observation.firstChar ?: "N/A"}”",
                    color = RecorderTheme.textSecondary
                )
                Text(
                    "Last Confirmed K: ${observation.lastConfirmedK ?: 0} · Confidence: ${((observation.confidence ?: 0f) * 100f).roundToInt()}% · Decision: ${observation.decision}",
                    color = RecorderTheme.textSecondary
                )
                observation.detectedText.forEach { text ->
                    Text(
                        "Text “${text.text}” · ${(text.confidence * 100f).roundToInt()}% · box ${text.bounds}",
                        color = RecorderTheme.textSecondary
                    )
                }
                observation.candidates.forEach { candidate ->
                    Text(
                        "K/D/A ${formatKda(candidate.kda)} · confidence " +
                            "${"%.2f".format(candidate.confidence)} · box ${candidate.bounds}",
                        color = RecorderTheme.textSecondary
                    )
                }
                if (observation.candidates.isEmpty()) {
                    Text("No candidate boxes recognized", color = RecorderTheme.textSecondary)
                }
                observation.rejectedDetections.forEach { reason ->
                    Text("Rejected: $reason", color = Color(0xFFFFC36B))
                }
                observation.visualCues.forEach { cue ->
                    Text(
                        "Visual cue [${cue.detectorSource}]: ${cue.label} · ${(cue.confidence * 100f).roundToInt()}% · box ${cue.bounds.toShortString()}",
                        color = RecorderTheme.cyan
                    )
                }
                observation.counterIncrease?.let { increase ->
                    Text(
                        "Counter ${increase.previousKills} → ${increase.currentKills} " +
                            "(+${increase.killsAdded}); first seen ${increase.firstObservedAtMs} ms, " +
                            "confirmed ${increase.confirmedAtMs} ms; previous value last seen " +
                            "${increase.previousObservedAtMs ?: "unknown"} ms",
                        color = RecorderTheme.cyan
                    )
                }
            }
        }
    }
}

@Composable
private fun KdaTrackerDiagnostics(
    videoUri: String,
    trimStartMs: Long,
    trimEndMs: Long,
    scanResult: AutoHighlightEngine.HighlightScanResult?,
    scanProgress: AutoHighlightEngine.KdaScanProgress?,
    isAnalyzing: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "KDA Tracker · Shop excluded",
                color = RecorderTheme.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            if (isAnalyzing && scanProgress != null) {
                Text(
                    "Scanning sample ${scanProgress.sampleIndex} / ${scanProgress.sampleCount} · " +
                        formatEditorTime(scanProgress.sourceTimeMs),
                    color = RecorderTheme.cyan,
                    fontWeight = FontWeight.Medium
                )
                VideoPreview(
                    videoUri = videoUri,
                    startMs = trimStartMs,
                    endMs = trimEndMs,
                    onDurationChanged = {},
                    previewSeekMs = scanProgress.sourceTimeMs,
                    previewRequestId = scanProgress.sampleIndex,
                    autoPlay = false,
                    showPlaybackControls = false
                )
            }
            val baseline = scanResult?.baselineKda
            val current = scanResult?.finalKda
            val liveKda = scanProgress?.currentKda
            val observedKdaStatus = if (scanProgress?.observedKda == liveKda) "confirmed" else "verifying"
            val latestHudKills = scanProgress?.observedKda?.kills ?: liveKda?.kills
            val pendingHudKillDelta = if (latestHudKills != null && liveKda != null) {
                (latestHudKills - liveKda.kills).coerceAtLeast(0)
            } else {
                0
            }
            val status = when {
                isAnalyzing && scanProgress != null -> "Scanning gameplay"
                isAnalyzing -> "Preparing scan"
                scanResult == null -> "Waiting for scan"
                scanResult.reviewDetections.isNotEmpty() -> "Review ${scanResult.reviewDetections.size} uncertain moment(s)"
                baseline == null -> "Waiting for stable K/D/A values in the calibrated HUD region"
                scanResult.events.isNotEmpty() -> "Kill Detected"
                else -> "Tracking"
            }

            Text("Status: $status", color = RecorderTheme.textSecondary)
            Text(
                "Kill moments confirmed: ${scanProgress?.confirmedKillCount ?: scanResult?.events?.size ?: 0}",
                color = RecorderTheme.cyan,
                fontWeight = FontWeight.SemiBold
            )
            if (scanProgress != null) {
                Text(
                    "Kills on HUD: ${latestHudKills ?: "—"}" +
                        if (pendingHudKillDelta > 0) " (+$pendingHudKillDelta pending verification)" else "",
                    color = RecorderTheme.cyan,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Starting KDA: ${scanProgress.baselineKda?.let(::formatKda) ?: "Not confirmed yet"}",
                    color = RecorderTheme.textSecondary
                )
                Text(
                    "Latest HUD read ($observedKdaStatus): ${scanProgress.observedKda?.let(::formatKda) ?: "No valid reading"}",
                    color = RecorderTheme.cyan
                )
                Text(
                    "Confirmed live KDA: ${liveKda?.let(::formatKda) ?: "Reading HUD…"}",
                    color = RecorderTheme.textSecondary
                )
                if (scanProgress.lastKillFrom != null && scanProgress.lastKillTo != null) {
                    Text(
                        "Latest kill check: ${scanProgress.lastKillFrom} → ${scanProgress.lastKillTo}" +
                            (scanProgress.confidence?.let { " · ${ (it * 100).roundToInt() }%" } ?: ""),
                        color = RecorderTheme.textSecondary
                    )
                }
                scanProgress.counterIncrease?.let { change ->
                    Text(
                        "Counter change observed: ${change.previousKills} → ${change.currentKills} " +
                            "(+${change.killsAdded}) at ${formatEditorTime(change.firstObservedAtMs)}; " +
                            "confirmed at ${formatEditorTime(change.confirmedAtMs)}",
                        color = RecorderTheme.cyan
                    )
                }
                Text("Check: ${scanProgress.decision}", color = RecorderTheme.textSecondary)
            }
            Text(
                "Tracked KDA: ${baseline?.let(::formatKda) ?: "Not confirmed"}" +
                    ((if (isAnalyzing) liveKda else current)?.let { " → ${formatKda(it)}" } ?: ""),
                color = RecorderTheme.textSecondary
            )
            Text(
                "Previous Kills: ${scanResult?.previousKills?.toString() ?: "—"}",
                color = RecorderTheme.textSecondary
            )
            Text(
                "Current Kills: ${scanResult?.currentKills?.toString() ?: "—"}",
                color = RecorderTheme.textSecondary
            )
            val increase = scanResult?.killIncrease ?: 0
            Text(
                "Kill Increase: ${if (increase > 0) "+$increase" else "0"}",
                color = RecorderTheme.textSecondary
            )
            Text(
                if (increase > 0) {
                    "Highlight: CREATED · ${scanResult?.events?.lastOrNull()?.type?.label ?: "Kill"}"
                } else if (baseline == null) {
                    "Highlight Trigger: Waiting for tracked KDA"
                } else {
                    "Highlight: No verified increase in your KDA kill counter."
                },
                color = if (increase > 0) RecorderTheme.cyan else RecorderTheme.textSecondary
            )
        }
    }
}

private fun formatKda(snapshot: com.mlbb.highlight.detection.KdaSnapshot): String =
    "${snapshot.kills} / ${snapshot.deaths} / ${snapshot.assists}"

@Composable
private fun ClipEditor(
    clip: HighlightClip,
    maxDurationMs: Long,
    advanced: Boolean,
    onAdvancedChanged: (Boolean) -> Unit,
    onClipChanged: (HighlightClip) -> Unit,
    onApplyEffectToAll: (EffectPreset) -> Unit,
    onClipRemoved: () -> Unit
) {
    val eventTimeInClip = (clip.eventTimeMs - clip.startMs).coerceIn(0L, clip.durationMs)
    Card(
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Edit moment", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Start · ${formatEditorTime(clip.startMs)}",
                color = RecorderTheme.textSecondary
            )
            Slider(
                value = clip.startMs.toFloat(),
                onValueChange = { value ->
                    val newStart = value.roundToLong()
                        .coerceIn(0L, (clip.endMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0L))
                    onClipChanged(
                        clip.copy(
                            startMs = newStart,
                            speedCurve = clip.speedCurve.copy(
                                points = clip.speedCurve.points.map { point ->
                                    point.copy(
                                        timeMs = (point.timeMs + clip.startMs - newStart)
                                            .coerceIn(0L, clip.endMs - newStart)
                                    )
                                }
                            )
                        )
                    )
                },
                valueRange = 0f..maxDurationMs.coerceAtLeast(1L).toFloat()
            )
            Text(
                "End · ${formatEditorTime(clip.endMs)}",
                color = RecorderTheme.textSecondary
            )
            Slider(
                value = clip.endMs.toFloat(),
                onValueChange = { value ->
                    val newEnd = value.roundToLong()
                        .coerceIn(
                            (clip.startMs + MIN_CLIP_DURATION_MS).coerceAtMost(maxDurationMs),
                            maxDurationMs
                        )
                    onClipChanged(
                        clip.copy(
                            endMs = newEnd,
                            speedCurve = clip.speedCurve.copy(
                                points = clip.speedCurve.points.map { point ->
                                    point.copy(
                                        timeMs = point.timeMs.coerceIn(
                                            0L,
                                            newEnd - clip.startMs
                                        )
                                    )
                                }
                            )
                        )
                    )
                },
                valueRange = 0f..maxDurationMs.coerceAtLeast(1L).toFloat()
            )
            Text("Effect intensity", color = RecorderTheme.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    "Kill" to VideoEffectPreset.KILL_IMPACT,
                    "Slow-mo" to VideoEffectPreset.KILL_SLOWMO,
                    "Savage" to VideoEffectPreset.SAVAGE,
                    "Original" to VideoEffectPreset.NONE
                ).forEach { (label, preset) ->
                    FilterChip(
                        selected = clip.effectPreset.videoPreset == preset,
                        onClick = {
                            val intensity = when {
                                preset == VideoEffectPreset.NONE -> EffectIntensity.OFF
                                clip.effectPreset.intensity == EffectIntensity.OFF -> EffectIntensity.MEDIUM
                                else -> clip.effectPreset.intensity
                            }
                            onClipChanged(
                                clip.copy(
                                    effectPreset = clip.effectPreset.copy(
                                        name = if (preset == VideoEffectPreset.NONE) {
                                            "Original"
                                        } else {
                                            "MLBB Cinematic Kill"
                                        },
                                        videoPreset = preset,
                                        intensity = intensity
                                    )
                                )
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EffectIntensity.entries.forEach { intensity ->
                    FilterChip(
                        selected = clip.effectPreset.intensity == intensity,
                        onClick = {
                            onClipChanged(
                                clip.copy(
                                    effectPreset = clip.effectPreset.copy(intensity = intensity)
                                )
                            )
                        },
                        label = { Text(intensity.label) }
                    )
                }
            }
            OutlinedButton(
                onClick = { onApplyEffectToAll(clip.effectPreset) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply effect to all moments")
            }
            OutlinedButton(
                onClick = { onAdvancedChanged(!advanced) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings")
            }
            if (advanced) {
                Text("Speed curve", color = RecorderTheme.textSecondary)
                SpeedCurveEditor(
                    clip = clip,
                    eventTimeMs = eventTimeInClip,
                    onSpeedChanged = { speed, curve ->
                        onClipChanged(
                            clip.copy(
                                effectPreset = clip.effectPreset.copy(slowMotionSpeed = speed),
                                speedCurve = curve
                            )
                        )
                    }
                )
                Text(
                    "Impact speed · ${(clip.effectPreset.slowMotionSpeed * 100f).roundToInt()}%",
                    color = RecorderTheme.textSecondary
                )
                Slider(
                    value = clip.effectPreset.slowMotionSpeed,
                    onValueChange = { speed ->
                        val curve = clip.speedCurve.copy(
                            points = clip.speedCurve.points.map { it.copy() }.let { points ->
                                val closestIndex = points.indices.minByOrNull { index ->
                                    kotlin.math.abs(points[index].timeMs - eventTimeInClip)
                                }
                                points.mapIndexed { index, point ->
                                    if (index == closestIndex) point.copy(speed = speed) else point
                                }
                            }
                        )
                        onClipChanged(
                            clip.copy(
                                effectPreset = clip.effectPreset.copy(slowMotionSpeed = speed),
                                speedCurve = curve
                            )
                        )
                    },
                    valueRange = 0.25f..1.5f
                )
                Text(
                    "Shake · ${(clip.effectPreset.shakeAmount * 100f).roundToInt()}%",
                    color = RecorderTheme.textSecondary
                )
                Slider(
                    value = clip.effectPreset.shakeAmount,
                    onValueChange = { shake ->
                        onClipChanged(
                            clip.copy(effectPreset = clip.effectPreset.copy(shakeAmount = shake))
                        )
                    },
                    valueRange = 0f..1f
                )
            }
            OutlinedButton(onClick = onClipRemoved, modifier = Modifier.fillMaxWidth()) {
                Text("Remove moment")
            }
        }
    }
}

@Composable
private fun SpeedCurveEditor(
    clip: HighlightClip,
    eventTimeMs: Long,
    onSpeedChanged: (Float, com.mlbb.highlight.highlight.SpeedCurve) -> Unit
) {
    val points = clip.speedCurve.points.sortedBy { it.timeMs }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RecorderTheme.surfaceRaised)
            .pointerInput(clip.id, clip.speedCurve, eventTimeMs) {
                detectDragGestures { change, _ ->
                    val closestIndex = points.indices.minByOrNull { index ->
                        kotlin.math.abs(points[index].timeMs - eventTimeMs)
                    } ?: return@detectDragGestures
                    val speed = (
                        1.5f - (change.position.y / size.height.coerceAtLeast(1)) * 1.25f
                        ).coerceIn(0.25f, 1.5f)
                    val updatedPoints = points.mapIndexed { index, point ->
                        if (index == closestIndex) point.copy(speed = speed) else point
                    }
                    onSpeedChanged(
                        speed,
                        clip.speedCurve.copy(points = updatedPoints)
                    )
                    change.consume()
                }
            }
    ) {
        val maxDuration = clip.durationMs.coerceAtLeast(1L).toFloat()
        val minSpeed = 0.25f
        val maxSpeed = 1.5f
        val yForSpeed: (Float) -> Float = { speed ->
            size.height - ((speed - minSpeed) / (maxSpeed - minSpeed)) * size.height
        }
        listOf(0.5f, 1f, 1.5f).forEach { speed ->
            val y = yForSpeed(speed)
            drawLine(
                color = RecorderTheme.muted.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = (point.timeMs / maxDuration) * size.width
            val y = yForSpeed(point.speed.coerceIn(minSpeed, maxSpeed))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = RecorderTheme.cyan, style = Stroke(width = 3.dp.toPx()))
        points.forEach { point ->
            drawCircle(
                color = if (kotlin.math.abs(point.timeMs - eventTimeMs) < 250L) {
                    Color.White
                } else {
                    RecorderTheme.cyan
                },
                radius = 4.dp.toPx(),
                center = Offset(
                    (point.timeMs / maxDuration) * size.width,
                    yForSpeed(point.speed.coerceIn(minSpeed, maxSpeed))
                )
            )
        }
    }
    Text(
        "Drag the curve to change impact speed",
        color = RecorderTheme.muted,
        style = MaterialTheme.typography.bodySmall
    )
}

private const val MIN_CLIP_DURATION_MS = 500L
