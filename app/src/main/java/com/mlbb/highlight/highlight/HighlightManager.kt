package com.mlbb.highlight.highlight

import com.mlbb.highlight.recording.ReplayBuffer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HighlightManager(
    private val replayBuffer: ReplayBuffer,
    private val clipBuilder: ClipBuilder,
    private val outputDirectory: File
) {
    fun createManualHighlight(triggerTimeMs: Long): HighlightResult {
        val request = HighlightRequest(
            triggerTimeMs = triggerTimeMs,
            preEventDurationMs = 20_000L,
            postEventDurationMs = 10_000L
        )

        val selectedSegments = replayBuffer.getSegmentsForWindow(
            request.startTimeMs,
            request.endTimeMs
        )

        if (selectedSegments.isEmpty()) {
            throw IllegalStateException("No replay segments available for highlight window")
        }

        val formatted = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(triggerTimeMs))
        val outputName = "highlight_$formatted.mp4"
        val outputFile = clipBuilder.buildFromSegments(selectedSegments, outputName)

        return HighlightResult(
            outputFile = outputFile,
            triggerTimeMs = triggerTimeMs,
            success = true,
            message = "Highlight saved to ${outputFile.absolutePath}"
        )
    }
}
