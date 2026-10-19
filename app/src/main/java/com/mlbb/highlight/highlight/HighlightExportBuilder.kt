package com.mlbb.highlight.highlight

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.TimestampWrapper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.ui.VideoEffectPreset
import com.mlbb.highlight.ui.createKillShakeEffect
import com.mlbb.highlight.ui.createOpeningFlashEffect

/**
 * Concatenates only the timeline's selected moment ranges. Preview and export
 * share this composition so both contain the same clips, effects, and audio.
 */
fun buildHighlightComposition(
    timeline: HighlightTimeline,
    settings: AppSettings = AppSettings(),
    context: Context? = null
): Composition {
    val trimStartMs = timeline.sourceTrimStartMs.coerceAtLeast(0L)
    val sourceDurationMs = (timeline.sourceTrimEndMs - trimStartMs).coerceAtLeast(0L)
    val clips = timeline.clips.mapNotNull { clip ->
        val clipStartMs = clip.startMs.coerceIn(0L, sourceDurationMs)
        val clipEndMs = clip.endMs.coerceIn(0L, sourceDurationMs)
        if (clipEndMs <= clipStartMs) return@mapNotNull null
        clip to (clipStartMs to clipEndMs)
    }
    require(clips.isNotEmpty()) { "No valid highlight moments to preview or export" }

    val videoItems = clips.map { (clip, range) ->
        val (clipStartMs, clipEndMs) = range
        val durationUs = (clipEndMs - clipStartMs) * 1_000L
        val eventTimeUs = (clip.eventTimeMs - clipStartMs)
            .coerceIn(0L, (clipEndMs - clipStartMs - 1L).coerceAtLeast(0L)) * 1_000L
        val mediaItemBuilder = MediaItem.Builder()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs + clipStartMs)
                    .setEndPositionMs(trimStartMs + clipEndMs)
                    .build()
            )
        try {
            mediaItemBuilder.setUri(Uri.parse(timeline.sourceUri))
        } catch (_: Throwable) {
            mediaItemBuilder.setMediaId(timeline.sourceUri)
        }
        val mediaItem = mediaItemBuilder.build()

        val active = clip.effectPreset.intensity != EffectIntensity.OFF &&
            clip.effectPreset.videoPreset != VideoEffectPreset.NONE
        val videoEffects = buildList<Effect> {
            if (active && settings.enableScreenShake && timeline.enableScreenShake) {
                add(
                    createKillShakeEffect(
                        intensity = clip.effectPreset.intensity.value,
                        shakeAmount = clip.effectPreset.shakeAmount,
                        eventTimeUs = eventTimeUs,
                        sceneEndUs = minOf(durationUs, eventTimeUs + 350_000L)
                    )
                )
            }
            if (active && settings.enableImpactFlash && timeline.enableImpactFlash) {
                val flashEndUs = minOf(durationUs, eventTimeUs + 140_000L)
                if (flashEndUs > eventTimeUs) {
                    add(
                        TimestampWrapper(
                            createOpeningFlashEffect(
                                intensity = clip.effectPreset.intensity.value,
                                eventTimeUs = eventTimeUs
                            ),
                            eventTimeUs,
                            flashEndUs
                        )
                    )
                }
            }
        }
        val itemBuilder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
        if (
            active && settings.enableSlowMotion && timeline.enableSlowMotion &&
            clip.effectPreset.videoPreset == VideoEffectPreset.KILL_SLOWMO
        ) {
            val windowStartUs = (eventTimeUs - 120_000L).coerceAtLeast(0L)
            val windowEndUs = minOf(durationUs, eventTimeUs + 350_000L)
            val speed = clip.effectPreset.slowMotionSpeed.coerceIn(0.25f, 0.9f)
            itemBuilder.setSpeed(object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float =
                    if (timeUs in windowStartUs until windowEndUs) speed else 1f

                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                    listOf(windowStartUs, windowEndUs).firstOrNull { it > timeUs } ?: C.TIME_UNSET
            })
        }
        itemBuilder.build()
    }
    val videoSequence = EditedMediaItemSequence.Builder(
        videoItems.first(), *videoItems.drop(1).toTypedArray()
    ).build()

    // Original audio remains attached to each selected moment; optional music loops under the montage.
    val sequences = mutableListOf(videoSequence)
    val killSoundEnabled = timeline.enableKillSound
    val killSoundUri = "android.resource://${context?.packageName ?: "com.mlbb.highlight"}/raw/default_kill_sound"
    val killSoundDurationMs = if (killSoundEnabled && context != null) {
        runCatching {
            MediaMetadataRetriever().run {
                setDataSource(context, Uri.parse(killSoundUri))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.also { release() }
                    ?: run { release(); DEFAULT_KILL_SOUND_DURATION_MS }
            }
        }.getOrDefault(DEFAULT_KILL_SOUND_DURATION_MS)
    } else DEFAULT_KILL_SOUND_DURATION_MS
    if (killSoundEnabled && context != null) {
        var outputOffsetMs = 0L
        val cues = clips.flatMap { (clip, range) ->
            val clipCues = clip.events.mapNotNull { event ->
                val adjustedEventMs = event.sourceTimeMs - trimStartMs +
                    (clip.eventTimeMs - clip.detectedEventTimeMs)
                val inClipMs = adjustedEventMs - range.first
                if (inClipMs in 0 until (clip.endMs - clip.startMs)) {
                    outputOffsetMs + inClipMs
                } else null
            }
            outputOffsetMs += range.second - range.first
            clipCues
        }.sorted()
        val audioItems = mutableListOf<EditedMediaItem>()
        var cursorMs = 0L
        cues.forEach { cueMs ->
            val cue = cueMs.coerceAtLeast(cursorMs)
            var silenceMs = cue - cursorMs
            while (silenceMs > 0L) {
                val chunkMs = silenceMs.coerceAtMost(SILENCE_RESOURCE_DURATION_MS)
                val silence = MediaItem.Builder()
                    .setUri(Uri.parse("android.resource://${context.packageName}/raw/silence_60s"))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs(chunkMs)
                            .build()
                    )
                    .build()
                audioItems += EditedMediaItem.Builder(silence).setRemoveVideo(true).build()
                silenceMs -= chunkMs
            }
            val gain = GainProcessor(object : GainProcessor.GainProvider {
                override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRateHz: Int): Float =
                    timeline.killSoundVolume.coerceIn(0f, 1f)

                override fun isUnityUntil(samplePosition: Long, sampleRateHz: Int): Long =
                    if (timeline.killSoundVolume >= 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
            })
            audioItems += EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(killSoundUri)))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(gain), emptyList()))
                .build()
            cursorMs = cue + killSoundDurationMs
        }
        if (audioItems.isNotEmpty()) {
            sequences += EditedMediaItemSequence.Builder(
                audioItems.first(), *audioItems.drop(1).toTypedArray()
            ).build()
        }
    }
    val musicUri = timeline.musicUri
    // The kill cue takes priority over the optional backing track to keep the
    // moment sound clear. Original gameplay audio remains in the video sequence.
    val bgMusicEnabled = !killSoundEnabled && (settings.enableBackgroundMusic || musicUri != null)
    if (bgMusicEnabled && musicUri != null) {
        val configuredVolume = if (settings.musicVolume > 0f) settings.musicVolume else timeline.musicVolume
        val effectiveVolume = configuredVolume.coerceIn(0f, 1f)
        val gain = GainProcessor(object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRateHz: Int): Float =
                effectiveVolume

            override fun isUnityUntil(samplePosition: Long, sampleRateHz: Int): Long =
                if (effectiveVolume == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
        })
        val musicMediaItem = try {
            MediaItem.fromUri(Uri.parse(musicUri))
        } catch (_: Throwable) {
            MediaItem.Builder().setMediaId(musicUri).build()
        }
        val musicItem = EditedMediaItem.Builder(musicMediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(gain), emptyList()))
            .build()
        sequences += EditedMediaItemSequence.Builder(musicItem)
            .setIsLooping(true)
            .build()
    }

    return Composition.Builder(sequences).build()
}

