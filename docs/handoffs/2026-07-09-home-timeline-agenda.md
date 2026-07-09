# Handoff: Home Timeline Agenda And Current Block Done

Date: 2026-07-09
Agent: Codex implementation subagent
Plan: `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
Task: Task 6, "Home Timeline Agenda And Current Block Done"

## Checkbox Status

- [x] Step 1: Add Home state tests for agenda rows
- [x] Step 2: Add shared Home timeline models
- [x] Step 3: Update Home state builder
- [x] Step 4: Implement compact Home UI
- [x] Step 5: Verify Task 6
- [x] Step 6: Commit

## Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
- `docs/handoffs/2026-07-09-home-timeline-agenda.md`

Review-fix update, 2026-07-10:
- `SnapshotViewState` now carries reducer output `tasks`, including completed non-deleted tasks.
- Home agenda derivation uses `snapshot.tasks` plus injected device `OffsetDateTime`, not `startingToday` or `serverTime`.
- `buildHomeScreenState` requires injected `OffsetDateTime`; the only current-time read is the injectable `BuzerApp.nowProvider` boundary.

Quality-review fix update, 2026-07-10:
- Added `HomeDoneActionHandler` to serialize Home refreshes and current-block Done actions.
- Home Done success now applies the coordinator's latest cached snapshot immediately after `coordinator.completeTask(taskId)` succeeds, so an already-active normal refresh cannot drop the post-action UI update.
- Home Done failure now leaves the action error visible and does not start a refresh that immediately clears it.

## Commands And Results

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: Initial worktree was clean before Task 6 edits.

Verification:
- Command: `npx ctx7@latest library "kotlinx.datetime" "Instant TimeZone LocalDateTime date formatting Kotlin Multiplatform"`
- Result: PASS
- Evidence: Resolved `/kotlin/kotlinx-datetime`.

Verification:
- Command: `npx ctx7@latest docs "/kotlin/kotlinx-datetime" "Instant TimeZone LocalDateTime date formatting Kotlin Multiplatform"`
- Result: PASS
- Evidence: Docs covered converting `Instant` to `LocalDateTime` with an explicit `TimeZone` and formatting/parsing date-time values.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose Material 3" "FloatingActionButton IconButton accessibility contentDescription list timeline Compose"`
- Result: PASS
- Evidence: Resolved `/websites/composables_jetpack-compose_androidx_compose_material3_material3`.

Verification:
- Command: `npx ctx7@latest docs "/websites/composables_jetpack-compose_androidx_compose_material3_material3" "FloatingActionButton IconButton accessibility contentDescription list timeline Compose"`
- Result: PASS
- Evidence: Docs covered Material 3 `FloatingActionButton`, `IconButton`, tooltips, and icon content descriptions for accessibility.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected)
- Evidence: Compile failed because `app.buzer.shared.ui.home` did not exist, `buildHomeScreenState` had no `now` parameter, and new fields such as `weekdayLabel`, `agendaRows`, `currentBlock`, and `attention` were unresolved.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: 94 debug unit tests passed after implementing Home timeline state; an intermediate run exposed stale copy expectations (`last known data` vs `last known tasks`) and was corrected to the redesign wording.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Android instrumentation sources compiled after migrating Home/Add Task selectors to icon-FAB content descriptions.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: 16 fake-backend tests passed after changing served snapshots to use current-date relative task times.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests and `assembleDebug` completed successfully.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (iteration)
- Evidence: First run reused a stale fake backend on port 18433, so Home did not receive the updated current-date fixture. Stale process PID 81844 was stopped and the lane was rerun.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (iteration)
- Evidence: Home state was correct (`rows=2 current=true attention=true` from temporary debug log), but instrumentation used exact-one text selectors for title/remaining text that appear in both current hero and timeline row. Selectors were changed to wait for any matching text node.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests and `assembleDebug` passed after selector fixes.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 23 tests with 0 skipped and 0 failed, including the new Home current-block `Done` fake-backend assertion.

Verification:
- Command: `npx ctx7@latest library "Java time OffsetDateTime" "OffsetDateTime parse now DateTimeFormatter Clock injection Java time"`
- Result: PASS
- Evidence: Resolved `/websites/oracle_en_java`.

Verification:
- Command: `npx ctx7@latest docs "/websites/oracle_en_java" "OffsetDateTime parse now DateTimeFormatter Clock injection Java time"`
- Result: PASS
- Evidence: Context7 returned the Oracle Java source set. The task used local Java time APIs already present in the codebase; no external behavior assumptions were added from sparse docs.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected review fix)
- Evidence: New tests failed at compile time because `buildHomeScreenState` still accepted `String` `now` values and exposed internal fallback behavior. This captured the required API change to injected `OffsetDateTime`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed after `SnapshotViewState.tasks` and required `OffsetDateTime` injection were implemented.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests and `assembleDebug` completed successfully after review fixes.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 23 tests with 0 skipped and 0 failed after review fixes.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`
- Result: PASS
- Evidence: Shared Android host tests passed after changing `SnapshotViewState`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected quality fix)
- Evidence: New `HomeDoneActionHandlerTest` failed at compile time because `HomeDoneActionHandler` did not exist. The tests covered failed completion preserving the action error and successful completion waiting for an active refresh before applying the latest cached snapshot.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed after adding `HomeDoneActionHandler` and wiring `MainActivity` through it.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests and `assembleDebug` completed successfully after quality-review fixes.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 23 tests with 0 skipped and 0 failed after quality-review fixes.

## Manual Verification

- Confirmed Home state derives weekday/date labels from injected `now`, not snapshot `serverTime`.
- Confirmed Home uses `dueDatetime` as block start and `dueDatetime + durationMinutes` as block end.
- Confirmed healthy Home state emits no freshness/status text.
- Confirmed Home current block `Done` is wired through `MainActivity` to `EnforcementCoordinator.completeTask(taskId)` followed by refresh, with no direct HTTP call from composables.
- Confirmed icon-only Home FAB actions expose content descriptions `Add task` and `Morning review`.
- Review-fix confirmation: Home planned/done agenda rows now come from reducer `tasks`, so stale/mismatched `serverTime` no longer controls Home inclusion.
- Quality-review confirmation: Home Done orchestration no longer uses the skip-based `EnforcementRefreshRunner`; it serializes refresh/action work and applies the latest cached snapshot after successful completion.

## Known Failures Or Skipped Checks

- No required final checks were skipped.
- The managed-device build emitted the existing native-library strip warning for `libandroidx.graphics.path.so`; packaging and tests continued successfully.
- Two failed e2e iterations are documented above and were resolved before final verification.

## Assumptions

- `HomeScreenState` keeps the exact Task 6 field set; the empty placeholder remains a UI rendering concern when `agendaRows` is empty and `currentBlock == null`.
- The fake backend now serves current-date relative snapshot times from `/tasks/snapshot` so Home e2e assertions do not decay as wall-clock time moves on. The deterministic `fixedSnapshot()` default remains available for fake-backend unit tests.
- Add Task instrumentation needed selector updates because Home's Add Task entry point changed from a full-width text button to an icon-only FAB.
- Review-fix update: `buildHomeScreenState` now accepts `OffsetDateTime` directly, not a string. `BuzerApp` owns the default device-time provider and can inject a deterministic provider in tests.

## Next Recommended Step

Proceed to Task 7 only after this Task 6 commit is reviewed/available. Do not infer blocker-surface migration work from this handoff.
