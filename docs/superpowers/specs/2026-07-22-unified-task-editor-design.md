# Unified Task Editor

Date: 2026-07-22

## Status

Accepted. This specification extends the implemented 2026-07-21 Task Editor
Redesign to every task-update surface. It supersedes that specification's
exclusion of Morning Review repair and deadline-blocker rescheduling, and is
paired with the endpoint-retirement decision in
`docs/decisions/2026-07-22-retire-reschedule-endpoint.md`.

The product-spec references to a dedicated reschedule mutation are amended only
as described here: deadline-blocker rescheduling becomes a complete task update.
Todoist remains the authority for task-visible data, and the action-journal,
reconciliation, direct-checklist, and alarm rules remain unchanged.

## Goal

Use one practical, Schedule-first editor for every task creation or update. A
user can edit every task field from Add, Edit, Morning Review Repair, and the
deadline blocker. The editor must not fork into four implementations or carry
screen-specific request and navigation code.

The completed UI keeps the approved compact interaction model:

- editable title;
- Schedule first, with collapsed summary, adaptive quick choices, and separate
  date, start-time, and duration wheel dialogs;
- editable Project, Assignee, Repeat, Notes, and Checklist sections;
- local draft changes until the primary action; and
- equal-width Cancel and primary action buttons.

Detailed Schedule, collaborator, recurrence, checklist, and time-zone semantics
from `2026-07-21-task-editor-redesign-design.md` remain in force. There is still
no visible time-zone selector.

## One Editor, Two Mutation Intents

The UI has no Add/Edit/Repair/Reschedule layout mode. Internally it has only:

| Mutation intent | Initial draft | Submit request |
| --- | --- | --- |
| `Create` | New-task defaults and preferred project | `CreateTaskRequest` |
| `Update` | Full `TaskSnapshotItem`, including root and checklist state | `UpdateTaskRequest` |

The shared `TaskEditor` is presentation-neutral. It receives the current draft,
project/collaborator facts, validation result, editor interaction state, and
event callbacks. It owns only local presentation details such as accordion,
sheet, and pending wheel-dialog state. It does not load data, create UUIDs, make
network calls, navigate, or decide a request shape.

A thin `TaskEditorHost` at each entry point supplies a presentation record:

- heading and optional contextual warning;
- primary action label and loading copy;
- validation exposure policy;
- discard destination; and
- submit and success callbacks.

This follows Compose state-hoisting guidance: screen hosts own state with
external effects while the reusable editor is driven by values and callbacks.
No component in the editor branches on a named repair or blocker mode.

## Host Presentation Differences

The following differences are intentionally confined to the host boundary.

| Entry point | Context copy | Primary action | Validation on entry | Success | Cancel destination |
| --- | --- | --- | --- | --- | --- |
| Add | `Add task`; no warning | Add | No | Normal Add destination | Prior app destination |
| Edit | `Edit task`; no warning | Save | Yes | Normal Edit destination | Prior app destination |
| Morning Review | `Repair task`; concise invalid-task warning | Repair | Yes | Remove/reduce the repaired item, then show remaining review list | Remaining review list |
| Deadline blocker | `Reschedule task`; block-ended warning | Reschedule | Yes | Close the blocker | Blocker action surface |

All four hosts render the same editable task fields. The normal Edit host may
append the existing read-only Activity log after Checklist through an outer slot;
the log is not part of `TaskEditor` and does not create a screen mode.

The deadline blocker continues to consume Android system Back as required by the
full-screen enforcement policy. Its editor still exposes Cancel; after a dirty
draft confirmation, Cancel returns to the blocker action surface rather than
escaping the blocker.

## Draft, Validation, and Exit Behavior

Every entry point creates a complete local `TaskEditorDraft`. Title, schedule,
project, assignee, recurrence, notes, and checklist mutations contribute to the
draft fingerprint. Accordion state, selector visibility, and uncommitted wheel
values do not.

`validateTaskDraft()` is the sole domain validator. It returns all current field
and section errors, including dependent schedule rules, assignee membership,
recurrence compatibility, and checklist constraints. It has no knowledge of the
entry point.

Validation exposure is deliberately minimal:

- `Create` starts quiet. Once a user interacts with a field, its errors and any
  dependent errors update immediately. Tapping Add reveals every remaining error.
- Every `Update` host validates the entire prefilled draft on entry. It highlights
  all invalid values, focuses a missing Title first, otherwise expands the first
  invalid expandable section in display order, and shows a concise contextual
  error. Project, Assignee, and Repeat remain closed selection rows with inline
  errors; entry validation never opens a selection sheet. Errors continue to update
  immediately as the draft changes.
