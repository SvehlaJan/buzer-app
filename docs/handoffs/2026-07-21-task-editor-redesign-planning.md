# Handoff: Task Editor Redesign Planning

Date: 2026-07-21

## Task / Plan

- Design specification: `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`
- Implementation plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Plan checkbox status: all implementation tasks remain unchecked; this session updated
  product/design documentation and created the execution plan only.

## Files Changed

- Updated `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`.
  - Reconciled the design with the merged real-backend E2E baseline.
  - Made Add and Edit equally editable for Project and Assignee.
  - Recorded project-scoped synced collaborator choices, personal locked Me,
    optional non-self schedules, explicit schedule clearing, accordion behavior,
    no time-zone UI, cross-midnight copy, and protected repair/reschedule scope.
- Added `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`.
  - Ten test-first implementation tasks cover collaborator mirroring, nullable
    schedules, shared DTOs, pure editor state, Compose UI, Add/Edit migration,
    fake/real E2E parity, and final verification.
- Updated `docs/handoffs/2026-07-21-task-editor-redesign-design.md` with a
  supersession note so its original read-only Edit assumption cannot mislead a future
  implementation session.
- Added this handoff.

## Investigation

- Confirmed merge `16a9ca0` contains `feature/e2e-real-backend`; no production Add/Edit
  UI changed in that merge.
- Confirmed `RootTaskPatch` already has `projectId` and `assigneeId`, and the backend
  update route maps both. The merge adds `@EncodeDefault` for root-only empty
  checklist-mutation requests.
- Confirmed the remaining gap for project-valid choices is a collaborator read model:
  `/projects` currently returns project flags only, while the Todoist adapter syncs
  only `items` and `projects`.
- Checked current Todoist documentation with Context7: Sync returns `collaborators`
  and `collaborator_states`; active membership maps a `user_id` to `project_id`.
  Todoist also supports clearing a duration with `duration: null` and an assignment
  with `responsible_uid: null`.
- Confirmed the newer due-semantics decision: Todoist due is the block start, and the
  enforcement deadline is due plus duration.
- Read the latest real-backend E2E plan, validation note, task 6–9 handoffs, current
  Add/Edit/picker sources, backend mutation/sync paths, and associated tests.

## Verification

Verification:

- Command: `git merge-base --is-ancestor feature/e2e-real-backend master`
- Result: PASS
- Evidence: exit code 0; master contains the E2E integration.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics after documentation updates.

Verification:

- Command: documentation/source inspection and Context7 Todoist Sync API lookup
- Result: PASS
- Evidence: plan paths, model names, validation rules, and E2E expectations match the
  inspected master sources and latest validation record.

## Tests

- Not run: this session changes documentation only. The plan specifies the full
  backend, JVM, fake-backend E2E, and real-backend E2E gates for implementation.

## Assumptions

- The current user is a valid shared-project assignee even if Todoist does not return
  an explicit collaborator-state row for the project owner.
- Active collaborator membership is the source for all non-self shared-project
  assignee options; no avatar retrieval is required.
- An explicit `No schedule` control for a non-self assignee clears both due and
  duration and resets recurrence to `never` in the local draft before Save.
- The known real-lane `[partial-failure]` discrepancy remains a test-fixture
  convention, not behavior to copy into the production adapter.

## Next Recommended Step

Execute `docs/superpowers/plans/2026-07-21-task-editor-redesign.md` in a new dedicated
worktree from current master, using a task-by-task review workflow. Do not reuse or
alter `.worktrees/e2e-real-backend`.
