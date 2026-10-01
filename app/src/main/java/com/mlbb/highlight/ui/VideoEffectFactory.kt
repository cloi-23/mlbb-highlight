package com.mlbb.highlight.ui

import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.TimestampWrapper

fun createSceneVideoEffects(
    options: VideoEffectOptions,
    sceneStartUs: Long,
    sceneEndUs: Long
): List<GlEffect> {
    val intensity = options.intensity.coerceIn(0f, 1f)
    return buildList {
        when (options.preset) {
            VideoEffectPreset.CINEMATIC -> {
                add(TimestampWrapper(Contrast(0.15f * intensity), sceneStartUs, sceneEndUs))
                add(TimestampWrapper(Brightness(-0.04f * intensity), sceneStartUs, sceneEndUs))
                add(
                    TimestampWrapper(
                        HslAdjustment.Builder()
                            .adjustSaturation(-0.12f * intensity)
                            .build(),
                        sceneStartUs,
                        sceneEndUs
                    )
                )
            }
            VideoEffectPreset.NEON -> {
                add(TimestampWrapper(Contrast(0.2f * intensity), sceneStartUs, sceneEndUs))
                add(
                    TimestampWrapper(
                        HslAdjustment.Builder()
                            .adjustSaturation(0.45f * intensity)
                            .adjustHue(12f * intensity)
                            .build(),
                        sceneStartUs,
                        sceneEndUs
                    )
                )
            }
            VideoEffectPreset.FLASH,
            VideoEffectPreset.NONE,
            VideoEffectPreset.SLOW_MOTION -> Unit
        }

        val grading = options.colorGrading - 0.5f
        if (grading != 0f) {
            add(
                TimestampWrapper(
                    HslAdjustment.Builder()
                        .adjustHue(grading * 24f)
                        .adjustSaturation(grading * 0.3f)
                        .build(),
                    sceneStartUs,
                    sceneEndUs
                )
            )
        }
        if (options.flashEnabled) {
            add(
                TimestampWrapper(
                    createOpeningFlashEffect(intensity, sceneStartUs),
                    sceneStartUs,
                    sceneEndUs
                )
            )
        }
        if (options.shakeEnabled) {
            add(
                TimestampWrapper(
                    createKillShakeEffect(intensity, sceneStartUs),
                    sceneStartUs,
                    sceneEndUs
                )
            )
        }
    }
}
