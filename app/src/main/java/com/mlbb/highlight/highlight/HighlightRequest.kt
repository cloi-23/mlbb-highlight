package com.mlbb.highlight.highlight

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HighlightRequest(
    val triggerTimeMs: Long,
    val preEventDurationMs: Long = 10_000L,
    val postEventDurationMs: Long = 2_000L
) {
    val startTimeMs: Long
        get() = triggerTimeMs - preEventDurationMs

    val endTimeMs: Long
        get() = triggerTimeMs + postEventDurationMs

    fun timestampName(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(triggerTimeMs))
    }
}
