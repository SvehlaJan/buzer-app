# Project-Aware Task Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android task creation project-aware so all active Todoist projects are selectable, assignment is required only for assignment-capable projects, and Android-created tasks schedule alarms without direct SQLite repair.

**Architecture:** The backend owns Todoist project capability detection, stores a small project mirror, and exposes `GET /projects` to Android. Task creation validates against the selected project, separates Todoist assignment from Buzer commitment ownership, and uses Todoist REST task creation with returned-field verification. Android loads projects once per process, persists the last selected project ID, and adapts the Add Task form based on `canAssignTasks`.

**Tech Stack:** Node.js 22, TypeScript, Fastify, Zod, better-sqlite3, Todoist API v1, Vitest, Kotlin Multiplatform, Ktor 3.5, Compose Multiplatform/Material3, Android SharedPreferences, Gradle.

---

## Context

Product truth:

- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-06-13-project-aware-task-creation-design.md`

Recent handoffs:

- `docs/handoffs/2026-06-13-real-alarm-verification.md`
- `docs/handoffs/2026-06-13-project-aware-task-creation-brainstorm.md`

Important current-worktree note:

- The worktree already contains intentional dirty Android/shared changes from real-alarm verification: global HTTP logging, explicit-null JSON serialization, Add Task logs, and overlay watcher changes. Do not revert those changes. This plan evolves the affected files in place.

Todoist docs used during design:

- Context7 library ID: `/websites/developer_todoist_api_v1`
- REST create supports `project_id`, `assignee_id`, `due_datetime`, `duration`, and `duration_unit`.
- Project objects include `shared` and `can_assign_tasks`.
- Sync can return `projects`, `collaborators`, and `collaborator_states`.

## File Map

Backend:

- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Create: `backend/src/repositories/projectRepository.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/src/todoist/todoistTypes.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`
- Create: `backend/test/repositories/projectRepository.test.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`
- Modify: `backend/test/http/routes.test.ts`

Shared Kotlin:

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`

Android:

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/ProjectSelectionStore.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`

Fake backend and docs:

- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`
- Modify: `docs/notes/api-contract.md`
- Create/update: `docs/handoffs/2026-06-13-project-aware-task-creation.md`

---

### Task 1: Add Backend Project Mirror And Commitment Ownership Storage

**Files:**
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Create: `backend/src/repositories/projectRepository.ts`
- Create: `backend/test/repositories/projectRepository.test.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/test/repositories/taskRepository.test.ts`

- [x] **Step 1: Write failing project repository tests**

Create `backend/test/repositories/projectRepository.test.ts`:

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";

describe("ProjectRepository", () => {
  it("upserts projects and lists active projects by name", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ProjectRepository(db);

    repo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: JSON.stringify({ id: "project-family" }),
      updatedAt: "2026-06-13T10:00:00Z"
    });
    repo.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: JSON.stringify({ id: "project-personal" }),
      updatedAt: "2026-06-13T10:01:00Z"
    });
    repo.upsertProject({
      todoistProjectId: "project-archived",
      name: "Archived",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: true,
      rawJson: JSON.stringify({ id: "project-archived" }),
      updatedAt: "2026-06-13T10:02:00Z"
    });

    expect(repo.listActive()).toEqual([
      {
        todoistProjectId: "project-family",
        name: "Family",
        shared: true,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: JSON.stringify({ id: "project-family" }),
        updatedAt: "2026-06-13T10:00:00Z"
      },
      {
        todoistProjectId: "project-personal",
        name: "Personal",
        shared: false,
        canAssignTasks: false,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: JSON.stringify({ id: "project-personal" }),
        updatedAt: "2026-06-13T10:01:00Z"
      }
    ]);
    expect(repo.getActiveByTodoistProjectId("project-personal")?.canAssignTasks).toBe(false);
    expect(repo.getActiveByTodoistProjectId("project-archived")).toBeNull();
  });
});
```

- [x] **Step 2: Add failing migration test for existing databases**

Append to `backend/test/db/migrations.test.ts`:

```ts
it("adds project mirror and commitment owner columns to an existing database", () => {
  const db = new Database(":memory:");
  db.exec(`
    create table schema_migrations (version integer primary key, applied_at text not null);
    create table todoist_sync_state (id integer primary key check (id = 1), sync_token text not null, updated_at text not null);
    create table todoist_tasks (
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
    create table commitment_states (
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
  `);

  migrate(db);

  const commitmentColumns = db.prepare("pragma table_info(commitment_states)").all() as Array<{ name: string }>;
  const tables = db.prepare("select name from sqlite_master where type = 'table'").all() as Array<{ name: string }>;
  expect(commitmentColumns.map((column) => column.name)).toContain("commitment_owner_id");
  expect(tables.map((table) => table.name)).toContain("todoist_projects");
});
```

- [x] **Step 3: Run repository and migration tests to verify RED**

Run:

```bash
cd backend
npm test -- projectRepository migrations
```

Expected: FAIL because `ProjectRepository`, `todoist_projects`, and `commitment_owner_id` do not exist.

- [x] **Step 4: Update schema**

In `backend/src/db/schema.sql`, add `commitment_owner_id text,` inside `commitment_states` after `started_at text,`.

Also add this table before `devices`:

```sql
create table if not exists todoist_projects (
  todoist_project_id text primary key,
  name text not null,
  shared integer not null,
  can_assign_tasks integer not null,
  is_inbox_project integer not null,
  is_deleted integer not null,
  is_archived integer not null,
  raw_json text not null,
  updated_at text not null
);
```

- [x] **Step 5: Add additive migration helpers**

Modify `backend/src/db/migrations.ts`:

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
  ensureColumn(db, "commitment_states", "commitment_owner_id", "text");
  db.prepare("insert or ignore into schema_migrations (version, applied_at) values (?, ?)").run(1, new Date().toISOString());
}

function ensureColumn(db: Database.Database, table: string, column: string, definition: string): void {
  const columns = db.prepare(`pragma table_info(${table})`).all() as Array<{ name: string }>;
  if (!columns.some((entry) => entry.name === column)) {
    db.exec(`alter table ${table} add column ${column} ${definition}`);
  }
}
```

- [x] **Step 6: Create ProjectRepository**

Create `backend/src/repositories/projectRepository.ts`:

```ts
import Database from "better-sqlite3";

export interface TodoistProjectUpsert {
  todoistProjectId: string;
  name: string;
  shared: boolean;
  canAssignTasks: boolean;
  isInboxProject: boolean;
  isDeleted: boolean;
  isArchived: boolean;
  rawJson: string;
  updatedAt: string;
}

export interface TodoistProject extends TodoistProjectUpsert {}

export class ProjectRepository {
  constructor(private readonly db: Database.Database) {}

  upsertProject(project: TodoistProjectUpsert): void {
    this.db.prepare(`
      insert into todoist_projects (
        todoist_project_id, name, shared, can_assign_tasks, is_inbox_project,
        is_deleted, is_archived, raw_json, updated_at
      ) values (
        @todoistProjectId, @name, @shared, @canAssignTasks, @isInboxProject,
        @isDeleted, @isArchived, @rawJson, @updatedAt
      )
      on conflict(todoist_project_id) do update set
        name = excluded.name,
        shared = excluded.shared,
        can_assign_tasks = excluded.can_assign_tasks,
        is_inbox_project = excluded.is_inbox_project,
        is_deleted = excluded.is_deleted,
        is_archived = excluded.is_archived,
        raw_json = excluded.raw_json,
        updated_at = excluded.updated_at
    `).run(toRow(project));
  }

  getActiveByTodoistProjectId(todoistProjectId: string): TodoistProject | null {
    const row = this.db.prepare(`
      select ${projectSelect}
      from todoist_projects
      where todoist_project_id = ?
        and is_deleted = 0
        and is_archived = 0
    `).get(todoistProjectId) as ProjectRow | undefined;

    return row === undefined ? null : fromRow(row);
  }

  listActive(): TodoistProject[] {
    return this.db.prepare(`
      select ${projectSelect}
      from todoist_projects
      where is_deleted = 0
        and is_archived = 0
      order by lower(name), todoist_project_id
    `).all().map((row) => fromRow(row as ProjectRow));
  }
}

const projectSelect = `
  todoist_project_id as todoistProjectId,
  name,
  shared,
  can_assign_tasks as canAssignTasks,
  is_inbox_project as isInboxProject,
  is_deleted as isDeleted,
  is_archived as isArchived,
  raw_json as rawJson,
  updated_at as updatedAt
`;

interface ProjectRow {
  todoistProjectId: string;
  name: string;
  shared: 0 | 1;
  canAssignTasks: 0 | 1;
  isInboxProject: 0 | 1;
  isDeleted: 0 | 1;
  isArchived: 0 | 1;
  rawJson: string;
  updatedAt: string;
}

function toRow(project: TodoistProjectUpsert): ProjectRow {
  return {
    todoistProjectId: project.todoistProjectId,
    name: project.name,
    shared: project.shared ? 1 : 0,
    canAssignTasks: project.canAssignTasks ? 1 : 0,
    isInboxProject: project.isInboxProject ? 1 : 0,
    isDeleted: project.isDeleted ? 1 : 0,
    isArchived: project.isArchived ? 1 : 0,
    rawJson: project.rawJson,
    updatedAt: project.updatedAt
  };
}

function fromRow(row: ProjectRow): TodoistProject {
  return {
    todoistProjectId: row.todoistProjectId,
    name: row.name,
    shared: row.shared === 1,
    canAssignTasks: row.canAssignTasks === 1,
    isInboxProject: row.isInboxProject === 1,
    isDeleted: row.isDeleted === 1,
    isArchived: row.isArchived === 1,
    rawJson: row.rawJson,
    updatedAt: row.updatedAt
  };
}
```

- [x] **Step 7: Add commitment owner to TaskRepository**

Modify `backend/src/repositories/taskRepository.ts`:

```ts
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
  commitmentOwnerId?: string | null;
}
```

Add `c.commitment_owner_id as commitmentOwnerId,` to `taskWithCommitmentSelect` after `c.started_at as startedAt,`.

Add `commitmentOwnerId: string | null;` to `TaskWithCommitment`.

Replace the `insert or ignore into commitment_states` statement with:

```ts
      this.db.prepare(`
        insert into commitment_states (
          task_id, state, started_at, commitment_owner_id, start_dismiss_count,
          deadline_extension_count, schedule_version, updated_at
        ) values (
          @id, 'planned', null, @commitmentOwnerId, 0, 0, 1, @updatedAt
        )
        on conflict(task_id) do update set
          commitment_owner_id = coalesce(commitment_states.commitment_owner_id, excluded.commitment_owner_id)
      `).run({
        ...task,
        commitmentOwnerId: task.commitmentOwnerId ?? null
      });
```

- [x] **Step 8: Extend TaskRepository test for commitment owner**

In `backend/test/repositories/taskRepository.test.ts`, update the existing task upsert call to include:

```ts
commitmentOwnerId: "user-self"
```

Then add:

```ts
expect(snapshot?.commitmentOwnerId).toBe("user-self");
```

- [x] **Step 9: Run Task 1 tests**

Run:

```bash
cd backend
npm test -- projectRepository taskRepository migrations
npm run typecheck
```

Expected: PASS.

- [x] **Step 10: Commit Task 1**

Run:

```bash
git add backend/src/db/schema.sql backend/src/db/migrations.ts backend/src/repositories/projectRepository.ts backend/src/repositories/taskRepository.ts backend/test/repositories/projectRepository.test.ts backend/test/repositories/taskRepository.test.ts backend/test/db/migrations.test.ts
git commit -m "feat: add project mirror storage"
```

Expected: commit succeeds.

---

### Task 2: Extend Todoist Adapter For Projects And REST Task Creation

**Files:**
- Modify: `backend/src/todoist/todoistTypes.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`

- [x] **Step 1: Add failing adapter tests**

Append to `backend/test/todoist/httpTodoistAdapter.test.ts`:

```ts
it("creates tasks through REST with optional assignee", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    id: "todoist-created",
    content: "Pay bill",
    project_id: "project-1",
    responsible_uid: "user-self",
    due: { datetime: "2026-05-08T16:00:00Z" },
    duration: { amount: 30, unit: "minute" },
    added_by_uid: "user-self",
    updated_at: "2026-05-08T10:00:00Z"
  })));
  const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

  const created = await adapter.createTask({
    title: "Pay bill",
    projectId: "project-1",
    assigneeId: "user-self",
    dueDatetime: "2026-05-08T16:00:00Z",
    durationMinutes: 30
  });

  expect(fetchMock.mock.calls[0]?.[0]).toBe("https://todoist.test/api/v1/tasks");
  expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
    content: "Pay bill",
    project_id: "project-1",
    assignee_id: "user-self",
    due_datetime: "2026-05-08T16:00:00Z",
    duration: 30,
    duration_unit: "minute"
  });
  expect(created.task).toMatchObject({
    todoistTaskId: "todoist-created",
    projectId: "project-1",
    assigneeId: "user-self",
    dueDatetime: "2026-05-08T16:00:00Z",
    durationMinutes: 30
  });
});

it("omits assignee_id when creating a task without assignment", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    id: "todoist-personal",
    content: "Personal task",
    project_id: "project-personal",
    due: { datetime: "2026-05-08T18:00:00Z" },
    duration: { amount: 45, unit: "minute" },
    added_by_uid: "user-self",
    updated_at: "2026-05-08T10:00:00Z"
  })));
  const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

  await adapter.createTask({
    title: "Personal task",
    projectId: "project-personal",
    assigneeId: null,
    dueDatetime: "2026-05-08T18:00:00Z",
    durationMinutes: 45
  });

  expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).not.toHaveProperty("assignee_id");
});

it("sync maps Todoist projects", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    sync_token: "project-token",
    items: [],
    projects: [
      {
        id: "project-family",
        name: "Family",
        shared: true,
        can_assign_tasks: true,
        inbox_project: false,
        is_deleted: false,
        is_archived: false,
        updated_at: "2026-06-13T10:00:00Z"
      }
    ]
  })));
  const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

  const result = await adapter.sync("*");

  expect(result.projects).toEqual([
    {
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: {
        id: "project-family",
        name: "Family",
        shared: true,
        can_assign_tasks: true,
        inbox_project: false,
        is_deleted: false,
        is_archived: false,
        updated_at: "2026-06-13T10:00:00Z"
      },
      updatedAt: "2026-06-13T10:00:00Z"
    }
  ]);
});
```

