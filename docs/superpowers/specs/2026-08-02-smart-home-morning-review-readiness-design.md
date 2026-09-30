# Smart Home, Daily Morning Review, And Startup Readiness

Date: 2026-08-02

## Status

Accepted reduced-scope design after visual review and a full grilling session. This
revision supersedes the broader first draft committed earlier on the same feature
branch.

The work is deliberately split into three sequential, independently verified feature
changes:

1. Smart Home and authoritative pull-to-refresh.
2. Daily Morning Review scheduling and flow.
3. Foreground enforcement-readiness gate.

Each change receives its own implementation plan, branch/worktree, test migration,
and verification. They are not one big-bang implementation.

> Amended by docs/decisions/2026-09-29-reminder-reliability.md: only an active Start blocker delays the Morning Review, for at most 15 minutes after 08:00; overdue work never does.

## Purpose

Make Home answer four questions without becoming a second Todoist client:

- What should I be doing now?
- What have I missed?
- Which work has no owner?
- What is coming today and later?

Home shows all future commitments assigned to the device owner in one continuously
scrolling agenda. It also keeps every unassigned root task discoverable without
importing or managing tasks assigned to other people.

The two later changes make the daily review reliable at local 08:00 and ensure the
Android capabilities required for enforcement are checked whenever Buzer returns to
the foreground.

## Reduced Product Boundary

Included in Buzer's task snapshot:

- root tasks explicitly assigned to the device owner;
- personal-project roots that are implicitly owned by the device owner even though
  Todoist stores no assignee;
- every genuinely unassigned root in synchronized assignable/shared projects;
- checklist children nested under their root task.

Root tasks explicitly assigned to another person remain excluded from the task
snapshot. Home also omits deleted tasks, completed-history browsing, and checklist
children as independent agenda rows. Terminal task records may still travel in an
authoritative mutation response when required for reconciliation; they are not Home
sections.

The existing strict commitment rule remains owner-only. Explicitly and implicitly
owner-owned tasks require an exact start date/time and a positive duration. Genuinely
unassigned shared-project tasks do not require a schedule, do not enter the agenda,
and never block Morning Review. Assigning one to the owner activates the existing
strict schedule validation. Assigning it to another person removes it from Buzer's
next authoritative snapshot.

This deliberately avoids collaborator agendas, assignee badges, `Others overdue`,
universal scheduling validation, and editing another person's commitments.

## Change 1: Smart Home And True Refresh

### Current Gap

The current Home filters agenda rows to tasks whose start or end local date is today.
The backend already returns later owner commitments, so valid future work disappears.
The current hero is duplicated in the agenda, overlapping work lacks an explicit
priority rule, refresh feedback shifts layout, and empty copy can claim that no tasks
exist while later tasks are available.

The current snapshot excludes genuinely unassigned shared-project roots as `NOT_OWNED`.
The backend projection must add a non-enforced unassigned classification while
continuing to exclude tasks assigned to other people.

### Information Architecture

Home is one Material 3 `PullToRefreshBox` containing one `LazyColumn`:

1. Title and synchronization context.
2. Morning Review/repair attention banner, only when review is available or due.
3. Owner `Now` hero.
4. Compact owner `Now` rows for overlapping current commitments.
5. Owner `Overdue` section.
6. Collapsed `Unassigned (N)` section.
7. All future owner commitments, grouped by sticky local-date headers.

The Home app bar has no calendar button, date jump, search button, or overflow menu.
The only persistent global action is the Add Task floating action button. After the
user scrolls several date groups, a small transient `Back to top` pill appears and
disappears again at the top.

There is no `Completed today` section in this change.

### Now

An unfinished owner task is current when the injected current instant is within its
committed half-open interval. When several are current, the task ending soonest is the
hero; stable task identity breaks ties. The hero displays `+N overlapping`, and the
remaining current owner tasks appear as compact rows immediately below it.

