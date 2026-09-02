# Level 1 mutation simplification review fixes — continuation handoff

Date: 2026-09-02  
Integration branch: `feature/level-1-mutation-simplification`  
Integration worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`  
Current integration HEAD: `59ed5c4` (`cleanup: remove context locals left dead by the deviceId removal`)

## Update (this continuation session) — session complete

Everything the previous handoff called out as outstanding is now done,
independently reviewed, and device-verified. Commit sequence this session
(all on this same integration branch, in this same worktree — no separate
Android branch was used, per explicit instruction):

1. `1449772` — backend lifecycle-log start/terminal pairing fix. Independent
   review: PASS. Merged as `d7bb8eb`.
2. `1f945cf` — all four Android mutation-warning-surface findings (warning
   refresh authority, coordinator snapshot discard, AddTask error ownership,
   structured Sentry attributes). Independent review: PASS.
3. `70f6fc6` — dead `MutationOutcome` type removed (cleanup item 1).
4. `b43777f` — the three originally-flagged stale `androidTest` files fixed
   *and device-verified* (not just compiled), plus the backend
   `e2eServer.ts` fixes this required, plus a real retry-masking product bug
   found and fixed along the way. Independent review: PASS, two minor
   non-blocking findings, both addressed in commits 6-7 below.
5. `6c9cb48` — dead `deviceId` wire-threading removed (cleanup item 2);
   `EnforcementCoordinator`'s child mutex investigated and confirmed *not*
   redundant, left alone; `TaskMutationRunner`'s compatibility constructor
   simplified (cleanup item 3, backend). Independent review: PASS.
6. `6ab00b7` — corrected the 2026-08-30 plan and implementation handoff's
   overstated Checkpoint E / "all tasks complete" claims.
7. `59ed5c4` — removed two dead `context` locals the review in step 4/5
   flagged (Kotlin only warns on unused locals, so these survived
   compilation; not a functional issue, just residue).

Also done this session, beyond the three flagged files: **the entire
`androidTest` suite — 115 tests across every instrumentation class in the
project — was run (not just compiled) on a real Android emulator against a
real `backend/test/e2eServer.ts` process: 115/115 PASS.** This is the first
time in this whole initiative that real-backend Android instrumentation was
actually executed rather than only compiled; see "Final verification —
status" below.

One item flagged by the final independent review, worth surfacing rather
than silently fixing: `b43777f` broadened `AddTaskScreen.kt`'s
`isAddTaskOperationalFailure` from "`BuzerApiException` with a known
operational code or 4xx status" to "any `BuzerApiException`" — this was a
deliberate correctness fix (it now matches `BlockerErrorReporter`'s own
`is BuzerApiException -> return false` guard, so AddTask no longer creates a
spurious local Sentry Issue for a backend-owned non-4xx failure the backend
already reports itself), covered by `AddTaskOperationalFailureTest.kt`, but
the commit message's bullet points focused on the dialog→banner UI change
and didn't call this out explicitly. Recorded here for the record; not
re-litigated or amended into the original commit.

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
- [x] Unused mutation-screen `deviceId` plumbing — done (`6c9cb48`,
      dead-local follow-up `59ed5c4`). Removed `updateRequest(...)`'s
      `deviceId` parameter (never read inside the function body — the wire
      request never carried it) and the dead threading feeding it
      (`EditTaskScreen`'s own param, `MorningReviewScreen`'s/`BlockerScreens`'
      local vals, `AddTaskScreen`'s unused local val). Left `App.kt`'s own
      `deviceId` param and its `deviceId.isBlank()` Edit-readiness gate
      alone (a separate, legitimate use), and left
      `EnforcementDependencies.deviceIdentityStore`/`DiagnosticCorrelation.deviceId`
      (the real observability channel) untouched.
- [x] Child mutex/result state — investigated (`6c9cb48`), confirmed **not**
      redundant, deliberately left alone. `EnforcementCoordinator`'s
      `childActionMutex`/`inFlightChildren` is the only protection against a
      rapid double-tap on the same Home-screen checklist item —
      `MainActivity.toggleCurrentChild` never sets any UI-level in-flight
      state of its own (the pre-existing `state.interactionInFlight` guard
      on `HomeScreen`'s checklist toggle is driven by unrelated actions),
      and nothing in this session's whole-surface-locking work touches that
      code path at all.
- [x] Compatibility constructors/aliases — done (`6c9cb48`, backend).
      `TaskMutationRunner`'s dual-mode constructor (positional args or a
      deps object) simplified to deps-object-only after confirming via grep
      that all 27 call sites (2 production, 25 test) already used the
      object form, and via `tsc --noEmit` after (would fail on any
      remaining positional-style caller). A broader sweep for the same
      pattern elsewhere in the backend found no other instance. No
      equivalent pattern found on the Android side.

Every cleanup-list item is now closed.

## Final verification — status

Run this session, against the combined repaired branch (final HEAD `59ed5c4`):

```bash
cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/backend
npm test            # 38 files / 482 tests PASS
npm run typecheck   # PASS
npm run build       # PASS

cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification/client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
# PASS (98 shared tests, 413-414 androidApp tests depending on exact count
# taken, assembleDebug succeeds)

