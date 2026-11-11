import Database from "better-sqlite3";
import { afterEach, describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskSnapshotService } from "../../src/services/taskSnapshotService.js";
import { DEFAULT_POLL_INTERVAL_MS, POLL_DEADLINE_MS, TodoistPoller } from "../../src/services/todoistPoller.js";
import { createTodoistRuntime } from "../../src/services/todoistRuntime.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

describe("TodoistPoller", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("defaults to 60,000 ms polling interval and runs periodically", async () => {
    expect(DEFAULT_POLL_INTERVAL_MS).toBe(60_000);
    expect(POLL_DEADLINE_MS).toBe(13_000);

    vi.useFakeTimers();
    const pollOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const poller = new TodoistPoller({ pollOnce });

    poller.start();
    expect(pollOnce).toHaveBeenCalledTimes(0);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(pollOnce).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(pollOnce).toHaveBeenCalledTimes(2);

    poller.stop();
  });

  it("runs immediately on start when runImmediately is true", async () => {
    vi.useFakeTimers();
    const pollOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const poller = new TodoistPoller({ pollOnce }, { runImmediately: true });

    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(pollOnce).toHaveBeenCalledTimes(1);

    poller.stop();
  });

  it("delegates to runtime.pollOnce with deadlineMs 13,000", async () => {
    const pollOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const poller = new TodoistPoller({ pollOnce });

    await poller.pollOnce();

    expect(pollOnce).toHaveBeenCalledTimes(1);
    expect(pollOnce).toHaveBeenCalledWith({ deadlineMs: 13_000 });
  });

  it("gracefully handles a busy runtime queue / deadline timeout by skipping without throwing", async () => {
    let now = 1000;
    const db = new Database(":memory:");
    migrate(db);
    const tasks = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    const adapter = new FakeTodoistAdapter();
    const snapshots = new TaskSnapshotService(tasks, projects, "user-self");
    const obs = new RecordingBackendObservability();
    const runtime = createTodoistRuntime({
      db,
      tasks,
      projects,
      todoist: adapter,
      snapshots,
      currentUserId: "user-self",
      observability: obs,
      now: () => now
    });

    // Block the runtime's internal queue with an in-flight operation
    let notifyEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      notifyEntered = resolve;
    });
    let releaseBlocker!: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });

    let syncCalls = 0;
    adapter.sync = vi.fn(async () => {
      syncCalls += 1;
      if (syncCalls === 1) {
        notifyEntered();
        await blockerPromise;
      }
      return {
        syncToken: "token-1",
        tasks: [],
        projects: [],
        collaborators: [],
        collaboratorStates: []
      };
    });

    const blocker = runtime.pollOnce({ deadlineMs: 30_000 });
    await entered;

    const onError = vi.fn();
    const poller = new TodoistPoller(runtime, { observability: obs, onError });

    // Poller attempts to run while blocker holds the runtime queue
    const pollPromise = poller.pollOnce();

    // Advance time past the poll deadline (13s)
    now += 14_000;

    // Blocker finishes, runtime queue rejects the expired poll item
    releaseBlocker();
    await blocker;
    await expect(pollPromise).resolves.toBeUndefined();

    // Expired poll never invoked adapter.sync
    expect(syncCalls).toBe(1);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(onError).toHaveBeenCalledTimes(1);

    // Warning emitted, but no Sentry Issue / exception
    expect(obs.logs).toHaveLength(1);
    expect(obs.logs[0]).toMatchObject({
      level: "warn",
      marker: "todoist_poll_failed",
      attributes: expect.objectContaining({
        error_kind: "timeout",
        operational: true
      })
    });
    expect(obs.exceptions).toHaveLength(0);
    expect(obs.backgroundFailures).toHaveLength(0);
  });
});
