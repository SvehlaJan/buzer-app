# Handoff: Unified Task Editor Task 2 — Universal Scaffold

Date: 2026-07-23
Agent: `unified_editor_task2`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 2 — make `TaskEditorScaffold` universal and presentation-neutral.
- [ ] Tasks 3–8 — not attempted; remain owned by their follow-on tasks.

## Scope Attempted

- Replaced the authoritative scaffold API with a presentation-neutral overload receiving
  `TaskEditorPresentation`, a host-owned `TaskEditorDraft`, visible shared validation,
  draft callback, generic cancel/primary callbacks, and optional footer content.
- Kept a temporary forwarding legacy overload in `TaskEditorScaffold.kt` because Add and
  Edit are intentionally not migrated until Tasks 3 and 4. It preserves their existing
  callbacks without adding any loading, navigation, request, repair, or blocker behavior
  to the universal shell.
- Rendered the shared form in Title, Schedule, Project, Assignee, Repeat, Notes,
  Checklist order. Entry validation expands Schedule, displays its shared field message
  and common banner, and disables the generic primary action when `canSubmit` is false.
- Kept quick choices and custom wheel dialogs local only for presentation; each selection
  sends a new draft through the host callback. Update context and footer are host-supplied.
- Did not migrate Add, Edit, Morning Review, or Blocker, and did not modify backend,
  shared protocol, legacy editor widgets, or `tools/fake-backend`.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldLayoutTest.kt`
- `docs/handoffs/2026-07-22-unified-task-editor-task2-scaffold.md`

## TDD Evidence

RED (from `client/`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set):

- `./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorScaffoldStateTest'`
  - Expected RED: `scheduleValidationForVisibleErrors` was unresolved before the
    visibility-projection adapter existed.
- `./gradlew :androidApp:pixel8Api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.buzer.android.ui.taskeditor.TaskEditorScaffoldLayoutTest`
  - Expected RED: the existing scaffold lacked the generic `presentation`,
    `onDraftChange`, `onPrimaryAction`, and `validation` parameters.
- Same managed-device class after adding the update-context assertion.
  - Expected RED: `updateFooterFollowsChecklist` failed because `contextMessage` was
    forwarded into the core composable but was not rendered.

GREEN:

- The focused state suite passed after mapping the visible schedule error to a focused
  schedule validation object.
- The managed-device class passed five tests after adding the universal overload and
  rendering context: entry validation/order, long equal-width action, quick callback,
  custom wheel-dialog callback, and context/footer placement.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorScaffoldStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; the new visible-schedule-error state coverage and the
  existing scaffold state tests passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:pixel8Api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.buzer.android.ui.taskeditor.TaskEditorScaffoldLayoutTest`
- Result: PASS
- Evidence: managed-device instrumentation result `OK (5 tests)` in 15.52 seconds.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; full Android app JVM suite completed with no failures.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors after the handoff was added.

## Manual Verification

- The managed-device layout test supplied deterministic visual/interaction verification;
  no separate manual device inspection was required.

## Known Failures / Skipped Checks

- The first sandboxed Gradle invocation could not create the shared Gradle wrapper-cache
  lock. The approved elevated retry was used for all Gradle commands.
- `scripts/e2e-fake-backend.sh` was intentionally skipped. This task explicitly requires
  the backend-free managed-device class and excludes deprecated fake-backend invocation.
- No build/install or full app e2e lane was run because this task changes only the shared
  scaffold API; host migration and real backend e2e are owned by later tasks.

## Assumptions

- `TaskEditorPresentation.showUpdateFooter` controls whether the supplied footer slot is
  rendered. Update hosts will set it when they provide an Activity-log footer.
- The temporary legacy overload is required only while Tasks 3 and 4 leave existing Add
  and Edit call sites untouched; remove it when those hosts have migrated.
- A custom recurrence without a selected supported replacement displays `Custom
  recurrence`; its exact legacy rule text remains preserved by the forwarding adapter
  until Edit migrates.

## Next Recommended Step

- Task 3 should introduce `TaskEditorUpdateScreen`, own entry validation and submit state
  there, and migrate Edit to the presentation-neutral `TaskEditorScaffold` overload.
