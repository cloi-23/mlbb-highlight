package com.mlbb.highlight.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.mlbb.highlight.highlight.EffectIntensity
import com.mlbb.highlight.highlight.HighlightAspectRatio
import com.mlbb.highlight.highlight.HighlightClip
import com.mlbb.highlight.highlight.HighlightEvent
import com.mlbb.highlight.highlight.HighlightEventOrigin
import com.mlbb.highlight.highlight.HighlightResolution
import com.mlbb.highlight.highlight.HighlightStyle
import com.mlbb.highlight.highlight.HighlightTimeline
import com.mlbb.highlight.highlight.AutoHighlightEngine
import com.mlbb.highlight.highlight.TextOverlay
import com.mlbb.highlight.highlight.TextPosition
import com.mlbb.highlight.highlight.buildHighlightSpeedProvider
import com.mlbb.highlight.detection.HudRegion
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun AutoHighlightScreen(
    videoUri: String?,
    trimStartMs: Long,
    trimEndMs: Long,
    timeline: HighlightTimeline?,
    detectedEvents: List<HighlightEvent>,
    scanResult: AutoHighlightEngine.HighlightScanResult?,
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
    onClipRemoved: (HighlightClip) -> Unit,
    onStyleChanged: (HighlightStyle) -> Unit,
    onAspectRatioChanged: (HighlightAspectRatio) -> Unit,
    onResolutionChanged: (HighlightResolution) -> Unit,
    onChooseMusic: () -> Unit,
    onMusicVolumeChanged: (Float) -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    val clipIds = timeline?.clips?.map(HighlightClip::id)
    var selectedClipId by remember(clipIds) {
        mutableStateOf(timeline?.clips?.firstOrNull()?.id.orEmpty())
    }
    var style by rememberSaveable(timeline?.style) {
        mutableStateOf(timeline?.style ?: HighlightStyle.CINEMATIC)
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        EditorScreenHeader(
            title = "Auto Highlight",
            onBack = onBack
        )
        Text(
            "Analyze your video to build an editable montage. Automatic moments need a stable, high-confidence kill-counter increase; uncertain moments stay here for review. Manual marking and clip editing remain available.",
            color = RecorderTheme.textSecondary
        )

        if (videoUri == null) {
            EmptyEditorCard(
                title = "Choose a gameplay video",
                message = "Import or record a video before generating highlights.",
                buttonLabel = "Back to Trim",
                onClick = onBack
            )
        } else {
            VideoPreview(
                videoUri = videoUri,
                startMs = selectedPreviewStartMs,
                endMs = selectedPreviewEndMs,
                onDurationChanged = {},
                videoEffects = previewPipeline.videoEffects,
                speedProvider = previewSpeedProvider,
                previewSeekMs = selectedClip?.let { trimStartMs + it.eventTimeMs } ?: trimStartMs,
                onPositionChanged = { playheadMs = it }
            )

            Text("Choose a style", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HighlightStyle.entries.forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = {
                            style = option
                            onStyleChanged(option)
                        },
                        label = { Text(option.label) }
                    )
                }
            }

            HudCalibrationCard(
                videoUri = videoUri,
                currentPlayheadMs = playheadMs,
                region = hudRegion,
                onRegionChanged = onHudRegionChanged
            )

            Button(
                onClick = { onAnalyze(style) },
                enabled = !isAnalyzing && !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null
                )
                Text(if (isAnalyzing) "Analyzing gameplay…" else "Auto Highlight")
            }
            if (isAnalyzing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            analysisMessage?.let { Text(it, color = RecorderTheme.cyan) }
            KdaTrackerDiagnostics(scanResult)
            scanResult?.reviewDetections?.forEach { review ->
                ReviewDetectionCard(
                    review = review,
                    onAccept = { onAcceptReview(review) },
                    onDismiss = { onDismissReview(review.id) }
                )
            }
            OutlinedButton(
                onClick = { showDetectionDebug = !showDetectionDebug },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showDetectionDebug) "Hide detection details" else "Show detection details")
            }
            if (showDetectionDebug) {
                KdaDetectionDebug(scanResult)
            }

            Text("Moment timeline", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
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
                if (detectedEvents.isEmpty()) {
                    "No personal kill increase detected yet. Play to a moment and mark it manually."
                } else {
                    "${detectedEvents.count { it.origin == HighlightEventOrigin.OCR }} OCR moments · " +
                        "${detectedEvents.count { it.origin == HighlightEventOrigin.MANUAL }} manual marks"
                },
                color = RecorderTheme.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { onManualMark(playheadMs.coerceIn(trimStartMs, trimEndMs)) },
                enabled = !isAnalyzing && !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mark Moment at ${formatEditorTime(playheadMs)}")
            }

            if (timeline != null && timeline.sourceUri == videoUri && timeline.clips.isNotEmpty()) {
                Text(
                    "Generated moments · ${formatEditorTime(timeline.durationMs)} total",
                    color = RecorderTheme.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                timeline.clips.forEach { clip ->
                    val isSelected = clip.id == selectedClipId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { selectedClipId = clip.id },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                Color(0xFF163A66)
                            } else {
                                RecorderTheme.surface
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                clip.label,
                                color = RecorderTheme.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${formatEditorTime(clip.startMs)} – ${formatEditorTime(clip.endMs)}  ·  " +
                                    clip.effectPreset.name,
                                color = RecorderTheme.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            clip.events.firstOrNull()?.let { event ->
                                Text(
                                    if (event.origin == HighlightEventOrigin.OCR) {
                                        "OCR · ${(event.confidence * 100f).roundToInt()}% confidence"
                                    } else {
                                        "Manually marked"
                                    },
                                    color = RecorderTheme.cyan,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                if (selectedClip != null) {
                    ClipEditor(
                        clip = selectedClip,
                        maxDurationMs = trimDurationMs,
                        advanced = showAdvanced,
                        onAdvancedChanged = { showAdvanced = it },
                        onClipChanged = onClipChanged,
                        onClipRemoved = {
                            onClipRemoved(selectedClip)
                            selectedClipId = timeline.clips.firstOrNull { it.id != selectedClip.id }?.id.orEmpty()
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onPreview,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isAnalyzing && !isExporting
                        ) {
                            Text("Preview full montage")
                        }
                    }
                }

                Text("Output aspect ratio", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HighlightAspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = timeline.aspectRatio == ratio,
                            onClick = { onAspectRatioChanged(ratio) },
                            label = { Text(ratio.label) }
                        )
                    }
                }
                Text("Export size", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HighlightResolution.entries.forEach { resolution ->
                        FilterChip(
                            selected = timeline.resolution == resolution,
                            onClick = { onResolutionChanged(resolution) },
                            label = { Text(resolution.label) }
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Music", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (timeline.musicUri == null) {
                                "Original game audio is kept. Import a music track to mix it into the export."
                            } else {
                                "Custom background track selected"
                            },
                            color = RecorderTheme.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onChooseMusic, modifier = Modifier.fillMaxWidth()) {
                            Text(if (timeline.musicUri == null) "Choose Music File" else "Change Music File")
                        }
                        if (timeline.musicUri != null) {
                            Text(
                                "Music volume · ${(timeline.musicVolume * 100f).roundToInt()}%",
                                color = RecorderTheme.textSecondary
                            )
                            Slider(
                                value = timeline.musicVolume,
                                onValueChange = onMusicVolumeChanged,
                                valueRange = 0f..1f
                            )
                        }
                    }
                }
            } else if (timeline != null && timeline.sourceUri == videoUri) {
                Text(
                    "No event was confidently recognized. Nothing has been invented—mark a moment manually above.",
                    color = RecorderTheme.textSecondary
                )
            }
        }

        if (isExporting) {
            LinearProgressIndicator(
                progress = { exportProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        exportMessage?.let { Text(it, color = RecorderTheme.textSecondary) }
        if (timeline?.clips?.isNotEmpty() == true) {
            Button(
                onClick = onExport,
                enabled = isPreviewReady && !isExporting && !isAnalyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        isExporting -> "Exporting…"
                        isPreviewReady -> "Export Highlight MP4"
                        else -> "Preview before Export"
                    }
                )
            }
        }
    }
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
                    detectedKda = firstReading
                    ocrStatus = if (firstReading != null) {
                        "✓ Found K/D/A: ${firstReading.kills} / ${firstReading.deaths} / ${firstReading.assists} " +
                            "(${(firstReading.confidence * 100f).roundToInt()}% confidence)"
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

            // Presets
            Text("Calibration presets", color = RecorderTheme.textSecondary, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudRegion.PRESETS.forEach { (label, presetRegion) ->
                    FilterChip(
                        selected = region == presetRegion,
                        onClick = { onRegionChanged(presetRegion) },
                        label = { Text(label) }
                    )
                }
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
                    "Timestamp: ${observation.requestedTimeMs} ms · Kill Confirmed: ${if (observation.isKillConfirmed) "YES ✓" else "NO"}",
                    color = if (observation.isKillConfirmed) RecorderTheme.cyan else RecorderTheme.textPrimary,
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
                observation.selectedEventTimestampMs?.let { timestamp ->
                    Text(
                        "Selected event timestamp: ${timestamp} ms",
                        color = RecorderTheme.cyan
                    )
                }
            }
        }
    }
}

