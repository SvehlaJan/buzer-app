# Backend Todoist Sync And REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Todoist sync polling, task action services, and the REST API consumed by Android.

**Architecture:** The backend exposes Fastify routes backed by service classes. Todoist access is isolated behind an adapter interface so tests use a fake adapter and production uses the Todoist Sync API.

**Tech Stack:** Fastify, Zod, TypeScript, SQLite repositories, Todoist Sync API, Vitest.

---

## Prerequisites

- Complete `2026-05-08-01-backend-core-sqlite-state-machine.md`.
- Keep API shape aligned with `docs/notes/api-contract.md`.
- Use Context7 for current Todoist and Fastify docs before coding.

## Files

- Create: `backend/src/todoist/todoistTypes.ts`
- Create: `backend/src/todoist/todoistAdapter.ts`
- Create: `backend/src/todoist/httpTodoistAdapter.ts`
- Create: `backend/src/services/taskSnapshotService.ts`
- Create: `backend/src/services/taskActionService.ts`
- Create: `backend/src/services/todoistSyncService.ts`
- Create: `backend/src/http/auth.ts`
- Create: `backend/src/http/routes.ts`
- Create: `backend/src/server.ts`
- Create: `backend/test/fakes/fakeTodoistAdapter.ts`
- Create: `backend/test/services/taskSnapshotService.test.ts`
- Create: `backend/test/services/taskActionService.test.ts`
- Create: `backend/test/services/todoistSyncService.test.ts`
- Create: `backend/test/http/routes.test.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `docs/notes/api-contract.md` if endpoint fields change.

## Task 1: Resolve Current Docs

- [x] **Step 1: Fetch Todoist docs**

Run:

```bash
npx ctx7@latest library "Todoist API" "Sync API task polling due_datetime duration completion update complete tasks"
npx ctx7@latest docs "/resolved/todoist/id" "Sync API incremental sync task update complete task due_datetime duration"
```

Record the resolved ID and relevant endpoint notes in the handoff.

- [x] **Step 2: Fetch Fastify docs**

Run:

```bash
npx ctx7@latest library "Fastify" "TypeScript routes hooks authentication testing inject"
npx ctx7@latest docs "/resolved/fastify/id" "TypeScript route registration hooks authentication inject testing"
```

Record the resolved ID in the handoff.

## Task 2: Define Todoist Adapter Contract

- [x] **Step 1: Write fake-adapter driven tests**

Create `backend/test/fakes/fakeTodoistAdapter.ts`:

```ts
import type { TodoistAdapter, TodoistCreateTaskInput, TodoistRescheduleInput, TodoistSyncResult } from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: TodoistSyncResult[] = [];
  createdTasks: TodoistCreateTaskInput[] = [];
  completedTaskIds: string[] = [];
  reschedules: TodoistRescheduleInput[] = [];

  async sync(_syncToken: string): Promise<TodoistSyncResult> {
    return this.syncResults.shift() ?? { syncToken: "empty-token", tasks: [] };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string }> {
    this.createdTasks.push(input);
    return { todoistTaskId: `created-${this.createdTasks.length}` };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    this.completedTaskIds.push(todoistTaskId);
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    this.reschedules.push(input);
  }
}
```

- [x] **Step 2: Implement `backend/src/todoist/todoistTypes.ts`**

```ts
export interface TodoistTaskMirror {
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: unknown;
  updatedAt: string;
}
```

- [x] **Step 3: Implement `backend/src/todoist/todoistAdapter.ts`**

```ts
import type { TodoistTaskMirror } from "./todoistTypes.js";

export interface TodoistSyncResult {
  syncToken: string;
  tasks: TodoistTaskMirror[];
}

export interface TodoistCreateTaskInput {
  title: string;
  projectId: string | null;
  assigneeId: string;
  dueDatetime: string | null;
  durationMinutes: number | null;
}

export interface TodoistRescheduleInput {
  todoistTaskId: string;
  dueDatetime: string;
  durationMinutes: number;
}