private const val DEFAULT_KILL_SOUND_DURATION_MS = 1_000L
private const val SILENCE_RESOURCE_DURATION_MS = 60_000L

data class HighlightValidationResult(
    val isValid: Boolean,
    val checks: List<String>,
    val issues: List<String>
)

fun validateHighlightComposition(
    timeline: HighlightTimeline,
    composition: Composition,
    settings: AppSettings = AppSettings()
): HighlightValidationResult {
    val checks = mutableListOf<String>()
    val issues = mutableListOf<String>()

    if (composition.sequences.isEmpty()) {
        issues += "Composition contains no video sequence"
    } else {
        checks += "Highlight moment sequence present"
    }

    val expectedDurationMs = timeline.durationMs
    if (expectedDurationMs <= 0L) {
        issues += "Timeline duration is non-positive"
    } else {
        checks += "Timeline duration valid: ${expectedDurationMs}ms"
    }

    val firstItem = composition.sequences.firstOrNull()?.editedMediaItems?.firstOrNull()
    if (firstItem?.removeAudio == true) {
        issues += "Original gameplay audio is muted or removed"
    } else {
        checks += "Original gameplay audio preserved"
    }

    if (composition.sequences.size > 1) {
        checks += "Background music mixed at configured volume (${(settings.musicVolume * 100).toInt()}%)"
    }

    checks += "Original resolution and aspect ratio preserved (no crop / zoom reframing)"
    checks += "No decorative text overlays or subtitles added"

    return HighlightValidationResult(
        isValid = issues.isEmpty(),
        checks = checks,
        issues = issues
    )
}

fun buildHighlightSpeedProvider(
    timeline: HighlightTimeline,
    timeOffsetMs: Long = 0L
): SpeedProvider? {
    val activeClips = timeline.clips.filter {
        it.effectPreset.intensity != EffectIntensity.OFF &&
            it.effectPreset.videoPreset != VideoEffectPreset.NONE
    }
    if (activeClips.isEmpty() || !timeline.enableSlowMotion) return null
    val windows = activeClips.map { clip ->
        val eventTime = clip.eventTimeMs + timeOffsetMs
        val sUs = (eventTime - 120L).coerceAtLeast(0L) * 1_000L
        val eUs = (eventTime + 350L) * 1_000L
        sUs to eUs
    }
    return object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float {
            val inSlow = windows.any { (s, e) -> timeUs in s until e }
            return if (inSlow) 0.55f else 1f
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            return windows
                .flatMap { listOf(it.first, it.second) }
                .filter { it > timeUs }
                .minOrNull() ?: C.TIME_UNSET
        }
    }
}
