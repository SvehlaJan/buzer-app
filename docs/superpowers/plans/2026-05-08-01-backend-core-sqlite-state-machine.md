# Backend Core SQLite State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend foundation: TypeScript project, SQLite migrations, repositories, validation rules, and commitment lifecycle state machine.

**Architecture:** The backend is a Node.js/TypeScript service with a small domain layer independent of Todoist and WhatsApp adapters. SQLite persistence is accessed through repositories; domain logic is tested without HTTP or network dependencies.

**Tech Stack:** Node.js current LTS, TypeScript, Vitest, SQLite, Fastify-compatible domain structure.

---

## Files

- Create: `backend/package.json`
- Create: `backend/tsconfig.json`
- Create: `backend/vitest.config.ts`
- Create: `backend/.env.example`
- Create: `backend/src/domain/time.ts`
- Create: `backend/src/domain/taskValidation.ts`
- Create: `backend/src/domain/commitmentState.ts`
- Create: `backend/src/domain/errors.ts`
- Create: `backend/src/db/connection.ts`
- Create: `backend/src/db/migrations.ts`
- Create: `backend/src/db/schema.sql`
- Create: `backend/src/repositories/taskRepository.ts`
- Create: `backend/src/repositories/deviceRepository.ts`
- Create: `backend/test/domain/taskValidation.test.ts`
- Create: `backend/test/domain/commitmentState.test.ts`
- Create: `backend/test/db/migrations.test.ts`
- Create: `backend/test/repositories/taskRepository.test.ts`

## Task 1: Create Backend Tooling

- [ ] **Step 1: Use Context7 for library docs**

Run:

```bash
npx ctx7@latest library "Vitest" "Node TypeScript project unit tests configuration"
npx ctx7@latest docs "/resolved/vitest/id" "Vitest TypeScript Node project configuration"
```

Replace `/resolved/vitest/id` with the ID returned by the first command. Record the resolved ID in the handoff.

- [ ] **Step 2: Create `backend/package.json`**

Use this baseline:

```json
{
  "name": "buzer-backend",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "tsc -p tsconfig.json --noEmit",
    "dev": "tsx src/server.ts",
    "migrate": "tsx src/db/migrations.ts"
  },
  "dependencies": {
    "@fastify/sensible": "latest",
    "better-sqlite3": "latest",
    "dotenv": "latest",
    "fastify": "latest",
    "zod": "latest"
  },
  "devDependencies": {
    "@types/better-sqlite3": "latest",
    "@types/node": "latest",
    "tsx": "latest",
    "typescript": "latest",
    "vitest": "latest"
  }
}
```

- [ ] **Step 3: Create TypeScript configs**

`backend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,
    "esModuleInterop": true,
    "forceConsistentCasingInFileNames": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "rootDir": "."
  },
  "include": ["src/**/*.ts", "test/**/*.ts", "vitest.config.ts"]
}
```

`backend/vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
    clearMocks: true
  }
});
```

- [ ] **Step 4: Create `.env.example`**

```dotenv
NODE_ENV=development
PORT=3001
DATABASE_PATH=./data/buzer-dev.db
ANDROID_API_TOKEN=replace-with-local-dev-token
TODOIST_API_TOKEN=replace-with-todoist-token
TODOIST_USER_ID=replace-with-current-user-id
WHATSAPP_RECIPIENT_ID=replace-with-chat-id
```

- [ ] **Step 5: Install dependencies**

Run:

```bash
cd backend
npm install
```

Expected: `package-lock.json` is created and install exits successfully.

## Task 2: Implement Domain Time Helpers With Tests

- [ ] **Step 1: Write failing test in `backend/test/domain/commitmentState.test.ts`**

