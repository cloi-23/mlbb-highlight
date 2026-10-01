package com.mlbb.highlight.ui

import android.graphics.Matrix
import androidx.media3.effect.GlEffect
import androidx.media3.effect.MatrixTransformation
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

fun createKillShakeEffect(intensity: Float, sceneStartUs: Long): GlEffect {
    val amplitude = 0.018f * intensity.coerceIn(0f, 1f)
    val zoom = 1.04f + 0.025f * intensity.coerceIn(0f, 1f)
    val decayPerSecond = 2.8f
    val frequencyHz = 13f

    return MatrixTransformation { presentationTimeUs ->
        val timeSeconds = (presentationTimeUs - sceneStartUs).coerceAtLeast(0L) / 1_000_000f
        val decay = exp(-decayPerSecond * timeSeconds)
        val oscillation = sin(2.0 * PI * frequencyHz * timeSeconds).toFloat()
        Matrix().apply {
            setScale(zoom, zoom, 0.5f, 0.5f)
            postTranslate(
                amplitude * oscillation * decay,
                amplitude * 0.65f * sin(2.0 * PI * (frequencyHz * 0.82f) * timeSeconds).toFloat() * decay
            )
        }
    }
}
