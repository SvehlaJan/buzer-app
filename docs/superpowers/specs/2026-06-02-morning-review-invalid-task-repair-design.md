# Morning Review Invalid Task Repair Design

Date: 2026-06-02

## Goal

Make morning review useful for the current MVP by showing invalid assigned-to-user tasks and letting the user repair missing commitment fields without leaving the blocker.

## Scope

In scope:

- Android morning review blocker UI.
- Invalid task list from the latest backend snapshot.
- Repair form for one invalid task at a time.
- Native Material3 date picker plus time picker for repaired deadline.
- Duration minutes input for repaired duration.
- Existing `POST /tasks/:id/reschedule` as the repair action.
- Existing `POST /review/dismiss` as the backend-visible snooze action.
- Fake-backend instrumentation coverage.

Out of scope:

- Reassign, delete, or complete invalid tasks from morning review.
- Overdue task repair.
- Health warning repair actions.
- Calendar blocking.
- Todoist webhooks or push invalidation.
- Shared reusable picker component extraction.

## User Experience

The morning review screen shows:

- `Morning review`
- invalid task rows with title and validation messages
- `Repair` button per invalid task
- `Snooze 5 min`

When there are no invalid tasks, it shows `No invalid tasks`.

Tapping `Repair` opens an inline repair form for that task:

- `Repair task`
- the task title
- deadline picker button with content description `Repair deadline picker`
- `No deadline selected` before selection
- formatted local date/time after selection
- `Duration minutes`
- `Save repair`
- `Cancel`

The deadline picker behavior matches add-task and deadline-reschedule:

- date defaults to today
- time defaults to the next local hour when no previous selection exists
- confirming date opens time picker
- confirming time stores a local `DateTimeSelection`

## Validation And Actions

Local validation:

- missing deadline shows `Deadline is required`
- missing, non-numeric, or non-positive duration shows `Duration must be a positive whole number of minutes`
- local validation errors keep the blocker visible

Successful repair:

- Android calls `POST /tasks/:id/reschedule` with ISO instant deadline and duration minutes.
- The repaired task is removed from the in-memory morning review list for immediate progress feedback.
- If no invalid tasks remain after repair, the activity hides.

Repair backend failure:

- The activity hides.
- `BlockerErrorReporter` logs `RESCHEDULE_TASK` for the repaired task.
- The standard backend failure notification path remains active.

Snooze:

- Android calls `POST /review/dismiss`.
- Android schedules a local morning review alarm for five minutes later.
- The activity hides.

Snooze backend failure:

- Android still schedules the local five-minute morning review alarm.
- The activity hides.
- `BlockerErrorReporter` logs `MORNING_REVIEW_DISMISS`.

## Data Flow

`MorningReviewActivity` creates an `EnforcementCoordinator`, refreshes the backend snapshot, stores/reconciles alarms through the existing coordinator path, reduces the snapshot with `SnapshotReducer`, and passes invalid tasks into `MorningReviewScreen`.

`MorningReviewScreen` stays UI-only. It owns transient form state and calls:

- `onRepair(taskId, RescheduleTaskRequest)`
- `onSnooze()`
- `onBack()`

`EnforcementCoordinator` gains `dismissMorningReview()` and uses `BuzerApiClient.dismissMorningReview()` through `EnforcementApi`.

## Testing

Unit or compile checks:

- Android unit tests must pass.
- Android instrumentation test APK must build.
- Fake backend tests must cover `POST /review/dismiss` recording and forced failure behavior.

Instrumentation tests:

- Launch `MorningReviewActivity` with fake backend.
- Verify invalid fixture task title appears.
- Verify missing repair deadline shows `Deadline is required`.
- Verify selecting deadline and duration posts `/tasks/task-invalid/reschedule`.
- Verify repair backend failure hides the activity and logs `RESCHEDULE_TASK`.
- Verify snooze posts `/review/dismiss`, schedules/hides the activity, and uses fake backend.

Final verification:

- `cd tools/fake-backend && npm test`
- `cd client && ./gradlew :androidApp:testDebugUnitTest`
- `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- fake backend on port `18433`
- `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