```ts
import { describe, expect, it } from "vitest";
import { computeStartAt } from "../../src/domain/time.js";

describe("computeStartAt", () => {
  it("subtracts duration minutes from due datetime", () => {
    expect(computeStartAt("2026-05-08T16:00:00Z", 45)).toBe("2026-05-08T15:15:00.000Z");
  });

  it("rejects non-positive duration", () => {
    expect(() => computeStartAt("2026-05-08T16:00:00Z", 0)).toThrow("durationMinutes must be positive");
  });

  it("rejects invalid due datetime", () => {
    expect(() => computeStartAt("not-a-date", 30)).toThrow("dueDatetime must be a valid ISO timestamp");
  });
});
```

- [ ] **Step 2: Run failing test**

```bash
cd backend
npm test -- test/domain/commitmentState.test.ts
```

Expected: fails because `src/domain/time.ts` does not exist.

- [ ] **Step 3: Implement `backend/src/domain/time.ts`**

```ts
export function parseIsoTimestamp(value: string, fieldName: string): Date {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error(`${fieldName} must be a valid ISO timestamp`);
  }
  return parsed;
}

export function computeStartAt(dueDatetime: string, durationMinutes: number): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  const due = parseIsoTimestamp(dueDatetime, "dueDatetime");
  return new Date(due.getTime() - durationMinutes * 60_000).toISOString();
}

export function addMinutes(timestamp: string, minutes: number): string {
  if (!Number.isInteger(minutes)) {
    throw new Error("minutes must be an integer");
  }

  const date = parseIsoTimestamp(timestamp, "timestamp");
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}
```

- [ ] **Step 4: Run test**

```bash
cd backend
npm test -- test/domain/commitmentState.test.ts
```

Expected: pass.

## Task 3: Implement Strict Task Validation

- [ ] **Step 1: Write `backend/test/domain/taskValidation.test.ts`**

```ts
import { describe, expect, it } from "vitest";
import { validateTaskStrictness } from "../../src/domain/taskValidation.js";

describe("validateTaskStrictness", () => {
  const currentUserId = "user-self";

  it("requires assignee for tasks created by the user", () => {
    const result = validateTaskStrictness({
      title: "Buy batteries",
      creatorId: currentUserId,
      assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      currentUserId
    });

    expect(result.valid).toBe(false);
    expect(result.errors).toContain("assignee_required_for_created_task");
  });

  it("requires deadline and duration for tasks assigned to the user", () => {
    const result = validateTaskStrictness({
      title: "Fix router",
      creatorId: "wife",
      assigneeId: currentUserId,
      dueDatetime: null,
      durationMinutes: null,
      currentUserId
    });

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual(["due_datetime_required", "duration_required"]);
  });

  it("allows tasks assigned to someone else without user commitment fields", () => {
    const result = validateTaskStrictness({
      title: "Call school",
      creatorId: currentUserId,
      assigneeId: "wife",
      dueDatetime: null,
      durationMinutes: null,
      currentUserId
    });

    expect(result.valid).toBe(true);
    expect(result.errors).toEqual([]);
  });
});
```

- [ ] **Step 2: Run failing test**

```bash
cd backend
npm test -- test/domain/taskValidation.test.ts
```

Expected: fails because module is missing.

- [ ] **Step 3: Implement `backend/src/domain/taskValidation.ts`**

```ts
export interface StrictTaskInput {
  title: string;
  creatorId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  currentUserId: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export function validateTaskStrictness(input: StrictTaskInput): ValidationResult {
  const errors: string[] = [];
  const title = input.title.trim();
  const createdByUser = input.creatorId === input.currentUserId;
  const assignedToUser = input.assigneeId === input.currentUserId;

  if (title.length === 0) {
    errors.push("title_required");
  }

  if (createdByUser && input.assigneeId === null) {
    errors.push("assignee_required_for_created_task");
  }

  if (assignedToUser) {
    if (input.dueDatetime === null) {
      errors.push("due_datetime_required");
    }

    if (input.durationMinutes === null) {
      errors.push("duration_required");
    } else if (!Number.isInteger(input.durationMinutes) || input.durationMinutes <= 0) {
      errors.push("duration_must_be_positive_minutes");
    }
  }

  return { valid: errors.length === 0, errors };
}
```

