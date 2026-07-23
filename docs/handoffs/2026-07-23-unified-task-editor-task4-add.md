# Handoff: Unified Task Editor Task 4 — Add Host

Date: 2026-07-23
Agent: `unified_editor_task4`
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`

## Task / Checkbox Status

- [x] Task 4 — migrated Add onto the presentation-neutral task-editor shell without
  changing Create semantics.
- [ ] Tasks 5–8 — not attempted.

## Scope Attempted

- Moved `AddTaskScreen` from the temporary forwarding `TaskEditorScaffold` overload to
  the generic shell with `TaskEditorPresentation(primaryVerb = "Add")` and common
  `validateTaskEditor` visibility.
- Added an Add-host state projection for `ValidationExposure.OnInteraction`: initial
  renders are quiet; changed fields reveal their relevant shared errors; a primary
  attempt reveals all errors; the banner clears as soon as the draft becomes valid.
- Kept automatic default-project hydration outside the touched-field path, so loading a
  persisted project and its Me selection cannot expose validation before a user edit.
- Kept Add actionable before the first validation attempt when choices are available,
  so tapping Add can reveal all invalid fields. Selection/collaborator loading and
  submitting still disable the primary while leaving the editor rendered.
- Preserved the current project default/persistence behavior, project-member selection
  rule, request payload serialization, retained request object/action ID on retry,
  checklist request conversion, create failure dialog, successful navigation, and
  dirty Cancel/system-Back discard policy.
- Retained custom recurrence unchanged when its schedule changes; common validation
  immediately requires an explicit supported Repeat replacement.
- Removed the now-unused temporary legacy `TaskEditorScaffold` forwarding overload.
- Migrated the legacy Android fake-backend test's semantic action selectors, helper
  names, and range regex from `Save` to the approved `Add` verb. The JVM state contract
  now asserts both bare `Add` and `Add HH:MM–HH:MM` without requiring a backend server.
- Did not change `AddTaskFormValidator.kt` or its focused compatibility test: Task 5's
  `MorningReviewScreen` still imports `AddTaskInvalidField` from that file. Add itself
  no longer imports or calls the compatibility validator; Task 5/7 must remove the
  remaining compatibility type/file once its caller is migrated.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/AddTaskScreenStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- This handoff.

## TDD Evidence

RED (from `client/`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set):

- `./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskScreenStateTest'`
  failed before implementation because `addTaskEditorState`, `addTaskCreateRequest`,
  and `retainAddTaskCreateRequest` were unresolved. The initial sandboxed invocation
  could not open the shared Gradle wrapper lock; the approved cache-access retry reached
  the intended compile failure.

GREEN:

- The same focused command passed after adding the Add interaction-state projection and
  request/retry helpers. A first Green compilation caught one stale legacy `errorText`
  reset, which was removed before the passing rerun.
- Self-review then found that automatic project hydration was being recorded as a user
  touch. A new `automaticDefaultProjectHydrationDoesNotTouchValidationFields` test was
  RED because `addTaskTouchedFields` did not exist; it passed after the one-source fix
  that distinguishes user edits from hydration.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskScreenStateTest' --tests '*AddTaskCreateFlowTest' --tests '*AddTaskSubmitStateTest' --tests '*AddTaskFailurePresentationTest' --tests '*AddTaskProjectLoadFeedbackTest' --tests '*AddTaskFormValidatorTest' --tests '*AddTaskAssigneePresentationTest' --tests '*DraftExitPolicyTest' --tests '*TaskEditorScaffoldStateTest' --tests '*TaskEditorValidationTest' --tests '*TaskEditorUpdateScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; focused Add, compatibility-validator, dirty-exit,
  central validation, scaffold, and Update-host suites completed with no failures.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 38 Gradle tasks completed with no Android app JVM
  test failures. Rerun after the selector-only follow-up on 2026-07-23.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; the source-level JVM contract verifies both the bare
  `Add` fallback label and the scheduled `Add HH:MM–HH:MM` range label used by the
  migrated instrumentation selectors.

Verification:
- Command: `rg -n "Save task|Save \\d|clickSave|waitForSave|saveLabels|saveBlockRange" client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt || true`
- Result: PASS
- Evidence: no legacy Save action selector/helper/pattern remains in the migrated
  Android test source.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; the migrated instrumentation source compiled without
  starting an emulator, backend, or fake-backend test class.

Verification:
- Command: `rg -n "AddTaskFormValidator|AddTaskFormResult" client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- Result: PASS
- Evidence: no Add-screen compatibility-validator import/call remains; the only
  production scaffold declaration is the generic presentation-neutral overload.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before the handoff was added; rerun after this handoff
  is required immediately before commit.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose" "state hoisting and stateful screen hosts delegating to stateless reusable form composables"` followed by `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose" "State hoisting reusable composable with state and callbacks while a screen host owns business logic, side effects, navigation and screen-level state"`
- Result: PASS
- Evidence: the official Compose state-hoisting guidance supports the Add host owning
  validation/request/navigation state and passing state plus callbacks to the reusable
  scaffold.

## Manual Verification

- Not performed. This host migration is covered by deterministic JVM state/request/
  dirty-exit tests; the generic shell's managed-device layout coverage remains from
  Task 2.

## Known Failures / Skipped Checks

- No E2E command was run. The task explicitly prohibits `tools/fake-backend` and all
  fake-backend scripts; `scripts/e2e-real-backend.sh` is the authoritative lane but is
  reserved for Task 8.
- No instrumentation/device lane was run because this task changes host wiring and pure
  Add state. `AddTaskFakeBackendTest` selectors were migrated but intentionally not run:
  do not start its fake server/tooling, and Task 8 owns the eventual
  `scripts/e2e-real-backend.sh` verification against the main backend with fake adapters.
- The retained compatibility validator/type and `AddTaskFormValidatorTest` are a known
  deferred cleanup dependency of Task 5's legacy Morning Review implementation, not an
  Add caller.

## Assumptions

- The generic shell's `setNoSchedule` behavior is authoritative for Add as well: a
  custom recurrence is not replaced silently; `validateTaskEditor` blocks the changed
  custom schedule until Repeat explicitly supplies a supported replacement.
- `primaryActionEnabled` deliberately permits the first primary attempt when ordinary
  errors are still hidden, then uses the submitted common validation to disable repeat
  attempts until the draft is valid. Loading/unavailable selection facts always keep it
  disabled.
- Retry identity means retaining the same `CreateTaskRequest` instance/action ID while
  the draft is unchanged, matching the previous `pendingCreateRequest` behavior.

## Next Recommended Step

- Integrate/review this Task 4 branch, then migrate Morning Review in Task 5. Remove
  `AddTaskFormValidator.kt` and its compatibility test only after Morning Review no
  longer imports `AddTaskInvalidField`.
