# Handoff: Recurring Tasks And Checklist Sub-Tasks Design

Date: 2026-07-13 to 2026-07-15
Plan/task: research and brainstorming design; no implementation plan exists yet
Attempted steps: project research, Todoist API verification, product clarification, architecture
comparison, section-by-section design approval, adjacent task-action gap review, written spec, and
spec self-review

## Status

- Research and brainstorming are complete.
- No code or API behavior changed.
- The occurrence-key retrofit with checklist-only children and broader task-action correctness
  cleanup is approved.
- Research note: `docs/notes/2026-07-13-subtasks-recurring-tasks-research.md`
- Design spec:
  `docs/superpowers/specs/2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`
- The next gate is owner review of the committed written spec. Do not write the implementation plan
  until that review is approved.

## Project Context Read

- `AGENTS.md`
- approved 2026-04-30 product spec
- implementation plan index
- backend Sync/API plan
- alarm reliability/Todoist semantics plan
- full task edit plan
- Android redesign plan
- recent handoffs for the redesign, full edit flow, personal-project assignment, and Todoist update
- accepted Todoist-native due and task-details decisions

## Files Changed

- `docs/notes/2026-07-13-subtasks-recurring-tasks-research.md`
- `docs/handoffs/2026-07-13-subtasks-recurring-tasks-research.md`
- `docs/superpowers/specs/2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`

At the final amendment, the worktree was on `master`, one commit ahead of `origin/master`, with only
these three design documents untracked. The existing backend-exposure commit was left untouched.

During the earlier review, another process created feature-branch commit `21b1180` and included the
initially staged versions of these three documents alongside backend-exposure documentation. That
commit was not amended or rewritten. After the workspace returned to `master`, the documents were
untracked again; the final approved versions are committed on the current branch as a focused
documentation change.

## Commands And Results

Verification:
- Command: `git status --short --branch`
- Result: PASS
- Evidence: current branch is `master`, one commit ahead of `origin/master`; only the three
  recurrence/checklist design documents are uncommitted for this task.

Verification:
- Command: `npx ctx7@latest library "Todoist API" "Current API v1 Sync API subtasks parent_id child_order recurring tasks due is_recurring recurrence completion next occurrence item_complete item_update item_add"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Sync API items subtasks parent_id child_order create subtask parent_id move completion recurring tasks due is_recurring string lang timezone item_complete advancing next occurrence item_update recurrence"`
- Result: PASS
- Evidence: confirmed parent/move fields and recurring completion/reset-subtask commands.

Verification:
- Command: `npx ctx7@latest docs /websites/developer_todoist_api_v1 "Sync API item_update can it change project_id; correct item_move command to move task to another project; implications for subtasks"`
- Result: PASS
- Evidence: confirmed that moving requires the dedicated `item_move` command rather than an
  ordinary `item_update`.

Verification:
- Command: official-site `bx` research queries scoped to `developer.todoist.com` and
  `todoist.com`
- Result: PASS
- Evidence: confirmed Todoist task hierarchy, due recurrence representation, `item_close`,
  `item_update_date_complete`, reset-subtask behavior, hierarchy depth, and ordering limitations.

Verification:
- Command: `gh run list --workflow=ci.yml --limit 5 --json ...` during research
- Result: PASS
- Evidence: research-baseline commit `31d1953` passed run `29248106439`.

Verification:
- Command: `rg` placeholder/ambiguity scan, `git diff --check`, and staged diff review
- Result: PASS
- Evidence: no unresolved placeholders or whitespace errors; review fixes made reset behavior
  explicit for external Todoist rollover, separated root and child revisions, and made the
  recurring extension UI unambiguously disabled. The amendment review also made project-move
  recovery and the one-time extension audit contract explicit.

## Tests

Verification:
- Command: backend/client/fake-backend local test suites
- Result: SKIPPED
- Evidence: no runtime code changed; CI was checked for the research baseline and the 2026-07-12
  redesign handoff records the full local backend, fake-backend, Gradle, and 24-test emulator lanes
  passing.

## Manual Verification

- Read the current schema, repositories, adapter, sync/action/snapshot services, shared DTOs,
  reducer, alarm planner, Home rendering, editor models, fixtures, and relevant test inventory.
- No live Todoist mutations were performed.

## Known Findings

- Parent and recurrence metadata exist only in stored raw Todoist JSON.
- Recurring completion and next-occurrence lifecycle reset are not supported correctly.
- Existing reschedule/edit/extend actions may destroy a recurrence by replacing it with an exact
  non-recurring due datetime.
- Full edit uses `item_update` for project movement and resets lifecycle even for metadata-only
  edits.
- Current one-time extension moves the Todoist start rather than extending the block end.
- Per-task enforcement state and the one-escalation-per-task index need occurrence scoping.
- Cross-device safety requires durable action IDs, task/child revisions, expected occurrence keys,
  and serialized mutations.

## Assumptions

- Todoist remains the source of truth for visible hierarchy and recurrence rules.
- Buzer should not calculate future recurrence occurrences independently when Todoist can advance
  them authoritatively.
- Todoist tasks are expected to follow the approved MVP hierarchy invariant; unexpected structures
  are diagnosed and blocked rather than silently flattened.
- The managed activity section is read-only in Buzer but remains editable through external Todoist
  clients; malformed markers are preserved and a fresh section is appended safely.

## Next Recommended Step

Have the owner review the committed design spec. After explicit approval, invoke the writing-plans
workflow and create a detailed implementation plan that includes existing backend, shared JVM,
Android JVM, `client/androidApp/src/androidTest`, fake-backend, and deterministic emulator suites.