export interface TodoistAdapter {
  sync(syncToken: string): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string }>;
  completeTask(todoistTaskId: string): Promise<void>;
  rescheduleTask(input: TodoistRescheduleInput): Promise<void>;
}
```

## Task 3: Implement Sync Service

- [x] **Step 1: Write `backend/test/services/todoistSyncService.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TodoistSyncService } from "../../src/services/todoistSyncService.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TodoistSyncService", () => {
  it("stores synced tasks and updates sync token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "next-token",
      tasks: [{
        todoistTaskId: "todoist-1",
        title: "Pick up parcel",
        projectId: null,
        assigneeId: "user-self",
        creatorId: "wife",
        dueDatetime: "2026-05-08T17:00:00Z",
        durationMinutes: 20,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "todoist-1" },
        updatedAt: "2026-05-08T10:00:00Z"
      }]
    });

    const service = new TodoistSyncService(db, repo, adapter);
    await service.syncOnce();

    expect(repo.getByTodoistTaskId("todoist-1")?.title).toBe("Pick up parcel");
    const token = db.prepare("select sync_token as syncToken from todoist_sync_state where id = 1").get() as { syncToken: string };
    expect(token.syncToken).toBe("next-token");
  });
});
```

- [x] **Step 2: Add repository support for listing snapshots**

Extend `TaskRepository` with:

```ts
listAll(): TaskWithCommitment[] {
  return this.db.prepare(`
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
    order by coalesce(t.due_datetime, '9999-12-31T23:59:59Z'), t.title
  `).all() as TaskWithCommitment[];
}
```

- [x] **Step 3: Implement `backend/src/services/todoistSyncService.ts`**

```ts
import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import type { TaskRepository } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";

export class TodoistSyncService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistAdapter
  ) {}

  async syncOnce(): Promise<void> {
    const current = this.db.prepare("select sync_token as syncToken from todoist_sync_state where id = 1").get() as { syncToken: string } | undefined;
    const result = await this.todoist.sync(current?.syncToken ?? "*");
    const now = new Date().toISOString();

    const tx = this.db.transaction(() => {
      for (const task of result.tasks) {
        this.tasks.upsertTodoistTask({
          id: randomUUID(),
          todoistTaskId: task.todoistTaskId,
          title: task.title,
          projectId: task.projectId,
          assigneeId: task.assigneeId,
          creatorId: task.creatorId,
          dueDatetime: task.dueDatetime,
          durationMinutes: task.durationMinutes,
          completedAt: task.completedAt,
          deletedAt: task.deletedAt,
          rawJson: JSON.stringify(task.rawJson),
          updatedAt: task.updatedAt
        });
      }

      this.db.prepare(`
        insert into todoist_sync_state (id, sync_token, updated_at)
        values (1, ?, ?)
        on conflict(id) do update set sync_token = excluded.sync_token, updated_at = excluded.updated_at
      `).run(result.syncToken, now);
    });

    tx();
  }
}
```

- [x] **Step 4: Run sync test**

```bash
cd backend
npm test -- test/services/todoistSyncService.test.ts
```

Expected: pass.

## Task 4: Implement Snapshot Service

- [x] **Step 1: Write `backend/test/services/taskSnapshotService.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskSnapshotService } from "../../src/services/taskSnapshotService.js";

describe("TaskSnapshotService", () => {
  it("returns validation errors and computed start time", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Prepare lunch",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T12:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T08:00:00Z"
    });

    const service = new TaskSnapshotService(repo, "user-self");
    const snapshot = service.getSnapshot("2026-05-08T08:30:00Z");

    expect(snapshot.tasks[0]?.startAt).toBe("2026-05-08T11:30:00.000Z");
    expect(snapshot.tasks[0]?.validationErrors).toEqual([]);
  });
});
```

- [x] **Step 2: Implement `backend/src/services/taskSnapshotService.ts`**

```ts
import { computeStartAt } from "../domain/time.js";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class TaskSnapshotService {
  constructor(private readonly tasks: TaskRepository, private readonly currentUserId: string) {}

  getSnapshot(serverTime: string) {
    const rows = this.tasks.listAll();
    const items = rows.map((task) => {
      const validation = validateTaskStrictness({
        title: task.title,
        creatorId: task.creatorId,
        assigneeId: task.assigneeId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        currentUserId: this.currentUserId
      });

      const startAt = task.dueDatetime !== null && task.durationMinutes !== null
        ? computeStartAt(task.dueDatetime, task.durationMinutes)
        : null;

      return {
        id: task.id,
        todoistTaskId: task.todoistTaskId,
        title: task.title,
        projectId: task.projectId,
        assigneeId: task.assigneeId,
        creatorId: task.creatorId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        startAt,
        state: validation.valid ? task.state : "invalid",
        isAssignedToUser: task.assigneeId === this.currentUserId,
        isCreatedByUser: task.creatorId === this.currentUserId,
        isCompleted: task.completedAt !== null,
        isDeleted: task.deletedAt !== null,
        validationErrors: validation.errors,
        startedAt: task.startedAt,
        startDismissCount: task.startDismissCount,
        deadlineExtensionCount: task.deadlineExtensionCount,
        escalationSentAt: task.escalationSentAt,
        updatedAt: task.updatedAt
      };
    });

    return {
      scheduleVersion: Math.max(0, ...rows.map((row) => row.scheduleVersion)),
      serverTime,
      tasks: items,
      health: []
    };
  }
}
```

- [x] **Step 3: Run snapshot test**

```bash
cd backend
npm test -- test/services/taskSnapshotService.test.ts
```

Expected: pass.

## Task 5: Implement Task Actions

- [x] **Step 1: Write `backend/test/services/taskActionService.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskActionService } from "../../src/services/taskActionService.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TaskActionService", () => {
  it("extends due datetime by exactly 30 minutes", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Exercise",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 60,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, adapter);

    await service.extend("task-1", "2026-05-08T18:00:00Z");

    expect(adapter.reschedules[0]).toEqual({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T18:30:00.000Z",
      durationMinutes: 60
    });
  });
});
```

- [x] **Step 2: Implement `backend/src/services/taskActionService.ts`**

```ts
import type Database from "better-sqlite3";
import { addMinutes } from "../domain/time.js";
import type { TaskRepository } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";

