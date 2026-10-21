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
    val saveLocationUri: String? = null,
    val enableScreenShake: Boolean = true,
    val enableSlowMotion: Boolean = true,
    val enableImpactFlash: Boolean = true,
    val enableBackgroundMusic: Boolean = false,
    val musicVolume: Float = 0.13f
)
