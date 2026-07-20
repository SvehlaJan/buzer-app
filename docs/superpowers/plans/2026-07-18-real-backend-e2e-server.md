# Real-Backend E2E Server (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot the real backend on a real port against a stateful in-memory Todoist simulation so the existing 25 Android E2E tests exercise real business logic instead of `tools/fake-backend`.

**Architecture:** A self-contained `E2ETodoistAdapter` (test tree only, implements `TodoistAdapter`) owns an in-memory Todoist "world"; mutations apply Todoist-side semantics and `sync()` returns the full world state, closing the coordinator's effect-confirmation loop. `backend/test/e2eServer.ts` wires it into the unmodified `buildServer()`, seeds the 15 fixture tasks + 2 projects through the production sync path, and adds the six old-fake-parity debug endpoints outside production code. A vitest conformance suite in the default `npm test` run is the drift guard.

**Tech Stack:** TypeScript (Node 22, ESM), Fastify 5, better-sqlite3 (`:memory:`), vitest, tsx, Bash, Kotlin (androidTest, JUnit4 + Compose test rules).

**Spec:** `docs/superpowers/specs/2026-07-18-real-backend-e2e-server-design.md`

**Execution context:** Real backend code — execute in an isolated worktree (superpowers:using-git-worktrees), branch name suggestion `e2e-real-backend`. Never `git add -A`; stage listed files explicitly (concurrent sessions share this checkout when not in a worktree).

---

## File Structure

| File | Responsibility |
| --- | --- |
| Create `backend/test/fakes/e2eTodoistAdapter.ts` | `E2ETodoistWorld` (in-memory Todoist state + mutation semantics) and `E2ETodoistAdapter` (implements `TodoistAdapter`, scenario gating). Zero backend logic. |
| Create `backend/test/fakes/e2eTodoistAdapter.test.ts` | Unit tests for world semantics and scenario presets. |
| Create `backend/test/fakes/e2eFixtures.ts` | The 15 fixture task mirrors (+6 checklist child mirrors), 2 project mirrors, commitment nudges, occurrence-key pins. Relative due times computed at call time. |
| Create `backend/test/fakes/e2eFixtures.test.ts` | Unit tests for fixture derivation invariants. |
| Create `backend/test/e2eServer.ts` | `buildE2EServer()`: `:memory:` DB + migrations, seeding, `buildServer()` wiring, debug routes, CLI entry (`PORT`, default 18433). |
| Create `backend/test/e2eServer.test.ts` | Conformance suite (drift guard): seeding shape, every mutation endpoint's happy path completes, preset pending outcomes, debug endpoint parity, reset monotonicity. Runs in `npm test`. |
| Modify `backend/package.json` | Add `"e2e-server": "tsx test/e2eServer.ts"` script. |
| Create `scripts/e2e-real-backend.sh` | Boots the e2e server, waits for `/health`, runs the same Gradle invocation as `scripts/e2e-fake-backend.sh` (which stays untouched). |
| Modify `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt` | Additive: `setScenario(name)`, `taskReconciliationStatus(taskId)` (authorized snapshot read), `authorized` flag on `request`. |
| Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/RealBackendScenarioTest.kt` | The 2 new preset instrumentation tests. Self-skips (JUnit `Assume`) when `/__scenario` is absent so the old fake-backend script and CI stay green in Phase 1. |

Key facts the implementer must know (verified against the codebase, do not rediscover):

- Sync assigns `randomUUID()` as the local id for unseen tasks (`todoistSyncService.ts:69`). Android tests hit URLs like `/tasks/task-start/start`, so fixture rows are **pre-upserted with pinned ids before the first sync**; sync then keeps them (`on conflict(todoist_task_id)` update).
- The blocker tests launch activities with `EXTRA_OCCURRENCE_KEY = "occurrence-<taskId>-1"` (`BlockerActivityFakeBackendTest.kt:491-505`), but sync rotates occurrence keys to random UUIDs. Seeding therefore **pins `commitment_states.occurrence_key` to `occurrence-<id>-1` after the boot sync** (only the key — sync's due/fingerprint anchors stay, so later syncs decide `unchanged`).
- First sync of a fresh commitment row decides `rotate: "initialized"` (`occurrenceReconciler.ts:41-43`), so seeding checked children on recurring fixtures is safe.
- `mirror_contract_version = 2` is set by the real full sync itself (`todoistSyncService.ts:139-145`); no manual mirror-ready poking.
- `snapshot_generations` is bumped by SQLite triggers on every row change (`migrations.ts:125-135`). `/__reset` **deletes rows instead of recreating the DB**, so generation stays monotonic with zero special handling.
- `buildServer` rate-limits 60 req/min by default; the e2e server passes `{ max: 100_000, timeWindowMs: 60_000 }`.
- Config parity: `currentUserId: "user-me"`, `androidApiToken: "test-token"`, port 18433 (see `scripts/e2e-fake-backend.sh:5-6,78`). Project fixtures must be named "Test Shared" / "Test Personal" (AddTask UI selects by name).
- The old fake records only the action-metadata subset of bodies (`tools/fake-backend/src/server.ts:36-40`); `/__calls` must record exactly that subset to preserve `assertRecordedWithout` semantics.
- `waitForErrorLog(..., "forced_failure")` greps the error **code**, so `__fail-actions` responses must carry `code: "forced_failure"`.

---

### Task 1: E2E Todoist world + adapter

**Files:**
- Create: `backend/test/fakes/e2eTodoistAdapter.ts`
- Test: `backend/test/fakes/e2eTodoistAdapter.test.ts`

- [x] **Step 1: Write the failing test**

```typescript
import { describe, expect, it } from "vitest";
import { E2ETodoistAdapter } from "./e2eTodoistAdapter.js";
import type { TodoistProjectMirror, TodoistTaskMirror } from "../../src/todoist/todoistTypes.js";

function mirror(overrides: Partial<TodoistTaskMirror> & Pick<TodoistTaskMirror, "todoistTaskId" | "title">): TodoistTaskMirror {
  return {
    description: null, projectId: "project-test", parentId: null, childOrder: null,
    assigneeId: "user-me", creatorId: "user-me", dueDate: null,
    dueDatetime: "2026-07-18T10:00:00.000Z", dueString: null, dueLang: null, dueTimezone: null,
    isRecurring: false, durationAmount: 30, durationUnit: "minute", durationMinutes: 30,
    completedAt: null, deletedAt: null, rawJson: {}, updatedAt: "2026-07-18T09:00:00.000Z",
    ...overrides
  };
}

const project: TodoistProjectMirror = {
  todoistProjectId: "project-test", name: "Test Shared", shared: true, canAssignTasks: true,
  isInboxProject: false, isDeleted: false, isArchived: false, rawJson: {}, updatedAt: "2026-07-18T09:00:00.000Z"
};

function adapterWith(...tasks: TodoistTaskMirror[]): E2ETodoistAdapter {
  const adapter = new E2ETodoistAdapter("user-me");
  adapter.world.load({ tasks, projects: [project] });
  return adapter;
}

async function syncedTask(adapter: E2ETodoistAdapter, todoistTaskId: string): Promise<TodoistTaskMirror> {
  const result = await adapter.sync("*");
  const task = result.tasks.find((candidate) => candidate.todoistTaskId === todoistTaskId);
  if (task === undefined) throw new Error(`missing ${todoistTaskId}`);
  return task;
}

