package com.mlbb.highlight.recording

import java.io.File
import java.util.ArrayDeque

class ReplayBuffer(
    private val maxDurationMs: Long,
    private val segmentDurationMs: Long = 5_000L,
    private val onSegmentRemoved: (SegmentFile) -> Unit = {}
) {
    private val segments = ArrayDeque<SegmentFile>()

    @Synchronized
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

    @Synchronized
    fun getSegmentsForWindow(startMs: Long, endMs: Long): List<SegmentFile> {
        return segments.filter { it.overlaps(startMs, endMs) }
    }

    @Synchronized
    fun currentSegments(): List<SegmentFile> = segments.toList()

    @Synchronized
    fun clear() {
        segments.forEach(onSegmentRemoved)
        segments.clear()
    }

    private fun trimToLimit() {
        var totalDuration = 0L
        while (segments.isNotEmpty()) {
            val oldest = segments.first()
            totalDuration = (segments.last().endTimeMs - oldest.createdAtMs).coerceAtLeast(0L)
            if (totalDuration <= maxDurationMs) break
            onSegmentRemoved(segments.removeFirst())
        }
    }
}
