# Manual test checklist (Galaxy S23, One UI 6.1, no adb)

Only ever dial **your second phone**. Keep the second phone next to you for every test.

## 0. Install
1. Copy `app/build/outputs/apk/debug/app-debug.apk` to the phone (USB file transfer, cloud, etc.).
2. If installation is blocked by **Auto Blocker**: Settings › Security and privacy › Auto Blocker › off.
   It also blocks *updates* of sideloaded apps, so keep it off while testing (you can turn it back on afterwards).
3. Open the APK in **My Files**. When asked, allow "Install unknown apps" for My Files, then Install.
   Updates: install the new APK over the old one. Don't uninstall, or your settings and log are lost.
4. If Play Protect warns about an unknown developer, choose "Install anyway" (or "More details › Install anyway").

## 1. First launch: permissions
1. Open AutoDial. Enter the number or use **Choose contact** (check that the name appears next to the field).
2. Tap **Start**. Android asks for: Phone (make and manage calls), Call logs, and Notifications. Allow all.
   - If "Call logs" is not offered, or is greyed out in Settings › Apps › AutoDial › Permissions, report it.
     This is the sideload-restriction case (DECISIONS D5). Also try Settings › Apps › AutoDial › ⋮ ›
     "Allow restricted settings", then grant again.
3. The orange warnings box lists anything still missing.

## 2. Battery settings (do all three)
1. In the app, tap **Allow background (battery)** and accept the system dialog. The warning should disappear.
2. Settings › Apps › AutoDial › Battery › **Unrestricted**.
3. Settings › Battery and device care › Battery › Background usage limits › **Never sleeping apps** › + › AutoDial › Add.
   (On some firmware it is Settings › Battery › Background usage limits.) Make sure AutoDial is **not** in
   "Sleeping apps" or "Deep sleeping apps".
4. Report the exact menu names you saw if they differ from the above.

Use delay = 15, max attempts = 3, threshold = 1, Speaker on for the tests below unless noted.
(Delay can be 0, and max attempts 0 means unlimited. Unlimited only stops when answered or when you tap Stop.)
**Before each test, open View log › Clear.** After each test, **Copy log** or **Share log** and send it to me.

## 3. Scenarios
| # | Scenario | What you do | Expected |
|---|---|---|---|
| A | Answered | Start; answer on the second phone on attempt 1, talk ≥ 3 s, hang up | Stops: "Answered on attempt 1 (duration=Ns)" |
| B | Rejected | Start; reject on the second phone every time | 3 attempts, ~15 s apart, then "Not answered after 3 attempts" |
| C | No answer | Start; let it ring and don't touch it | Same as B. **If voicemail picks up, it will count as answered**: report what happened |
| D | Answer on 2nd | Reject attempt 1, answer attempt 2 | Stops after attempt 2 with "Answered" |
| E | Screen off | Start, immediately press the power button. Reject attempts 1–2, answer attempt 3 (max 5). Don't touch the S23 | Redials continue with screen off; stops after the answer |
| F | Stop while waiting | Reject attempt 1; during "Redial in …s" tap Stop (app or notification) | No further calls. "Stopped by user" |
| G | Stop mid-call | While attempt 1 is ringing, tap Stop in the notification | No redial. The ringing call is **not** hung up by the app; hang up yourself |
| H | No SIM (optional) | Power off, remove SIM, power on, tap Start | Error "No SIM card inserted"; nothing dialed |
| I | Airplane mode | Turn on airplane mode, tap Start | Error "Airplane mode is on"; nothing dialed |
| J | Long screen-off (optional) | Delay 300 s, max 3, screen off, reject each, leave the phone still for ~15 min | 3 attempts about 5 min apart; check for "delay overran" in the log |
| L | Speaker | Run A once with Speaker on and once with it off | On: the call starts on loudspeaker. Off: earpiece. Report if On doesn't work |
| M | Unlimited | Max attempts 0, delay 0; reject 5 times, then answer | Keeps redialing; stops on the answer |
| K | Restart | Force-stop the app (Settings › Apps › AutoDial › Force stop), reopen | Number, contact name and settings are remembered |

## 4. What to send me
Send the whole log (Copy log). I especially need these lines:
- `Env: ...`: device, `batteryOptIgnored=`, `bgRestricted=`, `standbyBucket=`
- `Permission result: ...`: any `granted=false`
- `placeCall(..., speaker=true) requested at t0=...` followed by `call state -> OFFHOOK`. This confirms D1 (dialing from the
  background works on Samsung).
- `call log query #N (+Xms after IDLE): ... rows [...] -> ...`: confirms D4 (timing, number format, duration)
- `Attempt N outcome=... -> decision=...` and `Session end: reason=...`
- Any `ERROR`, `WARNING: redial delay overran`, `no matching call log entry`, `Detected previous session ... killed`

Also tell me, for each scenario: pass/fail, and what you saw on the screen that the log doesn't show
(for example, did the in-call screen appear with the screen off? did voicemail answer?).