describe("E2ETodoistWorld semantics", () => {
  it("returns the full world from sync with a fresh token each time", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const first = await adapter.sync("*");
    const second = await adapter.sync(first.syncToken);
    expect(first.tasks).toHaveLength(1);
    expect(second.tasks).toHaveLength(1);
    expect(second.syncToken).not.toBe(first.syncToken);
    expect(first.projects[0]?.todoistProjectId).toBe("project-test");
  });

  it("itemClose sets completedAt and itemUncomplete clears it", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
    await adapter.itemUncomplete({ commandUuid: "c-2", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).toBeNull();
  });

  it("itemUpdate applies title, duration, and a due replacement", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemUpdate({
      commandUuid: "c-1", todoistTaskId: "t-1", title: "Renamed",
      duration: { amount: 90, unit: "minute" },
      due: { kind: "replace", datetime: "2026-07-19T08:00:00.000Z", string: null, lang: null, timezone: null },
      assignment: { kind: "preserve" }
    });
    const task = await syncedTask(adapter, "t-1");
    expect(task.title).toBe("Renamed");
    expect(task.durationMinutes).toBe(90);
    expect(task.dueDatetime).toBe("2026-07-19T08:00:00.000Z");
    expect(task.isRecurring).toBe(false);
  });

  it("itemAdd creates a root and a child under it", async () => {
    const adapter = adapterWith();
    const root = await adapter.itemAdd({
      kind: "root", commandUuid: "c-1", tempId: "tmp-1", title: "Created root",
      projectId: "project-test", assigneeId: "user-me",
      due: { kind: "replace", datetime: "2026-07-19T08:00:00.000Z", string: null, lang: null, timezone: null },
      duration: { amount: 30, unit: "minute" }
    });
    expect(root.todoistTaskId).not.toBeNull();
    const child = await adapter.itemAdd({
      kind: "child", commandUuid: "c-2", tempId: "tmp-2", title: "Created child",
      projectId: "project-test", parentTodoistTaskId: root.todoistTaskId as string
    });
    const childTask = await syncedTask(adapter, child.todoistTaskId as string);
    expect(childTask.parentId).toBe(root.todoistTaskId);
    expect(childTask.childOrder).toBe(1);
    expect(childTask.dueDatetime).toBeNull();
  });

  it("itemMove changes the project and itemDelete tombstones", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemMove({ commandUuid: "c-1", todoistTaskId: "t-1", projectId: "project-personal" });
    expect((await syncedTask(adapter, "t-1")).projectId).toBe("project-personal");
    await adapter.itemDelete({ commandUuid: "c-2", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).deletedAt).not.toBeNull();
  });

  it("itemUpdateDateComplete advances a daily recurring due by one day", async () => {
    const task = mirror({
      todoistTaskId: "t-1", title: "Daily", isRecurring: true,
      dueString: "every day at 10:00", dueLang: "en", dueTimezone: "Europe/Bratislava"
    });
    const adapter = adapterWith(task);
    await adapter.itemUpdateDateComplete({ commandUuid: "c-1", task });
    expect((await syncedTask(adapter, "t-1")).dueDatetime).toBe("2026-07-19T10:00:00.000Z");
  });
});

