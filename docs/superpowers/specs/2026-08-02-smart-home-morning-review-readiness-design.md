# Smart Home, Daily Morning Review, And Startup Readiness

Date: 2026-08-02

## Status

Accepted product and technical design. This specification records the completed
Home-screen audit and brainstorming decisions. It intentionally supersedes the
earlier mockup's calendar and overflow buttons: the final Home has neither.

## Purpose

Make Home the useful default surface for the commitment companion. On every visit,
it should immediately answer:

- What needs my attention now?
- What have I missed?
- What is coming today and later?
- What is the most useful action I can take from here?

Home must show every future task returned by the authoritative snapshot in one
continuously scrolling, date-grouped agenda. The redesign also closes two reliability
gaps discovered during the audit: Morning Review is not currently scheduled for the
start of each day, and Android enforcement readiness is not comprehensively checked
on every app start.

## Current Gaps

The current Home implementation filters agenda rows to tasks whose local start or end
date is today. The backend already returns future tasks, so this client-side filter
makes valid upcoming work disappear and can produce a misleading `No tasks for today`
empty state while future tasks exist.

Other observed Home gaps:

- current work is duplicated between the hero and agenda;
- overlapping current tasks have no explicit priority rule;
- rows are grouped by status rather than forming a chronological agenda;
- overdue and completed work can overwhelm the useful future view;
- row actions are discoverable only by tapping, with no contextual quick actions;
- refresh feedback shifts the layout;
- the old Morning Review `R` action is cryptic;
- the UI tests do not lock future grouping, hero deduplication, or overlap behavior.

Morning Review is a real, functional repair surface, but its production alarm is only
created by its own five-minute Snooze action. There is no initial daily 08:00 alarm.
It currently focuses on invalid tasks and does not transition into an overdue/today
day-start summary.

Android has a permission-health reader, but no functional Health screen consumes it.
The checks omit full-screen-intent access and global/channel notification state, and
there is no sequential startup path for acquiring missing enforcement permissions.

## Scope

In scope:

- a smart Home hierarchy with current, overdue, completed-today, and all future tasks;
- one continuously scrolling agenda grouped by local date;
- contextual quick actions and a single Add Task floating action button;
- pull-to-refresh with cached-content retention and accessible fallback;
- an attention banner that opens Morning Review when review is relevant;
- a reliable local 08:00 Morning Review blocker and day-start flow;
- comprehensive enforcement-readiness checks on every launcher start;
- sequential permission/setup guidance when readiness is incomplete;
- deterministic state builders, scheduling logic, and regression coverage.

Out of scope:

- backend or Todoist API expansion;
- calendar integration or a date-picker/date-jump feature;
- a Health & permissions screen or Home overflow menu;
- changing Todoist's authority over visible task data;
- changing the action-journal and authoritative-snapshot reconciliation protocol;
- calendar blocking, webhooks, or push invalidation.

## Final Home Information Architecture

Home is one `LazyColumn` inside Material 3 `PullToRefreshBox`. It uses this order:

1. Greeting/title and sync context.
2. Attention banner, only when Morning Review or a retryable sync condition needs
   attention.
3. `Now` hero for the most relevant current task.
4. Compact `Now` rows for any other overlapping current tasks.
5. `Overdue` section.
6. Collapsed `Completed today (N)` section.
7. Continuous future agenda, grouped by sticky local-date headers.

The only persistent global action is the Add Task floating action button. The final
top app bar has no calendar button and no three-dot overflow. Refresh is a pull gesture
and accessibility action. Morning Review is reachable from its attention banner, not
from a permanent menu item. There is no Health destination.

### Now

A task is current when its local instant interval contains the injected current
instant and it is not completed. When several tasks are current, the hero is the task
ending soonest. Stable task identity is the final tie-breaker. The hero shows a
`+N overlapping` cue when additional current tasks exist; those tasks appear in a
compact Now list immediately below it.

The hero is excluded from every later list. Other current tasks are also excluded
from the future agenda, so every task has one visible placement.

Hero quick actions:

- `Done` completes the task through the existing authoritative action flow;
- `Edit` opens the unified task editor;
- `Reschedule` is available when the task is overdue or its commitment can no longer
  be met as scheduled.

### Overdue

An unfinished task is overdue when the relevant committed end/deadline is before the
injected current instant. Show the three most recently missed tasks initially, ordered
most recent first. The section always shows the total count. `Show all N` expands the
full list; the expanded list uses the same deterministic ordering.

Each overdue row exposes `Done` and `Reschedule`, with Edit available through the task
details/editor path. Overdue tasks do not also appear in the future agenda.

### Completed Today

Only tasks completed on the device's current local date are represented. The section
is collapsed by default and labelled `Completed today (N)`. Expanding it reveals the
rows in most-recently-completed order. Older completed tasks are absent from Home.

If the snapshot cannot provide a trustworthy completion instant, the implementation
must use the existing domain evidence rather than inventing a date. Such a task may be
omitted from this section until authoritative completion data exists.

