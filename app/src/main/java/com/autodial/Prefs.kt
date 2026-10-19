package com.autodial

import android.content.Context
import androidx.core.content.edit
import com.autodial.core.RetryConfig

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("autodial", Context.MODE_PRIVATE)

    var number: String
        get() = sp.getString(KEY_NUMBER, "").orEmpty()
        set(v) = sp.edit { putString(KEY_NUMBER, v) }

    var contactName: String
        get() = sp.getString(KEY_NAME, "").orEmpty()
        set(v) = sp.edit { putString(KEY_NAME, v) }

    var config: RetryConfig
        get() = RetryConfig(
            delaySeconds = sp.getInt(KEY_DELAY, RetryConfig.DEFAULT.delaySeconds),
            maxAttempts = sp.getInt(KEY_MAX, RetryConfig.DEFAULT.maxAttempts),
            answeredThresholdSeconds = sp.getInt(KEY_THRESHOLD, RetryConfig.DEFAULT.answeredThresholdSeconds),
        ).sanitized()
        set(v) = sp.edit {
            putInt(KEY_DELAY, v.delaySeconds)
            putInt(KEY_MAX, v.maxAttempts)
            putInt(KEY_THRESHOLD, v.answeredThresholdSeconds)
        }

    var speakerphone: Boolean
        get() = sp.getBoolean(KEY_SPEAKER, true)
        set(v) = sp.edit { putBoolean(KEY_SPEAKER, v) }

    /** True while a session runs; if still true when no service is running, the process was killed. */
    var sessionActive: Boolean
        get() = sp.getBoolean(KEY_ACTIVE, false)
        set(v) = sp.edit(commit = true) { putBoolean(KEY_ACTIVE, v) }

    private companion object {
        const val KEY_NUMBER = "number"
        const val KEY_NAME = "contact_name"
        const val KEY_DELAY = "delay_s"
        const val KEY_MAX = "max_attempts"
        const val KEY_THRESHOLD = "answered_threshold_s"
        const val KEY_ACTIVE = "session_active"
        const val KEY_SPEAKER = "speakerphone"
    }
}