- [ ] **Step 4: Run test**

```bash
cd backend
npm test -- test/domain/taskValidation.test.ts
```

Expected: pass.

## Task 4: Implement Commitment State Machine

- [ ] **Step 1: Extend `backend/test/domain/commitmentState.test.ts`**

Add:

```ts
import { applyAction } from "../../src/domain/commitmentState.js";

describe("applyAction", () => {
  it("marks planned task as started", () => {
    const next = applyAction({
      state: "start_blocker_active",
      startDismissCount: 0,
      deadlineExtensionCount: 0,
      startedAt: null,
      escalationSentAt: null
    }, {
      type: "start",
      now: "2026-05-08T15:00:00Z"
    });

    expect(next.state).toBe("started");
    expect(next.startedAt).toBe("2026-05-08T15:00:00Z");
  });

  it("triggers escalation on second start postpone", () => {
    const next = applyAction({
      state: "start_blocker_active",
      startDismissCount: 1,
      deadlineExtensionCount: 0,
      startedAt: null,
      escalationSentAt: null
    }, {
      type: "postpone_start",
      now: "2026-05-08T15:05:00Z"
    });

    expect(next.startDismissCount).toBe(2);
    expect(next.escalationRequired).toBe(true);
  });

  it("increments extension count", () => {
    const next = applyAction({
      state: "deadline_blocker_active",
      startDismissCount: 0,
      deadlineExtensionCount: 2,
      startedAt: "2026-05-08T15:00:00Z",
      escalationSentAt: null
    }, {
      type: "extend_deadline",
      now: "2026-05-08T16:00:00Z"
    });

    expect(next.deadlineExtensionCount).toBe(3);
    expect(next.state).toBe("started");
  });
});
```

- [ ] **Step 2: Implement `backend/src/domain/commitmentState.ts`**

```ts
export type CommitmentState =
  | "planned"
  | "pre_start_warning"
  | "start_blocker_active"
  | "started"
  | "deadline_blocker_active"
  | "completed"
  | "invalid"
  | "deleted";

export interface CommitmentRecord {
  state: CommitmentState;
  startDismissCount: number;
  deadlineExtensionCount: number;
  startedAt: string | null;
  escalationSentAt: string | null;
}

export type CommitmentAction =
  | { type: "start"; now: string }
  | { type: "postpone_start"; now: string }
  | { type: "deadline_active"; now: string }
  | { type: "complete"; now: string }
  | { type: "extend_deadline"; now: string }
  | { type: "reschedule"; now: string }
  | { type: "mark_invalid"; now: string }
  | { type: "mark_deleted"; now: string };

export interface CommitmentTransitionResult extends CommitmentRecord {
  escalationRequired: boolean;
}

export function applyAction(record: CommitmentRecord, action: CommitmentAction): CommitmentTransitionResult {
  switch (action.type) {
    case "start":
      return { ...record, state: "started", startedAt: action.now, escalationRequired: false };
    case "postpone_start": {
      const count = record.startDismissCount + 1;
      return {
        ...record,
        startDismissCount: count,
        escalationRequired: count >= 2 && record.escalationSentAt === null
      };
    }
    case "deadline_active":
      return { ...record, state: "deadline_blocker_active", escalationRequired: false };
    case "complete":
      return { ...record, state: "completed", escalationRequired: false };
    case "extend_deadline":
      return {
        ...record,
        state: "started",
        deadlineExtensionCount: record.deadlineExtensionCount + 1,
        escalationRequired: false
      };
    case "reschedule":
      return { ...record, state: "planned", startDismissCount: 0, startedAt: null, escalationRequired: false };
    case "mark_invalid":
      return { ...record, state: "invalid", escalationRequired: false };
    case "mark_deleted":
      return { ...record, state: "deleted", escalationRequired: false };
  }
}
```

