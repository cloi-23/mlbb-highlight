package com.mlbb.highlight.settings

import android.content.Context
import androidx.core.content.edit

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBufferSeconds(): Int = prefs.getInt(KEY_BUFFER_SECONDS, DEFAULT_BUFFER_SECONDS)
    fun setBufferSeconds(value: Int) = prefs.edit { putInt(KEY_BUFFER_SECONDS, value) }

    fun getPreEventSeconds(): Int = prefs.getInt(KEY_PRE_EVENT_SECONDS, DEFAULT_PRE_EVENT_SECONDS)
    fun setPreEventSeconds(value: Int) = prefs.edit { putInt(KEY_PRE_EVENT_SECONDS, value) }

    fun getPostEventSeconds(): Int = prefs.getInt(KEY_POST_EVENT_SECONDS, DEFAULT_POST_EVENT_SECONDS)
    fun setPostEventSeconds(value: Int) = prefs.edit { putInt(KEY_POST_EVENT_SECONDS, value) }

    fun getSegmentLengthSeconds(): Int = prefs.getInt(KEY_SEGMENT_LENGTH_SECONDS, DEFAULT_SEGMENT_LENGTH_SECONDS)
    fun setSegmentLengthSeconds(value: Int) = prefs.edit { putInt(KEY_SEGMENT_LENGTH_SECONDS, value) }

    fun load(): AppSettings = AppSettings(
        bufferSeconds = getBufferSeconds(),
        preEventSeconds = getPreEventSeconds(),
        postEventSeconds = getPostEventSeconds(),
        segmentLengthSeconds = getSegmentLengthSeconds()
    )

    fun save(appSettings: AppSettings) {
        setBufferSeconds(appSettings.bufferSeconds)
        setPreEventSeconds(appSettings.preEventSeconds)
        setPostEventSeconds(appSettings.postEventSeconds)
        setSegmentLengthSeconds(appSettings.segmentLengthSeconds)
    }

    companion object {
        private const val PREFS_NAME = "mlbb_highlight_settings"
        private const val KEY_BUFFER_SECONDS = "buffer_seconds"
        private const val KEY_PRE_EVENT_SECONDS = "pre_event_seconds"
        private const val KEY_POST_EVENT_SECONDS = "post_event_seconds"
        private const val KEY_SEGMENT_LENGTH_SECONDS = "segment_length_seconds"

        private const val DEFAULT_BUFFER_SECONDS = 30
        private const val DEFAULT_PRE_EVENT_SECONDS = 20
        private const val DEFAULT_POST_EVENT_SECONDS = 10
        private const val DEFAULT_SEGMENT_LENGTH_SECONDS = 5
    }
}
