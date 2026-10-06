# CallGuard (Call Duration & Snooze Control)

Android default-dialer application built on the official Android Telecom framework (`RoleManager.ROLE_DIALER`, `InCallService`).

Features:
- **Interactive Dial-Pad**: standard 3x4 dialer, backspace, and outgoing call initiation.
- **Incoming-Call UI**: full-screen over lock screen with caller ID, Answer, and Decline buttons.
- **Ongoing-Call UI**: live duration timer, presence countdown, and End Call button.
- **Duration Limit & Recurring Snooze**:
  - Configurable call limit (30s test preset, 1m, 2m, 5m, 10m, 30m).
  - Configurable snooze interval (30s test preset, 1m, 2m, 5m, 10m).
  - Configurable presence confirmation window (15s, 30s, 45s, 60s).
  - Automatic call disconnect if presence is not confirmed within the window.
  - Vibration alerts on threshold.
- **POC Verification Drawer**: manual disconnect test and remote confirmation tracking.

Supported APIs only: `RoleManager` (ROLE_DIALER), `InCallService`, `Call.disconnect()`, `TelecomManager.placeCall()`. No AccessibilityService, root, hidden APIs or workarounds.

## Build via GitHub Actions

1. Push this folder to your GitHub repository.
2. The *Build POC APK* workflow runs the unit tests and compiles `app-debug.apk`.
3. Download the APK from the workflow's **Artifacts** or create a tag (`git tag v0.2 && git push --tags`) to get a downloadable release.
4. Install on the phone (allow "install unknown apps").
5. **If Android displays "App was denied access / Restricted setting":**
   Go to: **Settings → Apps → CallGuard → ⋮ (top right) → Allow restricted settings**.

## Layout

```
app/src/main/java/com/example/callguard/
  MainActivity.kt        Dial-pad, call limit & snooze duration settings, diagnostics
  CallActivity.kt        Incoming & ongoing call screen with snooze alert popup
  POCInCallService.kt    InCallService: call lifecycles, notification actions, CallActivity launcher
  CallPolicy.kt          SharedPreferences policy model (limit, snooze, window, vibration)
  CallVibrator.kt        Vibration alerts on threshold
  DialerRoleManager.kt   ROLE_DIALER check and request
  ActiveCallStore.kt     Session state machine (Active -> Confirmation -> Snooze -> Disconnect)
  CallStateLogger.kt     State and disconnect logger (no phone numbers logged)
```
