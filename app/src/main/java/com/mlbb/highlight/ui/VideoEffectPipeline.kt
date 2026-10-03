package com.mlbb.highlight.ui

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.GlEffect

enum class EffectTimestampBasis {
    SOURCE_VIDEO,
    CLIPPED_VIDEO
}

data class VideoEffectPipeline(
    val videoEffects: List<GlEffect>,
    val speedProvider: SpeedProvider?
)

fun VideoEditState.timestampOffsetUs(timestampBasis: EffectTimestampBasis): Long =
    when (timestampBasis) {
        EffectTimestampBasis.SOURCE_VIDEO -> trimStartMs.coerceAtLeast(0L) * 1_000L
        EffectTimestampBasis.CLIPPED_VIDEO -> 0L
    }

fun buildVideoEffects(
    editState: VideoEditState,
    timestampBasis: EffectTimestampBasis
): VideoEffectPipeline {
    val offsetUs = editState.timestampOffsetUs(timestampBasis)
    val scenes = editState.exportableSceneEffects
    val videoEffects = scenes.flatMap { scene ->
        val lastActiveMillisecond = (scene.endMs - 1L).coerceAtLeast(scene.startMs)
        val eventTimeMs = (scene.eventTimeMs ?: (scene.startMs + 200L))
            .coerceIn(scene.startMs, lastActiveMillisecond)
        createSceneVideoEffects(
            options = scene.options,
            sceneStartUs = offsetUs + scene.startMs * 1_000L,
            sceneEndUs = offsetUs + scene.endMs * 1_000L,
            eventTimeUs = offsetUs + eventTimeMs.coerceIn(scene.startMs, scene.endMs) * 1_000L
        )
    }
    val slowMotionScenes = scenes.filter { it.options.usesSlowMotion() }
    val speedProvider = if (slowMotionScenes.isEmpty()) {
        null
    } else {
        object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float =
                slowMotionScenes
                    .filter { scene ->
                        val startUs = offsetUs + scene.startMs * 1_000L
                        val endUs = offsetUs + scene.endMs * 1_000L
                        timeUs >= startUs && timeUs < endUs
                    }
                    .minOfOrNull { it.options.slowMotionSpeed.coerceIn(0.25f, 0.9f) }
                    ?: 1f

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                slowMotionScenes
                    .flatMap { scene ->
                        listOf(
                            offsetUs + scene.startMs * 1_000L,
                            offsetUs + scene.endMs * 1_000L
                        )
                    }
                    .filter { it > timeUs }
                    .minOrNull()
                    ?: C.TIME_UNSET
        }
    }
    return VideoEffectPipeline(videoEffects, speedProvider)
}
