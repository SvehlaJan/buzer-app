# Task Details And Todoist Description Decision

Date: 2026-07-08

## Status

Accepted for the Android UI redesign roadmap.

## Context

The approved UI redesign adds optional task details to Add Task and requires existing task details to appear where useful on blockers and Morning Review repair cards. Todoist's UI calls this field "Description"; the API also exposes it as `description`. The current stack only mirrors and creates task title/content. Future fields such as photo attachments or subtasks are plausible, but they are not part of the current redesign.

Todoist API documentation fetched with Context7 on 2026-07-08 confirms:

- Task creation accepts an optional `description` field.
- Task update accepts an optional `description` field.
- Returned task objects include `description`.
- The description can contain markdown-formatted text and links in the Sync API model.

## Decision

Treat task details as Todoist-owned visible task data, not companion enforcement metadata. In code and API payloads, this maps only to Todoist's existing `description` field.

Add Todoist `description` as a first-class optional mirrored field across:

- Todoist adapter input/output mirrors, for both task creation and reschedule (`item_update`).
- Backend SQLite `todoist_tasks`.
- Backend task snapshot responses.
- Backend create and reschedule request schemas and services.
- Shared Kotlin DTOs, including `CreateTaskRequest` and `RescheduleTaskRequest`.
- Fake backend fixtures and assertions, for both `POST /tasks` and `POST /tasks/:id/reschedule`.
- Add Task, Morning Review repair, and Deadline reschedule UI, all through the shared Commitment Editor.
- Task detail display in Home, blockers, overlay, and Morning Review.

Task details are editable everywhere the Commitment Editor appears, not only in Add Task. Repair and Reschedule persist edits through the reschedule path so a description change made while fixing an invalid task or moving a deadline is not silently dropped.

Normalize blank task details to `null` at the app/backend boundary where practical. Preserve non-blank text after trimming outer whitespace.

Do not create any Buzer-exclusive task-detail field, companion-only details table, or backend-owned generic details store. The backend may persist `description` only as part of its Todoist task mirror, the same way it mirrors title, project, assignee, due time, and duration.

## Future Field Strategy

Do not add a generic JSON bag for future UI fields in this roadmap.

When a future field becomes real:

- Todoist-owned visible task data, such as subtasks, comments, labels, or attachments, should be modeled explicitly against Todoist's existing API concepts.
- Companion-only enforcement metadata should stay in companion-owned backend tables.
- UI components should consume typed view data such as `TaskDetailsUiModel`, not backend raw JSON.

This keeps validation, migrations, and Todoist sync behavior inspectable. A generic bag would be faster initially but would make tests, migrations, and cross-platform UI contracts harder to reason about.

## Consequences

- The redesign has one early cross-stack phase before UI components can render Todoist task details end to end.
- Existing databases need a migration guard that adds `todoist_tasks.description`.
- Existing Kotlin tests constructing `TaskSnapshotItem` need either a default value or named-field update.
- The reschedule request/service/adapter path grows a `description` field alongside `dueDatetime`/`durationMinutes`, with clear semantics: omitted leaves it unchanged, blank or null clears it, non-blank sets it.
- The UI can later add attachments or subtasks without changing the `CommitmentEditor` layout contract if those details are represented through typed extension sections.
