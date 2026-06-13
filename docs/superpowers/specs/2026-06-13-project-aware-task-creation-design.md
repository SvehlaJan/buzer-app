# Project-Aware Task Creation Design

Date: 2026-06-13

## Goal

Stabilize Android task creation by making Todoist project selection mandatory, adapting assignment rules to each project, and ensuring Android-created tasks become enforceable Buzer commitments without requiring explicit `null` payload fields.

This design covers the next implementation target only:

- backend task-create contract and Todoist create behavior
- backend project sync/API
- Android Add Task project picker and validation
- verification that Android-created tasks can drive real alarms without direct SQLite repair

It does not cover Home in-progress UI, duplicate blocker surface verification, or boot-recovery verification; those remain separate follow-up work.

## Current Problem

The previous Android Add Task flow sent `projectId = null` and required an assignee. In live verification, the backend accepted the request only after Android serialized explicit nulls, but the Todoist-created task later synced back with `due = null` and `responsible_uid = null`. The task became invalid and required direct backend SQLite repair to complete real-alarm verification.

The product rule now changes: Android must require selecting a Todoist project. All projects should be available, not only shared projects. If a selected project cannot assign tasks, the form should not require or send an assignee, but Buzer should still treat the task as the current user's commitment and enforce it.

## Project Model

The backend owns Todoist project capability detection. It syncs Todoist projects into a local project mirror and exposes active projects to Android.

Minimum stored project fields:

- backend/local ID if needed
- Todoist project ID
- name
- `shared`
- `canAssignTasks`
- inbox flag when available
- deleted flag
- archived flag
- raw Todoist JSON
- updated timestamp

`GET /projects` returns all active, non-deleted, non-archived Todoist projects. It includes `canAssignTasks` so Android can adapt the form before submission.

Example response shape:

```json
{
  "projects": [
    {
      "id": "6P34vmQCRRcCprG7",
      "name": "Family",
      "canAssignTasks": true,
      "shared": true,
      "isInboxProject": false
    }
  ]
}
```

## Task Creation Rules

Backend task creation becomes project-aware.

Request rules:

- `projectId` is mandatory.
- `title` is mandatory.
- `dueDatetime` is mandatory for all Android-created Buzer commitments.
- `durationMinutes` is mandatory for all Android-created Buzer commitments.
- If the selected project has `canAssignTasks = true`, `assigneeId` is mandatory.
- If the selected project has `canAssignTasks = false`, `assigneeId` must be omitted or null.

The backend schema accepts omitted or null optional fields and normalizes them internally. Malformed types return `400 validation_error`; valid JSON that violates business rules returns `422 task_validation_failed`.

## Commitment Ownership

Todoist assignment and Buzer enforcement ownership are separate concepts.

For assignable projects, Todoist assignment remains the source for who the task is assigned to. If the selected assignee is the current user, Buzer enforces the task for the current user.

For non-assignable projects, Todoist cannot store an assignee. When Android creates a task in such a project, the backend stores companion-owned commitment ownership for the current user, for example `commitment_owner_id = currentUserId`.

Snapshot behavior:

- `assigneeId` continues to reflect Todoist-visible assignment.
- `isAssignedToUser` is true when Todoist assignee is the current user or companion ownership is the current user.
- Alarm planning uses `isAssignedToUser`, so non-assignable personal commitments still schedule start and deadline blockers.

This keeps Todoist-visible data honest while allowing Buzer to enforce personal commitments in projects where Todoist assignment is unavailable.

## Todoist Create Strategy

Backend should create Android-originated tasks through Todoist REST v1 `POST /tasks` instead of Sync `item_add`.

For assignable projects, the backend sends:

- `content`
- `project_id`
- `assignee_id`
- `due_datetime`
- `duration`
- `duration_unit = "minute"`

For non-assignable projects, the backend sends:

- `content`
- `project_id`
- `due_datetime`
- `duration`
- `duration_unit = "minute"`

The backend verifies the returned Todoist task before returning success to Android:

- project ID matches the selected project
- assignee matches when an assignee was required
- due datetime persisted
- duration and duration unit persisted

If Todoist accepts creation but the returned task does not preserve required fields, the backend returns `422 todoist_create_verification_failed`. For MVP, the backend does not auto-delete partially created Todoist tasks because destructive cleanup is riskier than surfacing a clear repair path.

