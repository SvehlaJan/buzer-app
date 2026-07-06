# Handoff: Alarm Reliability And Todoist-Native Semantics

Date: 2026-07-06
Agent: Claude (Sonnet 5 workers under a Fable 5 controller, subagent-driven)
Plan: `docs/superpowers/plans/2026-07-05-alarm-reliability-and-todoist-semantics.md`

## Scope

- Execute the full plan: Todoist-native due semantics (due = start of block, enforcement
  deadline = due + duration), `setAlarmClock` delivery for blocker alarms, keyguard-aware
  overlay skip, watcher backoff, start-time UI language with default-now picker and
  "Ends HH:MM" readout, title sentence capitalization, fixture/instrumentation migration,
  and the owner-gated on-device verification.

## Completed

All 9 tasks. Commits (merged fast-forward to master as `022ff35`, branch deleted):
- `b2dc215` docs: decide todoist-native due semantics and alarm-clock delivery
- `568241b` feat: adopt todoist-native due semantics in backend
- `c874216` test: consolidate time-domain tests and clarify start comment (review fix)
- `5bba68d` feat: schedule deadline blocker at due plus duration
- `1d54485` test: pin deadline alarm for in-progress block (review fix)
- `6e1d4fc` feat: deliver blocker alarms via setAlarmClock (also migrated 3
  EnforcementCoordinatorTest deadline expectations broken cross-module by 5bba68d)
- `43d95c4` fix: skip keyguard-invisible overlay and back off watcher
- `572a654` feat: start-time picker language with computed end readout
- `022ff35` test: migrate fixtures and e2e tests to start semantics (also migrated two
  "Deadline is required" assertions the plan's sweep tables missed)

Every implementation task passed spec-compliance and quality review (two-stage; combined
into one reviewer per task from Task 3 on). Review-driven fixes are the two `test:` commits
above.

## Verification

- Command: `cd backend && npm test && npm run typecheck && npm run build`
- Result: PASS — 16 files / 60 tests. (One environment repair en route: Homebrew moved
  Node from v26 to v24 mid-session, breaking better-sqlite3's native ABI; fixed with
  `npm rebuild better-sqlite3`. Not a code issue.)

- Command: `cd tools/fake-backend && npm test`
- Result: PASS — 15 tests.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS (exit-code confirmed).

- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS — `Finished 21 tests on pixel8Api34`, 0 failed. (First from-clean attempt
  failed with INSTALL_PARSE_FAILED_NOT_APK — a self-inflicted race: a concurrent gradle
  build rewrote the APK during the lane's install. Re-run exclusively: green.)

- Command: CI `ci.yml` on the branch (run `28770677246`) and on master post-merge
- Result: PASS both.

- On-device (owner's Pixel 10, locked, real VPS backend), 2026-07-06:
  - Alarm scheduled 17:57:00.000 delivered **17:57:00.130** (130 ms; the 2026-07-05
    baseline for the identical scenario was 4-9+ minutes late). `dumpsys alarm` showed the
    alarm as the device's "Next wake from idle" with the setAlarmClock wake-from-idle flag.
  - `overlay_start skipped reason=keyguard_locked` logged; `deadline_blocker show` at
    17:57:00.430 — the full-screen blocker rendered over the lock screen.
  - Owner tapped Done: `COMPLETE_TASK` → VPS 200 → `action success` (full loop).
  - Owner confirmed Home shows the in-progress task; Todoist calendar rendering follows
    from the adopted native semantics (block = due → due+duration); no objection raised.

## Decisions And Assumptions

- All recorded in `docs/decisions/2026-07-05-todoist-native-due-semantics.md`.
- Friction log items 1, 2, 4, 5, 6 closed by this change
  (`docs/notes/2026-07-05-usage-friction.md`).

## Open Issues

- Friction item 7 (found during the device pass): a task whose start is already in the
  past when reconcile runs — the common case now that the picker defaults to now — gets NO
  start blocker and skips the whole start-commitment flow (never `started`, escalation
  unreachable). Proposed fix in the friction log; this is the top candidate for the next
  code session.
- Friction item 3 (Home refresh indicator reflow) remains open, folds into the Home
  "today's agenda" work.
- The `deadline` state-variable/parameter names in the Android UI layer still carry the old
  name while meaning "start" (plan kept renames out of scope); rename opportunistically
  during the UI overhaul.

## Next Step

- Per owner priority: friction item 7 (start blocker for in-progress blocks), then the
  Home "today's agenda" list, then the all-screens UX redesign (backlog updated 2026-07-06
  with owner emphasis: Morning Review is the worst offender).
