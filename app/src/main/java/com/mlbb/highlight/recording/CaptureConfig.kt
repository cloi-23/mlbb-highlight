package com.mlbb.highlight.recording

data class CaptureConfig(
    val bufferSeconds: Int = 30,
    val preEventSeconds: Int = 20,
    val postEventSeconds: Int = 10,
    val segmentDurationSeconds: Int = 5,
    val maxRecordingWidth: Int = 1280,
    val maxRecordingHeight: Int = 720,
    val frameRate: Int = 30,
    val bitrate: Int = 8_000_000
)
