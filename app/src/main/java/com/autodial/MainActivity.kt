package com.autodial

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autodial.core.PhoneNumbers
import com.autodial.core.RetryConfig
import com.autodial.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Number as returned by the picker; the name is shown only while the field still matches it. */
    private var pickedNumber: String = ""

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != RESULT_OK || uri == null) {
            EventLog.log("Contact picker: cancelled (resultCode=${res.resultCode})")
            return@registerForActivityResult
        }
        readPickedContact(uri)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.forEach { (perm, granted) ->
                val rationale = shouldShowRequestPermissionRationale(perm)
                EventLog.log("Permission result: ${perm.substringAfterLast('.')} granted=$granted showRationale=$rationale")
            }
            val deniedRequired = AndroidPhone.REQUIRED_PERMISSIONS.filter { !isGranted(it) }
            if (deniedRequired.isNotEmpty()) {
                val blocked = deniedRequired.filter { !shouldShowRequestPermissionRationale(it) }
                val msg = if (blocked.isNotEmpty()) {
                    "Permission denied: ${names(deniedRequired)}. Android won't ask again (or it is restricted). " +
                        "Open App settings › Permissions and allow them manually."
                } else {
                    "Permission denied: ${names(deniedRequired)}. Tap Start to be asked again."
                }
                EventLog.log("Required permissions missing after request: ${names(deniedRequired)} blocked=${names(blocked)}")
                showResult(msg, isError = true)
            } else if (!DialState.ui.value.running) {
                showResult("Permissions granted. Tap Start again.", isError = false)
            }
            refreshWarnings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        restoreFields()
        b.number.doAfterTextChanged { text ->
            if (pickedNumber.isNotEmpty() && !PhoneNumbers.sameNumber(text?.toString(), pickedNumber)) {
                pickedNumber = ""
                prefs.contactName = ""
                b.contactName.text = ""
            }
        }
        b.chooseContact.setOnClickListener { launchPicker() }
        b.start.setOnClickListener { onStartPressed() }
        b.stop.setOnClickListener {
            EventLog.log("UI: Stop pressed")
            try {
                DialService.stop(this)
            } catch (e: Exception) {
                EventLog.error("Could not deliver stop to service", e)
            }
        }
        b.viewLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.fixBattery.setOnClickListener { requestBatteryExemption() }
        b.appSettings.setOnClickListener { openAppSettings() }

        if (prefs.sessionActive && !DialState.ui.value.running) {
            EventLog.log("Detected previous session that ended without cleanup: process was killed by the system")
            prefs.sessionActive = false
            showResult("Previous session was killed by the system. Check battery settings (see warnings).", true)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DialState.ui.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWarnings()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun restoreFields() {
        b.number.setText(prefs.number)
        b.contactName.text = prefs.contactName
        if (prefs.contactName.isNotEmpty()) pickedNumber = prefs.number
        val c = prefs.config
        // Locale.ROOT: the fields are parsed back with toIntOrNull(), which expects ASCII digits.
        b.delay.setText(String.format(Locale.ROOT, "%d", c.delaySeconds))
        b.maxAttempts.setText(String.format(Locale.ROOT, "%d", c.maxAttempts))
        b.threshold.setText(String.format(Locale.ROOT, "%d", c.answeredThresholdSeconds))
        b.speaker.isChecked = prefs.speakerphone
        b.limits.text = getString(R.string.limits, RetryConfig.MAX_DELAY_SECONDS)
    }

    private fun saveFields() {
        prefs.number = b.number.text.toString().trim()
        prefs.config = readConfig()
        prefs.speakerphone = b.speaker.isChecked
    }

    private fun readConfig(): RetryConfig = RetryConfig(
        delaySeconds = b.delay.text.toString().toIntOrNull() ?: RetryConfig.DEFAULT.delaySeconds,
        maxAttempts = b.maxAttempts.text.toString().toIntOrNull() ?: RetryConfig.DEFAULT.maxAttempts,
        answeredThresholdSeconds = b.threshold.text.toString().toIntOrNull()
            ?: RetryConfig.DEFAULT.answeredThresholdSeconds,
    )

    private fun render(s: DialState.Ui) {
        b.status.text = s.status
        b.start.isEnabled = !s.running
        b.stop.isEnabled = s.running
        listOf(b.number, b.chooseContact, b.delay, b.maxAttempts, b.threshold, b.speaker).forEach { it.isEnabled = !s.running }
        s.lastResult?.let { showResult(it, s.isError) }
    }

    private fun showResult(text: String, isError: Boolean) {
        b.result.text = text
        b.result.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.error else R.color.ok),
        )
        b.result.visibility = View.VISIBLE
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        try {
            pickContact.launch(intent)
            EventLog.log("Contact picker launched")
        } catch (e: ActivityNotFoundException) {
            EventLog.error("No contact picker app available", e)
            showResult("No contacts app available to pick from", true)
        }
    }

    private fun readPickedContact(uri: Uri) {
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val number = c.getString(0).orEmpty()
                    val name = c.getString(1).orEmpty()
                    pickedNumber = number
                    b.number.setText(number)
                    b.contactName.text = name
                    prefs.number = number
                    prefs.contactName = name
                    EventLog.log("Contact picked: name='${name.take(1)}…' number=${PhoneNumbers.mask(number)}")
                } else {
                    EventLog.log("Contact picker returned an empty row")
                    showResult("Picked contact has no number", true)
                }
            } ?: run {
                EventLog.log("Contact query returned null cursor")
                showResult("Could not read the picked contact", true)
            }
        } catch (e: Exception) {
            EventLog.error("Reading picked contact failed", e)
            showResult("Could not read the picked contact: ${e.javaClass.simpleName}", true)
        }
    }

    private fun onStartPressed() {
        saveFields()
        val raw = b.number.text.toString()
        val config = readConfig().sanitized()
        val speaker = b.speaker.isChecked
        EventLog.log("UI: Start pressed number=${PhoneNumbers.mask(raw)} config=$config speaker=$speaker")

        val dial = when (val v = PhoneNumbers.validate(raw)) {
            is PhoneNumbers.Validation.Invalid -> {
                EventLog.log("Start rejected: ${v.reason}")
                showResult(v.reason, true)
                return
            }
            is PhoneNumbers.Validation.Valid -> v.dialString
        }

        val phone = AndroidPhone(this)
        if (phone.isEmergencyNumber(dial)) {
            EventLog.log("Start rejected: emergency number")
            showResult("Emergency numbers cannot be auto-dialed", true)
            return
        }

        val missing = permissionsToRequest()
        if (AndroidPhone.REQUIRED_PERMISSIONS.any { it in missing }) {
            EventLog.log("Requesting permissions: ${names(missing)}")
            requestPermissions.launch(missing.toTypedArray())
            return
        }

        phone.preflightProblem()?.let {
            EventLog.log("Start rejected by preflight: $it")
            showResult(it, true)
            return
        }

        val afterChecks = {
            // Ask for notifications too, but don't block the session on it.
            if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
            b.result.visibility = View.GONE
            DialState.update { DialState.Ui(running = true, status = "Starting…") }
            try {
                DialService.start(this, dial, config, speaker)
            } catch (e: Exception) {
                EventLog.error("startForegroundService failed", e)
                DialState.update { DialState.Ui(lastResult = "Could not start service: ${e.javaClass.simpleName}", isError = true) }
            }
        }

        if (!isIgnoringBatteryOptimizations()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_fix) { _, _ -> requestBatteryExemption() }
                .setNegativeButton(R.string.battery_continue) { _, _ ->
                    EventLog.log("User chose to start without battery exemption")
                    afterChecks()
                }
                .show()
        } else {
            afterChecks()
        }
    }

    private fun permissionsToRequest(): List<String> =
        (AndroidPhone.REQUIRED_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS).filter { !isGranted(it) }

    private fun isGranted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun names(ps: List<String>) = ps.joinToString { it.substringAfterLast('.') }

    private fun isIgnoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshWarnings() {
        val w = mutableListOf<String>()
        val missing = permissionsToRequest()
        if (missing.isNotEmpty()) w += "Missing permissions: ${names(missing)}"
        if (!isIgnoringBatteryOptimizations()) w += "Battery optimization is ON — redialing may stop with the screen off"
        if (getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted) {
            w += "App battery usage is 'Restricted' — set it to 'Unrestricted' in App settings › Battery"
        }
        AndroidPhone(this).preflightProblem()?.takeIf { missing.isEmpty() }?.let { w += it }
        b.warnings.text = w.joinToString("\n• ", prefix = "• ")
        b.warningsCard.visibility = if (w.isEmpty()) View.GONE else View.VISIBLE
        b.fixBattery.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    // Exemption is the app's core function: per AOSP 14, Doze disables partial wake locks of FGS apps
    // that aren't allowlisted. See docs/DECISIONS.md.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        EventLog.log("Requesting battery optimization exemption")
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            EventLog.error("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable, opening list", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                EventLog.error("Battery optimization settings unavailable", e2)
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
