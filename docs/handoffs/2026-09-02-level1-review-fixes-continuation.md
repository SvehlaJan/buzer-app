# Level 1 mutation simplification review fixes — continuation handoff

Date: 2026-09-02  
Integration branch: `feature/level-1-mutation-simplification`  
Integration worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`  
Current integration HEAD: `70f6fc6` (`cleanup: remove dead MutationOutcome type`)

## Update (this continuation session)

The mutation-semantics repair's outstanding P2 lifecycle-logging finding
(below, kept for record) is fixed, independently re-reviewed (PASS), and
merged into the integration branch. Backend work for both the queue/deadline
repair and the mutation-semantics repair is now fully integrated.

**Android work for all four findings is now done, independently reviewed
(PASS), and committed directly on this integration branch** (worked in this
same worktree, not a separate branch, per explicit instruction) as `1f945cf`
(the four findings + their tests) and `70f6fc6` (dead `MutationOutcome`
type removal — the first cleanup-list item). See "Android work — done this
session" below for what changed and "Recommended next action" at the bottom
for what's still outstanding (remaining cleanup items, device-based
`androidTest`/`scripts/e2e-real-backend.sh` verification, and correcting the
2026-08-30 plan/handoff completion claims).

## Objective and product constraints

Continue the thorough repair of the second Level 1 mutation-simplification implementation. Preserve the custom mutation UI, but prefer simpler behavior and less code over UX polish. The user explicitly chose to keep the current full repair scope after considering a smaller scope.

Important settled behavior:

- A normal action button becomes immediately available again after a request completes.
- An uncertain action must not be shown as successful; the user stays informed and in control.
- Warning state does not need to persist across app restarts.
- Remove the obsolete sync endpoint; explicit refresh uses `forceRefresh=true` on the snapshot endpoint.
- Network/operational uncertainty should produce warnings, not Sentry Issues.
- Parsing, state-application, SQLite/invariant, and other internal failures should create exactly one owning Sentry Issue.
- Todoist webhooks remain backlog only.
- If Sentry MCP authentication expires, ask the user to reauthenticate. Do not bypass API access with browser automation.
- Do not close Sentry Issues until the fix is deployed and verified against production behavior.

Product/spec sources:

- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`
- `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- `docs/handoffs/2026-08-24-sync-pending-recurring-done-investigation.md`

The 2026-08-30 plan/handoff currently overstate completion. Correct their checkbox/status claims only after the verification described below actually passes.

## Repository/worktree state

Before this handoff was added, both active worktrees had clean tracked status. Git emitted only the known benign fsmonitor IPC warning.

### Integration worktree

- Path: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`
- Branch: `feature/level-1-mutation-simplification`
- HEAD: `1c1150b`
- Contains the reviewed queue/deadline repair.
- No push, deployment, production canary, or Sentry closure has occurred.

### Queue/deadline repair — complete and integrated

- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level1-fix-queue-deadline`
- Branch: `feature/level1-fix-queue-deadline`
- Final HEAD: `faa8fb2979746e6f65713766254fb58bf9547d8a`
- Commits: `f5d7cdd`, `2f9684e`, `e35ea76`, `faa8fb2`
- Merged into integration as `1c1150b`.
- Independent spec review: PASS.
- Independent code-quality review: APPROVED.

Implemented behavior:

- Fastify `request.signal` is used instead of Node's pre-aborted raw request signal.
- The 13-second total deadline covers queue admission and active work.
- Admission timeout is `backend_busy` and explicitly says nothing was sent.
- Active timeout preserves the known mutation phase: unresolved send is delivery unknown, acknowledged send is accepted/snapshot stale, explicit rejection remains rejected.
- The request returns at the deadline, but the global FIFO remains owned until late work settles; Todoist operations never overlap.
- Queue signal reaches read sync, forced refresh, polling, and mutation post-sync.
- Timer starvation is caught by rechecking elapsed time after callback settlement.
- Mutation lifecycle logging has no late contradictory success after timeout.
- Forced-refresh internal snapshot failures are captured once; operational refresh failures remain warning-only.

Verification recorded by the worker and reviewers:

- Full backend: 38 files / 456 tests PASS.
- Typecheck: PASS.
- Build: PASS.
- Focused quality-review verification: 126 tests PASS.
- `git diff --check`: PASS.

Detailed handoff: `docs/handoffs/2026-09-01-level1-queue-deadline-fix.md`.

### Mutation-semantics repair — complete and integrated

- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level1-fix-mutation-semantics`
- Branch: `feature/level1-fix-mutation-semantics`
- Final HEAD: `1449772` (`fix: pair lifecycle start/terminal logs on every mutation-runner exit`)
- Commits: `b86135c`, `603126c`, `1449772`
- Merged into integration as `d7bb8eb`.
- Independent spec review (prior session): PASS.
- Independent code-quality review (prior session): APPROVED.
- Independent read-only re-review of the lifecycle-log fix (this session): PASS.

