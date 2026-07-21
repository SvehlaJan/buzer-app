# Real-backend Android E2E validation triage

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`, Task 9.

## Gates

### 1. Backend gate

```text
cd backend && npm test && npm run typecheck
```

Result: **PASS**. Vitest: 31 test files / 370 tests passed. TypeScript typecheck
completed with no diagnostics. This was run initially and again after the E2E debug
parity correction.

```text
git diff --stat master -- backend/src backend/test/fakes/fakeTodoistAdapter.ts
```

Result: **PASS**. The command produced no output: neither production backend code nor
the old fake adapter differs from `master`. `git diff --check` also produced no output.

### 2. Old fake-backend control

```text
ANDROID_HOME=/Users/svehla/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk \
scripts/e2e-fake-backend.sh
```

Final result: **PASS**. The managed-device JUnit XML reported 27 tests, 0 failures, 0
errors, and 2 expected skips: both `RealBackendScenarioTest` methods self-skip because
the old fake intentionally has no `/__scenario` endpoint. This was the control run
before the real lane; its normal managed-device XML location was later overwritten by
the real-lane rerun.

Setup and cleanup: the isolated worktree initially lacked
`tools/fake-backend/node_modules`, so `cd tools/fake-backend && npm ci` was run
successfully before the control lane. A sandbox attempt could not create tsx's temporary
IPC socket (`listen EPERM`), so the completed lane used the elevated Android/emulator
environment. The runner's cleanup trap stopped the server it started.

### 3. Real-backend lane

```text
ANDROID_HOME=/Users/svehla/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk \
scripts/e2e-real-backend.sh
```

Initial result: 27 tests, 9 failures, 0 errors, 0 skips. Both new
`RealBackendScenarioTest` cases passed. Five failures were the same forced-action error
parity defect; four were old-fake discrepancies listed below.

The defect was fixed in `2aabcb7 fix(e2e): preserve forced action error code parity`.
The final rerun reported **27 tests, 1 failure, 0 errors, 0 skips**. Both
`RealBackendScenarioTest` cases pass:

- `deadlineDone_underCommandTimeout_goesReconciliationPending`
- `deadlineDone_underSyncLag_goesReconciliationPending`

The sole failure is `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`,
the documented `[partial-failure]` fake-only convention below. Final Android evidence:

- XML: `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/TEST-pixel8Api34-_androidApp-.xml`
- Test log: `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/testlog/test-results.log`
- Per-test Android logcats: `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/logcat-*.txt`

The runner cleaned up its server. A post-run
`lsof -nP -iTCP:18433 -sTCP:LISTEN` produced no listener.

## Failure triage

### Fixed: E2E debug parity bug (bucket a)

Five initial failures were:

- `addTask_backendFailureKeepsFormAndEnteredTitle`
- `deadlineBlocker_backendFailureShowsRetryDialogAndWritesErrorLog`
- `morningReview_repairBackendFailureShowsErrorAndStaysOpen`
- `deadlineBlocker_rescheduleBackendFailureShowsRetryDialogAndWritesErrorLog`
- `startBlocker_backendFailureShowsRetryDialogAndWritesErrorLog`

Observed behavior: the Android error log contained
`message=Forced action failure`, but the assertions require `forced_failure`.

Root cause: the old fake's action failure is `{ code: "forced_failure", snapshot }`.
The new E2E debug pre-handler returned the same code *plus*
`message: "Forced action failure"`; `BuzerApiClient` deliberately uses a response
message in preference to its code, so the client observed different behavior.

Fix: the E2E-only `POST /__fail-actions` response now omits `message`, preserving the
old-fake contract. A focused conformance test was first made red by asserting no
`message` property (it failed with the unexpected property), then turned green. The
focused `npx vitest run test/e2eServer.test.ts` passed (19 tests), and the full backend
gate passed afterward. The final Android rerun confirms all five failures are fixed.

### Documented old-fake discrepancies (bucket c)

1. `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`
   - Observed: the real backend creates the requested checklist rather than exposing
     the old fake's synthetic reconciliation-pending path; the assertion cannot find
     `Checklist item 1` after the `[partial-failure]` draft.
   - Cause: the old fake treats the literal title `[partial-failure]` as a magic
     partial-create failure. No such Todoist or production-backend behavior exists.
   - Resolution: document only; do not teach the real E2E world this fake convention.

### Fixed client contract and test-control defects

- The production validation codes `due_datetime_required` and `duration_required` are
  now mapped by `MorningReviewScreen` alongside the old fake aliases; the two morning
  review tests pass against both backends.
- `UpdateTaskRequest.childMutations` is annotated with `@EncodeDefault`, so an empty
  list is sent to the strict production update route; the repair test passes.
- The AddTask instrumentation helper now retries managed-emulator host startup and
  local validation submits. This resolved intermittent lost-picker/submit clicks and
  initial `10.0.2.2` readiness failures without changing production behavior.

No fixture-seeding defect (bucket b) was found.

## Acceptance audit

| Criterion | Evidence | Status |
| --- | --- | --- |
| Test-only adapter/world and two presets | `backend/test/fakes/e2eTodoistAdapter.ts` exists and declares `implements TodoistAdapter`; the named presets are present. | PASS |
| E2E server, npm entry point, 15-task/2-project seed | `backend/test/e2eServer.ts` exports `buildE2EServer`; `backend/package.json` has `e2e-server: tsx test/e2eServer.ts`; fixture module contains the expected tasks/projects. | PASS |
| Six debug endpoints and reset parity | all six route registrations are present; `test/e2eServer.test.ts` passes as part of the full 370-test backend run. | PASS |
| Backend conformance suite | `cd backend && npm test` passed: 31 files / 370 tests. | PASS |
| Runner exercises old + real scenarios | old-fake control: 27 tests, no failures, 2 expected scenario skips; real final lane: both scenario tests pass and all remaining failures are triaged bucket-c discrepancies. | PASS WITH DOCUMENTED DISCREPANCIES |
| Protected source untouched | protected `git diff --stat` was empty. | PASS |

The one remaining real-lane failure is intentionally left visible as evidence of
old-fake drift; it is not a reason to make the real backend behave like the fake.