# Emulator (Buzer_API_34, started this session via `android emulator start`) +
# backend/test/e2eServer.ts started directly (not through the wrapper script):
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
# Run with NO class/package filter -- the entire androidTest suite:
# 115/115 PASS. (An earlier, narrower run targeting just
# app.buzer.android.blockers + app.buzer.android.addtask had already gone
# 63/63; the unfiltered run supersedes and includes it.)

git diff --check     # PASS
git status --short   # clean
```

**`scripts/e2e-real-backend.sh` itself (the wrapper script) was still not
used** — its underlying pieces (emulator, e2e backend,
`connectedDebugAndroidTest`, the same gradle properties) were run
manually/directly instead, for faster control over the e2e server process
across multiple iterations. Functionally equivalent to running the wrapper
script (same task, same properties, same effect), but if the wrapper script
itself has ever drifted from what it invokes, that drift wouldn't have been
caught. Worth running the actual wrapper once before a production canary,
as a final sanity check on the script itself rather than its pieces.

The design's full rollout gate ("Pass all backend, shared, Android, and
full real-backend instrumentation suites") is now met for the first time in
this initiative.

The `Buzer_API_34` emulator and a manually-started `e2eServer.ts` process
(port 18433) were left running at the end of this session for continued
iteration — stop them if picking this up later in a fresh state, or reuse
them directly.

## Plan/task status at handoff

- [x] Fix Fastify request cancellation and enforce the total queue/sync deadline.
- [x] Finish and integrate mutation pre-send validation, checklist parent IDs, backend internal-error ownership, and the lifecycle-log start/terminal pairing fix.
- [x] Fix Android warning refresh, authority propagation, local error ownership, and structured Sentry attributes. (`1f945cf`, independently reviewed PASS.)
- [x] Remove simplification residue and migrate stale instrumentation. All four cleanup-list items closed (`70f6fc6`, `b43777f`, `6c9cb48`, `59ed5c4`) — see "Cleanup still pending" above.
- [x] Run combined backend/JVM/build/full E2E verification. Backend (482 tests), client JVM (shared 98 + androidApp ~414), `assembleDebug`, and the **complete, unfiltered** `androidTest` suite (115/115) all PASS, verified on a real emulator against a real e2e backend this session.
- [x] Run final read-only review and correct plan/handoff completion claims. Independent review of the two commits that hadn't had one yet: PASS. The 2026-08-30 plan and implementation handoff's overstated Checkpoint E / "all tasks complete" claims corrected in `6ab00b7`.

Every item on this handoff's own checklist is now done. What remains is
entirely outside this handoff's scope: **Checkpoint E / Task 16 — production
deployment, canary, and Sentry Issue closure** — see "Recommended next
action" below.

## Manual verification, skipped checks, and assumptions

- Android device verification this session used a local emulator
  (`Buzer_API_34`) and a directly-started `e2eServer.ts` process, not a
  physical device and not the `scripts/e2e-real-backend.sh` wrapper script
  itself (its underlying pieces were run directly instead) — see "Final
  verification — status" above.
- No production Todoist mutation, deployment, Sentry canary, or Sentry Issue closure was performed.
- CI was not checked because no branch was pushed.
- Backend work relied on unit/integration tests and real TCP HTTP regression coverage; no live Todoist destructive test was used.
- The Android mutation-warning-surface fix's independent review (earlier in this session) verified wiring and Sentry SDK API correctness by reading source and the pinned SDK jar, and ran the full JVM/build gate, before any device became available — it explicitly did not run the app on a device; that gap was closed later this same session by the androidTest work.
- Assumption: the existing Level 1 product decisions remain authoritative; the user explicitly said to keep the current repair scope.

## Recommended next action

All backend and Android repair work, all cleanup, and full verification
(backend + client JVM + the complete real-backend `androidTest` suite,
115/115 on a real emulator) are done and integrated on
`feature/level-1-mutation-simplification` at `59ed5c4`, in this same
worktree. The only thing left in the entire Level 1 mutation-simplification
initiative is **Checkpoint E / Task 16 from the plan** — production rollout:

1. Back up and verify production SQLite (`.backup` before deploying).
2. Deploy the backend commit; verify the v7 schema migration ran cleanly and
   the one-minute poller is healthy in production logs.
3. Install the matching Android release build on a primary device.
4. Run production canaries for every action (Create, Edit, checklist,
   Extend, one-time Done, recurring Done, Start, Postpone) and each warning
   family (busy-before-send, rejection, delivery-unknown, accepted-stale,
   forced-refresh failure), confirming normal controls reappear immediately
   after every outcome and nothing destructive double-fires.
5. Verify structured logs and component-owned internal Issues appear
   correctly in Sentry for real production traffic.
6. Only after each individual gate passes, resolve the four Sentry Issues
   through Sentry MCP (`BUZER-ANDROID-4`, `-5`, `-7`, `-8`) per their
   specific verification criteria in the design doc. If Sentry MCP
   authentication is unavailable, ask the user to reauthenticate — do not
   bypass API access with browser automation.

Do not mark this checkpoint complete locally without having actually done
the deployment and canary. If a branch is ever pushed for this work, check
the push-triggered `ci.yml` run before claiming CI passes — it has not been
checked this entire initiative because no branch has been pushed yet.

Housekeeping: the `Buzer_API_34` emulator and a manually-started
`e2eServer.ts` process (port 18433) were left running at the end of this
session — stop them (`android emulator stop Buzer_API_34`; kill the
`e2eServer.ts` node process) if picking this up later in a clean state, or
reuse them directly for the Android release-build canary above.
