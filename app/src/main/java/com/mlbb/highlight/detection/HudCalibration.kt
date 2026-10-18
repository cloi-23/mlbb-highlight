package com.mlbb.highlight.detection

import android.content.Context
import android.graphics.Rect
import androidx.core.content.edit

data class HudPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class HudRegion(
    val left: Float = 0.73f,
    val top: Float = 0f,
    val right: Float = 0.93f,
    val bottom: Float = 0.20f
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(right - left >= MIN_REGION_SIZE && bottom - top >= MIN_REGION_SIZE)
    }

    fun toPixelBounds(width: Int, height: Int): HudPixelBounds? {
        if (width <= 0 || height <= 0) return null
        val l = (left * width).toInt()
        val t = (top * height).toInt()
        val r = (right * width).toInt()
        val b = (bottom * height).toInt()
        if (r <= l || b <= t) return null
        return HudPixelBounds(l, t, r, b)
    }

    fun toPixelRect(width: Int, height: Int): Rect? {
        val bounds = toPixelBounds(width, height) ?: return null
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    companion object {
        const val MIN_REGION_SIZE = 0.03f
        val DEFAULT = HudRegion(left = 0.73f, top = 0.00f, right = 0.93f, bottom = 0.20f)
        val WIDE_20_9 = HudRegion(left = 0.75f, top = 0.01f, right = 0.94f, bottom = 0.20f)
        val NOTCH_INSET = HudRegion(left = 0.71f, top = 0.02f, right = 0.90f, bottom = 0.22f)
        val TABLET = HudRegion(left = 0.68f, top = 0.00f, right = 0.92f, bottom = 0.17f)

        val PRESETS = listOf(
            "16:9 Standard" to DEFAULT,
            "20:9 Wide" to WIDE_20_9,
            "Notch / Inset" to NOTCH_INSET,
            "Tablet" to TABLET
        )
    }
}

class HudCalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): HudRegion {
        val defaults = HudRegion.DEFAULT
        val left = preferences.getFloat(KEY_LEFT, defaults.left)
            .coerceIn(0f, 1f - HudRegion.MIN_REGION_SIZE)
        val top = preferences.getFloat(KEY_TOP, defaults.top)
            .coerceIn(0f, 1f - HudRegion.MIN_REGION_SIZE)
        val right = preferences.getFloat(KEY_RIGHT, defaults.right)
            .coerceIn(left + HudRegion.MIN_REGION_SIZE, 1f)
        val bottom = preferences.getFloat(KEY_BOTTOM, defaults.bottom)
            .coerceIn(top + HudRegion.MIN_REGION_SIZE, 1f)
        return HudRegion(left, top, right, bottom)
    }

    fun save(region: HudRegion) {
        preferences.edit {
            putFloat(KEY_LEFT, region.left)
            putFloat(KEY_TOP, region.top)
            putFloat(KEY_RIGHT, region.right)
            putFloat(KEY_BOTTOM, region.bottom)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mlbb_hud_calibration"
        const val KEY_LEFT = "left"
        const val KEY_TOP = "top"
        const val KEY_RIGHT = "right"
        const val KEY_BOTTOM = "bottom"
    }
}
