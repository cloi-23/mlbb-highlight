package com.mlbb.highlight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlbb.highlight.highlight.HighlightClip
import com.mlbb.highlight.highlight.EffectIntensity
import com.mlbb.highlight.highlight.withEventTimingOffset
import kotlin.math.roundToInt

private enum class DragPart { MOVE, LEFT, RIGHT }

@Composable
fun MomentsTimeline(
    clips: List<HighlightClip>,
    sourceDurationMs: Long,
    selectedClipId: String?,
    musicUri: String?,
    musicStartMs: Long,
    musicEndMs: Long?,
    outputPositionMs: Long,
    scrollState: ScrollState,
    onSelectClip: (HighlightClip) -> Unit,
    onSeekOutput: (Long) -> Unit,
    onClipChanged: (HighlightClip) -> Unit,
    onFxTimingChanged: (HighlightClip) -> Unit,
    onMoveClip: (String, Int) -> Unit,
    onMusicRangeChanged: (Long, Long) -> Unit,
    gameAudioMuted: Boolean,
    onGameAudioMutedChanged: (Boolean) -> Unit
) {
    val selectedClip = clips.firstOrNull { it.id == selectedClipId } ?: clips.firstOrNull()
    val allDurationMs = clips.sumOf(HighlightClip::durationMs).coerceAtLeast(1L)
    val allOutputStarts = remember(clips) {
        var cursor = 0L
        clips.map { clip ->
            (clip.id to cursor).also { cursor += clip.durationMs }
        }.toMap()
    }
    val selectedOutputStartMs = selectedClip?.let { allOutputStarts[it.id] } ?: 0L
    val visibleClips = listOfNotNull(selectedClip)
    val totalDurationMs = selectedClip?.durationMs?.coerceAtLeast(1L) ?: 1L
    // Show a single selected moment in a fixed-width editing window instead of
    // making the user scroll across the full montage to reach it.
    val trackWidthDp = 320.dp
    val pxPerMs = trackWidthDp.value / totalDurationMs
    val outputStarts = visibleClips.associate { it.id to 0L }
    val musicStart = (musicStartMs - selectedOutputStartMs).coerceIn(0L, totalDurationMs)
    val musicEnd = ((musicEndMs ?: allDurationMs) - selectedOutputStartMs)
        .coerceIn(musicStart, totalDurationMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RecorderTheme.surface)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TimelineLane(
            label = "",
            labelControl = {
                Box(
                    modifier = Modifier.size(20.dp).clickable {
                        onGameAudioMutedChanged(!gameAudioMuted)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (gameAudioMuted) Icons.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = if (gameAudioMuted) "Unmute game audio" else "Mute game audio",
                        tint = if (gameAudioMuted) RecorderTheme.cyan else RecorderTheme.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            },
            scrollState = scrollState,
            contentWidth = trackWidthDp,
            height = 24.dp,
            onTap = { x ->
                onSeekOutput(selectedOutputStartMs + (x / pxPerMs).roundToInt().toLong().coerceIn(0L, totalDurationMs))
            }
        ) { width ->
            Box(Modifier.width(width).height(24.dp)) {
                visibleClips.forEach { clip ->
                    val start = outputStarts[clip.id] ?: 0L
                    val clipWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(42.dp)
                    DraggableTimelineBlock(
                        label = clip.label,
                        startMs = start,
                        endMs = start + clip.durationMs,
                        pxPerMs = pxPerMs,
                        contentWidth = width,
                        blockWidth = clipWidth,
                        blockHeight = 22.dp,
                        selected = clip.id == selectedClipId,
                        fill = Color(0xFF1F78C1),
                        onClick = {
                            onSelectClip(clip)
                        },
                        onRangeChanged = { newStart, newEnd ->
                            val sourceStart = (clip.startMs + newStart - start)
                                .coerceIn(0L, (clip.endMs - 500L).coerceAtLeast(0L))
                            val sourceEnd = (clip.endMs + newEnd - (start + clip.durationMs))
                                .coerceIn(sourceStart + 500L, maxOf(sourceStart + 500L, sourceDurationMs))
                            val delta = sourceStart - clip.startMs
                            val newDuration = sourceEnd - sourceStart
                            onClipChanged(
                                clip.copy(
                                    startMs = sourceStart,
                                    endMs = sourceEnd,
                                    eventTimeMs = clip.eventTimeMs.coerceIn(sourceStart, sourceEnd),
                                    speedCurve = clip.speedCurve.copy(
                                        points = clip.speedCurve.points.map { point ->
                                            point.copy(timeMs = (point.timeMs - delta).coerceIn(0L, newDuration))
                                        }.distinctBy { it.timeMs }.sortedBy { it.timeMs }
                                    ),
                                )
                            )
                        },
                        onMove = { deltaMs ->
                            val center = (selectedOutputStartMs + start + clip.durationMs / 2L + deltaMs)
                                .coerceIn(0L, allDurationMs)
                            val target = clips.indices.minByOrNull { targetIndex ->
                                val targetStart = allOutputStarts[clips[targetIndex].id] ?: 0L
                                kotlin.math.abs(center - (targetStart + clips[targetIndex].durationMs / 2L))
                            } ?: 0
                            onMoveClip(clip.id, target)
                        }
                    )
                }
                PlayheadLine(
                    (outputPositionMs - selectedOutputStartMs).coerceIn(0L, totalDurationMs),
                    pxPerMs,
                    height = 24.dp,
                    color = RecorderTheme.cyan
                )
            }
        }
        TimelineLane(
            label = "",
            labelIcon = Icons.Outlined.MusicNote,
            scrollState = scrollState,
            contentWidth = trackWidthDp,
            height = 22.dp,
            onTap = { x ->
                onSeekOutput(selectedOutputStartMs + (x / pxPerMs).roundToInt().toLong().coerceIn(0L, totalDurationMs))
            }
        ) { width ->
            Box(Modifier.width(width).height(22.dp)) {
                if (musicUri != null && musicEnd > musicStart) {
                    DraggableTimelineBlock(
                        label = "Background music",
                        startMs = musicStart,
                        endMs = musicEnd,
                        pxPerMs = pxPerMs,
                        contentWidth = width,
                        blockWidth = ((musicEnd - musicStart) * pxPerMs).dp.coerceAtLeast(42.dp),
                        blockHeight = 20.dp,
                        selected = false,
                        fill = Color(0xFF176C62),
                        onClick = { onSeekOutput(selectedOutputStartMs + musicStart) },
                        onRangeChanged = { start, end ->
                            onMusicRangeChanged(selectedOutputStartMs + start, selectedOutputStartMs + end)
                        },
                        onMove = { delta ->
                            val duration = musicEnd - musicStart
                            val newStart = (musicStart + delta).coerceIn(0L, (totalDurationMs - duration).coerceAtLeast(0L))
                            onMusicRangeChanged(
                                selectedOutputStartMs + newStart,
                                selectedOutputStartMs + newStart + duration
                            )
                        }
                    )
                } else {
                    Text(
                        if (musicUri == null) "Choose music in Music" else "Music outside this moment",
                        modifier = Modifier.padding(start = 8.dp, top = 3.dp),
                        color = RecorderTheme.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                PlayheadLine(
                    (outputPositionMs - selectedOutputStartMs).coerceIn(0L, totalDurationMs),
                    pxPerMs,
                    height = 22.dp,
                    color = RecorderTheme.cyan
                )
            }
        }
        TimelineLane(
            label = "FX",
            scrollState = scrollState,
            contentWidth = trackWidthDp,
            height = 24.dp,
            onTap = { x ->
                onSeekOutput(selectedOutputStartMs + (x / pxPerMs).roundToInt().toLong().coerceIn(0L, totalDurationMs))
            }
        ) { width ->
            Box(Modifier.width(width).height(24.dp)) {
                visibleClips.forEach { clip ->
                    val clipOutputStart = outputStarts[clip.id] ?: 0L
                    val hasVideoFx = clip.effectPreset.intensity != EffectIntensity.OFF &&
                        clip.effectPreset.videoPreset != VideoEffectPreset.NONE
                    if (hasVideoFx) {
                        val fxWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(42.dp)
                        Box(
                            Modifier.offset(x = (clipOutputStart * pxPerMs).dp)
                                .width(fxWidth)
                                .height(11.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (clip.id == selectedClipId) RecorderTheme.cyan else Color(0xFF43526F))
                                .pointerInput(clip.id, clip.eventTimeMs, pxPerMs) {
                                    detectTapGestures { tap ->
                                        val tappedOutputMs = clipOutputStart +
                                            (tap.x / density / pxPerMs).roundToInt().toLong()
                                        val newEventTimeMs = (clip.startMs + tappedOutputMs - clipOutputStart)
                                            .coerceIn(clip.startMs, (clip.endMs - 1L).coerceAtLeast(clip.startMs))
                                        onSelectClip(clip)
                                        onFxTimingChanged(
                                            clip.withEventTimingOffset(newEventTimeMs - clip.detectedEventTimeMs)
                                        )
                                        onSeekOutput(selectedOutputStartMs + tappedOutputMs)
                                    }
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                clip.effectPreset.name,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                maxLines = 1
                            )
                        }
                    }
                    if (
                        clip.id == selectedClipId && clip.effectPreset.intensity != EffectIntensity.OFF &&
                        clip.effectPreset.videoPreset != VideoEffectPreset.NONE
                    ) {
                        DraggableFxMarker(
                            clip = clip,
                            outputStartMs = clipOutputStart,
                            pxPerMs = pxPerMs,
                            onFxTimingChanged = onFxTimingChanged
                        )
                    }
                }
                PlayheadLine(
                    (outputPositionMs - selectedOutputStartMs).coerceIn(0L, totalDurationMs),
                    pxPerMs,
                    height = 24.dp,
                    color = RecorderTheme.cyan
                )
            }
        }
    }
}

@Composable
private fun DraggableFxMarker(
    clip: HighlightClip,
    outputStartMs: Long,
    pxPerMs: Float,
    onFxTimingChanged: (HighlightClip) -> Unit
) {
    var dragDeltaMs by remember(clip.id, clip.eventTimeMs) { mutableFloatStateOf(0f) }
    val eventOutputMs = outputStartMs + (clip.eventTimeMs - clip.startMs).coerceIn(0L, clip.durationMs)
    val markerOutputMs = (eventOutputMs + dragDeltaMs.roundToInt()).coerceIn(
        outputStartMs,
        outputStartMs + clip.durationMs
    )
    Box(
        modifier = Modifier
            .offset(x = (markerOutputMs * pxPerMs).dp - 9.dp)
            .width(18.dp)
            .height(24.dp)
            .pointerInput(clip.id, clip.eventTimeMs, pxPerMs) {
                detectDragGestures(
                    onDragStart = { dragDeltaMs = 0f },
                    onDragEnd = {
                        val eventTimeMs = (clip.eventTimeMs + dragDeltaMs.roundToInt())
                            .coerceIn(clip.startMs, (clip.endMs - 1L).coerceAtLeast(clip.startMs))
                        onFxTimingChanged(
                            clip.withEventTimingOffset(eventTimeMs - clip.detectedEventTimeMs)
                        )
                    }
                ) { change, amount ->
                    val delta = amount.x / density / pxPerMs
                    val minDelta = clip.startMs - clip.eventTimeMs
                    val maxDelta = (clip.endMs - 1L) - clip.eventTimeMs
                    dragDeltaMs = (dragDeltaMs + delta).coerceIn(minDelta.toFloat(), maxDelta.toFloat())
                    change.consume()
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            "FX",
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(RecorderTheme.cyan)
                .padding(horizontal = 3.dp, vertical = 1.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(18.dp)
                .background(Color.White, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun TimelineLane(
    label: String,
    labelIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    labelControl: @Composable () -> Unit = {},
    scrollState: ScrollState,
    contentWidth: Dp,
    height: Dp,
    onTap: (Float) -> Unit,
    content: @Composable (Dp) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.width(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (labelIcon != null) {
                Icon(
                    imageVector = labelIcon,
                    contentDescription = if (label == "") null else label,
                    modifier = Modifier.size(17.dp),
                    tint = RecorderTheme.textSecondary
                )
            } else if (label.isNotBlank()) {
                Text(label, modifier = Modifier.weight(1f), color = RecorderTheme.textSecondary,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
            labelControl()
        }
        Box(
            Modifier.weight(1f).height(height).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D1930))
        ) {
            Box(
                Modifier.horizontalScroll(scrollState, reverseScrolling = false)
                    .pointerInput(contentWidth) {
                        val densityScale = density
                        detectTapGestures {
                            onTap((it.x + scrollState.value) / densityScale)
                        }
                    }
            ) { content(contentWidth) }
        }
    }
}

@Composable
private fun PlayheadLine(positionMs: Long, pxPerMs: Float, height: Dp, color: Color) {
    Box(
        Modifier.offset(x = (positionMs * pxPerMs).dp)
            .width(2.dp)
            .height(height)
            .background(color)
    )
}

@Composable
private fun DraggableTimelineBlock(
    label: String,
    startMs: Long,
    endMs: Long,
    pxPerMs: Float,
    contentWidth: Dp,
    blockWidth: Dp,
    blockHeight: Dp,
    verticalOffset: Dp = 0.dp,
    selected: Boolean,
    fill: Color,
    onClick: () -> Unit,
    onRangeChanged: (Long, Long) -> Unit,
    onMove: (Long) -> Unit
) {
    var dragDeltaMs by remember(startMs, endMs) { mutableFloatStateOf(0f) }
    var dragPart by remember(startMs, endMs) { mutableFloatStateOf(0f) }
    Box(
        Modifier.offset(x = (startMs * pxPerMs).dp, y = verticalOffset)
            .width(blockWidth)
            .height(blockHeight)
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RecorderTheme.cyan else fill)
            .pointerInput(startMs, endMs, pxPerMs) {
                detectDragGestures(
                    onDragStart = { point ->
                        dragDeltaMs = 0f
                        dragPart = when {
                            point.x < 14.dp.toPx() -> DragPart.LEFT.ordinal.toFloat()
                            point.x > size.width - 14.dp.toPx() -> DragPart.RIGHT.ordinal.toFloat()
                            else -> DragPart.MOVE.ordinal.toFloat()
                        }
                    },
                    onDragEnd = {
                        when (dragPart.roundToInt()) {
                            DragPart.LEFT.ordinal -> {
                                val start = (startMs + dragDeltaMs.roundToInt()).coerceIn(0L, endMs - 500L)
                                onRangeChanged(start, endMs)
                            }
                            DragPart.RIGHT.ordinal -> {
                                val end = (endMs + dragDeltaMs.roundToInt()).coerceIn(startMs + 500L, (contentWidth.value / pxPerMs).toLong())
                                onRangeChanged(startMs, end)
                            }
                            else -> onMove(dragDeltaMs.roundToInt().toLong())
                        }
                    }
                ) { change, amount ->
                    dragDeltaMs += amount.x / density / pxPerMs
                    change.consume()
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(20.dp).background(Color.White))
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(20.dp).background(Color.White))
        }
    }
}

