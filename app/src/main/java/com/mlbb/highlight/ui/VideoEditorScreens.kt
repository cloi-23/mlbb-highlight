package com.mlbb.highlight.ui

import android.net.Uri
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import com.mlbb.highlight.storage.HighlightEntity

enum class VideoEffectPreset(val label: String) {
    NONE("Original"),
    CINEMATIC("Cinematic"),
    NEON("Neon"),
    SLOW_MOTION("Slow-mo"),
    FLASH("Flash"),
    IMPACT("Impact"),
    SHAKE("Shake"),
    ZOOM("Zoom"),
    GLOW("Glow"),
    IMPACT_OVERLAY("Hit Overlay"),
    KILL_IMPACT("Kill Impact"),
    KILL_SLOWMO("Kill Slowmo"),
    SAVAGE("Savage"),
    BLUR("Blur"),
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    SATURATION("Saturation")
}

data class VideoEffectOptions(
    val preset: VideoEffectPreset = VideoEffectPreset.NONE,
    val intensity: Float = 0.65f,
    val slowMotionEnabled: Boolean = false,
    val slowMotionSpeed: Float = 0.5f,
    val shakeEnabled: Boolean = false,
    val flashEnabled: Boolean = false,
    val colorGrading: Float = 0.5f,
    val zoomScale: Float = 1.06f,
    val shakeAmount: Float = 0.5f,
    val opacity: Float = 0.7f,
    val blurAmount: Float = 0.4f,
    val brightnessAmount: Float = 0.35f,
    val contrastAmount: Float = 0.35f,
    val saturationAmount: Float = 0.35f
)

