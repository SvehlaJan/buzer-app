import Database from "better-sqlite3";
import { afterEach, describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskSnapshotService } from "../../src/services/taskSnapshotService.js";
import {
  createTodoistRuntime,
  type CreateTodoistRuntimeOptions
} from "../../src/services/todoistRuntime.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import type { TodoistProjectMirror, TodoistTaskMirror } from "../../src/todoist/todoistTypes.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

function markMirrorReady(db: Database.Database): void {
  db.prepare(`
    insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
    values (1, 'ready-token', 3, '2026-08-31T09:00:00Z')
    on conflict(id) do update set
      sync_token = excluded.sync_token,
      mirror_contract_version = excluded.mirror_contract_version,
      updated_at = excluded.updated_at
  `).run();
}

function makeProjectMirror(overrides?: Partial<TodoistProjectMirror>): TodoistProjectMirror {
  return {
    todoistProjectId: "proj-1",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: true,
    isDeleted: false,
    isArchived: false,
    rawJson: { id: "proj-1" },
    updatedAt: "2026-08-31T09:00:00Z",
    ...overrides
  };
}

function makeTaskMirror(overrides?: Partial<TodoistTaskMirror>): TodoistTaskMirror {
  return {
    todoistTaskId: "todoist-task-1",
    title: "Write architecture tests",
    description: null,
    projectId: "proj-1",
    parentId: null,
    childOrder: 1,
    assigneeId: null,
    creatorId: "user-self",
    dueDate: "2026-08-31T12:00:00Z",
    dueDatetime: "2026-08-31T12:00:00Z",
    dueString: null,
    dueLang: null,
    dueTimezone: null,
    isRecurring: false,
    durationAmount: 30,
    durationUnit: "minute",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: { id: "todoist-task-1" },
    updatedAt: "2026-08-31T09:30:00Z",
    ...overrides
  };
}