### Future Agenda

Every unfinished task with a future scheduled occurrence is included, not only tasks
touching today. Date groups are ordered ascending by local date. Rows within a group
are ordered by start instant, then end/deadline instant, then stable task identity.

Date headers remain sticky while scrolling. Labels are human-friendly (`Today`,
`Tomorrow`, weekday/date, then full date when needed) but their grouping keys are
local dates derived with the device zone injected into the state builder.

A task spanning midnight belongs to the group containing its start. Its row makes the
cross-day end explicit. Unscheduled invalid/pending tasks do not get silently placed
in a guessed date group; they contribute to the attention/review state instead.

Future rows expose Edit as their direct action. Invalid or reconciliation-pending
rows use `Review` and route to the existing repair/reconciliation surface.

When there is no current, overdue, completed-today, or future work, Home shows one
true all-clear empty state. It must not claim there are no tasks merely because today
is empty while later tasks exist.

## Pure Home State

Build presentation state with a pure function receiving the complete authoritative
snapshot, device zone, injected current instant, and local expansion state. It returns
explicit sections rather than making composables classify tasks.

Classification precedence is:

1. invalid or unresolved reconciliation attention;
2. completed today;
3. overdue;
4. current;
5. future agenda;
6. not shown.

Where current and overdue semantics could overlap at a boundary, domain commitment
semantics and half-open time intervals must be encoded once in this builder and locked
with tests. The same task ID cannot appear in more than one output section.

UI-only expansion state (`overdue expanded`, `completed expanded`) is saveable and
does not change task authority. Changes caused by an action are rendered from the
authoritative response/snapshot immediately, consistent with `EnforcementCoordinator`
and the repository's reconciliation protocol.

## Pull To Refresh

Use Material 3 `PullToRefreshBox` around the single Home `LazyColumn` and the existing
authoritative refresh/reconciliation path.

- cached tasks stay visible throughout refresh;
- the indicator overlays content instead of inserting a row and shifting layout;
- success applies the authoritative snapshot without rebuilding a parallel cache;
- failure preserves cached content and shows a concise, retryable attention strip;
- concurrent pulls coalesce with the existing in-flight refresh semantics;
- the list exposes an accessibility custom action named `Refresh tasks`;
- the retry action provides a visible non-gesture alternative.

## Morning Review

Morning Review remains a full-screen blocker, but becomes a deliberate daily start
flow at local 08:00.

### Entry Points

There are exactly two product entry points:

1. the Home attention banner when today's review is due, snoozed, or incomplete;
2. the automated full-screen Morning Review blocker.

There is no permanent Home button or overflow item for Morning Review.

### Daily Flow

The blocker launches every local day at 08:00 even when all commitments are valid.
Its states are:

1. Load the freshest available authoritative snapshot while retaining usable cached
   data and communicating refresh failure.
2. Present invalid/unsupported/pending tasks first. Each must be repaired, reconciled,
   or the whole review snoozed. `Start day` is unavailable while blocking items remain.
3. Once commitments are valid, show overdue work and today's chronological agenda.
4. Permit contextual overdue actions, but do not require overdue completion or
   rescheduling before starting the day.
5. `Start day` records acknowledgement for the current local date and closes the
   blocker. `Snooze 5 min` schedules a new one-shot alarm and leaves the day
   unacknowledged.

Home displays the attention banner after 08:00 whenever today's review is not
acknowledged. Before 08:00 it may display only a specifically snoozed/incomplete state,
not a premature daily-review prompt.

### Scheduling And Persistence

Introduce a focused `MorningReviewScheduler` and a small local state store containing
the last acknowledged local date and any pending snooze metadata. No backend endpoint
is needed.

The scheduler:

- computes the next local 08:00 and converts it to an instant using the current zone;
- uses a one-shot alarm-clock delivery path consistent with reliable task blockers;
- schedules the next daily alarm after firing, acknowledgement, or reconciliation;
- replaces rather than stacks equivalent daily alarms;
- restores/recomputes alarms after boot, package replacement, manual clock change,
  and timezone change;
- catches up on launcher start when local time is after 08:00 and today is not
  acknowledged;
- treats DST gaps/overlaps deterministically according to Android/java.time zone
  resolution and tests the resulting local-day behavior;
- keeps the existing five-minute Snooze behavior as an explicit one-shot alarm.

A launch caused by the daily alarm must not mark the review acknowledged. Only the
successful `Start day` action does so.

## Enforcement Readiness On App Start

Remove the unused Health & permissions navigation concept. Replace it with a reusable,
typed readiness checker and a startup setup gate.

### Checks

On every launcher start, and again after returning from a system Settings intent,
evaluate:

- exact-alarm capability;
- runtime notification permission where required;
- global app-notification state and required notification-channel state;
- full-screen-intent capability where the Android version exposes user control;
- overlay permission;
- battery-optimization exemption required by the enforcement model;
- boot receiver component/configuration readiness.