The hero and overlapping rows are excluded from every later section. Tasks cannot be
duplicated across presentation sections.

The hero shows all existing checklist children inline, preserving the current Home
toggle behavior. `Done` is disabled while checklist items remain incomplete and the
progress count explains why.

### Overdue

An unfinished owner task becomes overdue when its committed interval ends. Show the
three most recently missed tasks initially, newest miss first, together with the total
count. `Show all N` expands the complete deterministic list inline.

### Unassigned

Every genuinely unassigned assignable/shared-project root appears in a collapsed
`Unassigned (N)` section. Personal-project roots with implicit owner responsibility are
owner commitments, not Unassigned rows. An Unassigned row never appears in Now,
Overdue, the dated agenda, or Morning Review's blocking queue. Expanding the section
reveals all such roots inline, ordered most recently updated first. Tapping a row opens
Edit so an assignee can be selected. Normal owner schedule validation applies if the
owner is selected.

### Future Agenda

Include every unfinished, valid, future owner commitment in the authoritative
snapshot—not only tasks touching today. Groups are ordered by local start date. Rows
within each group are ordered by start instant, end instant, then stable task identity.

Sticky headers use `Today`, `Tomorrow`, weekday/date, and an unambiguous full date when
needed. The state builder receives the device zone; composables do not read the system
clock directly. A task crossing midnight belongs to its start-date group and exposes
the cross-day end in its row.

A recurring task contributes only Todoist's current active/next occurrence. The client
does not project speculative future recurrence rows.

Owner tasks with incomplete schedules remain invalid and route through the existing
repair/Morning Review attention path rather than being placed in a guessed date group.
Reconciliation-pending work is labelled `Sync pending`; conflicting mutations remain
disabled and Retry uses the existing reconciliation path.

When no Now, overdue, unassigned, or future task exists, Home shows one true all-clear
state. It does not say `No tasks for today` while future tasks exist.

### Actions

- Owner Now/overdue rows: one-tap `Done` and `Edit`.
- Owner future rows: `Edit`.
- Owner invalid rows in repair: one-tap `Done` when completable and `Fix`.
- Unassigned rows: Edit/assign only.
- Reconciliation-pending rows: `Retry sync` only until resolved.

There is no dedicated Reschedule action. Schedule changes happen in the unified Edit
screen. Parent completion remains one tap with no confirmation and no Undo in this
change. An Undo/reopen contract is explicitly deferred to the product backlog.

All mutations continue through `EnforcementCoordinator` and the action-journal/
authoritative-snapshot protocol. Home never removes a task optimistically without an
authoritative response.

### Pure Home State

Build presentation state with a pure function receiving the complete snapshot, device
zone, injected current instant, and the two saveable expansion flags (Overdue and
Unassigned). Composables render explicit section models rather than classifying tasks.

Owner-task classification precedence is:

1. invalid or reconciliation attention;
2. overdue;
3. current;
4. future agenda;
5. not shown.

Unassigned roots are projected separately and cannot enter owner classification.
Deleted/completed tasks are not shown. Every visible row identity is unique.

### Authoritative Pull-To-Refresh

The existing foreground/open path continues to load the backend mirror immediately;
the backend's five-minute Todoist polling remains unchanged. Buzer does not force a
Todoist synchronization on every foreground.

Pull-to-refresh adds an authenticated backend operation that:

- coalesces concurrent synchronization attempts through the existing serialized sync
  boundary;
- synchronizes Todoist immediately;
- returns the resulting authoritative snapshot on success;
- returns a normal error on failure.

On failure, Home retains its already displayed cached/mirrored snapshot and shows a
concise Retry strip. The response does not add partial-success or stale-snapshot
metadata. Manual and automatic snapshot loading use the same overlaid refresh
indicator, which never inserts a row or shifts layout.

The list exposes an accessibility custom action named `Refresh tasks`; visible Retry
is the non-gesture alternative.

