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
            saveLocationUri = prefs.getString(KEY_SAVE_LOCATION_URI, null),
            enableScreenShake = prefs.getBoolean(KEY_ENABLE_SCREEN_SHAKE, true),
            enableSlowMotion = prefs.getBoolean(KEY_ENABLE_SLOW_MOTION, true),
            enableImpactFlash = prefs.getBoolean(KEY_ENABLE_IMPACT_FLASH, true),
            enableBackgroundMusic = prefs.getBoolean(KEY_ENABLE_BACKGROUND_MUSIC, false),
            musicVolume = prefs.getFloat(KEY_MUSIC_VOLUME, 0.13f)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit {
            putInt(KEY_RESOLUTION_SHORT_EDGE, settings.resolutionShortEdge)
            putInt(KEY_FRAME_RATE, settings.frameRate)
            putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            putString(KEY_SAVE_LOCATION_URI, settings.saveLocationUri)
            putBoolean(KEY_ENABLE_SCREEN_SHAKE, settings.enableScreenShake)
            putBoolean(KEY_ENABLE_SLOW_MOTION, settings.enableSlowMotion)
            putBoolean(KEY_ENABLE_IMPACT_FLASH, settings.enableImpactFlash)
            putBoolean(KEY_ENABLE_BACKGROUND_MUSIC, settings.enableBackgroundMusic)
            putFloat(KEY_MUSIC_VOLUME, settings.musicVolume)
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
        private const val KEY_ENABLE_SCREEN_SHAKE = "enable_screen_shake"
        private const val KEY_ENABLE_SLOW_MOTION = "enable_slow_motion"
        private const val KEY_ENABLE_IMPACT_FLASH = "enable_impact_flash"
        private const val KEY_ENABLE_BACKGROUND_MUSIC = "enable_background_music"
        private const val KEY_MUSIC_VOLUME = "music_volume"
    }
}