Implemented behavior:

- Mutable task occurrence/revision and checklist-child revision are re-read inside the Todoist FIFO immediately before the first send.
- A stale root or child state sends zero Todoist commands.
- Checklist children receive the permanent root Todoist ID returned from the earlier separate `item_add` request; mapping exists only for one mutation run.
- The fake adapter no longer masks the cross-request temporary-ID defect.
- Missing, empty, or non-string permanent IDs are parse/response-shape defects; dependent children are not sent.
- Missing or malformed `sync_status` is a parse defect; recognized non-ok Todoist statuses remain explicit command rejection.
- Parse/internal preparation failures create exactly one backend Issue and return a conservative visible outcome.
- Expected escalation sender failure remains handled by `EscalationService`; unexpected DB/invariant errors propagate after the local postpone is persisted.

Changed files on the mutation branch:

- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskMutationRunner.ts`
- `backend/src/services/todoistChecklistService.ts`
- `backend/src/services/todoistCommandExecutor.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/test/fakes/e2eTodoistAdapter.ts`
- Corresponding service/adapter tests
- `docs/handoffs/2026-09-02-level1-mutation-semantics-fix.md`

Latest verification (mutation-semantics branch, HEAD `1449772`):

- Full backend: 38 files / 482 tests PASS.
- Typecheck: PASS.
- Build: PASS.
- `git diff --check`: PASS.
- Independent read-only review of the lifecycle-log fix: PASS.

### P2 logging-contract issue — fixed (kept for record)

The independent spec reviewer found one P2 logging-contract issue at mutation branch HEAD `603126c`:

- `backend/src/services/taskMutationRunner.ts` used to emit `todoist_operation_started` only after `prepareCommands` succeeds.
- Prepared-path validation conflicts and internal preparation failures therefore emitted no start record.
- Queue admission busy could emit a terminal `backend_busy` record without a matching start.
- Caller cancellation had the same pairing problem.
- The approved lifecycle contract requires exactly `[todoist_operation_started, one terminal]` for every mutation-runner invocation, including pre-send exits.

Fixed in commit `1449772`: `todoist_operation_started` is now emitted unconditionally and synchronously before `queue.run(...)` is ever called (`command_count` is the real count when `options.commands` is known up front, else the documented `0` estimate for `prepareCommands` callers, whose real count is only known once `prepareCommands` runs inside the FIFO). Every catch-block terminal branch's `if (startLogged)` guard was removed since a start is now always logged; the existing `terminalLogged` idempotency guard still prevents any double-emit. `prepareCommands()` itself still only runs inside the FIFO immediately before the first send — mutable state reads were not moved.

Added/updated RED→GREEN coverage in `backend/test/services/taskMutationRunner.test.ts` for: prepared-path validation conflict, prepared-path internal preparation defect, queue-busy admission via `prepareCommands`, and caller cancellation before the FIFO admits the operation. Two pre-existing tests that had asserted the old (buggy) "no lifecycle log on pre-send exit" behavior were rewritten to assert the correct paired behavior instead.

Merged into integration worktree as `d7bb8eb`. Full integration-branch backend verification after merge (38 files / 482 tests, typecheck, build, `git diff --check`) all PASS — see "Final verification" section below for what still needs to run against the *combined* Android+backend tree once Android work lands.

## Android work — done this session

All four original review findings are fixed, committed as `1f945cf` (`fix:
repair Android mutation warning surfaces`), and independently read-only
reviewed: **PASS on all four, no blocking defects.**

1. **Warning Refresh authoritative.** `TaskEditorUpdateScreen.kt` gained
   `onRefreshWarning: suspend () -> Unit`, a `warningRefreshInFlight` state
   field, and a `refreshWarning()` function that locks the whole surface
   (`canSubmit`/`editingEnabled` both now gate on it, not just the refresh
   button), calls the callback, and clears the warning only if it returns
   normally (keeps it on any exception, including a re-thrown
   `CancellationException`). Threaded through `EditTaskScreen.kt` → `App.kt`
   → `MainActivity.kt` (new `refreshWarningSnapshot()` →
   `coordinator.forceSyncAndReconcile()`); through `MorningReviewScreen.kt`'s
   `MorningReviewRepairEditor` → `MorningReviewActivity.kt` (new
   `refreshRepairWarningSnapshot()`); through `BlockerScreens.kt`'s
   `DeadlineBlockerUpdateEditor` → `DeadlineBlockerActivity.kt` (inline
   binding). `AddTaskScreen.kt` got the same treatment with its own local
   `warningRefreshInFlight`/`refreshWarning()`, wired to the same
   `MainActivity.refreshWarningSnapshot()`.
2. **Coordinator authoritative-snapshot discard.**
   `EnforcementCoordinator.applyAuthoritativeSnapshot()` gained an `onStale`
   callback param (default reproduces the prior hardcoded
   `Conflict("stale_snapshot", ...)` for its two other callers, unchanged).
   The operational-Warning branch of `runBackendAction` now returns whatever
   the authority check actually settled on — including
   `LocalReconciliationFailed` when local reconciliation itself throws —
   instead of always returning the raw, possibly-stale response snapshot.
   Two new regression tests in `EnforcementCoordinatorTest.kt` cover the
   stale-race and reconciliation-failure cases; both would have failed
   against the pre-fix code (independently verified by the reviewer).
3. **AddTask local-defect ownership.** New `internal fun
   isAddTaskOperationalFailure(error: Throwable): Boolean` in
   `AddTaskScreen.kt` mirrors `EnforcementCoordinator`'s
   operational/transport classification (deliberately duplicated, not
   shared, per "keep it simple"). `submitRequest()`'s catch now rethrows
   `CancellationException`, stays `AppLog.warn`-only for operational/
   transport failures, and calls `ObservabilityRuntime.instance
   .captureHandled(...)` exactly once for everything else. New
   `AddTaskOperationalFailureTest.kt` covers the classification directly.
4. **Structured Sentry log attributes.**
   `SentryAndroidObservability.addLog()` now builds
   `SentryAttribute`/`SentryAttributes` from `record.attributes` (plus
   `throwable_type`/`throwable_message`) and calls `Sentry.logger().log(level,
   SentryLogParameters.create(...), record.message)` — verified type-correct
   against the pinned `sentry-android:8.53.0` API (reviewer extracted and
   `javap`'d the actual jar). No more string-flattened attributes anywhere.

Verification this session: 414 `androidApp` unit tests + 98 `shared` tests
PASS, `:androidApp:assembleDebug` PASS, `git diff --check` PASS,
`compileDebugAndroidTestKotlin` PASS (compiles — not executed, see below).

Also landed as a separate commit `70f6fc6`: removed the dead
`MutationOutcome` type (first item on the cleanup list below), confirmed via
`rg` to have zero production/test usages beyond two unused test imports.

### Not done this session — still required

- **Device-based verification.** No Android emulator/device was attached in
  this session (`adb devices` returned empty). Neither `androidTest` nor
  `scripts/e2e-real-backend.sh` was executed. The reviewer traced the wiring
  and existing instrumented tests by reading source only; this is not a
  substitute for actually running them. This remains the design's own
  rollout gate ("Pass all backend, shared, Android, and full real-backend
  instrumentation suites") before any production canary.
- **Pre-existing stale `androidTest` expectations, confirmed by the
  reviewer (not introduced by this session's diff, but now confirmed
  actionable residue):**
  - `BlockerActivityFakeBackendTest.kt` (`morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSync`)
    still asserts a `/tasks/sync` call, which the design removed.
  - `EditTaskFakeBackendTest.kt` (`editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard`)
    clicks a node with exact text `"Retry"`; the live `SessionWarningBanner`
    only ever renders `"Refresh"`/`"Refreshing..."`/`"Open Todoist"` — this
    assertion would fail if run today.
  - `AddTaskFakeBackendTest.kt` (`addTask_backendFailureKeepsFormAndEnteredTitle`)
    expects an `AlertDialog` titled `"Task was not saved"` from
    `AddTaskFailurePresentation.kt`, which the reviewer confirmed via `rg` is
    now **dead code** (zero usages in `androidApp/src/main` outside its own
    file) — `AddTaskScreen` drives its failure UI through
    `SessionWarningBanner`/`submitDialogMessage` only.
- **Other cleanup-list items untouched:** unused mutation-screen `deviceId`
  plumbing, redundant child mutex/result state, compatibility
  constructors/aliases.

## Cleanup still pending

After behavior is correct, remove only verified residue:

- [x] Dead `MutationOutcome` type — done (`70f6fc6`).
- [ ] Fix the three stale `androidTest` expectations above (`/tasks/sync`,
      `"Retry"` button text, dead `AddTaskFailurePresentation.kt` + its own
      test file) — these need to change together with a real device run to
      confirm the corrected assertions actually pass.
- [ ] Unused mutation-screen `deviceId` plumbing; preserve device identity
      where check-in/observability still needs it.
- [ ] Redundant child mutex/result state if whole-surface locking has made
      it obsolete.
- [ ] Compatibility constructors/aliases only when all usages/tests have
      migrated.

Do not turn cleanup into a redesign. Run `rg` before every removal and keep commits reviewable.

## Final verification still required

None of the following final gates has run against the combined repaired branch:

```bash
cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/backend
npm test
npm run typecheck
npm run build

cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug

cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification
scripts/e2e-real-backend.sh
git diff --check
git status --short
```

Record exact PASS/FAIL evidence. Do not mark the production canary checkpoint complete locally. After any push, check the push-triggered `ci.yml` run before claiming completion.

## Plan/task status at handoff

- [x] Fix Fastify request cancellation and enforce the total queue/sync deadline.
- [x] Finish and integrate mutation pre-send validation, checklist parent IDs, backend internal-error ownership, and the lifecycle-log start/terminal pairing fix.
- [x] Fix Android warning refresh, authority propagation, local error ownership, and structured Sentry attributes. (`1f945cf`, independently reviewed PASS.)
- [ ] Remove simplification residue and migrate stale instrumentation. (`MutationOutcome` done; `deviceId` plumbing, child mutex, compatibility aliases, and the three stale `androidTest` files remain — see "Cleanup still pending".)
- [ ] Run combined backend/JVM/build/full E2E verification. (Backend and Android JVM/build gates all pass; device-based `androidTest`/`scripts/e2e-real-backend.sh` has not run — no emulator was attached this session.)
- [ ] Run final read-only review and correct plan/handoff completion claims.

## Manual verification, skipped checks, and assumptions

- No Android manual verification or emulator E2E was performed in this continuation session (`adb devices` returned no attached device).
- No production Todoist mutation, deployment, Sentry canary, or Sentry Issue closure was performed.
- CI was not checked because no branch was pushed.
- Backend work relied on unit/integration tests and real TCP HTTP regression coverage; no live Todoist destructive test was used.
- The Android fix's independent review verified wiring and Sentry SDK API correctness by reading source and the pinned SDK jar, and ran the full JVM/build gate — it did not and could not run the app on a device.
- Assumption: the existing Level 1 product decisions remain authoritative; the user explicitly said to keep the current repair scope.

## Recommended next action

Both backend repairs and all four Android mutation-warning-surface findings are now integrated on `feature/level-1-mutation-simplification` at `70f6fc6`, in this same worktree (no separate Android worktree/branch was used, per instruction), and fully JVM/build-verified. What's left:

1. Fix the three stale `androidTest` files identified above (`/tasks/sync` assertion in `BlockerActivityFakeBackendTest.kt`, `"Retry"` button text in `EditTaskFakeBackendTest.kt`, and the dead `AddTaskFailurePresentation.kt` + its own test referenced from `AddTaskFakeBackendTest.kt`) together with the remaining cleanup-list items (`deviceId` plumbing, child mutex state, compatibility aliases) — verify each removal with `rg` first, keep commits reviewable.
2. Get an Android emulator or device attached and run:
   ```bash
   cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/client
   ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug

   cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification
   scripts/e2e-real-backend.sh
   git diff --check
   git status --short
   ```
   Record exact PASS/FAIL evidence. Do not mark the production canary checkpoint complete locally. After any push, check the push-triggered `ci.yml` run before claiming completion.
3. Only after both of those pass, run a final read-only review of the whole combined branch and correct the 2026-08-30 plan/handoff completion claims.
