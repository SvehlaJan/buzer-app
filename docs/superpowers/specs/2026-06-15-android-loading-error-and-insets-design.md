# Android Loading, Error, and Insets Design

Date: 2026-06-15

## Goal

Make Android app operations visibly responsive and failure states hard to miss. The app must always
show immediate feedback when it is loading, refreshing, submitting, or performing blocker actions.
The same UI pass must also fix modern Android system-bar overlap so content is not covered by the
status bar or navigation bar.

This design covers the Android client only. Backend API behavior is unchanged.

## Product Rules

- Every operation shows visible feedback immediately.
- Required data loads block the affected content until data is available, failed, or retried.
- Background refresh keeps existing content visible and shows a non-blocking status.
- User-triggered actions show button-level progress and prevent duplicate taps.
- User-triggered failures and required-load failures show alert dialogs with recovery actions.
- Passive/background refresh failures show persistent status or warning rows, not modal dialogs.
- Screens must respect Android system insets. No readable or actionable UI may sit under the status
  bar, navigation bar, cutouts, or gesture areas.

## Android System UI Requirement

The app targets Android SDK 35, where Android 15 enforces edge-to-edge behavior. Official Android
docs state that the status bar becomes transparent by default and content can draw behind system
bars unless insets are applied.

The app remains visually simple and full-screen where appropriate, but the content layout must
apply safe drawing/system-bar padding. Blocker activities may still use full-screen windows, but
their titles, messages, and buttons must be inset-safe.

## Architecture

Use a lightweight shared UI status layer instead of independent ad hoc loading text on each screen.
This layer provides reusable concepts and components, not a heavy app-wide state machine.

Recommended building blocks:

- `OperationStatus`: idle, loading, failed, or success-like complete state for a specific operation.
- `ScreenLoadState<T>`: loading, ready with data, failed with no usable data, or ready with warning.
- Reusable UI components:
  - screen loading state
  - inline loading/status row
  - warning/error banner with Retry
  - alert dialog for blocking/user-triggered failures
  - action button with loading label/disabled state
  - inset-safe root container for normal screens and blocker screens

The design must fit existing Compose code and avoid introducing a full navigation framework or
global store.

## Home Behavior

Home depends on the backend task snapshot.

Initial state:

- If no snapshot is available and refresh is running, Home shows a screen-level loading state.
- It must not render zero counts as if real data were loaded.

Refresh with existing data:

- Keep the current snapshot-derived counts and task sections visible.
- Show an immediate non-blocking status row such as `Refreshing...`.
- If refresh succeeds, remove the status row and update the data.

Refresh failure with existing data:

- Keep stale data visible.
- Show a persistent warning row with Retry.
- The warning must be explicit enough to explain that data may be stale.

Refresh failure with no data:

- Show a blocking error state with Retry.
- Do not show fake zero counts.

## Add Task Behavior

Add Task cannot be used without projects because project selection is mandatory.

Project initial load:

- Show immediate loading feedback in the project picker area.
- Disable Save while projects are loading.
- Keep the rest of the form visible if it is useful, but do not let the user submit.

Project load failure:

- Show an alert dialog with Retry and Dismiss.
- Show an inline Retry action on the screen after the dialog is dismissed.
- Save remains disabled until projects load successfully and a project is selected.

Project retry:

- Show immediate loading feedback.
- Clear old project-load errors while retrying.

Task save:

- Save button changes to a loading label such as `Saving...`.
- Disable duplicate submit and form controls that could change the outgoing request during
  submission.
- On success, keep the existing navigation behavior back to Home and trigger enforcement refresh.

Task save failure:

- Show an alert dialog with Dismiss and Retry Save.
- Retry Save reuses the currently valid request if the form still validates; otherwise it surfaces
  the validation error inline.

## Morning Review Behavior

Morning Review may have cached invalid tasks and then perform a fresh backend refresh.

Initial refresh:

- If cached invalid tasks exist, show them immediately and show a `Refreshing...` status row.
- If no cached tasks exist, show screen-level loading while refresh runs.

Refresh failure:

- If cached data exists, keep it visible and show a warning banner.
- If no usable cached data exists, show an alert dialog and a blocking error state with Retry.

Repair/Snooze actions:

- The tapped action shows immediate loading feedback.
- Prevent duplicate taps while the action is in flight.
- On failure, show an alert dialog with Retry and Dismiss.

## Start and Deadline Blocker Behavior

Blockers must be visually decisive, but still show network/action state clearly.

Before blocker content:

- While the activity refreshes and reconciles state before rendering actions, show
  `Checking task state...`.
- If the task resolves during refresh, close the blocker as current behavior already does.
- If refresh fails, report the error and show the blocker with an explicit warning that state may be
  stale.

Actions:

- Start, Postpone, Done, Extend, and Reschedule show immediate action-level loading once tapped.
- All other blocker actions are disabled while one action is in flight.
- On success, keep current finish/dismiss behavior.

Action failure:

- Show an alert dialog with Retry and Dismiss.
- Retry repeats the same action.
- Dismiss follows the existing fail-and-hide behavior only after the failure has been reported
  through the app's error reporting path. The user should not be silently trapped without feedback.

## Error Presentation Policy

Use alert dialogs for:

- project list load failure on Add Task
- task save failure
- Morning Review required-load failure with no cached data
- Morning Review repair/snooze action failure
- blocker action failure

Use persistent inline warning/status rows for:

- Home background refresh failure when stale data exists
- Morning Review background refresh failure when cached data exists
- non-blocking refresh status such as `Refreshing...`

All alerts should avoid raw stack traces. They may include concise backend/network details such as
`Network request failed`, `Backend unavailable`, or the HTTP error message when it is user useful.

## Testing Strategy

Unit tests:

- reducers/state builders distinguish loading, ready, failed-with-data, and failed-without-data.
- Add Task project load failure and retry state.
- Add Task submit loading/failure retry behavior.
- Home does not render zero counts before first snapshot.
- blocker action state prevents duplicate action taps.

Android/UI tests where practical:

- Add Task shows loading and Retry for project load failure.
- Home shows initial loading before snapshot and stale-data warning after refresh failure.
- blocker buttons become disabled and show loading after tap.
- root screens respect safe drawing insets.

Manual verification:

- real device on Android 15+ confirms content is not covered by the status bar.
- real backend/Tailscale-off path confirms Add Task project load failure is visible and recoverable.

## Out of Scope

- Redesigning visual style beyond what is needed for status/loading/error clarity.
- Replacing the navigation structure.
- Introducing push/realtime backend invalidation.
- Changing backend endpoints.
- Changing Todoist task validation rules.
