# Handoff: Unified Task Editor Task 3 — Update Host

Date: 2026-07-23
Agent: `unified_editor_task3`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 3 — shared Update host extracted and Edit migrated; final focused and broad JVM
  verification passed on 2026-07-23.
- [ ] Tasks 4–8 — not attempted; remain owned by follow-on tasks.

## Scope Attempted

- Added one presentation-neutral `TaskEditorUpdateScreen` and its pure state adapter.
  It owns the hydrated update draft, `OnEntry` validation projection, choice availability,
  pending/result transitions, duplicate-submit blocking, and dirty discard confirmation.
- Migrated the public `EditTaskScreen` to load/retry choices and supply the existing direct
  checklist, Activity-log footer, full `UpdateTaskRequest` construction, and normal Home
  callbacks to that host.
- Preserved custom recurrence replacement on a non-self No-schedule selection, direct child
  mutation construction, cached-request retry identity, and existing dirty fingerprints.
- Replays the captured update action after a pending reconciliation response, so the backend
  action journal can re-check authority without creating a second user mutation. Pending remains
  non-dismissible through Back and Cancel, blocks duplicate submission, and keeps discard hidden
  until the authority confirms or rejects it.
- Accepts host-provided checklist validation so incomplete direct checklist items disable Save and
  report their error with the Checklist content. A loaded project-choice list also keeps an
  unavailable snapshot project editable rather than making the form impossible to repair.
- Locks unsupported nested checklist hierarchies read-only with the existing hierarchy warning,
  while leaving the root Update fields editable and omitting direct-checklist mutations on save.
- Removed the dead private `LegacyEditTaskScreen` and helpers reachable only from it after the
  quality-review clarification authorized removal of that duplicate implementation. Retained the
  reusable direct-checklist model/editor and complete `UpdateTaskRequest` builder used by the
  migrated Edit wrapper and Morning Review.
- Replaced the wrapper's paired mutable captured-request fields with one immutable capture. Its
  fingerprint preserves the exact `UpdateTaskRequest` (and action ID) when the Update host replays
  a pending confirmation.
- Did not modify Add, Morning Review, blocker flows, backend/shared APIs, or `tools/fake-backend`.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreenStateTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- Removed `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenStateTest.kt`
  because it only exercised the removed duplicate implementation; its membership coverage now
  exercises the shared Update-state adapter.
- This handoff.

## TDD Evidence

RED (all Gradle commands run from `client/` with `ANDROID_HOME` and
`ANDROID_SDK_ROOT` set):

- `./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorUpdateScreenStateTest'`
  - Expected RED: unresolved `TaskEditorUpdateState`, choices, transitions, and footer
    projection before the shared host existed.
- Same focused command after adding the non-self custom-recurrence No-schedule regression.
  - Expected RED: clearing the schedule did not mark a supported Never replacement.
- Same focused command after adding cached-choice loading coverage.
  - Expected RED: cached projects allowed submission while their refresh was still in flight.
- Same focused command after adding the pending-refresh regression.
  - Expected RED: refreshed collaborator membership mutated the pending draft and cleared its
    duplicate-submit guard.

GREEN:

- The initial host implementation passed six state tests after the fixture assertion was
  aligned with the existing shared validator's blank fallback-ID error.
- The custom-recurrence normalization passed seven tests.
- The loading guard passed eight tests.
- Pending choice-refresh preservation passed nine tests.
- After code review, added coverage for host-slot checklist validation and the captured-action
  pending-confirmation retry.
- The pending-dismissal and unsupported-hierarchy tests initially failed to compile because their
  pure host adapters did not exist. After adding those adapters and the Edit wiring, the focused
  host state test passed. A first GREEN attempt caught one `WarningStatus` function-reference
  compilation error; the minimal lambda correction then passed.
- The action-ID replay integration test initially failed to compile because the Edit wrapper had
  no captured-request abstraction. It now verifies the real wrapper capture plus Update-host
  pending/confirmation reducer path constructs one request, dispatches that same request twice,
  retains its action ID, and reports success once.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorUpdateScreenStateTest' --tests '*EditTaskScreenRequestTest' --tests '*DraftExitPolicyTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`; final shared-host, migrated Edit request, and dirty-exit
  coverage passed after the duplicate-editor removal and action-ID replay coverage (38 tasks;
  3 executed).

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`; final broad Android app JVM suite completed with 38 tasks
  and no test failures (2 executed).

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose" "state hoisting reusable composable with state and callbacks for screen-level host"` followed by `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose" "State hoisting reusable composable with state and event callbacks while host owns business logic, side effects, navigation and screen-level state"`
- Result: PASS
- Evidence: official Compose guidance supports screen-level state/effect ownership and value-plus-callback child composables; `TaskEditorScaffold` remains presentation-only while the Update host owns state/effects.

## Manual Verification

- Not performed. This focused correction is covered by deterministic state/request/dirty-exit JVM
  tests; Task 2's existing managed-device scaffold test covers the footer-after-Checklist shell
  placement. No E2E lane was required or started.

## Known Failures / Skipped Checks

- No E2E lane was run. The task remains JVM-only; neither `tools/fake-backend` nor an E2E script
  was invoked.
- No Android instrumentation/device lane was run because this task changes the host adapter and
  Edit wiring only; the pre-existing deterministic scaffold instrumentation coverage remains in
  Task 2's handoff.

## Assumptions / Self-Review

- `CoordinatorActionResult.Success` remains the existing coordinator signal that an authoritative
  snapshot was applied; the shared reducer delivers `onSuccess` once and only once for that
  result. Pending keeps its draft and remains non-submittable across choice refreshes, then
  retries the same captured action ID after five seconds so backend reconciliation can refresh
  authority safely. While pending, the same pure host decision consumes Back and Cancel; after a
  failure resets pending state, normal dirty-discard behavior resumes.
- Generic stale/local/network result text is intentionally `Task update failed. Your draft is
  still here; retry.` per the unified-editor decision to avoid conflict merge/rebase UI.
- `HierarchyStatus.unsupported` is only a checklist-mutation restriction. The host continues to
  enable root fields, and the Edit wrapper applies the same read-only access to existing and newly
  drafted direct-checklist controls while preserving the snapshot's hierarchy warning.
- The only removed code was the private duplicate Edit implementation plus symbols exclusively
  reachable from it. `ExistingChecklistDraft`, direct-checklist helpers, `updateRequest`, and all
  shared legacy components remain because active flows still use them.
- The replay test crosses the live Edit capture seam and the Update-host result reducer: both the
  initial update and pending confirmation dispatch the same request instance/action ID, and the
  host's normal success effect is asserted once.
- A narrow review accepted the retained private legacy body as the explicit no-deletion
  constraint. The two later Important findings (pending dismissal and nested hierarchy handling)
  are addressed here and covered by the final GREEN JVM runs.

## Next Recommended Step

- Complete integration review for this verified Task 3 branch, then execute Task 4 to move Add
  onto the presentation-neutral scaffold while retaining its quiet `OnInteraction` validation and
  create semantics.
