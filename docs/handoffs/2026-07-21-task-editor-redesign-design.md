# Task Editor Redesign Design Session — 2026-07-21

## Task / Plan

- Task: consolidate the approved Add/Edit task editor redesign and preserve the generated concepts for later revisions.
- Design specification: `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`.
- No implementation plan exists yet. The next step is user review of the written design, then a separate implementation-planning session.

## Files Changed

- Added `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`.
- Added seven preview PNGs under `docs/designs/2026-07-21-task-editor-redesign/`.
- Added this handoff.

## Investigation

- Read the product specification, relevant existing Android UI plan, recent handoffs, Add/Edit screens, `CommitmentEditor`, wheel picker implementations, and their existing tests.
- Confirmed `WheelPickerCompose` is already declared in the Android dependency catalog and is currently used by both `WheelDateTimeField` and `WheelDurationField`.
- Confirmed that the current update request only patches title, description, schedule, duration, recurrence, and checklist mutations; it does not patch project or assignee.

## Verification

- Command: `find docs/designs/2026-07-21-task-editor-redesign -maxdepth 1 -type f -print | sort`
  - Result: PASS
  - Evidence: all seven approved concept PNGs were copied into the workspace folder.

- Command: documentation/source inspection only
  - Result: PASS
  - Evidence: the design records the current `CommitmentEditor` sharing boundary and the existing wheel picker constraints (0–6 hours and five-minute duration increments).

## Tests

- Not run: documentation and reference-asset consolidation only; no production or test code changed.

## Assumptions

- The accepted mockups establish layout and interaction intent, while exact icon assets and typography continue to use the repository's existing theme system.
- The project/assignee selector mockups are immediately actionable for Add Task. Edit Task retains read-only project/assignee context until a backend contract explicitly supports changing those fields.

## Next Recommended Step

1. User reviews `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`, especially the Add-vs-Edit project/assignee boundary.
2. After approval, create a detailed implementation plan that names the existing instrumentation tests to migrate and keeps repair/reschedule regressions protected.
