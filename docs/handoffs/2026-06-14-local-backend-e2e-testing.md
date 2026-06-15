# 2026-06-14 Local Backend E2E Testing

## Task / Plan

- Plan: `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`
- Branch: `feature/project-aware-task-creation`
- Checkbox status: Tasks 1-5 complete.
- Execution mode: started with subagent-driven development. Final Task 3 review and Task 4 implementation/review were completed locally after subagent quota returned a usage-limit error.

## Files Changed

- Fake backend:
  - `tools/fake-backend/src/server.ts`
  - `tools/fake-backend/src/server.test.ts`
- Android instrumentation:
  - `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
  - `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Scripts and docs:
  - `scripts/e2e-fake-backend.sh`
  - `scripts/local-real-backend-smoke.mjs`
  - `scripts/local-real-backend-smoke.test.mjs`
  - `docs/notes/local-development.md`
  - `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`
  - `docs/handoffs/2026-06-14-local-backend-e2e-testing.md`

## Commits Created

- `1a57c3c` `test: add fake backend project failure toggle`
- `d1b2e73` `test: cover project-aware add task e2e`
- `bd5386c` `test: tighten add task e2e isolation`
- `99bc50d` `test: reset add task e2e project cache`
- `77d1739` `test: add fake backend e2e runner`
- `039138e` `test: harden fake backend e2e runner`
- `ebe3fb3` `test: add local real backend smoke`

## Verification

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: Node test runner reported 15 tests passed, 0 failed.

- Command: `node --test scripts/local-real-backend-smoke.test.mjs`
- Result: PASS
- Evidence: Node test runner reported 6 tests passed, 0 failed.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: Vitest reported 15 test files passed, 56 tests passed.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed successfully.

- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` completed and copied `src/db/schema.sql` to `dist/db/schema.sql`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Gradle reported `BUILD SUCCESSFUL`; 70 actionable tasks, 8 executed, 62 up-to-date.

- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Script started `tools/fake-backend`, ran `pixel8Api34DebugAndroidTest`, finished 21 tests on `pixel8Api34`, and Gradle reported `BUILD SUCCESSFUL in 2m 39s`.

- Command: `scripts/local-real-backend-smoke.mjs --help`
- Result: PASS
- Evidence: Printed usage with required live Todoist opt-in flag.

- Command: `scripts/local-real-backend-smoke.mjs`
- Result: PASS
- Evidence: Exited non-zero with `Refusing to run without --i-understand-this-uses-live-todoist`.

- Command: `test -f backend/.env.local-smoke`
- Result: SKIPPED
- Evidence: File was absent, so the live Todoist smoke was not run.

## Manual Verification

- Confirmed `scripts/e2e-fake-backend.sh` is executable and uses deterministic fake-backend token `test-token`.
- Confirmed `scripts/local-real-backend-smoke.mjs` is executable and guarded by both explicit CLI opt-in and `WHATSAPP_DRY_RUN=true` validation.
- Confirmed no production backend cleanup endpoint was added; live-smoke cleanup uses Todoist REST DELETE directly.

## Known Failures / Skipped Checks

- Live real-backend smoke was initially skipped because `backend/.env.local-smoke` did not exist in this checkout. On 2026-06-15, the env was copied from `/home/skclaw/buzer-app/.env` on `oc-vps.taila50381.ts.net`, rewritten locally with smoke-safe overrides, and the live smoke passed.
- Task 3 worker initially hit sandbox `tsx` IPC failure; elevated/outside-sandbox script runs passed afterward.
- Subagent quota was exhausted during final Task 3 quality review, so the last Task 3 review and Task 4 implementation/review were completed locally.
- Existing unrelated dirty files remain untouched:
  - `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
  - `docs/handoffs/2026-06-13-context-status-and-next-step.md`
  - `docs/handoffs/2026-06-13-real-alarm-verification.md`
  - `docs/notes/2026-06-13-product-backlog.md`

## Assumptions

- The deterministic Android E2E lane should always use fake-backend token `test-token`, independent of any real `BUZER_BACKEND_TOKEN` exported in the developer shell.
- The live local smoke remains opt-in because it creates real Todoist tasks even though it attempts automatic cleanup.
- `WHATSAPP_DRY_RUN=true` is mandatory for all local real-backend smoke runs.

## Next Recommended Step

1. Return to the product backlog items: Home should show the in-progress task, and Add Task should gain the mandatory persisted project picker behavior already covered by tests.

## 2026-06-15 Live Smoke Follow-Up

- Copied the VPS env from `oc-vps.taila50381.ts.net:/home/skclaw/buzer-app/.env` to ignored local file `backend/.env.local-smoke`.
- Rewrote local-only safety values without printing secrets:
  - `NODE_ENV=development`
  - `PORT=18434`
  - `DATABASE_PATH=./data/buzer-local-smoke.db`
  - `WHATSAPP_DRY_RUN=true`
  - `WHATSAPP_SESSION_PATH=./whatsapp-sessions`
  - `WHATSAPP_CLIENT_ID=buzer-local-smoke`
  - `SMOKE_ASSIGNEE_ID` from `TODOIST_USER_ID`
- First live smoke exposed `todoist_create_verification_failed` with `due_datetime_mismatch`. Todoist created task `6grjp9GQ9HQvxJGg`; it was manually deleted with Todoist REST and returned HTTP 204.
- Root cause: the smoke script generated fractional-second due datetimes, while Todoist REST returns due datetimes at whole-second precision. Backend verification compared strings exactly.
- Fix: backend create verification now compares due datetimes at Todoist whole-second precision, and the smoke script records `todoistTaskId` from verification-failure responses so cleanup can delete failed-created Todoist tasks.

Verification:
- Command: direct Todoist create/delete due probe
- Result: PASS
- Evidence: Todoist returned `due.date` without fractional seconds for requested fractional-second due datetime; probe task was deleted with HTTP 204.

Verification:
- Command: `cd backend && npm test -- taskActionService`
- Result: PASS
- Evidence: Vitest reported 1 test file passed, 13 tests passed.

Verification:
- Command: `node --test scripts/local-real-backend-smoke.test.mjs`
- Result: PASS
- Evidence: Node test runner reported 7 tests passed, 0 failed.

Verification:
- Command: `cd backend && npm test`
- Result: PASS
- Evidence: Vitest reported 15 test files passed, 57 tests passed.

Verification:
- Command: `scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist`
- Result: PASS
- Evidence: Smoke passed for run `20260615061342`; cleaned Todoist tasks `6grjpww2P6HccHFq` and `6grjpwr8Jv82XG7g`.

Verification:
- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed successfully.

Verification:
- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` completed and copied `src/db/schema.sql` to `dist/db/schema.sql`.
