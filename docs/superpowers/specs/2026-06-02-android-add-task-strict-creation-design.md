# Android Add Task Strict Creation Design

Date: 2026-06-02

## Goal

Make the Android add-task screen create strict Todoist commitment tasks through the backend. The screen must be frictionless enough for daily use while still enforcing the MVP rule that companion-created tasks always have title, assignee, exact deadline, and expected duration.

## Scope

In scope:

- Android add-task UI.
- Fixed assignee choices: `Me` and `Wife`.
- Default Todoist project by sending `projectId = null`.
- Native Compose Material3 date and time pickers for exact deadline selection.
- Local validation before backend submission.
- Backend submission through existing `POST /tasks`.
- Fake-backend instrumentation tests for successful create and backend failure.

Out of scope:

- Project picker.
- Collaborator/project metadata endpoints.
- Offline pending-create queue.
- Widget-specific UI changes.
- Replacing the existing reschedule text deadline field; that can reuse the picker helper in a follow-up slice.

## Product Rules

Every task created through the companion must have:

- non-blank title
- selected assignee
- exact deadline
- positive integer duration

The add-task screen uses two fixed assignee buttons:

- `Me`, backed by `BUZER_ASSIGNEE_ME_ID`
- `Wife`, backed by `BUZER_ASSIGNEE_WIFE_ID`

Both IDs are Android build-config values loaded from Gradle properties or `client/local.properties`. If the chosen assignee ID is blank, saving stays on the form and shows an inline configuration error.

Tasks created for either assignee carry the chosen deadline and duration in this MVP. The backend may later decide which assignee requires strict commitment fields, but the companion creation flow always supplies them.

## Deadline Picker UX

The screen displays a deadline field as a button-like row:

- before selection: `No deadline selected`
- after selection: formatted local date/time, for example `2026-06-02 18:30`

Tapping the field opens a Material3 `DatePickerDialog`. Confirming the date opens a Material3 `TimePickerDialog`. Confirming the time stores a local date/time selection.

Default picker values:

- date defaults to today
- time defaults to the next local hour when no previous selection exists
- if a deadline was already selected, the dialogs reopen at that selected date/time

On save, the local date/time is converted using `ZoneId.systemDefault()` to an ISO instant string for `CreateTaskRequest.dueDatetime`.

## Save Behavior

Save has three states:

- idle: button enabled
- submitting: button disabled and text changes to `Saving...`
- failed: form remains visible, inputs are preserved, and an inline backend error is shown

On success, the screen navigates back to home. The backend response body is not needed by Android for this slice because the next snapshot refresh will pull the created task.

## Architecture

Add a small Android-only date/time helper package:

- `DateTimeSelection`: stores `LocalDate`, hour, and minute
- conversion helper to ISO instant string
- display formatter for the selected local deadline
- helpers for converting Material3 date-picker millis into `LocalDate`

Add-task UI stays in `AddTaskScreen`, with dependencies injected through parameters so instrumentation tests can use the real `BuzerApiClient` path while JVM/Compose tests can pass fake callbacks where useful.

Build config adds:

- `BUZER_ASSIGNEE_ME_ID`
- `BUZER_ASSIGNEE_WIFE_ID`

The fake backend adds:

- `POST /tasks`, which records the request body and returns a deterministic created task response
- forced failure support through the existing `setFailActions(true)` test hook

## Testing

JVM tests cover:

- converting `DateTimeSelection` to UTC ISO instant with a fixed zone
- formatting selected local deadline text
- add-task form validation for missing title, missing assignee, missing deadline, blank configured assignee ID, and invalid duration

Native instrumentation tests cover:

- successful app flow: Home -> Add task -> fill fields -> choose default date/time through native dialogs -> Save -> fake backend records `POST /tasks`
- backend failure: same form flow with forced fake backend failure -> form stays visible, entered title remains, backend error appears

Verification commands:

- `cd client && ./gradlew :androidApp:testDebugUnitTest`
- `cd tools/fake-backend && npm test`
- `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- fake backend on port `18433`
- `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`

## Follow-Up

After this slice, migrate deadline reschedule to reuse the native picker helper so add-task and reschedule have the same deadline UX.
