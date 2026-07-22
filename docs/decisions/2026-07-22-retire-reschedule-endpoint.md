# Retire The Schedule-Only Reschedule Endpoint

Date: 2026-07-22

## Decision

Retire `POST /tasks/:id/reschedule` and its `RescheduleTaskRequest` contract after
the Android deadline blocker moves to the complete `POST /tasks/:id/update` flow.

The deadline-blocker editor now permits every normal task field to change. A
schedule-only request would either silently discard those edits or duplicate the
existing complete update contract. The user-facing primary action remains
`Reschedule`, but its recovery semantics are presentation only; its mutation is an
ordinary reconciled task update.

## Consequences

- The backend, shared client, Android enforcement API, coordinator, and tests have
  one complete update path for Edit, Morning Review Repair, and blocker recovery.
- `CommitmentEditor` and its legacy combined picker can be removed when its callers
  migrate.
- The product design's former dedicated reschedule endpoint is superseded.
- This is an atomic personal-app cutover. The user accepts brief downtime and will
  install the new APK with the backend deployment; no compatibility shim is kept
  for an older installed app.

## Non-Changes

`Extend 30m`, `Done`, start/postpone enforcement actions, Todoist mutation
authority, alarm recomputation, and action-journal reconciliation keep their
existing contracts and behavior.
