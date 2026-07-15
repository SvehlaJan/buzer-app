# Sub-Tasks And Recurring Tasks Research

Date: 2026-07-13 (updated 2026-07-15)
Status: research complete; approved design recorded in
`docs/superpowers/specs/2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`

## Scope

Investigate the current Buzer task pipeline and the Todoist behavior needed to support:

- Todoist sub-task hierarchy
- Todoist recurring tasks
- Android display, editing, alarm, completion, and accountability behavior for both

This note records the investigation that informed the approved design. The linked design spec is
the authority for decisions; this note remains the evidence and current-state analysis.

## Current Project State

At the time of the investigation, the current feature stack was healthy:

- Current commit CI: `ci.yml` run `29248106439` passed on 2026-07-13
- Most recent full local emulator evidence: the Android redesign handoff reports 24 managed-device
  tests passing on 2026-07-12

The implemented task path is:

```text
Todoist Sync items
  -> HttpTodoistAdapter flat TodoistTaskMirror
  -> todoist_tasks + one commitment_states row per Todoist task
  -> flat /tasks/snapshot DTOs
  -> SnapshotReducer + AlarmPlanCalculator
  -> Home, Morning Review, and blocker surfaces
```

Important current semantics:

- Todoist `due.datetime` is the start of a commitment block.
- Buzer's enforcement deadline is `due + duration`.
- Todoist owns visible task data; Buzer owns enforcement-only state.
- Every visible snapshot item is currently validated and scheduled independently.
- The app has a shared `CommitmentEditor` for create, repair, and reschedule, but it has no parent
  or recurrence fields.

## What The Current Model Omits

The Sync response's raw task JSON is retained, but the typed model, SQLite mirror, snapshot, and
Android DTO omit:

- `parent_id`
- `child_order`
- due `is_recurring`
- due `string`
- due `lang`
- due `timezone`

Consequences:

- Sub-tasks appear as unrelated flat tasks.
- The sibling order cannot be reconstructed from typed data.
- Recurring tasks look like ordinary tasks for the current occurrence.
- Android cannot label a recurrence or safely edit it.
- Existing edit, extend, and reschedule calls send only an exact `due.datetime`; doing that to a
  recurring task risks replacing its recurring due rule with a one-time date.

## Current Todoist Sub-Task Semantics

Current Todoist API v1 documentation says:

- `parent_id` identifies a task's parent; `null` means a root task.
- `child_order` orders tasks that share a parent.
- A task can be created with `parent_id`.
- Moving an existing task under a parent uses the dedicated `item_move` Sync command. The general
  update operation explicitly says parent changes require a more specific command.
- Reordering siblings uses `item_reorder`.
- A parent and child must be in the same Todoist project.
- Todoist clients support four indentation levels.
- Closing a root task closes it and its sub-tasks. Closing a sub-task marks that sub-task complete,
  but it is not moved to history until its parent is completed.

Sources:

- [Todoist API v1](https://developer.todoist.com/api/v1/)
- [Introduction to sub-tasks](https://www.todoist.com/help/articles/introduction-to-sub-tasks-kMamDo)

## Current Todoist Recurrence Semantics

Todoist does not expose a structured RRULE as the primary editable recurrence model. Recurrence is
part of the task's `due` object:

- `date`: the current occurrence's date or datetime
- `is_recurring`: whether a recurrence exists
- `string`: the human-readable recurrence rule, such as `every Monday at 09:00`
- `lang`: the language used to parse the rule
- `timezone`: required to calculate fixed-time future occurrences correctly

REST task creation accepts `due_string` and `due_lang`; an exact `due_datetime` alone creates a
one-time due date. Sync `item_update` accepts a `due` object with the recurrence string.

Completion is occurrence-aware:

- `item_close` behaves like Todoist clients: regular tasks become completed, while recurring tasks
  advance to their next occurrence.
- `item_update_date_complete` is the explicit recurring-completion command. It accepts
  `reset_subtasks`.
- With `reset_subtasks = false`, completed sub-tasks stay completed when the recurring parent
  advances.
- With reset enabled, Todoist recreates/reset the same first-level sub-tasks for the next
  occurrence. Todoist documents a limit of 30 and does not reset deeper sub-sub-tasks; sibling order
  is not preserved.
- Todoist also exposes a distinct "Complete forever" behavior. Buzer has no equivalent today.

Sources:

- [Todoist API v1](https://developer.todoist.com/api/v1/)
- [Complete a task with a recurring date](https://www.todoist.com/help/articles/complete-a-task-with-a-recurring-date-dmI6SVqdP)
- [Reset a sub-task in Todoist](https://www.todoist.com/help/articles/reset-a-sub-task-in-todoist-V6SRBGPw0)
- [Introduction to recurring dates](https://www.todoist.com/help/articles/introduction-to-recurring-dates-YUYVJJAV)

Context7 resolved the current documentation as `/websites/developer_todoist_api_v1`.

## Recurring Completion Is Not Correct Today

`HttpTodoistAdapter.completeTask` currently sends `item_complete`. `TaskActionService.done` then
immediately:

- writes `completed_at`
- changes the companion state to `completed`
- increments the schedule version

Android consequently hides the task and cancels its alarms. Even if a later Todoist sync returns
the same recurring task ID active at its next date, `TodoistSyncService` only pushes state toward
`completed` or `deleted`; it does not reset a completed row to `planned`.

Correct recurring rollover also needs to reset per-occurrence enforcement state:

- `started_at`
- start dismiss count
- deadline extension count
- last snooze
- escalation sent timestamp
- lifecycle state

There is a further schema conflict: `whatsapp_escalations_one_per_task` is unique by task ID. A
recurring task could therefore escalate only during its first occurrence unless accountability is
keyed to an occurrence identity.

The stable Todoist task ID should remain the task identity, but Buzer needs an explicit occurrence
boundary. The exact representation is a design decision; candidates include a due-instance key or
a separate occurrence table.

## Adjacent Task-Action Gaps

The same backend action paths contain correctness gaps worth fixing with recurrence rather than
building around them:

- normal completion currently sends `item_complete`, while current Todoist API v1 documents
  `item_close` and the recurrence-specific `item_update_date_complete`;
- full edit sends `project_id` inside `item_update`, while Todoist project movement requires
  `item_move`;
- full edit sends an exact due and resets lifecycle to planned even for metadata-only changes;
- Todoist command UUIDs are generated per attempt, so retries do not have a durable idempotency
  identity; and
- `Extend 30` currently moves `due.datetime` 30 minutes later while keeping duration fixed. Under
  the accepted due-is-start semantics, that postpones the whole block instead of extending its end.

The approved broader cleanup corrects these boundaries alongside occurrence support. One-time
extension will preserve due, add 30 minutes to duration, and append an idempotent entry to a managed
read-only activity section in the Todoist description. Recurring extension remains unavailable.

## Sub-Task Product Question Investigated

The current strictness rule is task-local: every task assigned to the user needs an exact start and
duration. Applying that unchanged to a hierarchy means an undated child becomes independently
invalid even when its parent is a fully scheduled commitment.

The research considered whether hierarchy should mean:

1. every parent and child is an independent committed block;
2. a scheduled parent is the commitment and children are an execution checklist; or
3. both modes are supported explicitly.

The approved answer is one scheduled root commitment with direct checklist children. The mirror
still records all task facts uniformly so independently scheduled child commitments can be added
later without a schema rewrite.

Other hierarchy questions that were resolved in the design:

- whether completing a parent from Buzer may cascade to unfinished children
- whether a child can have its own schedule inside the parent's block
- whether parent schedule/assignee/duration are inherited visually or semantically
- whether Buzer creates and re-parents sub-tasks or initially only mirrors Todoist hierarchy
- how many hierarchy levels Buzer should display or edit

## Cross-Cutting Implementation Surfaces

Any eventual implementation plan must cover these existing suites and consumers in the same plan:

### Backend

- `backend/src/db/schema.sql` and guarded migrations
- task repository and Todoist mirror types
- HTTP Todoist adapter mapping and commands
- Todoist sync rollover detection
- task action service completion, update, reschedule, and extend semantics
- snapshot contract and strictness validation
- WhatsApp escalation uniqueness and occurrence scoping
- backend adapter, sync, action, snapshot, route, repository, and migration tests

### Shared And Android

- `TaskSnapshotItem` and request serialization
- `SnapshotReducer`
- `AlarmPlanCalculator`
- schedule/blocker UI models
- Home agenda grouping and stable Compose keys
- Morning Review repair
- Add Task and shared Commitment Editor
- blocker completion and recurring-specific actions
- Android JVM state tests
- `client/androidApp/src/androidTest` Add Task and blocker flows

### Test Infrastructure And Documentation

- fake backend fixtures/server for parent trees and recurrence rollover
- deterministic `scripts/e2e-fake-backend.sh` lane
- `docs/notes/api-contract.md`, which is already behind current description/update behavior and
  should be brought back into alignment when the contract next changes

## Research Conclusions

- Both features are feasible without changing the Android-to-backend trust boundary.
- Sub-task read support is mostly a typed-data and presentation problem, but create/move/reorder and
  cascade completion make full editing substantially larger.
- Recurrence is first a correctness/lifecycle problem, then a creation/editor problem. Merely adding
  an `isRecurring` badge would leave completion, editing, alarms, and accountability wrong.
- Recurring parent tasks combine both features and expose Todoist-specific reset behavior that Buzer
  must make explicit rather than silently choosing for the user.
- A safe design should establish recurrence-safe completion and occurrence scoping before allowing
  Buzer to create or edit recurrence rules.

## Approved Product Direction

- A Buzer commitment is a root Todoist task with at most 30 direct, schedule-free checklist
  children and no grandchildren.
- Add/Edit supports the recurrence presets Never, Daily, Weekdays, Weekly, Monthly, and Yearly.
- Recurrence is fixed-calendar and anchored to the selected start and time zone.
- Parent `Done` requires all direct children checked.
- Recurring completion always uses `reset_subtasks = true`; there is no reset preference.
- The backend uses occurrence keys, per-occurrence accountability, revisions, and idempotent action
  IDs so multiple Android phones can enforce the same commitment safely.
- `Extend 30` is unavailable for recurring commitments in this MVP.
- For one-time commitments, `Extend 30` preserves the start, adds 30 minutes to Todoist duration,
  and appends one timestamped entry to a Buzer-managed activity section in the description.
- Full task mutation uses dedicated Todoist close, recurring-complete, move, update, unassign, and
  child commands with durable command UUIDs and post-mutation synchronization.
- Metadata-only edits preserve lifecycle; editor-driven schedule changes replan it.
- The implementation brings `docs/notes/api-contract.md` in line with the actual expanded contract.
  This includes its deployment base-path guidance, which changed during the research period.

See the approved design spec for the architecture, API, UI, failure handling, and test strategy.
