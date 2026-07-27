# Overdue Morning Review Design

**Date:** 2026-07-27
**Status:** Approved

## Purpose

Make every currently overdue commitment visible on Home and require its resolution in
Morning Review. This refines the Home and Morning Review behavior in the approved MVP
spec, which already calls for Morning Review to show overdue tasks.

The design deliberately uses only the current backend snapshot. It does not add an
occurrence-history ledger, a database migration, a new backend endpoint, or a new
recurrence model.

## Scope

An overdue task is a root commitment in the current snapshot that is:

- not completed or deleted;
- scheduled with both an exact due datetime and duration; and
- at or past its calculated end time (`dueDatetime + durationMinutes`).

Invalid tasks remain a separate Morning Review concern. Checklist children do not
become independent overdue commitments.

For recurring Todoist tasks, Buzer enforces only the current occurrence exposed by
Todoist. If Todoist has already advanced the task, the prior occurrence is not
retained or enforceable.

## Shared classification

A small, deterministic client-side selector derives overdue root tasks from the
current `SnapshotViewState` and an injected current time. Home and Morning Review use
the same selector so they cannot disagree about whether a task is overdue.

The selector treats completed and deleted tasks as resolved. Tasks missing a deadline
or duration are left to the existing invalid-task flow rather than guessed at.

## Home

Home shows every current overdue task, including tasks scheduled on previous dates.
Each uses the existing overdue timeline styling and an elapsed label such as
`Overdue - ended 1d 2h ago`.

The Home agenda orders overdue work ahead of current and upcoming work. The current
task hero remains unchanged. A task that spans midnight is considered overdue only
after its actual calculated end time.

## Morning Review gate

Morning Review loads the current snapshot, derives the overdue queue, and displays
those tasks alongside the existing invalid-task review cards.

While one or more overdue tasks remain:

- Snooze is unavailable;
- Back navigation does not close the blocker; and
- each overdue task must be resolved through an existing authoritative action:
  **Done**, **reschedule**, or **delete**.

After every action, the activity uses the refreshed backend snapshot as authority.
The overdue gate lifts only when that snapshot has no overdue tasks. Invalid tasks
retain their existing review and snooze behavior once the overdue queue is clear.

If a refresh or action fails, the app keeps any cached overdue queue and presents a
retryable error. It never assumes that a failed request resolved a task. When no
snapshot is available, the existing loading/error experience remains; the client does
not manufacture an overdue queue from incomplete local data.

## Boundaries

- This is a catch-up mechanism in the daily Morning Review only. It does not create a
  new immediate blocker when a task becomes overdue during the day.
- It reuses the existing task snapshot and completion/update/delete flows. No backend
  persistence or API-contract change is part of this work.
- It does not make a historical recurring occurrence enforceable after Todoist has
  advanced the recurrence.

## Verification

Automated coverage must include:

- the shared overdue selector: previous-day and same-day expirations, midnight
  boundaries, completed/deleted exclusions, invalid schedule exclusion, and root-only
  behavior;
- Home state: prior-day overdue rows appear, retain the overdue label, and sort before
  current and upcoming rows;
- Morning Review state/UI: overdue tasks appear beside invalid tasks, Snooze and Back
  are blocked while any overdue task exists, and the gate clears only after an
  authoritative refreshed snapshot is overdue-free; and
- a focused real-backend Android instrumentation flow covering an overdue Morning
  Review task through a resolution action and the resulting gate release.

The full `scripts/e2e-real-backend.sh` suite remains required before implementation is
considered complete, per repository policy.
