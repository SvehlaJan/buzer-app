import { afterEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_POLL_INTERVAL_MS, POLL_DEADLINE_MS, TodoistPoller } from "../../src/services/todoistPoller.js";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

describe("TodoistPoller", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("defaults to 60,000 ms polling interval and runs periodically", async () => {
    expect(DEFAULT_POLL_INTERVAL_MS).toBe(60_000);
    expect(POLL_DEADLINE_MS).toBe(13_000);

    vi.useFakeTimers();
    const syncOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const queue = new TodoistOperationQueue();
    const poller = new TodoistPoller({ syncOnce }, { queue });

    poller.start();
    expect(syncOnce).toHaveBeenCalledTimes(0);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(syncOnce).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(syncOnce).toHaveBeenCalledTimes(2);

    poller.stop();
  });

  it("runs immediately on start when runImmediately is true", async () => {
    vi.useFakeTimers();
    const syncOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const queue = new TodoistOperationQueue();
    const poller = new TodoistPoller({ syncOnce }, { queue, runImmediately: true });

    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(syncOnce).toHaveBeenCalledTimes(1);

    poller.stop();
  });

  it("acquires the TodoistOperationQueue with operation 'poll' and deadlineMs 13,000", async () => {
    const syncOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const queue = new TodoistOperationQueue();
    const runSpy = vi.spyOn(queue, "run");
    const poller = new TodoistPoller({ syncOnce }, { queue });

    await poller.pollOnce();

    expect(runSpy).toHaveBeenCalledTimes(1);
    expect(runSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        operation: "poll",
        deadlineMs: 13_000
      }),
      expect.any(Function)
    );
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(syncOnce).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
  });

  it("gracefully handles a busy queue / deadline timeout by skipping without throwing", async () => {
    let now = 1000;
    const queue = new TodoistOperationQueue({ now: () => now });
    const obs = new RecordingBackendObservability();

    // Block the queue with an in-flight operation
    let releaseBlocker: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });

    const blocker = queue.run(
      { operation: "long_mutation", deadlineMs: 30_000 },
      async () => {
        await blockerPromise;
      }
    );

    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const onError = vi.fn();
    const poller = new TodoistPoller({ syncOnce }, { queue, observability: obs, onError });

    // Poller attempts to acquire the queue while blocker is running
    const pollPromise = poller.pollOnce();

    // Advance time past the poll deadline (13s)
    now += 14_000;

    // Blocker finishes, queue processes the expired poll item
    releaseBlocker!();
    await blocker;
    await expect(pollPromise).resolves.toBeUndefined();

    // syncOnce was not executed because the queue timed out before admission
    expect(syncOnce).toHaveBeenCalledTimes(0);
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
