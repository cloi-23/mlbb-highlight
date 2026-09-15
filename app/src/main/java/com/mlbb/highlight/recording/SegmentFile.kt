package com.mlbb.highlight.recording

import java.io.File

data class SegmentFile(
    val file: File,
    val createdAtMs: Long,
    val durationMs: Long = 5_000L
) {
    val endTimeMs: Long
        get() = createdAtMs + durationMs

    fun overlaps(startMs: Long, endMs: Long): Boolean {
        return createdAtMs < endMs && endTimeMs > startMs
    }
}
