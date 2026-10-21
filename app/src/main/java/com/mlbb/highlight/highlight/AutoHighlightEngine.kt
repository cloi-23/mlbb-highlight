package com.mlbb.highlight.highlight

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.mlbb.highlight.BuildConfig
import com.mlbb.highlight.detection.KdaSnapshot
import com.mlbb.highlight.detection.KdaTrackerUpdate
import com.mlbb.highlight.detection.HudRegion
import com.mlbb.highlight.detection.HudSignalDetector
import com.mlbb.highlight.detection.MyKdaTracker
import com.mlbb.highlight.detection.OcrDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class AutoHighlightEngine(
    private val detectorFactory: (HudRegion) -> HudSignalDetector = { OcrDetector(it) }
) {
    suspend fun analyze(
        context: Context,
        sourceUri: Uri,
        trimStartMs: Long,
        trimEndMs: Long,
        hudRegion: HudRegion = HudRegion.DEFAULT,
        onProgress: (KdaScanProgress) -> Unit = {}
    ): HighlightScanResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
            val metadataDuration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: throw IOException("Could not read video duration")
            val effectiveTrimStart = if (trimStartMs < 0L || trimEndMs <= trimStartMs) 0L else trimStartMs
            val effectiveTrimEnd = if (trimEndMs <= trimStartMs) metadataDuration else trimEndMs
            val startMs = effectiveTrimStart.coerceIn(0L, metadataDuration)
            val endMs = effectiveTrimEnd.coerceIn(0L, metadataDuration)
            require(endMs > startMs) { "The selected video contains no playable duration" }

            val durationMs = endMs - startMs
            val sampleIntervalMs = (durationMs / MAX_SAMPLES).coerceAtLeast(SAMPLE_INTERVAL_MS)

            val presentationTimesUs = samplePresentationTimesUs(
                context = context,
                sourceUri = sourceUri,
                startMs = startMs,
                endMs = endMs,
                sampleIntervalMs = sampleIntervalMs
            )
            if (presentationTimesUs.isEmpty()) {
                throw IOException("No video presentation timestamps found in the selected trim")
            }
            val detector = detectorFactory(hudRegion)
            try {
                val detectedKills = mutableListOf<HighlightEvent>()
                val counterIncreases = mutableListOf<com.mlbb.highlight.detection.KdaCounterIncrease>()
                var latestCounterIncrease: com.mlbb.highlight.detection.KdaCounterIncrease? = null
                val reviewDetections = mutableListOf<HighlightReview>()
                var lastUnconfirmedHudIncrease: PendingHudIncrease? = null
                val debugObservations = ArrayDeque<KdaSampleDiagnostic>()
                val tracker = MyKdaTracker(
                    maximumStableSampleGapMs = sampleIntervalMs * REQUIRED_STABLE_READINGS,
                    requiredStableReadings = REQUIRED_STABLE_READINGS
                )
                val metadataWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: SAMPLE_MAX_EDGE
                val metadataHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: SAMPLE_MAX_EDGE
                val (sampleWidth, sampleHeight) = scaledDimensions(
                    metadataWidth,
                    metadataHeight,
                    SAMPLE_MAX_EDGE
                )
                for ((sampleIndex, presentationTimeUs) in presentationTimesUs.withIndex()) {
                    val sampleTimeMs = presentationTimeUs / 1_000L
                    val frame = retriever.getScaledFrameAtTime(
                        presentationTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        sampleWidth,
                        sampleHeight
                    ) ?: continue
                    try {
                        val result = detector.analyze(frame, sampleTimeMs)
                        val candidates = result.kdaCandidates.mapNotNull { reading ->
                            MyKdaTracker.from(reading, frame.width, frame.height)
                        }
                        val update: KdaTrackerUpdate = tracker.observe(
                            candidates = candidates,
                            sourceTimeMs = sampleTimeMs
                        )
                        val candidateSummaries = result.parsedKdaCandidates.map { reading ->
                            KdaCandidateDiagnostic(
                                kda = KdaSnapshot(reading.kills, reading.deaths, reading.assists),
                                confidence = reading.confidence,
                                bounds = reading.bounds.toShortString()
                            )
                        }
                        val acceptedSummaries = result.kdaCandidates.map { reading ->
                            "${reading.kills}/${reading.deaths}/${reading.assists}@${reading.bounds.toShortString()}"
                        }
                        val rawOcr = update.rawOcrResult ?: result.rawOcrText
                            .ifBlank { result.kdaCandidates.firstOrNull()?.rawText.orEmpty() }
                        val firstChar = update.firstChar ?: result.kdaCandidates.firstOrNull()?.firstChar
                        val lastK = update.lastConfirmedK ?: tracker.lastConfirmedKills
                        val conf = update.selectedCandidate?.confidence ?: result.confidence
                        debugObservations.addLast(
                            KdaSampleDiagnostic(
                                requestedTimeMs = sampleTimeMs,
                                frameWidth = frame.width,
                                frameHeight = frame.height,
                                candidates = candidateSummaries,
                                decision = update.decision,
                                detectedText = result.textObservations,
                                rejectedDetections = result.rejectedDetections,
                                visualCues = result.visualCues,
                                acceptedKda = acceptedSummaries,
                                counterIncrease = update.counterIncrease,
                                rawOcrText = rawOcr,
                                firstChar = firstChar,
                                lastConfirmedK = lastK,
                                confidence = conf,
                                counterIncreaseConfirmed = update.killConfirmed
                            )
                        )
                        while (debugObservations.size > MAX_DEBUG_OBSERVATIONS) {
                            debugObservations.removeFirst()
                        }
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                DEBUG_TAG,
                                "framePts=${sampleTimeMs}ms size=${frame.width}x${frame.height} " +
                                    "ocr=${result.rawOcrText} " +
                                    "parsed=${candidateSummaries.map { "${it.kda}@${it.bounds}(conf=${it.confidence})" }} " +
                                    "accepted=$acceptedSummaries " +
                                    "confidence=$conf rejected=${result.rejectedDetections} " +
                                    "selected=$rawOcr firstChar=$firstChar lastK=$lastK " +
                                    "killConfirmed=${update.killConfirmed} decision=${update.decision}"
                            )
                        }
                        update.counterIncrease?.let { change ->
                            latestCounterIncrease = change
                            if (counterIncreases.none {
                                    it.firstObservedAtMs == change.firstObservedAtMs &&
                                        it.previousKills == change.previousKills &&
                                        it.currentKills == change.currentKills
                                }
                            ) counterIncreases += change
                        }
                        update.killEvent?.let { event ->
                            detectedKills += event
                            if (BuildConfig.DEBUG) {
                                Log.i(
                                    DEBUG_TAG,
                                    "KDA transition estimated=${event.sourceTimeMs}ms " +
                                        "previousRead=${event.counterObservedFromMs}ms " +
                                        "firstIncreasedRead=${event.counterFirstObservedAtMs}ms " +
                                        "confirmed=${event.counterConfirmedAtMs}ms " +
                                        "(${event.killsAdded ?: 0} added): " +
                                        "${update.previousKills} -> ${update.currentKills}, " +
                                        "confidence=${event.confidence}"
                                )
                            }
                        }
                        val reviewCandidate = update.selectedCandidate
                        val confirmedKills = tracker.lastConfirmedKills
                        if (
                            reviewCandidate != null &&
                            reviewCandidate.isFirstDigitValid &&
                            reviewCandidate.isTripletNumericallyValid &&
                            confirmedKills != null &&
                            reviewCandidate.snapshot.kills > confirmedKills
                        ) {
                            lastUnconfirmedHudIncrease = PendingHudIncrease(
                                sourceTimeMs = sampleTimeMs,
                                previousKills = confirmedKills,
                                candidate = reviewCandidate
                            )
                        } else if (
                            reviewCandidate != null &&
                            reviewCandidate.isFirstDigitValid &&
                            reviewCandidate.isTripletNumericallyValid &&
                            confirmedKills != null &&
                            reviewCandidate.snapshot.kills <= confirmedKills
                        ) {
                            lastUnconfirmedHudIncrease = null
                        }
                        if (
                            update.decision.startsWith("Kill increase needs review") &&
                            reviewCandidate != null
                        ) {
                            val previousKills = update.previousKills ?: tracker.lastConfirmedKills
                            if (previousKills != null && reviewDetections.none {
                                    it.previousKills == previousKills &&
                                        it.currentKills == reviewCandidate.snapshot.kills
                                }
                            ) {
                                reviewDetections += HighlightReview(
                                    id = UUID.randomUUID().toString(),
                                    sourceTimeMs = sampleTimeMs,
                                    previousKills = previousKills,
                                    currentKills = reviewCandidate.snapshot.kills,
                                    confidence = reviewCandidate.confidence,
                                    reason = update.decision
                                )
                            }
                        }
                        withContext(Dispatchers.Main.immediate) {
                            onProgress(
                                KdaScanProgress(
                                    sampleIndex = sampleIndex + 1,
                                    sampleCount = presentationTimesUs.size,
                                    sourceTimeMs = sampleTimeMs,
                                    baselineKda = tracker.baseline,
                                    currentKda = tracker.current,
                                    observedKda = update.selectedCandidate?.takeIf {
                                        it.isFirstDigitValid && it.isTripletNumericallyValid
                                    }?.snapshot,
                                    confirmedKillCount = detectedKills.size,
                                    lastKillFrom = update.previousKills,
                                    lastKillTo = update.currentKills,
                                    decision = update.decision,
                                    confidence = update.selectedCandidate?.confidence,
                                    counterIncrease = latestCounterIncrease
                                )
                            )
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                lastUnconfirmedHudIncrease?.let { pending ->
                    if (reviewDetections.none {
                            it.previousKills == pending.previousKills &&
                                it.currentKills == pending.candidate.snapshot.kills
                        }
                    ) {
                        reviewDetections += HighlightReview(
                            id = UUID.randomUUID().toString(),
                            sourceTimeMs = pending.sourceTimeMs,
                            previousKills = pending.previousKills,
                            currentKills = pending.candidate.snapshot.kills,
                            confidence = pending.candidate.confidence,
                            reason = "HUD kill count increased but did not remain stable before the scan ended; confirm this moment manually."
                        )
                    }
                }
                HighlightScanResult(
                    events = detectedKills,
                    counterIncreases = counterIncreases,
                    baselineKda = tracker.baseline,
                    finalKda = tracker.current,
                    previousKills = tracker.lastKillPrevious ?: tracker.current?.kills
                        ?: tracker.baseline?.kills,
                    currentKills = tracker.lastKillCurrent ?: tracker.current?.kills,
                    killIncrease = tracker.lastKillCurrent?.minus(tracker.lastKillPrevious ?: 0),
                    debugObservations = debugObservations.toList(),
                    reviewDetections = reviewDetections
                )
            } finally {
                detector.close()
            }
        } finally {
            retriever.release()
        }
    }

    private fun samplePresentationTimesUs(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        sampleIntervalMs: Long
    ): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IOException("The selected media contains no video track")
            extractor.selectTrack(videoTrack)
            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L
            val intervalUs = sampleIntervalMs * 1_000L
            val timestamps = mutableListOf<Long>()
            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            var nextTargetUs = startUs
            while (true) {
                val timeUs = extractor.sampleTime
                if (timeUs < 0L || timeUs >= endUs) break
                if (timeUs >= startUs && timeUs >= nextTargetUs) {
                    timestamps += timeUs
                    nextTargetUs = timeUs + intervalUs
                }
                if (!extractor.advance()) break
            }
            if (timestamps.isEmpty()) {
                var stepMs = startMs
                while (stepMs < endMs) {
                    timestamps += stepMs * 1_000L
                    stepMs += sampleIntervalMs
                }
            }
            return timestamps
        } finally {
            extractor.release()
        }
    }

    private fun scaledDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxEdge to (maxEdge * 9 / 16)
        val scale = maxEdge.toFloat() / maxOf(width, height)
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    fun createTimeline(
        sourceUri: String,
        trimStartMs: Long,
        trimEndMs: Long,
        events: List<HighlightEvent>,
        style: HighlightStyle
    ): HighlightTimeline {
        val effectiveStartMs = trimStartMs.coerceAtLeast(0L)
        val effectiveEndMs = if (trimEndMs > effectiveStartMs) {
            trimEndMs
        } else {
            (events.maxOfOrNull { it.sourceTimeMs + 5_000L } ?: (effectiveStartMs + 60_000L))
        }
        val durationMs = effectiveEndMs - effectiveStartMs
        val eligibleEvents = events
            .filter { it.sourceTimeMs in effectiveStartMs until effectiveEndMs }
            .sortedBy(HighlightEvent::sourceTimeMs)
        val groups = mutableListOf<MutableList<HighlightEvent>>()
        eligibleEvents.forEach { event ->
            val lastGroup = groups.lastOrNull()
            val previous = lastGroup?.lastOrNull()
            if (
                lastGroup == null ||
                previous == null ||
                event.sourceTimeMs - previous.sourceTimeMs >
                PRE_EVENT_MS + POST_EVENT_MS + MERGE_GAP_MS
            ) {
                groups += mutableListOf(event)
            } else {
                lastGroup += event
            }
        }

        val clips = groups.map { group ->
            val firstEventMs = group.first().sourceTimeMs - effectiveStartMs
            val lastEventMs = group.last().sourceTimeMs - effectiveStartMs
            var startMs = (firstEventMs - PRE_EVENT_MS).coerceAtLeast(0L)
            var endMs = (lastEventMs + POST_EVENT_MS).coerceAtMost(durationMs)
            if (endMs - startMs < MIN_CLIP_DURATION_MS) {
                val missingMs = MIN_CLIP_DURATION_MS - (endMs - startMs)
                val extendAfter = minOf(missingMs, durationMs - endMs)
                endMs += extendAfter
                startMs = (startMs - (missingMs - extendAfter)).coerceAtLeast(0L)
            }
            val primary = group.maxByOrNull { eventPriority(it.type) } ?: group.first()
            val effectPreset = primary.type.effectPreset(style)
            HighlightClip(
                id = UUID.randomUUID().toString(),
                label = group.joinToString(" + ") { it.type.label },
                startMs = startMs,
                endMs = endMs,
                eventTimeMs = primary.sourceTimeMs - trimStartMs,
                events = group,
                effectPreset = effectPreset,
                speedCurve = if (style == HighlightStyle.CLEAN) {
                    SpeedCurve(
                        listOf(
                            SpeedCurvePoint(0L, 1f),
                            SpeedCurvePoint((endMs - startMs).coerceAtLeast(0L), 1f)
                        )
                    )
                } else {
                    createSpeedCurve(
                        startMs,
                        endMs,
                        primary.sourceTimeMs - trimStartMs,
                        effectPreset.slowMotionSpeed
                    )
                },
                textOverlay = null
            )
        }.filter { it.endMs > it.startMs }
        return HighlightTimeline(
            sourceUri = sourceUri,
            sourceTrimStartMs = trimStartMs,
            sourceTrimEndMs = trimEndMs,
            style = style,
            clips = clips
        )
    }

    private fun createSpeedCurve(
        clipStartMs: Long,
        clipEndMs: Long,
        eventTimeMs: Long,
        slowSpeed: Float
    ): SpeedCurve {
        val eventInClipMs = eventTimeMs - clipStartMs
        return SpeedCurve(
            listOf(
                SpeedCurvePoint(0L, 1f),
                SpeedCurvePoint((eventInClipMs - 600L).coerceAtLeast(0L), 1.15f),
                SpeedCurvePoint((eventInClipMs - 120L).coerceAtLeast(0L), slowSpeed),
                SpeedCurvePoint((eventInClipMs + 300L).coerceAtLeast(0L), 1.2f),
                SpeedCurvePoint((clipEndMs - clipStartMs).coerceAtLeast(0L), 1f)
            ).distinctBy(SpeedCurvePoint::timeMs)
                .sortedBy(SpeedCurvePoint::timeMs)
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val MAX_SAMPLES = 1_800L
        const val PRE_EVENT_MS = 4_000L
        const val POST_EVENT_MS = 6_000L
        const val MIN_CLIP_DURATION_MS = 10_000L
        const val MERGE_GAP_MS = 1_000L
        const val SAMPLE_MAX_EDGE = 1280
        const val REQUIRED_STABLE_READINGS = 2
        const val MAX_DEBUG_OBSERVATIONS = 80
        const val DEBUG_TAG = "AutoHighlight"
    }

    private data class PendingHudIncrease(
        val sourceTimeMs: Long,
        val previousKills: Int,
        val candidate: com.mlbb.highlight.detection.PositionedKda
    )

    data class HighlightScanResult(
        val events: List<HighlightEvent>,
        val counterIncreases: List<com.mlbb.highlight.detection.KdaCounterIncrease> = emptyList(),
        val baselineKda: com.mlbb.highlight.detection.KdaSnapshot?,
        val finalKda: com.mlbb.highlight.detection.KdaSnapshot?,
        val previousKills: Int?,
        val currentKills: Int?,
        val killIncrease: Int?,
        val debugObservations: List<KdaSampleDiagnostic> = emptyList(),
        val reviewDetections: List<HighlightReview> = emptyList()
    )

    data class KdaScanProgress(
        val sampleIndex: Int,
        val sampleCount: Int,
        val sourceTimeMs: Long,
        val baselineKda: KdaSnapshot?,
        val currentKda: KdaSnapshot?,
        /** Latest valid OCR candidate, which may still be pending confirmation. */
        val observedKda: KdaSnapshot? = null,
        val confirmedKillCount: Int,
        val lastKillFrom: Int?,
        val lastKillTo: Int?,
        val decision: String,
        val confidence: Float?,
        val counterIncrease: com.mlbb.highlight.detection.KdaCounterIncrease? = null
    )

    data class KdaSampleDiagnostic(
        val requestedTimeMs: Long,
        val frameWidth: Int,
        val frameHeight: Int,
        val candidates: List<KdaCandidateDiagnostic>,
        val decision: String,
        val detectedText: List<com.mlbb.highlight.detection.OcrTextObservation> = emptyList(),
        val rejectedDetections: List<String> = emptyList(),
        val visualCues: List<com.mlbb.highlight.detection.HudVisualCue> = emptyList(),
        val acceptedKda: List<String> = emptyList(),
        val counterIncrease: com.mlbb.highlight.detection.KdaCounterIncrease? = null,
        val rawOcrText: String? = null,
        val firstChar: String? = null,
        val lastConfirmedK: Int? = null,
        val confidence: Float? = null,
        val counterIncreaseConfirmed: Boolean = false
    )

    data class KdaCandidateDiagnostic(
        val kda: KdaSnapshot,
        val confidence: Float,
        val bounds: String
    )

    data class HighlightReview(
        val id: String,
        val sourceTimeMs: Long,
        val previousKills: Int,
        val currentKills: Int,
        val confidence: Float,
        val reason: String
    )
}
