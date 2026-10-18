package com.mlbb.highlight.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudCalibrationTest {

    @Test
    fun defaultRegionIsWithinUpperRightHud() {
        val defaultRegion = HudRegion.DEFAULT
        assertTrue(defaultRegion.left >= 0.70f)
        assertTrue(defaultRegion.right <= 1.0f)
        assertTrue(defaultRegion.top >= 0.0f)
        assertTrue(defaultRegion.bottom <= 0.25f)
    }

    @Test
    fun presetsAreAvailableAndWellFormed() {
        assertTrue(HudRegion.PRESETS.isNotEmpty())
        HudRegion.PRESETS.forEach { (name, region) ->
            assertTrue("$name left boundary invalid", region.left in 0f..1f)
            assertTrue("$name right boundary invalid", region.right in 0f..1f)
            assertTrue("$name width invalid", region.right - region.left >= HudRegion.MIN_REGION_SIZE)
            assertTrue("$name height invalid", region.bottom - region.top >= HudRegion.MIN_REGION_SIZE)
        }
    }

    @Test
    fun toPixelRectScalesCorrectly() {
        val region = HudRegion(left = 0.75f, top = 0.05f, right = 0.95f, bottom = 0.15f)
        val pixelBounds = region.toPixelBounds(1920, 1080)
        assertNotNull(pixelBounds)
        assertEquals(1440, pixelBounds!!.left)
        assertEquals(54, pixelBounds.top)
        assertEquals(1824, pixelBounds.right)
        assertEquals(162, pixelBounds.bottom)
    }
}
