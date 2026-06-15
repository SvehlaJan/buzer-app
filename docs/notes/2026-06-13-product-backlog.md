# Product Backlog Notes

Date: 2026-06-13

## Show Current In-Progress Task

The Buzer home screen should show the currently started/in-progress task after the user taps
`I'm starting`.

Observed during real-alarm verification:

- Start action successfully moved the backend task into started state.
- Home still showed only aggregate counts.
- The user expected Buzer to make the active in-progress commitment visible.

Candidate behavior:

- Add a prominent in-progress section on Home when any task has `state=started`.
- Show task title, deadline, remaining time, and primary action to open the deadline blocker/details.
- If multiple tasks are started, show the soonest deadline first and keep the rest scannable.

Status:

- Implemented the first slice on 2026-06-15: Home now preserves `state=started` tasks from the
  snapshot and shows the soonest-deadline in-progress task with its due timestamp.
- Follow-up UI polish remains: format the due time more nicely, show remaining time, and add a
  direct action to the active task/detail or deadline flow once that destination exists.

## Avoid Duplicate Blocker Surfaces

During the same run, the start alarm produced both overlay and activity surfaces. The activity
`I'm starting` action succeeded first, but the overlay remained and later submitted a duplicate
`START_TASK` action before dismissing.

Follow-up:

- Ensure a successful action from either blocker surface dismisses the other visible surface.
- Consider deduplicating repeated idempotent blocker actions in the UI layer or coordinator.

Status:

- A polling overlay watcher was added in the current workspace so visible task overlays refresh
  the snapshot every 2 seconds and dismiss when the task is no longer actionable for that blocker
  kind.
- Manual reproduction of the original lock-screen-activity-then-overlay sequence should still be
  rerun before treating this as fully closed.

## Add Mandatory Project Picker

The Android Add Task flow should require selecting a Todoist project instead of sending
`projectId=null`.

Requested behavior:

- Project selection is mandatory before saving a task.
- The project list is fetched on first open of the Add Task screen.
- The project list is kept in memory for the Add Task session/app lifetime so reopening the screen
  does not immediately refetch unless the app process is restarted or an explicit refresh is added.
- The selected project should be persisted as a user preference and preselected on the next Add
  Task open when still available.

Notes:

- This likely needs a backend endpoint exposing Todoist projects from the local mirror or live sync.
- It should be implemented together with the backend task-create fix so Android-created tasks land
  in a project where due date and assignment semantics are verified.
