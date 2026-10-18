package com.mlbb.highlight.detection

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KdaSpatialParserTest {

    private fun token(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float
    ) = KdaNumberToken(
        text = text,
        bounds = Rect(left, top, right, bottom),
        confidence = confidence,
        boxLeft = left,
        boxTop = top,
        boxRight = right,
        boxBottom = bottom
    )

    @Test
    fun parseTripletsWithVariousSeparators() {
        val separators = listOf("/", "|", "\\", ":")
        separators.forEach { sep ->
            val tok = token(
                text = "10${sep}2${sep}5",
                left = 100,
                top = 10,
                right = 200,
                bottom = 30,
                confidence = 0.9f
            )
            val result = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080)
            assertEquals("Failed for separator: $sep", 1, result.readings.size)
            val reading = result.readings.single()
            assertEquals(10, reading.kills)
            assertEquals(2, reading.deaths)
            assertEquals(5, reading.assists)
            assertEquals(0.9f, reading.confidence, 0.01f)
        }
    }

    @Test
    fun parseSpatialRowNumbersAsTriplets() {
        val tokens = listOf(
            token("7", 100, 10, 120, 30, 0.85f),
            token("1", 130, 10, 150, 30, 0.90f),
            token("4", 160, 10, 180, 30, 0.88f)
        )
        val result = KdaSpatialParser.parseDetailed(tokens, 1920, 1080)
        assertEquals(1, result.readings.size)
        val reading = result.readings.single()
        assertEquals(7, reading.kills)
        assertEquals(1, reading.deaths)
        assertEquals(4, reading.assists)
    }

    @Test
    fun lowConfidenceTokensAreRejectedWithExplanations() {
        val tok = token("3/1/2", 100, 10, 200, 30, 0.40f)
        val result = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080)
        assertTrue(result.readings.isEmpty())
        assertTrue(result.rejectedReasons.any { it.contains("rejected: confidence below") })
    }

    @Test
    fun parseZeroKdaWithSeparatorsAndSpaces() {
        val formats = listOf("0/0/0", "0 0 0", "0-0-0", "0.0.0", "000")
        formats.forEach { raw ->
            val tok = token(raw, 100, 10, 200, 30, 0.85f)
            val result = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080)
            assertEquals("Failed for zero format: $raw", 1, result.readings.size)
            val reading = result.readings.single()
            assertEquals("Kills not 0 for $raw", 0, reading.kills)
            assertEquals("Deaths not 0 for $raw", 0, reading.deaths)
            assertEquals("Assists not 0 for $raw", 0, reading.assists)
            assertTrue("First digit should be valid for $raw", reading.isFirstDigitValid)
        }
    }

    @Test
    fun letterMisreadsInFirstPositionAreTreatedAsUncertainInsteadOfNumber() {
        val formats = listOf("O/0/0", "o/o/o", "S/2/7", "B/1/3")
        formats.forEach { raw ->
            val tok = token(raw, 100, 10, 200, 30, 0.85f)
            val result = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080)
            assertEquals("Failed for letter format: $raw", 1, result.readings.size)
            val reading = result.readings.single()
            org.junit.Assert.assertFalse(
                "Letter in first character must not be treated as valid digit: $raw",
                reading.isFirstDigitValid
            )
        }
    }

    @Test
    fun implausibleMergedKillCountIsUncertainInsteadOfBeingCountedAsFortyEight() {
        val tok = token("48/0/0", 100, 10, 200, 30, 0.95f)
        val reading = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080).readings.single()
        assertEquals(-1, reading.kills)
        org.junit.Assert.assertFalse(reading.isFirstDigitValid)
    }

    @Test
    fun validTwoDigitKillCountIsRetained() {
        val tok = token("10/8/0", 100, 10, 200, 30, 0.95f)
        val reading = KdaSpatialParser.parseDetailed(listOf(tok), 1920, 1080).readings.single()
        assertEquals(10, reading.kills)
        org.junit.Assert.assertTrue(reading.isFirstDigitValid)
    }
}
