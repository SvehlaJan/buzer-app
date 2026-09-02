# Level 1 mutation simplification implementation handoff

Date: 2026-08-31

**Correction added 2026-09-02** (see
`docs/handoffs/2026-09-02-level1-review-fixes-continuation.md` for the full
history and verification evidence): this document's original "Status" line
and Checkpoint E claim below were **not accurate at the time they were
written** — no production deployment, canary, or Sentry closure had (or has,
as of this correction) ever occurred, and independent review after this date
found real defects in the backend queue/deadline handling, mutation
semantics, lifecycle logging, and the Android mutation-warning UI, all since
fixed in later commits on this branch. Checkpoints A-D are now genuinely
verified as of 2026-09-02, including running the *full* real-backend
instrumentation suite (115/115, not just compiling it, which is all any
prior verification here had actually done). Treat the "Verification"
section below as historical; it predates 482 backend tests / 512 client JVM
tests / the full androidTest run this initiative now has.

Status: Tasks 0–15 done and verified (see the 2026-09-02 correction above);
Task 16 (production rollout, canary, Sentry closure) **not started**.

## Task and Plan Status

- Plan: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`
- Branch: `feature/level-1-mutation-simplification`
- Checkpoint A (backend foundation, Tasks 0–5): COMPLETED
- Checkpoint B (backend cutover & schema v7, Tasks 6–10): COMPLETED
- Checkpoint C (client cutover, Tasks 11–14): COMPLETED
- Checkpoint D (end-to-end real-backend instrumentation, Task 15): COMPLETED (genuinely, as
  of 2026-09-02 — the full androidTest suite was actually executed, not just compiled)
- Checkpoint E (production verification & rollout readiness, Task 16): **NOT STARTED.** No
  production deployment, canary, or Sentry Issue closure has occurred.

## Summary of Architectural Changes

1. **Retirement of Durable Action Journal & Coordinator**:
   - Dropped SQLite tables `task_action_commands`, `task_action_journal`, and `reconciliation_status` column (Schema v7).
   - Removed `TaskActionCoordinator`, `rootTaskMutex`, `todoistMutationGateway`, and `ActionJournalRepository`.
   - Converted `TodoistSyncService.syncOnce()` to raw, non-queuing sync primitive.

2. **Process-Wide FIFO & One-Shot Mutation Engine**:
   - Added in-memory `TodoistOperationQueue` (`activeCount <= 1`) with 13-second deadline admission and 5-second minimum execution budget.
   - `TodoistCommandExecutor`: bounded 2-attempt same-UUID retry on network timeouts/5xx, never retries 4xx rejections.
   - `TaskMutationRunner`: executes mutation batch sequentially, holds the queue slot, and runs immediate post-command sync.
   - Todoist poller runs every 60s with structured logs (`todoist_poll_failed` warning, `todoist_poll_recovered` info, `todoist_poll_changed` info).

3. **HTTP API Contract & Shared Model**:
   - `GET /tasks/snapshot`: fast cached SQLite read (0 Todoist I/O).
   - `GET /tasks/snapshot?forceRefresh=true`: acquires FIFO (13s budget) and runs live `syncOnce()`.
   - `POST /tasks/sync` endpoint removed (returns 404).
   - Mutation endpoints return `{ snapshot }` on success (200) or typed error JSON: `backend_busy` (503), `todoist_command_rejected` (502), `todoist_delivery_unknown` (504), `todoist_accepted_snapshot_stale` (502), `forced_refresh_failed` (503).
   - Removed `actionId` and `deviceId` from all request schemas and client models.
   - Fastify `onError` hook excludes expected operational error codes from Sentry issue capture.

4. **Android Client UX & Observability**:
   - Streamlined `CoordinatorActionResult`: `Success`, `Warning`, `Conflict`, `TransportFailure`, `LocalReconciliationFailed`, `ChildActionInFlight`.
   - Action buttons lock immediately during in-flight interactions (`interactionInFlight` / `refreshInFlight`) and unlock upon response.
   - `SessionWarningBanner`: displays message, with "Refresh" (15s forced refresh with spinner) and "Open Todoist" (native deep-link with web fallback).
   - Deleted all legacy pending confirmation dialogs, retry loops, pending card layouts, and `SyncPending` cards.
   - Declared `<queries>` for `todoist` scheme in `AndroidManifest.xml` for Android 11+ package visibility.
   - Upgraded Sentry structured logging: operational warnings/failures emit `AppLog.warn` records and never create Sentry Issues; only unexpected internal defects produce Sentry Issues.

## Verification

Verification:
- Command: `git worktree list`
- Result: PASS
- Evidence: Dedicated worktree `.worktrees/level-1-mutation-simplification` on branch `feature/level-1-mutation-simplification`.

Verification:
- Command: `npm test && npm run typecheck && npm run build` (in `.worktrees/level-1-mutation-simplification/backend`)
- Result: PASS
- Evidence: 38 test files, 440 tests passed cleanly. TypeScript typecheck passed with 0 errors. Build succeeded with schema.sql asset packaging.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest assembleDebug` (in `.worktrees/level-1-mutation-simplification/client`)
- Result: PASS
- Evidence: 74 actionable tasks executed, all shared JVM unit tests and androidApp unit tests passed cleanly with 0 failures. `assembleDebug` succeeded producing APK.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin` (in `.worktrees/level-1-mutation-simplification/client`)
- Result: PASS
- Evidence: All real-backend and UI instrumentation test classes compiled cleanly without errors.

## Production Rollout & Canary Verification Gates

Follow this exact deployment sequence:

1. **Database Backup**:
   ```bash
   sqlite3 /var/lib/buzer/buzer.db ".backup '/var/lib/buzer/buzer.db.v6.bak'"
   ```
2. **Backend Deployment**:
   - Deploy backend commit to VPS.
   - Verify SQLite migration v7 runs cleanly.
   - Verify 1-minute poller health in stdout / Sentry logs (`todoist_operation_started`, `todoist_poll_recovered`).
3. **Android Client Deployment**:
   - Install release APK on primary device.
   - Verify cached snapshot loads instantly.
   - Verify forced refresh (`GET /tasks/snapshot?forceRefresh=true`).
   - Exercise Create, Edit, Done, Extend, Start, Postpone.
4. **Sentry Issue Resolution Gates**:
   - `BUZER-ANDROID-4`: verify journal/pending removed; recurring Done and previously stuck tasks succeed.
   - `BUZER-ANDROID-5`: verify `POST /tasks/sync` removed and forced snapshot refresh operates with 15-second timeout.
   - `BUZER-ANDROID-7`: verify pre-command sync removed and typed Todoist failure/retry behavior operates in production.
   - `BUZER-ANDROID-8`: verify operational failures emit structured warning logs and do not produce Sentry Issues; Android internal defects still create Issues.