- [ ] **Step 3: Run domain tests**

```bash
cd backend
npm test -- test/domain
```

Expected: pass.

## Task 5: Implement SQLite Schema And Migrations

- [ ] **Step 1: Write migration test `backend/test/db/migrations.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";

describe("migrate", () => {
  it("creates core tables and enables WAL", () => {
    const db = new Database(":memory:");
    migrate(db);

    const tables = db.prepare("select name from sqlite_master where type = 'table' order by name").all() as Array<{ name: string }>;
    expect(tables.map((row) => row.name)).toEqual([
      "commitment_states",
      "devices",
      "schema_migrations",
      "todoist_sync_state",
      "todoist_tasks",
      "whatsapp_escalations"
    ]);
  });
});
```

- [ ] **Step 2: Create `backend/src/db/schema.sql`**

```sql
create table if not exists schema_migrations (
  version integer primary key,
  applied_at text not null
);

create table if not exists todoist_sync_state (
  id integer primary key check (id = 1),
  sync_token text not null,
  updated_at text not null
);

create table if not exists todoist_tasks (
  id text primary key,
  todoist_task_id text not null unique,
  title text not null,
  project_id text,
  assignee_id text,
  creator_id text,
  due_datetime text,
  duration_minutes integer,
  completed_at text,
  deleted_at text,
  raw_json text not null,
  updated_at text not null
);

create table if not exists commitment_states (
  task_id text primary key references todoist_tasks(id) on delete cascade,
  state text not null,
  started_at text,
  start_dismiss_count integer not null default 0,
  deadline_extension_count integer not null default 0,
  last_snoozed_at text,
  escalation_sent_at text,
  schedule_version integer not null default 1,
  updated_at text not null
);

create table if not exists devices (
  id text primary key,
  app_version text not null,
  permission_json text not null,
  last_check_in_at text not null
);

create table if not exists whatsapp_escalations (
  id text primary key,
  task_id text not null references todoist_tasks(id) on delete cascade,
  recipient_id text not null,
  message text not null,
  status text not null,
  error_message text,
  created_at text not null,
  sent_at text
);
```

- [ ] **Step 3: Implement `backend/src/db/migrations.ts` and `connection.ts`**

`backend/src/db/migrations.ts`:

```ts
import Database from "better-sqlite3";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

export function migrate(db: Database.Database): void {
  db.pragma("foreign_keys = ON");
  db.pragma("journal_mode = WAL");
  const schema = readFileSync(join(__dirname, "schema.sql"), "utf8");
  db.exec(schema);
  db.prepare("insert or ignore into schema_migrations (version, applied_at) values (?, ?)").run(1, new Date().toISOString());
}
```

`backend/src/db/connection.ts`:

```ts
import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { migrate } from "./migrations.js";

export function openDatabase(path: string): Database.Database {
  if (path !== ":memory:") {
    mkdirSync(dirname(path), { recursive: true });
  }

  const db = new Database(path);
  migrate(db);
  return db;
}
```

- [ ] **Step 4: Run migration test**

```bash
cd backend
npm test -- test/db/migrations.test.ts
```

Expected: pass.

## Task 6: Implement Task Repository

- [ ] **Step 1: Write repository test `backend/test/repositories/taskRepository.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";

describe("TaskRepository", () => {
  it("upserts task mirror and initializes commitment state", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);

    repo.upsertTodoistTask({
      id: "local-task-1",
      todoistTaskId: "todoist-1",
      title: "Write report",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: "todoist-1" }),
      updatedAt: "2026-05-08T10:00:00Z"
    });

    const snapshot = repo.getByTodoistTaskId("todoist-1");
    expect(snapshot?.title).toBe("Write report");
    expect(snapshot?.state).toBe("planned");
    expect(snapshot?.scheduleVersion).toBe(1);
  });
});
```

