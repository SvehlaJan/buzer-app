# Task Editor Redesign

Date: 2026-07-21

## Status

Design direction accepted in conversation; this written spec is the review gate before implementation planning. The supporting mockups are kept together in `docs/designs/2026-07-21-task-editor-redesign/` so they can be revised without losing the current direction.

## Purpose

Make Add Task and Edit Task fast to scan and practical on a phone. The current form repeats schedule information in a receipt and in separate inputs, uses space heavily, and makes recurrence consume the screen. The redesigned form presents the current choice in compact collapsed rows and exposes only the controls needed for the current decision.

This is a UI and interaction redesign. It must preserve the existing strict-task validation, create/update APIs, reconciliation behaviour, draft-exit protection, and alarm scheduling semantics.

## Supporting Mockups

| Mockup | Role |
| --- | --- |
| `01-schedule-inline-options.png` | Expanded schedule section with adaptive quick choices and icon-only custom controls. |
| `02-schedule-custom-wheel-dialog.png` | Precise wheel-based schedule dialog concept. |
| `03-collapsed-task-editor.png` | Main form hierarchy with Schedule first and collapsed. |
| `04-repeat-picker.png` | Recurrence selection sheet. |
| `05-notes-and-checklist.png` | Expanded Notes and Checklist sections. |
| `06-project-selector.png` | Project-selection sheet. |
| `07-assignee-selector.png` | Assignee-selection sheet. |

Mockup text and layout are design references, not test fixtures or a source of API truth.

## Scope

In scope:

- Add Task and Edit Task visual hierarchy and form interactions.
- A collapsed/expanded Schedule section with adaptive fast choices.
- Separate precise date, time, and duration dialogs based on the already-installed `WheelPickerCompose` dependency.
- Compact Project, Assignee, Repeat, Notes, and Checklist sections.
- Matching JVM and Android instrumentation coverage for the redesigned paths.

Out of scope:

- Changing the task lifecycle, alarm plan, Todoist mutation semantics, or validation rules.
- Redesigning Morning Review repair and blocker reschedule forms in this change. They currently share `CommitmentEditor`; they must retain their current layout unless an explicitly scoped follow-up migrates them.
- Editing project or assignee through the existing Edit Task API. The current `UpdateTaskRequest.RootTaskPatch` does not carry either field. The edit screen may render them as read-only context in this change; making them editable requires a separate backend/API design and tests.
- Persisting a new assignment representation for personal projects. The UI should present the accountable owner clearly, but any conflict between the current personal-project request contract and the product rule that every new task has an assignee must be resolved before changing backend behaviour.

## Main Form

The screen has one scrollable content column and a sticky bottom action bar. Its visual sequence is:

1. Back affordance and `Add task` or `Edit task` title.
2. Single-line task title input.
3. **Schedule** — first data section, collapsed by default.
4. Project.
5. Assignee.
6. Repeat.
7. Notes.
8. Checklist.
9. Sticky `Cancel` plus the existing calculated save action.

The Schedule row displays one summary rather than a receipt plus duplicate fields. When complete it reads in the shape `Today · 10:00–10:30 · 30 min`. If incomplete, it gives a short action-oriented prompt such as `Set date, time & duration`; validation remains tied to the individual missing value.

Project, Assignee, and Repeat are compact label/value rows with a leading semantic icon and a trailing chevron only when interactive. Notes and Checklist show a disclosure affordance and a useful summary (`Checklist · 2 items`). Thin dividers, not permanent outlined containers, separate rows.

The redesign removes the top attention banner from normal Add/Edit forms. Health and morning-review attention belong to their established surfaces rather than ordinary task editing.

## Schedule Interaction

Tapping the collapsed Schedule row expands it in place below the summary. It contains Date, Start time, and Duration groups in the same visual language:

- a concise section label;
- adaptive quick-choice buttons arranged in a horizontally scrollable row when necessary;
- a compact 48 dp icon-only button at the end for the precise custom picker;
- a quiet derived `Ends at …` label after Duration.

The quick path must be usable with a single tap and at most one horizontal swipe. The custom icon has an accessible text alternative (`Custom date`, `Custom start time`, or `Custom duration`) even though it has no visible label.

### Adaptive quick choices

Suggestion calculation is pure and receives the current draft plus a supplied clock. It never reads the system clock inside a composable, which keeps the UI deterministic in tests.

- **Date:** include the selected date or current default, then nearby useful dates such as tomorrow and the next work-week boundary; avoid duplicate labels/dates.
- **Start time:** include the selected time and neighbouring sensible slots. With a selected 10:00, the primary candidates are 09:30, 10:00, and 10:30. With no selected time, start from the current time rounded up to the app's five-minute precision and offer nearby future slots.
- **Duration:** choose from the supported canonical minute values, always including the current value and its closest useful alternatives. For a 30-minute selection, the initial candidates are 15 min, 30 min, and 1 hr.

Selecting a quick choice updates the draft immediately, closes no separate dialog, recomputes the Schedule summary and end time, clears only the affected validation error, and invalidates any pending create/update request as current code already does.

### Precise custom dialogs

The repository already includes `com.github.commandiron:WheelPickerCompose` and wraps it in `WheelDateTimeField` and `WheelDurationField`. The redesign splits those combined interactions into precise, draft-based dialogs:

