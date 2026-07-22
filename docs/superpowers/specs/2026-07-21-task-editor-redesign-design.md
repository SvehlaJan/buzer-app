# Task Editor Redesign

Date: 2026-07-21

## Status

Accepted design and implementation scope. This specification supersedes the initial
design-session notes after the 2026-07-21 real-backend E2E merge and the completed
design review. The supporting mockups remain together in
`docs/designs/2026-07-21-task-editor-redesign/` so they can be revised without
losing the agreed direction.

Implementation status (2026-07-22): the redesign is implemented on
`feature/task-editor-redesign`, including backend/fake-backend contract coverage and
migrated Android instrumentation sources. Backend and fake-backend gates pass. The
final Android compilation, device E2E, real-backend E2E, and hands-on checks remain
pending because the environment cannot access Gradle's shared wrapper cache and its
required elevation was rejected for usage limits; see the implementation handoff for
the exact commands and evidence.

## Purpose

Make Add Task and Edit Task fast to scan and practical on a phone. The current form
duplicates schedule facts in a receipt and separate inputs, uses too much vertical
space, and exposes recurrence as a long stack of buttons. The redesigned editor
shows each current choice in a compact row and reveals controls only for the active
decision.

The form must preserve Todoist as the authority for visible task data, the existing
action-journal/reconciliation protocol, draft-exit protection, direct-checklist
constraints, and Android alarm behavior. Under the accepted Todoist-native semantics,
`due.datetime` is the **start** of the committed block and its end is
`due + durationMinutes`; the UI therefore says `Start` and displays a derived end.

## Supporting Mockups

| Mockup | Role |
| --- | --- |
| `01-schedule-inline-options.png` | Expanded Schedule with adaptive quick choices and compact Custom controls. |
| `02-schedule-custom-wheel-dialog.png` | Precise wheel-dialog concept. |
| `03-collapsed-task-editor.png` | Main hierarchy with Schedule first and collapsed. |
| `04-repeat-picker.png` | Recurrence selection sheet. |
| `05-notes-and-checklist.png` | Expanded Notes and Checklist sections. |
| `06-project-selector.png` | Project-selection sheet. |
| `07-assignee-selector.png` | Assignee-selection sheet. |

Mockup text and layout are design references, not test fixtures or API truth.

## Scope

In scope:

- One equivalent Add/Edit editor: title, Schedule, Project, Assignee, Repeat, Notes,
  and Checklist are editable in both flows.
- Schedule-first hierarchy, collapsed by default, adaptive single-tap choices, and
  separate precise date, time, and duration wheel dialogs.
- Project-scoped Assignee choices sourced from the backend's synced Todoist
  collaborator membership rather than a static Me/Wife build configuration.
- The existing `POST /tasks` and `POST /tasks/:id/update` contracts needed to create
  or edit an unscheduled non-self task and to clear an existing non-self schedule.
- Matching JVM, backend, and Android instrumentation coverage, including the merged
  real-backend E2E runner.

Out of scope:

- Changing lifecycle, reconciliation, alarm delivery, or Todoist mutation authority.
- Redesigning Morning Review repair or blocker reschedule. They continue to use the
  existing `CommitmentEditor` layout and are protected by regression tests.
- A visible time-zone selector. The app still supplies the device zone internally for
  scheduled recurrence serialization, but the user has no time-zone decision here.
- Child schedules, checklist reorder, grandchildren, Todoist webhooks, and avatar
  image fetching.

## Main Form

The screen has one scrollable content column and a sticky bottom action bar:

1. Back affordance and `Add task` or `Edit task` heading.
2. Prominent, borderless, single-line title editor with an understated divider.
3. **Schedule**, first data section and collapsed by default.
4. Project.
5. Assignee.
6. Repeat.
7. Notes.
8. Checklist.
9. Edit only: the existing managed Activity log, retained as an unchanged read-only
   section after Checklist.
10. Sticky Cancel plus the single Save action.

Project, Assignee, and Repeat are compact label/value rows with a semantic leading
icon and a chevron when interactive. Notes and Checklist use disclosure rows. Thin
dividers, not permanent outlined containers, separate rows. The normal Add/Edit
screen has no attention banner.