describe("scenario presets", () => {
  it("after_command_sent_timeout throws from mutations and leaves the world untouched", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    adapter.setScenario("after_command_sent_timeout");
    await expect(adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" })).rejects.toThrow();
    adapter.setScenario("none");
    expect((await syncedTask(adapter, "t-1")).completedAt).toBeNull();
  });

  it("sync_lag_effect_unknown applies mutations but hides them from sync", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    adapter.setScenario("sync_lag_effect_unknown");
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });
    expect((await adapter.sync("*")).tasks).toHaveLength(0);
    adapter.setScenario("none");
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
Expected: FAIL — cannot resolve `./e2eTodoistAdapter.js`.

- [x] **Step 3: Write the implementation**

`backend/test/fakes/e2eTodoistAdapter.ts`:

```typescript
import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistCreateTaskResult,
  TodoistLegacyUpdateCommandIdentities,
  TodoistRescheduleInput,
  TodoistSyncResult,
  TodoistUpdateTaskInput
} from "../../src/todoist/todoistAdapter.js";
import type {
  TodoistChildItemAddInput,
  TodoistDueMutation,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistRecurringCompletionInput,
  TodoistRootItemAddInput
} from "../../src/todoist/todoistMutationGateway.js";
import type { TodoistProjectMirror, TodoistTaskMirror } from "../../src/todoist/todoistTypes.js";

export type E2EScenario = "none" | "after_command_sent_timeout" | "sync_lag_effect_unknown";
export const E2E_SCENARIOS: readonly E2EScenario[] = ["none", "after_command_sent_timeout", "sync_lag_effect_unknown"];

/**
 * In-memory Todoist-side state. Models only what Todoist itself would do behind the
 * adapter interface; every backend behavior (snapshots, checklists, reconciliation)
 * runs in the real services on top of this.
 */
export class E2ETodoistWorld {
  private tasks = new Map<string, TodoistTaskMirror>();
  private projects = new Map<string, TodoistProjectMirror>();
  private createdCount = 0;

  constructor(private readonly currentUserId: string) {}

  load(seed: { tasks: TodoistTaskMirror[]; projects: TodoistProjectMirror[] }): void {
    this.tasks = new Map(seed.tasks.map((task) => [task.todoistTaskId, structuredClone(task)]));
    this.projects = new Map(seed.projects.map((project) => [project.todoistProjectId, structuredClone(project)]));
    this.createdCount = 0;
  }

  state(): { tasks: TodoistTaskMirror[]; projects: TodoistProjectMirror[] } {
    return {
      tasks: [...this.tasks.values()].map((task) => structuredClone(task)),
      projects: [...this.projects.values()].map((project) => structuredClone(project))
    };
  }

  addRoot(input: TodoistRootItemAddInput): string {
    const todoistTaskId = this.nextCreatedId();
    this.tasks.set(todoistTaskId, {
      todoistTaskId,
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId,
      parentId: null,
      childOrder: null,
      assigneeId: input.assigneeId,
      creatorId: this.currentUserId,
      dueDate: null,
      dueDatetime: input.due.kind === "replace" ? input.due.datetime : null,
      dueString: input.due.kind === "replace" ? input.due.string : null,
      dueLang: input.due.kind === "replace" ? input.due.lang : null,
      dueTimezone: input.due.kind === "replace" ? input.due.timezone : null,
      isRecurring: input.due.kind === "replace" && input.due.string !== null,
      durationAmount: input.duration?.amount ?? null,
      durationUnit: input.duration?.unit ?? null,
      durationMinutes: input.duration?.amount ?? null,
      completedAt: null,
      deletedAt: null,
      rawJson: {},
      updatedAt: new Date().toISOString()
    });
    return todoistTaskId;
  }

  addChild(input: TodoistChildItemAddInput): string {
    const todoistTaskId = this.nextCreatedId();
    const siblings = [...this.tasks.values()].filter((task) => task.parentId === input.parentTodoistTaskId);
    this.tasks.set(todoistTaskId, {
      todoistTaskId,
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId,
      parentId: input.parentTodoistTaskId,
      childOrder: siblings.length + 1,
      assigneeId: null,
      creatorId: this.currentUserId,
      dueDate: null, dueDatetime: null, dueString: null, dueLang: null, dueTimezone: null,
      isRecurring: false,
      durationAmount: null, durationUnit: null, durationMinutes: null,
      completedAt: null, deletedAt: null, rawJson: {},
      updatedAt: new Date().toISOString()
    });
    return todoistTaskId;
  }

  applyUpdate(input: TodoistItemUpdateInput): void {
    const task = this.require(input.todoistTaskId);
    if (input.title !== undefined) task.title = input.title;
    if (input.description !== undefined) task.description = input.description;
    if (input.duration !== undefined) {
      task.durationAmount = input.duration.amount;
      task.durationUnit = input.duration.unit;
      task.durationMinutes = input.duration.amount;
    }
    this.applyDue(task, input.due);
    if (input.assignment.kind === "assign") task.assigneeId = input.assignment.assigneeId;
    if (input.assignment.kind === "unassign") task.assigneeId = null;
    task.updatedAt = new Date().toISOString();
  }

  move(todoistTaskId: string, projectId: string): void {
    const task = this.require(todoistTaskId);
    task.projectId = projectId;
    task.updatedAt = new Date().toISOString();
  }

  close(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.completedAt = new Date().toISOString();
    task.updatedAt = task.completedAt;
  }

  uncomplete(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.completedAt = null;
    task.updatedAt = new Date().toISOString();
  }

  tombstone(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.deletedAt = new Date().toISOString();
    task.updatedAt = task.deletedAt;
  }

  advanceRecurringDue(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    if (task.dueDatetime === null) return;
    const rule = task.dueString ?? "";
    const days = rule.startsWith("every year") ? 365
      : rule.startsWith("every month") ? 30
        : /^every (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) at/.test(rule) ? 7
          : 1;
    task.dueDatetime = new Date(new Date(task.dueDatetime).getTime() + days * 86_400_000).toISOString();
    task.updatedAt = new Date().toISOString();
  }

  getTask(todoistTaskId: string): TodoistTaskMirror {
    return structuredClone(this.require(todoistTaskId));
  }

  private nextCreatedId(): string {
    this.createdCount += 1;
    return `e2e-created-${this.createdCount}`;
  }

  private applyDue(task: TodoistTaskMirror, due: TodoistDueMutation): void {
    if (due.kind === "preserve") return;
    if (due.kind === "clear") {
      task.dueDate = null; task.dueDatetime = null; task.dueString = null;
      task.dueLang = null; task.dueTimezone = null; task.isRecurring = false;
      return;
    }
    task.dueDatetime = due.datetime;
    task.dueString = due.string;
    task.dueLang = due.lang;
    task.dueTimezone = due.timezone;
    task.isRecurring = due.string !== null;
  }

  private require(todoistTaskId: string): TodoistTaskMirror {
    const task = this.tasks.get(todoistTaskId);
    if (task === undefined) throw new Error(`e2e world has no task ${todoistTaskId}`);
    return task;
  }
}

export class E2ETodoistAdapter implements TodoistAdapter {
  readonly world: E2ETodoistWorld;
  private scenario: E2EScenario = "none";
  private syncCount = 0;

  constructor(currentUserId: string) {
    this.world = new E2ETodoistWorld(currentUserId);
  }

  setScenario(name: E2EScenario): void {
    this.scenario = name;
  }

  currentScenario(): E2EScenario {
    return this.scenario;
  }

  async sync(_syncToken: string): Promise<TodoistSyncResult> {
    this.syncCount += 1;
    const syncToken = `e2e-sync-${this.syncCount}`;
    if (this.scenario === "sync_lag_effect_unknown") {
      return { syncToken, tasks: [], projects: [] };
    }
    const state = this.world.state();
    return { syncToken, tasks: state.tasks, projects: state.projects };
  }

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    this.guardCommand();
    return { todoistTaskId: input.kind === "root" ? this.world.addRoot(input) : this.world.addChild(input) };
  }

  async itemUpdate(input: TodoistItemUpdateInput): Promise<void> {
    this.guardCommand();
    this.world.applyUpdate(input);
  }

  async itemMove(input: TodoistItemMoveInput): Promise<void> {
    this.guardCommand();
    this.world.move(input.todoistTaskId, input.projectId);
  }

  async itemClose(input: TodoistItemIdentityInput): Promise<void> {
    this.guardCommand();
    this.world.close(input.todoistTaskId);
  }

  async itemUncomplete(input: TodoistItemIdentityInput): Promise<void> {
    this.guardCommand();
    this.world.uncomplete(input.todoistTaskId);
  }

  async itemDelete(input: TodoistItemIdentityInput): Promise<void> {
    this.guardCommand();
    this.world.tombstone(input.todoistTaskId);
  }

  async itemUpdateDateComplete(input: TodoistRecurringCompletionInput): Promise<void> {
    this.guardCommand();
    this.world.advanceRecurringDue(input.task.todoistTaskId);
  }

  async createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult> {
    this.guardCommand();
    const todoistTaskId = this.world.addRoot({
      kind: "root",
      commandUuid: "e2e-legacy-create",
      tempId: "e2e-legacy-create",
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId ?? "project-test",
      assigneeId: input.assigneeId,
      due: input.dueDatetime === null
        ? { kind: "clear" }
        : { kind: "replace", datetime: input.dueDatetime, string: null, lang: null, timezone: null },
      duration: input.durationMinutes === null ? null : { amount: input.durationMinutes, unit: "minute" }
    });
    return { todoistTaskId, task: this.world.getTask(todoistTaskId) };
  }

  async completeTask(todoistTaskId: string, _commandUuid: string): Promise<void> {
    this.guardCommand();
    this.world.close(todoistTaskId);
  }

  async rescheduleTask(input: TodoistRescheduleInput, _commandUuid: string): Promise<void> {
    this.guardCommand();
    this.world.applyUpdate({
      commandUuid: "e2e-legacy-reschedule",
      todoistTaskId: input.todoistTaskId,
      ...(input.description === undefined ? {} : { description: input.description }),
      duration: { amount: input.durationMinutes, unit: "minute" },
      due: { kind: "replace", datetime: input.dueDatetime, string: null, lang: null, timezone: null },
      assignment: { kind: "preserve" }
    });
  }

  async updateTask(input: TodoistUpdateTaskInput, _commands: TodoistLegacyUpdateCommandIdentities): Promise<void> {
    this.guardCommand();
    this.world.move(input.todoistTaskId, input.projectId);
    this.world.applyUpdate({
      commandUuid: "e2e-legacy-update",
      todoistTaskId: input.todoistTaskId,
      title: input.title,
      description: input.description,
      duration: { amount: input.durationMinutes, unit: "minute" },
      due: { kind: "replace", datetime: input.dueDatetime, string: null, lang: null, timezone: null },
      assignment: input.assigneeId === null ? { kind: "unassign" } : { kind: "assign", assigneeId: input.assigneeId }
    });
  }

  private guardCommand(): void {
    if (this.scenario === "after_command_sent_timeout") {
      throw new Error("e2e_command_timeout");
    }
  }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
Expected: PASS (8 tests).

- [x] **Step 5: Commit**

```bash
git add backend/test/fakes/e2eTodoistAdapter.ts backend/test/fakes/e2eTodoistAdapter.test.ts
git commit -m "feat(e2e): add stateful E2E Todoist world adapter with scenario presets"
```

---

### Task 2: Fixture seed module

**Files:**
- Create: `backend/test/fakes/e2eFixtures.ts`
- Test: `backend/test/fakes/e2eFixtures.test.ts`

- [x] **Step 1: Write the failing test**

```typescript
import { describe, expect, it } from "vitest";
import { e2eFixtureSeed } from "./e2eFixtures.js";
import { describeRecurrence } from "../../src/domain/taskRecurrence.js";

describe("e2eFixtureSeed", () => {
  const seed = e2eFixtureSeed(new Date("2026-07-18T10:00:00.000Z"));
  const byId = new Map(seed.tasks.map((task) => [task.id, task.mirror]));

  it("defines the 15 fixture roots plus 6 checklist children with pinned ids", () => {
    const roots = seed.tasks.filter((task) => task.mirror.parentId === null);
    const children = seed.tasks.filter((task) => task.mirror.parentId !== null);
    expect(roots).toHaveLength(15);
    expect(children).toHaveLength(6);
    expect(byId.get("task-start")?.title).toBe("Start blocker fixture");
    for (const { id, mirror } of seed.tasks) {
      expect(mirror.todoistTaskId).toBe(`todoist-${id}`);
    }
  });

  it("derives invalid, hierarchy, and recurrence states rather than declaring them", () => {
    expect(byId.get("task-invalid")?.dueDatetime).toBeNull();
    expect(byId.get("task-invalid")?.durationMinutes).toBeNull();
    expect(byId.get("child-unsupported-hierarchy")?.dueDatetime).not.toBeNull();
    expect(byId.get("child-unsupported-hierarchy")?.parentId).toBe("todoist-task-unsupported-hierarchy");
    const daily = byId.get("task-recurring-all-checked");
    expect(describeRecurrence({
      isRecurring: daily?.isRecurring ?? false, rule: daily?.dueString ?? null,
      language: daily?.dueLang ?? null, timeZone: daily?.dueTimezone ?? null
    })).toEqual({ kind: "supported", preset: "daily" });
    const custom = byId.get("task-custom-recurrence");
    expect(describeRecurrence({
      isRecurring: custom?.isRecurring ?? false, rule: custom?.dueString ?? null,
      language: custom?.dueLang ?? null, timeZone: custom?.dueTimezone ?? null
    })).toEqual({ kind: "custom", preset: null });
  });

  it("marks checked children as completed and pins occurrence keys for every root", () => {
    expect(byId.get("child-one-time-partial-done")?.completedAt).not.toBeNull();
    expect(byId.get("child-one-time-partial-open")?.completedAt).toBeNull();
    const rootIds = seed.tasks.filter((task) => task.mirror.parentId === null).map((task) => task.id);
    expect(seed.occurrencePins.map((pin) => pin.id).sort()).toEqual([...rootIds].sort());
    expect(seed.occurrencePins.find((pin) => pin.id === "task-start")?.occurrenceKey).toBe("occurrence-task-start-1");
  });

  it("nudges started fixtures and the reconciliation-pending fixture", () => {
    expect(seed.nudges).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: "task-deadline", kind: "started" }),
      expect.objectContaining({ id: "task-reschedule", kind: "started" }),
      expect.objectContaining({ id: "task-deadline-fail", kind: "started" }),
      expect.objectContaining({ id: "task-reschedule-fail", kind: "started" }),
      expect.objectContaining({ id: "task-reconciliation-pending", kind: "reconciliation_pending" })
    ]));
    expect(seed.nudges).toHaveLength(5);
  });

  it("defines the two fixture projects the AddTask flow selects by name", () => {
    expect(seed.projects.map((project) => [project.todoistProjectId, project.name, project.canAssignTasks])).toEqual([
      ["project-test", "Test Shared", true],
      ["project-personal", "Test Personal", false]
    ]);
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
Expected: FAIL — cannot resolve `./e2eFixtures.js`.

- [x] **Step 3: Write the implementation**

`backend/test/fakes/e2eFixtures.ts`:

```typescript
import type { TodoistProjectMirror, TodoistTaskMirror } from "../../src/todoist/todoistTypes.js";

export interface E2EFixtureTask { id: string; mirror: TodoistTaskMirror }
export interface E2ECommitmentNudge { id: string; kind: "started" | "reconciliation_pending"; startedAt?: string }
export interface E2EOccurrencePin { id: string; occurrenceKey: string }
export interface E2EFixtureSeed {
  tasks: E2EFixtureTask[];
  projects: TodoistProjectMirror[];
  nudges: E2ECommitmentNudge[];
  occurrencePins: E2EOccurrencePin[];
}

const TIME_ZONE = "Europe/Bratislava";

/**
 * The same 15 fixture tasks tools/fake-backend/src/fixtures.ts serves today, expressed
 * as Todoist mirrors so the real backend derives every state (invalid, unsupported
 * hierarchy, recurrence kind, checklist counts) instead of being handed it.
 */
export function e2eFixtureSeed(now: Date): E2EFixtureSeed {
  const iso = (minutes: number) => new Date(now.getTime() + minutes * 60_000).toISOString();
  const nowIso = now.toISOString();

  const root = (
    id: string,
    overrides: Partial<TodoistTaskMirror> & Pick<TodoistTaskMirror, "title">
  ): E2EFixtureTask => ({
    id,
    mirror: {
      todoistTaskId: `todoist-${id}`,
      description: null,
      projectId: "project-test",
      parentId: null,
      childOrder: null,
      assigneeId: "user-me",
      creatorId: "user-me",
      dueDate: null,
      dueDatetime: null,
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: 30,
      durationUnit: "minute",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: {},
      updatedAt: nowIso,
      ...overrides
    }
  });

  const child = (
    id: string,
    parentFixtureId: string,
    title: string,
    order: number,
    checked: boolean,
    overrides: Partial<TodoistTaskMirror> = {}
  ): E2EFixtureTask => ({
    id,
    mirror: {
      todoistTaskId: `todoist-${id}`,
      title,
      description: null,
      projectId: "project-test",
      parentId: `todoist-${parentFixtureId}`,
      childOrder: order,
      assigneeId: null,
      creatorId: "user-me",
      dueDate: null, dueDatetime: null, dueString: null, dueLang: null, dueTimezone: null,
      isRecurring: false,
      durationAmount: null, durationUnit: null, durationMinutes: null,
      completedAt: checked ? nowIso : null,
      deletedAt: null,
      rawJson: {},
      updatedAt: nowIso,
      ...overrides
    }
  });

  const recurring = (rule: string): Partial<TodoistTaskMirror> => ({
    isRecurring: true, dueString: rule, dueLang: "en", dueTimezone: TIME_ZONE
  });

  const tasks: E2EFixtureTask[] = [
    root("task-start", {
      title: "Start blocker fixture",
      description: "Bring the printed form and check opening hours.",
      dueDatetime: iso(120)
    }),
    root("task-deadline", {
      title: "Deadline blocker fixture",
      description: "Share the final numbers before closing the block.",
      dueDatetime: iso(-30), durationAmount: 60, durationMinutes: 60
    }),
    root("task-reschedule", {
      title: "Reschedule blocker fixture",
      dueDatetime: iso(-30), durationAmount: 60, durationMinutes: 60
    }),
    root("task-start-fail", { title: "Start failure fixture", dueDatetime: iso(150) }),
    root("task-deadline-fail", {
      title: "Deadline failure fixture",
      dueDatetime: iso(-30), durationAmount: 60, durationMinutes: 60
    }),
    root("task-reschedule-fail", {
      title: "Reschedule failure fixture",
      dueDatetime: iso(-30), durationAmount: 60, durationMinutes: 60
    }),
    root("task-invalid", {
      title: "Invalid assigned fixture",
      description: "Clarify the missing commitment fields.",
      durationAmount: null, durationUnit: null, durationMinutes: null
    }),
    root("task-floating-time", {
      title: "Canonical datetime fixture",
      description: "Snapshot datetimes are normalized to RFC3339 instants.",
      dueDatetime: iso(180)
    }),
    root("task-one-time-empty", { title: "One-time empty checklist", dueDatetime: iso(120) }),
    root("task-one-time-partial", { title: "One-time partially checked", dueDatetime: iso(180) }),
    child("child-one-time-partial-done", "task-one-time-partial", "Already done", 1, true),
    child("child-one-time-partial-open", "task-one-time-partial", "Still open", 2, false),
    root("task-recurring-all-checked", {
      title: "Recurring complete checklist",
      dueDatetime: iso(60), ...recurring("every day at 10:00")
    }),
    child("child-recurring-all-checked-a", "task-recurring-all-checked", "First", 1, true),
    child("child-recurring-all-checked-b", "task-recurring-all-checked", "Second", 2, true),
    root("task-recurring-extend-disabled", {
      title: "Recurring extend disabled",
      dueDatetime: iso(300), ...recurring("every Monday at 15:00")
    }),
    child("child-recurring-extend-disabled", "task-recurring-extend-disabled", "Complete", 1, true),
    root("task-custom-recurrence", {
      title: "Custom recurring task",
      dueDatetime: iso(1440), ...recurring("every 2 days at 10:00")
    }),
    root("task-unsupported-hierarchy", {
      title: "Unsupported hierarchy",
      dueDatetime: iso(1560)
    }),
    child("child-unsupported-hierarchy", "task-unsupported-hierarchy", "Scheduled child", 1, false, {
      dueDatetime: iso(1560)
    }),
    root("task-reconciliation-pending", { title: "Pending reconciliation", dueDatetime: iso(1680) })
  ];

  const projects: TodoistProjectMirror[] = [
    {
      todoistProjectId: "project-test", name: "Test Shared", shared: true, canAssignTasks: true,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: {}, updatedAt: nowIso
    },
    {
      todoistProjectId: "project-personal", name: "Test Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: {}, updatedAt: nowIso
    }
  ];

  const nudges: E2ECommitmentNudge[] = [
    { id: "task-deadline", kind: "started", startedAt: iso(-29) },
    { id: "task-reschedule", kind: "started", startedAt: iso(-29) },
    { id: "task-deadline-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reschedule-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reconciliation-pending", kind: "reconciliation_pending" }
  ];

  // The blocker tests launch activities with EXTRA_OCCURRENCE_KEY "occurrence-<id>-1",
  // so the sync-rotated random keys are re-pinned to that format after seeding.
  const occurrencePins: E2EOccurrencePin[] = tasks
    .filter((task) => task.mirror.parentId === null)
    .map((task) => ({ id: task.id, occurrenceKey: `occurrence-${task.id}-1` }));

  return { tasks, projects, nudges, occurrencePins };
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
Expected: PASS (5 tests).

- [x] **Step 5: Commit**

```bash
git add backend/test/fakes/e2eFixtures.ts backend/test/fakes/e2eFixtures.test.ts
git commit -m "feat(e2e): add fixture seed module deriving the 15 fake-backend fixtures"
```

---

### Task 3: `buildE2EServer()` — boot, seed, snapshot conformance

**Files:**
- Create: `backend/test/e2eServer.ts`
- Test: `backend/test/e2eServer.test.ts`

- [x] **Step 1: Write the failing seeding-conformance test**

`backend/test/e2eServer.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { buildE2EServer, type E2EServer } from "./e2eServer.js";

const auth = { authorization: "Bearer test-token" };

async function withServer(run: (server: E2EServer) => Promise<void>): Promise<void> {
  const server = await buildE2EServer();
  try {
    await run(server);
  } finally {
    await server.app.close();
  }
}

async function snapshot(server: E2EServer): Promise<Record<string, any>> {
  const response = await server.app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth });
  expect(response.statusCode).toBe(200);
  return response.json();
}

