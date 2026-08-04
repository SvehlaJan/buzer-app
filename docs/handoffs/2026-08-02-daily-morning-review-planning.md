# Daily Morning Review Planning Handoff

Date: 2026-08-02
Branch/worktree: `feature/daily-morning-review-plan` / `/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review-plan`
Base: `feature/smart-home` at `6255730`

## Task and status

Prepared the self-contained implementation plan for accepted Change 2, Daily Morning Review. Implementation is intentionally gated on review/integration of Change 1.

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Status:

- [x] Created a dedicated planning worktree after verifying `.worktrees/` is ignored.
- [x] Read the working agreement, base product spec, accepted 2026-08-02 design, workspace guidance, relevant recent handoffs, and current Change 1 plan/handoff.
- [x] Inspected the current Morning Review activity/screen/tests, alarm scheduler/receiver, boot reconciliation, manifest, snapshot/coordinator paths, and real-backend instrumentation.
- [x] Inspected the older unmerged overdue-review design and documented the newer non-blocking rule that supersedes it.
- [x] Researched AlarmManager/AndroidX Activity behavior through Context7.
- [x] Established a clean backend/client JVM baseline.
- [x] Wrote and self-reviewed the Change 2 plan for schedule, local state, flow, Home/catch-up entry, priority, accessibility, instrumentation, and final verification.
- [ ] Runtime implementation has not started.

## Files changed

- `docs/superpowers/plans/2026-08-02-daily-morning-review.md` — new Change 2 implementation plan.
- `docs/handoffs/2026-08-02-daily-morning-review-planning.md` — this planning handoff.

No production or test source changed. `npm ci` created ignored `backend/node_modules` in the planning worktree only.

## Key planning decisions

- Android local date/state is authoritative for acknowledgement and Snooze; no new backend persistence/API is added.
- The Android client stops calling `/review/dismiss`; the backend compatibility route remains for older installed APKs.
- A typed local state records last acknowledged date, pending Snooze, and the sole scheduled Morning Review identity.
- The scheduler uses one-shot `setAlarmClock`, one alarm at a time, and replaces rather than stacks daily/Snooze/priority-retry alarms.
- Home/app-open availability is pure and includes a foreground reevaluation boundary for midnight, 08:00, and Snooze expiry.
- Automated entry is a hard blocker; Home entry permits Back. Both preserve the underlying MainActivity editor instance through an Activity Result contract.
- Invalid/pending owner tasks block Start; overdue remains actionable and non-blocking; unassigned tasks never enter review.
- Deadline/Start task blockers defer Morning Review with a one-minute internal priority retry.
- Home and Morning Review share one extracted owner agenda selector after Change 1 is integrated.
- Change 3 readiness gating is excluded.

## Verification

Verification:
- Command: `git check-ignore -v .worktrees`
- Result: PASS
- Evidence: `.gitignore:2:.worktrees/` confirms project-local worktrees are ignored.

Verification:
- Command: `npm ci`
- Result: PASS
- Evidence: 343 packages installed. npm warned that the project requests Node 22 while the current runtime is Node 26 and reported existing audit findings; installation completed successfully.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 32 Vitest files and 387 tests passed.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 33s`; only pre-existing unnecessary-cast warnings appeared in `EnforcementCoordinatorTest.kt`.

Verification:
- Command: Context7 library/docs queries for Android AlarmManager and AndroidX Activity
- Result: PASS
- Evidence: confirmed one-shot alarm-clock APIs, AlarmManager scheduling guidance, Activity Result saved-state integration, and ComponentActivity back/result support.

Verification:
- Command: `rg`/`sed` source mapping across Morning Review, alarms, receivers, manifest, Home/Change 1 dependencies, and E2E tests
- Result: PASS
- Evidence: every source/test path named in the plan exists now or is explicitly created by a plan task; removed fake-backend tooling is not used.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before adding this handoff.

Verification:
- Command: Android instrumentation and `scripts/e2e-real-backend.sh`
- Result: SKIPPED
- Evidence: documentation-only planning session. The plan mandates focused instrumentation plus the full unfiltered real-backend suite during implementation.

## Assumptions

- Change 1 will be accepted and integrated before Change 2 implementation starts.
- Change 1 provides `forceSyncAndReconcile()` and the pure complete Home classifier described by its accepted plan.
- The backend `/review/dismiss` route remains temporarily for compatibility but is no longer product authority.
- Android exact-alarm/full-screen readiness is handled by accepted Change 3; Change 2 still schedules through existing platform capabilities.

## Known failures or skipped checks

- No planning failure.
- Android runtime/instrumentation was skipped because no runtime files changed.
- CI was not triggered because this planning branch was not pushed.
- The informal `.agents/tasks` manifest was not created because `.agents/` is not ignored in this repository; the branch/worktree and owned documentation files are recorded here instead.

## Next recommended step

Wait for Change 1's implementation handoff and acceptance review. Then integrate this plan, create a fresh Change 2 implementation worktree from the accepted Change 1 branch, and execute `docs/superpowers/plans/2026-08-02-daily-morning-review.md` task-by-task. Do not merge or cherry-pick the older `feature/overdue-morning-review` behavior.