Schedule, Notes, and Checklist form one accordion: opening one collapses the other
two. Opening or closing a section is presentation state only and never makes a draft
dirty. Project, Assignee, and Repeat open a selection surface instead of becoming
accordion sections.

The sticky primary label is `Save HH:mm–HH:mm` when a complete schedule exists and
`Save task` when the selected non-self assignee has no schedule. A cross-midnight
summary must be unambiguous, for example
`Today · 23:30–Tomorrow 00:30 · 1 hr`.

## Schedule

The collapsed Schedule row displays one derived summary:

- complete: `Today · 10:00–10:30 · 30 min`;
- incomplete for a self-assigned task: `Set date, time & duration`;
- valid unscheduled non-self task: `No schedule`.

Tapping the row expands Date, Start time, and Duration groups in the same visual
language. Each group has three adaptive quick choices reachable in one tap and at
most one horizontal swipe, plus a compact trailing icon-only Custom control with a
semantic label (`Custom date`, `Custom start time`, or `Custom duration`). Quick
choices mutate only the local task draft, leave Schedule expanded, recompute the
summary/end, clear the affected validation highlight, and invalidate a captured retry
request.

Suggestion calculation is pure and receives the current draft plus an injected clock;
no composable reads the system clock directly.

- **Date:** selected/current date, tomorrow, and the next useful work-day boundary,
  deduplicated by date and user-facing label.
- **Start time:** selected time and nearby likely five-minute slots; with no selected
  time, round the injected current time up to a five-minute slot and offer nearby
  future slots.
- **Duration:** canonical nearby values that always include the selected duration;
  `15 min`, `30 min`, and `1 hr` are the initial 30-minute choices.

Selecting `No schedule` is available only for a non-self assignee. It clears the
date/time/duration in the local draft and changes Repeat to `Does not repeat`; it
does not call Todoist until Save. An unscheduled task cannot repeat. Changing from a
self assignee to a non-self assignee retains an existing schedule. Changing back to
self never invents one: Save expands Schedule and highlights the missing controls.

### Precise dialogs

The repository already uses `WheelPickerCompose`. The redesigned editor splits the
combined date-time field into three centered `AlertDialog` wheel pickers:

- Custom date: date wheel seeded from the draft/current date.
- Custom start time: time wheel in five-minute increments, seeded from the
  draft/current time.
- Custom duration: Hours/Minutes wheels, 0–6 hours and five-minute minute steps;
  zero remains invalid.

Each dialog owns a pending value. `Apply` writes to the form draft; Cancel and outside
dismissal discard the pending value. Spinning a wheel therefore never changes the form
until Apply.

## Project And Assignee

Project and Assignee have the same draft-only behavior in Add and Edit. A selection
sheet dismisses immediately after a selection, but no Todoist request occurs until the
single Save action. A failure keeps the entire draft and its retry request in the
editor.

Project opens a bottom sheet with optional search and a selectable project list. A
selection persists the preferred project and recalculates Assignee:

- If the current assignee remains valid, retain it.
- Otherwise select `Me` when the new project permits it; otherwise clear it and show
  the Assignee requirement on Save.

Assignee opens an immediate-selection bottom sheet. Its options are the current user
and active Todoist collaborators for the selected shared project, with a primary name,
small identity hint, and trailing selected check. The client must use fresh or cached,
previously validated membership. If no such membership is available, disable the row,
show retry, and block only a save that cannot validate the required selection.

A personal/non-assignable project renders `Me` as a locked value, with a small
Personal-project hint. Its Todoist request remains unassigned (`assigneeId = null`),
because the current user is the implicit owner. A shared project displays a selectable
assignee. The backend validates that a shared-project assignee is a current project
member; it never trusts an arbitrary client-provided ID.

Tasks assigned to the current user require title, project, assignee, start, and
positive duration. Tasks assigned to another collaborator require title, project, and
assignee but may be unscheduled. Validation is a concise error at Schedule level:
Save auto-expands Schedule and highlights the missing controls rather than repeating
per-control messages.

## Repeat, Notes, And Checklist

Repeat opens a compact bottom sheet with `Does not repeat`, `Daily`, `Weekdays`,
`Weekly`, `Monthly`, and `Yearly`; the selected row has a trailing check and applies
to the draft immediately. For an existing custom recurrence, choosing a supported
preset replaces it in the draft without a confirmation. An unscheduled non-self task
is fixed to `Does not repeat` until a schedule is set.