### Change 1 Verification

Pure/JVM coverage must lock:

- all future owner dates and deterministic chronology;
- ending-soonest hero selection, overlap count, and no duplicate IDs;
- overdue three-item cap, total, expansion, and ordering;
- unassigned inclusion, exclusion from enforcement sections, expansion, and ordering;
- exclusion of tasks assigned to other people;
- cross-midnight grouping;
- current/overdue boundary behavior;
- recurring current-occurrence-only behavior;
- invalid, pending, empty, loading, and cached-error states;
- disabled Done for incomplete checklists.

Android instrumentation must cover future scrolling, sticky headers, Back to top,
owner quick actions, inline checklist toggles, unassigned assignment routing,
pull-to-refresh success/failure/Retry/accessibility, and absence of calendar/search/
overflow controls.

The implementation plan must migrate at least:

- `HomeScreenStateTest.kt`;
- `HomeDoneActionHandlerTest.kt`;
- affected `BlockerActivityFakeBackendTest.kt` snapshot fixtures;
- relevant real-backend instrumentation fixtures and expectations.

Final verification includes relevant JVM tests, Android assembly, scoped
`scripts/e2e-real-backend.sh` runs while iterating, and the full unfiltered real-backend
E2E suite before completion.

## Change 2: Daily Morning Review

### Entry And Timing

Morning Review has exactly two entry paths:

1. the Home attention banner;
2. the automated full-screen daily blocker.

There is no permanent Home action or overflow item. The Home banner is available from
local midnight whenever the current local date is unacknowledged, allowing an early
review to cancel that day's 08:00 alarm.

The blocker fires at local 08:00 every calendar day, including weekends and task-free
days. Review identity follows the current device local date and timezone. A timezone
change recomputes the next local occurrence; a date already acknowledged in local
state is not repeated.

### Review Flow

1. Load cached data immediately and attempt a fresh authoritative sync.
2. Present invalid/pending owner tasks first. `Start day` remains unavailable until
   they are repaired/resolved, or the entire review is snoozed.
3. Once blocking items are clear, show owner overdue work and today's chronological
   owner agenda.
4. Overdue work is actionable but does not block `Start day`.
5. `Start day` records acknowledgement for the current local date and closes review.
6. `Snooze 5 min` closes review temporarily and schedules one one-shot retry alarm.

Unassigned tasks never enter the blocking queue or day summary. When review/repair is
active, the review banner is the only review-related Home surface; the normal
Unassigned section remains below it but has no review authority.

Cached data is sufficient for normal acknowledgement. If no cache and no network data
exist, the screen clearly says that task data is unavailable but permits
`Start day without data`; this records a normal acknowledgement. There is no separate
unverified state machine.

New invalid owner tasks discovered after `Start day` show the Home attention banner
and become blocking in the next daily review. They do not trigger a same-day critical
notification or foreground repair gate.

### Scheduling

Introduce a focused `MorningReviewScheduler` and small local state store containing
the last acknowledged local date and pending snooze metadata.

The scheduler uses a reliable one-shot alarm-clock path and:

- computes the next local 08:00 using the current zone;
- replaces rather than stacks equivalent daily alarms;
- schedules the next alarm after fire, acknowledgement, or reconciliation;
- recomputes after boot, package replacement, manual time change, and timezone change;
- performs app-open catch-up after 08:00 when today is unacknowledged;
- handles DST gaps/overlaps deterministically in pure tested date calculations;
- preserves the explicit five-minute Snooze alarm.

If the phone was off at 08:00, no special first-unlock receiver launches review. The
review catches up when Buzer next opens.

Explicit blocker priority remains:

1. deadline blocker;
2. start blocker;
3. Morning Review.

> Amended by docs/decisions/2026-09-29-reminder-reliability.md: only an active Start blocker delays the Morning Review, for at most 15 minutes after 08:00; overdue work never does.

