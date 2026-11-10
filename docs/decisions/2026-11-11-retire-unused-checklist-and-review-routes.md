# Retire unused checklist and review routes, distinguish unsupported hierarchy in Morning Review, and standardize blocker/editor failure surfaces

Date: 2026-11-11
Status: Accepted

## Context

During the November 2026 architecture deepening pass across `backend/` and `client/`, four contract and UX ambiguities surfaced between legacy surface area and the approved Level 1 mutation model (`docs/decisions/2026-08-30-level-1-mutation-simplification.md`):

1. Several HTTP endpoints and adapter methods remained in code and tests without any live callers: `POST /review/dismiss` (superseded by Android-local Morning Review state in `2026-07-19-morning-review-design.md`), standalone checklist CRUD endpoints (`POST /tasks/:id/checklist`, `.../update`, `.../delete`, superseded by batched `childMutations` inside `POST /tasks/:id/update`), their corresponding `BuzerApiClient` methods, and pre-Level-1 `TodoistAdapter` methods (`createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete`).
2. Companion-owned tasks with `HierarchyStatus.unsupported` blocked Morning Review (`2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`) via `MorningReviewBlockingCard.Repair`, whose `Repair` action opens `TaskEditorUpdateScreen` with checklist editing disabled — leaving the user unable to resolve a pure hierarchy violation inside Buzer. Meanwhile, `HomeScreenState` filtered `unsupported` tasks out of its default `reviewTasks` count while `MainActivity.morningReviewBlockingCount()` included them.
3. `OverlayBlockerService` swallowed non-`Success` `CoordinatorActionResult` values and timeouts when starting or postponing from the native `WindowManager` overlay, violating the Level 1 rule that uncertain or failed mutations must surface a visible session warning rather than silently disappearing, while `2026-06-10-android-overlay-enforcement-phase-1.md` requires keeping the overlay service free of `ComposeView`.
4. Project/collaborator load failures in task editors were split between inline `TaskEditorProjectLoadStatus` banners (`MorningReviewScreen`, `AddTaskScreen`), duplicate inline banners (`DeadlineBlockerScreen`), and two modal `ErrorAlertDialog` popups (`EditTaskScreen`).

## Decision

### 1. Retire uncalled HTTP routes and dead client/Todoist adapter methods

- Remove `POST /review/dismiss`, `POST /tasks/:id/checklist`, `POST /tasks/:id/checklist/:childId/update`, and `POST /tasks/:id/checklist/:childId/delete` from the backend HTTP router.
- Keep `POST /tasks/:id/checklist/:childId/check` and `POST /tasks/:id/checklist/:childId/uncheck` for focused single-item checkbox toggles on Home and Deadline Blocker.
- Remove unused `BuzerApiClient.addChecklistItem`, `updateChecklistItem`, and `deleteChecklistItem` along with `AddChecklistItemRequest` and `UpdateChecklistItemRequest`.
- Remove unused `TodoistAdapter` methods (`createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete`) and their dead input/output types across `todoistAdapter.ts`, `httpTodoistAdapter.ts`, `fakeTodoistAdapter.ts`, and `e2eTodoistAdapter.ts`.

### 2. Distinguish `UnsupportedHierarchy` from `NeedsScheduleRepair` in Morning Review and align Home counts

- Keep companion-owned `HierarchyStatus.unsupported` root tasks in `ownerAgenda.blocking` so Morning Review still surfaces every companion-owned task that enforcement cannot schedule.
- When a blocking task has root schedule/strictness `validationErrors` (even if its hierarchy is also unsupported), continue rendering `MorningReviewBlockingCard.Repair` so the root schedule can be repaired in-place while checklist editing stays read-only.
- When a blocking task has `hierarchyStatus == HierarchyStatus.unsupported` and no root `validationErrors`, render `MorningReviewBlockingCard.UnsupportedHierarchy` with human-readable copy explaining `task.hierarchyViolation` and an `Open Todoist` action (alongside Morning Review's existing refresh control) instead of a dead-end `Repair` button.
- Align `buildHomeScreenState` and `MainActivity.morningReviewBlockingCount()` so both count all `ownerAgenda.blocking` tasks consistently while preserving existing `"1 invalid task needs review"` / `"$count invalid tasks need review"` Home attention copy.

### 3. Hand off native overlay action failures to `StartBlockerActivity`

- Keep `OverlayBlockerService` free of `ComposeView`.
- When `START` or `POSTPONE` in `OverlayBlockerService` returns `CoordinatorActionResult.Success`, dismiss the overlay as before.
- On any non-`Success` `CoordinatorActionResult` (`Warning`, `Conflict`, `TransportFailure`, `LocalReconciliationFailed`, `ChildActionInFlight`) or timeout/exception, dismiss the native overlay and immediately launch `StartBlockerActivity` (`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`) carrying `EXTRA_INITIAL_WARNING_MESSAGE` so `StartBlockerActivity` renders its sticky `SessionWarningBanner` (`Refresh` + `Open Todoist`).

### 4. Standardize editor project-load failures on inline `TaskEditorProjectLoadStatus`

- All four task editor surfaces (`EditTaskScreen`, `DeadlineBlockerScreen`, `MorningReviewScreen`, and `AddTaskScreen`) render project/collaborator loading and failure state through the non-blocking inline `TaskEditorProjectLoadStatus` banner with `Retry`.
- Remove the two modal project-load `ErrorAlertDialog` popups in `EditTaskScreen` and the duplicate inline project status lines in `DeadlineBlockerScreen`.

## Consequences

- Smaller HTTP, client API, and `TodoistAdapter` interfaces with zero dead mutation paths to maintain or test.
- Users encountering an unsupported Todoist task hierarchy in Morning Review get an actionable `Open Todoist` path instead of an editor that cannot fix the hierarchy.
- Overlay enforcement honors Level 1 uncertainty visibility without introducing Compose lifecycle plumbing into a background `Service`.
- Task editors share one consistent, non-modal project-loading UX and shared loader state.