Notes is collapsed when blank. Its collapsed row shows a one-line ellipsized preview
when present; expanding it reveals a quiet multi-line editor without a permanent heavy
outline.

Checklist is collapsed by default and shows progress, for example
`Checklist · 2/5 done`. Expanded rows keep the existing checkbox, editable title,
optional details, removal, child-mutation ordering, and 30-item maximum. Reorder and
child schedules remain unsupported.

## State, Errors, And Accessibility

- The title, schedule, project, assignee, repeat, notes, and checklist all contribute
  to a domain draft fingerprint; accordion/sheet/dialog visibility does not.
- A captured create/update request is invalidated whenever a domain field changes.
  Retry reuses the unchanged request/action ID.
- Project and Assignee data are local draft selections; a multi-field save is one
  reconciled mutation, never a sequence of immediate mutations.
- A stale, pending, or network failure leaves the editor open and preserves the full
  draft. Pending uses the current reconciliation copy rather than pretending success.
- Every icon-only control has a content description. Dialogs expose Apply and Cancel.
  The sticky action bar stays reachable above IME and system navigation.
- Back and Cancel use the current clean/dirty/in-flight discard policy.

## Architecture Boundaries

Do not broaden the current shared `CommitmentEditor`, because it is also used by
Morning Review repair and blocker reschedule. The Add/Edit-only redesign introduces
focused components while preserving that editor for those protected surfaces:

- `TaskEditorScreenScaffold`: Add/Edit layout, sticky actions, and section state.
- `ScheduleEditor`: summary, quick-choice groups, validation focus, and dialog state.
- `ScheduleQuickChoices`: pure candidate generation, formatting, and reducers.
- `ProjectSelectionSheet`, `AssigneeSelectionSheet`, and `RecurrenceSelectionSheet`:
  draft-selection surfaces.
- Separate date, time, and duration wheel-dialog primitives.

`AddTaskScreen` owns project loading/preferred-project persistence, the Add draft,
validation, create retry, and returned authoritative snapshot. `EditTaskScreen` owns
the existing root/checklist draft, project data, update retry, and returned authoritative
snapshot. The existing `RootTaskPatch` already supports `projectId` and `assigneeId`;
the edit flow must populate both rather than introducing another endpoint.

The backend extends the existing `/projects` read model with synced collaborator
membership. It extends, rather than replaces, the existing create/update mutation
contracts so unscheduled non-self tasks and explicit schedule clearing remain
representable. The Todoist Sync adapter remains the only source for project/member
facts.

## Verification Expectations

The implementation plan must add or migrate coverage in the same change:

- Backend tests for collaborator/member sync, `/projects` projection, invalid
  cross-project assignment rejection, scheduled/unscheduled create and update, and
  Todoist `due`/`duration` clearing.
- Shared DTO serialization tests for collaborator data and explicit nullable schedule
  patches.
- JVM tests for quick-choice generation/deduplication/rounding, cross-midnight summary,
  draft reducers, self-vs-non-self validation, and dialog Apply/Cancel behavior.
- Add/Edit instrumentation tests for the collapsed Schedule, quick choices, custom
  picker confirmation/cancellation, draft-only Project/Assignee edits, optional
  non-self schedule, recurrence, Notes/Checklist accordion behavior, save, retry, and
  dirty exit.
- Regression coverage proving Morning Review repair and blocker reschedule retain the
  existing combined picker/editor layout.
- `:shared:testAndroidHostTest`, `:androidApp:testDebugUnitTest`,
  `:androidApp:assembleDebug`, and `scripts/e2e-fake-backend.sh` after UI changes.
  Run `scripts/e2e-real-backend.sh` as the complementary harness; its known
  `[partial-failure]` fake-only discrepancy must remain documented unless this feature
  introduces a new real-backend failure.

## Design Self-Review

- Add and Edit have equal editing capability; no row advertises an unavailable action.
- Schedule has one source of truth: the draft. The collapsed summary, end, and Save
  label are derived values.
- The design does not expose time zones or weaken self-assigned commitment rules.
- Non-self unscheduled tasks are explicit Todoist-visible data, not a client-only
  exception; the backend validates membership and schedule requirements.
- Repair and blocker reschedule are intentionally protected from this visual redesign.
