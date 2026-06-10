# 2026-06-10 BOOT_COMPLETED Reboot Test

## Task / Plan

- Task: manually reboot the real Pixel 5 and verify whether `BOOT_COMPLETED` reaches the Android app.
- Referenced plan: `docs/superpowers/plans/2026-05-08-05-android-native-enforcement.md`, Task 9 manual device checklist.
- Result: `BOOT_COMPLETED` was delivered to `app.buzer.android/.enforcement.BootReceiver`, but the app's current boot path did not refresh/reconcile alarms because Android blocked the receiver's attempt to start `MainActivity` from the background.

## Files Changed

- `docs/handoffs/2026-06-10-boot-completed-reboot-test.md`

## Commands Run

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: worktree was clean before this handoff file was created.

Verification:
- Command: `adb devices`
- Result: PASS
- Evidence: `11141FDD4002R1	device`.

Verification:
- Command: `adb -s 11141FDD4002R1 logcat -c`
- Result: PASS
- Evidence: no error before reboot.

Verification:
- Command: `adb -s 11141FDD4002R1 reboot`
- Result: PASS
- Evidence: command returned successfully and the device disconnected/reconnected.

Verification:
- Command: `adb -s 11141FDD4002R1 wait-for-device`
- Result: PASS
- Evidence: command returned after reboot.

Verification:
- Command: `adb -s 11141FDD4002R1 shell getprop sys.boot_completed`
- Result: PASS
- Evidence: returned `1`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell getprop dev.bootcomplete`
- Result: PASS
- Evidence: returned `1`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys user`
- Result: PASS
- Evidence: before unlock, user state was `RUNNING_LOCKED`; after unlock, user state was `RUNNING_UNLOCKED` with `Unlock time` present.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys package app.buzer.android | rg -i 'stopped|enabled|BootReceiver|RECEIVE_BOOT_COMPLETED'`
- Result: PASS
- Evidence: package state showed `BootReceiver`, `android.permission.RECEIVE_BOOT_COMPLETED: granted=true`, and `stopped=false`.

Verification:
- Command: `adb -s 11141FDD4002R1 logcat -d -s Buzer`
- Result: FAIL
- Evidence: no `Buzer` logs were emitted after boot/unlock, meaning the app refresh path did not run.

Verification:
- Command: `adb -s 11141FDD4002R1 logcat -d | rg -i 'Start proc 8818:app\.buzer\.android|Background activity launch blocked|Abort background activity starts|cmp=app\.buzer\.android/.MainActivity|BootReceiver'`
- Result: FAIL
- Evidence:
  - `Start proc 8818:app.buzer.android/u0a1064 for broadcast {app.buzer.android/app.buzer.android.enforcement.BootReceiver}`
  - `Background activity launch blocked [callingPackage: app.buzer.android ... intent: Intent { flg=0x10000000 cmp=app.buzer.android/.MainActivity (has extras) } ...]`
  - `Abort background activity starts from 11064`
  - `START u0 ... cmp=app.buzer.android/.MainActivity ... (BAL_BLOCK) result code=102`

## Manual Verification

- The Pixel 5 was physically rebooted through `adb reboot`.
- The user unlocked the phone after boot; the Android user state changed from `RUNNING_LOCKED` to `RUNNING_UNLOCKED`.
- Android did deliver `BOOT_COMPLETED` to the app receiver.
- Android blocked the app's background activity launch, so the current implementation does not recover alarms after reboot unless the user manually opens the app.

## Known Failures / Skipped Checks

- Boot rescheduling failed in the current implementation because `BootReceiver` delegates to `RescheduleAfterBoot.enqueue`, which starts `MainActivity`. Background activity launch restrictions block that from a boot broadcast on the tested Pixel 5.
- No code fix was applied in this pass.
- No Gradle test suite was run because this was a manual device verification pass.

## Assumptions

- This is a product-relevant MVP issue because exact alarms are cleared across reboot and the app cannot rely on launching an activity from a boot broadcast.
- The correct fix should move boot recovery into background-safe work, not ask users to manually open the app after reboot.

## Next Recommended Step

Replace the current boot receiver path with a background-safe reconciliation mechanism. The pragmatic MVP option is a small `BroadcastReceiver.goAsync` path that creates the existing `EnforcementCoordinator` and calls `refreshAndReconcile()` with strict timeout/error logging. The more durable option is WorkManager or a foreground service, but that adds a dependency and operational surface. Either path should include an app log line from `BootReceiver` so future reboot tests have direct evidence.