- [ ] **Step 2: Implement `backend/src/repositories/taskRepository.ts`**

```ts
import Database from "better-sqlite3";

export interface TodoistTaskUpsert {
  id: string;
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: string;
  updatedAt: string;
}

export interface TaskWithCommitment extends TodoistTaskUpsert {
  state: string;
  startedAt: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  lastSnoozedAt: string | null;
  escalationSentAt: string | null;
  scheduleVersion: number;
}

export class TaskRepository {
  constructor(private readonly db: Database.Database) {}

  upsertTodoistTask(task: TodoistTaskUpsert): void {
    const tx = this.db.transaction(() => {
      this.db.prepare(`
        insert into todoist_tasks (
          id, todoist_task_id, title, project_id, assignee_id, creator_id,
          due_datetime, duration_minutes, completed_at, deleted_at, raw_json, updated_at
        ) values (
          @id, @todoistTaskId, @title, @projectId, @assigneeId, @creatorId,
          @dueDatetime, @durationMinutes, @completedAt, @deletedAt, @rawJson, @updatedAt
        )
        on conflict(todoist_task_id) do update set
          title = excluded.title,
          project_id = excluded.project_id,
          assignee_id = excluded.assignee_id,
          creator_id = excluded.creator_id,
          due_datetime = excluded.due_datetime,
          duration_minutes = excluded.duration_minutes,
          completed_at = excluded.completed_at,
          deleted_at = excluded.deleted_at,
          raw_json = excluded.raw_json,
          updated_at = excluded.updated_at
      `).run(task);

      this.db.prepare(`
        insert or ignore into commitment_states (
          task_id, state, start_dismiss_count, deadline_extension_count, schedule_version, updated_at
        ) values (
          @id, 'planned', 0, 0, 1, @updatedAt
        )
      `).run(task);
    });

    tx();
  }

  getByTodoistTaskId(todoistTaskId: string): TaskWithCommitment | null {
    const row = this.db.prepare(`
      select
        t.id,
        t.todoist_task_id as todoistTaskId,
        t.title,
        t.project_id as projectId,
        t.assignee_id as assigneeId,
        t.creator_id as creatorId,
        t.due_datetime as dueDatetime,
        t.duration_minutes as durationMinutes,
        t.completed_at as completedAt,
        t.deleted_at as deletedAt,
        t.raw_json as rawJson,
        t.updated_at as updatedAt,
        c.state,
        c.started_at as startedAt,
        c.start_dismiss_count as startDismissCount,
        c.deadline_extension_count as deadlineExtensionCount,
        c.last_snoozed_at as lastSnoozedAt,
        c.escalation_sent_at as escalationSentAt,
        c.schedule_version as scheduleVersion
      from todoist_tasks t
      join commitment_states c on c.task_id = t.id
      where t.todoist_task_id = ?
    `).get(todoistTaskId) as TaskWithCommitment | undefined;

    return row ?? null;
  }
}
```

- [ ] **Step 3: Run repository test**

```bash
cd backend
npm test -- test/repositories/taskRepository.test.ts
```

Expected: pass.

## Task 7: Final Verification And Commit

- [ ] **Step 1: Run all backend tests**

```bash
cd backend
npm test
npm run typecheck
```

Expected: both pass.

- [ ] **Step 2: Scan for placeholders**

```bash
rg -n "\bTBD\b|\bTODO\b|fill in" backend/src backend/test docs/superpowers/plans/2026-05-08-01-backend-core-sqlite-state-machine.md
```

Expected: no matches that indicate incomplete implementation.

- [ ] **Step 3: Commit**

```bash
git add backend docs/superpowers/plans/2026-05-08-01-backend-core-sqlite-state-machine.md
git commit -m "feat: add backend core state foundation"
```

- [ ] **Step 4: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-backend-core.md` with completed steps, tests, and next recommendation.