@Composable
private fun KdaTrackerDiagnostics(
    scanResult: AutoHighlightEngine.HighlightScanResult?
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
            val baseline = scanResult?.baselineKda
            val current = scanResult?.finalKda
            val status = when {
                scanResult == null -> "Waiting for scan"
                scanResult.reviewDetections.isNotEmpty() -> "Review ${scanResult.reviewDetections.size} uncertain moment(s)"
                baseline == null -> "Waiting for stable upper-right K/D/A values"
                scanResult.events.isNotEmpty() -> "Kill Detected"
                else -> "Tracking"
            }

            Text("Status: $status", color = RecorderTheme.textSecondary)
            Text(
                "Tracked KDA: ${baseline?.let(::formatKda) ?: "Not confirmed"}" +
                    (current?.let { " → ${formatKda(it)}" } ?: ""),
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
            Text(
                "Kill Feed: ${scanResult?.lastKillFeedType?.label ?: "Not detected"}",
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
                    "Highlight: IGNORED · Kill-feed event did not increase tracked player's kills."
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
                    "Zoom · ${(clip.effectPreset.zoomAmount * 100f).roundToInt()}%",
                    color = RecorderTheme.textSecondary
                )
                Slider(
                    value = clip.effectPreset.zoomAmount,
                    onValueChange = { zoom ->
                        onClipChanged(
                            clip.copy(effectPreset = clip.effectPreset.copy(zoomAmount = zoom))
                        )
                    },
                    valueRange = 0f..0.2f
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
                TextField(
                    value = clip.textOverlay?.text.orEmpty(),
                    onValueChange = { text ->
                        val overlay = clip.textOverlay?.copy(text = text)
                            ?: TextOverlay(
                                text,
                                clip.startMs + eventTimeInClip,
                                clip.startMs + eventTimeInClip + 900L
                            )
                        onClipChanged(clip.copy(textOverlay = overlay))
                    },
                    label = { Text("Kill text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Text position", color = RecorderTheme.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextPosition.entries.forEach { position ->
                        FilterChip(
                            selected = clip.textOverlay?.position == position,
                            onClick = {
                                val overlay = clip.textOverlay ?: TextOverlay(
                                    text = "",
                                    startMs = clip.startMs + eventTimeInClip,
                                    endMs = clip.startMs + eventTimeInClip + 900L
                                )
                                onClipChanged(
                                    clip.copy(textOverlay = overlay.copy(position = position))
                                )
                            },
                            label = {
                                Text(position.name.lowercase().replaceFirstChar(Char::uppercase))
                            }
                        )
                    }
                }
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