async function snapshotTask(server: E2EServer, taskId: string): Promise<Record<string, any>> {
  const task = (await snapshot(server)).tasks.find((candidate: { id: string }) => candidate.id === taskId);
  expect(task, `task ${taskId} in snapshot`).toBeDefined();
  return task;
}

describe("seeding", () => {
  it("serves the fixture snapshot with real-backend derivations", async () => {
    await withServer(async (server) => {
      const body = await snapshot(server);
      expect(body.tasks).toHaveLength(15);
      const byId = new Map<string, any>(body.tasks.map((task: { id: string }) => [task.id, task]));
      expect(byId.get("task-start").title).toBe("Start blocker fixture");
      expect(byId.get("task-start").occurrenceKey).toBe("occurrence-task-start-1");
      expect(byId.get("task-start").isAssignedToUser).toBe(true);
      expect(byId.get("task-deadline").state).toBe("started");
      expect(byId.get("task-invalid").state).toBe("invalid");
      expect(byId.get("task-unsupported-hierarchy").hierarchyStatus).toBe("unsupported");
      expect(byId.get("task-unsupported-hierarchy").hierarchyViolation).toBe("child_has_schedule");
      expect(byId.get("task-custom-recurrence").recurrence.kind).toBe("custom");
      expect(byId.get("task-recurring-all-checked").recurrence.preset).toBe("daily");
      expect(byId.get("task-recurring-all-checked").checkedCount).toBe(2);
      expect(byId.get("task-recurring-all-checked").totalCount).toBe(2);
      expect(byId.get("task-reconciliation-pending").reconciliationStatus).toBe("pending");
      const partialChildren = byId.get("task-one-time-partial").checklistItems;
      expect(partialChildren.map((child: { id: string }) => child.id).sort())
        .toEqual(["child-one-time-partial-done", "child-one-time-partial-open"]);
      expect(partialChildren.find((child: { id: string }) => child.id === "child-one-time-partial-done").checked).toBe(true);
    });
  });

  it("serves the two fixture projects and rejects missing auth", async () => {
    await withServer(async (server) => {
      const projects = await server.app.inject({ method: "GET", url: "/projects", headers: auth });
      expect(projects.statusCode).toBe(200);
      expect(projects.json().projects.map((project: { id: string; name: string }) => [project.id, project.name]))
        .toEqual([["project-test", "Test Shared"], ["project-personal", "Test Personal"]]);
      const unauthorized = await server.app.inject({ method: "GET", url: "/tasks/snapshot" });
      expect(unauthorized.statusCode).toBe(401);
    });
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run test/e2eServer.test.ts`
Expected: FAIL — cannot resolve `./e2eServer.js`.

- [x] **Step 3: Write the implementation (core server, debug routes come in Task 5)**

`backend/test/e2eServer.ts`:

```typescript
import Database from "better-sqlite3";
import { DryRunSender } from "../src/accountability/dryRunSender.js";
import { migrate } from "../src/db/migrations.js";
import { ProjectRepository } from "../src/repositories/projectRepository.js";
import { TaskRepository } from "../src/repositories/taskRepository.js";
import { buildServer } from "../src/server.js";
import { EscalationService } from "../src/services/escalationService.js";
import { TaskSnapshotService } from "../src/services/taskSnapshotService.js";
import { TodoistSyncService } from "../src/services/todoistSyncService.js";
import { e2eFixtureSeed } from "./fakes/e2eFixtures.js";
import { E2E_SCENARIOS, E2ETodoistAdapter, type E2EScenario } from "./fakes/e2eTodoistAdapter.js";

const CURRENT_USER_ID = "user-me";
const ANDROID_API_TOKEN = "test-token";
const ACTION_METADATA_KEYS = [
  "actionId", "deviceId", "expectedOccurrenceKey", "expectedTaskRevision", "expectedChildRevision"
] as const;

export interface RecordedCall { method: string; path: string; body: Record<string, unknown>; at: string }

export interface E2EServer {
  app: ReturnType<typeof buildServer>;
  adapter: E2ETodoistAdapter;
  db: Database.Database;
  reset: () => Promise<void>;
}

export async function buildE2EServer(): Promise<E2EServer> {
  const db = new Database(":memory:");
  migrate(db);
  const adapter = new E2ETodoistAdapter(CURRENT_USER_ID);
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const synchronizer = new TodoistSyncService(db, tasks, projects, adapter, CURRENT_USER_ID);
  const snapshots = new TaskSnapshotService(tasks, projects, CURRENT_USER_ID, true);
  const escalationService = new EscalationService(db, tasks, new DryRunSender(), "e2e-recipient");
  const app = buildServer({
    db,
    currentUserId: CURRENT_USER_ID,
    androidApiToken: ANDROID_API_TOKEN,
    todoist: adapter,
    escalationService,
    rateLimit: { max: 100_000, timeWindowMs: 60_000 }
  });

  const calls: RecordedCall[] = [];
  const controls = { failActions: false, failSnapshot: false, failProjects: false };

  const seed = async (): Promise<void> => {
    const fixture = e2eFixtureSeed(new Date());
    adapter.world.load({ tasks: fixture.tasks.map((task) => task.mirror), projects: fixture.projects });
    // Pre-upsert pins the local ids the Android tests address by URL; the boot sync
    // then flows through the production ingestion path (projects, occurrence
    // initialization, mirror_contract_version) on top of the same rows.
    for (const { id, mirror } of fixture.tasks) {
      tasks.upsertTodoistTask({ ...mirror, id, rawJson: JSON.stringify(mirror.rawJson) });
    }
    await synchronizer.syncOnce();
    const nowIso = new Date().toISOString();
    for (const nudge of fixture.nudges) {
      if (nudge.kind === "started") {
        tasks.updateCommitmentState(nudge.id, {
          state: "started", startedAt: nudge.startedAt ?? nowIso,
          incrementScheduleVersion: true, updatedAt: nowIso
        });
      } else {
        tasks.markOccurrenceReconciliationPending(nudge.id, nowIso);
      }
    }
    // The blocker tests hardcode EXTRA_OCCURRENCE_KEY "occurrence-<id>-1"; only the key
    // is re-pinned, so sync's due/fingerprint anchors keep deciding "unchanged".
    const pinOccurrence = db.prepare("update commitment_states set occurrence_key = ? where task_id = ?");
    for (const pin of fixture.occurrencePins) {
      pinOccurrence.run(pin.occurrenceKey, pin.id);
    }
  };

  const reset = async (): Promise<void> => {
    // Row deletes instead of a fresh database: the snapshot-generation triggers keep
    // snapshotGeneration strictly monotonic across resets, which clients require
    // because they retain the last authoritative snapshot.
    db.transaction(() => {
      for (const table of [
        "task_action_commands", "task_action_journal", "whatsapp_escalations", "devices",
        "commitment_states", "todoist_tasks", "todoist_projects", "todoist_sync_state"
      ]) {
        db.prepare(`delete from ${table}`).run();
      }
    })();
    calls.splice(0);
    controls.failActions = false;
    controls.failSnapshot = false;
    controls.failProjects = false;
    adapter.setScenario("none");
    await seed();
  };

  registerDebugSurface(app, { adapter, calls, controls, snapshots, reset });

  await seed();
  return { app, adapter, db, reset };
}

interface DebugSurfaceOptions {
  adapter: E2ETodoistAdapter;
  calls: RecordedCall[];
  controls: { failActions: boolean; failSnapshot: boolean; failProjects: boolean };
  snapshots: TaskSnapshotService;
  reset: () => Promise<void>;
}

function registerDebugSurface(app: ReturnType<typeof buildServer>, options: DebugSurfaceOptions): void {
  // Implemented in the debug-parity task; kept separate so production wiring stays readable.
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 18433);
  const { app } = await buildE2EServer();
  await app.listen({ host: "0.0.0.0", port });
  console.log(`e2e real backend listening on ${port}`);
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run test/e2eServer.test.ts`
Expected: PASS (2 tests). If `task-deadline` is not `started`, the nudge ran before sync — nudges and pins must run after `syncOnce()`.

- [x] **Step 5: Commit**

```bash
git add backend/test/e2eServer.ts backend/test/e2eServer.test.ts
git commit -m "feat(e2e): boot real backend against the world adapter with seeded fixtures"
```

---

### Task 4: Happy-path mutation conformance (the drift guard core)

**Files:**
- Modify: `backend/test/e2eServer.test.ts` (append a `describe` block)
- Possibly fix: `backend/test/fakes/e2eTodoistAdapter.ts` (where reality disagrees)

These tests drive every mutation endpoint through the full stack (journal, coordinator,
sync, effect confirmation). Every happy path must return `outcome: "completed"` — the
whole point of Phase 1. Failures here are world-semantics bugs; debug with the real
services, do not weaken assertions to `202`.

- [x] **Step 1: Append the failing conformance tests**

```typescript
const device = { deviceId: "e2e-device" };
let actionCounter = 0;
function action(extra: Record<string, unknown> = {}): Record<string, unknown> {
  actionCounter += 1;
  return { actionId: `e2e-action-${actionCounter}`, ...device, ...extra };
}

async function mutate(
  server: E2EServer,
  url: string,
  payload: Record<string, unknown>,
  expectedStatus = 200
): Promise<Record<string, any>> {
  const response = await server.app.inject({ method: "POST", url, headers: auth, payload });
  expect(response.statusCode, `${url} -> ${response.body}`).toBe(expectedStatus);
  return response.json();
}

describe("happy-path conformance (every action must complete, not go pending)", () => {
  it("start", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      const body = await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-start")).state).toBe("started");
    });
  });

  it("done on a one-time task completes it", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-one-time-empty");
      const body = await mutate(server, "/tasks/task-one-time-empty/done", action({ expectedOccurrenceKey: task.occurrenceKey }));
      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-one-time-empty")).isCompleted).toBe(true);
    });
  });

  it("done on a recurring task advances the occurrence and resets children", async () => {
    await withServer(async (server) => {
      const before = await snapshotTask(server, "task-recurring-all-checked");
      const body = await mutate(server, "/tasks/task-recurring-all-checked/done", action({ expectedOccurrenceKey: before.occurrenceKey }));
      expect(body.outcome).toBe("completed");
      const after = await snapshotTask(server, "task-recurring-all-checked");
      expect(after.isCompleted).toBe(false);
      expect(after.dueDatetime).not.toBe(before.dueDatetime);
      expect(after.occurrenceKey).not.toBe(before.occurrenceKey);
      expect(after.checkedCount).toBe(0);
    });
  });

  it("postpone-start snoozes", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      const body = await mutate(server, "/tasks/task-start/postpone-start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-start")).startDismissCount).toBe(1);
    });
  });

  it("extend adds 30 minutes", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-deadline");
      const body = await mutate(server, "/tasks/task-deadline/extend", action({
        expectedOccurrenceKey: task.occurrenceKey, expectedTaskRevision: task.taskRevision
      }));
      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-deadline")).durationMinutes).toBe(90);
    });
  });

  it("reschedule applies the new schedule", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-reschedule");
      const dueDatetime = new Date(Date.now() + 120 * 60_000).toISOString();
      const body = await mutate(server, "/tasks/task-reschedule/reschedule", action({
        expectedOccurrenceKey: task.occurrenceKey, dueDatetime, durationMinutes: 45
      }));
      expect(body.outcome).toBe("completed");
      const after = await snapshotTask(server, "task-reschedule");
      expect(after.dueDatetime).toBe(dueDatetime);
      expect(after.durationMinutes).toBe(45);
    });
  });

  it("update renames via rootPatch", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-floating-time");
      const body = await mutate(server, "/tasks/task-floating-time/update", action({
        expectedOccurrenceKey: task.occurrenceKey,
        rootPatch: { expectedTaskRevision: task.taskRevision, title: "Renamed fixture" },
        childMutations: []
      }));
      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-floating-time")).title).toBe("Renamed fixture");
    });
  });

  it("create with a checklist draft lands in the snapshot", async () => {
    await withServer(async (server) => {
      const body = await mutate(server, "/tasks", action({
        title: "E2E created task", projectId: "project-test", assigneeId: "user-me",
        dueDatetime: new Date(Date.now() + 240 * 60_000).toISOString(), durationMinutes: 30,
        description: null,
        checklistDrafts: [{ clientMutationId: "draft-1", title: "Draft child", description: null }]
      }));
      expect(body.outcome).toBe("completed");
      const created = (await snapshot(server)).tasks.find(
        (candidate: { title: string }) => candidate.title === "E2E created task"
      );
      expect(created).toBeDefined();
      expect(created.checklistItems).toHaveLength(1);
      expect(created.checklistItems[0].title).toBe("Draft child");
    });
  });

  it("checklist add and check complete against the real checklist service", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-one-time-partial");
      const added = await mutate(server, "/tasks/task-one-time-partial/checklist", action({
        expectedOccurrenceKey: task.occurrenceKey, clientMutationId: "add-1", title: "Newly added", description: null
      }));
      expect(added.outcome).toBe("completed");
      const openChild = (await snapshotTask(server, "task-one-time-partial")).checklistItems
        .find((child: { id: string }) => child.id === "child-one-time-partial-open");
      const checked = await mutate(
        server,
        "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/check",
        action({ expectedOccurrenceKey: task.occurrenceKey, expectedChildRevision: openChild.childRevision })
      );
      expect(checked.outcome).toBe("completed");
      const after = await snapshotTask(server, "task-one-time-partial");
      expect(after.checklistItems.find((child: { id: string }) => child.id === "child-one-time-partial-open").checked).toBe(true);
      expect(after.checklistItems).toHaveLength(3);
    });
  });

  it("review dismiss responds with a snooze window", async () => {
    await withServer(async (server) => {
      const body = await mutate(server, "/review/dismiss", {});
      expect(typeof body.snoozedUntil).toBe("string");
    });
  });
});
```

- [x] **Step 2: Run and fix until green**

Run: `cd backend && npx vitest run test/e2eServer.test.ts`
Expected: initially some failures are plausible (this task exists to find them). Debug
rules: a 202 where 200 is expected means the world's post-mutation state does not
satisfy that action's `isEffectPresent` (`taskActionService.ts:342,401,418,438,489,545,640`;
`todoistChecklistService.ts:85`) — fix the world's semantics in
`e2eTodoistAdapter.ts`, never the backend services and never the assertion. A 409/422
means the request payload violates `backend/src/http/routes.ts` schemas — fix the test
payload against the zod schemas at `routes.ts:10-92`.

- [x] **Step 3: Run the full backend suite to confirm nothing regressed**

Run: `cd backend && npm test`
Expected: PASS in full (the diff so far touches only new `test/` files).

- [x] **Step 4: Commit**

```bash
git add backend/test/e2eServer.test.ts backend/test/fakes/e2eTodoistAdapter.ts
git commit -m "test(e2e): prove every mutation happy path completes against the world"
```

---

### Task 5: Debug-endpoint parity + scenario presets over HTTP

**Files:**
- Modify: `backend/test/e2eServer.ts` (fill in `registerDebugSurface`)
- Modify: `backend/test/e2eServer.test.ts` (append a `describe` block)

- [x] **Step 1: Append the failing tests**

```typescript
describe("debug surface parity", () => {
  it("records the action-metadata subset of mutation calls", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      const calls = (await server.app.inject({ method: "GET", url: "/__calls" })).json().calls;
      const recorded = calls.find((call: { path: string }) => call.path === "/tasks/task-start/start");
      expect(recorded).toBeDefined();
      expect(Object.keys(recorded.body).sort()).toEqual(["actionId", "deviceId", "expectedOccurrenceKey"]);
    });
  });

  it("__fail-actions forces 500 with code forced_failure but still records the call", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      await server.app.inject({ method: "POST", url: "/__fail-actions", payload: { enabled: true } });
      const failed = await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }), 500);
      expect(failed.code).toBe("forced_failure");
      const calls = (await server.app.inject({ method: "GET", url: "/__calls" })).json().calls;
      expect(calls.some((call: { path: string }) => call.path === "/tasks/task-start/start")).toBe(true);
      await server.app.inject({ method: "POST", url: "/__fail-actions", payload: { enabled: false } });
      const recovered = await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      expect(recovered.outcome).toBe("completed");
    });
  });

  it("__fail-snapshot and __fail-projects force 500s on their routes", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__fail-snapshot", payload: { enabled: true } });
      expect((await server.app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).statusCode).toBe(500);
      await server.app.inject({ method: "POST", url: "/__fail-snapshot", payload: { enabled: false } });
      await server.app.inject({ method: "POST", url: "/__fail-projects", payload: { enabled: true } });
      expect((await server.app.inject({ method: "GET", url: "/projects", headers: auth })).statusCode).toBe(500);
      await server.app.inject({ method: "POST", url: "/__fail-projects", payload: { enabled: false } });
    });
  });

  it("__scenario after_command_sent_timeout drives an action to reconciliation_pending", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "after_command_sent_timeout" } });
      const task = await snapshotTask(server, "task-one-time-empty");
      const body = await mutate(server, "/tasks/task-one-time-empty/done", action({ expectedOccurrenceKey: task.occurrenceKey }), 202);
      expect(body.outcome).toBe("reconciliation_pending");
      expect((await snapshotTask(server, "task-one-time-empty")).reconciliationStatus).toBe("pending");
    });
  });

  it("__scenario sync_lag_effect_unknown leaves a successful command unconfirmed", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "sync_lag_effect_unknown" } });
      const task = await snapshotTask(server, "task-reschedule");
      const dueDatetime = new Date(Date.now() + 90 * 60_000).toISOString();
      const body = await mutate(server, "/tasks/task-reschedule/reschedule", action({
        expectedOccurrenceKey: task.occurrenceKey, dueDatetime, durationMinutes: 45
      }), 202);
      expect(body.outcome).toBe("reconciliation_pending");
      expect((await snapshotTask(server, "task-reschedule")).reconciliationStatus).toBe("pending");
    });
  });

  it("__scenario rejects unknown names", async () => {
    await withServer(async (server) => {
      const response = await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "nope" } });
      expect(response.statusCode).toBe(400);
    });
  });

  it("__reset reseeds while keeping snapshotGeneration monotonic", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      const before = (await snapshot(server)).snapshotGeneration;
      const reset = await server.app.inject({ method: "POST", url: "/__reset", payload: {} });
      expect(reset.statusCode).toBe(200);
      const after = await snapshot(server);
      expect(after.snapshotGeneration).toBeGreaterThan(before);
      expect((await snapshotTask(server, "task-start")).state).toBe("planned");
      expect((await server.app.inject({ method: "GET", url: "/__calls" })).json().calls).toHaveLength(0);
    });
  });
});
```

- [x] **Step 2: Run to verify the new block fails**

Run: `cd backend && npx vitest run test/e2eServer.test.ts`
Expected: the `debug surface parity` tests FAIL (404 on `/__calls` etc.); earlier blocks stay green.

- [x] **Step 3: Implement `registerDebugSurface`**

Replace the empty `registerDebugSurface` body in `backend/test/e2eServer.ts`:

```typescript
function registerDebugSurface(app: ReturnType<typeof buildServer>, options: DebugSurfaceOptions): void {
  const { adapter, calls, controls, snapshots, reset } = options;

  // Root-level hook: records the same action-metadata subset the old fake records
  // (tools/fake-backend/src/server.ts:36-40) and applies the HTTP-level fail toggles.
  // Recording happens before the failure so waitForRecordedCall works in failure tests.
  app.addHook("preHandler", async (request, reply) => {
    const path = request.url.split("?")[0] ?? request.url;
    if (path.startsWith("/__")) return;
    if (request.method === "POST" && (path.startsWith("/tasks") || path.startsWith("/review"))) {
      const body = typeof request.body === "object" && request.body !== null
        ? request.body as Record<string, unknown>
        : {};
      calls.push({
        method: request.method,
        path,
        body: Object.fromEntries(
          ACTION_METADATA_KEYS.filter((key) => body[key] !== undefined).map((key) => [key, body[key]])
        ),
        at: new Date().toISOString()
      });
      if (controls.failActions) {
        return reply.code(500).send({
          code: "forced_failure",
          message: "Forced action failure",
          snapshot: snapshots.getSnapshot(new Date().toISOString())
        });
      }
    }
    if (controls.failSnapshot && request.method === "GET" && path === "/tasks/snapshot") {
      return reply.code(500).send({ error: "forced_snapshot_failure" });
    }
    if (controls.failProjects && request.method === "GET" && path === "/projects") {
      return reply.code(500).send({ error: "forced_project_failure" });
    }
  });

  app.post("/__scenario", async (request, reply) => {
    const name = (request.body as { name?: unknown } | null)?.name;
    if (typeof name !== "string" || !E2E_SCENARIOS.includes(name as E2EScenario)) {
      return reply.code(400).send({ error: "unknown_scenario", known: E2E_SCENARIOS });
    }
    adapter.setScenario(name as E2EScenario);
    return { scenario: name };
  });
  app.post("/__reset", async () => {
    await reset();
    return { ok: true };
  });
  app.get("/__calls", async () => ({ calls }));
  app.post("/__fail-actions", async (request) => (
    { failActions: controls.failActions = (request.body as { enabled?: unknown } | null)?.enabled === true }
  ));
  app.post("/__fail-snapshot", async (request) => (
    { failSnapshot: controls.failSnapshot = (request.body as { enabled?: unknown } | null)?.enabled === true }
  ));
  app.post("/__fail-projects", async (request) => (
    { failProjects: controls.failProjects = (request.body as { enabled?: unknown } | null)?.enabled === true }
  ));
}
```

- [x] **Step 4: Run the file, then the full suite**

Run: `cd backend && npx vitest run test/e2eServer.test.ts && npm test`
Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add backend/test/e2eServer.ts backend/test/e2eServer.test.ts
git commit -m "feat(e2e): debug-endpoint parity and HTTP scenario control with conformance tests"
```