The checker returns typed results with `Ready`, `NeedsRuntimePermission`,
`NeedsSettingsAction`, or `ConfigurationFailure` semantics and OS-version-aware
details. It contains no Compose navigation logic and can be tested independently.

### Startup Gate

Readiness is checked before displaying normal Home. When all checks pass, startup
goes directly to Home without flashing a gate. When checks fail, show one focused
requirement at a time in enforcement-critical order.

- Ask for normal runtime notification permission with the platform prompt.
- Open the specific system Settings surface for exact alarms, full-screen intent,
  overlay, notifications/channel, or battery exemption.
- Recheck the entire readiness set in `onResume` after every prompt/Settings return.
- Explain why the current capability is required and provide one primary action.
- Keep the gate until enforcement-critical permissions are ready; do not present a
  false healthy state.
- Check and self-repair boot receiver configuration when safe. If self-repair is not
  possible, surface an actionable configuration failure instead of asking the user to
  grant a nonexistent permission.

Permission acquisition remains user-driven: the app never loops system prompts or
launches multiple Settings panels at once. Denial retains a clear retry path.

The readiness checker is also available to alarm/Morning Review startup logic so
background components can report real capability failures without depending on a
removed Health screen.

## Data Flow And Actions

The backend snapshot remains the source of visible task truth. Home receives the
complete snapshot already available through the client API and never requests a
separate future-agenda endpoint.

All task mutations (`Done`, Edit/Reschedule saves, and repair) continue through the
existing coordinator/action-journal protocol. Pending, timeout, or stale responses
must preserve the current authoritative/cached display and expose reconciliation
state; they must not optimistically remove work without authority.

Morning Review acknowledgement and snooze are device-local scheduling state, not
Todoist task mutations. Permission readiness is device capability state. Keeping these
three authorities separate prevents Home rendering from becoming a second source of
truth.

## Errors, Loading, And Accessibility

- Initial startup may use the existing cached snapshot while a fresh snapshot loads.
- Refresh and action failures appear close to the affected section without replacing
  the entire agenda when usable data exists.
- Empty, loading, stale, and error states are distinct.
- Sticky headers are announced as headings; relative date labels include an absolute
  date in semantics.
- Every icon-only action has a content description and a minimum touch target.
- Quick actions have text or explicit accessibility labels and deterministic focus
  order.
- Collapsed sections expose expanded/collapsed semantics and their counts.
- Morning Review and the readiness gate remain usable with large font and TalkBack.
- Pull-to-refresh has both the custom accessibility action and visible Retry fallback.

## Existing Tests To Migrate

The implementation plan must inspect and migrate all existing suites that assert the
changed behavior, not defer them to follow-up work:

- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- receiver/alarm registry tests affected by the new daily alarm and time-change events.

The instrumentation class name still contains `FakeBackend` for historical reasons,
but final UI verification must use the repository's current real-backend E2E runner;
the removed fake-backend tool must not be restored.

## Required New Verification

Pure/JVM coverage:

- all future dates are included and date groups/rows are chronological;
- current hero selection, overlap count, and no duplicate task IDs;
- overdue cap, total count, expansion, and ordering;
- completed-today collapse and exclusion of older completion history;
- cross-midnight grouping and labels;
- invalid/pending placement and true all-clear state;
- Morning Review before/after-08:00, acknowledgement, snooze, catch-up, next alarm,
  boot/package/time/timezone reconciliation, and DST boundaries;
- invalid tasks block `Start day`, while overdue tasks do not;
- every readiness state, sequential progression, Settings return, healthy bypass,
  denial/retry, and Android-version-specific behavior.

Android instrumentation coverage:

- future scrolling and sticky date headers;
- hero/overlap/overdue/completed section behavior;
- Done, Edit, Reschedule, Review, and Add routing;
- pull refresh success, failure with retained content, Retry, and accessibility action;
- absence of calendar and overflow actions;
- daily Morning Review launch and day-start flow;
- readiness gate progression and direct healthy launch.

Final verification for the UI-affecting implementation must include relevant JVM
tests, Android compilation/assembly, scoped real-backend E2E tests while iterating,
and the full unfiltered `scripts/e2e-real-backend.sh` suite before completion.

## Product Decisions Recorded

- Home shows all future tasks in a continuous agenda.
- Smart zones precede the future agenda.
- Current work uses an ending-soonest hero with explicit overlap handling.
- Overdue initially shows three; completed today is collapsed; older completions are
  absent.
- Home has no calendar/date-jump button and no overflow menu.
- Morning Review is available only through attention and the automated daily blocker.
- Morning Review always launches at local 08:00, including on clean days.
- Health & permissions is not a screen; readiness is checked/guided at every start.
- Home refresh is pull-to-refresh using the authoritative snapshot path.
- No backend API expansion is required.

## Open Questions

None. Exact visual tokens, spacing, and copy may be tuned during implementation as
long as they preserve this hierarchy, behavior, and accessibility contract.
