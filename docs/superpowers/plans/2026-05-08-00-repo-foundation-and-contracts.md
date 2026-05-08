# Repo Foundation And Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish repository structure, ignore rules, contract documentation, and handoff conventions so backend and Android agents can work in parallel.

**Architecture:** The repository is a simple monorepo with `backend/` for Node.js/TypeScript, `client/` for Kotlin Multiplatform/Android, and `docs/` for specs, plans, decisions, notes, and handoffs. This task creates documentation and structure only; it does not implement runtime code.

**Tech Stack:** Markdown, Git, Node.js/TypeScript project directories, Kotlin Multiplatform project directories.

---

## Files

- Create: `.gitignore`
- Create: `README.md`
- Create: `docs/notes/api-contract.md`
- Create: `docs/notes/local-development.md`
- Create: `backend/.gitkeep`
- Create: `client/.gitkeep`
- Modify: `docs/superpowers/plans/2026-05-08-00-repo-foundation-and-contracts.md`

## Task 1: Add Repository Ignore Rules

- [x] **Step 1: Create `.gitignore`**

Create `.gitignore` with exactly this content:

```gitignore
# Worktrees
.worktrees/

# OS/editor
.DS_Store
.idea/
.vscode/
*.iml

# Env/secrets
.env
.env.*
!.env.example
secrets/

# Node/backend
node_modules/
dist/
coverage/
.nyc_output/
npm-debug.log*
yarn-debug.log*
yarn-error.log*
pnpm-debug.log*

# SQLite/runtime
*.db
*.db-shm
*.db-wal
data/

# WhatsApp Web auth/session data
.wwebjs_auth/
.wwebjs_cache/
whatsapp-sessions/

# Kotlin/Android
.gradle/
build/
**/build/
local.properties
captures/
.externalNativeBuild/
.cxx/

# Generated reports
reports/
test-results/
```

- [x] **Step 2: Verify ignore coverage**

Run:

```bash
git status --short
```

Expected: `.gitignore` appears as untracked or modified, and no generated runtime directories are tracked.

## Task 2: Create Root README

- [x] **Step 1: Write `README.md`**

Create `README.md` with:

```markdown
# Todoist Commitment Companion

Personal Todoist companion that enforces strict task commitments on Android.

## MVP

- Android-first Kotlin Multiplatform / Compose Multiplatform client.
- Android-native exact alarms, blocker screens, widget, boot rescheduling, and permission health.
- Node.js/TypeScript backend on a VPS.
- SQLite database.
- Todoist polling every 5 minutes.
- `whatsapp-web.js` escalation after repeated start postponement.

## Source Of Truth

- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Implementation plan index: `docs/superpowers/plans/2026-05-08-implementation-plan-index.md`
- Agent rules: `AGENTS.md`

## Repository Layout

- `backend/`: Node.js/TypeScript backend.
- `client/`: Kotlin Multiplatform / Compose Multiplatform Android client.
- `docs/handoffs/`: agent handoff documents.
- `docs/decisions/`: durable architecture and product decisions.
- `docs/notes/`: technical notes and API contracts.
- `docs/superpowers/`: specs and implementation plans.

## Development

Follow `AGENTS.md` before changing files. Each implementation session must leave a handoff in `docs/handoffs/`.
```

- [x] **Step 2: Verify README references exist**

Run:

```bash
test -f AGENTS.md
test -f docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
test -f docs/superpowers/plans/2026-05-08-implementation-plan-index.md
```

Expected: all commands exit with status 0.

## Task 3: Define Initial API Contract

- [x] **Step 1: Write `docs/notes/api-contract.md`**

Create `docs/notes/api-contract.md` with:

```markdown
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

interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}
```

## Endpoints

### `GET /tasks/snapshot`

Returns the latest task snapshot for Android alarm reconciliation and UI.

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
```

- [x] **Step 2: Verify Markdown fences**

Run:

```bash
rg -n '```' docs/notes/api-contract.md
```

Expected: output contains an even number of fence lines.

## Task 4: Add Local Development Notes

- [x] **Step 1: Write `docs/notes/local-development.md`**

Create:

```markdown
# Local Development Notes

Date: 2026-05-08

## Backend

The backend lives in `backend/`. It uses SQLite for MVP. Runtime databases and session folders are ignored by Git.

Expected local commands after backend setup:

```bash
cd backend
npm install
npm test
npm run dev
```

## Android Client

The client lives in `client/`. It is a Kotlin Multiplatform / Compose Multiplatform project with Android-native enforcement code.

Expected local commands after client setup:

```bash
cd client
./gradlew test
./gradlew :androidApp:assembleDebug
```

## Secrets

Secrets stay out of Git. Use `.env.example` files for documented variable names only.

## Handoffs

Each implementation session writes a handoff in `docs/handoffs/` before stopping.
```

- [x] **Step 2: Create project directory marker files**

Create empty directory marker files:

```text
backend/.gitkeep
client/.gitkeep
```

## Task 5: Final Verification And Commit

- [x] **Step 1: Scan for placeholders**

Run:

```bash
rg -n "\bTB[D]\b|\bTO[D]O\b|fill[ ]in" README.md docs/notes docs/superpowers/plans/2026-05-08-00-repo-foundation-and-contracts.md
```

Expected: no matches.

- [x] **Step 2: Review status**

Run:

```bash
git status --short
```

Expected: only files from this plan and already-approved docs are modified or untracked.

- [x] **Step 3: Commit**

Run:

```bash
git add .gitignore README.md docs/notes/api-contract.md docs/notes/local-development.md backend/.gitkeep client/.gitkeep docs/superpowers/plans/2026-05-08-00-repo-foundation-and-contracts.md
git commit -m "docs: add repo foundation plan and contracts"
```

Expected: commit succeeds.

- [x] **Step 4: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-repo-foundation.md` using the template in `docs/handoffs/README.md`.
