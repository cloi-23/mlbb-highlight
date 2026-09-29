package com.mlbb.highlight.settings

data class AppSettings(
    val bufferSeconds: Int = 30,
    val preEventSeconds: Int = 10,
    val postEventSeconds: Int = 2,
    val segmentLengthSeconds: Int = 5,
    val outputDirectoryName: String = "Highlights"
) {
    fun bufferDurationMs(): Long = bufferSeconds * 1000L
    fun preEventDurationMs(): Long = preEventSeconds * 1000L
    fun postEventDurationMs(): Long = postEventSeconds * 1000L
    fun segmentDurationMs(): Long = segmentLengthSeconds * 1000L
}
