package com.autodial.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Mirrors TelephonyManager.CALL_STATE_* (IDLE=0, RINGING=1, OFFHOOK=2). */
enum class CallState { IDLE, RINGING, OFFHOOK }

/** Platform access needed by the session. Implemented with real Android APIs in the service, faked in tests. */
interface Phone {
    /** Null if calls can be placed now, otherwise a human-readable reason (no SIM, airplane mode, permission...). */
    fun preflightProblem(): String?
    fun isInCall(): Boolean
    /** Asks Telecom to place the call. Throws on failure (e.g. SecurityException). */
    fun placeCall(number: String, speakerphone: Boolean)
    fun currentCallState(): CallState
    /** Outgoing call log rows with DATE >= [sinceMillis], newest first. Throws SecurityException without READ_CALL_LOG. */
    fun queryCallLog(sinceMillis: Long): List<CallLogRow>
}

sealed class SessionStatus {
    data class WaitingForOtherCall(val attempt: Int) : SessionStatus()
    data class Dialing(val attempt: Int, val maxAttempts: Int) : SessionStatus()
    data class InCall(val attempt: Int, val maxAttempts: Int) : SessionStatus()
    data class CheckingCallLog(val attempt: Int) : SessionStatus()
    data class WaitingToRedial(val nextAttempt: Int, val maxAttempts: Int, val secondsLeft: Int) : SessionStatus()
}

data class SessionResult(val reason: StopReason, val attempts: Int, val detail: String)