If review fires over an active editor, it appears above the editor and returns to the
preserved draft after acknowledgement or Snooze.

### Change 2 Verification

Tests must cover early acknowledgement, clean-day 08:00 delivery, invalid-owner
blocking, overdue non-blocking behavior, cached and no-data acknowledgement, Snooze,
next-day scheduling, app-open catch-up, boot/package/time/timezone reconciliation, DST
boundaries, blocker priority, and preserved editor state.

Migrate `MorningReviewScreenStateTest.kt`, `MorningReviewRepairHostTest.kt`, relevant
`BlockerActivityFakeBackendTest.kt` scenarios, alarm/receiver tests, and the full
real-backend instrumentation flow.

## Change 3: Foreground Enforcement Readiness

Remove the unused Health & permissions navigation concept. Replace it with a typed
readiness checker and a sequential startup/foreground gate.

### Checks

On every background-to-foreground transition and after returning from a Settings
intent, check:

- notification runtime permission where required;
- global app-notification and required channel state;
- exact-alarm capability;
- full-screen-intent capability on applicable Android versions;
- overlay permission;
- battery-optimization exemption.

Boot receiver wiring is not part of this user-facing gate. It is application
configuration verified through manifest/unit/instrumentation tests, while boot alarm
reconciliation remains production behavior.

The checker is UI-independent and returns typed `Ready`, `NeedsRuntimePermission`, and
`NeedsSettingsAction` results with OS-version-aware details.

### Gate Behavior

When healthy, Home appears without a readiness-screen flash. When unhealthy, the gate
hard-blocks normal app access and presents one requirement at a time in this order:

1. notifications;
2. exact alarms;
3. full-screen intent;
4. overlay;
5. battery exemption.

All five capabilities are mandatory. Runtime notification permission uses the normal
platform prompt; special capabilities open their specific Settings surfaces. On every
return, recheck the whole readiness set.

The gate never launches Settings automatically. A denied capability leaves the gate
visible and requires another explicit `Open settings` tap. If a check unexpectedly
cannot progress, the surface offers Retry only; there is no bypass or Health screen.

Readiness precedes Morning Review and task-repair gates. It is layered above current
app navigation so an Add/Edit draft survives and resumes exactly after permissions are
restored.

### Change 3 Verification

Unit tests cover every typed state, ordering, denial/retry, Settings return, healthy
bypass, and Android-version boundary. Instrumentation covers the sequential gate,
preserved underlying editor state, no automatic Settings loop, and direct healthy
Home startup. Existing `PermissionHealthTest.kt` is migrated rather than left as a
parallel unused model.

Final verification again includes the full real-backend E2E suite because foreground
navigation and Home visibility change.

## Shared Errors And Accessibility

- Cached task data stays visible during ordinary loads and refreshes.
- Empty, loading, stale-cache, and error states remain distinct.
- Every icon-only action has a content description and minimum touch target.
- Sticky date headers are headings and expose absolute dates in semantics.
- Expanded/collapsed sections expose state and count.
- Pull refresh has both a custom accessibility action and visible Retry.
- Morning Review and readiness support TalkBack, large text, and deterministic focus.

## Explicitly Deferred

- tasks assigned to other people and collaborator agendas;
- universal scheduling validation;
- calendar/date-jump/search/overflow controls;
- a Health & permissions screen;
- same-day invalid-task notification/re-gating;
- automatic full-screen catch-up after post-08:00 device boot;
- automatic Todoist sync on every foreground;
- parent task Undo/reopen;
- accurate occurrence-level `Completed today` history;
- projected future recurrence rows;
- backend push invalidation or Todoist webhooks.

The two explicitly requested future product items—parent completion Undo and accurate
`Completed today`—are recorded in `docs/notes/2026-06-13-product-backlog.md`.

## Open Questions

None. Visual spacing and copy may be tuned during implementation without changing the
section hierarchy, authority boundaries, or behavior above.
