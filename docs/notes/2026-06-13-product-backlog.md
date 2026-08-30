# Product Backlog Notes

Date: 2026-06-13

## Todoist Webhook Invalidation

Added: 2026-08-30 during Level 1 mutation simplification. Priority: after the Level 1
release is stable in production.

The one-minute poll is the accepted near-term freshness mechanism. Add Todoist webhooks
later to reduce the delay between Todoist-side changes and Buzer's SQLite mirror without
adding a realtime client data layer.

Candidate scope:

- Treat webhook delivery as an invalidation hint, not authoritative task state.
- Authenticate/validate the webhook according to current Todoist documentation.
- Deduplicate redelivery cheaply, then enqueue one ordinary sync through the same
  process-wide Todoist FIFO.
- Keep the one-minute poll as loss recovery and the source of eventual convergence.
- Start without FCM, Supabase/Firebase realtime, or direct Android push invalidation.
- Add bounded structured logs for accepted, rejected, deduplicated, synced, and failed
  webhook handling without task titles or payload bodies.

Before implementation, confirm Todoist's current webhook availability, event contract,
signature/authentication requirements, retry policy, and account/app constraints using
official documentation.

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

Status:

- Implemented in the project-aware task creation branch.

## Make Loading And Error States Obvious

The app should make backend/network failures visible enough that a user understands what failed and
what to do next.

Observed during real-device verification on 2026-06-15:

- The device was initially not connected to the Tailscale network, so project-loading requests could
  not reach the VPS.
- The Add Task project picker did not make the failure obvious enough. The user saw that projects did
  not load, but the app should have shown a clear error surface.

Candidate behavior:

- Show visible loading indicators for backend-backed screen initialization, especially Add Task
  project loading and Home snapshot refresh.
- Show alert dialogs or similarly prominent error surfaces for failed required operations.
- Include a retry action on recoverable network/backend failures.
- Keep inline field validation for user-fixable form mistakes, but use prominent errors for system
  failures such as no network, Tailscale disconnected, backend unavailable, auth failure, or server
  errors.
- Ensure errors are logged with endpoint/action context and visible user-facing messages are concise.

## Adopt Todoist-Native Due Semantics

Added: 2026-07-05 (owner decision during go-live). Priority: next after the live smoke, before
the Home task list and the UI overhaul.

Observed during go-live: Buzer treats `dueDatetime` as the hard deadline and computes
`startAt = due - duration` (`backend/src/domain/time.ts`), while Todoist treats `due.datetime`
as the START of a block and renders duration forward. Consequences: Buzer-created tasks show in
the Todoist calendar as ending at deadline + duration, and Todoist-created tasks would be
enforced a full duration earlier than the user intended.

Decision: adopt Todoist-native semantics project-wide.

- `due.datetime` = start of the committed block; enforcement deadline = due + duration.
- Backend: `computeStartAt` becomes `startAt = due`; deadline computation moves to
  `due + duration`. Alarm planner deadline-kind timing follows.
- Android Add Task / reschedule / repair: the picker semantics change to "Start at" (or the UI
  keeps a deadline input and computes `due = deadline - duration` before sending — decide in
  the spec).
- Requires a decision doc (this amends the 2026-04-30 product spec), a migration story for
  existing mirrored tasks, and updates to every test asserting the old start/deadline math.

## Home Task List

Added: 2026-07-05 (owner request). Priority: after the due-semantics change, before the UI
overhaul.

Home currently shows aggregate counts plus the single soonest in-progress task. The owner wants
a "today's agenda" list on Home (refined 2026-07-06 during the device pass): today's committed
tasks in start order, with their block times and states, so the day is visible at a glance.
Supersedes/extends the remaining polish items in "Show Current In-Progress Task" above
(remaining time, direct action routing) — fold those into the agenda design rather than doing
them separately.

Status update 2026-08-02: the accepted reduced-scope Smart Home design supersedes the
today-only boundary. Home will show Now, owner overdue work, every genuinely unassigned
shared-project root in a collapsed section, and all future owner commitments in a
continuous date-grouped agenda. See
`docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md`.

## UI Overhaul (AI-Assisted)

Added: 2026-07-05 (owner request). Priority: after the Home task list.

Current UI is unstyled default Material (`BuzerTheme` is a passthrough with no color scheme,
typography, or shape language). Owner emphasis (2026-07-06): ALL screens need a
usability/UX-first redesign, with Morning Review called out as the worst offender.
Plan: screenshot every screen, then run an AI-assisted redesign
(candidates researched 2026-07-05: Google Stitch for concept generation, UX Pilot, Figma
Make + the Figma MCP design-to-code loop; implementation target Material 3 Expressive on
Compose). Not Anthropic-only by explicit owner preference. Sequencing note: do this after the
due-semantics change so redesigned screens carry the corrected "Start at" language.

Owner decisions (2026-07-06, after the five-phase process proposal was accepted):

- Standard Material Design components over custom ones — stable M3 foundation; M3 Expressive
  only as an optional late polish behind its experimental opt-in.
- Dark AND light modes are required (Phase 2 builds both color schemes; dynamic-color
  decision still open).
- Figma is allowed but optional — owner has no paid subscription; default lane is
  Stitch reference images + code-first iteration; Figma free tier + the Figma MCP only if a
  screen needs pixel-level back-and-forth.
- Codex (OpenAI, owner's existing ChatGPT subscription) is an approved alternate executor
  for Phase 3 implementation plans — plans are engine-agnostic and AGENTS.md carries the
  verification gates; one branch/agent at a time (use worktrees if overlapping, as the
  earlier codex/* work did). Concept generation stays with Stitch.

## Parent Task Completion Undo

Added: 2026-08-02 during the Smart Home scope review.

Smart Home deliberately keeps owner-task completion as a one-tap action with no
confirmation. Add a short-lived Snackbar `Undo` backed by a real, reconciled parent-task
reopen operation. This is not a local optimistic toggle: reopening must use a dedicated
backend/Todoist command, preserve action-journal idempotency, return an authoritative
snapshot, restore the correct recurring/non-recurring occurrence, and reconcile alarms.

The implementation requires explicit behavior for a recurring task whose completion
already advanced its occurrence. Do not expose Undo until that boundary is designed and
covered by backend, coordinator, and real-backend instrumentation tests.

## Accurate Completed-Today History

Added: 2026-08-02 during the Smart Home scope review.

Add a collapsed `Completed today (N)` Home section only after the backend can project
occurrence-level completion events accurately enough for recurring tasks. The active
Todoist row is insufficient because completing a recurrence advances the same task to
its next occurrence.

Candidate accepted direction from the review:

- store completion events for owner occurrences, including recurring occurrences;
- use exact timestamps for Buzer completions;
- for recurring completions performed directly in Todoist, use first-observed sync time
  when exact activity history is unavailable and retain timestamp provenance;
- return a rolling 72-hour event window so the client can derive its current local day
  across timezone changes;
- keep the section collapsed by default and owner-only.

This remains out of the initial Smart Home implementation because it adds persistence,
snapshot-contract, recurrence, retention, and timezone complexity without affecting
actionable work.