class DialSession(
    private val number: String,
    config: RetryConfig,
    private val phone: Phone,
    private val speakerphone: Boolean,
    /** Every call state change from TelephonyCallback, in order (unbounded channel). */
    private val callStates: ReceiveChannel<CallState>,
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit,
    private val onStatus: (SessionStatus) -> Unit = {},
    private val timing: Timing = Timing(),
) {
    data class Timing(
        /** How long to wait for OFFHOOK after placeCall before assuming the call failed to start. */
        val offhookTimeoutMs: Long = 30_000,
        /** How long to keep polling the call log after the call ended. */
        val callLogWaitMs: Long = 20_000,
        val callLogPollMs: Long = 1_000,
        val inCallCheckMs: Long = 5_000,
    )

    private val policy = RetryPolicy(config)
    val config: RetryConfig get() = policy.config

    suspend fun run(): SessionResult {
        val cfg = policy.config
        log("Session start: number=${PhoneNumbers.mask(number)} delay=${cfg.delaySeconds}s " +
            "maxAttempts=${if (cfg.maxAttempts == RetryConfig.UNLIMITED) "unlimited" else cfg.maxAttempts} " +
            "answeredIfDuration>=${cfg.answeredThresholdSeconds}s speakerphone=$speakerphone")
        var attempt = 0
        try {
            while (true) {
                attempt++
                val outcome = runAttempt(attempt)
                val decision = policy.decide(attempt, outcome.first)
                log("Attempt $attempt outcome=${outcome.first} (${outcome.second}) -> decision=$decision")
                when (decision) {
                    is RetryDecision.Stop -> return finish(SessionResult(decision.reason, attempt, outcome.second))
                    is RetryDecision.Retry -> waitBeforeRedial(attempt + 1, decision.delaySeconds)
                }
            }
        } catch (e: CancellationException) {
            log("Session cancelled (user stop or service shutdown) during attempt $attempt. " +
                "A call in progress is NOT hung up by the app.")
            throw e
        } catch (e: PreconditionException) {
            return finish(SessionResult(StopReason.PRECONDITION_FAILED, attempt, e.message.orEmpty()))
        }
    }

    private fun finish(r: SessionResult): SessionResult {
        log("Session end: reason=${r.reason} attempts=${r.attempts} detail=${r.detail}")
        return r
    }

    private class PreconditionException(msg: String) : Exception(msg)

    /** Returns outcome and a short detail string. */
    private suspend fun runAttempt(attempt: Int): Pair<AttemptOutcome, String> {
        val max = policy.config.maxAttempts

        phone.preflightProblem()?.let {
            log("Attempt $attempt preflight failed: $it")
            throw PreconditionException(it)
        }

        if (phone.isInCall()) {
            log("Attempt $attempt: another call is in progress; waiting until it ends")
            onStatus(SessionStatus.WaitingForOtherCall(attempt))
            while (phone.isInCall()) delay(timing.inCallCheckMs)
            log("Attempt $attempt: other call ended")
        }

        // Drop stale state events so we only see this attempt's transitions.
        while (callStates.tryReceive().isSuccess) Unit

        onStatus(SessionStatus.Dialing(attempt, max))
        val start = nowMillis()
        try {
            phone.placeCall(number, speakerphone)
            log("Attempt $attempt/${if (max == RetryConfig.UNLIMITED) "∞" else max}: " +
                "placeCall(${PhoneNumbers.mask(number)}, speaker=$speakerphone) requested at t0=$start")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("Attempt $attempt: placeCall FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return AttemptOutcome.PLACE_FAILED to "placeCall threw ${e.javaClass.simpleName}"
        }

        val sawOffhook = withTimeoutOrNull(timing.offhookTimeoutMs) {
            waitForState(attempt, start, CallState.OFFHOOK)
            true
        } ?: false

        if (sawOffhook) {
            onStatus(SessionStatus.InCall(attempt, max))
            waitForState(attempt, start, CallState.IDLE)
        } else {
            val now = phone.currentCallState()
            log("Attempt $attempt: no OFFHOOK within ${timing.offhookTimeoutMs}ms; current state=$now")
            if (now != CallState.IDLE) {
                onStatus(SessionStatus.InCall(attempt, max))
                waitForState(attempt, start, CallState.IDLE)
            }
        }

        onStatus(SessionStatus.CheckingCallLog(attempt))
        val detection = pollCallLog(attempt, start)
        return when (detection) {
            is Detection.Answered ->
                AttemptOutcome.ANSWERED to "duration=${detection.row.durationSeconds}s"
            is Detection.NotAnswered ->
                AttemptOutcome.NOT_ANSWERED to "duration=${detection.row.durationSeconds}s"
            Detection.NotFound ->
                if (sawOffhook) AttemptOutcome.RESULT_UNKNOWN to "no call log entry after ${timing.callLogWaitMs}ms"
                else AttemptOutcome.PLACE_FAILED to "call never went off-hook and no call log entry"
        }
    }

    private suspend fun waitForState(attempt: Int, start: Long, wanted: CallState) {
        while (true) {
            val s = callStates.receive()
            log("Attempt $attempt: call state -> $s (+${nowMillis() - start}ms)")
            if (s == wanted) return
        }
    }

    private suspend fun pollCallLog(attempt: Int, start: Long): Detection {
        val since = AnswerDetector.queryLowerBound(start)
        val ended = nowMillis()
        var polls = 0
        while (true) {
            polls++
            val rows = try {
                phone.queryCallLog(since)
            } catch (e: SecurityException) {
                log("Attempt $attempt: call log query denied: ${e.message}")
                throw PreconditionException("READ_CALL_LOG denied")
            }
            val d = AnswerDetector.detect(rows, number, start, policy.config.answeredThresholdSeconds)
            if (d !is Detection.NotFound || polls == 1) {
                log("Attempt $attempt: call log query #$polls (+${nowMillis() - ended}ms after IDLE): " +
                    "${rows.size} outgoing row(s) since t0-${AnswerDetector.CLOCK_SLACK_MS}ms " +
                    rows.joinToString(prefix = "[", postfix = "]") { describe(it, start) } + " -> ${describe(d, start)}")
            }
            if (d !is Detection.NotFound) return d
            if (nowMillis() - ended >= timing.callLogWaitMs) {
                log("Attempt $attempt: no matching call log entry after $polls queries " +
                    "(${nowMillis() - ended}ms); last rows=" +
                    rows.joinToString(prefix = "[", postfix = "]") { describe(it, start) })
                return d
            }
            delay(timing.callLogPollMs)
        }
    }

    private fun describe(r: CallLogRow, start: Long) =
        "{num=${PhoneNumbers.mask(r.number)} date=t0${signed(r.dateMillis - start)}ms dur=${r.durationSeconds}s type=${r.type}}"

    /** Never log Detection.toString(): it contains the unmasked number. */
    private fun describe(d: Detection, start: Long) = when (d) {
        is Detection.Answered -> "ANSWERED ${describe(d.row, start)}"
        is Detection.NotAnswered -> "NOT_ANSWERED ${describe(d.row, start)}"
        Detection.NotFound -> "NOT_FOUND"
    }

    private fun signed(v: Long) = if (v >= 0) "+$v" else "$v"

    private suspend fun waitBeforeRedial(nextAttempt: Int, delaySeconds: Int) {
        val target = nowMillis() + delaySeconds * 1000L
        log("Waiting ${delaySeconds}s before attempt $nextAttempt")
        while (true) {
            val left = target - nowMillis()
            if (left <= 0) break
            onStatus(SessionStatus.WaitingToRedial(nextAttempt, policy.config.maxAttempts, ((left + 999) / 1000).toInt()))
            delay(minOf(1000L, left))
        }
        val overrun = nowMillis() - target
        if (overrun > 5_000) {
            log("WARNING: redial delay overran by ${overrun}ms (device sleep/Doze? check battery exemption)")
        }
    }
}
