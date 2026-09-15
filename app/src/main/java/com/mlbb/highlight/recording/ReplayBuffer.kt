package com.mlbb.highlight.recording

import java.io.File
import java.util.ArrayDeque

class ReplayBuffer(
    private val maxDurationMs: Long,
    private val segmentDurationMs: Long = 5_000L
) {
    private val segments = ArrayDeque<SegmentFile>()

    fun addSegment(segment: SegmentFile) {
        segments.addLast(segment)
        trimToLimit()
    }

    fun addFile(file: File, createdAtMs: Long = System.currentTimeMillis()) {
        val segment = SegmentFile(
            file = file,
            createdAtMs = createdAtMs,
            durationMs = segmentDurationMs
        )
        addSegment(segment)
    }

    fun getSegmentsForWindow(startMs: Long, endMs: Long): List<SegmentFile> {
        return segments.filter { it.overlaps(startMs, endMs) }
    }

    fun currentSegments(): List<SegmentFile> = segments.toList()

    fun clear() {
        segments.clear()
    }

    private fun trimToLimit() {
        var totalDuration = 0L
        while (segments.isNotEmpty()) {
            val oldest = segments.first()
            totalDuration = (segments.last().endTimeMs - oldest.createdAtMs).coerceAtLeast(0L)
            if (totalDuration <= maxDurationMs) break
            segments.removeFirst()
        }
    }
}