- A cancelled wheel dialog leaves the draft and all validation state unchanged.

Project/collaborator loading is an availability state, not a false validation
failure. Entry validation immediately evaluates every fact present in the task
snapshot; membership-dependent errors become actionable when the project facts
finish loading, and Submit remains disabled while a required choice cannot be
validated.

Schedule remains one dependent group. Date, start time, duration, self-assignment,
and recurrence are evaluated together. A partial schedule is never silently
accepted; a non-self assignee may retain an empty schedule only with `Never`
recurrence.

Dirty Cancel or back behavior is common. A clean draft takes its host's cancel
destination; a dirty draft first shows `Discard changes?`. Submitting disables
dismissal until the action resolves.

## Submission, Retry, and Recovery

Add keeps the existing create flow. Edit, Repair, and the deadline blocker use one
shared update-request builder that produces the existing complete
`UpdateTaskRequest`: root patch, project, typed nullable assignee and schedule
patches, recurrence, and ordered checklist child mutations.

The builder receives the task identity, expected occurrence key, expected task
revision, current draft, and original checklist state from its host. It is not
specialized for Repair or Reschedule.

On first submission, a host captures the request and action ID. Retry resends the
same captured request. A domain-draft change invalidates it and creates a new
request only on a later submit. A stale, pending, local-reconciliation, or network
failure keeps the full editor open with the draft intact and uses the established
error/retry presentation.

Morning Review retains its existing snapshot reduction rules after a successful
update. The deadline blocker submits through the existing update coordinator and
closes only on its confirmed successful update outcome; the next authoritative
snapshot controls future alarms and blockers.

## Full Blocker Editing

Opening the deadline-blocker editor must retain the complete `TaskSnapshotItem`,
not only its compact `BlockerUiModel`. The host loads the same project and
collaborator choices used by normal Edit before enabling a submit. It can display
the block-ended warning while those choices load, and preserves the local draft on
load, membership, or action failure.

The user may change title, Schedule, Project, Assignee, Repeat, Notes, and
Checklist from this context. The label remains `Reschedule` because it describes
the recovery intent, but it submits the ordinary full task update rather than a
schedule-only request.

## Retire Legacy Editor and Reschedule Contract

After all callers migrate:

1. Delete `CommitmentEditor`, its mode models, and the legacy combined
   date-time/duration picker if no other source imports them.
2. Delete the schedule-only `RescheduleTaskRequest`, Android client and
   enforcement API methods, coordinator method, backend route/service path, and
   tests.
3. Remove the old `POST /tasks/:id/reschedule` endpoint. The deadline blocker uses
   `POST /tasks/:id/update` with the same reconciliation protocol as Edit and
   Repair.

For a deployed personal installation, update the Android app before or together
with the backend release that removes the old endpoint. No external public API
compatibility promise is required.

## Verification

The implementation plan must migrate the existing tests rather than retaining
legacy-picker tests as a separate regression contract.

- Pure Kotlin tests: one validator for Create and Update exposure policies,
  initial invalid-update focus, field/dependency revalidation, picker
  Apply/Cancel, dirty exits, request identity, and complete update construction.
- Backend and shared-contract tests: retain the current update validation and
  reconciliation guarantees; delete assertions and fixtures unique to the retired
  reschedule route.
- Android instrumentation: Add's quiet initial state; Edit and Repair immediate
  invalid-state display; Repair's return to remaining list; full deadline-blocker
  editing and successful close; all update retries and discard paths.
- Run `:shared:testAndroidHostTest`, `:androidApp:testDebugUnitTest`, and
  `:androidApp:assembleDebug`.
- The real backend E2E server in `backend/test/e2eServer.ts` is the authoritative
  Android integration lane. Do not invoke, modify, or count the deprecated
  TypeScript server under `tools/fake-backend`.
- Perform targeted physical-device verification of Repair and full blocker editing
  against a non-destructive local E2E backend before installing the production
  configuration.

## Design Self-Review

- Every editable task field has one visual implementation and one draft model.
- The sole mutation distinction is Create versus Update; presentation does not leak
  into the editor or validator.
- Every update surface intentionally validates on entry, while a blank Add form
  remains approachable.
- Repair, Edit, and blocker recovery preserve action-journal retries and draft
  safety instead of introducing immediate field mutations.
- Retiring the duplicate reschedule contract reduces backend and client branching
  without changing the enforcement lifecycle or alarm authority.
