package com.autodial

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autodial.core.DialSession
import com.autodial.core.PhoneNumbers
import com.autodial.core.RetryConfig
import com.autodial.core.SessionResult
import com.autodial.core.SessionStatus
import com.autodial.core.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type specialUse) that runs one [DialSession]. Started only from the visible
 * activity (Start button), which is allowed by the Android 12+ background-start rules.
 */
class DialService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var phone: AndroidPhone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        EventLog.log("Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                EventLog.log("Stop requested (${intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"})")
                stopSession(userStopped = true)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                // null intent = system restart after kill; we never auto-resume calling.
                EventLog.log("Service started with action=${intent?.action}; not resuming (conservative)")
                prefs.sessionActive = false
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!promoteToForeground("Starting…")) return START_NOT_STICKY

        if (job?.isActive == true) {
            EventLog.log("Start ignored: a session is already running")
            return START_NOT_STICKY
        }
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val config = RetryConfig(
            intent.getIntExtra(EXTRA_DELAY, RetryConfig.DEFAULT.delaySeconds),
            intent.getIntExtra(EXTRA_MAX, RetryConfig.DEFAULT.maxAttempts),
            intent.getIntExtra(EXTRA_THRESHOLD, RetryConfig.DEFAULT.answeredThresholdSeconds),
        ).sanitized()
        startSession(number, config, intent.getBooleanExtra(EXTRA_SPEAKER, true))
        return START_NOT_STICKY
    }

    private fun promoteToForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException / SecurityException / InvalidForegroundServiceTypeException
        EventLog.error("startForeground failed", e)
        fail("Could not start foreground service: ${e.javaClass.simpleName}. See log.")
        stopSelf()
        false
    }

    private fun startSession(number: String, config: RetryConfig, speakerphone: Boolean) {
        val p = AndroidPhone(this)
        phone = p
        logEnvironment(p)

        if (PhoneNumbers.validate(number) !is PhoneNumbers.Validation.Valid || p.isEmergencyNumber(number)) {
            EventLog.log("Refusing to dial invalid/emergency number ${PhoneNumbers.mask(number)}")
            fail("Refused: invalid or emergency number")
            stopSession(userStopped = false)
            return
        }
        try {
            p.startListening()
        } catch (e: SecurityException) {
            EventLog.error("registerTelephonyCallback denied (READ_PHONE_STATE?)", e)
            fail("Phone permission missing (READ_PHONE_STATE)")
            stopSession(userStopped = false)
            return
        }

        acquireWakeLock()
        prefs.sessionActive = true
        DialState.update { DialState.Ui(running = true, status = "Starting…") }

        val session = DialSession(
            number = number,
            config = config,
            phone = p,
            speakerphone = speakerphone,
            callStates = p.callStates,
            nowMillis = System::currentTimeMillis,
            log = EventLog::log,
            onStatus = ::onStatus,
        )
        job = scope.launch {
            val result = try {
                session.run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.error("Session crashed", e)
                SessionResult(StopReason.PRECONDITION_FAILED, 0, "internal error ${e.javaClass.simpleName}")
            }
            onFinished(result)
        }
    }

    private fun onStatus(s: SessionStatus) {
        acquireWakeLock() // refresh the timeout at every step
        val text = when (s) {
            is SessionStatus.WaitingForOtherCall -> "Attempt ${s.attempt}: waiting for current call to end"
            is SessionStatus.Dialing -> "Attempt ${s.attempt}${of(s.maxAttempts)}: dialing"
            is SessionStatus.InCall -> "Attempt ${s.attempt}${of(s.maxAttempts)}: in call"
            is SessionStatus.CheckingCallLog -> "Attempt ${s.attempt}: checking call log"
            is SessionStatus.WaitingToRedial -> "Redial ${s.nextAttempt}${of(s.maxAttempts)} in ${s.secondsLeft}s"
        }
        DialState.update { it.copy(running = true, status = text) }
        // Avoid re-posting the notification every second during countdown.
        if (s !is SessionStatus.WaitingToRedial || s.secondsLeft % 5 == 0) updateNotification(text)
    }

    private fun of(max: Int) = if (max == RetryConfig.UNLIMITED) "" else "/$max"

    private fun onFinished(r: SessionResult) {
        val (text, isError) = when (r.reason) {
            StopReason.ANSWERED -> "Answered on attempt ${r.attempts} (${r.detail})" to false
            StopReason.MAX_ATTEMPTS_REACHED -> "Not answered after ${r.attempts} attempts" to false
            StopReason.RESULT_UNKNOWN -> "Stopped: couldn't read call result (${r.detail})" to true
            StopReason.PLACE_FAILED -> "Stopped: call failed (${r.detail})" to true
            StopReason.PRECONDITION_FAILED -> "Stopped: ${r.detail}" to true
            StopReason.USER_STOPPED -> "Stopped by user" to false
        }
        DialState.update { DialState.Ui(running = false, status = "Idle", lastResult = text, isError = isError) }
        cleanup()
    }

    private fun fail(message: String) {
        DialState.update { DialState.Ui(running = false, status = "Idle", lastResult = message, isError = true) }
    }

    private fun stopSession(userStopped: Boolean) {
        val wasRunning = job?.isActive == true
        job?.cancel()
        job = null
        if (userStopped) {
            DialState.update {
                DialState.Ui(
                    running = false, status = "Idle",
                    lastResult = if (wasRunning) "Stopped by user (an ongoing call is not hung up)" else it.lastResult,
                )
            }
        }
        cleanup()
    }

    private fun cleanup() {
        phone?.stopListening()
        phone = null
        releaseWakeLock()
        prefs.sessionActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        EventLog.log("onTaskRemoved (app swiped from recents); service keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        EventLog.log("Service onDestroy (sessionWasActive=${job?.isActive == true})")
        if (job?.isActive == true) {
            job?.cancel()
            DialState.update {
                DialState.Ui(running = false, status = "Idle", lastResult = "Service was stopped by the system", isError = true)
            }
        }
        phone?.stopListening()
        releaseWakeLock()
        prefs.sessionActive = false
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoDial:session")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Generous timeout covering one call plus delay; refreshed on every status update.
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun logEnvironment(p: AndroidPhone) {
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(ActivityManager::class.java)
        val usm = getSystemService(UsageStatsManager::class.java)
        EventLog.log(
            "Env: app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) device=${Build.MANUFACTURER} ${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} " +
                "batteryOptIgnored=${pm.isIgnoringBatteryOptimizations(packageName)} " +
                "bgRestricted=${am.isBackgroundRestricted} standbyBucket=${usm.appStandbyBucket} " +
                "powerSave=${pm.isPowerSaveMode} ${p.describeOutgoingAccount()}",
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DialService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SOURCE, "notification"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_redial)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the FGS still runs; notification only visible in Task Manager.
        }
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        const val ACTION_START = "com.autodial.START"
        const val ACTION_STOP = "com.autodial.STOP"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_MAX = "max"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SPEAKER = "speaker"

        fun start(context: Context, number: String, config: RetryConfig, speakerphone: Boolean) {
            val i = Intent(context, DialService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_DELAY, config.delaySeconds)
                .putExtra(EXTRA_MAX, config.maxAttempts)
                .putExtra(EXTRA_THRESHOLD, config.answeredThresholdSeconds)
                .putExtra(EXTRA_SPEAKER, speakerphone)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DialService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SOURCE, "app button"),
            )
        }
    }
}
