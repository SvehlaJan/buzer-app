# Level 1 mutation simplification review fixes — continuation handoff

Date: 2026-09-02  
Integration branch: `feature/level-1-mutation-simplification`  
Integration worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`  
Current integration HEAD: `b43777f` (`fix: repair stale androidTest suite and a retry-masking bug it found`)

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
`compileDebugAndroidTestKotlin` PASS (compiles — not yet executed at that
point; see the device-verification pass below, done later the same session).

Also landed as a separate commit `70f6fc6`: removed the dead
`MutationOutcome` type (first item on the cleanup list below), confirmed via
`rg` to have zero production/test usages beyond two unused test imports.

### Device-based verification — done later this session (`b43777f`)

An Android emulator turned out to be available on this machine
(`android emulator start Buzer_API_34`, `ANDROID_SERIAL=emulator-5554`) with
two pre-provisioned AVDs (`Buzer_API_34`, `Buzer_API_36`); `scripts/
e2e-real-backend.sh`'s own e2e backend (`backend/test/e2eServer.ts`) was
started directly for iteration speed. All three stale `androidTest` files
flagged above were fixed **and verified by actually running them**, not just
read:

- `BlockerActivityFakeBackendTest.kt`: 43/43. Fixed `/tasks/sync` →
  `/tasks/snapshot` everywhere (12 occurrences, ~7 tests — the reviewer's
  spot-check had only caught one). Fixed the "Action failed"/`"Retry"`
  dialog pattern in 4 tests (`startBlocker_backendFailureShows...`,
  `deadlineBlocker_backendFailureShows...`,
  `deadlineBlocker_rescheduleFailureUsesUpdateAction`,
  `morningReview_repairBackendFailureShowsErrorAndStaysOpen`) — confirmed
  `runActionOnce`/`EnforcementCoordinator` route every failure outcome
  through `warningMessage` now, so the dialog path is genuinely dead code —
  rewrote them to assert the `session-warning-banner` tag instead, renamed
  the two misleadingly-named tests. Renamed
  `morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSync` →
  `...TasksSnapshot` and fixed its `"Retry refresh"` text assertion the same
  way.
  - This required a **backend fix**, not just a Kotlin one:
    `backend/test/e2eServer.ts` never recorded `GET` requests at all (only
    `POST /tasks|/review`), so no instrumented assertion could ever observe
    Android's `forceRefresh=true` calls no matter how the Kotlin was
    rewritten. Now records `GET /tasks/snapshot?forceRefresh=true`
    specifically (the routine unforced read stays unrecorded, so it doesn't
    pollute the fixed `toHaveLength(0)`-style backend tests) — verified via
    `npm test` (482 backend tests, including the 27 in `e2eServer.test.ts`).
  - Also fixed, in the same file: `ACTION_METADATA_KEYS`'s body extraction
    only ever checked top-level keys, but `expectedTaskRevision` has lived
    nested under `rootPatch` since well before Level 1 (July 2026) — added a
    `rootPatch` fallback. This is what actually made
    `deadlineBlocker_rescheduleUsesSharedUpdateUi` and
    `morningReview_repairCallsFakeBackend` pass; a genuinely pre-existing,
    unrelated gap, confirmed via `git log -S`.
- `EditTaskFakeBackendTest.kt`: 2/2. Rewrote
  `editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard`:
  no more `ErrorAlertDialog`/`"Retry"`/`"Dismiss"` — a failed update shows
  `session-warning-banner`; retry means pressing the primary Save button
  again (same as `UnifiedTaskEditorRealBackendTest`'s established pattern),
  which must resend the identical captured request.
- `AddTaskFakeBackendTest.kt`: 11/11.
  `addTask_backendFailureKeepsFormAndEnteredTitle` now asserts
  `session-warning-banner` + the preserved title instead of the dead
  `AddTaskFailurePresentation` dialog; that presentation class and its own
  JVM test were deleted (confirmed zero remaining usages).
  - Investigating this file on-device surfaced a **real, unrelated
    pre-existing bug**, found and fixed in the same pass:
    `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`
    expected the Add screen to stay open with the failed checklist draft
    visible. Logcat showed the create actually succeeding outright
    (`add_task create_success`, task count incremented). Root cause:
    `fakeBackend.setFailNextChildAdd(true)` makes the fake Todoist adapter
    throw a plain `Error` (not a `TodoistOperationError`) for one child
    `item_add`; `TodoistCommandExecutor.executeCommand` classifies that as
    `kind: "transport"`, which is retry-eligible — so the SAME backend
    request retries once (same UUID, per the executor's own contract) and
    the retry succeeds, transparently, inside that one HTTP call. The test's
    "stay open, show the failed draft" premise predates that retry-once
    contract and no longer matches reality. Renamed to
    `addTask_transientChildAddFailureRetriesOnceInsideTheSameRequestAndSucceeds`
    and rewrote it to assert what actually happens now: exactly one
    Android-initiated HTTP request, transparent recovery, normal success.
- Also picked up incidentally by package-level targeting and unaffected by
  anything above: `RealBackendScenarioTest.kt` 7/7.
- **All 63 tests, run together in one pass**: 63/63, `BUILD SUCCESSFUL`.

Everything above is one commit, `b43777f`.

## Cleanup still pending

After behavior is correct, remove only verified residue:

- [x] Dead `MutationOutcome` type — done (`70f6fc6`).
- [x] Stale `androidTest` expectations (`/tasks/sync`, `"Retry"` dialog
      pattern, dead `AddTaskFailurePresentation.kt` + its own test) — done
      and device-verified (`b43777f`).
- [ ] Unused mutation-screen `deviceId` plumbing; preserve device identity
      where check-in/observability still needs it. Confirmed so far (this
      session, not yet acted on): `updateRequest(...)`'s `deviceId`
      parameter (threaded from `MainActivity` → `App.kt` →
      `EditTaskScreen`/`MorningReviewScreen`/`BlockerScreens`) is accepted
      but never read inside the function body — genuinely dead wire
      threading. `AddTaskScreen.kt`'s own local `deviceId` val is computed
      but never referenced at all. `EnforcementDependencies.deviceIdentityStore`
      itself and `DiagnosticCorrelation.deviceId` (the legitimate
      observability channel, currently populated by zero call sites) should
      stay untouched — only the confirmed-dead mutation-screen threading
      should go.
- [ ] Redundant child mutex/result state if whole-surface locking has made
      it obsolete. Not yet investigated this session — `EnforcementCoordinator`'s
      `childActionMutex`/`inFlightChildren` (used by `runFocusedChildAction`)
      is the candidate; verify it isn't protecting a real race the UI-level
      `enabled` state can't fully prevent before touching it.
- [ ] Compatibility constructors/aliases only when all usages/tests have
      migrated.

Do not turn cleanup into a redesign. Run `rg` before every removal and keep commits reviewable.

## Final verification — status

Run this session, against the combined repaired branch at `b43777f`:

```bash
cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/backend
npm test            # 38 files / 482 tests PASS
npm run typecheck   # PASS
npm run build       # PASS

cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
# PASS (98 shared tests, 414 androidApp tests, assembleDebug succeeds)

# Emulator (Buzer_API_34, started this session via `android emulator start`) +
# backend/test/e2eServer.ts started directly (not through the wrapper script):
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.buzer.android.blockers,app.buzer.android.addtask \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
# 63/63 PASS (BlockerActivityFakeBackendTest 43, EditTaskFakeBackendTest 2,
# AddTaskFakeBackendTest 11, RealBackendScenarioTest 7)

git diff --check     # PASS
git status --short   # clean
```

**Not run this session, and still required before any production canary:**
`scripts/e2e-real-backend.sh` itself (the wrapper script) was not used —
its underlying pieces (emulator, e2e backend, `connectedDebugAndroidTest`,
the same gradle properties) were run manually/directly instead, for faster
iteration on the `BlockerActivityFakeBackendTest`/`EditTaskFakeBackendTest`/
`AddTaskFakeBackendTest` packages only. The **other** `androidTest` classes
(`readiness/`, `taskeditor/`, `ui/screens/*HostTest`, `observability/`, the
`UnifiedTaskEditorRealBackendTest`, etc.) have not been run this session at
all — only compiled. Do not assume they pass; run the full suite (the
wrapper script, or `connectedDebugAndroidTest` with no class/package filter)
before claiming the design's full rollout gate ("Pass all backend, shared,
Android, and full real-backend instrumentation suites") is met.

The `Buzer_API_34` emulator and a manually-started `e2eServer.ts` process
(port 18433) were left running at the end of this session for continued
iteration — stop them if picking this up later in a fresh state, or reuse
them directly.

## Plan/task status at handoff

- [x] Fix Fastify request cancellation and enforce the total queue/sync deadline.
- [x] Finish and integrate mutation pre-send validation, checklist parent IDs, backend internal-error ownership, and the lifecycle-log start/terminal pairing fix.
- [x] Fix Android warning refresh, authority propagation, local error ownership, and structured Sentry attributes. (`1f945cf`, independently reviewed PASS.)
- [ ] Remove simplification residue and migrate stale instrumentation. (`MutationOutcome` and the three stale `androidTest` files done and device-verified; `deviceId` plumbing, child mutex, and compatibility aliases remain — see "Cleanup still pending".)
- [ ] Run combined backend/JVM/build/full E2E verification. (Backend, Android JVM/build, and the three targeted `androidTest` packages — 63/63 — all pass, verified on a real emulator this session. The rest of the `androidTest` suite and the actual `scripts/e2e-real-backend.sh` wrapper have not been run this session.)
- [ ] Run final read-only review and correct plan/handoff completion claims.

## Manual verification, skipped checks, and assumptions

- Android device verification this session used a local emulator
  (`Buzer_API_34`) and a directly-started `e2eServer.ts` process, not a
  physical device and not the `scripts/e2e-real-backend.sh` wrapper itself —
  see "Final verification — status" above for exactly what ran and what
  didn't.
- No production Todoist mutation, deployment, Sentry canary, or Sentry Issue closure was performed.
- CI was not checked because no branch was pushed.
- Backend work relied on unit/integration tests and real TCP HTTP regression coverage; no live Todoist destructive test was used.
- The Android mutation-warning-surface fix's independent review (earlier in this session) verified wiring and Sentry SDK API correctness by reading source and the pinned SDK jar, and ran the full JVM/build gate, before any device became available — it explicitly did not run the app on a device; that gap was closed later this same session by the androidTest work above.
- Assumption: the existing Level 1 product decisions remain authoritative; the user explicitly said to keep the current repair scope.

## Recommended next action

Both backend repairs, all four Android mutation-warning-surface findings, and the three originally-flagged stale `androidTest` files (plus a real bug they surfaced) are now integrated on `feature/level-1-mutation-simplification` at `b43777f`, in this same worktree, and verified — including on a real emulator against a real e2e backend, not just compiled. What's left:

1. Finish the remaining cleanup-list items: unused mutation-screen `deviceId` plumbing, the `EnforcementCoordinator` child-mutex redundancy question, and compatibility constructors/aliases — see "Cleanup still pending" above for what's already been scoped out this session versus not yet investigated.
2. Run the **rest** of the `androidTest` suite (not just the 3 packages covered this session) — either the full `scripts/e2e-real-backend.sh` wrapper, or `connectedDebugAndroidTest` with no class/package filter, against the `Buzer_API_34` emulator (already running) or a fresh one.
3. Only after that, run a final read-only review of the whole combined branch and correct the 2026-08-30 plan/handoff completion claims. Do not mark the production canary checkpoint complete locally. After any push, check the push-triggered `ci.yml` run before claiming completion.
3. Only after both of those pass, run a final read-only review of the whole combined branch and correct the 2026-08-30 plan/handoff completion claims.
