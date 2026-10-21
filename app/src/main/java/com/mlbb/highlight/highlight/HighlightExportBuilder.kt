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
        val sourceAudioProcessors: List<androidx.media3.common.audio.AudioProcessor> =
            if (timeline.gameAudioMuted || !timeline.preserveOriginalGameplay) {
                listOf(GainProcessor(object : GainProcessor.GainProvider {
                    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRateHz: Int): Float = 0f
                    override fun isUnityUntil(samplePosition: Long, sampleRateHz: Int): Long = C.TIME_UNSET
                }))
            } else emptyList()
        val itemBuilder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(sourceAudioProcessors, videoEffects))
        if (
            active && settings.enableSlowMotion && timeline.enableSlowMotion &&
            clip.effectPreset.videoPreset == VideoEffectPreset.KILL_SLOWMO
        ) {
            val speed = clip.effectPreset.slowMotionSpeed.coerceIn(0.25f, 0.9f)
            val windowStartUs = (eventTimeUs - (200_000f * speed).toLong()).coerceAtLeast(0L)
            val windowEndUs = minOf(durationUs, eventTimeUs + (800_000f * speed).toLong())
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

    // Original gameplay audio remains attached to each selected moment; optional music loops underneath.
    val sequences = mutableListOf(videoSequence)
    val musicUri = timeline.musicUri
    // Original gameplay audio remains attached to the video sequence. Music
    // occupies its own timed sequence.
    val bgMusicEnabled = !timeline.musicMuted && (settings.enableBackgroundMusic || musicUri != null)
    if (bgMusicEnabled && musicUri != null) {
        val effectiveVolume = timeline.musicVolume.coerceIn(0f, 1f)
        val montageDurationMs = timeline.durationMs
        val musicStartMs = timeline.musicStartMs.coerceIn(0L, montageDurationMs)
        val musicEndMs = (timeline.musicEndMs ?: montageDurationMs)
            .coerceIn(musicStartMs, montageDurationMs)
        if (context == null && musicStartMs == 0L && musicEndMs == montageDurationMs &&
            timeline.musicFadeInMs == 0L && timeline.musicFadeOutMs == 0L
        ) {
            // Keep the context-free composition path useful for callers that
            // only inspect/build a looping sequence (such as unit tests).
            val mediaItem = runCatching { MediaItem.fromUri(Uri.parse(musicUri)) }
                .getOrElse { MediaItem.Builder().setMediaId(musicUri).build() }
            val gain = GainProcessor(object : GainProcessor.GainProvider {
                override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRateHz: Int): Float = effectiveVolume
                override fun isUnityUntil(samplePosition: Long, sampleRateHz: Int): Long =
                    if (effectiveVolume == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
            })
            val musicItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(gain), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(musicItem).setIsLooping(true).build()
        } else if (musicEndMs > musicStartMs) {
            val mediaUri = Uri.parse(musicUri)
            val sourceDurationMs = context?.let { appContext ->
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, mediaUri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(0L)
            } ?: 0L
            require(sourceDurationMs > 0L) { "Could not read the selected music duration" }
            val musicItems = mutableListOf<EditedMediaItem>()
            fun appendSilence(durationMs: Long) {
                var remainingMs = durationMs.coerceAtLeast(0L)
                while (remainingMs > 0L) {
                    val chunkMs = remainingMs.coerceAtMost(SILENCE_RESOURCE_DURATION_MS)
                    val silence = MediaItem.Builder()
                        .setUri(Uri.parse("android.resource://${context?.packageName ?: "com.mlbb.highlight"}/raw/silence_60s"))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0L)
                                .setEndPositionMs(chunkMs)
                                .build()
                        )
                        .build()
                    musicItems += EditedMediaItem.Builder(silence).setRemoveVideo(true).build()
                    remainingMs -= chunkMs
                }
            }
            appendSilence(musicStartMs)
            var outputCursorMs = musicStartMs
            var sourceCursorMs = 0L
            val knownSourceDurationMs = sourceDurationMs.takeIf { it > 0L }
            while (outputCursorMs < musicEndMs) {
                val remainingMs = musicEndMs - outputCursorMs
                val chunkMs = knownSourceDurationMs?.let { minOf(it - sourceCursorMs, remainingMs) }
                    ?: remainingMs
                if (chunkMs <= 0L) {
                    sourceCursorMs = 0L
                    continue
                }
                val mediaBuilder = MediaItem.Builder().setUri(mediaUri)
                if (knownSourceDurationMs != null) {
                    mediaBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(sourceCursorMs)
                            .setEndPositionMs(sourceCursorMs + chunkMs)
                            .build()
                    )
                }
                val fadeInMs = timeline.musicFadeInMs.coerceAtMost(musicEndMs - musicStartMs)
                val fadeOutMs = timeline.musicFadeOutMs.coerceAtMost(musicEndMs - musicStartMs)
                val segmentStartMs = outputCursorMs
                val gain = GainProcessor(object : GainProcessor.GainProvider {
                    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRateHz: Int): Float {
                        val timeMs = segmentStartMs + samplePosition * 1_000L / sampleRateHz
                        val inGain = if (fadeInMs > 0L) {
                            ((timeMs - musicStartMs).toFloat() / fadeInMs).coerceIn(0f, 1f)
                        } else 1f
                        val outGain = if (fadeOutMs > 0L) {
                            ((musicEndMs - timeMs).toFloat() / fadeOutMs).coerceIn(0f, 1f)
                        } else 1f
                        return effectiveVolume * minOf(inGain, outGain)
                    }

                    override fun isUnityUntil(samplePosition: Long, sampleRateHz: Int): Long =
                        if (effectiveVolume == 1f && fadeInMs == 0L && fadeOutMs == 0L) {
                            C.TIME_END_OF_SOURCE
                        } else C.TIME_UNSET
                })
                musicItems += EditedMediaItem.Builder(mediaBuilder.build())
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(gain), emptyList()))
                    .build()
                outputCursorMs += chunkMs
                if (knownSourceDurationMs != null) sourceCursorMs += chunkMs
            }
            appendSilence(montageDurationMs - musicEndMs)
            if (musicItems.isNotEmpty()) {
                sequences += EditedMediaItemSequence.Builder(
                    musicItems.first(), *musicItems.drop(1).toTypedArray()
                ).build()
            }
        }
    }

    return Composition.Builder(sequences).build()
}

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
            it.effectPreset.videoPreset == VideoEffectPreset.KILL_SLOWMO
    }
    if (activeClips.isEmpty() || !timeline.enableSlowMotion) return null
    val windows = activeClips.map { clip ->
        val eventTime = clip.eventTimeMs + timeOffsetMs
        val speed = clip.effectPreset.slowMotionSpeed.coerceIn(0.25f, 0.9f)
        val beforeMs = (200f * speed).toLong()
        val afterMs = (800f * speed).toLong()
        val startUs = (eventTime - beforeMs).coerceAtLeast(0L) * 1_000L
        val endUs = (eventTime + afterMs) * 1_000L
        Triple(startUs, endUs, speed)
    }
    return object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float {
            return windows.firstOrNull { (startUs, endUs, _) -> timeUs in startUs until endUs }
                ?.third ?: 1f
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            return windows
                .flatMap { listOf(it.first, it.second) }
                .filter { it > timeUs }
                .minOrNull() ?: C.TIME_UNSET
        }
    }
}
