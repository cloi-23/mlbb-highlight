package com.mlbb.highlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditStateTest {
    @Test
    fun exportableScenesAreClippedToTheSelectedRange() {
        val effect = VideoEffectOptions(preset = VideoEffectPreset.SHAKE)
        val state = VideoEditState(
            trimStartMs = 5_000L,
            trimEndMs = 6_000L,
            sceneEffects = listOf(
                SceneEffect("inside", "Inside", 200L, 100L, 400L, effect),
                SceneEffect("overlap", "Overlap", 950L, 900L, 1_200L, effect),
                SceneEffect("outside", "Outside", 1_250L, 1_200L, 1_300L, effect),
                SceneEffect(
                    "disabled",
                    "Disabled",
                    null,
                    100L,
                    300L,
                    effect.copy(preset = VideoEffectPreset.NONE)
                )
            )
        )

        assertEquals(
            listOf(100L to 400L, 900L to 1_000L),
            state.exportableSceneEffects.map { it.startMs to it.endMs }
        )
        assertEquals(1_000L, state.clipDurationMs)
    }

    @Test
    fun previewAndExportUseTheirCorrectPresentationTimeBases() {
        val state = VideoEditState(trimStartMs = 7_500L, trimEndMs = 12_500L)

        assertEquals(7_500_000L, state.timestampOffsetUs(EffectTimestampBasis.SOURCE_VIDEO))
        assertEquals(0L, state.timestampOffsetUs(EffectTimestampBasis.CLIPPED_VIDEO))
        assertTrue(state.exportableSceneEffects.isEmpty())
    }
}
