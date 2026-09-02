# Level 1 mutation simplification review fixes — continuation handoff

Date: 2026-09-02  
Integration branch: `feature/level-1-mutation-simplification`  
Integration worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`  
Current integration HEAD: `d7bb8eb` (`merge: integrate mutation semantics repair`)

## Update (this continuation session)

The mutation-semantics repair's outstanding P2 lifecycle-logging finding
(below, kept for record) is fixed, independently re-reviewed (PASS), and
merged into the integration branch. Backend work for both the queue/deadline
repair and the mutation-semantics repair is now fully integrated. **Android
work has not started.** See "Recommended next action" at the bottom.

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

## Android work not yet started

The following original review findings remain untouched:

1. Warning Refresh on Add/Edit/Repair surfaces invokes ordinary project refresh rather than authoritative `forceRefresh=true`, and does not reliably clear the warning only after successful reconciliation.
2. The mutation coordinator obtains an authoritative warning snapshot but discards it; callers can reapply the stale original snapshot.
3. Local Android parsing, snapshot-application, and state-update defects do not consistently create exactly one owning Sentry Issue. AddTask currently logs locally instead of capturing its owned internal failures.
4. Android Sentry log attributes are flattened into message text rather than sent as structured searchable attributes.

Keep the Android implementation simple:

- One explicit forced-snapshot refresh callback for warning state.
- Preserve the draft/screen while refreshing.
- Clear the warning only after authoritative refresh and successful local application.
- Lock the entire mutation surface while refresh/action is in flight; the normal action becomes available again after completion.
- Return or apply the coordinator's authoritative result exactly once; do not let callers reapply stale input.
- Operational/network/backend-owned failures remain warning-only on Android.
- Local parse/apply/invariant failures are captured once by the local owner.
- Use Sentry's structured log parameter API rather than interpolating attributes into the message.

Android test requirements:

- Update shared JVM tests for warning refresh, authority propagation, and error ownership.
- Update `client/androidApp/src/androidTest` tests that still expect old Retry/dialog behavior or `/tasks/sync`.
- UI behavior changes require `scripts/e2e-real-backend.sh`; JVM tests alone are insufficient.

## Cleanup still pending

After behavior is correct, remove only verified residue:

- Dead `MutationOutcome` type.
- Unused mutation-screen `deviceId` plumbing; preserve device identity where check-in/observability still needs it.
- Redundant child mutex/result state if whole-surface locking has made it obsolete.
- Compatibility constructors/aliases only when all usages/tests have migrated.
- Stale instrumentation expectations for removed `/tasks/sync`, Retry buttons, and old dialogs.

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
- [ ] Fix Android warning refresh, authority propagation, local error ownership, and structured Sentry attributes.
- [ ] Remove simplification residue and migrate stale instrumentation.
- [ ] Run combined backend/JVM/build/full E2E verification.
- [ ] Run final read-only review and correct plan/handoff completion claims.

## Manual verification, skipped checks, and assumptions

- No Android manual verification or emulator E2E was performed in this continuation session.
- No production Todoist mutation, deployment, Sentry canary, or Sentry Issue closure was performed.
- CI was not checked because no branch was pushed.
- Backend work relied on unit/integration tests and real TCP HTTP regression coverage; no live Todoist destructive test was used.
- Assumption: the existing Level 1 product decisions remain authoritative; the user explicitly said to keep the current repair scope.

## Recommended next action

Both backend repairs (queue/deadline, mutation-semantics) are now integrated at `d7bb8eb` and fully backend-verified. Start the Android behavior repair next, in a fresh dedicated worktree branched from the updated integration branch (`feature/level-1-mutation-simplification` at `d7bb8eb`):

```bash
cd /Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification
git worktree add ../level1-fix-android-mutation-ui -b feature/level1-fix-android-mutation-ui
```

Then implement the four untouched Android findings and requirements listed under "Android work not yet started" above: forced-snapshot warning refresh, authoritative-result propagation (no stale-input reapply), single local-defect ownership (fix `AddTask`'s local-only logging), and Sentry structured log attributes (not interpolated into message text). Update shared JVM tests and the `androidTest` suite per "Android test requirements" above; UI behavior changes still need `scripts/e2e-real-backend.sh`, not just JVM tests. After Android behavior is correct, do the "Cleanup still pending" pass (dead `MutationOutcome` type, unused `deviceId` plumbing, redundant child mutex state, stale `/tasks/sync`/Retry-button instrumentation) — verify each removal with `rg` first. Finish with the full "Final verification still required" gate list against the combined branch before correcting the 2026-08-30 plan/handoff completion claims.
