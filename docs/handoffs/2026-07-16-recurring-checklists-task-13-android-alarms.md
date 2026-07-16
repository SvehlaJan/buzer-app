# Recurring Checklists Task 13: Device Identity And Occurrence-Keyed Android Alarms

Plan/task attempted:

- Implemented Task 13 source and RED tests from `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`. The focused Android test command cannot yet compile because the pre-existing Task 11 fixed-contract migration remains unfinished in Task 14/15/16 callers. No legacy empty-metadata fallbacks were restored.

Changes made:

- Added `DeviceIdentityStore`, with a persisted installation UUID and a separate fresh action UUID factory. Action IDs are intentionally not persisted or reused; retry code must retain the original caller-held ID.
- Added mandatory occurrence identity to `ScheduledAlarm`, PendingIntent receiver extras/request codes, full-screen notification IDs, overlay/blocker intents, logs, and snapshot gate checks. Morning Review uses `morning-review` for both synthetic task and occurrence identifiers.
- Added `ScheduledAlarmRegistry`, backed by atomically committed SharedPreferences state. Reconciliation cancels every persisted identity absent from the desired set (including an old same-time recurring occurrence), schedules desired alarms, then replaces the durable registry.
- Updated start/deadline activity gating so a refreshed stale occurrence finishes before content is shown. The overlay also rejects a stale/resolved cached identity before rendering.
- Left release networking untouched. `client/androidApp/src/main/AndroidManifest.xml` has no release cleartext/security-config change; debug still limits cleartext to `10.0.2.2` in `src/debug/res/xml/network_security_config.xml`.

Files changed:

- `client/androidApp/src/main/kotlin/app/buzer/android/sync/DeviceIdentityStore.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/ScheduledAlarmRegistry.kt`
- Android alarm scheduler/intent/receiver/coordinator/dependencies/overlay/blocker-gate files and their focused tests.
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- this handoff.

TDD evidence:

- RED command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.sync.DeviceIdentityStoreTest' --tests 'app.buzer.android.enforcement.ScheduledAlarmRegistryTest' --tests 'app.buzer.android.enforcement.AlarmIntentFactoryTest' --tests 'app.buzer.android.blockers.BlockerTaskSnapshotGateTest' --console=plain`
  - Result: FAIL before test execution.
  - Evidence: the new RED tests were present, but `:androidApp:compileDebugKotlin` fails on the known Task 11→Task 14/15/16 caller migration (`EnforcementApi` lacks fixed action request objects; editor/blocker/Morning Review callers lack action/device/occurrence metadata). This boundary predates Task 13 and prevents Android unit-test compilation.

Context7 lookup:

- `npx ctx7@latest library "Android AlarmManager" "PendingIntent identity is determined by action data type class categories, extras excluded; cancellation requires matching PendingIntent; use SharedPreferences persistent UUID"`
- `npx ctx7@latest docs "/websites/developer_android" "For Android AlarmManager, explain how PendingIntent identity works for scheduling and cancellation, whether extras differentiate intent identity, and recommended persistent storage for a per-install UUID."`
- Result: used the official PendingIntent guidance: `FLAG_UPDATE_CURRENT` updates extras of a matching PendingIntent, so occurrence is included in the request-code identity rather than relying on extras; the registry preserves exact cancellation identity across process restart.

Verification:

- Command: `./gradlew :androidApp:compileDebugKotlin --console=plain`
  - Result: FAIL (known integration debt).
  - Evidence: after updating the debug overlay launcher for the new occurrence parameter, no Task 13 source error is reported. Remaining errors are the fixed-request/action metadata migration in `EnforcementApi`, add/edit screens, blocker screens, and Morning Review; Task 14/15/16 own those callers.
- Command: `git diff --check`
  - Result: PASS.
  - Evidence: no whitespace errors.
- Command: `rg -n "networkSecurityConfig|usesCleartextTraffic" client/androidApp/src`
  - Result: PASS.
  - Evidence: the only network security config is debug `10.0.2.2`; no release cleartext opt-in exists.

Manual verification:

- Inspected all production alarm construction/receiver/notification/blocker paths to confirm that `occurrenceKey` is now carried through the PendingIntent identity and surface intent; the stale gate compares the refreshed task key before presenting activity content.

