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

All paths in this contract are relative to a base URL that already includes the `/buzer` prefix — the backend serves every route under it (Tailscale Funnel does not strip its path mount):

- Production (public, HTTPS via Tailscale Funnel): `https://oc-vps.taila50381.ts.net/buzer`
- Tailnet fallback (dev-machine `curl` only, plain HTTP): `http://100.105.180.34:18432/buzer`

Example: the snapshot endpoint below is `GET https://oc-vps.taila50381.ts.net/buzer/tasks/snapshot`. Clients must never assume the API lives at the host root.

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
}

interface TasksSnapshotResponse {
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
```

## Endpoints

### `GET /tasks/snapshot`

Returns the latest task snapshot for Android alarm reconciliation and UI.

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
  "durationMinutes": 30
}
```

### `POST /tasks/:id/start`

Marks companion-only started state.

### `POST /tasks/:id/done`

Completes the Todoist task through the backend.

### `POST /tasks/:id/postpone-start`

Snoozes the start blocker for 5 minutes. The second call triggers escalation.

### `POST /tasks/:id/extend`

Extends Todoist `due_datetime` by exactly 30 minutes.

### `POST /tasks/:id/reschedule`

Request:

```json
{
  "dueDatetime": "2026-05-08T18:00:00Z",
  "durationMinutes": 45
}
```

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
