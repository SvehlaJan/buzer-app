# 2026-06-13 Project-Aware Task Creation Brainstorm

## Task / Plan

- Task: brainstorm and document the next implementation target after real-alarm verification.
- Target chosen by user: A, backend task creation plus Android project picker.
- Plan: no implementation plan executed yet; design spec was produced for user review.

## Files Changed

- `docs/superpowers/specs/2026-06-13-project-aware-task-creation-design.md`
- `docs/handoffs/2026-06-13-project-aware-task-creation-brainstorm.md`

## Decisions Captured

- Android Add Task must require selecting a Todoist project.
- The project picker should show all active projects, not only shared/assignment-capable projects.
- If a project has `canAssignTasks = true`, Android must require selecting an assignee.
- If a project has `canAssignTasks = false`, Android should not require or send an assignee.
- Tasks created in non-assignable projects are still Buzer commitments for the current user.
- Backend should represent that with companion-owned commitment ownership rather than fake Todoist assignment.
- Backend should switch Android-originated task creation to Todoist REST v1 `POST /tasks` and verify returned fields.
- Backend schemas should accept omitted/null optional fields so Android can stop depending on explicit `null` payloads.

## Commands Run

Verification:
- Command: `npx ctx7@latest library "Todoist API" "Sync API item_add project_id responsible_uid due datetime duration projects resource types REST create task update task due datetime duration assignment"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Sync API item_add project_id responsible_uid due datetime duration projects resource types REST create task update task due datetime duration assignment"`
- Result: PASS
- Evidence: docs showed Sync `item_add`, REST create/update fields including `project_id`, `assignee_id`, `due_datetime`, `duration`, and `duration_unit`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Todoist API projects sync resource types project object shared collaborators assignment capable project fields"`
- Result: PASS
- Evidence: docs showed project fields including `shared` and `can_assign_tasks`, and sync resources including `projects`, `collaborators`, and `collaborator_states`.

Verification:
- Command: `rg -n "TBD|TODO|FIXME|placeholder|must be decided|should we" docs/superpowers/specs/2026-06-13-project-aware-task-creation-design.md`
- Result: PASS
- Evidence: no placeholder or ambiguity markers found.

## Tests Run

- No code tests were run; this was a design-only brainstorming pass.

## Manual Verification

- User reviewed and approved each design section in chat.

## Known Failures / Skipped Checks

- The design spec has not yet been turned into an implementation plan.
- The design spec has not yet been committed at the time this handoff was written.
- Existing Android/shared logging and overlay watcher changes remain dirty in the worktree from the prior verification pass.

## Assumptions

- Todoist `can_assign_tasks` is reliable enough for Android form behavior.
- Non-assignable project tasks created from Android should always be current-user Buzer commitments.
- Auto-deleting partially created Todoist tasks is out of scope for MVP because it is destructive.

## Next Recommended Step

Ask the user to review `docs/superpowers/specs/2026-06-13-project-aware-task-creation-design.md`. If approved, invoke the writing-plans workflow and create a detailed implementation plan for this target.
