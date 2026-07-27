# Handoff: Add vs. Repair Task Editor Audit

Date: 2026-07-27
Agent: Codex
Task: Audit remaining UI and logic differences between Add task and Morning Review Repair.

## Plan / Scope Attempted

- Read the approved product spec, unified task-editor design and implementation plan, the prior Morning Repair handoff, and the current Add/Repair/editor source and tests.
- Traced the Schedule UI from both entry points to the shared formatter and quick-choice generation.
- Audited host-only behavior: validation, initial draft, choice loading, submission/retry, checklist, activity footer, cancel, and success handling.

## Findings

- Schedule rendering and formatting are shared: `TaskEditorScaffold` renders one `ScheduleEditor`, which calls one `scheduleQuickChoices` implementation. The source contains no Add/Repair formatting branch.
- A Repair draft starts from the task snapshot, while Add starts blank. Consequently the shared quick-choice algorithm preserves a Repair task's selected date/time and prefixes non-today suggested times with a relative date; a blank Add draft starts from the current rounded time. This is data-driven rather than a UI implementation fork.
- Intentional differences remain at the Create vs. Update host boundary: quiet versus entry validation, create versus full-update/reconciliation, existing checklist and activity footer, and return-to-review success behavior.
- Remaining avoidable inconsistency: Add shows project-load failures through `ErrorAlertDialog`; Repair shows the same condition as an inline `WarningStatus`.
- Remaining determinism inconsistency: Add accepts `nowProvider`; Repair directly captures `LocalDateTime.now()`, making its quick suggestions harder to test/reproduce with a fixed clock.
- `showUpdateFooter` remains a redundant presentation switch: all three current `TaskEditorUpdateScreen` callers set it to `true`.

## Files Changed

- Added this handoff only.

## Commands Run

- `git worktree list` — PASS; primary checkout on `master`, two unrelated worktrees present.
- `git status --short` — PASS; pre-existing untracked `docs/handoffs/2026-07-21-connected-device-install.md` left untouched.
- `client/gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest' --tests '*AddTaskScreenStateTest' --tests '*MorningReviewScreenStateTest' --tests '*TaskEditorUpdateScreenStateTest'` — PASS; `BUILD SUCCESSFUL`.

## Verification

Verification:
- Command: `client/gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest' --tests '*AddTaskScreenStateTest' --tests '*MorningReviewScreenStateTest' --tests '*TaskEditorUpdateScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; covers shared quick choices, Add state, Repair state, and Update-host state.

## Manual Verification

- Not performed; this was a read-only source/test audit.

## Known Failures / Skipped Checks

- No full real-backend instrumentation lane was run because no user-visible behavior was changed.

## Assumptions

- The accepted unified-editor design remains the authority: Add is the sole Create host, while Repair is an Update host.

## Next Recommended Step

- If the product goal is strict UI consistency, unify project-load error presentation and inject a clock into Repair/Update hosts. Keep the listed Create-vs.-Update differences because they are explicitly required by the accepted design.
