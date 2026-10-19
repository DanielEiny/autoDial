package com.autodial.core

/** User-configurable settings. Always pass through [sanitized] before use. */
data class RetryConfig(
    val delaySeconds: Int,
    /** 0 = unlimited (redial until answered or stopped). */
    val maxAttempts: Int,
    /** A call log duration >= this counts as answered. 1 means "duration > 0". */
    val answeredThresholdSeconds: Int = 1,
) {
    fun sanitized(): RetryConfig = RetryConfig(
        delaySeconds = delaySeconds.coerceIn(0, MAX_DELAY_SECONDS),
        maxAttempts = maxAttempts.coerceAtLeast(UNLIMITED),
        answeredThresholdSeconds = answeredThresholdSeconds.coerceIn(1, MAX_THRESHOLD_SECONDS),
    )

    companion object {
        const val MAX_DELAY_SECONDS = 3600
        const val UNLIMITED = 0
        const val MAX_THRESHOLD_SECONDS = 60
        val DEFAULT = RetryConfig(delaySeconds = 30, maxAttempts = 10, answeredThresholdSeconds = 1)
    }
}

enum class AttemptOutcome {
    /** Call log shows duration >= threshold. */
    ANSWERED,
    /** Call log shows an outgoing entry with duration below threshold. */
    NOT_ANSWERED,
    /** No matching call log entry appeared within the wait window. */
    RESULT_UNKNOWN,
    /** The call could not be placed (permission, SIM, telecom error). */
    PLACE_FAILED,
}

enum class StopReason {
    ANSWERED,
    MAX_ATTEMPTS_REACHED,
    RESULT_UNKNOWN,
    PLACE_FAILED,
    USER_STOPPED,
    PRECONDITION_FAILED,
}

sealed class RetryDecision {
    data class Retry(val delaySeconds: Int) : RetryDecision()
    data class Stop(val reason: StopReason) : RetryDecision()
}

/**
 * Decides what happens after an attempt. Conservative: anything other than a confirmed
 * "not answered" stops the session, so the app never keeps calling someone who may have answered.
 */
class RetryPolicy(config: RetryConfig) {
    val config: RetryConfig = config.sanitized()

    /** @param attemptsMade number of attempts completed so far, including the one just finished (1-based). */
    fun decide(attemptsMade: Int, outcome: AttemptOutcome): RetryDecision = when (outcome) {
        AttemptOutcome.ANSWERED -> RetryDecision.Stop(StopReason.ANSWERED)
        AttemptOutcome.RESULT_UNKNOWN -> RetryDecision.Stop(StopReason.RESULT_UNKNOWN)
        AttemptOutcome.PLACE_FAILED -> RetryDecision.Stop(StopReason.PLACE_FAILED)
        AttemptOutcome.NOT_ANSWERED ->
            if (config.maxAttempts != RetryConfig.UNLIMITED && attemptsMade >= config.maxAttempts) {
                RetryDecision.Stop(StopReason.MAX_ATTEMPTS_REACHED)
            } else {
                RetryDecision.Retry(config.delaySeconds)
            }
    }
}
