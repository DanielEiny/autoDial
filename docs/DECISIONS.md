# Design decisions and sources

Target: Samsung Galaxy S23 (SM-S911B), Android 14 / API 34, One UI 6.1. Research done 2026-10-19.
"VERIFIED" means it was checked against the cited official doc or AOSP source (android14-release branch).
**UNVERIFIED** means it could not be confirmed for Samsung's build. The app logs enough to confirm it on the device.

## D1. Place calls with `TelecomManager.placeCall()`, not `startActivity(ACTION_CALL)`
- Background activity starts are blocked unless one of a fixed list of exemptions applies. Running a foreground
  service is **not** an exemption, and a blocked launch fails silently (only a logcat line "Background activity
  launch blocked!"). With the screen off, our activity is not visible, so `ACTION_CALL` would be unreliable.
  Source: https://developer.android.com/guide/components/activities/background-starts (VERIFIED)
- `placeCall` "is equivalent to placing an outgoing call using Intent.ACTION_CALL, except that the outgoing call
  will always be sent via the system telecom service" and requires `CALL_PHONE`.
  Source: https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle) (VERIFIED)
- AOSP 14 `TelecomServiceImpl.placeCall` checks only `CALL_PHONE` permission + app-op; it has no foreground
  check, and Telecom (a system process) launches the in-call UI.
  Source: https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/android14-release/src/com/android/server/telecom/TelecomServiceImpl.java (VERIFIED for AOSP)
- **UNVERIFIED:** Samsung's Telecom/InCallUI behaves the same when placeCall comes from a background FGS with the
  screen off. Log lines `placeCall(...) requested` followed by `call state -> OFFHOOK` confirm it.
- No SIM / PhoneAccount selection: the single SIM is the default. `getDefaultOutgoingPhoneAccount("tel")` is
  logged for diagnosis only.

## D2. Foreground service type `specialUse`
- `phoneCall` requires `FOREGROUND_SERVICE_PHONE_CALL` and at runtime either `MANAGE_OWN_CALLS` declared or the
  ROLE_DIALER role. Its documented purpose is "Continue an ongoing call using the ConnectionService APIs", which is
  not what this app does.
- `specialUse` requires `FOREGROUND_SERVICE_SPECIAL_USE`, has no runtime prerequisites, and covers "valid use cases
  not covered by the other types". It's declared with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property (only reviewed by
  Google Play; irrelevant for sideloading).
- `shortService` is not usable: it has a ~3 minute limit.
  Source: https://developer.android.com/develop/background-work/services/fgs/service-types (VERIFIED)
- Timeouts on API 34: none for specialUse. (The 6 h/24 h limit applies only to `dataSync`/`mediaProcessing` and only
  for apps targeting 15+.) Source: https://developer.android.com/develop/background-work/services/fgs/timeout (VERIFIED)
