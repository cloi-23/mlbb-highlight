package com.mlbb.highlight.detection

import android.graphics.Rect
import com.mlbb.highlight.highlight.HighlightEvent
import com.mlbb.highlight.highlight.HighlightEventOrigin
import com.mlbb.highlight.highlight.HighlightEventType
import java.util.UUID
import kotlin.math.hypot

data class KdaSnapshot(
    val kills: Int,
    val deaths: Int,
    val assists: Int
)

data class PositionedKda(
    val snapshot: KdaSnapshot,
    val centerX: Float,
    val centerY: Float,
    val confidence: Float,
    val rawText: String = "",
    val firstChar: String = "",
    val isFirstDigitValid: Boolean = true
)

data class KdaTrackerUpdate(
    val baseline: KdaSnapshot?,
    val current: KdaSnapshot?,
    val killEvent: HighlightEvent?,
    val previousKills: Int? = null,
    val currentKills: Int? = null,
    val stableFrameCount: Int = 0,
    val decision: String = "Waiting for K/D/A",
    val selectedCandidate: PositionedKda? = null,
    val killConfirmed: Boolean = false,
    val rawOcrResult: String? = null,
    val firstChar: String? = null,
    val lastConfirmedK: Int? = null
)

class MyKdaTracker(
    private val maximumPositionDrift: Float = 0.05f,
    private val maximumStableSampleGapMs: Long = DEFAULT_MAX_STABLE_SAMPLE_GAP_MS,
    private val minimumKillEventConfidence: Float = MIN_KILL_EVENT_CONFIDENCE
) {
    var baseline: KdaSnapshot? = null
        private set

    var current: KdaSnapshot? = null
        private set

    var lastConfirmedKills: Int? = null
        private set

    private var trackedCenter: Pair<Float, Float>? = null
    private var pendingKills: Int? = null
    private var pendingSnapshot: KdaSnapshot? = null
    private var pendingCenter: Pair<Float, Float>? = null
    private var pendingFrameCount = 0
    private var pendingStartTimeMs: Long? = null
    var lastKillPrevious: Int? = null
        private set
    var lastKillCurrent: Int? = null
        private set

    fun observe(
        candidates: List<PositionedKda>,
        sourceTimeMs: Long,
        supportingBanner: HighlightEventType? = null
    ): KdaTrackerUpdate {
        val previousPosition = trackedCenter
        val distinctCandidates = candidates
            .filter { it.confidence >= MIN_CONFIDENCE }
            .distinctBy { candidate ->
                listOf(
                    candidate.snapshot,
                    (candidate.centerX / POSITION_BUCKET).toInt(),
                    (candidate.centerY / POSITION_BUCKET).toInt()
                )
            }
        val selected = if (previousPosition == null) {
            // Do not lock onto an arbitrary teammate row while the scoreboard is open.
            if (distinctCandidates.size != 1) {
                clearPendingCandidate()
                return snapshotUpdate(
                    event = null,
                    decision = if (distinctCandidates.isEmpty()) {
                        "No K/D/A candidate found"
                    } else {
                        "Multiple initial candidates; waiting instead of guessing"
                    }
                )
            }
            distinctCandidates.single()
        } else {
            val nearby = distinctCandidates
                .map { candidate ->
                    candidate to hypot(
                        candidate.centerX - previousPosition.first,
                        candidate.centerY - previousPosition.second
                    )
                }
                .filter { (_, distance) -> distance <= maximumPositionDrift }
                .sortedBy { (_, distance) -> distance }
            val closest = nearby.firstOrNull() ?: run {
                clearPendingCandidate()
                return snapshotUpdate(null, decision = "Tracked K/D/A region not visible")
            }
            if (
                nearby.size > 1 &&
                nearby[1].second - closest.second < MIN_REGION_SEPARATION
            ) {
                clearPendingCandidate()
                return snapshotUpdate(
                    null,
                    decision = "Multiple readings near tracked region; waiting"
                )
            }
            closest.first
        }

        if (!selected.isFirstDigitValid) {
            clearPendingCandidate()
            return snapshotUpdate(
                event = null,
                decision = "Uncertain reading: first character '${selected.firstChar}' is not a valid digit; retaining last confirmed K=${lastConfirmedKills ?: 0}",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        val kills = selected.snapshot.kills
        val selectedCenter = selected.centerX to selected.centerY
        if (
            pendingKills == kills && pendingSnapshot == selected.snapshot &&
            pendingCenter?.let { hypot(it.first - selectedCenter.first, it.second - selectedCenter.second) }
                ?.let { it <= maximumPositionDrift } == true &&
            pendingStartTimeMs?.let {
                sourceTimeMs >= it && sourceTimeMs - it <= maximumStableSampleGapMs
            } == true
        ) {
            pendingFrameCount += 1
        } else {
            pendingKills = kills
            pendingSnapshot = selected.snapshot
            pendingCenter = selectedCenter
            pendingFrameCount = 1
            pendingStartTimeMs = sourceTimeMs
        }

        trackedCenter = selectedCenter
        if (pendingFrameCount < REQUIRED_STABLE_READINGS) {
            return snapshotUpdate(
                event = null,
                decision = "K reading stability ${pendingFrameCount}/$REQUIRED_STABLE_READINGS",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        val confirmedAtMs = pendingStartTimeMs ?: sourceTimeMs
        if (baseline == null || lastConfirmedKills == null) {
            baseline = selected.snapshot
            current = selected.snapshot
            lastConfirmedKills = kills
            return snapshotUpdate(
                event = null,
                decision = "Stable baseline established (K=$kills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        val lastKills = lastConfirmedKills ?: kills
        if (kills < lastKills) {
            return snapshotUpdate(
                event = null,
                decision = "Lower kill OCR reading ignored ($kills < $lastKills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }
        if (kills == lastKills) {
            current = selected.snapshot
            return snapshotUpdate(
                event = null,
                decision = "Tracked K/D/A unchanged (K=$kills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }
        if (selected.confidence < minimumKillEventConfidence) {
            return snapshotUpdate(
                event = null,
                decision = "Kill increase needs review: OCR confidence too low",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        if (kills - lastKills > 1) {
            current = selected.snapshot
            lastConfirmedKills = kills
            return snapshotUpdate(
                event = null,
                decision = "Kill increase needs review: counter jumped from $lastKills to $kills",
                selectedCandidate = selected,
                killConfirmed = false
            ).copy(previousKills = lastKills, currentKills = kills, lastConfirmedK = kills)
        }

        lastKillPrevious = lastKills
        lastKillCurrent = kills
        lastConfirmedKills = kills
        current = selected.snapshot
        val event = HighlightEvent(
            id = UUID.randomUUID().toString(),
            type = HighlightEventType.KILL,
            sourceTimeMs = confirmedAtMs,
            confidence = selected.confidence,
            origin = HighlightEventOrigin.OCR
        )
        return snapshotUpdate(
            event = event,
            previousKillsForEvent = lastKills,
            decision = "Kill counter increased: K $lastKills -> $kills",
            selectedCandidate = selected,
            killConfirmed = true
        )
    }

    private fun snapshotUpdate(
        event: HighlightEvent?,
        previousKillsForEvent: Int? = lastKillPrevious ?: lastConfirmedKills,
        decision: String = "Waiting for K/D/A",
        selectedCandidate: PositionedKda? = null,
        killConfirmed: Boolean = false
    ): KdaTrackerUpdate =
        KdaTrackerUpdate(
            baseline = baseline,
            current = current,
            killEvent = event,
            previousKills = previousKillsForEvent,
            currentKills = current?.kills ?: lastConfirmedKills,
            stableFrameCount = pendingFrameCount,
            decision = decision,
            selectedCandidate = selectedCandidate,
            killConfirmed = killConfirmed,
            rawOcrResult = selectedCandidate?.rawText,
            firstChar = selectedCandidate?.firstChar,
            lastConfirmedK = lastConfirmedKills
        )

    private fun clearPendingCandidate() {
        pendingKills = null
        pendingSnapshot = null
        pendingCenter = null
        pendingFrameCount = 0
        pendingStartTimeMs = null
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.45f
        private const val MIN_KILL_EVENT_CONFIDENCE = 0.80f
        private const val POSITION_BUCKET = 0.025f
        private const val MIN_REGION_SEPARATION = 0.025f
        private const val REQUIRED_STABLE_READINGS = 3
        private const val DEFAULT_MAX_STABLE_SAMPLE_GAP_MS = 3_000L

        private val CLASSIFIABLE_BANNERS = setOf(
            HighlightEventType.FIRST_BLOOD,
            HighlightEventType.KILL,
            HighlightEventType.DOUBLE_KILL,
            HighlightEventType.TRIPLE_KILL,
            HighlightEventType.MANIAC,
            HighlightEventType.SAVAGE
        )

        fun from(reading: KdaReading, frameWidth: Int, frameHeight: Int): PositionedKda? {
            if (frameWidth <= 0 || frameHeight <= 0) return null
            val bounds: Rect = reading.bounds
            if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return null
            return PositionedKda(
                snapshot = KdaSnapshot(
                    kills = if (reading.isFirstDigitValid) reading.kills else 0,
                    deaths = reading.deaths,
                    assists = reading.assists
                ),
                centerX = (bounds.left + bounds.right) / (2f * frameWidth),
                centerY = (bounds.top + bounds.bottom) / (2f * frameHeight),
                confidence = reading.confidence,
                rawText = reading.rawText,
                firstChar = reading.firstChar,
                isFirstDigitValid = reading.isFirstDigitValid
            )
        }
    }
}
