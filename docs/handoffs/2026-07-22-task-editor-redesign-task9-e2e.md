# Task editor redesign — Task 9 E2E parity handoff

## Task / plan status

Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`, Task 9.

- [x] Step 1 — added fake/real fixture parity and migrated editor instrumentation coverage.
- [x] Step 2 — observed the intended backend contract gaps; Android compilation reached the local Gradle-cache sandbox blocker.
- [x] Step 3 — made deterministic fake and E2E-world fixture behavior match the route contract.
- [x] Step 4 — migrated Add tests to redesigned-editor semantics and added Edit root-update coverage; legacy blocker picker tests remain unchanged.
- [ ] Step 5 — Node suites pass. Android compilation and device E2E remain blocked by the documented sandbox/usage-limit condition.

## Files changed

- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `backend/test/e2eServer.ts`
- `backend/test/e2eServer.test.ts`
- `backend/test/fakes/e2eFixtures.ts`
- `backend/test/fakes/e2eFixtures.test.ts`
- `backend/test/fakes/e2eTodoistAdapter.ts`
- `backend/test/fakes/e2eTodoistAdapter.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`

## What changed

- Real E2E fixtures now seed active `Me`/`Wife` collaborator mirrors and shared-project membership. The test server resets those tables and the E2E Todoist world returns them from sync.
- Both `/projects` harnesses now expose matching, ordered `ProjectItem` JSON: personal first, `collaboratorsReady`, current user first with `email: null`, and Wife with her email in the shared project.
- Fake root creation/update accepts an unscheduled Wife task, applies project/assignee changes, clears nullable due/duration fields, and rejects members outside the selected project. The full request trace is test-only at `/__calls/full`; existing `/__calls` stays metadata-only.
- E2E-world coverage explicitly clears duration alongside due. The action-service FakeTodoistAdapter contract is already locked by the existing root-update assertions for `{ due: clear, duration: clear }`.
- Add instrumentation now uses Schedule, Project, Assignee, Notes, and Checklist semantics; it covers quick choices, each Custom dialog Cancel/Apply, collapsed summaries, draft-only changes, selector selection dismissal, and Cancel/device-Back discard confirmation.
- New Edit instrumentation covers Project/Assignee draft changes, one full root patch on Save, retry payload preservation, and Cancel/device-Back discard confirmation.
- `BlockerActivityFakeBackendTest` remains unchanged and still owns every legacy `Start picker`/`Duration picker` assertion. A static scan found no legacy picker labels in the Add/Edit tests.

## Verification

- Command: `npx vitest run test/fakes/e2eFixtures.test.ts test/fakes/e2eTodoistAdapter.test.ts test/e2eServer.test.ts` (from `backend/`)
  Result: PASS
  Evidence: 3 files, 43 tests passed.

- Command: `npm test` (from `backend/`)
  Result: PASS
  Evidence: 32 files, 386 tests passed.

- Command: `npm test` (from `tools/fake-backend/`)
  Result: PASS
  Evidence: 15 tests passed.

- Command: `git diff --check`
  Result: PASS
  Evidence: no whitespace errors.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin` (from `client/`)
  Result: BLOCKED
  Evidence: sandbox denied Gradle's existing wrapper cache lock at `~/.gradle/wrapper/dists/gradle-9.5.1-bin/...zip.lck` with `Operation not permitted`. The required escalation was automatically rejected because of the usage limit. Do not retry this command or work around the cache restriction in this task.

- Command: `scripts/e2e-fake-backend.sh`
  Result: SKIPPED
  Evidence: depends on the same unavailable Gradle/device lane; no workaround was attempted.

## Manual/static verification

- Confirmed the Add/Edit tests use redesigned semantic labels and the en-dash Save range label.
- The deterministic `ScheduleQuickChoicesTest` already asserts the cross-midnight summary and `Save 23:30–00:30`; device confirmation remains part of Task 10 once Android E2E is runnable.
- Confirmed legacy picker labels are retained only in `BlockerActivityFakeBackendTest`.

## Assumptions and next step

- The fake response intentionally hides the current user's email (`null`) to match the production `/projects` projection even though the fixture mirror retains the source email.
- Task 10 should run Android test compilation and both documented fake/real backend E2E lanes once the Gradle-cache approval limitation is cleared, then inspect the device flow described in the implementation plan.

## Post-review correction

Review found that the fake backend still accepted an empty or partial schedule for a self-assigned root. The follow-up applies production-equivalent proposed-state validation before fake root creation and update:

- self (`user-me`) and personal-project roots require both due datetime and positive duration;
- non-self collaborators may have an empty schedule but never a partial one;
- validation returns the production `task_validation_failed` response shape and the matching `due_datetime_required`, `duration_required`, `schedule_fields_must_be_set_together`, and positive-duration codes.

Verification:

- Command: `npm test` (from `tools/fake-backend/`)
  Result: PASS
  Evidence: 16 tests passed, including self create/update rejection, partial non-self rejection, and empty non-self update acceptance.

- Command: `npx vitest run test/services/taskActionService.test.ts test/http/routes.test.ts` (from `backend/`)
  Result: PASS
  Evidence: 2 files, 83 tests passed.

- Command: `git diff --check`
  Result: PASS
  Evidence: no whitespace errors.

Android compilation/E2E remains blocked for the same documented Gradle-cache sandbox and usage-limit reason; it was not retried.
