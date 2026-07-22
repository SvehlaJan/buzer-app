# Handoff: Unified Task Editor Design

Date: 2026-07-22
Agent: Codex
Plan: `2026-07-22-unified-task-editor.md` — Tasks 1–8 unchecked; planning complete, implementation not started.

## Scope

- Brainstorm and record the replacement of `CommitmentEditor` with one editor for
  Add, Edit, Morning Review Repair, and deadline-blocker recovery.

## Completed

- The user approved a two-intent architecture: `Create` and `Update`.
- Every update flow validates immediately on entry; Create remains quiet until a
  field is interacted with or Add is tapped.
- Repair and blocker recovery use the full editable editor and a complete
  `UpdateTaskRequest`; only headings, warnings, primary labels, success handling,
  and cancel destinations differ by host.
- The user authorized retiring the schedule-only reschedule endpoint after caller
  migration.
- Wrote the accepted specification and a decision document.
- The subsequent grill-me review confirmed atomic endpoint removal, all-Update
  eager validation, shared Activity-log footer, dynamic verb-plus-range action
  labels, disabled invalid actions, in-place project loading, pending confirmation,
  and no MVP conflict-resolution or FCM work.
- Wrote the detailed, test-first implementation plan and cross-checked its file
  paths against the current test layout. It explicitly migrates existing tests and
  forbids use or modification of the deprecated TypeScript fake backend.

## Files Changed

- `docs/superpowers/specs/2026-07-22-unified-task-editor-design.md`
- `docs/decisions/2026-07-22-retire-reschedule-endpoint.md`
- `docs/decisions/2026-07-22-defer-update-conflict-resolution.md`
- `docs/superpowers/plans/2026-07-22-unified-task-editor.md`
- This handoff.

## Verification

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose" "What is the recommended architecture boundary between a reusable stateful editor composable and screen-level navigation, validation, and side effects in 2026?"`
- Result: PASS
- Evidence: resolved the official Android Compose documentation identifier.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose" "State hoisting guidance for reusable composables: which state should a reusable composable receive as parameters and which screen-level responsibilities stay outside?"`
- Result: PASS
- Evidence: official Compose documentation recommends screen-level ownership of
  state/effects and value-plus-callback reusable content.

Verification:
- Command: documentation review against `AGENTS.md`, the approved product spec,
  the 2026-07-21 redesign design/plan, recent task-editor handoffs, and current git
  worktree/status.
- Result: PASS
- Evidence: the new spec explicitly supersedes the former repair/reschedule
  exclusion and records the endpoint contract change.

Verification:
- Command: `rg --files client/androidApp/src/test client/androidApp/src/androidTest client/shared/src/commonTest backend/test | rg 'TaskEditor|AddTask|EditTask|MorningReview|Blocker|BuzerApiClient|routes|taskAction|e2eServer'`
- Result: PASS
- Evidence: plan test paths match the current unit/instrumentation/backend layout;
  it creates new tests only where no equivalent coverage exists.

## Manual Verification

- Generated and reviewed Repair and full-blocker editor concept images during the
  approved design discussion. They are preview assets, not production resources.

## Decisions And Assumptions

- The user selected full editing from the blocker, not a schedule-only or
  Schedule-plus-Notes variant.
- Repair returns to the remaining invalid-task list after success; blocker update
  closes the blocker after confirmed success.
- The pre-existing untracked
  `docs/handoffs/2026-07-21-connected-device-install.md` belongs to another
  session and remains untouched.
- The deprecated `tools/fake-backend` TypeScript server is not an implementation or
  verification target for this work; `backend/test/e2eServer.ts` is authoritative.
- The user accepts an atomic personal-app cutover for endpoint removal and will use
  one client at a time; stale conflict resolution is deferred until push
  invalidation is designed.

## Open Issues

- Implementation has not started. The earlier 2026-07-21 plan deliberately
  preserves `CommitmentEditor`; execute only the new replacement plan.

## Next Step

- Ask the user whether to execute via subagents or sequentially in this task. Use
  the plan's task order because the shared update host must exist before Repair and
  blocker migration, and remove the endpoint only after all callers have moved.
