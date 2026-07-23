# API Contract

Date: 2026-05-08
Status: Initial contract for MVP planning

## Principles

- Android talks only to the backend.
- Backend talks to Todoist and WhatsApp.
- ISO 8601 timestamps use UTC with `Z`.
- Durations are integer minutes.
- IDs exposed to Android are backend IDs plus Todoist IDs when useful for diagnostics.
- Errors use a stable machine-readable `code`.

## Base URL

- Production (public, HTTPS via Tailscale Funnel): `https://oc-vps.taila50381.ts.net/buzer`
- Tailnet fallback (dev-machine `curl` only, plain HTTP, bypasses Funnel entirely): `http://100.105.180.34:18432`

The `/buzer` segment exists only in the production URL, as Tailscale Funnel's own `--set-path /buzer` mapping — Funnel strips it before forwarding, so the backend itself serves plain, unprefixed routes (e.g. `GET /tasks/snapshot`) and never sees `/buzer` in the request path. This means the two base URLs above are **not** simply "same path, different host" — the production URL needs the `/buzer` segment appended by the client and the tailnet-fallback URL does not. See `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`.

Example: the snapshot endpoint below is `GET https://oc-vps.taila50381.ts.net/buzer/tasks/snapshot` in production, or `GET http://100.105.180.34:18432/tasks/snapshot` via the tailnet fallback.

## Core Types

```ts
type CommitmentState =
  | "planned"
  | "pre_start_warning"
  | "start_blocker_active"
  | "started"
  | "deadline_blocker_active"
  | "completed"
  | "invalid"
  | "deleted";

type HealthLevel = "ok" | "warning" | "error";

interface TaskSnapshotItem {
  id: string;
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  startAt: string | null;
  state: CommitmentState;
  isAssignedToUser: boolean;
  isCreatedByUser: boolean;
  isCompleted: boolean;
  isDeleted: boolean;
  validationErrors: string[];
  startedAt: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  escalationSentAt: string | null;
  updatedAt: string;
  occurrenceKey: string;
  taskRevision: string;
  recurrence: {
    kind: "none" | "supported" | "custom";
    preset: "never" | "daily" | "weekdays" | "weekly" | "monthly" | "yearly" | null;
    rule: string | null;
    displayLabel: string;
  };
  checklistItems: Array<{
    /** Mirror-local ID. Send this value in focused checklist route paths. */
    id: string;
    title: string;
    description: string | null;
    checked: boolean;
    childRevision: string;
    order: number;
  }>;
  checkedCount: number;
  totalCount: number;
  activityLogEntries: Array<{ timestamp: string; message: string }>;
}

interface TasksSnapshotResponse {
  /** Durable, global, strictly monotonic generation for the complete projection. */
  snapshotGeneration: number;
  /** Per-root scheduling revision retained for alarm calculation compatibility. */
  scheduleVersion: number;
  serverTime: string;
  tasks: TaskSnapshotItem[];
  health: HealthStatus[];
}

interface HealthStatus {
  key: string;
  level: HealthLevel;
  message: string;
}

interface ProjectItem {
  id: string;
  name: string;
  canAssignTasks: boolean;
  shared: boolean;
  isInboxProject: boolean;
}

interface ProjectsResponse {
  projects: ProjectItem[];
}

interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

interface MutationResponse {
  actionId: string;
  outcome: "completed" | "reconciliation_pending";
  snapshot: TasksSnapshotResponse;
}
```

## Endpoints

### `GET /tasks/snapshot`

Returns the latest task snapshot for Android alarm reconciliation and UI.

Every v3 task snapshot, including the nested `snapshot` in a successful
mutation response and in a `409` conflict body, contains `snapshotGeneration`.
It is a durable global integer maintained by SQLite in the same transaction as
every write to `todoist_tasks`, `commitment_states`, or `todoist_projects`. It
starts at `1` for a migrated database and never decreases; a rolled-back
transaction does not advance it. Android treats a positive generation as an
opaque total-order token and persists/reconciles it only when greater than the
cached positive generation. This property is required on every network
snapshot: a missing value rejects a GET or successful/202 mutation, and a 409
must include both an authoritative snapshot and its generation instead of
silently becoming zero. Only Android `SnapshotStore` may
migrate an already-persisted pre-v3 cache by writing generation `0`; zero is
unorderable legacy authority and must be replaced by the next positive
authoritative snapshot, not compared as a normal generation. `scheduleVersion`
remains a per-root scheduling compatibility value and must not be used to order
complete snapshots across phones.

The local cache rewrite uses a temporary file and rename. If that persistence
step fails, Android logs it but retains the decoded generation-`0` cache until a
positive server snapshot replaces it.

### `GET /projects`

