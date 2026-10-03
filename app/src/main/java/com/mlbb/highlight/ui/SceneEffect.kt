package com.mlbb.highlight.ui

data class SceneEffect(
    val id: String,
    val label: String,
    val eventTimeMs: Long?,
    val startMs: Long,
    val endMs: Long,
    val options: VideoEffectOptions = VideoEffectOptions()
)

data class VideoEditState(
    val sourceVideoUri: String? = null,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val sceneEffects: List<SceneEffect> = emptyList()
) {
    val exportableSceneEffects: List<SceneEffect>
        get() = sceneEffects.filter {
            it.startMs >= 0L &&
                it.endMs > it.startMs &&
                it.options.preset != VideoEffectPreset.NONE
        }.map { scene ->
            scene.copy(
                startMs = scene.startMs.coerceIn(0L, clipDurationMs),
                endMs = scene.endMs.coerceIn(0L, clipDurationMs)
            )
        }.filter { it.endMs > it.startMs }

    val clipDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
}

fun VideoEffectOptions.usesSlowMotion(): Boolean =
    slowMotionEnabled ||
        preset == VideoEffectPreset.SLOW_MOTION ||
        preset == VideoEffectPreset.KILL_SLOWMO