---

### Task 6: npm script and real-port smoke check

**Files:**
- Modify: `backend/package.json` (scripts block)

- [ ] **Step 1: Add the script**

In `backend/package.json`, extend `"scripts"`:

```json
    "dev": "tsx src/server.ts",
    "e2e-server": "tsx test/e2eServer.ts",
    "migrate": "tsx src/db/migrations.ts"
```

- [ ] **Step 2: Smoke-check on a real port**

```bash
cd backend && PORT=18455 npm run e2e-server &
sleep 4
curl --silent --fail http://127.0.0.1:18455/health
curl --silent --fail -H "Authorization: Bearer test-token" http://127.0.0.1:18455/tasks/snapshot | head -c 200; echo
curl --silent --fail -X POST -H "Content-Type: application/json" -d '{}' http://127.0.0.1:18455/__reset
kill %1
```

Expected: `{"ok":true,...}` from `/health`, a JSON snapshot prefix containing `"task-start"` or `snapshotGeneration`, and `{"ok":true}` from `/__reset`.

- [ ] **Step 3: Commit**

```bash
git add backend/package.json
git commit -m "feat(e2e): add e2e-server npm script"
```

---

### Task 7: `scripts/e2e-real-backend.sh`

**Files:**
- Create: `scripts/e2e-real-backend.sh` (mode 755)

