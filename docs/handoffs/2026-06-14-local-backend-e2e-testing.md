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

- Live real-backend smoke was skipped because `backend/.env.local-smoke` does not exist in this checkout.
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

1. Create `backend/.env.local-smoke` with local dry-run settings and real Todoist credentials when ready.
2. Run `scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist`.
3. Then return to the product backlog items: Home should show the in-progress task, and Add Task should gain the mandatory persisted project picker behavior already covered by tests.
