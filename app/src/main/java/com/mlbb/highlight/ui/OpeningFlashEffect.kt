package com.mlbb.highlight.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings

fun createOpeningFlashEffect(intensity: Float, sceneStartUs: Long): GlEffect {
    val flashBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
    val alpha = intensity.coerceIn(0f, 1f)
    val flashDurationUs = 140_000L

    return OverlayEffect(
        listOf(
            object : BitmapOverlay() {
                override fun getBitmap(presentationTimeUs: Long) = flashBitmap

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val sceneTimeUs = (presentationTimeUs - sceneStartUs).coerceAtLeast(0L)
                    val remainingAlpha =
                        (1f - sceneTimeUs.toFloat() / flashDurationUs)
                            .coerceIn(0f, 1f)
                    return StaticOverlaySettings.Builder()
                        .setAlphaScale(alpha * remainingAlpha)
                        .build()
                }
            }
        )
    )
}