export class TaskActionService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistAdapter
  ) {}

  async extend(taskId: string, now: string): Promise<void> {
    const task = this.tasks.getById(taskId);
    if (task === null) {
      throw new Error("task_not_found");
    }
    if (task.dueDatetime === null || task.durationMinutes === null) {
      throw new Error("task_missing_deadline_or_duration");
    }

    const newDue = addMinutes(task.dueDatetime, 30);
    await this.todoist.rescheduleTask({
      todoistTaskId: task.todoistTaskId,
      dueDatetime: newDue,
      durationMinutes: task.durationMinutes
    });

    this.db.prepare(`
      update commitment_states
      set deadline_extension_count = deadline_extension_count + 1,
          state = 'started',
          schedule_version = schedule_version + 1,
          updated_at = ?
      where task_id = ?
    `).run(now, taskId);
  }
}
```

- [x] **Step 3: Add `getById` to repository**

Add this method to `TaskRepository`:

```ts
getById(id: string): TaskWithCommitment | null {
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
    where t.id = ?
  `).get(id) as TaskWithCommitment | undefined;

  return row ?? null;
}
```

- [x] **Step 4: Run action test**

```bash
cd backend
npm test -- test/services/taskActionService.test.ts
```

Expected: pass.

## Task 6: Implement HTTP Routes

- [x] **Step 1: Write route test `backend/test/http/routes.test.ts`**

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { buildServer } from "../../src/server.js";

describe("HTTP routes", () => {
  it("rejects missing auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret" });

    const response = await app.inject({ method: "GET", url: "/tasks/snapshot" });
    expect(response.statusCode).toBe(401);
  });

  it("returns task snapshot with auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret" });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ tasks: [] });
  });
});
```

- [x] **Step 2: Implement `backend/src/http/auth.ts`**

```ts
import type { FastifyRequest } from "fastify";

export function requireBearerToken(request: FastifyRequest, expectedToken: string): void {
  const header = request.headers.authorization;
  if (header !== `Bearer ${expectedToken}`) {
    throw Object.assign(new Error("unauthorized"), { statusCode: 401 });
  }
}
```

- [x] **Step 3: Implement `backend/src/server.ts` and `routes.ts`**

`backend/src/server.ts`:

```ts
import Fastify from "fastify";
import type Database from "better-sqlite3";
import { registerRoutes } from "./http/routes.js";

export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
}

export function buildServer(options: BuildServerOptions) {
  const app = Fastify({ logger: true });
  registerRoutes(app, options);
  return app;
}
```

`backend/src/http/routes.ts`:

```ts
import type { FastifyInstance } from "fastify";
import type { BuildServerOptions } from "../server.js";
import { requireBearerToken } from "./auth.js";
import { TaskRepository } from "../repositories/taskRepository.js";
import { TaskSnapshotService } from "../services/taskSnapshotService.js";

export function registerRoutes(app: FastifyInstance, options: BuildServerOptions): void {
  app.addHook("preHandler", async (request) => {
    requireBearerToken(request, options.androidApiToken);
  });

  app.get("/tasks/snapshot", async () => {
    const repo = new TaskRepository(options.db);
    const service = new TaskSnapshotService(repo, options.currentUserId);
    return service.getSnapshot(new Date().toISOString());
  });
}
```

- [x] **Step 4: Run route test**

```bash
cd backend
npm test -- test/http/routes.test.ts
```

Expected: pass.

## Task 7: Final Verification And Handoff

- [x] **Step 1: Run backend verification**

```bash
cd backend
npm test
npm run typecheck
```

Expected: pass.

- [x] **Step 2: Update API contract**

If implemented endpoint request/response fields differ from `docs/notes/api-contract.md`, update the contract in the same commit.

- [x] **Step 3: Commit**

```bash
git add backend docs/notes/api-contract.md docs/superpowers/plans/2026-05-08-02-backend-todoist-sync-and-rest-api.md
git commit -m "feat: add Todoist sync and Android API"
```

- [x] **Step 4: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-backend-todoist-api.md` with docs IDs used, tests, implemented endpoints, and contract changes made.
