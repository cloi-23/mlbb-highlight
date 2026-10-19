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
    val isFirstDigitValid: Boolean = true,
    val isTripletNumericallyValid: Boolean = true
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
    val lastConfirmedK: Int? = null,
    val counterIncrease: KdaCounterIncrease? = null
)

data class KdaCounterIncrease(
    val previousKills: Int,
    val currentKills: Int,
    val previousObservedAtMs: Long?,
    val firstObservedAtMs: Long,
    val confirmedAtMs: Long
) {
    val killsAdded: Int get() = currentKills - previousKills
}

class MyKdaTracker(
    private val maximumPositionDrift: Float = 0.05f,
    private val maximumStableSampleGapMs: Long = DEFAULT_MAX_STABLE_SAMPLE_GAP_MS,
    private val minimumKillEventConfidence: Float = MIN_KILL_EVENT_CONFIDENCE,
    private val requiredStableReadings: Int = REQUIRED_STABLE_READINGS
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
    private var lastStableObservedAtMs: Long? = null
    private var lastProcessedSourceTimeMs: Long? = null
    private var lastReportedCounterTarget: Int? = null
    private var pendingCounterIncrease: KdaCounterIncrease? = null
    var lastKillPrevious: Int? = null
        private set
    var lastKillCurrent: Int? = null
        private set

    fun observe(
        candidates: List<PositionedKda>,
        sourceTimeMs: Long
    ): KdaTrackerUpdate {
        if (lastProcessedSourceTimeMs?.let { sourceTimeMs <= it } == true) {
            return snapshotUpdate(event = null, decision = "Duplicate or out-of-order frame timestamp ignored")
        }
        lastProcessedSourceTimeMs = sourceTimeMs
        val previousPosition = trackedCenter
        val distinctCandidates = candidates
            .filter { it.confidence >= MIN_CONFIDENCE && it.isTripletNumericallyValid }
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
            val closest = nearby.firstOrNull()
            when {
                closest != null && nearby.size > 1 &&
                    nearby[1].second - closest.second < MIN_REGION_SEPARATION -> {
                    clearPendingCandidate()
                    return snapshotUpdate(
                        null,
                        decision = "Multiple readings near tracked region; waiting"
                    )
                }
                closest != null -> closest.first
                else -> {
                    // Resolution/layout changes can move the HUD. The detector has
                    // already constrained candidates to a validated numerical HUD ROI.
                    val relocated = distinctCandidates.singleOrNull()
                    if (relocated == null) {
                        clearPendingCandidate()
                        return snapshotUpdate(null, decision = "Tracked K/D/A region not visible")
                    }
                    // Do not let stability accumulated at the old screen position
                    // confirm a counter read at the new position.
                    clearPendingCandidate()
                    relocated
                }
            }
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
        if (lastConfirmedKills != null && kills == lastConfirmedKills) {
            // K can remain legible even while OCR is still stabilizing D/A.
            // Keep the transition bracket anchored to the latest known old K.
            lastStableObservedAtMs = sourceTimeMs
        }
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
        if (pendingFrameCount < requiredStableReadings) {
            return snapshotUpdate(
                event = null,
                decision = "K/D/A stability ${pendingFrameCount}/$requiredStableReadings",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        val firstObservedAtMs = pendingStartTimeMs ?: sourceTimeMs
        if (baseline == null || lastConfirmedKills == null) {
            baseline = selected.snapshot
            current = selected.snapshot
            lastConfirmedKills = kills
            lastStableObservedAtMs = sourceTimeMs
            return snapshotUpdate(
                event = null,
                decision = "Stable baseline established (K=$kills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }

        val lastKills = lastConfirmedKills ?: kills
        val counterIncrease = if (kills > lastKills && lastReportedCounterTarget != kills) {
            lastReportedCounterTarget = kills
            KdaCounterIncrease(
                previousKills = lastKills,
                currentKills = kills,
                previousObservedAtMs = lastStableObservedAtMs,
                firstObservedAtMs = pendingStartTimeMs ?: sourceTimeMs,
                confirmedAtMs = sourceTimeMs
            )
        } else {
            null
        }
        if (counterIncrease != null) pendingCounterIncrease = counterIncrease
        if (kills < lastKills) {
            if (isSessionReset(selected.snapshot, baseline)) {
                baseline = selected.snapshot
                current = selected.snapshot
                lastConfirmedKills = kills
                lastStableObservedAtMs = sourceTimeMs
                lastKillPrevious = null
                lastKillCurrent = null
                lastReportedCounterTarget = null
                pendingCounterIncrease = null
                clearPendingCandidate()
                return snapshotUpdate(
                    event = null,
                    decision = "Stable lower KDA accepted as a new match/session baseline (K=$kills)",
                    selectedCandidate = selected,
                    killConfirmed = false
                )
            }
            return snapshotUpdate(
                event = null,
                decision = "Lower kill OCR reading ignored ($kills < $lastKills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }
        // Live HUD values are independent from event creation. A stable higher
        // count is shown even when its increase needs review; a lower count is
        // impossible within a match and is ignored as an OCR misread.
        current = selected.snapshot
        if (kills == lastKills) {
            current = selected.snapshot
            lastStableObservedAtMs = sourceTimeMs
            lastReportedCounterTarget = null
            pendingCounterIncrease = null
            return snapshotUpdate(
                event = null,
                decision = "Tracked K/D/A unchanged (K=$kills)",
                selectedCandidate = selected,
                killConfirmed = false
            )
        }
        if (selected.confidence < minimumKillEventConfidence) {
            lastStableObservedAtMs = sourceTimeMs
            return snapshotUpdate(
                event = null,
                decision = "Kill increase needs review: OCR confidence too low",
                selectedCandidate = selected,
                killConfirmed = false,
                counterIncrease = counterIncrease
            )
        }

        // A jump still confirms one observed counter transition. Its delta is
        // recorded as a bundle; sampled frames cannot establish individual kill times.
        lastKillPrevious = lastKills
        lastKillCurrent = kills
        lastConfirmedKills = kills
        lastStableObservedAtMs = sourceTimeMs
        lastReportedCounterTarget = null
        current = selected.snapshot
        val confirmedIncrease = counterIncrease ?: pendingCounterIncrease
        pendingCounterIncrease = null
        val observedAtMs = confirmedIncrease?.firstObservedAtMs ?: firstObservedAtMs
        val estimatedEventAtMs = estimateCounterTransitionTimeMs(
            previousObservedAtMs = confirmedIncrease?.previousObservedAtMs,
            firstIncreasedReadingAtMs = observedAtMs
        )
        val event = HighlightEvent(
            id = UUID.randomUUID().toString(),
            type = HighlightEventType.KILL,
            // The kill lies somewhere between the last old-count frame and the
            // first new-count frame. Use the interval midpoint to avoid always
            // placing effects at the late edge; keep observations separately.
            sourceTimeMs = estimatedEventAtMs,
            confidence = selected.confidence,
            origin = HighlightEventOrigin.OCR,
            killsAdded = confirmedIncrease?.killsAdded ?: (kills - lastKills),
            previousKillCount = lastKills,
            currentKillCount = kills,
            counterObservedFromMs = confirmedIncrease?.previousObservedAtMs,
            counterConfirmedAtMs = sourceTimeMs,
            counterFirstObservedAtMs = observedAtMs
        )
        return snapshotUpdate(
            event = event,
            previousKillsForEvent = lastKills,
            decision = if (kills - lastKills > 1) {
                "Stable KDA counter jump: K $lastKills -> $kills " +
                    "(+${confirmedIncrease?.killsAdded ?: (kills - lastKills)} observed; individual kill times unknown)"
            } else {
                "Stable KDA counter increase: K $lastKills -> $kills (+1 observed)"
            },
            selectedCandidate = selected,
            killConfirmed = true,
            counterIncrease = counterIncrease
        )
    }

    private fun snapshotUpdate(
        event: HighlightEvent?,
        previousKillsForEvent: Int? = lastKillPrevious ?: lastConfirmedKills,
        decision: String = "Waiting for K/D/A",
        selectedCandidate: PositionedKda? = null,
        killConfirmed: Boolean = false,
        counterIncrease: KdaCounterIncrease? = null
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
            lastConfirmedK = lastConfirmedKills,
            counterIncrease = counterIncrease
        )

    private fun clearPendingCandidate() {
        pendingKills = null
        pendingSnapshot = null
        pendingCenter = null
        pendingFrameCount = 0
        pendingStartTimeMs = null
    }

    private fun isSessionReset(next: KdaSnapshot, previousBaseline: KdaSnapshot?): Boolean {
        if (previousBaseline == null) return false
        // A genuine new match usually resets the whole K/D/A line. Require the
        // lower reading to have already passed the same three-sample stability
        // check used for increases, and avoid treating a kills-only regression
        // as a reset.
        val killsReset = next.kills < previousBaseline.kills
        val supportingReset = next.deaths < previousBaseline.deaths ||
            next.assists < previousBaseline.assists || next == KdaSnapshot(0, 0, 0)
        return killsReset && supportingReset
    }

    private fun estimateCounterTransitionTimeMs(
        previousObservedAtMs: Long?,
        firstIncreasedReadingAtMs: Long
    ): Long {
        if (previousObservedAtMs == null || previousObservedAtMs >= firstIncreasedReadingAtMs) {
            return firstIncreasedReadingAtMs
        }
        return previousObservedAtMs + (firstIncreasedReadingAtMs - previousObservedAtMs) / 2L
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.45f
        private const val MIN_KILL_EVENT_CONFIDENCE = 0.80f
        private const val POSITION_BUCKET = 0.025f
        private const val MIN_REGION_SEPARATION = 0.025f
        private const val REQUIRED_STABLE_READINGS = 3
        private const val DEFAULT_MAX_STABLE_SAMPLE_GAP_MS = 3_000L

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
                isFirstDigitValid = reading.isFirstDigitValid,
                isTripletNumericallyValid = reading.isTripletNumericallyValid
            )
        }
    }
}