Returns active Todoist projects available for Android task creation. `canAssignTasks` tells Android whether an assignee must be selected.

### `POST /tasks`

Creates a Todoist task.

Request:

```json
{
  "title": "Pay electricity bill",
  "projectId": "todoist-project-id-or-null",
  "assigneeId": "todoist-user-id",
  "dueDatetime": "2026-05-08T16:00:00Z",
  "durationMinutes": 30,
  "recurrencePreset": "monthly",
  "timeZone": "Europe/Bratislava",
  "checklistDrafts": [
    { "clientMutationId": "child-create-1", "title": "Read meter", "description": null }
  ],
  "actionId": "uuid-per-user-intent",
  "deviceId": "stable-device-uuid"
}
```

Returns `200` plus `MutationResponse` only after every requested Todoist
command and the following authoritative refresh are confirmed. If any command
was sent but its result cannot be proved, it returns `202 reconciliation_pending`
instead. Clients refresh the snapshot and reuse the same action ID; the backend
never exposes a partial child result or asks clients to retry individual drafts.

### `POST /tasks/:id/update`

Edits an authoritative root and direct children. The request carries the
root's occurrence/revision preconditions; every existing-child mutation carries
its own child revision.

```json
{
  "actionId": "uuid-per-user-intent",
  "deviceId": "stable-device-uuid",
  "expectedOccurrenceKey": "task-42:3",
  "rootPatch": {
    "expectedTaskRevision": "r:12",
    "title": "Pay electricity bill",
    "description": "Use the June reading",
    "dueDatetime": "2026-06-08T16:00:00Z",
    "durationMinutes": 30,
    "recurrencePreset": "monthly",
    "timeZone": "Europe/Bratislava"
  },
  "childMutations": [
    { "type": "edit", "clientMutationId": "child-edit-1", "childId": "child-1", "expectedChildRevision": "r:2", "title": "Read meter" },
    { "type": "check", "clientMutationId": "child-check-2", "childId": "child-2", "expectedChildRevision": "r:4" }
  ]
}
```

The server accepts only direct children; it does not expose a parent selector,
child schedules, reordering, or grandchildren. A custom Todoist recurrence is
read-only until a numbered supported preset explicitly replaces it.

### Checklist routes

- `POST /tasks/:id/checklist` adds one direct child.
- `POST /tasks/:id/checklist/:childId/update` edits title/description.
- `POST /tasks/:id/checklist/:childId/delete` removes a direct child.
- `POST /tasks/:id/checklist/:childId/check` and `/uncheck` toggle a child.

Each path requires `actionId`, `deviceId`, `expectedOccurrenceKey`, and, for an
existing child, `expectedChildRevision`. They return `MutationResponse`; a
focused check/uncheck advances that child's revision only. Checking the last
child never completes its root—the explicit `/done` action remains required.
`childId` is the snapshot's mirror-local `ChecklistItem.id`; the backend
resolves it to Todoist's opaque task ID internally. A temporary legacy Todoist
ID fallback exists only for already-issued older clients and is not part of the
current client contract.

### `POST /tasks/:id/start`

Marks companion-only started state.

### `POST /tasks/:id/done`

Completes the Todoist task through the backend.

### `POST /tasks/:id/postpone-start`

Snoozes the start blocker for 5 minutes. The second call triggers escalation.

### `POST /tasks/:id/extend`

Extends Todoist `due_datetime` by exactly 30 minutes.

## Mutation outcomes and errors

- `200 completed`: apply the returned authoritative snapshot exactly
  once; do not immediately fetch another snapshot.
- `202 reconciliation_pending`: retain the returned snapshot, show syncing
  state, and do not speculate on alarms or checklist state.
- `409 stale_occurrence` / `stale_child`: body includes an authoritative
  snapshot. Apply it, retain caller-owned editor drafts, and ask the user to
  reapply deliberate changes.
- `422 activity_log_full`: no local mutation; show the server's reason.
- `429 rate_limited`: honor `Retry-After`; retry only the exact captured request
  and its action ID after that interval.

All task/checklist routes require bearer authentication. Unauthenticated calls
return `401`; unmatched paths return `404`. Backend, local, fake, and tailnet
base URLs serve unprefixed paths. Only the public Funnel base has `/buzer`, and
Funnel strips that mount before the request reaches the backend.

### `POST /review/dismiss`

Snoozes morning review for 5 minutes.

### `POST /device/check-in`

Request:

```json
{
  "deviceId": "android-device-generated-id",
  "appVersion": "0.1.0-dev",
  "permissions": {
    "exactAlarm": "granted",
    "notifications": "granted",
    "fullScreenIntent": "granted",
    "batteryOptimizationIgnored": false,
    "bootReceiverEnabled": true
  }
}
```
