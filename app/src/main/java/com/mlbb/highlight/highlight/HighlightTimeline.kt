package com.mlbb.highlight.highlight

import com.mlbb.highlight.ui.VideoEffectOptions
import com.mlbb.highlight.ui.VideoEffectPreset

enum class HighlightEventType(val label: String, val overlayText: String) {
    FIRST_BLOOD("First Blood", "FIRST BLOOD"),
    KILL("Kill", "KILL"),
    DOUBLE_KILL("Double Kill", "DOUBLE KILL"),
    TRIPLE_KILL("Triple Kill", "TRIPLE KILL"),
    MANIAC("Maniac", "MANIAC"),
    SAVAGE("Savage", "SAVAGE"),
    VICTORY("Victory", "VICTORY"),
    DEFEAT("Defeat", "DEFEAT"),
    MANUAL_MOMENT("Manual Moment", "HIGHLIGHT")
}

enum class HighlightEventOrigin {
    OCR,
    MANUAL
}

data class HighlightEvent(
    val id: String,
    val type: HighlightEventType,
    val sourceTimeMs: Long,
    val confidence: Float,
    val origin: HighlightEventOrigin
)

enum class HighlightStyle(val label: String) {
    CINEMATIC("Cinematic"),
    AGGRESSIVE("Aggressive"),
    CLEAN("Clean"),
    CLASSIC("Classic")
}

enum class HighlightAspectRatio(val label: String, val ratio: Float) {
    VERTICAL("9:16", 9f / 16f),
    LANDSCAPE("16:9", 16f / 9f),
    SQUARE("1:1", 1f)
}

enum class HighlightResolution(val label: String, val shortEdgePx: Int) {
    P720("720p", 720),
    P1080("1080p", 1080)
}

enum class EffectIntensity(val label: String, val value: Float) {
    OFF("Off", 0f),
    LOW("Low", 0.3f),
    MEDIUM("Medium", 0.6f),
    HIGH("High", 0.9f)
}

data class EffectPreset(
    val name: String,
    val videoPreset: VideoEffectPreset,
    val intensity: EffectIntensity = EffectIntensity.MEDIUM,
    val slowMotionSpeed: Float = 0.55f,
    val zoomAmount: Float = 0.08f,
    val shakeAmount: Float = 0.2f
) {
    fun toVideoOptions(): VideoEffectOptions {
        val level = intensity.value
        return VideoEffectOptions(
            preset = if (intensity == EffectIntensity.OFF) VideoEffectPreset.NONE else videoPreset,
            intensity = level,
            slowMotionEnabled = videoPreset == VideoEffectPreset.KILL_SLOWMO &&
                intensity != EffectIntensity.OFF,
            slowMotionSpeed = slowMotionSpeed.coerceIn(0.25f, 0.9f),
            shakeEnabled = shakeAmount > 0f && videoPreset !in setOf(
                VideoEffectPreset.SHAKE,
                VideoEffectPreset.IMPACT,
                VideoEffectPreset.KILL_IMPACT,
                VideoEffectPreset.SAVAGE
            ),
            flashEnabled = intensity != EffectIntensity.OFF && videoPreset in setOf(
                VideoEffectPreset.FLASH,
                VideoEffectPreset.IMPACT,
                VideoEffectPreset.KILL_IMPACT,
                VideoEffectPreset.SAVAGE
            ),
            zoomScale = (1f + zoomAmount * level).coerceIn(1f, 1.2f),
            shakeAmount = (shakeAmount * level).coerceIn(0f, 1f)
        )
    }
}

data class SpeedCurvePoint(
    val timeMs: Long,
    val speed: Float
)

data class SpeedCurve(
    val points: List<SpeedCurvePoint>
) {
    fun speedAt(timeMs: Long): Float {
        val ordered = points.sortedBy(SpeedCurvePoint::timeMs)
        val previous = ordered.lastOrNull { it.timeMs <= timeMs } ?: return 1f
        val next = ordered.firstOrNull { it.timeMs > timeMs } ?: return previous.speed
        if (next.timeMs <= previous.timeMs) return previous.speed
        val progress = (timeMs - previous.timeMs).toFloat() /
            (next.timeMs - previous.timeMs).toFloat()
        return (previous.speed + (next.speed - previous.speed) * progress)
            .coerceIn(0.25f, 2f)
    }

    fun nextChangeTimeMs(timeMs: Long): Long? {
        val ordered = points.sortedBy(SpeedCurvePoint::timeMs)
        val nextPoint = ordered.firstOrNull { it.timeMs > timeMs }?.timeMs
        val previous = ordered.lastOrNull { it.timeMs <= timeMs }
        val next = ordered.firstOrNull { it.timeMs > timeMs }
        if (
            previous != null &&
            next != null &&
            previous.speed != next.speed
        ) {
            val nextRampSample = ((timeMs / SPEED_RAMP_SAMPLE_MS) + 1L) *
                SPEED_RAMP_SAMPLE_MS
            return minOf(nextPoint ?: nextRampSample, nextRampSample)
        }
        return nextPoint
    }

    private companion object {
        const val SPEED_RAMP_SAMPLE_MS = 50L
    }
}

