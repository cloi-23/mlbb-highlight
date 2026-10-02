package com.mlbb.highlight.settings

import android.content.Context
import androidx.core.content.edit

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val savedAudioSource = prefs.getString(KEY_AUDIO_SOURCE, null)
        val audioSource = savedAudioSource
            ?.let { value -> RecordingAudioSource.entries.firstOrNull { it.name == value } }
            ?: if (prefs.contains(KEY_INCLUDE_AUDIO)) {
                if (prefs.getBoolean(KEY_INCLUDE_AUDIO, false)) {
                    RecordingAudioSource.DEVICE
                } else {
                    RecordingAudioSource.SILENT
                }
            } else {
                AppSettings().audioSource
            }

        return AppSettings(
            resolutionShortEdge = prefs.getInt(KEY_RESOLUTION_SHORT_EDGE, DEFAULT_RESOLUTION_SHORT_EDGE)
                .coerceIn(HD_SHORT_EDGE, FULL_HD_SHORT_EDGE),
            frameRate = prefs.getInt(KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                .let { if (it == HIGH_FRAME_RATE) HIGH_FRAME_RATE else DEFAULT_FRAME_RATE },
            audioSource = audioSource,
            saveLocationUri = prefs.getString(KEY_SAVE_LOCATION_URI, null)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit {
            putInt(KEY_RESOLUTION_SHORT_EDGE, settings.resolutionShortEdge)
            putInt(KEY_FRAME_RATE, settings.frameRate)
            putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            putString(KEY_SAVE_LOCATION_URI, settings.saveLocationUri)
        }
    }

    companion object {
        const val HD_SHORT_EDGE = 720
        const val FULL_HD_SHORT_EDGE = 1080
        const val DEFAULT_RESOLUTION_SHORT_EDGE = HD_SHORT_EDGE
        const val DEFAULT_FRAME_RATE = 30
        const val HIGH_FRAME_RATE = 60

        private const val PREFS_NAME = "mlbb_recording_settings"
        private const val KEY_RESOLUTION_SHORT_EDGE = "resolution_short_edge"
        private const val KEY_FRAME_RATE = "frame_rate"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_INCLUDE_AUDIO = "include_audio"
        private const val KEY_SAVE_LOCATION_URI = "save_location_uri"
    }
}
