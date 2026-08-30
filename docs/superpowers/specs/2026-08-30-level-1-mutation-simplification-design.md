# Level 1 mutation simplification design

Date: 2026-08-30
Status: Approved
Decision: `docs/decisions/2026-08-30-level-1-mutation-simplification.md`

## Goal and boundary

Retain Buzer's custom Android action surfaces while replacing durable mutation
reconciliation with a bounded, one-shot execution model. Prefer visible uncertainty
and manual user control over automatic recovery, compatibility layers, or inferred
success.

This design covers Android, the backend API, Todoist command execution and polling,
SQLite cleanup, structured logs, rollout, and Sentry issue closure. Todoist webhooks are
backlog work.

## System model

```text
Android action/Refresh (15 s ceiling, screen disabled)
                         |
                         | X-Request-ID
                         v
Backend typed route -> process-wide FIFO -> Todoist operation
                         |                    |
                         |                    +-- command: one retry, same UUID
                         |                    +-- one post-command sync
                         v
                  SQLite snapshot
                         |
                         +-- success, or typed visible warning
```

The FIFO is an availability guard, not durable work storage. It exists only inside one
backend process. It serializes polls, forced refreshes, command batches, and
post-command syncs so two Todoist operations cannot race. A mutation owns its slot from
the first validation-dependent external operation until its post-command sync ends.
Direct SQLite Start/Postpone operations do not enter it.

## Time budget

- Android mutation and forced-refresh request timeout: 15,000 ms.
- Backend operation deadline: 13,000 ms, leaving response/transport margin.
- A Todoist HTTP attempt receives at most 5,000 ms and never more than the remaining
  backend budget.
- Queue wait counts against the backend deadline.
- Before sending the first command, the runner must reserve enough budget for one
  command attempt and response serialization. If it cannot, return `backend_busy` and
  guarantee that nothing was sent.
- Once a command has been sent, deadline exhaustion is never described as busy or safe
  to retry. It becomes `todoist_delivery_unknown` or
  `todoist_accepted_snapshot_stale`, depending on whether acknowledgement was received.

The constants are deliberately centralized and testable. There is no slow-operation
warning: only actual timeout/failure outcomes are logged.

## FIFO and cancellation

The queue accepts a deadline and operation metadata. Each entry gets one exclusive
callback and an `AbortSignal`. FIFO order is strict for accepted work. Expired entries
are removed without running and receive typed `backend_busy`.

Client disconnection may abort waiting work before anything is sent. It must not cause
an automatic replay. If disconnection occurs after a send begins, the backend finishes
only within the same bounded callback; the user-facing result remains unknown if it
cannot be delivered. Process restart discards all entries.

The sync service exposes a raw, non-queuing `syncOnce` primitive. Only top-level poll,
forced-refresh, and mutation entry points acquire the FIFO. A mutation calls raw
`syncOnce` while holding its slot; this avoids queue re-entry and deadlock.

## Command execution

Each Todoist command gets a fresh UUID when the request runner reaches that command.
The UUID stays in memory and is reused for one eligible retry.

Retry once for:

- transport failure before a response;
- attempt timeout;
- HTTP 429;
- Todoist HTTP 5xx.

When 429 supplies `Retry-After`, wait only if both the wait and retry fit the remaining
deadline. A terminal explicit HTTP response (429, 5xx, other 4xx, or non-`ok`
`sync_status`) maps to the conservative `todoist_command_rejected` warning after its
allowed retry is exhausted or cannot fit. A transport error or timeout without a
definitive response maps to `todoist_delivery_unknown`. Both tell the user to check
Todoist and neither claims retry is safe. Do not retry:

- an explicit non-`ok` Todoist `sync_status` result;
- validation or revision conflicts;
- other Todoist 4xx responses;
- any command a second time after its single retry.

For a multi-command mutation, commands execute sequentially and stop at the first
failure. There is no rollback. Every explicit command rejection is reported as
potentially partial, including a first-command rejection. The response and logs do not
include an acknowledged-command count because that detail would encourage unsafe
retry assumptions.

## Mutation flow and results

Before entering the FIFO, validate request shape. Inside the operation, validate the
current SQLite occurrence/task/child revision immediately before the first send. There
is no pre-command Todoist sync.

On an acknowledged batch:

1. Call raw `syncOnce` exactly once.
2. If it succeeds, return HTTP 200 with `{ snapshot }`.
3. Do not compare the snapshot with the requested effect.
4. If it fails, return typed non-2xx `todoist_accepted_snapshot_stale`; do not show
   success.

On explicit rejection or delivery uncertainty:

1. Stop the batch.
2. Attempt raw `syncOnce` once, best effort, within the same deadline.
3. If it succeeds, attach `{ snapshot }` to the typed error body.
4. Return `todoist_command_rejected` or `todoist_delivery_unknown` regardless of what
   the snapshot appears to show.

Local Start writes SQLite and returns a snapshot. Local Postpone writes SQLite, claims
second-dismiss escalation using the existing per-occurrence uniqueness mechanism, and
returns a snapshot. A WhatsApp send failure remains a background backend Issue and does
not turn a successfully persisted Postpone into a failed mutation.

## HTTP contract

### Snapshot

`GET /tasks/snapshot`

- Default: return the current SQLite snapshot without Todoist I/O.
- `forceRefresh=true`: acquire the FIFO, perform one raw sync, then return the snapshot.
- Any other query value is a validation error.
- A failed force refresh returns non-2xx `forced_refresh_failed`; Android keeps its
  displayed snapshot.

`POST /tasks/sync` is removed.

### Mutations

Mutation request bodies retain domain input and revision preconditions. They remove
`actionId` and `deviceId`. Checklist item `clientMutationId` may remain as an in-request
mapping key but has no persistence or replay meaning.

Success:

```json
{
  "snapshot": { "generation": 42, "tasks": [] }
}
```

Typed failure:

```json
{
  "code": "todoist_delivery_unknown",
  "message": "Buzer could not confirm the change. Check Todoist before trying again.",
  "snapshot": { "generation": 42, "tasks": [] }
}
```

`snapshot` is optional and present only when a post-failure refresh succeeded. Stable
codes are:

| Code | Meaning | User guidance |
| --- | --- | --- |
| `backend_busy` | Deadline expired before a command send | Nothing was sent; Refresh or retry |
| `todoist_command_rejected` | Todoist explicitly rejected a command | Potentially partial; check Todoist |
| `todoist_delivery_unknown` | Send/response outcome is unknown | Check Todoist before retrying |
| `todoist_accepted_snapshot_stale` | Commands acknowledged, refresh failed | Accepted; Buzer may be stale |
| `forced_refresh_failed` | Explicit live refresh failed | Existing view may be stale |
| existing conflict codes | Local revision/precondition failed | Apply authoritative snapshot and preserve draft |
| `internal_error` | Component-owned defect | Keep control visible; report Issue |

The backend continues echoing `X-Request-ID`. Operational typed errors may use 409,
429, 502, 503, or 504 as appropriate, but clients branch on stable `code`, not status.
Expected typed failures are excluded from Fastify's Sentry Issue capture.

## Android state and UX

Every API call receives a new ephemeral request UUID. The screen exposes one
`interactionInFlight` state covering mutations and forced refresh. While true, all
action controls on that screen are disabled. The state ends immediately on response or
timeout; it is never written to snapshot storage.

Mutation UI results are intentionally small:

- `Success(snapshot)`: apply snapshot, reconcile alarms, close/advance the surface.
- `Warning(kind, snapshot?, message)`: apply an included snapshot, keep the surface
  open, and show session warning actions.
- `Conflict(snapshot, message)`: apply authoritative snapshot and preserve the draft.
- `InternalFailure(snapshot?, message)`: keep the surface open and create an Android
  Issue only when Android owns the defect.

Warnings expose:

- `Refresh`: call `GET /tasks/snapshot?forceRefresh=true`; keep the warning on failure,
  clear it on success.
- `Open Todoist`: launch the Todoist Android package, falling back to Todoist's HTTPS
  site when unavailable.

The normal action button becomes available as soon as the original request finishes.
There is no Retry button, automatic retry, pending card, pending alarm exclusion,
durable warning, or automatic refresh timer. The UI never labels a rejected, unknown,
or accepted-but-stale outcome successful.

## Recurring behavior

Buzer recurring Done continues to send `reset_subtasks: true`. Polling no longer creates
a hidden system action when it detects external recurrence advancement with checked
children. It rotates the occurrence from Todoist's observed data and keeps the observed
child completion state. Checked children may therefore carry forward after completion
outside Buzer.

## Persistence migration

Schema version 7:

