package com.mlbb.highlight.recording

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SegmentManager(
    private val baseDirectory: File,
    private val segmentDurationMs: Long = 5_000L,
    private val replayBuffer: ReplayBuffer
) {
    private val segmentDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)

    fun createSegmentFile(): File {
        baseDirectory.mkdirs()
        val timestamp = segmentDateFormat.format(Date())
        return File(baseDirectory, "segment_$timestamp.mp4")
    }

    fun registerSegment(file: File): SegmentFile {
        val createdAtMs = System.currentTimeMillis()
        val segment = SegmentFile(
            file = file,
            createdAtMs = createdAtMs,
            durationMs = segmentDurationMs
        )
        replayBuffer.addSegment(segment)
        return segment
    }

    fun currentSegments(): List<SegmentFile> = replayBuffer.currentSegments()

    fun removeOldSegments() {
        replayBuffer.clear()
    }

    fun getSegmentsForWindow(startMs: Long, endMs: Long): List<SegmentFile> {
        return replayBuffer.getSegmentsForWindow(startMs, endMs)
    }
}
