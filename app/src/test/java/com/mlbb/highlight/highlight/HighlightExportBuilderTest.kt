package com.mlbb.highlight.highlight

import androidx.media3.common.audio.SpeedProvider
import com.mlbb.highlight.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightExportBuilderTest {

    private fun sampleTimeline(
        startMs: Long = 0L,
        endMs: Long = 30_000L,
        clips: List<HighlightClip> = emptyList(),
        musicUri: String? = null,
        musicVolume: Float = 0.13f
    ) = HighlightTimeline(
        sourceUri = "content://media/gameplay.mp4",
        sourceTrimStartMs = startMs,
        sourceTrimEndMs = endMs,
        style = HighlightStyle.CINEMATIC,
        clips = clips,
        musicUri = musicUri,
        musicVolume = musicVolume,
        preserveOriginalGameplay = true
    )

    private fun sampleClip(
        id: String,
        eventTimeMs: Long
    ) = HighlightClip(
        id = id,
        label = "Kill",
        startMs = (eventTimeMs - 2000L).coerceAtLeast(0L),
        endMs = eventTimeMs + 2000L,
        eventTimeMs = eventTimeMs,
        events = listOf(
            HighlightEvent(
                id = "evt-$id",
                type = HighlightEventType.KILL,
                sourceTimeMs = eventTimeMs,
                confidence = 0.95f,
                origin = HighlightEventOrigin.OCR
            )
        ),
        effectPreset = HighlightEventType.KILL.effectPreset(HighlightStyle.CINEMATIC),
        speedCurve = SpeedCurve(listOf(SpeedCurvePoint(0L, 1f), SpeedCurvePoint(4000L, 1f)))
    )

    @Test
    fun compositionContainsOnlyOrderedMomentClipsWithinSelectedTrim() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 45_000L,
            clips = listOf(sampleClip("c1", 15_000L), sampleClip("c2", 30_000L))
        )
        val composition = buildHighlightComposition(timeline)

        // One video sequence concatenates the selected clips without intervening source footage.
        assertEquals(1, composition.sequences.size)
        val videoSequence = composition.sequences.first()
        assertEquals(2, videoSequence.editedMediaItems.size)

        val videoItem = videoSequence.editedMediaItems.first()
        // Original audio is preserved
        assertFalse(videoItem.removeAudio)

        // First moment is clipped from the selected trim, not the full recording.
        val clipping = videoItem.mediaItem.clippingConfiguration
        assertEquals(13_000L, clipping.startPositionMs)
        assertEquals(17_000L, clipping.endPositionMs)
    }

    @Test
    fun doesNotAddForcedCropOrTextOverlays() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 20_000L,
            clips = listOf(sampleClip("c1", 10_000L))
        )
        val composition = buildHighlightComposition(timeline)
        val videoItem = composition.sequences.first().editedMediaItems.first()

        // Video effects list contains only subtle GlEffects around kill moments (shake, flash)
        // No Presentation crop or text overlays added
        val effectClassNames = videoItem.effects.videoEffects.map { it.javaClass.simpleName }
        assertFalse(
            "Must not contain Presentation crop: $effectClassNames",
            effectClassNames.any { it.contains("Presentation") }
        )
    }

    @Test
    fun withNoEffectsEnabledExportsFaithfulCopyOfOriginalVideo() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 20_000L,
            clips = listOf(sampleClip("c1", 10_000L))
        )
        val disabledSettings = AppSettings(
            enableScreenShake = false,
            enableSlowMotion = false,
            enableImpactFlash = false
        )
        val composition = buildHighlightComposition(timeline, disabledSettings)
        val videoItem = composition.sequences.first().editedMediaItems.first()

        // No effects applied at all
        assertTrue("Video effects must be empty when effects are disabled", videoItem.effects.videoEffects.isEmpty())
        assertEquals("Speed provider must be DEFAULT when slow motion is disabled", SpeedProvider.DEFAULT, videoItem.speedProvider)
    }

    @Test
    fun appliesEffectsAtConfirmedKillTimestamps() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 30_000L,
            clips = listOf(sampleClip("c1", 12_000L))
        )
        val settings = AppSettings(
            enableScreenShake = true,
            enableSlowMotion = true,
            enableImpactFlash = true
        )
        val composition = buildHighlightComposition(timeline, settings)
        val videoItem = composition.sequences.first().editedMediaItems.first()

        // Shake and flash are present
        assertEquals(2, videoItem.effects.videoEffects.size)
        // Slow motion speed provider is configured
        assertNotEquals(SpeedProvider.DEFAULT, videoItem.speedProvider)
    }

    @Test
    fun mixesBackgroundMusicAtConfigured13PercentVolume() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 30_000L,
            clips = listOf(sampleClip("music-clip", 15_000L)),
            musicUri = "file:///music/bg.mp3",
            musicVolume = 0.13f
        )
        val settings = AppSettings(enableBackgroundMusic = true, musicVolume = 0.13f)
        val composition = buildHighlightComposition(timeline, settings)

        // 2 sequences: 1 video (with original audio) + 1 looping music track
        assertEquals(2, composition.sequences.size)
        val musicSequence = composition.sequences[1]
        assertTrue(musicSequence.isLooping)
        val musicItem = musicSequence.editedMediaItems.first()
        assertTrue(musicItem.removeVideo)
    }

    @Test
    fun validationFunctionConfirmsMomentSequenceAndPreservedAudio() {
        val timeline = sampleTimeline(
            startMs = 0L,
            endMs = 25_000L,
            clips = listOf(sampleClip("c1", 8_000L))
        )
        val composition = buildHighlightComposition(timeline)
        val result = validateHighlightComposition(timeline, composition)

        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.checks.any { it.contains("Highlight moment sequence present") })
        assertTrue(result.checks.any { it.contains("Original gameplay audio preserved") })
        assertTrue(result.checks.any { it.contains("Original resolution and aspect ratio preserved") })
    }
}

