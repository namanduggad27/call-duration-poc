# CallGuard - Phase 0 POC (Call Duration Control)

Technical proof-of-concept only. It answers one question: **can a default-dialer app using the
supported Telecom APIs see real cellular calls and end them on command?**

Not included on purpose: timer, alerts, snooze, settings, Room, real dialer UI (Phase 1+).

Supported APIs only: `RoleManager` (ROLE_DIALER), `InCallService`, `Call.disconnect()`,
`TelecomManager.placeCall()`. No AccessibilityService, root, hidden APIs or workarounds.

## Build without Android Studio (GitHub Actions)

1. Create an empty GitHub repo and push this folder to it (or upload via the web UI).
2. Open the **Actions** tab. The *Build POC APK* workflow runs on every push, runs the unit
   tests, and builds the APK. (You can also run it manually via *Run workflow*.)
3. Download the APK: open the finished run, then **Artifacts**, then `callguard-poc-debug-apk`.
   Or tag a commit (`git tag v0.1-poc && git push --tags`) and download `app-debug.apk`
   from the repo's **Releases** page, which is easier to do from a phone.
4. Install on the phone (allow "install unknown apps" for your browser/file manager).

## Phone test procedure

Use **two phones**: the phone under test (A) and any other phone (B).
Never test with emergency numbers. To undo everything: Settings, Apps, Default apps, Phone app,
and pick your original dialer again.

1. Open CallGuard POC, tap **Request default dialer role**, choose CallGuard POC, confirm.
   Screen must show `ROLE_DIALER: HELD (OK)`. Allow the phone and notification permissions.
2. **Outgoing:** type B's number, tap **Place test call**, answer on B.
   Screen must show `OUTGOING  ACTIVE` and a running timer.
   Tap **Disconnect Test Call**. Look at B: did the call end? Tap **Yes, ended** or **No, still on**.
3. **Incoming:** call A from B. Tap the notification (or open the app), tap **Answer**.
   Screen must show `INCOMING  ACTIVE`. Tap **Disconnect Test Call**, check B, tap Yes/No.
4. Repeat each direction 3 times. Then repeat with the screen locked/app in background
   (answer, lock the phone, reopen via notification, disconnect).
5. Tap **Share report** / **Copy report** and keep it with the device/API/OEM line.

## PASS / FAIL

A single disconnect test is **PASS** only when both are true:
- Telecom confirmed the end within 5 s of `call.disconnect()` (state DISCONNECTED / onCallRemoved), and
- you confirmed the other phone's call really ended.

It is **FAIL** if Telecom never confirms within 5 s, `disconnect()` throws, or phone B is still connected.

Device-level result:
- **PASS:** role held; both directions show up with correct direction/state; at least 3/3 outgoing and
  3/3 incoming disconnect tests PASS; no failures when locked/backgrounded.
- **FAIL:** role cannot be held, call lifecycle never arrives, or any disconnect test fails repeatably.
  Record the failure; do not hide it.

## Compatibility matrix row (copy per device)

| Device | Android/API | OEM build | Default dialer | Outgoing disconnect | Incoming disconnect | Background/lock | Result |
|---|---|---|---|---|---|---|---|
| | | | PASS/FAIL | PASS/FAIL | PASS/FAIL | PASS/FAIL | GO/NO-GO |

## Layout

```
app/src/main/java/com/example/callguard/
  MainActivity.kt        POC screen, ACTION_DIAL handling, test controls, report export
  POCInCallService.kt    InCallService: onCallAdded / onCallRemoved
  DialerRoleManager.kt   ROLE_DIALER check/request
  ActiveCallStore.kt     holds the Call objects, disconnect verification, PASS/FAIL logic
  CallStateLogger.kt     state/disconnect log (no phone numbers logged)
```
