# Full Task Edit Repair Design

Date: 2026-07-12

## Goal

Morning Review repair should become a full task edit flow. When a task is invalid, the user must be able to fix every Todoist-owned field from the same screen: title, details, project, assignee, exact time, and duration.

## Requirements

- Repair mode must show project names, not Todoist project IDs.
- Repair mode must show assignee labels such as `Me`, not raw assignee IDs when a configured label exists.
- Repair mode must allow editing title, details, project, assignee, start time, and duration.
- Shared projects require an assignee before save.
- Projects that cannot assign tasks must hide assignee selection and send no assignee.
- Saving edit changes must update Todoist-owned task fields through the backend and refresh/reconcile the Android snapshot afterward.
- Deadline blocker reschedule remains schedule/details-only unless later product work explicitly asks for full edit there too.

## API Design

Add a full task update request alongside the existing create and reschedule requests.

```kotlin
data class UpdateTaskRequest(
    val title: String,
    val description: String? = null,
    val projectId: String,
    val assigneeId: String? = null,
    val dueDatetime: String,
    val durationMinutes: Int
)
```

Android sends this to `POST /tasks/:id/update`. The backend validates the same practical constraints as create, calls Todoist `item_update`, updates the local mirror, resets the commitment state to planned, increments the schedule version, and returns `{ ok: true }`.

## UI Design

Morning Review keeps its invalid-task card list. Tapping `Repair` opens the shared commitment editor in edit mode with:

- editable title
- editable details
- project dropdown populated from `/projects`
- assignee buttons populated from configured assignee options when the selected project supports assignment
- start picker and duration picker
- computed save label

The form is initialized from the selected invalid task. If the project ID from the snapshot is not present in the loaded project list, the editor shows a project-required validation when saving instead of showing the raw ID as a display label.

## Verification

Focused automated checks are required:

- shared JSON serialization for `UpdateTaskRequest`
- fake-backend route records `/tasks/:id/update`
- backend service test records Todoist update fields
- Android Morning Review state test verifies project and assignee display-name resolution
- Android JVM tests for request construction

The full emulator e2e lane is intentionally skipped for this iteration because manual device testing is requested.