- [x] **Step 2: Run adapter tests to verify RED**

Run:

```bash
cd backend
npm test -- httpTodoistAdapter
```

Expected: FAIL because adapter types and REST create response shape are not updated.

- [x] **Step 3: Add project and create result types**

In `backend/src/todoist/todoistTypes.ts`, add:

```ts
export interface TodoistProjectMirror {
  todoistProjectId: string;
  name: string;
  shared: boolean;
  canAssignTasks: boolean;
  isInboxProject: boolean;
  isDeleted: boolean;
  isArchived: boolean;
  rawJson: unknown;
  updatedAt: string;
}
```

In `backend/src/todoist/todoistAdapter.ts`, replace the create input/result and sync result with:

```ts
import type { TodoistProjectMirror, TodoistTaskMirror } from "./todoistTypes.js";

export interface TodoistSyncResult {
  syncToken: string;
  tasks: TodoistTaskMirror[];
  projects: TodoistProjectMirror[];
}

export interface TodoistCreateTaskInput {
  title: string;
  projectId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
}

export interface TodoistCreateTaskResult {
  todoistTaskId: string;
  task: TodoistTaskMirror;
}

export interface TodoistRescheduleInput {
  todoistTaskId: string;
  dueDatetime: string;
  durationMinutes: number;
}

export interface TodoistAdapter {
  sync(syncToken: string): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult>;
  completeTask(todoistTaskId: string): Promise<void>;
  rescheduleTask(input: TodoistRescheduleInput): Promise<void>;
}
```

- [x] **Step 4: Update FakeTodoistAdapter**

Modify `backend/test/fakes/fakeTodoistAdapter.ts`:

```ts
import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistCreateTaskResult,
  TodoistRescheduleInput,
  TodoistSyncResult
} from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: TodoistSyncResult[] = [];
  createdTasks: TodoistCreateTaskInput[] = [];
  createResults: TodoistCreateTaskResult[] = [];
  completedTaskIds: string[] = [];
  reschedules: TodoistRescheduleInput[] = [];

  async sync(_syncToken: string): Promise<TodoistSyncResult> {
    return this.syncResults.shift() ?? { syncToken: "empty-token", tasks: [], projects: [] };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult> {
    this.createdTasks.push(input);
    return this.createResults.shift() ?? {
      todoistTaskId: `created-${this.createdTasks.length}`,
      task: {
        todoistTaskId: `created-${this.createdTasks.length}`,
        title: input.title,
        projectId: input.projectId,
        assigneeId: input.assigneeId,
        creatorId: "user-self",
        dueDatetime: input.dueDatetime,
        durationMinutes: input.durationMinutes,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: `created-${this.createdTasks.length}` },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    this.completedTaskIds.push(todoistTaskId);
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    this.reschedules.push(input);
  }
}
```

- [x] **Step 5: Switch HttpTodoistAdapter createTask to REST**

In `backend/src/todoist/httpTodoistAdapter.ts`:

- Add `TodoistProjectMirror` import.
- Add fields to `SyncResponse`: `projects?: TodoistApiProject[];`.
- Add `TodoistApiProject` interface:

```ts
interface TodoistApiProject {
  id: string;
  name?: string;
  shared?: boolean;
  can_assign_tasks?: boolean;
  inbox_project?: boolean;
  is_deleted?: boolean;
  is_archived?: boolean;
  updated_at?: string;
}
```

Replace `createTask` with:

```ts
  async createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string; task: TodoistTaskMirror }> {
    const body: Record<string, unknown> = {
      content: input.title
    };
    if (input.projectId !== null) {
      body.project_id = input.projectId;
    }
    if (input.dueDatetime !== null) {
      body.due_datetime = input.dueDatetime;
    }
    if (input.durationMinutes !== null) {
      body.duration = input.durationMinutes;
      body.duration_unit = "minute";
    }
    if (input.assigneeId !== null) {
      body.assignee_id = input.assigneeId;
    }

    const response = await this.request<TodoistApiTask>("/tasks", {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" }
    });
    const task = this.mapTask(response);
    return { todoistTaskId: task.todoistTaskId, task };
  }
```

In `sync`, return projects:

```ts
    return {
      syncToken: response.sync_token ?? syncToken,
      tasks: (response.items ?? response.tasks ?? []).map((task) => this.mapTask(task)),
      projects: (response.projects ?? []).map((project) => this.mapProject(project))
    };
```

Add:

```ts
  private mapProject(project: TodoistApiProject): TodoistProjectMirror {
    return {
      todoistProjectId: String(project.id),
      name: project.name ?? "",
      shared: project.shared === true,
      canAssignTasks: project.can_assign_tasks === true,
      isInboxProject: project.inbox_project === true,
      isDeleted: project.is_deleted === true,
      isArchived: project.is_archived === true,
      rawJson: project,
      updatedAt: project.updated_at ?? new Date().toISOString()
    };
  }
```

- [x] **Step 6: Preserve Sync command behavior for complete/reschedule**

Keep `command`, `completeTask`, and `rescheduleTask` using `/sync`. In `request`, allow JSON request bodies to keep their content type:

```ts
headers: {
  authorization: `Bearer ${this.apiToken}`,
  "content-type": "application/x-www-form-urlencoded",
  ...init.headers
}
```

This existing order lets the REST create call override content type to `application/json`.

- [x] **Step 7: Run adapter tests**

Run:

```bash
cd backend
npm test -- httpTodoistAdapter
npm run typecheck
```

Expected: PASS.

If `npm run typecheck` reports existing `TodoistSyncResult` literals without `projects`, update those existing test fixtures to include `projects: []`. Do not implement Task 3 project persistence in this task.

- [x] **Step 8: Commit Task 2**

