package com.autodial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerDetectorTest {
    private val start = 1_700_000_000_000L
    private val number = "0501234567"
    private fun row(num: String? = number, date: Long = start + 800, dur: Long = 0, type: Int = 2) =
        CallLogRow(num, date, dur, type)

    @Test
    fun zeroDurationIsNotAnswered() {
        val d = AnswerDetector.detect(listOf(row(dur = 0)), number, start, 1)
        assertTrue(d is Detection.NotAnswered)
    }

    @Test
    fun positiveDurationIsAnswered() {
        val d = AnswerDetector.detect(listOf(row(dur = 12)), number, start, 1)
        assertEquals(Detection.Answered(row(dur = 12)), d)
    }

    @Test
    fun thresholdIsInclusive() {
        assertTrue(AnswerDetector.detect(listOf(row(dur = 4)), number, start, 5) is Detection.NotAnswered)
        assertTrue(AnswerDetector.detect(listOf(row(dur = 5)), number, start, 5) is Detection.Answered)
    }

    @Test
    fun emptyLogIsNotFound() {
        assertEquals(Detection.NotFound, AnswerDetector.detect(emptyList(), number, start, 1))
    }

    @Test
    fun entriesBeforeAttemptAreIgnored() {
        val old = row(date = start - AnswerDetector.CLOCK_SLACK_MS - 1, dur = 60)
        assertEquals(Detection.NotFound, AnswerDetector.detect(listOf(old), number, start, 1))
    }

    @Test
    fun smallClockSkewIsTolerated() {
        val early = row(date = start - 3_000, dur = 0)
        assertTrue(AnswerDetector.detect(listOf(early), number, start, 1) is Detection.NotAnswered)
    }

    @Test
    fun otherNumbersAndTypesAreIgnored() {
        val rows = listOf(
            row(num = "0529999999", dur = 30),
            row(type = 1, dur = 30), // incoming from same number
            row(type = 3), // missed
        )
        assertEquals(Detection.NotFound, AnswerDetector.detect(rows, number, start, 1))
    }

    @Test
    fun internationalFormatMatchesLocal() {
        val d = AnswerDetector.detect(listOf(row(num = "+972501234567", dur = 3)), number, start, 1)
        assertTrue(d is Detection.Answered)
    }

    @Test
    fun newestMatchingRowWins() {
        val rows = listOf(row(date = start + 100, dur = 0), row(date = start + 900, dur = 7))
        val d = AnswerDetector.detect(rows, number, start, 1)
        assertEquals(7L, (d as Detection.Answered).row.durationSeconds)
    }

    @Test
    fun nullNumberDoesNotMatch() {
        assertNull(AnswerDetector.findEntry(listOf(row(num = null, dur = 5)), number, start))
    }
}