- The FGS is started only from the Start button in the visible activity, which is allowed ("Your app transitions
  from a user-visible state"). Source: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (VERIFIED)
- `startForeground` is wrapped in try/catch. Failures (ForegroundServiceStartNotAllowedException, SecurityException)
  are shown in the UI and the log.
- The service returns `START_NOT_STICKY`. If the system kills it, the app does **not** auto-resume calling
  (conservative). The next app launch detects the leftover `session_active` flag and reports "killed by the system".

## D3. Screen-off operation: wake lock + battery-optimization exemption
- A `PARTIAL_WAKE_LOCK` is held during the session (2 h timeout, refreshed at every step).
- Doze "ignores wake locks". Allowlisted ("partially exempt") apps "can hold partial wake locks during Doze".
  Source: https://developer.android.com/training/monitoring-device-state/doze-standby (VERIFIED)
- AOSP 14 `PowerManagerService.setWakeLockDisabledStateLocked`: in device-idle mode a partial wake lock is disabled
  when the uid is not allowlisted and procState > `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`. A plain FGS app is
  `PROCESS_STATE_FOREGROUND_SERVICE`, which is greater, so **the FGS alone does not keep the wake lock in deep Doze**.
  Source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/power/PowerManagerService.java (VERIFIED for AOSP)
- Therefore the app checks `isIgnoringBatteryOptimizations()`, warns, and offers
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (needs the normal permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
  Lint's `BatteryLife` check is suppressed with this justification.
- The "restricted" background battery state means "Can't launch foreground services; existing foreground services
  are removed from the foreground". The app checks `ActivityManager.isBackgroundRestricted` and warns.
  Source: https://developer.android.com/about/versions/13/behavior-changes-13 (VERIFIED)
- The redial wait is a coroutine `delay`, and the session compares wall-clock time afterwards. If the delay overran
  by more than 5 s, it logs `WARNING: redial delay overran` (evidence of sleep or Doze).
- Exact alarms were not used: `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM`, and allow-while-idle alarms
  are rate-limited in Doze. The FGS + wake lock + exemption approach is simpler.

## D4. Answer detection: call state for timing, call log for the verdict
- `TelephonyCallback.CallStateListener` (API 31+, requires `READ_PHONE_STATE`) only reports IDLE / RINGING / OFFHOOK.
  For outgoing calls OFFHOOK covers both dialing and connected, so it cannot tell answered from not answered. It is
  used only to know when the call has ended.
  Source: https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener (VERIFIED)
- PreciseCallState (alerting vs active) needs `READ_PRECISE_PHONE_STATE`, which is for "dialers, carrier applications,
  or ims applications" and has no grantable path for us. Not used.
  Source: https://developer.android.com/reference/android/Manifest.permission#READ_PRECISE_PHONE_STATE (VERIFIED)
- How AOSP 14 Telecom writes the call log (`CallLogManager.java`, `Call.java`):
  - It is written on transition to DISCONNECTED/ABORTED, **asynchronously** (`LogCallAsyncTask` → `Calls.addCall`),
    so the row appears some time after the call state goes IDLE. The write can fail, in which case Telecom only logs
    a warning.
  - `DATE` = call creation time (when Telecom created the outgoing call, just after our `placeCall`).
  - `DURATION` = `getAgeMillis()/1000` = time spent ACTIVE, **truncated to whole seconds**. It is 0 if the call never
    went ACTIVE. An answer shorter than 1 s is therefore logged as 0.
  - Explicitly CANCELED calls (e.g. cancelled before placing) are **not logged at all**.
  Sources: https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/android14-release/src/com/android/server/telecom/CallLogManager.java
  and .../Call.java (VERIFIED for AOSP)
- Algorithm per attempt (`DialSession`):
  1. Preflight: telephony feature, permissions, airplane mode, `getSimState() == SIM_STATE_READY`.
  2. If `TelecomManager.isInCall()`, wait until the other call ends.
  3. Record t0, then `placeCall`.
  4. Wait up to 30 s for OFFHOOK, then wait (no limit) for IDLE.
  5. Poll `CallLog.Calls` every 1 s for up to 20 s with `TYPE=OUTGOING AND DATE >= t0-5s`, `DATE DESC`,
     `LIMIT_PARAM_KEY=10`. Match the number on its last 9 digits (handles 05X… vs +9725X…). The newest match wins.
  6. If `DURATION >= threshold`, it was answered (default threshold 1 s = "duration > 0", as requested). Otherwise it
     was not answered.
  `LIMIT_PARAM_KEY` usage and "strict SQL grammar": https://developer.android.com/reference/android/provider/CallLog.Calls (VERIFIED)
- Conservative outcomes (`RetryPolicy`): only a confirmed "not answered" is retried. (See D8 for limits.)
  - No call log row after 20 s: `RESULT_UNKNOWN`, stop.
  - Call never went OFFHOOK and no row: `PLACE_FAILED`, stop.
  - Call never went OFFHOOK but a row exists: use the row.
- **UNVERIFIED:** Samsung's call log provider and Samsung's Telecom write timing and number format. The log prints
  every queried row (masked number, date relative to t0, duration, type), so this can be checked from the first log.
- **Known limitation (user chose option A):** voicemail or carrier announcements that *connect* the call produce
  duration > 0 and would count as answered. Raising "Answered if duration ≥ N s" is the only mitigation. Hang-up
  before voicemail (`TelecomManager.endCall`, deprecated, needs `ANSWER_PHONE_CALLS`) was deliberately not built.

## D5. Permissions and sideloading
- Runtime: `CALL_PHONE`, `READ_CALL_LOG`, `READ_PHONE_STATE` (all dangerous) and `POST_NOTIFICATIONS` (optional; the
  FGS runs without it).
  Normal: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  Source: https://developer.android.com/reference/android/Manifest.permission (VERIFIED)
- `READ_CALL_LOG` is a **hard restricted** permission: "cannot be held by an app until the installer on record
  allowlists the permission". `PackageInstaller.SessionParams` says "Initially, all restricted permissions are
  allowlisted". In AOSP 14 the default `installFlags = INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS`, and the AOSP
  PackageInstaller app does not change it, so a sideloaded APK can be granted READ_CALL_LOG.
  Sources: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(java.util.Set%3Cjava.lang.String%3E) ,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/content/pm/PackageInstaller.java (VERIFIED for AOSP)
- **UNVERIFIED:** Samsung's installer (used when opening the APK from My Files) keeps that default. Symptom if not:
  the call-log permission is denied without a dialog, or is greyed out in Settings. The app logs
  `Permission result: READ_CALL_LOG granted=false showRationale=false` and tells the user to use App settings.
- "Restricted settings" for sideloaded apps (Settings › Apps › app › ⋮ › Allow restricted settings) are documented for
  accessibility-type settings. Call log is not mentioned. This app uses no accessibility or notification-listener
  service. Source: https://support.google.com/android/answer/12623953 (VERIFIED: the page doesn't list call log)
- Contact picker: `ACTION_PICK` on `CommonDataKinds.Phone.CONTENT_URI`. The result grants temporary read access to the
  picked row without `READ_CONTACTS`. The docs show the equivalent `setType(Phone.CONTENT_TYPE)` form.
  Source: https://developer.android.com/guide/components/intents-common#PickContactDat (VERIFIED)
- Emergency numbers are refused, by `TelephonyManager.isEmergencyNumber()` (no permission needed) plus a minimum
  of 5 digits. `*` and `#` (MMI codes) are rejected. Source: TelephonyManager reference (VERIFIED)

## D6. Samsung / One UI 6.1 (from Samsung support pages; exact 6.1 menu labels UNVERIFIED)
- Sleeping apps: Settings › Battery and device care › Battery › Background usage limits, with "Put unused apps to
  sleep", "Sleeping apps", "Deep sleeping apps" and "Never sleeping apps" (add with +). Newer firmware may show
  Settings › Battery directly. Source: https://www.samsung.com/us/support/answer/ANS10003442/ and
  https://www.samsung.com/ca/support/mobile-devices/galaxy-phone-sleeping-apps/
- Per-app battery: Settings › Apps › AutoDial › Battery › Unrestricted / Optimized / Restricted. Samsung describes
  Unrestricted as "permitted to run in the background without any restrictions". Source: Samsung support (via search
  results). **UNVERIFIED** whether Samsung's "Unrestricted" equals the AOSP Doze allowlist; the app logs
  `batteryOptIgnored=` so this can be checked.
- Auto Blocker: Settings › Security and privacy › Auto Blocker. When on, it blocks installs **and updates** from
  unauthorised sources (sideloaded APKs) and blocks USB commands. It's on by default only on devices that shipped with
  One UI 6.1.1; upgraded devices keep their previous setting.
  Source: https://www.samsung.com/uk/support/mobile-devices/protect-your-galaxy-device-with-the-new-auto-blocker-feature/
- Sideload prompt ("Install unknown apps" for My Files / the browser): standard Android flow. Exact Samsung wording
  **UNVERIFIED**.

## D8. Limits and speakerphone (changed in 0.2.0 at the user's request)
- The user removed the safety limits: **no attempt cap** (max attempts = 0 means unlimited; any positive number is
  honoured) and **no minimum delay** (0–3600 s). This replaces the original 15 s minimum and 50-attempt cap.
  Remaining safeguards: stop on unknown result / place failure / precondition failure, the Stop button (app +
  notification), and the refusal of emergency and MMI numbers.
- Speakerphone: `placeCall` extras carry `TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE`, which "determines
  whether the speakerphone should be automatically turned on for an outgoing call". It's a UI toggle, default on,
  persisted. Source: https://developer.android.com/reference/android/telecom/TelecomManager#EXTRA_START_CALL_WITH_SPEAKERPHONE (VERIFIED)
- **UNVERIFIED:** whether Samsung's in-call UI honours the extra (in particular with a Bluetooth headset connected,
  where audio routing may prefer the headset). Check on the phone.

## D7. Build
- minSdk = targetSdk = compileSdk = 34. The app targets this one Android 14 device. minSdk 34 avoids the API-34-only
  FGS type constant on older releases (lint `InlinedApi`).
- AGP 8.7.3, Gradle 8.11.1 (wrapper with pinned SHA-256), Kotlin 2.0.21, JDK 17 (Ubuntu openjdk-17), Views + ViewBinding.
- Debug signing uses AGP's default `~/.android/debug.keystore`. Back this file up: losing it means uninstalling
  before the next install.
- cmdline-tools 16111833 (sdkmanager is now a deprecated wrapper around the "Android CLI"). `--licenses` reported
  "no longer needed", but `licenses/android-sdk-license` was written.

## Device results (0.1.0 log, 2026-10-19, app in foreground)
- READ_CALL_LOG and the other runtime permissions were granted normally after installing from My Files, so D5's
  Samsung-installer question is **VERIFIED OK**.
- `isIgnoringBatteryOptimizations()=true` after the in-app request; standby bucket 5 (EXEMPTED).
- `placeCall` reached OFFHOOK 150–760 ms after the request (with the app visible; screen-off is still UNVERIFIED).
- Samsung call log: the row was absent ~30–90 ms after IDLE and present ~1.05 s after IDLE. NUMBER is stored in local
  format (054…). DATE = t0+5..11 ms. Rejected call → dur 0; answered → dur 14 s / 33 s. D4 matching **VERIFIED**.
- Still to verify: screen off, ring until no-answer/voicemail, Stop, speakerphone extra.

## Footnotes: newer Android versions (not targeted)
- API 35+: `dataSync`/`mediaProcessing` FGS 6 h limit. Apps targeting 15+ can't start `phoneCall` (and some other
  types) FGS from BOOT_COMPLETED. We use neither.
- API 35+: BAL creator-side opt-in for PendingIntents. We don't launch activities from the background.
- Android 15+ extends "restricted settings"/enhanced confirmation; re-check sideload permission behaviour if the
  phone is upgraded. (UNVERIFIED details)