@Composable
fun TrimScreen(
    videoUri: String?,
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    savedVideos: List<HighlightEntity>,
    onPickVideo: () -> Unit,
    onSelectSavedVideo: (HighlightEntity) -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    onDurationChanged: (Long) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var showSavedVideos by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditorScreenHeader(
            title = "Trim",
            onBack = onBack,
            actionIcon = Icons.Outlined.Crop,
            onAction = {
                if (durationMs > 0L) onTrimChanged(0L, durationMs)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF07182D))
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    color = RecorderTheme.blue,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    tint = RecorderTheme.cyan,
                    modifier = Modifier.size(34.dp)
                )
                Text("Select Video", color = RecorderTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose a video from your device or MLBB gallery.",
                    color = RecorderTheme.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickVideo,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 6.dp
                        )
                    ) {
                        Text("Choose Video")
                    }
                    OutlinedButton(
                        onClick = { showSavedVideos = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 6.dp
                        )
                    ) {
                        Text("MLBB Gallery")
                    }
                }
            }
        }

        Text("Video Preview", style = MaterialTheme.typography.titleSmall, color = RecorderTheme.textPrimary)
        if (videoUri == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RecorderTheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text("Choose a video to preview", color = RecorderTheme.textSecondary)
            }
        } else {
            VideoPreview(
                videoUri = videoUri,
                startMs = startMs,
                endMs = endMs,
                onDurationChanged = onDurationChanged
            )
            Text("Trim Range", style = MaterialTheme.typography.titleSmall, color = RecorderTheme.textPrimary)
            VideoTimeline(
                context = context,
                videoUri = videoUri,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                onTrimChanged = onTrimChanged
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatEditorTime(startMs), color = RecorderTheme.textSecondary)
                Text("Duration: ${formatEditorTime(endMs - startMs)}", color = RecorderTheme.textSecondary)
                Text(formatEditorTime(durationMs), color = RecorderTheme.textSecondary)
            }
            Button(
                onClick = onNext,
                enabled = endMs > startMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Next")
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
            }
        }
    }
    if (showSavedVideos) {
        val usableVideos = savedVideos.filter { video ->
            val file = java.io.File(video.filePath)
            file.isFile && file.length() > 0L
        }
        AlertDialog(
            onDismissRequest = { showSavedVideos = false },
            containerColor = RecorderTheme.surface,
            title = { Text("MLBB Gallery", color = RecorderTheme.textPrimary) },
            text = {
                if (usableVideos.isEmpty()) {
                    Text(
                        "No saved videos yet. Record a match or import a video from your device.",
                        color = RecorderTheme.textSecondary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        usableVideos.forEach { video ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSelectSavedVideo(video)
                                        showSavedVideos = false
                                    }
                                    .background(RecorderTheme.surfaceRaised)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(video.title, color = RecorderTheme.textPrimary)
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = "Select ${video.title}",
                                    tint = RecorderTheme.cyan
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showSavedVideos = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private data class TimelineThumbnails(
    val frames: List<Bitmap> = emptyList(),
    val errorMessage: String? = null
)

@Composable
private fun VideoTimeline(
    context: android.content.Context,
    videoUri: String,
    sourceStartMs: Long = 0L,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onTrimChanged: (Long, Long) -> Unit,
    markerTimesMs: List<Long> = emptyList(),
    playheadMs: Long = 0L
) {
    val thumbnailState by produceState(
        initialValue = TimelineThumbnails(),
        key1 = videoUri,
        key2 = durationMs
    ) {
        if (durationMs <= 0L) return@produceState
        value = TimelineThumbnails()
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(videoUri))
                val frames = (0 until TIMELINE_THUMBNAIL_COUNT).mapNotNull { index ->
                    val timeUs =
                        (sourceStartMs + durationMs * index / (TIMELINE_THUMBNAIL_COUNT - 1)) * 1_000L
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        TIMELINE_THUMBNAIL_WIDTH,
                        TIMELINE_THUMBNAIL_HEIGHT
                    )
                }
                TimelineThumbnails(frames = frames)
            } catch (exception: Exception) {
                TimelineThumbnails(
                    errorMessage = exception.localizedMessage ?: "Could not load timeline thumbnails"
                )
            } finally {
                retriever.release()
            }
        }
    }
    var timelineWidthPx by remember(videoUri) { mutableFloatStateOf(0f) }
    val currentStart by rememberUpdatedState(startMs)
    val currentEnd by rememberUpdatedState(endMs)
    val currentOnTrimChanged by rememberUpdatedState(onTrimChanged)
    val minimumRangeMs = minOf(100L, durationMs.coerceAtLeast(1L))
    val updateHandle: (Float, Boolean) -> Unit = { x, movingStart ->
        if (durationMs > 0L && timelineWidthPx > 0f) {
            val fraction = (x / timelineWidthPx).coerceIn(0f, 1f)
            val requestedMs = (fraction * durationMs).roundToLong()
            if (movingStart) {
                val nextStart = requestedMs.coerceIn(
                    0L,
                    (currentEnd - minimumRangeMs).coerceAtLeast(0L)
                )
                currentOnTrimChanged(nextStart, currentEnd)
            } else {
                val nextEnd = requestedMs.coerceIn(
                    (currentStart + minimumRangeMs).coerceAtMost(durationMs),
                    durationMs
                )
                currentOnTrimChanged(currentStart, nextEnd)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RecorderTheme.surfaceRaised)
            .onSizeChanged { timelineWidthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                var dragMode = 0
                var dragOriginX = 0f
                var initialRangeStart = 0L
                var initialRangeEnd = 0L
                detectDragGestures(
                    onDragStart = { position ->
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        val videoDuration = durationMs.coerceAtLeast(1L).toFloat()
                        val startX = currentStart / videoDuration * widthPx
                        val endX = currentEnd / videoDuration * widthPx
                        val onStartHandle = kotlin.math.abs(position.x - startX) <=
                            kotlin.math.abs(position.x - endX)
                        dragMode = if (
                            position.x > startX + 24.dp.toPx() &&
                            position.x < endX - 24.dp.toPx()
                        ) {
                            2
                        } else if (onStartHandle) {
                            0
                        } else {
                            1
                        }
                        dragOriginX = position.x
                        initialRangeStart = currentStart
                        initialRangeEnd = currentEnd
                        if (dragMode != 2) updateHandle(position.x, dragMode == 0)
                    },
                    onDrag = { change, _ ->
                        if (dragMode == 2 && durationMs > 0L && timelineWidthPx > 0f) {
                            val deltaMs = (
                                (change.position.x - dragOriginX) / timelineWidthPx * durationMs
                                ).roundToLong()
                            val rangeLength = initialRangeEnd - initialRangeStart
                            val nextStart = (initialRangeStart + deltaMs)
                                .coerceIn(0L, (durationMs - rangeLength).coerceAtLeast(0L))
                            currentOnTrimChanged(nextStart, nextStart + rangeLength)
                        } else {
                            updateHandle(change.position.x, dragMode == 0)
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Row(Modifier.fillMaxWidth().height(76.dp)) {
            if (thumbnailState.frames.isNotEmpty()) {
                thumbnailState.frames.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Video timeline frame",
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                repeat(TIMELINE_THUMBNAIL_COUNT) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .background(RecorderTheme.surfaceRaised)
                    )
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            if (durationMs <= 0L) return@Canvas
            val startFraction = (startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val endFraction = (endMs.toFloat() / durationMs).coerceIn(startFraction, 1f)
            val startX = size.width * startFraction
            val endX = size.width * endFraction
            drawRect(Color(0x99041120), topLeft = Offset.Zero, size = Size(startX, size.height))
            drawRect(
                Color(0x99041120),
                topLeft = Offset(endX, 0f),
                size = Size((size.width - endX).coerceAtLeast(0f), size.height)
            )
            drawRoundRect(
                color = Color(0x22258DFF),
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(0f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
            )
            drawRoundRect(
                color = RecorderTheme.blue,
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(0f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )
            listOf(startX, endX).forEach { handleX ->
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(handleX - 3.dp.toPx(), 0f),
                    size = Size(6.dp.toPx(), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                )
                drawCircle(
                    color = RecorderTheme.blue,
                    radius = 7.dp.toPx(),
                    center = Offset(handleX, 8.dp.toPx())
                )
            }
            markerTimesMs.forEach { markerMs ->
                val markerX = size.width * (markerMs.toFloat() / durationMs).coerceIn(0f, 1f)
                drawLine(
                    color = RecorderTheme.purple,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = RecorderTheme.purple,
                    radius = 5.dp.toPx(),
                    center = Offset(markerX, 7.dp.toPx())
                )
            }
            val playheadX = size.width * (playheadMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawLine(
                color = Color.White,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
    if (thumbnailState.errorMessage != null) {
        Text(
            "Timeline thumbnails unavailable: ${thumbnailState.errorMessage}",
            color = RecorderTheme.muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun EffectsScreen(
    editState: VideoEditState,
    effectStartMs: Long,
    effectEndMs: Long,
    isPreviewApplied: Boolean,
    previewRequestId: Int,
    selectedSceneId: String,
    playheadMs: Long,
    onEffectRangeChanged: (Long, Long) -> Unit,
    onSceneEffectsChanged: (List<SceneEffect>) -> Unit,
    onSceneSelected: (SceneEffect) -> Unit,
    onPlayheadChanged: (Long) -> Unit,
    onDurationChanged: (Long) -> Unit,
    options: VideoEffectOptions,
    onOptionsChanged: (VideoEffectOptions) -> Unit,
    onApplyPreview: () -> Unit,
    isExporting: Boolean,
    exportProgress: Float,
    exportMessage: String?,
    onExport: () -> Unit,
    onGoToTrim: () -> Unit,
    onBack: () -> Unit,
    onOpenVideos: () -> Unit
) {
    val context = LocalContext.current
    val videoUri = editState.sourceVideoUri
    val startMs = editState.trimStartMs
    val endMs = editState.trimEndMs
    val sceneEffects = editState.sceneEffects
    val selectedScene = sceneEffects.firstOrNull { it.id == selectedSceneId }
    val effectPipeline = remember(editState) {
        buildVideoEffects(editState, EffectTimestampBasis.SOURCE_VIDEO)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditorScreenHeader(
            title = "Effects",
            onBack = onBack,
            actionIcon = Icons.Outlined.AutoAwesome,
            onAction = {
                if (endMs > startMs) onEffectRangeChanged(0L, endMs - startMs)
            }
        )
        Text("Select a scene, choose an effect, preview, then export.", color = RecorderTheme.textSecondary)

        if (videoUri == null) {
            EmptyEditorCard(
                title = "No video selected",
                message = "Choose a video in Trim before applying effects.",
                buttonLabel = "Go to Trim",
                onClick = onGoToTrim
            )
        } else {
            val previewSceneStartMs = startMs + (selectedScene?.startMs ?: effectStartMs)
            VideoPreview(
                videoUri = videoUri,
                startMs = startMs,
                endMs = endMs,
                onDurationChanged = onDurationChanged,
                videoEffects = effectPipeline.videoEffects,
                speedProvider = effectPipeline.speedProvider,
                onPositionChanged = { position ->
                    onPlayheadChanged((position - startMs).coerceIn(0L, (endMs - startMs).coerceAtLeast(0L)))
                },
                previewSeekMs = previewSceneStartMs,
                previewRequestId = previewRequestId
            )
            if (sceneEffects.any { it.options.preset != VideoEffectPreset.NONE }) {
                Text(
                    "Effects update the preview on their assigned scenes. Slow motion applies only inside its selected range.",
                    color = RecorderTheme.cyan,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val clipDuration = (endMs - startMs).coerceAtLeast(0L)
                        if (clipDuration > 0L) {
                            val id = "scene-${System.currentTimeMillis()}"
                            val sceneStart = (playheadMs - 200L).coerceIn(0L, clipDuration)
                            val sceneEnd = (playheadMs + 350L).coerceIn(sceneStart, clipDuration)
                            val manual = SceneEffect(
                                id = id,
                                label = "Scene ${sceneEffects.count { it.eventTimeMs == null } + 1}",
                                eventTimeMs = null,
                                startMs = sceneStart,
                                endMs = sceneEnd
                            )
                            onSceneEffectsChanged(sceneEffects + manual)
                            onSceneSelected(manual)
                        }
                    },
                    enabled = endMs > startMs && !isExporting
                ) {
                    Text("Select Scene")
                }
            }
            if (sceneEffects.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sceneEffects.forEach { scene ->
                        FilterChip(
                            selected = scene.id == selectedSceneId,
                            onClick = { onSceneSelected(scene) },
                            label = { Text(scene.label) }
                        )
                    }
                }
            }
            VideoTimeline(
                context = LocalContext.current,
                videoUri = videoUri,
                sourceStartMs = startMs,
                durationMs = (endMs - startMs).coerceAtLeast(0L),
                startMs = effectStartMs,
                endMs = effectEndMs,
                onTrimChanged = onEffectRangeChanged,
                markerTimesMs = sceneEffects.mapNotNull { it.eventTimeMs },
                playheadMs = playheadMs
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatEditorTime(effectStartMs), color = RecorderTheme.textSecondary)
                Text("Selected scene", color = RecorderTheme.cyan)
                Text(formatEditorTime(endMs - startMs), color = RecorderTheme.textSecondary)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    selectedScene?.let { "Effect · ${it.label}" } ?: "Choose a scene first",
                    style = MaterialTheme.typography.titleMedium,
                    color = RecorderTheme.textPrimary
                )
                if (selectedScene != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSceneEffectsChanged(
                                    sceneEffects.map { scene ->
                                        if (scene.id == selectedScene.id) {
                                            scene.copy(eventTimeMs = playheadMs.coerceIn(scene.startMs, scene.endMs))
                                        } else scene
                                    }
                                )
                            }
                        ) { Text("Set event") }
                        OutlinedButton(
                            onClick = {
                                val remaining = sceneEffects.filterNot { it.id == selectedScene.id }
                                onSceneEffectsChanged(remaining)
                                remaining.firstOrNull()?.let(onSceneSelected)
                            }
                        ) { Text("Remove scene") }
                    }
                }
                val effectRows = listOf(
                    listOf(VideoEffectPreset.IMPACT, VideoEffectPreset.SHAKE, VideoEffectPreset.FLASH),
                    listOf(VideoEffectPreset.SLOW_MOTION, VideoEffectPreset.ZOOM, VideoEffectPreset.GLOW),
                    listOf(VideoEffectPreset.BLUR, VideoEffectPreset.BRIGHTNESS, VideoEffectPreset.CONTRAST),
                    listOf(VideoEffectPreset.SATURATION, VideoEffectPreset.IMPACT_OVERLAY, VideoEffectPreset.SAVAGE),
                    listOf(VideoEffectPreset.NONE)
                )
                effectRows.forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                selected = options.preset == preset,
                                onClick = {
                                    onOptionsChanged(
                                        options.copy(
                                            preset = preset,
                                            slowMotionEnabled = preset == VideoEffectPreset.SLOW_MOTION,
                                            flashEnabled = preset == VideoEffectPreset.FLASH,
                                            shakeEnabled = preset == VideoEffectPreset.SHAKE
                                        )
                                    )
                                },
                                enabled = selectedScene != null,
                                label = { Text(if (preset == VideoEffectPreset.NONE) "Original" else preset.label) }
                            )
                        }
                    }
                }
                if (selectedScene != null && options.preset != VideoEffectPreset.NONE) {
                    Text("Intensity · ${(options.intensity * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.intensity,
                        onValueChange = { onOptionsChanged(options.copy(intensity = it)) },
                        valueRange = 0.1f..1f
                    )
                }
                if (selectedScene != null && options.usesSlowMotion()) {
                    Text("Slow motion · ${(options.slowMotionSpeed * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.slowMotionSpeed,
                        onValueChange = { onOptionsChanged(options.copy(slowMotionSpeed = it)) },
                        valueRange = 0.25f..0.9f
                    )
                }
                if (
                    selectedScene != null &&
                    options.preset in setOf(
                        VideoEffectPreset.ZOOM,
                        VideoEffectPreset.IMPACT,
                        VideoEffectPreset.KILL_IMPACT,
                        VideoEffectPreset.KILL_SLOWMO,
                        VideoEffectPreset.SAVAGE
                    )
                ) {
                    Text("Zoom · ${(options.zoomScale * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.zoomScale,
                        onValueChange = { onOptionsChanged(options.copy(zoomScale = it)) },
                        valueRange = 1.01f..1.2f
                    )
                }
                if (
                    selectedScene != null &&
                    options.preset in setOf(
                        VideoEffectPreset.SHAKE,
                        VideoEffectPreset.IMPACT,
                        VideoEffectPreset.KILL_IMPACT,
                        VideoEffectPreset.SAVAGE
                    )
                ) {
                    Text("Shake · ${(options.shakeAmount * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.shakeAmount,
                        onValueChange = { onOptionsChanged(options.copy(shakeAmount = it)) }
                    )
                }
                if (
                    selectedScene != null &&
                    options.preset in setOf(
                        VideoEffectPreset.FLASH,
                        VideoEffectPreset.IMPACT,
                        VideoEffectPreset.KILL_IMPACT,
                        VideoEffectPreset.IMPACT_OVERLAY,
                        VideoEffectPreset.SAVAGE
                    )
                ) {
                    Text("Opacity · ${(options.opacity * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.opacity,
                        onValueChange = { onOptionsChanged(options.copy(opacity = it)) }
                    )
                }
                if (selectedScene != null && options.preset == VideoEffectPreset.BLUR) {
                    Text("Blur · ${(options.blurAmount * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.blurAmount,
                        onValueChange = { onOptionsChanged(options.copy(blurAmount = it)) }
                    )
                }
                if (selectedScene != null && options.preset == VideoEffectPreset.BRIGHTNESS) {
                    Text("Brightness · ${(options.brightnessAmount * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.brightnessAmount,
                        onValueChange = { onOptionsChanged(options.copy(brightnessAmount = it)) }
                    )
                }
                if (selectedScene != null && options.preset == VideoEffectPreset.CONTRAST) {
                    Text("Contrast · ${(options.contrastAmount * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.contrastAmount,
                        onValueChange = { onOptionsChanged(options.copy(contrastAmount = it)) }
                    )
                }
                if (selectedScene != null && options.preset == VideoEffectPreset.SATURATION) {
                    Text("Saturation · ${(options.saturationAmount * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                    Slider(
                        value = options.saturationAmount,
                        onValueChange = { onOptionsChanged(options.copy(saturationAmount = it)) }
                    )
                }
            }
        }

        if (isExporting) {
            LinearProgressIndicator(
                progress = { exportProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(exportMessage ?: "Exporting video…", color = RecorderTheme.textSecondary)
        } else if (exportMessage != null) {
            Text(exportMessage, color = RecorderTheme.textSecondary)
        }
        if (!isExporting && exportMessage?.startsWith("Export saved") == true) {
            OutlinedButton(
                onClick = onOpenVideos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View My Videos")
            }
        }

        Button(
            onClick = onApplyPreview,
            enabled = videoUri != null && endMs > startMs &&
                sceneEffects.any {
                    it.endMs > it.startMs && it.options.preset != VideoEffectPreset.NONE
                } && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Preview Effects")
        }
        OutlinedButton(
            onClick = onExport,
            enabled = videoUri != null && endMs > startMs &&
                effectEndMs > effectStartMs && !isExporting && isPreviewApplied,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isExporting) "Exporting…" else if (isPreviewApplied) "Export MP4" else "Preview before Export")
        }
    }
}

@Composable
private fun EmptyEditorCard(
    title: String,
    message: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(RecorderTheme.surfaceRaised, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = RecorderTheme.cyan
                )
            }
            Text(title, color = RecorderTheme.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(message, color = RecorderTheme.textSecondary)
            Button(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun VideoPreview(
    videoUri: String,
    startMs: Long,
    endMs: Long,
    onDurationChanged: (Long) -> Unit,
    videoEffects: List<Effect> = emptyList(),
    speedProvider: SpeedProvider? = null,
    previewSeekMs: Long = startMs,
    onPositionChanged: (Long) -> Unit = {},
    previewRequestId: Int = 0
) {
    val context = LocalContext.current
    val currentOnDurationChanged by rememberUpdatedState(onDurationChanged)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))
            prepare()
        }
    }
    var durationMs by remember(videoUri) { mutableLongStateOf(0L) }
    var positionMs by remember(videoUri) { mutableLongStateOf(0L) }
    var playing by remember(videoUri) { mutableStateOf(false) }
    var appliedPlaybackSpeed by remember(player) { mutableFloatStateOf(1f) }
    var videoAspectRatio by remember(videoUri) { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(
        player,
        startMs,
        endMs,
        speedProvider
    ) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            currentOnPositionChanged(positionMs)
            val playerDuration = player.duration
            if (playerDuration != C.TIME_UNSET && playerDuration > 0L && durationMs != playerDuration) {
                durationMs = playerDuration
                currentOnDurationChanged(playerDuration)
            }
            if (endMs > startMs && playing && positionMs >= endMs) {
                player.pause()
                player.seekTo(startMs)
            }
            val desiredSpeed = speedProvider?.getSpeed(positionMs * 1_000L) ?: 1f
            if (desiredSpeed != appliedPlaybackSpeed) {
                player.setPlaybackSpeed(desiredSpeed)
                appliedPlaybackSpeed = desiredSpeed
            }
            delay(200L)
        }
    }

    LaunchedEffect(player, videoEffects, previewRequestId) {
        player.setVideoEffects(videoEffects)
        if (previewRequestId > 0) {
            player.seekTo(previewSeekMs.coerceIn(startMs, endMs))
            player.play()
        }
    }

    LaunchedEffect(playing, player) {
        if (playing) player.play() else player.pause()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val previewHeight = (maxWidth.value / videoAspectRatio.coerceAtLeast(0.1f))
                    .coerceIn(160f, 640f)
                    .dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(previewHeight),
                        factory = { viewContext ->
                            (android.view.LayoutInflater.from(viewContext)
                                .inflate(com.mlbb.highlight.R.layout.editor_video_player, null) as PlayerView).apply {
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                this.player = player
                            }
                        },
                        update = { it.player = player }
                    )
                    if (!playing) {
                        IconButton(
                            onClick = {
                                if (player.currentPosition < startMs || player.currentPosition >= endMs) {
                                    player.seekTo(startMs)
                                }
                                player.play()
                            },
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = "Play video",
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatEditorTime(positionMs),
                    color = RecorderTheme.textSecondary
                )
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = { player.seekTo(it.roundToLong()) },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(formatEditorTime(durationMs), color = RecorderTheme.textSecondary)
                IconButton(
                    onClick = {
                        if (playing) {
                            player.pause()
                        } else {
                            if (player.currentPosition < startMs || player.currentPosition >= endMs) {
                                player.seekTo(startMs)
                            }
                            player.play()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = RecorderTheme.blue
                    )
                }
            }
        }
    }
}

private fun formatEditorTime(milliseconds: Long): String {
    val safeMs = milliseconds.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private const val TIMELINE_THUMBNAIL_COUNT = 8
private const val TIMELINE_THUMBNAIL_WIDTH = 160
private const val TIMELINE_THUMBNAIL_HEIGHT = 90
