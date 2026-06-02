# Deadline Reschedule Native Picker Design

Date: 2026-06-02

## Goal

Migrate the deadline blocker reschedule form from text deadline entry to the same native date/time picker behavior used by Android add-task creation.

## Scope

In scope:

- Deadline blocker reschedule form deadline field.
- Native Material3 `DatePickerDialog`.
- Native Material3 `TimePicker` wrapped in `AlertDialog`, matching the current add-task implementation.
- Reuse `DateTimeSelection` conversion and formatting helpers.
- Keep duration as a numeric text field.
- Update native fake-backend instrumentation tests.

Out of scope:

- Shared reusable `DateTimePickerField` extraction.
- Prefilling the current task deadline/duration from the snapshot.
- Backend changes.
- Add-task changes.

## UX

The reschedule form shows:

- `Reschedule task`
- deadline picker button with content description `New deadline picker`
- `No deadline selected` before selection
- formatted local date/time after selection, for example `2026-06-02 18:30`
- `Duration minutes`
- `Save reschedule`
- `Cancel`

Tapping the deadline button opens a date picker. Confirming the date opens a time picker. Confirming the time stores the selected local deadline.

Default picker values:

- date defaults to today
- time defaults to the next local hour when no previous selection exists
- if a deadline was already selected, reopening starts at that selected deadline

## Validation And Backend Behavior

Validation changes:

- missing deadline returns `Deadline is required`
- invalid or non-positive duration keeps the existing message: `Duration must be a positive whole number of minutes`

On valid submit, Android builds `RescheduleTaskRequest` using `DateTimeSelection.toIsoInstantString()`.

Existing blocker behavior remains unchanged:

- success hides the blocker
- backend failure hides the blocker and logs `RESCHEDULE_TASK` through the existing coordinator/error reporter path
- invalid local input keeps the form visible

## Testing

Update instrumentation tests to:

- verify invalid reschedule shows `Deadline is required` when no picker selection was made
- select default date/time through the native dialogs and assert `/tasks/:id/reschedule` is recorded
- select default date/time through the native dialogs and assert backend failure hides and logs

Final verification:

- `cd client && ./gradlew :androidApp:testDebugUnitTest`
- `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- fake backend on port `18433`
- `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