Run:

```bash
git add backend/src/todoist/todoistTypes.ts backend/src/todoist/todoistAdapter.ts backend/src/todoist/httpTodoistAdapter.ts backend/test/todoist/httpTodoistAdapter.test.ts backend/test/fakes/fakeTodoistAdapter.ts
git commit -m "feat: create Todoist tasks through REST"
```

Expected: commit succeeds.

---

### Task 3: Store Synced Projects And Expose GET /projects

**Files:**
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `docs/notes/api-contract.md`

- [x] **Step 1: Add failing sync-service project test**

Append to `backend/test/services/todoistSyncService.test.ts`:

```ts
it("stores synced projects", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const taskRepo = new TaskRepository(db);
  const projectRepo = new ProjectRepository(db);
  const adapter = new FakeTodoistAdapter();
  adapter.syncResults.push({
    syncToken: "project-token",
    tasks: [],
    projects: [{
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: { id: "project-family" },
      updatedAt: "2026-06-13T10:00:00Z"
    }]
  });

  const service = new TodoistSyncService(db, taskRepo, projectRepo, adapter);
  await service.syncOnce();

  expect(projectRepo.getActiveByTodoistProjectId("project-family")?.name).toBe("Family");
});
```

Add import:

```ts
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
```

- [x] **Step 2: Add failing route test for GET /projects**

Append to `backend/test/http/routes.test.ts`:

```ts
it("returns active projects with auth token", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const projects = new ProjectRepository(db);
  projects.upsertProject({
    todoistProjectId: "project-family",
    name: "Family",
    shared: true,
    canAssignTasks: true,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z"
  });
  const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer secret" }
  });
  await app.close();

  expect(response.statusCode).toBe(200);
  expect(response.json()).toEqual({
    projects: [{
      id: "project-family",
      name: "Family",
      canAssignTasks: true,
      shared: true,
      isInboxProject: false
    }]
  });
});
```

Add import:

```ts
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
```

- [x] **Step 3: Run tests to verify RED**

Run:

```bash
cd backend
npm test -- todoistSyncService routes
```

Expected: FAIL because `TodoistSyncService` constructor and `/projects` route are not updated.

- [x] **Step 4: Update TodoistSyncService constructor and sync**

Modify `backend/src/services/todoistSyncService.ts` constructor:

```ts
import type { ProjectRepository } from "../repositories/projectRepository.js";

export class TodoistSyncService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly todoist: TodoistAdapter
  ) {}
```

Inside the transaction in `syncOnce`, before task upserts, add:

```ts
      for (const project of result.projects) {
        this.projects.upsertProject({
          todoistProjectId: project.todoistProjectId,
          name: project.name,
          shared: project.shared,
          canAssignTasks: project.canAssignTasks,
          isInboxProject: project.isInboxProject,
          isDeleted: project.isDeleted,
          isArchived: project.isArchived,
          rawJson: JSON.stringify(project.rawJson),
          updatedAt: project.updatedAt
        });
      }
```

- [x] **Step 5: Update server wiring**

In `backend/src/server.ts`, import `ProjectRepository`, instantiate it, and pass it to `TodoistSyncService`:

```ts
import { ProjectRepository } from "./repositories/projectRepository.js";
```

```ts
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
```

```ts
    new TodoistSyncService(db, tasks, projects, todoist),
```

- [x] **Step 6: Add /projects route**

In `backend/src/http/routes.ts`, import `ProjectRepository`:

```ts
import { ProjectRepository } from "../repositories/projectRepository.js";
```

Add after `GET /tasks/snapshot`:

```ts
  app.get("/projects", async () => {
    const repo = new ProjectRepository(options.db);
    return {
      projects: repo.listActive().map((project) => ({
        id: project.todoistProjectId,
        name: project.name,
        canAssignTasks: project.canAssignTasks,
        shared: project.shared,
        isInboxProject: project.isInboxProject
      }))
    };
  });
```

- [x] **Step 7: Update existing TodoistSyncService constructors**

In existing tests and app code, change:

```ts
new TodoistSyncService(db, repo, adapter)
```

to:

```ts
new TodoistSyncService(db, repo, new ProjectRepository(db), adapter)
```

- [x] **Step 8: Update API contract note**

In `docs/notes/api-contract.md`, add:

```md
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
```

Add endpoint:

```md
### `GET /projects`

Returns active Todoist projects available for Android task creation. `canAssignTasks` tells Android whether an assignee must be selected.
```

- [x] **Step 9: Run Task 3 tests**

Run:

```bash
cd backend
npm test -- todoistSyncService routes
npm run typecheck
```

Expected: PASS.

- [x] **Step 10: Commit Task 3**

Run:

```bash
git add backend/src/services/todoistSyncService.ts backend/src/server.ts backend/src/http/routes.ts backend/test/services/todoistSyncService.test.ts backend/test/http/routes.test.ts docs/notes/api-contract.md
git commit -m "feat: expose synced Todoist projects"
```

Expected: commit succeeds.

---

### Task 4: Add Project-Aware Backend Task Creation

