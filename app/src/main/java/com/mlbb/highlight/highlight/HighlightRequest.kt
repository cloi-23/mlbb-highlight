package com.mlbb.highlight.highlight

data class HighlightRequest(
    val triggerTimeMs: Long,
    val preEventDurationMs: Long = 20_000L,
    val postEventDurationMs: Long = 10_000L
) {
    val startTimeMs: Long
        get() = triggerTimeMs - preEventDurationMs

    val endTimeMs: Long
        get() = triggerTimeMs + postEventDurationMs
}
