package com.autodial.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun sanitizedAllowsZeroDelayAndLargeAttemptCounts() {
        val c = RetryConfig(delaySeconds = 0, maxAttempts = 10_000, answeredThresholdSeconds = 0).sanitized()
        assertEquals(0, c.delaySeconds)
        assertEquals(10_000, c.maxAttempts)
        assertEquals(1, c.answeredThresholdSeconds)
    }

    @Test
    fun sanitizedClampsInvalidValues() {
        val c = RetryConfig(delaySeconds = -5, maxAttempts = -1, answeredThresholdSeconds = 999).sanitized()
        assertEquals(0, c.delaySeconds)
        assertEquals(RetryConfig.UNLIMITED, c.maxAttempts)
        assertEquals(RetryConfig.MAX_THRESHOLD_SECONDS, c.answeredThresholdSeconds)
        assertEquals(RetryConfig.MAX_DELAY_SECONDS, RetryConfig(999_999, 1).sanitized().delaySeconds)
    }

    @Test
    fun zeroDelayRetriesImmediately() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 0, maxAttempts = 3))
        assertEquals(RetryDecision.Retry(0), p.decide(1, AttemptOutcome.NOT_ANSWERED))
    }

    @Test
    fun unlimitedAttemptsNeverStopOnNotAnswered() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 10, maxAttempts = RetryConfig.UNLIMITED))
        for (n in listOf(1, 50, 1_000, 1_000_000)) {
            assertEquals(RetryDecision.Retry(10), p.decide(n, AttemptOutcome.NOT_ANSWERED))
        }
        assertEquals(RetryDecision.Stop(StopReason.ANSWERED), p.decide(1_001, AttemptOutcome.ANSWERED))
    }

    @Test
    fun notAnsweredRetriesUntilMaxAttempts() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 20, maxAttempts = 3))
        assertEquals(RetryDecision.Retry(20), p.decide(1, AttemptOutcome.NOT_ANSWERED))
        assertEquals(RetryDecision.Retry(20), p.decide(2, AttemptOutcome.NOT_ANSWERED))
        assertEquals(RetryDecision.Stop(StopReason.MAX_ATTEMPTS_REACHED), p.decide(3, AttemptOutcome.NOT_ANSWERED))
    }

    @Test
    fun answeredStopsImmediately() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 20, maxAttempts = 5))
        assertEquals(RetryDecision.Stop(StopReason.ANSWERED), p.decide(1, AttemptOutcome.ANSWERED))
    }

    @Test
    fun unknownResultStopsConservatively() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 20, maxAttempts = 5))
        assertEquals(RetryDecision.Stop(StopReason.RESULT_UNKNOWN), p.decide(1, AttemptOutcome.RESULT_UNKNOWN))
    }

    @Test
    fun placeFailureStops() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 20, maxAttempts = 5))
        assertEquals(RetryDecision.Stop(StopReason.PLACE_FAILED), p.decide(2, AttemptOutcome.PLACE_FAILED))
    }

    @Test
    fun singleAttemptConfigNeverRetries() {
        val p = RetryPolicy(RetryConfig(delaySeconds = 20, maxAttempts = 1))
        assertEquals(RetryDecision.Stop(StopReason.MAX_ATTEMPTS_REACHED), p.decide(1, AttemptOutcome.NOT_ANSWERED))
    }
}
