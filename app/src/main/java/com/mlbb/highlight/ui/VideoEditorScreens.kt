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
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Vibration
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
import androidx.compose.material3.Switch
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
    SLOW_MOTION("Slow Motion"),
    FLASH("Flash")
}

data class VideoEffectOptions(
    val preset: VideoEffectPreset = VideoEffectPreset.NONE,
    val intensity: Float = 0.65f,
    val slowMotionEnabled: Boolean = false,
    val slowMotionSpeed: Float = 0.5f,
    val shakeEnabled: Boolean = false,
    val flashEnabled: Boolean = false,
    val colorGrading: Float = 0.5f
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
    onTrimChanged: (Long, Long) -> Unit
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
                var movingStart = true
                detectDragGestures(
                    onDragStart = { position ->
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        val videoDuration = durationMs.coerceAtLeast(1L).toFloat()
                        val startX = currentStart / videoDuration * widthPx
                        val endX = currentEnd / videoDuration * widthPx
                        movingStart = kotlin.math.abs(position.x - startX) <=
                            kotlin.math.abs(position.x - endX)
                        updateHandle(position.x, movingStart)
                    },
                    onDrag = { change, _ ->
                        updateHandle(change.position.x, movingStart)
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
    videoUri: String?,
    startMs: Long,
    endMs: Long,
    effectStartMs: Long,
    effectEndMs: Long,
    isPreviewApplied: Boolean,
    appliedEffectOptions: VideoEffectOptions,
    appliedEffectStartMs: Long,
    appliedEffectEndMs: Long,
    previewRequestId: Int,
    onEffectRangeChanged: (Long, Long) -> Unit,
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditorScreenHeader(
            title = "Effects",
            onBack = onBack,
            actionIcon = Icons.Outlined.AutoAwesome,
            onAction = {
                if (endMs > startMs) onEffectRangeChanged(0L, endMs - startMs)
            }
        )
        Text(
            "Apply effects to preview the selected scene first. Export is a separate save step.",
            color = RecorderTheme.textSecondary
        )

        if (videoUri == null) {
            EmptyEditorCard(
                title = "No video selected",
                message = "Choose a video in Trim before applying effects.",
                buttonLabel = "Go to Trim",
                onClick = onGoToTrim
            )
        } else {
            val previewSceneStartMs = startMs + appliedEffectStartMs
            val previewSceneEndMs = startMs + appliedEffectEndMs
            val previewVideoEffects = remember(
                isPreviewApplied,
                appliedEffectOptions,
                previewSceneStartMs,
                previewSceneEndMs
            ) {
                if (isPreviewApplied && previewSceneEndMs > previewSceneStartMs) {
                    createSceneVideoEffects(
                        appliedEffectOptions,
                        previewSceneStartMs * 1_000L,
                        previewSceneEndMs * 1_000L
                    )
                } else {
                    emptyList()
                }
            }
            VideoPreview(
                videoUri = videoUri,
                startMs = startMs,
                endMs = endMs,
                onDurationChanged = onDurationChanged,
                videoEffects = previewVideoEffects,
                slowMotionEnabled = isPreviewApplied &&
                    (appliedEffectOptions.slowMotionEnabled ||
                        appliedEffectOptions.preset == VideoEffectPreset.SLOW_MOTION),
                slowMotionStartMs = previewSceneStartMs,
                slowMotionEndMs = previewSceneEndMs,
                slowMotionSpeed = appliedEffectOptions.slowMotionSpeed,
                previewRequestId = previewRequestId
            )
            if (isPreviewApplied) {
                Text(
                    "Preview effects are active on the selected scene. Slow motion temporarily changes playback speed only inside that range.",
                    color = RecorderTheme.cyan,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text("Effect Scene", style = MaterialTheme.typography.titleSmall, color = RecorderTheme.textPrimary)
            Text(
                "Drag the handles to choose where enabled effects should appear.",
                style = MaterialTheme.typography.bodySmall,
                color = RecorderTheme.textSecondary
            )
            VideoTimeline(
                context = LocalContext.current,
                videoUri = videoUri,
                sourceStartMs = startMs,
                durationMs = (endMs - startMs).coerceAtLeast(0L),
                startMs = effectStartMs,
                endMs = effectEndMs,
                onTrimChanged = onEffectRangeChanged
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatEditorTime(effectStartMs), color = RecorderTheme.textSecondary)
                Text(
                    "Scene ${formatEditorTime(effectEndMs - effectStartMs)} / Clip ${formatEditorTime(endMs - startMs)}",
                    color = RecorderTheme.cyan
                )
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Effect presets", style = MaterialTheme.typography.titleMedium, color = RecorderTheme.textPrimary)
                FilterChip(
                    selected = options.preset == VideoEffectPreset.NONE,
                    onClick = { onOptionsChanged(VideoEffectOptions()) },
                    label = { Text(VideoEffectPreset.NONE.label) },
                    leadingIcon = { Icon(Icons.Outlined.Movie, contentDescription = null) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        VideoEffectPreset.CINEMATIC to Icons.Outlined.Movie,
                        VideoEffectPreset.NEON to Icons.Outlined.StarOutline
                    ).forEach { (preset, icon) ->
                        FilterChip(
                            selected = options.preset == preset,
                            onClick = { onOptionsChanged(options.copy(preset = preset)) },
                            label = { Text(preset.label) },
                            leadingIcon = { Icon(icon, contentDescription = null) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        VideoEffectPreset.SLOW_MOTION to Icons.Outlined.Timer,
                        VideoEffectPreset.FLASH to Icons.Outlined.FlashOn
                    ).forEach { (preset, icon) ->
                        FilterChip(
                            selected = options.preset == preset,
                            onClick = {
                                onOptionsChanged(
                                    when (preset) {
                                        VideoEffectPreset.SLOW_MOTION -> options.copy(
                                            preset = preset,
                                            slowMotionEnabled = true
                                        )
                                        VideoEffectPreset.FLASH -> options.copy(
                                            preset = preset,
                                            flashEnabled = true
                                        )
                                        else -> options.copy(preset = preset)
                                    }
                                )
                            },
                            label = { Text(preset.label) },
                            leadingIcon = { Icon(icon, contentDescription = null) }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReferenceIconTile(
                        image = Icons.Outlined.GraphicEq,
                        tint = RecorderTheme.purple,
                        tileSize = 42.dp,
                        iconSize = 23.dp
                    )
                    Column {
                        Text("Intensity", color = RecorderTheme.textPrimary)
                        Text(
                            "${(options.intensity * 100).roundToLong()}%",
                            color = RecorderTheme.textSecondary
                        )
                    }
                }
                Slider(
                    value = options.intensity,
                    onValueChange = { onOptionsChanged(options.copy(intensity = it)) },
                    valueRange = 0f..1f
                )
                EffectSwitchRow(
                    title = "Kill-moment slow motion",
                    subtitle = "Slow down only the selected scene.",
                    icon = Icons.Outlined.Timer,
                    checked = options.slowMotionEnabled,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                slowMotionEnabled = it,
                                preset = if (!it && options.preset == VideoEffectPreset.SLOW_MOTION) {
                                    VideoEffectPreset.NONE
                                } else {
                                    options.preset
                                }
                            )
                        )
                    }
                )
                if (options.slowMotionEnabled) {
                    Text(
                        "Speed  ${(options.slowMotionSpeed * 100).roundToLong()}%",
                        color = RecorderTheme.textSecondary
                    )
                    Slider(
                        value = options.slowMotionSpeed,
                        onValueChange = { onOptionsChanged(options.copy(slowMotionSpeed = it)) },
                        valueRange = 0.25f..0.9f
                    )
                    Text(
                        "Only the selected scene slows down. Slower speeds make that scene and the final export longer.",
                        color = RecorderTheme.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                EffectSwitchRow(
                    title = "Kill shake",
                    subtitle = "Add a camera shake to the selected scene.",
                    icon = Icons.Outlined.Vibration,
                    checked = options.shakeEnabled,
                    onCheckedChange = { onOptionsChanged(options.copy(shakeEnabled = it)) }
                )
                EffectSwitchRow(
                    title = "Flash look",
                    subtitle = "Flash at the beginning of the selected scene.",
                    icon = Icons.Outlined.FlashOn,
                    checked = options.flashEnabled,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                flashEnabled = it,
                                preset = if (!it && options.preset == VideoEffectPreset.FLASH) {
                                    VideoEffectPreset.NONE
                                } else {
                                    options.preset
                                }
                            )
                        )
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReferenceIconTile(
                        image = Icons.Outlined.Palette,
                        tint = RecorderTheme.purple,
                        tileSize = 42.dp,
                        iconSize = 23.dp
                    )
                    Text("Color grading", color = RecorderTheme.textSecondary)
                }
                Text("${(options.colorGrading * 100).roundToLong()}%", color = RecorderTheme.textSecondary)
                Slider(
                    value = options.colorGrading,
                    onValueChange = { onOptionsChanged(options.copy(colorGrading = it)) },
                    valueRange = 0f..1f
                )
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
                effectEndMs > effectStartMs && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply Effects to Preview")
        }
        OutlinedButton(
            onClick = onExport,
            enabled = videoUri != null && endMs > startMs &&
                effectEndMs > effectStartMs && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isExporting) "Exporting…" else "Export MP4")
        }
    }
}

@Composable
private fun EffectSwitchRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReferenceIconTile(
                    image = icon,
                    tint = RecorderTheme.purple,
                    tileSize = 40.dp,
                    iconSize = 22.dp
                )
                Text(title, color = RecorderTheme.textPrimary)
            }
            Text(
                subtitle,
                modifier = Modifier.padding(start = 48.dp),
                color = RecorderTheme.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    slowMotionEnabled: Boolean = false,
    slowMotionStartMs: Long = 0L,
    slowMotionEndMs: Long = 0L,
    slowMotionSpeed: Float = 1f,
    previewRequestId: Int = 0
) {
    val context = LocalContext.current
    val currentOnDurationChanged by rememberUpdatedState(onDurationChanged)
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

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
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
        slowMotionEnabled,
        slowMotionStartMs,
        slowMotionEndMs,
        slowMotionSpeed
    ) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val playerDuration = player.duration
            if (playerDuration != C.TIME_UNSET && playerDuration > 0L && durationMs != playerDuration) {
                durationMs = playerDuration
                currentOnDurationChanged(playerDuration)
            }
            if (endMs > startMs && playing && positionMs >= endMs) {
                player.pause()
                player.seekTo(startMs)
            }
            val desiredSpeed = if (
                slowMotionEnabled &&
                positionMs >= slowMotionStartMs &&
                positionMs < slowMotionEndMs
            ) {
                slowMotionSpeed.coerceIn(0.25f, 0.9f)
            } else {
                1f
            }
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
            player.seekTo(slowMotionStartMs.coerceIn(startMs, endMs))
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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
