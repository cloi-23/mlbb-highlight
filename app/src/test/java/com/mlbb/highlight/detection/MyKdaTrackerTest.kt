package com.mlbb.highlight.detection

import com.mlbb.highlight.highlight.HighlightEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyKdaTrackerTest {
    @Test
    fun twoSampleModeConfirmsLiveDeathAndKillWithoutThreeSampleWait() {
        val tracker = MyKdaTracker(requiredStableReadings = 2)
        tracker.observe(listOf(candidate(0, 0, 0)), 1_000L)
        val baseline = tracker.observe(listOf(candidate(0, 0, 0)), 2_000L)
        assertEquals(KdaSnapshot(0, 0, 0), baseline.current)

        val firstChangedRead = tracker.observe(listOf(candidate(1, 1, 0)), 3_000L)
        assertNull(firstChangedRead.killEvent)
        val confirmed = tracker.observe(listOf(candidate(1, 1, 0)), 4_000L)

        assertEquals(KdaSnapshot(1, 1, 0), confirmed.current)
        assertEquals(HighlightEventType.KILL, confirmed.killEvent?.type)
        assertEquals(1, confirmed.killEvent?.killsAdded)
    }

    @Test
    fun unchangedPersonalKdaDoesNotCreateKillEvent() {
        val tracker = MyKdaTracker()
        repeat(3) {
            tracker.observe(
                listOf(candidate(5, 2, 3)),
                sourceTimeMs = 1_000L + it * 1_000L
            )
        }

        val update = tracker.observe(
            listOf(candidate(5, 2, 3)),
            sourceTimeMs = 4_000L
        )

        assertNull(update.killEvent)
        assertEquals(KdaSnapshot(5, 2, 3), update.baseline)
        assertEquals(KdaSnapshot(5, 2, 3), update.current)
    }

    @Test
    fun onlyKillCountIncreaseCreatesEventAndRepeatedReadingDoesNotDuplicate() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(7, 3, 4)), sourceTimeMs = 1_000L + it * 1_000L) }

        assertNull(
            tracker.observe(listOf(candidate(7, 4, 4)), sourceTimeMs = 4_000L).killEvent
        )
        assertNull(
            tracker.observe(listOf(candidate(7, 4, 5)), sourceTimeMs = 5_000L).killEvent
        )

        val kill = (6..8).map { sample ->
            tracker.observe(
                listOf(candidate(8, 4, 5)),
                sourceTimeMs = sample * 1_000L
            ).killEvent
        }.filterNotNull().single()
        assertEquals(HighlightEventType.KILL, kill?.type)
        assertEquals(5_500L, kill?.sourceTimeMs)
        assertEquals(6_000L, kill?.counterFirstObservedAtMs)
        assertEquals(8_000L, kill?.counterConfirmedAtMs)

        assertNull(tracker.observe(listOf(candidate(8, 4, 5)), sourceTimeMs = 9_000L).killEvent)
    }

    @Test
    fun ambiguousInitialScoreboardDoesNotChooseAnotherPlayersStats() {
        val tracker = MyKdaTracker()
        val update = tracker.observe(
            listOf(candidate(5, 2, 3, x = 0.2f), candidate(8, 1, 6, x = 0.8f)),
            sourceTimeMs = 1_000L
        )

        assertNull(update.baseline)
        assertNull(update.current)
        assertNull(update.killEvent)

        val baselineUpdate = tracker.observe(
            listOf(candidate(5, 2, 3)),
            sourceTimeMs = 2_000L
        )
        tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 3_000L)
        val stableBaseline = tracker.observe(
            listOf(candidate(5, 2, 3)),
            sourceTimeMs = 4_000L
        )
        assertNull(baselineUpdate.baseline)
        assertEquals(KdaSnapshot(5, 2, 3), stableBaseline.baseline)
        assertNull(stableBaseline.killEvent)
    }

    @Test
    fun conflictingNumbersInSameKdaRegionDoNotEstablishBaseline() {
        val tracker = MyKdaTracker()
        val update = tracker.observe(
            listOf(
                candidate(5, 2, 3),
                candidate(6, 2, 3)
            ),
            sourceTimeMs = 1_000L
        )

        assertNull(update.baseline)
        assertNull(update.killEvent)
    }

    @Test
    fun killCounterIncreaseCreatesGenericKillEvent() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(2, 0, 1)), sourceTimeMs = 1_000L + it * 1_000L) }

        val update = (4..6).map { sample ->
            tracker.observe(
                listOf(candidate(3, 0, 1)),
                sourceTimeMs = sample * 1_000L
            )
        }.last()

        assertEquals(HighlightEventType.KILL, update.killEvent?.type)
        assertEquals(KdaSnapshot(2, 0, 1), update.baseline)
        assertEquals(KdaSnapshot(3, 0, 1), update.current)
    }

    @Test
    fun lowerOcrKillReadingDoesNotResetBaselineOrCreateLaterDuplicate() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 1_000L + it * 1_000L) }

        assertNull(tracker.observe(listOf(candidate(4, 2, 3)), sourceTimeMs = 4_000L).killEvent)
        repeat(3) { tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 5_000L + it * 1_000L) }
        assertEquals(KdaSnapshot(5, 2, 3), tracker.baseline)
        assertEquals(KdaSnapshot(5, 2, 3), tracker.current)
    }

    @Test
    fun unstableKillReadingsDoNotCreateDuplicateHighlights() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 1_000L + it * 1_000L) }

        val oscillating = listOf(6, 5, 6, 5, 6).mapIndexed { index, kills ->
            tracker.observe(
                listOf(candidate(kills, 2, 3)),
                sourceTimeMs = 4_000L + index * 1_000L
            )
        }
        assertTrue(oscillating.none { it.killEvent != null })

        val confirmed = (9..11).map { sample ->
            tracker.observe(
                listOf(candidate(6, 2, 3)),
                sourceTimeMs = sample * 1_000L
            )
        }
        assertEquals(1, confirmed.count { it.killEvent != null })
        assertEquals(5, tracker.lastKillPrevious)
        assertEquals(6, tracker.lastKillCurrent)
    }

    @Test
    fun stableCounterJumpEmitsOneBundledTransitionEvent() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 1_000L + it * 1_000L) }
        val confirmed = (4..6).map { sample ->
            tracker.observe(listOf(candidate(7, 2, 3)), sourceTimeMs = sample * 1_000L)
        }

        assertTrue(confirmed.take(2).all { it.killEvent == null })
        val jumpEvent = confirmed.last().killEvent
        assertEquals(HighlightEventType.KILL, jumpEvent?.type)
        assertTrue(confirmed.last().decision.contains("individual kill times unknown"))
        assertEquals(KdaSnapshot(7, 2, 3), confirmed.last().current)
        val observed = confirmed.last().counterIncrease
        assertEquals(2, observed?.killsAdded)
        assertEquals(4_000L, observed?.firstObservedAtMs)
        assertEquals(6_000L, observed?.confirmedAtMs)
        assertEquals(3_000L, observed?.previousObservedAtMs)
        assertEquals(7, tracker.lastConfirmedKills)
        assertEquals(2, jumpEvent?.killsAdded)
        assertEquals(3_500L, jumpEvent?.sourceTimeMs)
        assertEquals(4_000L, jumpEvent?.counterFirstObservedAtMs)
        assertEquals(6_000L, jumpEvent?.counterConfirmedAtMs)
        assertNull(tracker.observe(listOf(candidate(7, 2, 3)), 7_000L).killEvent)

        val nextSequentialKill = (8..10).mapNotNull { sample ->
            tracker.observe(listOf(candidate(8, 2, 3)), sourceTimeMs = sample * 1_000L).killEvent
        }.single()
        assertEquals(HighlightEventType.KILL, nextSequentialKill.type)
        assertEquals(7, tracker.lastKillPrevious)
        assertEquals(8, tracker.lastKillCurrent)
    }

    @Test
    fun killIncreaseRequiresThreeStableReadings() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(0, 0, 0)), sourceTimeMs = 1_000L + it * 1_000L) }

        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 4_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 5_000L).killEvent)
        assertEquals(
            HighlightEventType.KILL,
            tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 6_000L).killEvent?.type
        )
    }

    @Test
    fun missingHudReadingsResetTheStabilitySequence() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(0, 0, 0)), sourceTimeMs = 1_000L + it * 1_000L) }

        tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 4_000L)
        tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 5_000L)
        assertEquals(
            "Tracked K/D/A region not visible",
            tracker.observe(emptyList(), sourceTimeMs = 6_000L).decision
        )
        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 7_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 8_000L).killEvent)
        assertEquals(
            HighlightEventType.KILL,
            tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 9_000L).killEvent?.type
        )
    }

    @Test
    fun duplicateSourceTimestampDoesNotCountAsAnotherStableFrame() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(0, 0, 0)), sourceTimeMs = 1_000L + it * 1_000L) }

        val first = tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 4_000L)
        val duplicate = tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 4_000L)
        assertEquals(1, first.stableFrameCount)
        assertEquals(1, duplicate.stableFrameCount)
        assertTrue(duplicate.decision.contains("timestamp ignored"))
        assertNull(duplicate.killEvent)

        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 5_000L).killEvent)
        val confirmed = tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 6_000L)
        assertEquals(HighlightEventType.KILL, confirmed.killEvent?.type)
    }

    @Test
    fun uniqueValidatedReadingCanRelockAfterHudMovesWithoutIconText() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(0, 0, 0, x = 0.2f)), 1_000L + it * 1_000L) }
        val firstMovedFrame = tracker.observe(listOf(candidate(1, 0, 0, x = 0.8f)), 4_000L)
        assertEquals(1, firstMovedFrame.stableFrameCount)
        assertEquals("1/0/0", firstMovedFrame.selectedCandidate?.rawText)
        assertNull(firstMovedFrame.killEvent)

        tracker.observe(listOf(candidate(1, 0, 0, x = 0.8f)), 5_000L)
        val confirmed = tracker.observe(listOf(candidate(1, 0, 0, x = 0.8f)), 6_000L)
        assertEquals(HighlightEventType.KILL, confirmed.killEvent?.type)
    }

    @Test
    fun lowConfidenceStableKdaUpdatesLiveStateButKillNeedsReview() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(5, 2, 3)), sourceTimeMs = 1_000L + it * 1_000L) }
        val uncertain = (4..6).map { sample ->
            tracker.observe(
                listOf(candidate(6, 2, 3, confidence = 0.7f)),
                sourceTimeMs = sample * 1_000L
            )
        }.last()

        assertNull(uncertain.killEvent)
        assertEquals("Kill increase needs review: OCR confidence too low", uncertain.decision)
        assertEquals(KdaSnapshot(6, 2, 3), uncertain.current)
        assertEquals(5, uncertain.lastConfirmedK)

        val confirmed = (7..9).mapNotNull { sample ->
            tracker.observe(
                listOf(candidate(6, 2, 3)),
                sourceTimeMs = sample * 1_000L
            ).killEvent
        }.single()
        assertEquals(HighlightEventType.KILL, confirmed.type)
        assertEquals(KdaSnapshot(6, 2, 3), tracker.current)
    }

    @Test
    fun tracksFromZeroKillsCleanlyCountingUp() {
        val tracker = MyKdaTracker()
        // Baseline 0/0/0
        repeat(3) {
            tracker.observe(listOf(candidate(0, 0, 0)), sourceTimeMs = 1_000L * (it + 1))
        }
        assertEquals(KdaSnapshot(0, 0, 0), tracker.baseline)

        // Kill 1 (0 -> 1)
        val k1 = (4..6).mapNotNull {
            tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 1_000L * it).killEvent
        }.single()
        assertEquals(3_500L, k1.sourceTimeMs)
        assertEquals(1, tracker.current?.kills)

        // Kill 2 (1 -> 2)
        val k2 = (7..9).mapNotNull {
            tracker.observe(listOf(candidate(2, 0, 1)), sourceTimeMs = 1_000L * it).killEvent
        }.single()
        assertEquals(6_500L, k2.sourceTimeMs)
        assertEquals(2, tracker.current?.kills)

        // Kill 3 (2 -> 3)
        val k3 = (10..12).mapNotNull {
            tracker.observe(listOf(candidate(3, 1, 1)), sourceTimeMs = 1_000L * it).killEvent
        }.single()
        assertEquals(9_500L, k3.sourceTimeMs)
        assertEquals(3, tracker.current?.kills)
    }

    @Test
    fun changesToDeathsOrAssistsNeverTriggerKillEvent() {
        val tracker = MyKdaTracker()
        repeat(3) {
            tracker.observe(listOf(candidate(5, 2, 7)), sourceTimeMs = 1_000L + it * 1_000L)
        }
        assertEquals(5, tracker.lastConfirmedKills)

        // Deaths increases from 2 to 3, assists stays 7
        val deathUpdate1 = tracker.observe(listOf(candidate(5, 3, 7)), sourceTimeMs = 4_000L)
        assertNull(deathUpdate1.killEvent)
        val deathUpdate2 = tracker.observe(listOf(candidate(5, 3, 7)), sourceTimeMs = 5_000L)
        assertNull(deathUpdate2.killEvent)
        val deathUpdate3 = tracker.observe(listOf(candidate(5, 3, 7)), sourceTimeMs = 6_000L)
        assertNull(deathUpdate3.killEvent)
        assertEquals(5, tracker.lastConfirmedKills)

        // Assists increases from 7 to 8
        val assistUpdate1 = tracker.observe(listOf(candidate(5, 3, 8)), sourceTimeMs = 7_000L)
        assertNull(assistUpdate1.killEvent)
        val assistUpdate2 = tracker.observe(listOf(candidate(5, 3, 8)), sourceTimeMs = 8_000L)
        assertNull(assistUpdate2.killEvent)
        val assistUpdate3 = tracker.observe(listOf(candidate(5, 3, 8)), sourceTimeMs = 9_000L)
        assertNull(assistUpdate3.killEvent)
        assertEquals(5, tracker.lastConfirmedKills)
    }

    @Test
    fun changingMapOrHudNumbersResetTheWholeKdaVerificationSequence() {
        val tracker = MyKdaTracker()
        repeat(3) {
            tracker.observe(listOf(candidate(0, 0, 0)), sourceTimeMs = 1_000L + it * 1_000L)
        }

        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 4_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 1, 0)), sourceTimeMs = 5_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 0, 1)), sourceTimeMs = 6_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 7_000L).killEvent)
        assertNull(tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 8_000L).killEvent)
        assertEquals(
            HighlightEventType.KILL,
            tracker.observe(listOf(candidate(1, 0, 0)), sourceTimeMs = 9_000L).killEvent?.type
        )
    }

    @Test
    fun stableFullKdaRegressionStartsNewSessionAndAllowsNewKills() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(8, 4, 11)), 1_000L + it * 1_000L) }

        val reset = (4..6).map { tracker.observe(listOf(candidate(0, 0, 0)), it * 1_000L) }
        assertTrue(reset.take(2).all { it.killEvent == null })
        assertTrue(reset.last().decision.contains("new match/session baseline"))
        assertEquals(KdaSnapshot(0, 0, 0), tracker.baseline)
        assertEquals(0, tracker.lastConfirmedKills)

        val firstKill = (7..9).mapNotNull {
            tracker.observe(listOf(candidate(1, 0, 0)), it * 1_000L).killEvent
        }.single()
        assertEquals(1, firstKill.killsAdded)
        assertEquals(0, firstKill.previousKillCount)
        assertEquals(1, firstKill.currentKillCount)
    }

    @Test
    fun staleTimestampCannotOverwriteNewerLiveKdaOrEmitAnEvent() {
        val tracker = MyKdaTracker()
        repeat(3) { tracker.observe(listOf(candidate(2, 0, 1)), 1_000L + it * 1_000L) }
        tracker.observe(listOf(candidate(3, 0, 1)), 4_000L)
        tracker.observe(listOf(candidate(3, 0, 1)), 5_000L)
        val newest = tracker.observe(listOf(candidate(3, 0, 1)), 6_000L)
        val stale = tracker.observe(listOf(candidate(9, 0, 1)), 5_500L)

        assertEquals(HighlightEventType.KILL, newest.killEvent?.type)
        assertTrue(stale.decision.contains("timestamp ignored"))
        assertEquals(KdaSnapshot(3, 0, 1), stale.current)
        assertNull(stale.killEvent)
    }

    @Test
    fun temporaryLetterMisreadInFirstDigitRetainsConfirmedKillsAndDoesNotTriggerKill() {
        val tracker = MyKdaTracker()
        repeat(3) {
            tracker.observe(listOf(candidate(5, 2, 7)), sourceTimeMs = 1_000L + it * 1_000L)
        }
        assertEquals(5, tracker.lastConfirmedKills)

        // First character misread as letter 'S' (e.g. S/2/7)
        val uncertainCandidate = PositionedKda(
            snapshot = KdaSnapshot(0, 2, 7),
            centerX = 0.2f,
            centerY = 0.2f,
            confidence = 0.9f,
            rawText = "S/2/7",
            firstChar = "S",
            isFirstDigitValid = false
        )
        val update = tracker.observe(listOf(uncertainCandidate), sourceTimeMs = 4_000L)
        assertNull(update.killEvent)
        assertEquals(false, update.killConfirmed)
        assertEquals(5, tracker.lastConfirmedKills)
        assertTrue(update.decision.contains("Uncertain reading"))

        // Next frame reads 5/2/7 again
        val nextUpdate = tracker.observe(listOf(candidate(5, 2, 7)), sourceTimeMs = 5_000L)
        assertNull(nextUpdate.killEvent)
        assertEquals(5, tracker.lastConfirmedKills)
    }

    @Test
    fun debugOutputContainsAllRequiredFields() {
        val tracker = MyKdaTracker()
        val candidate = PositionedKda(
            snapshot = KdaSnapshot(5, 2, 7),
            centerX = 0.2f,
            centerY = 0.2f,
            confidence = 0.92f,
            rawText = "5/2/7",
            firstChar = "5",
            isFirstDigitValid = true
        )
        val update = tracker.observe(listOf(candidate), sourceTimeMs = 1_000L)
        assertEquals("5/2/7", update.rawOcrResult)
        assertEquals("5", update.firstChar)
        assertEquals(false, update.killConfirmed)
        assertEquals(0.92f, update.selectedCandidate?.confidence ?: 0f, 0.01f)
    }

    private fun candidate(
        kills: Int,
        deaths: Int,
        assists: Int,
        x: Float = 0.2f,
        confidence: Float = 0.95f
    ) = PositionedKda(
        snapshot = KdaSnapshot(kills, deaths, assists),
        centerX = x,
        centerY = 0.2f,
        confidence = confidence,
        rawText = "$kills/$deaths/$assists",
        firstChar = kills.toString(),
        isFirstDigitValid = true
    )
}
