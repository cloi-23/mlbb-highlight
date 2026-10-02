package com.mlbb.highlight.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.Brightness
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TimestampWrapper

fun createSceneVideoEffects(
    options: VideoEffectOptions,
    sceneStartUs: Long,
    sceneEndUs: Long,
    eventTimeUs: Long = sceneStartUs + 200_000L
): List<GlEffect> {
    val intensity = options.intensity.coerceIn(0f, 1f)
    val preset = options.preset
    return buildList {
        when (preset) {
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
            VideoEffectPreset.GLOW -> {
                add(TimestampWrapper(Brightness(0.1f * intensity), sceneStartUs, sceneEndUs))
                add(TimestampWrapper(Contrast(0.16f * intensity), sceneStartUs, sceneEndUs))
                add(TimestampWrapper(
                    HslAdjustment.Builder().adjustSaturation(0.2f * intensity).build(),
                    sceneStartUs,
                    sceneEndUs
                ))
            }
            VideoEffectPreset.IMPACT,
            VideoEffectPreset.KILL_IMPACT -> {
                add(TimestampWrapper(Contrast(0.2f * intensity), sceneStartUs, sceneEndUs))
                add(zoom(options.zoomScale, sceneStartUs, sceneEndUs))
                add(createKillShakeEffect(intensity, options.shakeAmount, eventTimeUs, sceneEndUs))
            }
            VideoEffectPreset.SHAKE ->
                add(createKillShakeEffect(intensity, options.shakeAmount, eventTimeUs, sceneEndUs))
            VideoEffectPreset.ZOOM ->
                add(zoom(options.zoomScale, sceneStartUs, sceneEndUs))
            VideoEffectPreset.IMPACT_OVERLAY ->
                add(createTextImpactOverlay("IMPACT", options.opacity, eventTimeUs, sceneEndUs))
            VideoEffectPreset.SAVAGE -> {
                add(TimestampWrapper(Contrast(0.2f * intensity), sceneStartUs, sceneEndUs))
                add(zoom((options.zoomScale + 0.04f).coerceAtMost(1.18f), sceneStartUs, sceneEndUs))
                add(createKillShakeEffect(intensity, options.shakeAmount, eventTimeUs, sceneEndUs))
                add(createTextImpactOverlay("SAVAGE!", options.opacity, eventTimeUs, sceneEndUs))
            }
            VideoEffectPreset.FLASH,
            VideoEffectPreset.NONE,
            VideoEffectPreset.SLOW_MOTION,
            VideoEffectPreset.KILL_SLOWMO -> Unit
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
        if (options.flashEnabled ||
            preset == VideoEffectPreset.FLASH ||
            preset == VideoEffectPreset.IMPACT ||
            preset == VideoEffectPreset.KILL_IMPACT ||
            preset == VideoEffectPreset.SAVAGE
        ) {
            add(
                TimestampWrapper(
                    createOpeningFlashEffect(intensity * options.opacity.coerceIn(0f, 1f), eventTimeUs),
                    sceneStartUs,
                    sceneEndUs
                )
            )
        }
        if (options.shakeEnabled && preset !in setOf(
                VideoEffectPreset.SHAKE,
                VideoEffectPreset.IMPACT,
                VideoEffectPreset.KILL_IMPACT,
                VideoEffectPreset.SAVAGE
            )
        ) {
            add(
                TimestampWrapper(
                    createKillShakeEffect(intensity, options.shakeAmount, eventTimeUs, sceneEndUs),
                    sceneStartUs,
                    sceneEndUs
                )
            )
        }
        if (preset == VideoEffectPreset.KILL_SLOWMO) {
            add(zoom(1.035f, sceneStartUs, sceneEndUs))
        }
    }
}

private fun zoom(scale: Float, startUs: Long, endUs: Long): GlEffect =
    TimestampWrapper(
        MatrixTransformation { _ ->
            val boundedScale = scale.coerceIn(1f, 1.2f)
            android.graphics.Matrix().apply { setScale(boundedScale, boundedScale, 0.5f, 0.5f) }
        },
        startUs,
        endUs
    )

private fun createTextImpactOverlay(
    text: String,
    opacity: Float,
    eventTimeUs: Long,
    sceneEndUs: Long
): GlEffect {
    val bitmap = Bitmap.createBitmap(640, 160, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (text.startsWith("SAVAGE")) Color.rgb(255, 214, 64) else Color.WHITE
            textSize = if (text.startsWith("SAVAGE")) 88f else 72f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(12f, 0f, 3f, Color.BLACK)
            style = Paint.Style.FILL
        }
        drawText(text, width / 2f, height * 0.68f, paint)
    }
    val overlayEndUs = minOf(sceneEndUs, eventTimeUs + 450_000L)
    return OverlayEffect(
        listOf(object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                val elapsedUs = presentationTimeUs - eventTimeUs
                val fade = (1f - elapsedUs.toFloat() / 450_000f).coerceIn(0f, 1f)
                return StaticOverlaySettings.Builder()
                    .setAlphaScale(opacity.coerceIn(0f, 1f) * fade)
                    .build()
            }
        })
    ).let { TimestampWrapper(it, eventTimeUs, overlayEndUs.coerceAtLeast(eventTimeUs + 1L)) }
}