If REST create still returns incomplete fields in live testing, the next fallback is create-then-update via REST before failing.

## Android Add Task Flow

Android fetches projects on first Add Task screen open and keeps them in memory for the app process/session. It persists the selected project ID and preselects it on later opens when the project still exists.

Screen behavior:

- Show project loading state on first open.
- If project loading fails, disable Save and provide retry.
- Require selecting a project before Save.
- When the selected project has `canAssignTasks = true`, show the Me/Wife assignee picker and require selection.
- When the selected project has `canAssignTasks = false`, hide or disable assignee controls and create a personal Buzer commitment.
- Deadline and duration remain mandatory for every Android-created task.

Save payload behavior:

- Assignable project: send title, project ID, assignee ID, due datetime, and duration.
- Non-assignable project: send title, project ID, due datetime, and duration; omit `assigneeId` once backend accepts omitted nullable fields.

The temporary Android JSON setting that emits explicit nulls can be reverted after backend schemas normalize omitted/null fields.

## API Models

Shared Kotlin should add project DTOs:

```kotlin
@Serializable
data class ProjectItem(
    val id: String,
    val name: String,
    val canAssignTasks: Boolean,
    val shared: Boolean,
    val isInboxProject: Boolean
)

@Serializable
data class ProjectsResponse(
    val projects: List<ProjectItem>
)
```

`CreateTaskRequest.assigneeId` should become nullable or optional in the shared model so non-assignable project requests do not need fake assignment values.

## Error Handling

Expected backend errors:

- `400 validation_error`: malformed request JSON or wrong primitive types.
- `422 task_validation_failed`: project or commitment rule violation, such as missing assignee for an assignable project.
- `422 todoist_create_verification_failed`: Todoist create succeeded but did not return required persisted fields.
- `404 project_not_found`: selected project is unknown, deleted, or archived.

Android should keep the form content on create failures and show a concise error. It should not navigate back after a failed create.

## Testing

Backend route tests:

- `GET /projects` returns active projects and excludes deleted/archived projects.
- `POST /tasks` accepts omitted nullable fields.
- Assignable project requires `assigneeId`.
- Non-assignable project rejects a provided `assigneeId`.
- Non-assignable project creates a current-user commitment owner.
- Malformed JSON shape returns `400 validation_error`; business rule failures return `422 task_validation_failed`.

Todoist adapter tests:

- REST create sends `assignee_id` only when provided.
- REST create sends `project_id`, `due_datetime`, `duration`, and `duration_unit`.
- REST create maps and verifies returned task fields.
- Project sync maps `can_assign_tasks`, `shared`, deleted, archived, and inbox flags.

Android/shared tests:

- Project DTOs decode from backend JSON.
- Create request can omit null optional fields after backend support lands.
- Add Task validation requires project selection.
- Add Task validation requires assignee only when selected project can assign tasks.
- Add Task validation omits assignee for non-assignable projects.

Manual verification:

- Create a task in an assignable Todoist project and confirm the backend schedules alarms without SQLite repair.
- Create a task in a non-assignable Todoist project and confirm Buzer still schedules alarms as a personal commitment.
- Reopen Add Task and confirm the last selected project is preselected.

## Implementation Sequence

1. Add backend project mirror storage and project mapping tests.
2. Extend Todoist sync to fetch projects alongside tasks.
3. Add `GET /projects`.
4. Update backend task-create request parsing to normalize omitted/null fields.
5. Add project-aware task creation rules and companion commitment ownership.
6. Switch Android-originated Todoist creation to REST `POST /tasks` with returned-field verification.
7. Add shared Kotlin project models and `getProjects()`.
8. Add Android project repository/provider with in-memory cache and persisted selected project ID.
9. Update Add Task UI and validator.
10. Revert Android explicit-null serialization once backend compatibility is verified.
11. Run backend, shared, Android unit tests, then rerun live device create-to-alarm verification without SQLite repair.

## Open Follow-Ups

- Home should show currently started/in-progress tasks.
- The overlay polling watcher should be manually rerun against the original duplicate lock-screen surface sequence.
- Boot recovery should be verified with a cached future alarm after this create path is stable.
