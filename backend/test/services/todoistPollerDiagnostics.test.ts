import { describe, expect, it, vi } from "vitest";
import { TodoistPoller } from "../../src/services/todoistPoller.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

describe("TodoistPoller Diagnostics", () => {
  it("tracks diagnostics on successful poll cycle without reporting errors", async () => {
    const mockObs = new RecordingBackendObservability();
    const syncOnce = vi.fn().mockResolvedValue(undefined);

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs
      }
    );

    await poller.tick();

    expect(syncOnce).toHaveBeenCalledTimes(1);
    const diag = poller.getDiagnostics();
    expect(diag.consecutiveFailures).toBe(0);
    expect(diag.lastOutcome).toBe("success");
    expect(diag.lastSyncAt).not.toBeNull();
    expect(typeof diag.lastDurationMs).toBe("number");
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("handles transient failures (<3) without triggering background failure capture", async () => {
    const mockObs = new RecordingBackendObservability();
    const onError = vi.fn();
    const syncOnce = vi.fn().mockRejectedValue(new Error("Network timeout"));

    const poller = new TodoistPoller(
      { syncOnce },
      {
        onError,
        observability: mockObs
      }
    );

    // First failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");
    expect(onError).toHaveBeenCalledTimes(1);
    expect(mockObs.backgroundFailures).toHaveLength(0);

    // Second failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(2);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");
    expect(onError).toHaveBeenCalledTimes(2);
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("captures handled non-fatal buzer.backend.todoist_poll_failure on consecutiveFailures >= 3", async () => {
    const mockObs = new RecordingBackendObservability();
    const syncOnce = vi.fn().mockRejectedValue(new Error("Todoist 503 Service Unavailable"));

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs
      }
    );

    // Failure 1 & 2
    await poller.tick();
    await poller.tick();
    expect(mockObs.backgroundFailures).toHaveLength(0);

    // Failure 3: Triggers alert!
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(3);
    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.todoist_poll_failure");
    expect(mockObs.backgroundFailures[0]?.tags).toMatchObject({
      consecutive_failure_count: "3"
    });

    // Failure 4: Continues alerting on persistent failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(4);
    expect(mockObs.backgroundFailures).toHaveLength(2);
    expect(mockObs.backgroundFailures[1]?.tags).toMatchObject({
      consecutive_failure_count: "4"
    });
  });

  it("resets consecutiveFailures to 0 on subsequent successful poll", async () => {
    const mockObs = new RecordingBackendObservability();
    let fail = true;
    const syncOnce = vi.fn().mockImplementation(async () => {
      if (fail) {
        throw new Error("Failed");
      }
    });

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs
      }
    );

    // 3 failures
    await poller.tick();
    await poller.tick();
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(3);
    expect(mockObs.backgroundFailures).toHaveLength(1);

    // Now succeeds
    fail = false;
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(0);
    expect(poller.getDiagnostics().lastOutcome).toBe("success");

    // Next failure is count 1 (transient) and does not capture Sentry error
    fail = true;
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(mockObs.backgroundFailures).toHaveLength(1); // No new capture
  });

  it("safely handles exceptions thrown within onError callback", async () => {
    const mockObs = new RecordingBackendObservability();
    const syncOnce = vi.fn().mockRejectedValue(new Error("Sync exploded"));
    const throwingOnError = vi.fn(() => {
      throw new Error("Callback exploded");
    });

    const poller = new TodoistPoller(
      { syncOnce },
      {
        onError: throwingOnError,
        observability: mockObs
      }
    );

    // Should not throw
    await poller.tick();
    await poller.tick();
    await poller.tick();

    expect(throwingOnError).toHaveBeenCalledTimes(3);
    expect(poller.getDiagnostics().consecutiveFailures).toBe(3);
    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.todoist_poll_failure");
  });
});
