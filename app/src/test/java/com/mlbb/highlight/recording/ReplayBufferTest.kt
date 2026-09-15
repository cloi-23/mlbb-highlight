package com.mlbb.highlight.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReplayBufferTest {
    @Test
    fun `keeps only newest segments within max duration`() {
        val buffer = ReplayBuffer(maxDurationMs = 30_000L, segmentDurationMs = 5_000L)

        val segments = listOf(
            SegmentFile(File("segment1.mp4"), 0L, 5_000L),
            SegmentFile(File("segment2.mp4"), 5_000L, 5_000L),
            SegmentFile(File("segment3.mp4"), 10_000L, 5_000L),
            SegmentFile(File("segment4.mp4"), 15_000L, 5_000L),
            SegmentFile(File("segment5.mp4"), 20_000L, 5_000L),
            SegmentFile(File("segment6.mp4"), 25_000L, 5_000L),
            SegmentFile(File("segment7.mp4"), 30_000L, 5_000L)
        )

        segments.forEach(buffer::addSegment)

        val result = buffer.currentSegments()
        assertEquals(6, result.size)
        assertEquals(5_000L, result.first().createdAtMs)
        assertEquals(30_000L, result.last().createdAtMs)
    }

    @Test
    fun `returns only segments in requested window`() {
        val buffer = ReplayBuffer(maxDurationMs = 60_000L, segmentDurationMs = 5_000L)
        val segments = listOf(
            SegmentFile(File("s1.mp4"), 0L, 5_000L),
            SegmentFile(File("s2.mp4"), 5_000L, 5_000L),
            SegmentFile(File("s3.mp4"), 10_000L, 5_000L),
            SegmentFile(File("s4.mp4"), 15_000L, 5_000L),
            SegmentFile(File("s5.mp4"), 20_000L, 5_000L)
        )

        segments.forEach(buffer::addSegment)

        val result = buffer.getSegmentsForWindow(7_000L, 18_000L)
        assertEquals(3, result.size)
    }
}
