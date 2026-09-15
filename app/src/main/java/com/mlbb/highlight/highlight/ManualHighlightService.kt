package com.mlbb.highlight.highlight

import com.mlbb.highlight.recording.ReplayBuffer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ManualHighlightService(
    private val replayBuffer: ReplayBuffer,
    private val outputDirectory: File
) {
    private val clipBuilder = ClipBuilder(outputDirectory)

    fun createHighlight(triggerTimeMs: Long): HighlightResult {
        val preEventDurationMs = 20_000L
        val postEventDurationMs = 10_000L
        val startMs = triggerTimeMs - preEventDurationMs
        val endMs = triggerTimeMs + postEventDurationMs

        val segments = replayBuffer.getSegmentsForWindow(startMs, endMs)

        if (segments.isEmpty()) {
            throw IllegalStateException("No replay segments exist for the selected highlight window")
        }

        val outputFile = clipBuilder.buildFromSegments(
            segments.sortedBy { it.createdAtMs },
            "highlight_${timestamp(triggerTimeMs)}.mp4"
        )

        return HighlightResult(
            outputFile = outputFile,
            triggerTimeMs = triggerTimeMs,
            success = true,
            message = "Highlight saved to ${outputFile.absolutePath}"
        )
    }

    private fun timestamp(triggerTimeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(triggerTimeMs))
    }
}
