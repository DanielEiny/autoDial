package com.autodial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.autodial.core.CallLogRow
import com.autodial.core.CallState
import com.autodial.core.Phone
import kotlinx.coroutines.channels.Channel

/** Real [Phone] backed by TelecomManager, TelephonyManager and CallLog. */
class AndroidPhone(private val context: Context) : Phone {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    /** Every state change reported by TelephonyCallback, unbounded so no transition is lost. */
    val callStates = Channel<CallState>(Channel.UNLIMITED)
    private var callback: TelephonyCallback? = null

    @Volatile
    private var lastState: CallState = CallState.IDLE

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Registers the call state listener. Requires READ_PHONE_STATE (throws SecurityException otherwise). */
    fun startListening() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val s = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                    TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                    else -> CallState.IDLE
                }
                lastState = s
                EventLog.log("TelephonyCallback: onCallStateChanged($state=$s)")
                callStates.trySend(s)
            }
        }
        telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
        callback = cb
        EventLog.log("TelephonyCallback registered")
    }

    fun stopListening() {
        callback?.let {
            try {
                telephony.unregisterTelephonyCallback(it)
            } catch (e: Exception) {
                EventLog.error("unregisterTelephonyCallback failed", e)
            }
        }
        callback = null
        callStates.close()
    }

    override fun preflightProblem(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "Device has no telephony"
        }
        for (p in REQUIRED_PERMISSIONS) if (!granted(p)) return "Permission not granted: ${p.substringAfterLast('.')}"
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            return "Airplane mode is on"
        }
        return when (val sim = telephony.simState) {
            TelephonyManager.SIM_STATE_READY -> null
            TelephonyManager.SIM_STATE_ABSENT -> "No SIM card inserted"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "SIM is locked (PIN required)"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM is locked (PUK required)"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "SIM is network-locked"
            else -> "SIM not ready (state=$sim)"
        }
    }

    /** True if [number] is an emergency number per the platform; never auto-dial those. */
    fun isEmergencyNumber(number: String): Boolean = try {
        telephony.isEmergencyNumber(number)
    } catch (e: Exception) {
        EventLog.error("isEmergencyNumber check failed; treating as non-emergency", e)
        false
    }

    @SuppressLint("MissingPermission") // checked in preflightProblem()
    override fun isInCall(): Boolean = try {
        telecom.isInCall
    } catch (e: SecurityException) {
        EventLog.error("isInCall denied", e)
        false
    }

    @SuppressLint("MissingPermission") // checked in preflightProblem(); SecurityException handled by caller
    override fun placeCall(number: String, speakerphone: Boolean) {
        val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speakerphone) }
        telecom.placeCall(Uri.fromParts("tel", number, null), extras)
    }

    override fun currentCallState(): CallState = lastState

    override fun queryCallLog(sinceMillis: Long): List<CallLogRow> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "10")
            .build()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE,
        )
        val rows = mutableListOf<CallLogRow>()
        context.contentResolver.query(
            uri, projection,
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
            arrayOf(sinceMillis.toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            while (c.moveToNext()) {
                rows += CallLogRow(c.getString(iNum), c.getLong(iDate), c.getLong(iDur), c.getInt(iType))
            }
        } ?: EventLog.log("Call log query returned null cursor")
        return rows
    }

    /** Debug info about the default outgoing account (logged at session start). */
    @SuppressLint("MissingPermission")
    fun describeOutgoingAccount(): String = try {
        telecom.getDefaultOutgoingPhoneAccount("tel")?.let { "default tel account=${it.componentName.className}" }
            ?: "no default tel account (system will ask or use the only SIM)"
    } catch (e: Exception) {
        "outgoing account unavailable: ${e.javaClass.simpleName}"
    }

    companion object {
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
        )
    }
}
