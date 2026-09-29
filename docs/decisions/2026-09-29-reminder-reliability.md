# Decision: Reminder reliability fixes

Date: 2026-09-29
Status: accepted (owner grilling session, 2026-09-29)

## Context

The owner reported that reminders do not arrive on the phone. The investigation found five
independent causes. Evidence is in `docs/notes/2026-09-23-morning-review-deferred-by-overdue-tasks.md`
and `docs/handoffs/2026-09-23-morning-review-delivery-investigation.md` (Sentry Logs, VPS logs, VPS
database):

1. Morning Review deferral. `BlockerPriorityResolver` treats every cached overdue task as an
   active Deadline blocker. The 08:00 review was re-deferred every minute, all day, from
   2026-09-10 to 2026-09-23.
2. Floating Todoist times. Todoist returns due times without a timezone
   (`"2026-10-11T09:00:00"`, `due.timezone = null`) for 11 of the owner's 22 open timed
   tasks. The backend copies them into the snapshot unchanged. `AlarmPlanCalculator` calls
   `Instant.parse` on them and aborts the whole alarm plan (Sentry BUZER-ANDROID-E). Because the
   snapshot is saved before reconcile, no new alarm is armed until the task changes. The backend
   also reads floating times as UTC, so they come out 1–2 h late.
3. The Morning Review's opening forced sync runs at 08:00:0x. That is exactly when Todoist
   returns most of its 5xx errors, and 10 of 22 poll failures fell at `hh:00:04–12`. A failure
   posts a misleading "Backend action failed … Check VPS/backend health" notification.
4. Boot ANR (BUZER-ANDROID-D). The network refresh in `BootReconcileRunner` outlived the
   60 s boot-broadcast window.
5. A reconcile after 08:00 schedules tomorrow's review instead of today's.

## Decisions

1. **Floating time semantics.** A floating Todoist due time is a wall-clock time in the
   owner's Todoist timezone. That is the `todoist_collaborators.time_zone` of
   `TODOIST_USER_ID`. If it is missing or invalid, the backend uses the `OWNER_TIMEZONE` env
   var, then `Europe/Bratislava`. This matches Todoist's own definition: a floating time
   "always represents an event in current user's timezone".
2. **Resolve on the way out.** `todoist_tasks` stays a raw Todoist mirror. One backend
   resolver (`resolveDueInstant` in `backend/src/domain/time.ts`) turns a due value into an
   instant. The snapshot's `startAt`, its `dueDatetime`, and the escalation deadline all go
   through it, so the phone only ever receives UTC instants. No code outside `time.ts` may
   call `new Date(dueDatetime)`.
3. **Writes.** The edit path compares resolved instants, so an unchanged time is never
   rewritten to Todoist. A time that Buzer actually changes is written as today: a fixed UTC
   value, which Todoist pins to the owner's timezone. Floating-ness is not preserved.
4. **Client: isolate and report.** One strict parser (`parseSnapshotInstantOrNull` in
   `shared`) is used by the alarm planner, Home, the owner agenda, and the Morning Review
   priority check. A task whose time cannot be parsed gets no alarms, and every other task is
   planned normally. It is reported to Sentry once per app process, with `task_id`. The two
   guessing parsers in `HomeScreenState` and `OwnerAgendaSelector` are deleted. The
   save-then-reconcile order is unchanged.
5. **Morning Review priority.** This amends
   `docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md`
   ("Explicit blocker priority remains: deadline, start, Morning Review") and plan
   `2026-08-02-daily-morning-review.md` §"blocker priority":
   - Only an unstarted owner task inside its active interval (a Start blocker) delays the
     review.
   - The delay lasts at most until 15 minutes after the review date's 08:00.
   - Overdue work never delays the review.
   - A Deadline blocker is only ever armed for a future deadline, so none is live at review
     time. If both fire in the same minute, both show.
6. **Review sync.** When the review's forced sync fails, it retries once after 30 s. The
   inline error is shown only if the retry also fails. The review never posts the "Backend
   action failed" system notification.
7. **Boot.** `BootReconcileRunner` runs only the cached reconcile. The network refresh is
   removed, and the next app open refreshes as usual.
8. **Accepted limitation.** A reconcile between 08:00 and 08:15 can move the next review to
   tomorrow. The in-app catch-up still shows today's review when Buzer is opened. This is not
   fixed.
9. **Backend logging.** Backend operational failures are already in the `buzer-backend`
   project's Sentry Logs (`forced_refresh_failed`, `todoist_poll_failed`), searchable by
   `request_id`. There is no code change; the runbook documents this.

## Out of scope

- Duplicated WARN/ERROR lines in Sentry Logs.
- `overlay_start … invalid_request` log noise.
- Background snapshot sync (a known MVP limit).
- The task editor's own tolerant parser (`TaskEditorUpdateScreen.toTaskEditorUpdateSchedule`).
- Editing floating *recurring* tasks, which `describeRecurrence` already reports as custom.

## Consequences

- A backend deploy alone fixes BUZER-ANDROID-E for the APK already installed, because the
  phone then only receives instants.
- Floating tasks with a duration fire 1–2 h earlier than before, at the time Todoist shows.
- Implementation plan: `docs/superpowers/plans/2026-09-29-reminder-reliability.md`.