Known failures or skipped checks:

- The required focused `:androidApp:testDebugUnitTest` lane and Android instrumentation are blocked until the planned Task 14/15/16 source migration restores application compilation. No emulator run was attempted while the app cannot compile.
- A Task 13 quality/spec review and then the focused GREEN lane are still required after that migration boundary lands.

Assumptions:

- `SharedPreferences.Editor.commit()` is used for the registry/device UUID because registry replacement must be visible synchronously before reconciliation returns.
- The fixed `morning-review` synthetic identity is for Morning Review alarms only; root task alarms always use the backend-provided nonblank occurrence key.

Next recommended step:

- Have the Task 13 source reviewed now. Then complete Task 14’s explicit immutable action-request migration, re-run Task 13 focused tests for GREEN, and adjust only genuine Task 13 defects found by that run.

## Spec-review follow-up

Changes made:

- `BlockerNotificationCleaner` now receives the current occurrence key from the cached pre-action snapshot. Android cleanup cancels current occurrence-keyed Start/Deadline IDs and retains legacy-ID cleanup for upgrade safety.
- The overlay service now starts its foreground-service shell but waits up to five seconds for a fresh reconciliation before constructing/rendering the overlay. Fresh stale/resolved authority dismisses immediately; only timeout/failure takes the explicitly logged cached/offline display path, which still rejects cached stale authority.
- Full-screen activity PendingIntents now carry the same opaque `(taskId, occurrenceKey, kind)` in both explicit action and data URI, in addition to their occurrence-keyed request code and extras. This avoids depending on a hash request code alone.
- Start, Deadline, and overlay action/refresh/watcher logs now include occurrence identity.

Additional TDD evidence:

- RED command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.overlay.OverlayInitialDisplayGateTest' --tests 'app.buzer.android.enforcement.BlockerNotificationCleanerTest' --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest' --console=plain`
  - Result: FAIL before test execution due the unchanged planned Task 14/15/16 compile boundary.
  - Evidence: test sources were added before production counterparts, but the compiler stops in fixed-contract Android callers before compiling test classes.

Verification:

- Command: `./gradlew :androidApp:compileDebugKotlin --console=plain`
  - Result: FAIL (known integration debt only).
  - Evidence: after adding the missing `withTimeoutOrNull` import, no Task 13 source diagnostic is reported; remaining diagnostics are the unchanged `EnforcementApi`/editor/blocker/Morning Review fixed-action-request migration.

## Code-quality follow-up

Changes made:

- `BlockerTaskSnapshotGate.isBlockerResolved` now dismisses an authoritative snapshot that omits the root, while retaining the existing generic `isTaskResolved` missing-root semantics.
- Overlay requests now own a monotonic generation token. A replacement request cancels/removes the old presentation without stopping the service; old initial refreshes, watchers, and action completion cannot stop or remove a newer generation.
- Coordinator action methods take the caller's occurrence key directly. Start, postpone, complete, extend, reschedule, update, and Morning Review dismiss paths now thread the triggering key to notification cleanup and retry scheduling; cache mismatch/miss cannot redirect cleanup to a different occurrence.
- Registry reconciliation cancels desired identities if schedule or durable replacement fails, then rethrows while preserving the previously stored registry.

Additional test coverage added:

- authoritative missing-root dismissal in both blocker and overlay initial-display gates;
- generation invalidation for an older overlay request;
- current notification IDs and coordinator cleanup under cache mismatch/miss;
- scheduler and persistence failure rollback for the alarm registry.

Verification:

- Command: `./gradlew :androidApp:compileDebugKotlin --console=plain`
  - Result: FAIL (known integration debt only).
  - Evidence: no Task 13 diagnostic was emitted; the compiler remains blocked by the previously documented fixed action-request/editor/Morning Review migration.

## Final P1 follow-up

- Rollback now cancels only `desiredIdentities - existing`. A retained identity remains armed when a newly added desired identity fails to schedule or persist.
- Added a regression with existing `{A}` and desired `{A, B}` that proves failure cancels only `B` while retaining `A` in the old registry.