- [ ] **Step 1: Write the script**

Structurally parallel to `scripts/e2e-fake-backend.sh` (which stays untouched); only the
boot command and readiness probe differ:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-18433}"
TOKEN="test-token"
BACKEND_URL="http://127.0.0.1:${PORT}"
EMULATOR_BACKEND_URL="http://10.0.2.2:${PORT}"
# A physical device reaches the host backend through adb reverse.
CONNECTED_DEVICE_BACKEND_URL="${CONNECTED_DEVICE_BACKEND_URL:-http://127.0.0.1:${PORT}}"
STARTED_PID=""

cleanup() {
  if [[ -n "${STARTED_PID}" ]]; then
    kill "${STARTED_PID}" >/dev/null 2>&1 || true
    wait "${STARTED_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_backend() {
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if curl --silent --fail "${BACKEND_URL}/health" >/dev/null; then
      return 0
    fi
    if [[ -n "${STARTED_PID}" ]] && ! kill -0 "${STARTED_PID}" >/dev/null 2>&1; then
      local exit_code=0
      wait "${STARTED_PID}" || exit_code="$?"
      echo "E2E backend exited before becoming ready with status ${exit_code}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for E2E backend at ${BACKEND_URL}" >&2
  return 1
}

if curl --silent --fail "${BACKEND_URL}/health" >/dev/null; then
  echo "Using existing E2E backend at ${BACKEND_URL}"
