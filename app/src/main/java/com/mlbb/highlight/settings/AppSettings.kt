package com.mlbb.highlight.settings

enum class RecordingAudioSource {
    SILENT,
    DEVICE,
    MICROPHONE,
    DEVICE_AND_MICROPHONE
}

data class AppSettings(
    val resolutionShortEdge: Int = 720,
    val frameRate: Int = 30,
    val audioSource: RecordingAudioSource = RecordingAudioSource.DEVICE,
    val voiceCommandsEnabled: Boolean = false,
    val saveLocationUri: String? = null,
    val autoSave: Boolean = true
)