data class AudioCue(
    val type: String,
    val timeMs: Long,
    val assetUri: String? = null,
    val volume: Float = 1f
)

enum class TextPosition {
    TOP,
    CENTER,
    BOTTOM
}

data class TextOverlay(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val position: TextPosition = TextPosition.TOP
)

data class HighlightClip(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val eventTimeMs: Long,
    val events: List<HighlightEvent>,
    val effectPreset: EffectPreset,
    val speedCurve: SpeedCurve,
    val audioCues: List<AudioCue> = emptyList(),
    val textOverlay: TextOverlay? = null
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    val primaryEvent: HighlightEvent?
        get() = events.maxByOrNull { eventPriority(it.type) }
}

data class HighlightTimeline(
    val sourceUri: String,
    val sourceTrimStartMs: Long,
    val sourceTrimEndMs: Long,
    val style: HighlightStyle,
    val clips: List<HighlightClip>,
    val aspectRatio: HighlightAspectRatio = HighlightAspectRatio.VERTICAL,
    val resolution: HighlightResolution = HighlightResolution.P1080,
    val musicUri: String? = null,
    val musicVolume: Float = 0.13f,
    val enableScreenShake: Boolean = true,
    val enableSlowMotion: Boolean = true,
    val enableImpactFlash: Boolean = true,
    val enableKillSound: Boolean = false,
    val enableBackgroundMusic: Boolean = false,
    val preserveOriginalGameplay: Boolean = true
) {
    val durationMs: Long
        get() = clips.sumOf(HighlightClip::durationMs)

    val sourceDurationMs: Long
        get() = (sourceTrimEndMs - sourceTrimStartMs).coerceAtLeast(0L)
}

fun HighlightEventType.effectPreset(style: HighlightStyle): EffectPreset {
    val selectedPreset = when {
        this == HighlightEventType.SAVAGE -> VideoEffectPreset.SAVAGE
        style == HighlightStyle.CLEAN -> VideoEffectPreset.KILL_IMPACT
        style == HighlightStyle.CINEMATIC -> VideoEffectPreset.KILL_SLOWMO
        style == HighlightStyle.AGGRESSIVE -> VideoEffectPreset.KILL_IMPACT
        else -> VideoEffectPreset.KILL_IMPACT
    }
    val intensity = when (style) {
        HighlightStyle.CLEAN -> EffectIntensity.LOW
        HighlightStyle.AGGRESSIVE -> EffectIntensity.HIGH
        HighlightStyle.CINEMATIC,
        HighlightStyle.CLASSIC -> EffectIntensity.MEDIUM
    }
    return EffectPreset(
        name = "MLBB Cinematic Kill",
        videoPreset = selectedPreset,
        intensity = intensity,
        slowMotionSpeed = if (style == HighlightStyle.CINEMATIC) 0.5f else 0.65f,
        zoomAmount = when (style) {
            HighlightStyle.CLEAN -> 0.04f
            HighlightStyle.AGGRESSIVE -> 0.12f
            HighlightStyle.CINEMATIC,
            HighlightStyle.CLASSIC -> 0.08f
        },
        shakeAmount = when (style) {
            HighlightStyle.CLEAN -> 0.1f
            HighlightStyle.AGGRESSIVE -> 0.45f
            HighlightStyle.CINEMATIC,
            HighlightStyle.CLASSIC -> 0.2f
        }
    )
}

internal fun eventPriority(type: HighlightEventType): Int = when (type) {
    HighlightEventType.SAVAGE -> 7
    HighlightEventType.MANIAC -> 6
    HighlightEventType.TRIPLE_KILL -> 5
    HighlightEventType.DOUBLE_KILL -> 4
    HighlightEventType.FIRST_BLOOD -> 3
    HighlightEventType.VICTORY,
    HighlightEventType.DEFEAT -> 2
    HighlightEventType.KILL -> 1
    HighlightEventType.MANUAL_MOMENT -> 0
}
