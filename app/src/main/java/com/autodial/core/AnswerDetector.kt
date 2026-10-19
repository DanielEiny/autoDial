package com.autodial.core

/** One row of CallLog.Calls, reduced to what answer detection needs. */
data class CallLogRow(
    val number: String?,
    /** CallLog.Calls.DATE, epoch millis (call creation time in AOSP Telecom). */
    val dateMillis: Long,
    /** CallLog.Calls.DURATION, seconds (truncated active time). */
    val durationSeconds: Long,
    /** CallLog.Calls.TYPE. */
    val type: Int,
)

sealed class Detection {
    data class Answered(val row: CallLogRow) : Detection()
    data class NotAnswered(val row: CallLogRow) : Detection()
    object NotFound : Detection()
}

/**
 * Finds the call log entry for an attempt and classifies it.
 *
 * Matching: TYPE == OUTGOING, number matches ([PhoneNumbers.sameNumber]),
 * DATE >= attemptStart - [CLOCK_SLACK_MS]. The newest matching row wins.
 */
object AnswerDetector {
    const val OUTGOING_TYPE = 2 // CallLog.Calls.OUTGOING_TYPE
    const val CLOCK_SLACK_MS = 5_000L

    fun queryLowerBound(attemptStartMillis: Long): Long = attemptStartMillis - CLOCK_SLACK_MS

    fun findEntry(rows: List<CallLogRow>, dialedNumber: String, attemptStartMillis: Long): CallLogRow? =
        rows.asSequence()
            .filter { it.type == OUTGOING_TYPE }
            .filter { it.dateMillis >= queryLowerBound(attemptStartMillis) }
            .filter { PhoneNumbers.sameNumber(it.number, dialedNumber) }
            .maxByOrNull { it.dateMillis }

    fun detect(
        rows: List<CallLogRow>,
        dialedNumber: String,
        attemptStartMillis: Long,
        answeredThresholdSeconds: Int,
    ): Detection {
        val row = findEntry(rows, dialedNumber, attemptStartMillis) ?: return Detection.NotFound
        return if (row.durationSeconds >= answeredThresholdSeconds) Detection.Answered(row)
        else Detection.NotAnswered(row)
    }
}
