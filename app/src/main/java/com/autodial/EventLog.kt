package com.autodial

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Timestamped diagnostic log persisted to filesDir/autodial.log (survives process death).
 * Also mirrored to logcat under tag "AutoDial" for when adb becomes available.
 */
object EventLog {
    private const val TAG = "AutoDial"
    private const val FILE_NAME = "autodial.log"
    private const val MAX_BYTES = 512 * 1024L
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private lateinit var file: File
    private val _version = MutableStateFlow(0)
    /** Increments on every write; the log screen observes it to refresh. */
    val version: StateFlow<Int> = _version

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(message: String) {
        val line = "${LocalDateTime.now().format(FORMAT)} $message"
        Log.i(TAG, message)
        try {
            if (file.length() > MAX_BYTES) trim()
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log file", e)
        }
        _version.value++
    }

    fun error(message: String, t: Throwable? = null) =
        log("ERROR $message" + (t?.let { " | ${it.javaClass.name}: ${it.message}" } ?: ""))

    @Synchronized
    fun read(): String = try {
        if (file.exists()) file.readText() else ""
    } catch (e: Exception) {
        "Could not read log: ${e.message}"
    }

    @Synchronized
    fun clear() {
        try {
            file.writeText("")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log", e)
        }
        _version.value++
    }

    /** Keeps the newest half of the file. */
    private fun trim() {
        val text = file.readText()
        val keep = text.substring(text.length / 2)
        file.writeText("--- log trimmed ---\n" + keep.substringAfter('\n'))
    }
}