**Files:**
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`
- Modify: `backend/test/http/routes.test.ts`

- [x] **Step 1: Add failing TaskActionService tests**

Append to `backend/test/services/taskActionService.test.ts`:

```ts
it("creates a self-owned commitment in a non-assignable project without Todoist assignee", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const taskRepo = new TaskRepository(db);
  const projectRepo = new ProjectRepository(db);
  projectRepo.upsertProject({
    todoistProjectId: "project-personal",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

  const created = await service.create({
    title: "Personal strict task",
    projectId: "project-personal",
    assigneeId: null,
    dueDatetime: "2026-06-13T12:00:00Z",
    durationMinutes: 30,
    creatorId: "user-self",
    now: "2026-06-13T10:00:00Z"
  });

  expect(adapter.createdTasks[0]).toEqual({
    title: "Personal strict task",
    projectId: "project-personal",
    assigneeId: null,
    dueDatetime: "2026-06-13T12:00:00Z",
    durationMinutes: 30
  });
  expect(taskRepo.getById(created.id)).toMatchObject({
    assigneeId: null,
    commitmentOwnerId: "user-self"
  });
});

it("requires assignee for assignable projects", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const taskRepo = new TaskRepository(db);
  const projectRepo = new ProjectRepository(db);
  projectRepo.upsertProject({
    todoistProjectId: "project-family",
    name: "Family",
    shared: true,
    canAssignTasks: true,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

  await expect(service.create({
    title: "Shared task",
    projectId: "project-family",
    assigneeId: null,
    dueDatetime: "2026-06-13T12:00:00Z",
    durationMinutes: 30,
    creatorId: "user-self",
    now: "2026-06-13T10:00:00Z"
  })).rejects.toMatchObject({
    code: "task_validation_failed",
    statusCode: 422,
    details: { errors: ["assignee_required_for_assignable_project"] }
  });
});

it("rejects assignee for non-assignable projects", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const taskRepo = new TaskRepository(db);
  const projectRepo = new ProjectRepository(db);
  projectRepo.upsertProject({
    todoistProjectId: "project-personal",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

  await expect(service.create({
    title: "Personal task",
    projectId: "project-personal",
    assigneeId: "user-self",
    dueDatetime: "2026-06-13T12:00:00Z",
    durationMinutes: 30,
    creatorId: "user-self",
    now: "2026-06-13T10:00:00Z"
  })).rejects.toMatchObject({
    code: "task_validation_failed",
    statusCode: 422,
    details: { errors: ["assignee_not_supported_for_project"] }
  });
});
```

Add import:

```ts
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
```

- [x] **Step 2: Add failing snapshot test for commitment owner**

Append to `backend/test/services/taskSnapshotService.test.ts`:

```ts
it("treats companion-owned tasks as assigned to the current user", () => {
  const db = new Database(":memory:");
  migrate(db);
  const repo = new TaskRepository(db);
  repo.upsertTodoistTask({
    id: "task-personal",
    todoistTaskId: "todoist-personal",
    title: "Personal strict task",
    projectId: "project-personal",
    assigneeId: null,
    creatorId: "user-self",
    dueDatetime: "2026-06-13T12:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z",
    commitmentOwnerId: "user-self"
  });
  const service = new TaskSnapshotService(repo, "user-self");

  const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

  expect(snapshot.tasks[0]).toMatchObject({
    id: "task-personal",
    isAssignedToUser: true,
    state: "planned",
    validationErrors: []
  });
});
```

- [x] **Step 3: Add failing route tests for omitted nullable fields**

Append to `backend/test/http/routes.test.ts`:

```ts
it("accepts omitted assignee for non-assignable project creation", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const projects = new ProjectRepository(db);
  projects.upsertProject({
    todoistProjectId: "project-personal",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-06-13T10:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer secret" },
    payload: {
      title: "Personal strict task",
      projectId: "project-personal",
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30
    }
  });
  await app.close();

  expect(response.statusCode).toBe(200);
  expect(adapter.createdTasks[0]?.assigneeId).toBeNull();
});
```

- [x] **Step 4: Run tests to verify RED**

Run:

```bash
cd backend
npm test -- taskActionService taskSnapshotService routes
```

Expected: FAIL because creation is still assignment-only and schema requires explicit nullable fields.

- [x] **Step 5: Update route schema to normalize omitted/null fields**

In `backend/src/http/routes.ts`, replace `createTaskBody` with:

```ts
const nullableString = z.string().min(1).nullish().transform((value) => value ?? null);
const nullablePositiveInt = z.number().int().positive().nullish().transform((value) => value ?? null);

const createTaskBody = z.object({
  title: z.string().min(1),
  projectId: z.string().min(1),
  assigneeId: nullableString,
  dueDatetime: nullableString,
  durationMinutes: nullablePositiveInt
});
```

- [x] **Step 6: Inject ProjectRepository into TaskActionService**

In `backend/src/services/taskActionService.ts`, import `ProjectRepository` and update constructor:

```ts
import type { ProjectRepository } from "../repositories/projectRepository.js";
```

```ts
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly todoist: TodoistAdapter,
    private readonly currentUserId = "",
    private readonly escalationService: EscalationService | null = null
  ) {}
```

Update all `new TaskActionService(...)` calls to pass `new ProjectRepository(options.db)` or the test repository.

- [x] **Step 7: Replace create validation**

In `TaskActionService.create`, replace the validation block before Todoist create with:

```ts
    const project = this.projects.getActiveByTodoistProjectId(input.projectId);
    if (project === null) {
      throw Object.assign(new Error("Project not found"), {
        code: "project_not_found",
        statusCode: 404
      });
    }

    const errors: string[] = [];
    if (input.title.trim().length === 0) {
      errors.push("title_required");
    }
    if (input.dueDatetime === null) {
      errors.push("due_datetime_required");
    }
    if (input.durationMinutes === null) {
      errors.push("duration_required");
    }
    if (project.canAssignTasks && input.assigneeId === null) {
      errors.push("assignee_required_for_assignable_project");
    }
    if (!project.canAssignTasks && input.assigneeId !== null) {
      errors.push("assignee_not_supported_for_project");
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }

    const dueDatetime = input.dueDatetime;
    const durationMinutes = input.durationMinutes;
    if (dueDatetime === null || durationMinutes === null) {
      throw new Error("unreachable_missing_schedule_after_validation");
    }
```

- [x] **Step 8: Verify Todoist returned fields**

In `TaskActionService.create`, call Todoist with non-null schedule values and verify:

```ts
    const created = await this.todoist.createTask({
      title: input.title,
      projectId: input.projectId,
      assigneeId: input.assigneeId,
      dueDatetime,
      durationMinutes
    });
    const verificationErrors = verifyCreatedTask({
      task: created.task,
      expectedProjectId: input.projectId,
      expectedAssigneeId: input.assigneeId,
      expectedDueDatetime: dueDatetime,
      expectedDurationMinutes: durationMinutes
    });
    if (verificationErrors.length > 0) {
      throw Object.assign(new Error("Todoist create verification failed"), {
        code: "todoist_create_verification_failed",
        statusCode: 422,
        details: { errors: verificationErrors, todoistTaskId: created.todoistTaskId }
      });
    }
```

Add helper at bottom of file:

```ts
function verifyCreatedTask(input: {
  task: TaskWithTodoistFields;
  expectedProjectId: string;
  expectedAssigneeId: string | null;
  expectedDueDatetime: string;
  expectedDurationMinutes: number;
}): string[] {
  const errors: string[] = [];
  if (input.task.projectId !== input.expectedProjectId) {
    errors.push("project_mismatch");
  }
  if (input.expectedAssigneeId !== null && input.task.assigneeId !== input.expectedAssigneeId) {
    errors.push("assignee_mismatch");
  }
  if (input.task.dueDatetime !== input.expectedDueDatetime) {
    errors.push("due_datetime_mismatch");
  }
  if (input.task.durationMinutes !== input.expectedDurationMinutes) {
    errors.push("duration_mismatch");
  }
  return errors;
}

interface TaskWithTodoistFields {
  projectId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
}
```

- [x] **Step 9: Persist commitment owner for non-assignable tasks**

In the `upsertTodoistTask` call inside create, use returned task fields and add `commitmentOwnerId`:

```ts
    this.tasks.upsertTodoistTask({
      id,
      todoistTaskId: created.todoistTaskId,
      title: created.task.title,
      projectId: created.task.projectId,
      assigneeId: created.task.assigneeId,
      creatorId: created.task.creatorId ?? input.creatorId,
      dueDatetime: created.task.dueDatetime,
      durationMinutes: created.task.durationMinutes,
      completedAt: created.task.completedAt,
      deletedAt: created.task.deletedAt,
      rawJson: JSON.stringify(created.task.rawJson),
      updatedAt: input.now,
      commitmentOwnerId: project.canAssignTasks ? null : this.currentUserId
    });
```

- [x] **Step 10: Update snapshot validation for commitment ownership**

In `backend/src/services/taskSnapshotService.ts`, compute:

```ts
      const isAssignedToUser = task.assigneeId === this.currentUserId || task.commitmentOwnerId === this.currentUserId;
```

Pass a synthetic assignee into strictness validation when companion-owned:

```ts
        assigneeId: isAssignedToUser ? this.currentUserId : task.assigneeId,
```

Return:

```ts
        isAssignedToUser,
```

- [x] **Step 11: Update route constructors**

In `backend/src/http/routes.ts`, each `TaskActionService` constructor must include `new ProjectRepository(options.db)`:

```ts
const service = new TaskActionService(
  options.db,
  new TaskRepository(options.db),
  new ProjectRepository(options.db),
  options.todoist,
  options.currentUserId,
  options.escalationService ?? null
);
```

Use this full constructor in all action routes; for routes that do not need escalation, passing `options.currentUserId` and `options.escalationService ?? null` is still correct.

- [x] **Step 12: Run Task 4 tests**

Run:

```bash
cd backend
npm test -- taskActionService taskSnapshotService routes
npm run typecheck
```

Expected: PASS.

- [x] **Step 13: Commit Task 4**

Run:

```bash
git add backend/src/http/routes.ts backend/src/services/taskActionService.ts backend/src/services/taskSnapshotService.ts backend/test/services/taskActionService.test.ts backend/test/services/taskSnapshotService.test.ts backend/test/http/routes.test.ts
git commit -m "feat: validate task creation by project capability"
```

Expected: commit succeeds.

---

### Task 5: Add Shared Kotlin Project Models And Omit Null Create Fields

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`

- [x] **Step 1: Replace JSON serialization test with target behavior**

Modify `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`:

```kotlin
package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectsResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BuzerApiClientJsonTest {
    @Test
    fun createTaskRequestOmitsNullableFieldsWhenAbsent() {
        val body = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Strict task",
                projectId = "project-personal",
                assigneeId = null,
                dueDatetime = "2026-06-13T12:00:00Z",
                durationMinutes = 30
            )
        )

        assertContains(body, """"projectId":"project-personal"""")
        assertFalse(body.contains("assigneeId"))
    }

    @Test
    fun projectsResponseDecodesProjectCapabilities() {
        val response = buzerApiJson.decodeFromString<ProjectsResponse>(
            """
            {
              "projects": [
                {
                  "id": "project-family",
                  "name": "Family",
                  "canAssignTasks": true,
                  "shared": true,
                  "isInboxProject": false
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("project-family", response.projects.single().id)
        assertEquals(true, response.projects.single().canAssignTasks)
    }
}
```

- [x] **Step 2: Run shared test to verify RED**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientJsonTest' --console=plain
```

Expected: FAIL because project models do not exist and JSON still emits explicit nulls.

- [x] **Step 3: Update shared models**

In `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`, add:

```kotlin
@Serializable
data class ProjectItem(
    val id: String,
    val name: String,
    val canAssignTasks: Boolean,
    val shared: Boolean,
    val isInboxProject: Boolean
)

@Serializable
data class ProjectsResponse(
    val projects: List<ProjectItem>
)
```

Change `CreateTaskRequest`:

```kotlin
@Serializable
data class CreateTaskRequest(
    val title: String,
    val projectId: String,
    val assigneeId: String? = null,
    val dueDatetime: String,
    val durationMinutes: Int
)
```

- [x] **Step 4: Add API client getProjects and omit nulls**

In `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`, import `ProjectsResponse`, add:

```kotlin
    suspend fun getProjects(): ProjectsResponse =
        httpClient.get("$baseUrl/projects").body()
```

Change JSON config:

```kotlin
internal val buzerApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
```

Update create-task logging to handle nullable assignee:

```kotlin
"api create_task request titleLength=${request.title.length} project=${request.projectId} assignee=${request.assigneeId} " +
```

- [x] **Step 5: Run shared tests**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientJsonTest' --console=plain
```

Expected: PASS.

- [x] **Step 6: Commit Task 5**

Run:

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt
git commit -m "feat: add shared project API models"
```

Expected: commit succeeds.

---

### Task 6: Add Android Project Cache And Selection Persistence

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/ProjectSelectionStore.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`

- [x] **Step 1: Create project selection store**

Create `client/androidApp/src/main/kotlin/app/buzer/android/tasks/ProjectSelectionStore.kt`:

```kotlin
package app.buzer.android.tasks

import android.content.Context

class ProjectSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getSelectedProjectId(): String? =
        preferences.getString(KEY_SELECTED_PROJECT_ID, null)

    fun setSelectedProjectId(projectId: String) {
        preferences.edit().putString(KEY_SELECTED_PROJECT_ID, projectId).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "buzer_add_task"
        private const val KEY_SELECTED_PROJECT_ID = "selected_project_id"
    }
}
```

- [x] **Step 2: Create project repository**

Create `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`:

```kotlin
package app.buzer.android.tasks

import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem

class AddTaskProjectRepository(
    private val apiClient: BuzerApiClient
) {
    private var cachedProjects: List<ProjectItem>? = null

    suspend fun loadProjectsOnce(): List<ProjectItem> {
        cachedProjects?.let { return it }
        AppLog.info("add_task projects load_start")
        val projects = apiClient.getProjects().projects
        cachedProjects = projects
        AppLog.info("add_task projects load_success count=${projects.size}")
        return projects
    }

    suspend fun refreshProjects(): List<ProjectItem> {
        AppLog.info("add_task projects refresh_start")
        val projects = apiClient.getProjects().projects
        cachedProjects = projects
        AppLog.info("add_task projects refresh_success count=${projects.size}")
        return projects
    }
}
```

- [x] **Step 3: Add dependencies factory**

In `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`, add:

```kotlin
import android.content.Context
import app.buzer.shared.model.ProjectItem
```

Add helper:

```kotlin
    fun projectRepository(): AddTaskProjectRepository {
        val baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) {
            "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        require(BuildConfig.BUZER_BACKEND_TOKEN.isNotBlank()) {
            "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        return AddTaskProjectRepository(
            BuzerApiClient(
                baseUrl = baseUrl,
                token = BuildConfig.BUZER_BACKEND_TOKEN
            )
        )
    }

    fun projectSelectionStore(context: Context): ProjectSelectionStore =
        ProjectSelectionStore(context)
```

The existing `createTaskAction` stays, but update its log line to include `project=${request.projectId}`.

- [x] **Step 4: Run Android unit tests**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --console=plain
```

Expected: PASS.

- [x] **Step 5: Commit Task 6**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/tasks/ProjectSelectionStore.kt client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt
git commit -m "feat: cache add-task projects on Android"
```

Expected: commit succeeds.

---

### Task 7: Update Add Task Validation For Project Capability

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`

- [x] **Step 1: Replace validator tests**

Modify `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`:

```kotlin
package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ProjectItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = AddTaskAssigneeOption(label = "Me", id = "user-me")
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val assignableProject = ProjectItem(
        id = "project-family",
        name = "Family",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false
    )
    private val personalProject = ProjectItem(
        id = "project-personal",
        name = "Personal",
        canAssignTasks = false,
        shared = false,
        isInboxProject = false
    )

    @Test
    fun buildsAssignedCreateTaskRequestForAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Buy milk ",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Buy milk", result.request.title)
        assertEquals("project-family", result.request.projectId)
        assertEquals("user-me", result.request.assigneeId)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun buildsPersonalCreateTaskRequestForNonAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Personal task ",
            selectedProject = personalProject,
            selectedAssignee = null,
            deadline = deadline,
            durationText = "30",
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("project-personal", result.request.projectId)
        assertEquals(null, result.request.assigneeId)
    }

    @Test
    fun rejectsMissingProject() {
        val result = AddTaskFormValidator.validate("Buy milk", null, me, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Project is required"), result)
    }

    @Test
    fun rejectsMissingAssigneeForAssignableProject() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, null, deadline, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Assignee is required for this project"), result)
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedProject = assignableProject,
            selectedAssignee = AddTaskAssigneeOption("Me", ""),
            deadline = deadline,
            durationText = "45",
            zoneId = zone
        )

        assertEquals(AddTaskFormResult.Invalid("Me assignee ID is not configured"), result)
    }

    @Test
    fun rejectsMissingDeadline() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, me, null, "45", zone)

        assertEquals(AddTaskFormResult.Invalid("Deadline is required"), result)
    }

    @Test
    fun rejectsInvalidDuration() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, me, deadline, "0", zone)

        assertEquals(
            AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes"),
            result
        )
    }
}
```

- [x] **Step 2: Run validator test to verify RED**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest' --console=plain
```

