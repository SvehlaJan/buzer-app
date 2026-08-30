# Level 1 mutation simplification

Date: 2026-08-30
Status: Accepted

## Context

Buzer's durable action journal, replay/reconciliation protocol, root mutex, pending
Android state, and effect verification were intended to make Todoist mutations appear
atomic across network and process failures. In practice they made ordinary changes
expensive, created permanent `pending` states, and still could not provide true
distributed transactions with Todoist.

The custom Android UI remains valuable because it removes significant Todoist friction.
The owner accepts less automatic recovery and some loss of functionality in exchange for
a much smaller system whose uncertainty is visible and controlled by the user.

## Decision

### Mutation boundary

- Keep the custom Android Create, Edit, Done, Extend, checklist, Start, and Postpone UI.
- Remove the durable action journal, command journal, replay, reconciliation state,
  effect verification, and root mutex.
- Serialize all Todoist operations through one process-local, non-durable FIFO. A
  mutation holds one slot for its complete command batch and one post-command sync.
- Give the complete operation a 15-second user-visible ceiling, including queue wait.
  The backend uses a shorter internal deadline so it can return before Android's limit.
- Retry an individual Todoist command at most once for transport failure, timeout, 429,
  or Todoist 5xx. Reuse the exact same in-memory Todoist command UUID. Honor
  `Retry-After` only when it fits the remaining deadline.
- Never retry an explicit Todoist `sync_status` rejection. Stop a multi-command action
  on its first failure and do not compensate or roll back earlier commands.
- Treat every explicit command rejection conservatively as potentially partial. Do not
  expose acknowledged-command counts or tell the user that retry is safe.
- After successful command acknowledgement, run exactly one post-command sync. A
  mutation is successful only when the command batch was acknowledged and that sync
  completed. Do not compare the requested effect with the refreshed snapshot.
- After a rejected or delivery-unknown command, attempt one best-effort sync and include
  its actual snapshot when available. The warning remains conservative regardless.
- Keep Start and Postpone as direct SQLite actions. Keep WhatsApp escalation's existing
  per-occurrence claim; it is independent of the removed action journal.

### User control

- Disable the whole action screen while a mutation or explicit refresh is active.
- Re-enable normal actions immediately when the request returns, including warnings.
- Do not show uncertain, rejected, or accepted-but-stale actions as successful.
- Show session-only warnings with `Refresh` and `Open Todoist`. Clear a warning only
  after a successful explicit refresh. Do not persist it across app restarts.
- Distinguish these outcomes:
  - backend busy before sending: nothing was sent;
  - explicit Todoist rejection: potentially partial; check Todoist before retrying;
  - send timeout/disconnection: delivery unknown; check Todoist before retrying;
  - commands acknowledged but post-sync failed: accepted but the Buzer view is stale;
  - Android/backend internal failure: visible internal-error warning and Sentry Issue.

### Snapshot refresh and polling

- Remove `POST /tasks/sync`.
- `GET /tasks/snapshot` reads SQLite by default.
- `GET /tasks/snapshot?forceRefresh=true` first performs a live Todoist sync through the
  same FIFO. A failed forced refresh returns a typed non-2xx response; Android retains
  its displayed snapshot and warns that it may be stale.
- Poll Todoist every minute instead of every five minutes.
- Add Todoist webhooks to the backlog. They are not part of Level 1.

### Recurring child reset

- Buzer-triggered recurring Done still sends Todoist `reset_subtasks: true`.
- Remove automatic repair when polling discovers a recurring completion performed
  outside Buzer with checked children. Those checked children may carry into the next
  occurrence. This is an accepted functionality sacrifice.

### Contract and persistence

- Remove mutation `actionId`, mutation-scoped `deviceId`, pending/outcome/reconciliation
  wire fields, `task_action_commands`, `task_action_journal`, and
  `commitment_states.reconciliation_status`.
- Retain device identity only for check-in and diagnostics.
- Retain occurrence, task-revision, and child-revision preconditions.
- Ship a coordinated breaking backend and Android release without a compatibility shim.
- Back up production SQLite before the forward-only schema cleanup; rollback restores
  that backup and the prior binaries together.

### Observability and Sentry ownership

- Upgrade backend `@sentry/node` to a version supporting structured logs and enable
  searchable info/warning logs.
- Generate an ephemeral `request_id` for each Android API request and propagate it via
  `X-Request-ID`. It is correlation only, not persistence or idempotency.
- Log every mutation lifecycle; every failed one-minute poll and its recovery; and one
  aggregate summary for a poll that changes data. Do not log successful empty polls,
  per-task success records, or merely slow operations.
- Expected network failures, timeouts, rate limits, Todoist rejections, delivery
  uncertainty, and accepted-but-stale outcomes are warning logs, not Sentry Issues.
- Failed parsing, failed state updates, invariant violations, and other internal defects
  create Issues in the component that owns the defect. The other component records a
  correlated warning but does not create a duplicate Issue.
- Log identifiers and bounded counts only. Do not send titles, descriptions, payloads,
  bodies, tokens, headers, raw URLs, or task dumps.

## Consequences

### Benefits

- Deletes the durable distributed-action state machine and its Android pending UI.
- Makes uncertainty explicit instead of presenting inferred success.
- Keeps the low-friction custom UI and authoritative post-mutation snapshots.
- Creates searchable cross-component evidence without duplicate operational Issues.

### Accepted costs

- A process restart loses queued work and in-memory command UUIDs.
- A request disconnect after send can leave the result unknown.
- Multi-command actions can partially apply.
- Explicit Todoist rejections are intentionally described as potentially partial even
  when the backend may have more detail.
- Acknowledgement plus successful sync can report success for a Todoist no-op because
  effect verification is removed.
- Recurrences completed outside Buzer may retain checked children.
- One-minute polling increases Todoist read traffic and repeated outage warning volume.

## Superseded guidance

This decision supersedes the durable reconciliation requirements in:

- `docs/superpowers/specs/2026-07-16-atomic-mutation-reconciliation-design.md`
- `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md`
- `docs/notes/action-reconciliation-protocol.md`
- both repository copies of the `buzer-action-reconciliation` skill

It also supersedes the automatic external recurring-child-reset guarantee described by
the 2026-07-14 recurrence work. Historical documents remain useful as incident context
but are not current implementation requirements.

## Resolution gate

Sentry issues affected by this decision are resolved only after targeted tests, a
coordinated production deployment, and a manual production canary. If Sentry MCP
authentication has expired, stop and ask the owner to reauthenticate; never use a
browser workaround.
