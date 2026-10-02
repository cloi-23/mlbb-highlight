package com.mlbb.highlight.ui

data class SceneEffect(
    val id: String,
    val label: String,
    val eventTimeMs: Long?,
    val startMs: Long,
    val endMs: Long,
    val options: VideoEffectOptions = VideoEffectOptions()
)

fun VideoEffectOptions.usesSlowMotion(): Boolean =
    slowMotionEnabled ||
        preset == VideoEffectPreset.SLOW_MOTION ||
        preset == VideoEffectPreset.KILL_SLOWMO