Expected: FAIL because validator signature and project rules are not updated.

- [x] **Step 3: Update validator**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`:

```kotlin
package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectItem
import java.time.ZoneId

data class AddTaskAssigneeOption(
    val label: String,
    val id: String
)

sealed interface AddTaskFormResult {
    data class Valid(val request: CreateTaskRequest) : AddTaskFormResult
    data class Invalid(val message: String) : AddTaskFormResult
}

object AddTaskFormValidator {
    fun validate(
        title: String,
        selectedProject: ProjectItem?,
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required")
        }
        if (selectedProject.canAssignTasks) {
            if (selectedAssignee == null) {
                return AddTaskFormResult.Invalid("Assignee is required for this project")
            }
            if (selectedAssignee.id.isBlank()) {
                return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
            }
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Deadline is required")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                projectId = selectedProject.id,
                assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = duration
            )
        )
    }
}
```

- [x] **Step 4: Run validator test**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest' --console=plain
```

Expected: PASS.

- [x] **Step 5: Commit Task 7**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt
git commit -m "feat: validate add-task project selection"
```

Expected: commit succeeds.

---

### Task 8: Update Android Add Task Screen With Project Picker

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`

- [x] **Step 1: Update AddTaskScreen parameters**

Modify the function signature in `AddTaskScreen.kt`:

```kotlin
@Composable
fun AddTaskScreen(
    onBack: () -> Unit,
    createTask: suspend (CreateTaskRequest) -> Unit = AddTaskDependencies.createTaskAction(),
    assigneeOptions: List<AddTaskAssigneeOption> = AddTaskDependencies.assigneeOptions(),
    loadProjects: suspend () -> List<ProjectItem> = AddTaskDependencies.projectRepository()::loadProjectsOnce,
    refreshProjects: suspend () -> List<ProjectItem> = AddTaskDependencies.projectRepository()::refreshProjects,
    initialSelectedProjectId: String? = AddTaskDependencies.projectSelectionStore(LocalContext.current).getSelectedProjectId(),
    persistSelectedProjectId: (String) -> Unit = { projectId ->
        AddTaskDependencies.projectSelectionStore(LocalContext.current).setSelectedProjectId(projectId)
    }
)
```

Add imports:

```kotlin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import app.buzer.shared.model.ProjectItem
```

- [x] **Step 2: Add project state**

Inside `AddTaskScreen`, add state after `title`:

```kotlin
    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var projectsLoading by remember { mutableStateOf(true) }
    var projectLoadError by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectItem?>(null) }
    var projectMenuExpanded by remember { mutableStateOf(false) }
```

Add loader:

```kotlin
    fun applyLoadedProjects(loaded: List<ProjectItem>) {
        projects = loaded
        selectedProject = loaded.firstOrNull { it.id == initialSelectedProjectId } ?: loaded.firstOrNull()
        selectedProject?.let { persistSelectedProjectId(it.id) }
    }

    LaunchedEffect(Unit) {
        projectsLoading = true
        projectLoadError = null
        try {
            applyLoadedProjects(loadProjects())
        } catch (error: Exception) {
            AppLog.error("add_task projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            projectLoadError = error.message ?: "Project loading failed"
        } finally {
            projectsLoading = false
        }
    }
```

Implementation note: the final code intentionally does not fall back to `loaded.firstOrNull()` on first open. It only preselects a persisted/initial project ID so project selection remains mandatory.

- [x] **Step 3: Add project selector UI before assignee row**

Before the assignee `Row`, insert:

```kotlin
        OutlinedButton(
            onClick = { projectMenuExpanded = true },
            enabled = !projectsLoading && projects.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Project picker" }
        ) {
            Text(
                when {
                    projectsLoading -> "Loading projects..."
                    selectedProject != null -> selectedProject!!.name
                    else -> "Select project"
                }
            )
        }
        DropdownMenu(
            expanded = projectMenuExpanded,
            onDismissRequest = { projectMenuExpanded = false }
        ) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Text(if (project.canAssignTasks) project.name else "${project.name} (personal)")
                    },
                    onClick = {
                        selectedProject = project
                        selectedAssignee = null
                        persistSelectedProjectId(project.id)
                        projectMenuExpanded = false
                        errorText = null
                    }
                )
            }
        }
        projectLoadError?.let {
            Text(it)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        projectsLoading = true
                        projectLoadError = null
                        try {
                            applyLoadedProjects(refreshProjects())
                        } catch (error: Exception) {
                            AppLog.error("add_task projects refresh_failed type=${error::class.simpleName} message=${error.message}", error)
                            projectLoadError = error.message ?: "Project loading failed"
                        } finally {
                            projectsLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry")
            }
        }
```

