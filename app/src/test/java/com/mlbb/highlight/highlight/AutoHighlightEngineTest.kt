package com.mlbb.highlight.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHighlightEngineTest {
    private val engine = AutoHighlightEngine { error("OCR is not used in planner tests") }

    @Test
    fun nearbyEventsMergeIntoOneEditableSequence() {
        val timeline = engine.createTimeline(
            sourceUri = "content://gameplay",
            trimStartMs = 10_000L,
            trimEndMs = 30_000L,
            events = listOf(
                event(12_000L, HighlightEventType.KILL),
                event(16_000L, HighlightEventType.DOUBLE_KILL)
            ),
            style = HighlightStyle.CINEMATIC
        )

        assertEquals(1, timeline.clips.size)
        val clip = timeline.clips.single()
        assertEquals(0L, clip.startMs)
        assertEquals(12_000L, clip.endMs)
        assertEquals(
            listOf(HighlightEventType.KILL, HighlightEventType.DOUBLE_KILL),
            clip.events.map(HighlightEvent::type)
        )
        assertEquals(HighlightEventType.DOUBLE_KILL, clip.primaryEvent?.type)
        assertEquals(HighlightAspectRatio.VERTICAL, timeline.aspectRatio)
    }

    @Test
    fun separatedEventsRemainSeparateAndStayWithinTrimRange() {
        val timeline = engine.createTimeline(
            sourceUri = "content://gameplay",
            trimStartMs = 10_000L,
            trimEndMs = 30_000L,
            events = listOf(
                event(11_000L, HighlightEventType.FIRST_BLOOD),
                event(25_000L, HighlightEventType.SAVAGE)
            ),
            style = HighlightStyle.CLEAN
        )

        assertEquals(2, timeline.clips.size)
        assertEquals(0L, timeline.clips.first().startMs)
        assertEquals(10_000L, timeline.clips.first().endMs)
        assertEquals(10_000L, timeline.clips.last().startMs)
        assertEquals(20_000L, timeline.clips.last().endMs)
        assertTrue(timeline.clips.all { it.startMs >= 0L && it.endMs <= 20_000L })
        assertTrue(timeline.clips.all { clip -> clip.speedCurve.points.all { it.speed == 1f } })
    }

    @Test
    fun noEventsProducesAnEmptyTimelineInsteadOfInventedHighlights() {
        val timeline = engine.createTimeline(
            sourceUri = "content://gameplay",
            trimStartMs = 0L,
            trimEndMs = 10_000L,
            events = emptyList(),
            style = HighlightStyle.CLASSIC
        )

        assertTrue(timeline.clips.isEmpty())
        assertEquals(0L, timeline.durationMs)
    }

    private fun event(timeMs: Long, type: HighlightEventType) = HighlightEvent(
        id = "event-$timeMs",
        type = type,
        sourceTimeMs = timeMs,
        confidence = 0.9f,
        origin = HighlightEventOrigin.OCR
    )
}