else
  echo "Starting E2E backend at ${BACKEND_URL}"
  (
    cd "${ROOT_DIR}/backend"
    PORT="${PORT}" npm run e2e-server
  ) &
  STARTED_PID="$!"
  wait_for_backend
fi

# CI sets BUZER_GMD_GPU (e.g. swiftshader_indirect) to force the managed-device
# emulator GPU mode on GPU-less runners; unset locally, the flag is omitted.
GMD_GPU_ARG="${BUZER_GMD_GPU:+-Pandroid.testoptions.manageddevices.emulator.gpu=${BUZER_GMD_GPU}}"

cd "${ROOT_DIR}/client"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  TEST_TASK=":androidApp:connectedDebugAndroidTest"
  DEVICE_BACKEND_URL="${CONNECTED_DEVICE_BACKEND_URL}"
else
  TEST_TASK="pixel8Api34DebugAndroidTest"
  DEVICE_BACKEND_URL="${EMULATOR_BACKEND_URL}"
fi

GRADLE_ARGS=("${TEST_TASK}" "--rerun-tasks")
if [[ -n "${GMD_GPU_ARG}" ]]; then
  GRADLE_ARGS+=("${GMD_GPU_ARG}")
fi
if [[ -n "${ANDROID_TEST_CLASS:-}" ]]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${ANDROID_TEST_CLASS}")
fi
GRADLE_ARGS+=(
  "-PBUZER_BACKEND_BASE_URL=${DEVICE_BACKEND_URL}"
  "-PBUZER_BACKEND_TOKEN=${TOKEN}"
  "-PBUZER_ASSIGNEE_ME_ID=${BUZER_ASSIGNEE_ME_ID:-user-me}"
  "-PBUZER_ASSIGNEE_WIFE_ID=${BUZER_ASSIGNEE_WIFE_ID:-user-wife}"
  "-PFAKE_BACKEND_BASE_URL=${DEVICE_BACKEND_URL}"
)