function setupRuntime(overrides?: Partial<CreateTodoistRuntimeOptions> & { mirrorReady?: boolean }) {
  const db = new Database(":memory:");
  migrate(db);
  if (overrides?.mirrorReady !== false) {
    markMirrorReady(db);
  }
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const collaborators = new CollaboratorRepository(db);
  projects.upsertProject({
    todoistProjectId: "proj-1",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: true,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-08-31T09:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const snapshots = new TaskSnapshotService(tasks, projects, "user-self", true, collaborators);
  const observability = new RecordingBackendObservability();
  const runtime = createTodoistRuntime({
    db,
    tasks,
    projects,
    collaborators,
    todoist: adapter,
    snapshots,
    currentUserId: "user-self",
    observability,
    ...overrides
  });

  return { db, tasks, projects, collaborators, adapter, snapshots, observability, runtime };
}

describe("TodoistRuntime", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("serializes pollOnce, forceRefresh, and executeMutation in strict FIFO order and returns busy when pollOnce holds the queue", async () => {
    let currentTime = 1_000;
    const { adapter, observability, runtime } = setupRuntime({
      now: () => currentTime
    });

    let notifySyncEntered!: () => void;
    const syncEntered = new Promise<void>((resolve) => {
      notifySyncEntered = resolve;
    });
    let releaseFirstSync!: () => void;
    const firstSyncHeld = new Promise<void>((resolve) => {
      releaseFirstSync = resolve;
    });

    const executionOrder: string[] = [];
    let syncCallCount = 0;

    adapter.sync = vi.fn(async (_syncToken: string) => {
      syncCallCount += 1;
      const callNumber = syncCallCount;
      executionOrder.push(`sync:${callNumber}`);
      if (callNumber === 1) {
        notifySyncEntered();
        await firstSyncHeld;
      }
      return {
        syncToken: `token-${callNumber}`,
        tasks: [],
        projects: [],
        collaborators: [],
        collaboratorStates: []
      };
    });

    const originalItemClose = adapter.itemClose.bind(adapter);
    adapter.itemClose = vi.fn(async (input) => {
      executionOrder.push("command:itemClose");
      return await originalItemClose(input);
    });

    // 1. Occupy the single internal FIFO queue with pollOnce
    const heldPoll = runtime.pollOnce({ deadlineMs: 60_000 });
    await syncEntered;

    // 2. Enqueue a forceRefresh whose remaining budget will expire while waiting behind pollOnce,
    //    followed by executeMutation and another forceRefresh with ample deadlines.
    const busyRefreshPromise = runtime.forceRefresh({
      requestId: "req-busy-refresh",
      deadlineMs: 6_000,
      minimumExecutionBudgetMs: 5_000
    });
    const queuedMutationPromise = runtime.executeMutation({
      operation: "complete_task",
      requestId: "req-queued-mutation",
      commands: [{ type: "item_close", input: { todoistTaskId: "todoist-task-1" } }],
      deadlineMs: 30_000,
      minimumExecutionBudgetMs: 5_000
    });
    const secondRefreshPromise = runtime.forceRefresh({
      requestId: "req-second-refresh",
      deadlineMs: 30_000,
      minimumExecutionBudgetMs: 5_000
    });

    // Advance virtual clock by 2,000ms so busyRefresh remaining budget is 4,000ms (< 5,000ms min budget)
    currentTime += 2_000;
    releaseFirstSync();

    const [pollSummary, busyRefresh, mutationOutcome, secondRefresh] = await Promise.all([
      heldPoll,
      busyRefreshPromise,
      queuedMutationPromise,
      secondRefreshPromise
    ]);

    expect(pollSummary).toMatchObject({ hasChanges: false });
    expect(busyRefresh).toEqual({
      kind: "busy",
      message: "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly.",
      snapshot: expect.objectContaining({ tasks: [] })
    });
    expect(mutationOutcome.kind).toBe("success");
    expect(secondRefresh.kind).toBe("success");

    // Verify strict FIFO execution order: poll sync -> mutation command + post-mutation sync -> second forceRefresh sync
    expect(executionOrder).toEqual([
      "sync:1",
      "command:itemClose",
      "sync:2",
      "sync:3"
    ]);

    expect(observability.logs).toContainEqual({
      level: "warn",
      marker: "backend_busy",
      attributes: {
        operation: "force_refresh",
        request_id: "req-busy-refresh"
      }
    });
  });

  it("syncs Todoist into SQLite on forceRefresh success, emits started/succeeded logs, and returns { kind: 'success', snapshot }", async () => {
    const { adapter, tasks, observability, runtime } = setupRuntime();
    adapter.syncResults.push({
      syncToken: "synced-token-2",
      projects: [makeProjectMirror()],
      tasks: [makeTaskMirror({ todoistTaskId: "todoist-synced-1", title: "Synced via forceRefresh" })]
    });

    const outcome = await runtime.forceRefresh({ requestId: "req-refresh-ok" });

    expect(outcome.kind).toBe("success");
    if (outcome.kind === "success") {
      expect(outcome.snapshot.tasks).toHaveLength(1);
      expect(outcome.snapshot.tasks[0]).toMatchObject({
        todoistTaskId: "todoist-synced-1",
        title: "Synced via forceRefresh"
      });
    }
    expect(tasks.getByTodoistTaskId("todoist-synced-1")?.title).toBe("Synced via forceRefresh");
    expect(observability.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "force_refresh",
          request_id: "req-refresh-ok"
        }
      },
      {
        level: "info",
        marker: "todoist_operation_succeeded",
        attributes: {
          operation: "force_refresh",
          request_id: "req-refresh-ok"
        }
      }
    ]);
    expect(observability.exceptions).toHaveLength(0);
  });

  it("emits warn('forced_refresh_failed') without captureException on operational failure and returns { kind: 'failed', message, snapshot }", async () => {
    const { adapter, observability, runtime } = setupRuntime();
    const timeoutError = Object.assign(new Error("Timed out"), { name: "TimeoutError" });
    adapter.sync = vi.fn()
      .mockRejectedValueOnce(timeoutError)
      .mockRejectedValueOnce(new TodoistOperationError("timeout"));

    const first = await runtime.forceRefresh({ requestId: "req-op-fail-1" });
    expect(first).toEqual({
      kind: "failed",
      message: "Timed out",
      snapshot: expect.objectContaining({ tasks: [] })
    });

    const second = await runtime.forceRefresh({ requestId: "req-op-fail-2" });
    expect(second).toEqual({
      kind: "failed",
      message: "Todoist operation failed: timeout",
      snapshot: expect.objectContaining({ tasks: [] })
    });

    expect(observability.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: { operation: "force_refresh", request_id: "req-op-fail-1" }
      },
      {
        level: "warn",
        marker: "forced_refresh_failed",
        attributes: { operation: "force_refresh", request_id: "req-op-fail-1" }
      },
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: { operation: "force_refresh", request_id: "req-op-fail-2" }
      },
      {
        level: "warn",
        marker: "forced_refresh_failed",
        attributes: { operation: "force_refresh", request_id: "req-op-fail-2" }
      }
    ]);
    expect(observability.exceptions).toHaveLength(0);
    expect(observability.backgroundFailures).toHaveLength(0);
  });

  it("captures both forced_refresh_internal_defect and forced_refresh_snapshot_internal_defect without rethrowing when parse defect and fallback snapshot both fail", async () => {
    const parseDefect = new TodoistOperationError("parse");
    const snapshotDefect = new Error("SQLite fallback snapshot read failed");
    const { adapter, observability, runtime } = setupRuntime({
      snapshots: {
        getSnapshot: () => {
          throw snapshotDefect;
        }
      }
    });
    adapter.sync = vi.fn().mockRejectedValue(parseDefect);

    const outcome = await runtime.forceRefresh({ requestId: "req-double-defect" });

    expect(outcome).toEqual({
      kind: "failed",
      message: "Failed to refresh snapshot from Todoist."
    });
    expect("snapshot" in outcome).toBe(false);
    expect(observability.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: { operation: "force_refresh", request_id: "req-double-defect" }
      },
      {
        level: "warn",
        marker: "forced_refresh_failed",
        attributes: { operation: "force_refresh", request_id: "req-double-defect" }
      }
    ]);
    expect(observability.exceptions).toEqual([
      {
        error: parseDefect,
        context: {
          marker: "forced_refresh_internal_defect",
          operation: "force_refresh",
          request_id: "req-double-defect"
        }
      },
      {
        error: snapshotDefect,
        context: {
          marker: "forced_refresh_snapshot_internal_defect",
          operation: "force_refresh",
          request_id: "req-double-defect"
        }
      }
    ]);
  });

  it("forwards AbortSignal to adapter.sync in pollOnce({ deadlineMs: 60_000 }) and returns TodoistSyncSummary", async () => {
    const { adapter, runtime } = setupRuntime();
    adapter.syncResults.push({
      syncToken: "poll-token-1",
      projects: [makeProjectMirror()],
      tasks: [makeTaskMirror({ todoistTaskId: "polled-1" })]
    });
    const syncSpy = vi.spyOn(adapter, "sync");

    const summary = await runtime.pollOnce({ deadlineMs: 60_000 });

    expect(syncSpy).toHaveBeenCalledTimes(1);
    expect(syncSpy).toHaveBeenCalledWith("ready-token", {
      signal: expect.any(AbortSignal)
    });
    expect(summary).toEqual({
      hasChanges: true,
      changedTaskCount: 1,
      changedTaskIds: ["polled-1"],
      changedProjectCount: 1,
      changedCollaboratorCount: 0
    });
  });
});
