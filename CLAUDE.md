# AutoDial

Android app (Kotlin) that redials one number until the call is answered (decided from the call log duration).
Target device: Samsung Galaxy S23 SM-S911B, Android 14 (API 34), One UI 6.1, single SIM.

## Constraints
- **No adb / USB debugging.** Never assume a device is connected. Build the APK; the user installs it manually and
  sends back the in-app log (View log › Copy/Share). If the user later says adb is available, switch to:
  `./gradlew installDebug`, `adb shell pm grant com.autodial android.permission.{CALL_PHONE,READ_CALL_LOG,READ_PHONE_STATE,POST_NOTIFICATIONS}`,
  `adb shell am start -n com.autodial/.MainActivity`, `adb logcat -s AutoDial`.
  Note: Samsung Auto Blocker also blocks USB commands.
- Only ever dial the user's second phone. Never any other number.
- Target API 34 specifically; treat newer-version behaviour as footnotes.
- Keep the default debug keystore (`~/.android/debug.keystore`) so updates install over the old APK.

## Build / test
```
export ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # also set in ~/.bashrc
./gradlew testDebugUnitTest      # unit tests (retry policy, answer detection, numbers, session loop)
./gradlew lint                   # report: app/build/reports/lint-results-debug.html
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```
Toolchain: JDK 17 (apt openjdk-17), SDK in ~/Android/Sdk (cmdline-tools/latest, platforms;android-34,
build-tools;34.0.0, platform-tools), AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21.

## Layout
- `app/src/main/java/com/autodial/core/`: pure Kotlin, unit-tested: `PhoneNumbers`, `RetryPolicy`,
  `AnswerDetector`, `DialSession` (the attempt loop behind the `Phone` interface).
- `AndroidPhone`: real `Phone` (TelecomManager.placeCall, TelephonyCallback, CallLog query, SIM checks).
- `DialService`: `specialUse` foreground service, wake lock, notification with Stop.
- `MainActivity` / `LogActivity`: UI. `EventLog`: timestamped log in filesDir/autodial.log (+ logcat tag AutoDial).

## Key decisions (details and sources in docs/DECISIONS.md)
- placeCall (not ACTION_CALL): background activity starts are blocked even with an FGS.
- FGS type specialUse; started only from the visible activity; START_NOT_STICKY (never auto-resumes calling).
- Battery-optimization exemption required: in AOSP 14, Doze disables FGS wake locks unless allowlisted.
- Answer = outgoing call log row (last-9-digit number match, DATE >= t0-5s) with DURATION >= threshold (default 1 s).
  The row is written asynchronously after the call ends, so the app polls for up to 20 s.
- Conservative: unknown result / place failure / precondition failure all stop. Per user request (0.2.0) there is
  NO minimum delay and NO attempt cap (max attempts 0 = unlimited).
- Speakerphone toggle (default on) via `TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE` in placeCall extras.

## Known unknowns (verify from the user's logs)
- Samsung: does placeCall from a background FGS with the screen off work? (look for OFFHOOK after placeCall)
- Samsung call log: write delay after IDLE, number format, duration semantics.
- Samsung installer allowlists hard-restricted READ_CALL_LOG for sideloaded APKs?
- Samsung "Unrestricted" battery == AOSP Doze allowlist? (`batteryOptIgnored=` in the Env log line)
- Voicemail / carrier announcements connect the call, so they count as answered (user accepted; threshold mitigates).
- Exact One UI 6.1 menu labels for battery / sleeping apps.
- Does Samsung's in-call UI honour EXTRA_START_CALL_WITH_SPEAKERPHONE?

See docs/MANUAL_TEST.md for the on-device checklist.