- [x] **Step 4: Show assignee row only for assignable projects**

Wrap the existing assignee `Row`:

```kotlin
        if (selectedProject?.canAssignTasks == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assigneeOptions.forEach { option ->
                    val selected = selectedAssignee == option
                    val label = if (selected) "${option.label} selected" else option.label
                    if (selected) {
                        Button(
                            onClick = { selectedAssignee = option },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                selectedAssignee = option
                                errorText = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
```

- [x] **Step 5: Update submit validation and logs**

Change submit log:

```kotlin
                    "add_task submit_clicked titleLength=${title.length} project=${selectedProject?.id} " +
                        "assignee=${selectedAssignee?.id} deadline=${deadline?.formatForDisplay()} durationInput=$durationMinutes"
```

Change validator call:

```kotlin
AddTaskFormValidator.validate(title, selectedProject, selectedAssignee, deadline, durationMinutes)
```

Change create-start log:

```kotlin
                                    "add_task create_start titleLength=${result.request.title.length} " +
                                        "project=${result.request.projectId} assignee=${result.request.assigneeId} " +
                                        "due=${result.request.dueDatetime} duration=${result.request.durationMinutes}"
```

Disable Save while projects are loading or failed:

```kotlin
            enabled = !submitting && !projectsLoading && projectLoadError == null,
```

- [x] **Step 6: Run Android unit tests and assemble**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [x] **Step 7: Commit Task 8**

Run:

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt
git commit -m "feat: add project picker to add-task screen"
```

Expected: commit succeeds.

---

### Task 9: Update Fake Backend For Android Verification

**Files:**
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`

- [x] **Step 1: Add failing fake backend tests**

Append to `tools/fake-backend/src/server.test.ts`:

```ts
test("projects are returned for add-task screen", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().projects[0].id, "project-test");
  assert.equal(response.json().projects[0].canAssignTasks, true);
});

test("create task accepts personal project without assignee", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Personal task",
      projectId: "project-personal",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 201);
});
```

- [x] **Step 2: Run fake backend tests to verify RED**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: FAIL because `/projects` is missing.

- [x] **Step 3: Implement fake projects route**

In `tools/fake-backend/src/server.ts`, add before `POST /tasks`:

```ts
  app.get("/projects", async () => {
    await maybeDelay();
    return {
      projects: [
        {
          id: "project-test",
          name: "Test Shared",
          canAssignTasks: true,
          shared: true,
          isInboxProject: false
        },
        {
          id: "project-personal",
          name: "Test Personal",
          canAssignTasks: false,
          shared: false,
          isInboxProject: false
        }
      ]
    };
  });
```

Leave `POST /tasks` permissive enough to record either payload with or without `assigneeId`.

- [x] **Step 4: Run fake backend tests**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS.

- [x] **Step 5: Commit Task 9**

Run:

```bash
git add tools/fake-backend/src/server.ts tools/fake-backend/src/server.test.ts
git commit -m "feat: add fake project list endpoint"
```

Expected: commit succeeds.

---

### Task 10: Full Verification, Device Smoke, And Handoff

**Files:**
- Create/update: `docs/handoffs/2026-06-13-project-aware-task-creation.md`

- [ ] **Step 1: Run backend tests and typecheck**

Run:

```bash
cd backend
npm test
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 2: Run fake backend tests**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS.

- [ ] **Step 3: Run Android/shared tests and assemble**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [ ] **Step 4: Install Android build on the connected device**

Run:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install
```

Expected: PASS. Evidence includes install success for `app.buzer.android`.

- [ ] **Step 5: Verify backend deployment path before live destructive test**

Run a backend smoke against local tests first. If deploying to the VPS in the same session, use the repo's existing deployment notes:

```bash
sed -n '1,220p' docs/notes/vps-deployment.md
```

Expected: the deployment command/path is identified before touching the VPS.

- [ ] **Step 6: Manual live verification with user operating the device**

Use the connected Pixel and live backend after deployment:

1. Clear logs:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat-clear
```

2. Ask the user to open Add Task.
3. Confirm projects load.
4. Ask the user to create one task in an assignable project with `Me` selected, due 5 minutes in the future, duration 3 minutes.
5. Pull logs:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat 240
```

Expected: `POST /tasks` returns success, snapshot contains the created task, and alarms are scheduled without SQLite repair.

6. Ask the user to create one task in a non-assignable project with due 5 minutes in the future and duration 3 minutes.
7. Pull alarms:

```bash
BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh alarms
```

Expected: start and deadline alarms exist for the personal commitment.

- [ ] **Step 7: Write handoff**

Create or update `docs/handoffs/2026-06-13-project-aware-task-creation.md` with:

```md
# 2026-06-13 Project-Aware Task Creation

## Task / Plan

- Plan: `docs/superpowers/plans/2026-06-13-project-aware-task-creation.md`
- Checkbox status: record completed and skipped steps.

## Files Changed

- List backend, shared, Android, fake-backend, and docs files changed.

## Commands Run

Verification:
- Command: `cd backend && npm test`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

Verification:
- Command: `cd backend && npm run typecheck`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

Verification:
- Command: `cd backend && npm run build`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

Verification:
- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
- Result: PASS/FAIL/SKIPPED
- Evidence: summarize output.

## Manual Verification

- Record assignable project create result.
- Record non-assignable project create result.
- Record alarm scheduling result.

## Known Failures / Skipped Checks

- Record any deployment, device, Todoist, or WhatsApp checks skipped.

## Assumptions

- Record Todoist project capability observations.

## Next Recommended Step

- Recommend whether to proceed to Home in-progress UI, duplicate overlay repro, or boot recovery.
```

- [ ] **Step 8: Commit final docs and any remaining implementation files**

Run:

```bash
git status --short
git add docs/handoffs/2026-06-13-project-aware-task-creation.md
git commit -m "docs: record project-aware creation verification"
```

Expected: commit succeeds if only handoff docs remain. If implementation files are still unstaged, inspect and commit coherent remaining changes with a scope-specific message before this docs commit.

---

## Final Acceptance Criteria

- Backend `GET /projects` returns active Todoist projects with `canAssignTasks`.
- Backend `POST /tasks` accepts omitted nullable fields.
- Assignable projects require assignee.
- Non-assignable projects reject assignee and create current-user Buzer commitments.
- Android Add Task requires project selection and persists the selected project ID.
- Android Add Task requires assignee only when the selected project can assign tasks.
- Android create requests omit absent `assigneeId`.
- Real device verification creates at least one Android task that schedules alarms without direct SQLite repair.
