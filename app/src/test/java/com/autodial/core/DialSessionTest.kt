package com.autodial.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DialSessionTest {

    private val number = "0501234567"
    private val epoch = 1_700_000_000_000L

    /** Scripted behaviour for one attempt. */
    data class Script(
        val emitOffhook: Boolean = true,
        val callLengthMs: Long = 20_000,
        /** Duration written to the call log, or null for "no entry ever written". */
        val loggedDuration: Long? = 0,
        val logDelayMs: Long = 1_500,
        val placeThrows: Exception? = null,
    )

    private class FakePhone(
        val scope: TestScope,
        val states: Channel<CallState>,
        val scripts: List<Script>,
        val now: () -> Long,
    ) : Phone {
        var preflight: String? = null
        var inCallUntil: Long = 0
        var placed = 0
        var callLogDenied = false
        val rows = mutableListOf<CallLogRow>()
        var lastState = CallState.IDLE
        val speakerFlags = mutableListOf<Boolean>()

        override fun preflightProblem() = preflight
        override fun isInCall() = now() < inCallUntil
        override fun currentCallState() = lastState

        override fun placeCall(number: String, speakerphone: Boolean) {
            speakerFlags += speakerphone
            val s = scripts[placed]
            placed++
            s.placeThrows?.let { throw it }
            val t0 = now()
            scope.launch {
                if (s.emitOffhook) {
                    kotlinx.coroutines.delay(300)
                    lastState = CallState.OFFHOOK
                    states.send(CallState.OFFHOOK)
                    kotlinx.coroutines.delay(s.callLengthMs)
                    lastState = CallState.IDLE
                    states.send(CallState.IDLE)
                }
                if (s.loggedDuration != null) {
                    kotlinx.coroutines.delay(s.logDelayMs)
                    rows += CallLogRow("+972501234567", t0 + 50, s.loggedDuration, AnswerDetector.OUTGOING_TYPE)
                }
            }
        }

        override fun queryCallLog(sinceMillis: Long): List<CallLogRow> {
            if (callLogDenied) throw SecurityException("denied")
            return rows.filter { it.dateMillis >= sinceMillis }.sortedByDescending { it.dateMillis }
        }
    }

    private fun TestScope.session(
        scripts: List<Script>,
        config: RetryConfig = RetryConfig(delaySeconds = 15, maxAttempts = 5),
        logs: MutableList<String> = mutableListOf(),
        speakerphone: Boolean = true,
        configure: FakePhone.() -> Unit = {},
    ): Pair<DialSession, FakePhone> {
        val states = Channel<CallState>(Channel.UNLIMITED)
        val now = { epoch + currentTime }
        val phone = FakePhone(this, states, scripts, now).apply(configure)
        return DialSession(number, config, phone, speakerphone, states, now, { logs += it }) to phone
    }

    @Test
    fun answeredOnFirstAttemptStops() = runTest {
        val (s, phone) = session(listOf(Script(loggedDuration = 42)))
        val r = s.run()
        assertEquals(StopReason.ANSWERED, r.reason)
        assertEquals(1, r.attempts)
        assertEquals(1, phone.placed)
    }

    @Test
    fun redialsUntilAnswered() = runTest {
        val (s, phone) = session(listOf(Script(), Script(), Script(loggedDuration = 5)))
        val r = s.run()
        assertEquals(StopReason.ANSWERED, r.reason)
        assertEquals(3, r.attempts)
        assertEquals(3, phone.placed)
    }

    @Test
    fun stopsAtMaxAttempts() = runTest {
        val (s, phone) = session(List(3) { Script() }, RetryConfig(delaySeconds = 15, maxAttempts = 3))
        val r = s.run()
        assertEquals(StopReason.MAX_ATTEMPTS_REACHED, r.reason)
        assertEquals(3, phone.placed)
    }

    @Test
    fun waitsConfiguredDelayBetweenAttempts() = runTest {
        val placedAt = mutableListOf<Long>()
        val states = Channel<CallState>(Channel.UNLIMITED)
        val now = { epoch + currentTime }
        val inner = FakePhone(this, states, listOf(Script(callLengthMs = 10_000), Script(loggedDuration = 3)), now)
        val phone = object : Phone by inner {
            override fun placeCall(number: String, speakerphone: Boolean) {
                placedAt += currentTime
                inner.placeCall(number, speakerphone)
            }
        }
        DialSession(number, RetryConfig(delaySeconds = 60, maxAttempts = 5), phone, true, states, now, {}).run()
        // gap = 300ms to OFFHOOK + 10s call + 1.5s log delay (polled at 1s granularity) + 60s delay
        val gap = placedAt[1] - placedAt[0]
        assertTrue("gap=$gap", gap in 71_300L..73_000L)
    }

    @Test
    fun delayedCallLogEntryIsFound() = runTest {
        val (s, _) = session(listOf(Script(loggedDuration = 9, logDelayMs = 12_000)))
        assertEquals(StopReason.ANSWERED, s.run().reason)
    }

    @Test
    fun missingCallLogEntryStopsAsUnknown() = runTest {
        val logs = mutableListOf<String>()
        val (s, phone) = session(listOf(Script(loggedDuration = null)), logs = logs)
        val r = s.run()
        assertEquals(StopReason.RESULT_UNKNOWN, r.reason)
        assertEquals(1, phone.placed)
        assertTrue(logs.any { it.contains("no matching call log entry") })
    }

    @Test
    fun callThatNeverStartsIsPlaceFailure() = runTest {
        val (s, _) = session(listOf(Script(emitOffhook = false, loggedDuration = null)))
        assertEquals(StopReason.PLACE_FAILED, s.run().reason)
    }

    @Test
    fun callThatFailsFastButIsLoggedCountsAsNotAnswered() = runTest {
        val (s, phone) = session(
            listOf(Script(emitOffhook = false, loggedDuration = 0), Script(loggedDuration = 2)),
        )
        assertEquals(StopReason.ANSWERED, s.run().reason)
        assertEquals(2, phone.placed)
    }

    @Test
    fun placeCallExceptionStops() = runTest {
        val (s, _) = session(listOf(Script(placeThrows = SecurityException("no CALL_PHONE"))))
        val r = s.run()
        assertEquals(StopReason.PLACE_FAILED, r.reason)
    }

    @Test
    fun preflightProblemStopsBeforeDialing() = runTest {
        val (s, phone) = session(listOf(Script())) { preflight = "No SIM card inserted" }
        val r = s.run()
        assertEquals(StopReason.PRECONDITION_FAILED, r.reason)
        assertEquals("No SIM card inserted", r.detail)
        assertEquals(0, phone.placed)
    }

    @Test
    fun callLogPermissionDeniedStops() = runTest {
        val (s, _) = session(listOf(Script())) { callLogDenied = true }
        assertEquals(StopReason.PRECONDITION_FAILED, s.run().reason)
    }

    @Test
    fun waitsForOtherCallBeforeDialing() = runTest {
        val (s, phone) = session(listOf(Script(loggedDuration = 4))) { inCallUntil = epoch + 30_000 }
        val r = s.run()
        assertEquals(StopReason.ANSWERED, r.reason)
        assertEquals(1, phone.placed)
    }

    @Test
    fun cancellationStopsRedialing() = runTest {
        val (s, phone) = session(List(5) { Script() })
        val job = launch { s.run() }
        // First attempt ends at ~22s; we cancel during the 15s redial wait.
        kotlinx.coroutines.delay(30_000)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, phone.placed)
    }

    @Test
    fun thresholdTreatsShortConnectionAsNotAnswered() = runTest {
        val (s, phone) = session(
            listOf(Script(loggedDuration = 3), Script(loggedDuration = 25)),
            RetryConfig(delaySeconds = 15, maxAttempts = 5, answeredThresholdSeconds = 10),
        )
        assertEquals(StopReason.ANSWERED, s.run().reason)
        assertEquals(2, phone.placed)
    }

    @Test
    fun unlimitedAttemptsKeepRedialingPastOldCap() = runTest {
        val scripts = List(80) { Script() } + Script(loggedDuration = 6)
        val (s, phone) = session(scripts, RetryConfig(delaySeconds = 0, maxAttempts = RetryConfig.UNLIMITED))
        val r = s.run()
        assertEquals(StopReason.ANSWERED, r.reason)
        assertEquals(81, r.attempts)
        assertEquals(81, phone.placed)
    }

    @Test
    fun speakerFlagIsPassedOnEveryAttempt() = runTest {
        val (on, phoneOn) = session(listOf(Script(), Script(loggedDuration = 2)), speakerphone = true)
        on.run()
        assertEquals(listOf(true, true), phoneOn.speakerFlags)
        val (off, phoneOff) = session(listOf(Script(loggedDuration = 2)), speakerphone = false)
        off.run()
        assertEquals(listOf(false), phoneOff.speakerFlags)
    }

    @Test
    fun logsNeverContainFullNumber() = runTest {
        val logs = mutableListOf<String>()
        val (s, _) = session(listOf(Script(), Script(loggedDuration = 3)), logs = logs)
        s.run()
        assertTrue(logs.none { it.contains("501234567") })
        assertTrue(logs.any { it.contains("-> ANSWERED {num=+97•••4567") })
    }
}