- **Custom date:** a date wheel dialog, seeded from the current schedule draft.
- **Custom start time:** a `WheelTimePicker` dialog, seeded from the current time and retaining the existing five-minute precision.
- **Custom duration:** the existing two-wheel Hours/Minutes model, with 0–6 hours and five-minute minute steps. Zero remains invalid.

Each dialog holds a local pending selection. `Apply` writes that selection back to the task draft; `Cancel` and outside dismissal discard it. This means spinning a wheel never mutates the form until confirmation. The existing component can be refactored into reusable wheel-dialog primitives; it must not keep one combined DateTime dialog behind both the date and time controls.

## Project And Assignee

### Add Task

Project opens a bottom sheet with an optional search field and a plain selectable project list. A tap selects immediately, persists the existing preferred-project setting, dismisses the sheet, and refreshes the Assignee state. The selected row has a single trailing check rather than a radio button or action footer.

Assignee uses the same immediate-selection sheet pattern, with an avatar/initial, primary name, a small identity hint, and one trailing selection check. It must clearly communicate the owner of the task instead of rendering `Not required` in the normal form. When the selected project does not allow a Todoist collaborator choice, the form presents the account owner as a non-interactive value rather than an empty requirement; the backing request semantics remain unchanged until the product-contract audit described in Scope is complete.

For assignable projects, changing project clears an assignee that is no longer valid and directs focus/validation to Assignee before saving. It must not silently submit an invalid selection.

### Edit Task

In this rework, Project and Assignee rows show authoritative task context. They are read-only unless the implementation plan also introduces a reviewed API mutation for those fields. A read-only row has no chevron and never implies an unavailable action. This intentionally differs from the exploratory mockup's chevrons and avoids a deceptive UI.

## Remaining Sections

### Repeat

Repeat opens a short selection sheet with `Does not repeat`, `Daily`, `Weekdays`, `Weekly`, `Monthly`, and `Yearly`. The selected row gets a trailing check. The choice applies on tap and returns to the form. There are no numbered labels or stacked full-width buttons. Existing custom recurrence copy remains visible when relevant and changing it still follows current behaviour.

### Notes

Notes is collapsed when blank. Tapping it expands a quiet multiline editor with no heavy permanent outline; its content is part of the normal task draft and uses the existing validation/draft-exit behaviour.

### Checklist

Checklist is collapsed by default and shows its item count. When expanded, it presents task-like rows: checkbox, title, optional reorder affordance, and overflow/delete action, followed by an inline `Add item` control. Existing checklist validation, child-mutation ordering, and max-item rules remain unchanged.

## Architecture Boundaries

Do not broaden `CommitmentEditor` until its use by Add, Edit, repair, and blocker reschedule paths has been deliberately separated. The recommended structure is:

- `TaskEditorScreenScaffold`: Add/Edit-only content arrangement, sticky actions, and expandable sections.
- `ScheduleEditor`: pure state-facing summary, adaptive-choice groups, and custom-dialog coordination.
- `ScheduleQuickChoices`: pure candidate calculator and formatters with JVM tests.
- `ProjectSelectionSheet`, `AssigneeSelectionSheet`, and `RecurrenceSelectionSheet`: small, add-flow selection surfaces.
- Existing wheel wrappers refactored into date, time, and duration dialog primitives.

`AddTaskScreen` remains the owner of loaded projects, preferred-project persistence, assignee options, validation, and create retry state. `EditTaskScreen` remains the owner of its root/checklist draft and update retry state. Existing `DraftExitPolicy` fingerprints must include the same domain fields and must not treat disclosure/dialog visibility as a change.

## Errors, Accessibility, And State

- Validation appears beside or directly below the affected collapsed/expanded row; submitting an incomplete form expands Schedule if start or duration is invalid.
- Loading/failed project fetch behaviour remains visible and retryable; selection controls are disabled while the data needed for them is unavailable.
- Every icon-only control has a semantic content description.
- Dialogs use explicit `Apply` and `Cancel` controls and preserve the current draft until Apply.
- The sticky action bar must remain reachable above system navigation and IME content.
- Android Back and Cancel retain the existing clean/dirty/in-flight discard policy.

## Verification Expectations

The implementation plan must add or migrate coverage in the same change:

- JVM tests for date/time/duration candidate generation, deduplication, rounding, end-time formatting, and custom dialog draft/apply/cancel reducers.
- Existing picker tests migrated from combined DateTime behaviour to split date/time behaviour.
- Add Task instrumentation coverage for collapsed schedule summary, quick choice updates, custom-picker confirmation/cancellation, project selection, assignee requirement, recurrence selection, and dirty-exit behaviour.
- Edit Task instrumentation coverage for read-only Project/Assignee context, schedule editing, repeat/notes/checklist expansion, save, and dirty exit.
- Regression coverage for Morning Review repair and blocker reschedule so their current editor paths do not change accidentally.
- The complete deterministic `scripts/e2e-fake-backend.sh` lane after UI changes, in addition to `:shared:testAndroidHostTest`, `:androidApp:testDebugUnitTest`, and `:androidApp:assembleDebug`.

## Design Self-Review

- No placeholder decisions remain for this UI rework. The only intentionally deferred capability is editing project/assignee from Edit Task because current API models do not support it.
- The form has one source of schedule truth: the Schedule draft. The collapsed summary, end time, and save label are derived displays.
- The design preserves all strict-task enforcement and does not weaken assignment, deadline, or duration validation.
- The implementation scope is limited to Add/Edit surfaces and shared leaf widgets, preventing accidental redesign of repair/reschedule screens.