./gradlew "${GRADLE_ARGS[@]}"
```

- [ ] **Step 2: Make it executable and verify boot-only behavior**

```bash
chmod +x scripts/e2e-real-backend.sh
bash -n scripts/e2e-real-backend.sh
```

Expected: `bash -n` exits 0 (syntax check). Full runs happen in Task 9.

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e-real-backend.sh
git commit -m "feat(e2e): add real-backend E2E runner script"
```

---

### Task 8: FakeBackendClient additions + the two preset instrumentation tests

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/RealBackendScenarioTest.kt`

The new tests live in their **own class** and self-skip when the backend has no
`/__scenario` (the old fake), so `scripts/e2e-fake-backend.sh` and CI stay green in
Phase 1 — a spec non-goal protects both.

- [ ] **Step 1: Extend FakeBackendClient (additive only)**

Change `request`/`requestOnce` to support authorization, and add the two methods.
The existing method bodies stay identical apart from the added parameter:

```kotlin
    fun setScenario(name: String) {
        request("POST", "/__scenario", """{"name":"$name"}""")
    }

    fun taskReconciliationStatus(taskId: String): String? {
        val body = request("GET", "/tasks/snapshot", authorized = true)
        val tasks = Json.parseToJsonElement(body).jsonObject["tasks"]?.jsonArray ?: return null
        return tasks.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == taskId }
            ?.jsonObject?.get("reconciliationStatus")?.jsonPrimitive?.contentOrNull
    }
```

```kotlin
    private fun request(method: String, path: String, body: String = "{}", authorized: Boolean = false): String {
        // ... existing retry loop, forwarding `authorized` to requestOnce ...
    }

    private fun requestOnce(method: String, path: String, body: String, authorized: Boolean): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (authorized) {
            connection.setRequestProperty("Authorization", "Bearer $AUTH_TOKEN")
        }
        // ... rest unchanged ...
    }

    private companion object {
        const val CONNECTIVITY_RETRY_WINDOW_MILLIS = 20_000L
        const val AUTH_TOKEN = "test-token"
    }
```

- [ ] **Step 2: Write the scenario test class**

`RealBackendScenarioTest.kt` (same package as the blocker tests, so
`DeadlineBlockerActivity` needs no import; `AlarmIntentFactory` comes from
`app.buzer.android.enforcement`):

```kotlin
package app.buzer.android.blockers

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.enforcement.AlarmIntentFactory
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fault-injection presets only exist on the real-backend E2E server
 * (backend/test/e2eServer.ts). Against tools/fake-backend these tests self-skip so the
 * Phase 1 fake-backend script and CI keep passing unchanged.
 */
@RunWith(AndroidJUnit4::class)
class RealBackendScenarioTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Before
    fun requireScenarioSupport() {
        assumeTrue(
            "backend has no /__scenario (tools/fake-backend) - skipping preset tests",
            runCatching { fakeBackend.setScenario("none") }.isSuccess
        )
        File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "latest_snapshot.json"
        ).delete()
        fakeBackend.resetCalls()
    }

    @Test
    fun deadlineDone_underCommandTimeout_goesReconciliationPending() {
        runScenarioDone("after_command_sent_timeout")
    }

    @Test
    fun deadlineDone_underSyncLag_goesReconciliationPending() {
        runScenarioDone("sync_lag_effect_unknown")
    }

    private fun runScenarioDone(scenarioName: String) {
        fakeBackend.setScenario(scenarioName)
        try {
            val scenario = launchDeadlineBlocker("task-deadline")
            try {
                waitForText("Done")
                composeRule.onNodeWithText("Done").performClick()
                waitForRecordedCall("/tasks/task-deadline/done")
                waitForReconciliationStatus("task-deadline", "pending")
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setScenario("none")
        }
    }

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected backend call $path. Recorded calls: $lastCalls")
    }

    private fun waitForReconciliationStatus(taskId: String, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching { fakeBackend.taskReconciliationStatus(taskId) }.getOrNull() ?: last
            if (last == expected) return
            SystemClock.sleep(200)
        }
        fail("Expected $taskId reconciliationStatus=$expected, last seen: $last")
    }
}
```

- [ ] **Step 3: Compile the androidTest sources**

Run: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL. (If `createEmptyComposeRule` import differs, copy the exact
import line from `BlockerActivityFakeBackendTest.kt:11`.)

- [ ] **Step 4: Commit**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/RealBackendScenarioTest.kt
git commit -m "test(e2e): scenario-preset instrumentation tests with old-fake self-skip"
```

---

### Task 9: Full validation run and triage

**Files:**
- Create: `docs/notes/2026-07-18-e2e-real-backend-validation.md` (triage record)

- [ ] **Step 1: Run the full backend gate**

Run: `cd backend && npm test && npm run typecheck`
Expected: PASS in full. `git diff --stat master -- backend/src backend/test/fakes/fakeTodoistAdapter.ts` must show **no changes** to `backend/src/` or `fakeTodoistAdapter.ts` (spec acceptance criterion).

- [ ] **Step 2: Run the old fake-backend E2E suite as a control**

Run: `scripts/e2e-fake-backend.sh`
Expected: all pre-existing tests pass; the 2 new `RealBackendScenarioTest` tests report **skipped** (assumption failure). This proves Phase 1 breaks nothing existing.

- [ ] **Step 3: Run the new real-backend E2E suite**

Run: `scripts/e2e-real-backend.sh`
Expected: the 25 existing tests plus the 2 preset tests run against the real backend.

- [ ] **Step 4: Triage every failure into the spec's three buckets**

For each failing test, record in `docs/notes/2026-07-18-e2e-real-backend-validation.md`:
test name, observed behavior, bucket — (a) real bug in the new server/world → fix it
here; (b) fixture-seeding gap → fix `e2eFixtures.ts`/seeding here; (c) the old fake was
more lenient or plain wrong (already-known candidates: validation error codes differ —
real `due_datetime_required`/`duration_required` vs old fake's `missing_due_datetime`/
`missing_duration`; the `[partial-failure]` magic-title convention in
`addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft` does not exist in the
real backend; `reconciliationStatus` idle value is `confirmed`, not `none`) → document
as evidence, do not "fix" the backend to match the fake. Re-run after each (a)/(b) fix
until only (c) entries remain.

- [ ] **Step 5: Commit the validation record**

```bash
git add docs/notes/2026-07-18-e2e-real-backend-validation.md
git commit -m "docs(e2e): record real-backend E2E validation triage"
```

- [ ] **Step 6: Verify the spec's acceptance criteria one by one**

Walk `docs/superpowers/specs/2026-07-18-real-backend-e2e-server-design.md` §Acceptance
Criteria; each bullet must be demonstrably true (file exists / suite output / diff
shows untouched files). Then hand the branch to the
superpowers:finishing-a-development-branch flow.

---

## Self-Review Notes (already applied)

- **Spec coverage:** adapter+world (Task 1), fixtures 15+2 (Task 2), server+seeding via
  production sync (Task 3), happy-path conformance = drift-guard suite in `npm test`
  (Task 4), presets + all six debug endpoints + monotonic reset (Task 5), npm script
  (Task 6), script (Task 7), `FakeBackendClient.setScenario` + 2 instrumentation tests
  (Task 8), 25-test validation + triage (Task 9). Compile-time drift layer is inherent
  (`implements TodoistAdapter`); structural layer is inherent (world holds no backend
  logic).
- **Known intentional deviation from spec letter:** `/__reset` deletes rows instead of
  recreating the database — same observable contract, and it makes generation
  monotonicity automatic via the existing triggers.
- **Additions the spec review demanded beyond its text:** local-id pinning pre-upsert
  and occurrence-key pinning (both discovered from `todoistSyncService.ts:69` and the
  blocker tests' hardcoded `EXTRA_OCCURRENCE_KEY`); the self-skip guard keeping the old
  script/CI green.
- **Type consistency check:** `E2EScenario`/`E2E_SCENARIOS`/`setScenario` (Tasks 1, 5, 8),
  `E2EFixtureSeed.{tasks,projects,nudges,occurrencePins}` (Tasks 2, 3), `buildE2EServer`
  returning `{ app, adapter, db, reset }` (Tasks 3-5) are used consistently.
