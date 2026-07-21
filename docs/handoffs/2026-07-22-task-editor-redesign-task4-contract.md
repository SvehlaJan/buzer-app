# Handoff: Task Editor Redesign — Task 4 shared contract and validation

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 4, Update shared Kotlin DTOs and Android validation state
- Checkbox status: Task 4 implementation is complete in this worktree. The parent
  controller owns plan-checkbox updates.

## Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/AddTaskAssigneePresentationTest.kt`
- Direct caller migrations required by the deleted assignee type:
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
  - `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `docs/handoffs/2026-07-22-task-editor-redesign-task4-contract.md`

## Implementation

- Added the serializable `ProjectCollaborator` DTO and safe collaborator/readiness
  defaults to `ProjectItem`, preserving older snapshot fixtures.
- Made create schedules nullable and added a JSON-only create-request serializer so
  both nullable schedule fields are deliberately sent as JSON `null`. The app-wide
  JSON setting uses `explicitNulls = false`, so annotations alone could not express
  the required clear contract.
- Changed root due/duration fields to typed `NullablePatch<String>` and
  `NullablePatch<Int>` values. String and integer helpers independently preserve
  absent-versus-explicit-null update semantics.
- Removed the static `AddTaskDependencies.assigneeOptions()` and
  `AddTaskAssigneeOption`. Add and Morning Review now consume `ProjectCollaborator`
  values from project data, retaining an injectable fallback solely for legacy test
  seams. The existing Morning Review combined editor remains in place.
- Updated the Add validator: self requires a complete positive schedule; non-self
  accepts only complete or entirely absent schedules; partial and non-positive
  schedules fail. A personal project keeps its locked `Me` selection locally while
  encoding a null Todoist assignee. An unscheduled request is normalized to `never`
  recurrence; device zone data is included only for a scheduled recurrence.
- Updated existing Edit/Morning repair update callers to wrap their always-present
  schedules in the new typed patch values without changing their editor flow.
- Follow-up: a personal project's Assignee section now displays its locked `Me`
  selection with the non-interactive hint `Personal project · assigned to you`.
  The UI still encodes `assigneeId = null`, as required by the Todoist contract.

## TDD / Debugging Evidence

- Initial RED:
  `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests app.buzer.shared.api.BuzerApiClientJsonTest`
  and the equivalent validator command both failed as intended after the new tests:
  nullable create fields, collaborator DTOs, and typed schedule patches did not yet
  exist.
- The first Green attempt found that `@EncodeDefault` still omitted `null` with the
  repository's `Json { explicitNulls = false }` configuration. The test XML showed
  the exact serialized request without either schedule field. A dedicated custom
  serializer, patterned after the existing reschedule serializer and verified against
  Context7 kotlinx-serialization guidance, fixes that root cause.
- The locked-assignee presentation test was first run RED before its pure UI-state
  helper existed; it then passed after implementation. A targeted fake-backend
  instrumentation run initially exposed an ambiguous duplicate `Select project`
  label introduced by the follow-up. Removing that redundant label restored the
  existing test selector and the targeted test passed.

## Verification

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests app.buzer.shared.api.BuzerApiClientJsonTest`
- Result: PASS
- Evidence: 10 JSON contract tests passed, including collaborator decoding and
  explicit-null versus absent schedules.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest`
- Result: PASS
- Evidence: validator cases cover self, non-self unscheduled, partial, and
  non-positive schedule rules.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`
- Result: PASS
- Evidence: complete shared host test suite passed.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: complete Android JVM unit suite passed, including legacy Morning Review
  and blocker request coverage.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: debug APK assembled successfully.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.AddTaskAssigneePresentationTest`
- Result: PASS
- Evidence: focused unit tests cover locked personal-project `Me` presentation and
  absence of the lock for shared projects.

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_personalProjectShowsLockedMe scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: managed-emulator instrumentation report records `Tests run: 1`, `OK
  (1 test)`, and `test-result-exit-code.txt` is `0`.

## Manual Verification

- Context7 lookup: kotlinx-serialization custom serializer and default-value
  encoding behavior were checked before implementing explicit JSON null handling.
- Inspected the serialized test failure and final JSON assertions to confirm create
  emits `"dueDatetime":null` and `"durationMinutes":null`, while an unchanged root
  patch omits both fields.

## Assumptions / Notes

- The existing build configuration still declares legacy `BUZER_ASSIGNEE_ME_ID` and
  `BUZER_ASSIGNEE_WIFE_ID` fields, but no Kotlin source consumes them after this
  task. Removing build configuration is intentionally left out of this focused
  contract migration.
- The new Add/Edit editor work will own richer project/member loading, draft
  selection, and member-readiness UX. This task keeps the pre-redesign legacy
  surfaces compiling and behaviorally equivalent while they transition away from the
  static option type.
- The current fake `/projects` fixture has no collaborator or readiness fields.
  Therefore shared-project Add Task instrumentation still cannot choose a real
  collaborator after the intentional removal of static assignee options. Task 9
  must add collaborator fixtures before the full `AddTaskFakeBackendTest` class is
  expected to pass; this follow-up only runs the targeted personal locked-`Me` UI
  case.

## Next Recommended Step

Proceed with Task 5/6 draft and Compose editor primitives, then migrate Add/Edit to
those components in Tasks 7/8 and add end-to-end coverage in Task 9.