1. Drop `task_action_commands`.
2. Drop `task_action_journal`.
3. Remove `commitment_states.reconciliation_status` while preserving every other row
   and column.
4. Remove action-journal guard installation from startup.
5. Keep migration history and all unrelated triggers/indexes intact.

The checked-in latest schema omits these objects for new databases. Migration tests
cover an existing v6 database with journal rows and commitment data. Production deploy
must create and verify a SQLite backup before running the new binary. Rollback restores
both the backup and prior backend version; schema downgrade is not attempted.

## Structured observability

Upgrade backend `@sentry/node` from 8.53.0 to 10.72.0, enable logs, and sanitize through
`beforeSendLog`. Android sends the same marker/attribute model through breadcrumbs and
Sentry structured logs.

Common attributes:

- `request_id`, `operation`, `route`, `result`, `error_code`, `failure_stage`;
- `task_id`, `occurrence_key`, and `child_id` only when required;
- `status_code`, `duration_ms`, `queue_wait_ms`, `attempt`, and bounded counts;
- `snapshot_generation`, `source`, `screen`, and `blocker_type` where applicable.

Never include titles, descriptions, request/response bodies, Todoist payloads, tokens,
headers, raw URLs, or task dumps. Changed-poll identifier lists are bounded and expose
IDs only.

Required markers:

| Level | Marker | Rule |
| --- | --- | --- |
| info | `todoist_operation_started` | One per mutation/forced refresh; request-correlated |
| info | `todoist_operation_succeeded` | Acknowledged plus successful sync only |
| warning | `todoist_command_rejected` | Expected warning, potentially partial |
| warning | `todoist_delivery_unknown` | Expected warning, never an Issue |
| warning | `todoist_accepted_snapshot_stale` | Expected warning, never success |
| warning | `todoist_poll_failed` | Every failed one-minute poll |
| info | `todoist_poll_recovered` | First success after failure |
| info | `todoist_poll_changed` | One summary only when data changed |
| warning | `api_request_failed` | Android operational correlation |
| info | `snapshot_applied` | Android counts/generation, no content |
| warning | `snapshot_apply_failed` | Android Issue when parsing/state ownership is local |

Do not emit successful empty-poll logs or slow-operation warnings. Parsing failures,
state-update failures, invariant violations, and other internal failures become Sentry
Issues in the owning component. A backend defect produces a backend Issue and only an
Android warning; an Android parse/render/state defect produces an Android Issue. This
removes the duplicate handled exceptions currently produced for generic network/5xx
failures.

## Testing model

Backend tests prove deadline admission, FIFO order, non-reentrancy, same-UUID retry,
Retry-After handling, conservative rejection, partial-batch stop, post-sync outcome,
force refresh, poll logging/recovery, local actions, recurrence sacrifice, schema v7,
redaction, and Issue ownership.

Shared/Android tests prove the reduced wire model, 15-second per-request overrides,
warning mapping, snapshot application, screen-wide interaction locks, session-only
warnings, Refresh/Open Todoist actions, no pending/retry state, and Android/backend
Issue ownership.

Real-backend instrumentation exercises Create, Edit, checklist, Extend, one-time Done,
recurring Done, Start, Postpone, force-refresh failure, rejection, delivery unknown,
accepted-stale, busy-before-send, and normal controls reappearing after every outcome.

## Rollout and Sentry closure

This is a coordinated breaking release:

1. Pass all backend, shared, Android, and full real-backend instrumentation suites.
2. Back up and verify production SQLite.
3. Deploy the backend and confirm the v7 schema plus one-minute poll health.
4. Install the matching Android build.
5. Run production canaries for all actions and each warning family without destructive
   duplicate retries.
6. Verify structured logs and component-owned internal test Issues in Sentry.
7. Resolve issues through Sentry MCP only after their individual gates pass:
   - `BUZER-ANDROID-4`: journal/pending removed; recurring Done and formerly stuck task
     verified.
   - `BUZER-ANDROID-5`: sync POST removed; forced snapshot refresh and 15-second UI
     behavior verified.
   - `BUZER-ANDROID-7`: no pre-command sync; typed Todoist failure/retry behavior and
     production mutation verified.
   - `BUZER-ANDROID-8`: operational failures are warning-only; Android internal defects
     still create Issues; no paired Android Issue in canary.

If MCP authentication is unavailable, ask the owner to reauthenticate. Do not use the
browser to bypass API access.
