import { describe, expect, it, vi } from "vitest";
import { TodoistPoller } from "../../src/services/todoistPoller.js";
import { RingBuffer } from "../../src/diagnostics/ringBuffer.js";
import type { BackendObservability } from "../../src/diagnostics/backendObservability.js";

function createMockObservability(): BackendObservability & {
  handledCaptures: Array<{ marker: string; error: unknown; correlation?: unknown }>;
} {
  const handledCaptures: Array<{ marker: string; error: unknown; correlation?: unknown }> = [];
  return {
    handledCaptures,
    isEnabled: () => true,
    setContext: vi.fn(),
    captureHandled: vi.fn((marker: string, error: unknown, correlation?: unknown) => {
      handledCaptures.push({ marker, error, correlation });
      return "mock-event-id-001";
    }),
    captureFatal: vi.fn(),
    newEventId: () => "mock-event-id",
    captureDiagnosticEvent: vi.fn(),
    flush: vi.fn(async () => true)
  };
}

describe("TodoistPoller Diagnostics", () => {
  it("records every successful poll cycle to diagnostic ring buffer and log ring buffer", async () => {
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const syncOnce = vi.fn().mockResolvedValue(undefined);

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs,
        diagnosticRingBuffer: diagBuffer,
        logRingBuffer: logBuffer
      }
    );

    await poller.tick();

    expect(syncOnce).toHaveBeenCalledTimes(1);
    const diagSnapshot = diagBuffer.snapshot() as Array<{
      type: string;
      outcome: string;
      consecutiveFailures: number;
      durationMs: number;
    }>;
    expect(diagSnapshot).toHaveLength(1);
    expect(diagSnapshot[0]?.type).toBe("todoist_poll");
    expect(diagSnapshot[0]?.outcome).toBe("success");
    expect(diagSnapshot[0]?.consecutiveFailures).toBe(0);
    expect(typeof diagSnapshot[0]?.durationMs).toBe("number");

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("INFO Todoist poll succeeded");

    const diag = poller.getDiagnostics();
    expect(diag.consecutiveFailures).toBe(0);
    expect(diag.lastOutcome).toBe("success");
    expect(diag.lastSyncAt).not.toBeNull();
    expect(typeof diag.lastDurationMs).toBe("number");
    expect(mockObs.handledCaptures).toHaveLength(0);
  });

  it("handles transient failures (<3) by recording to ring buffers and logging warning without Sentry alert", async () => {
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const onError = vi.fn();
    const syncOnce = vi.fn().mockRejectedValue(new Error("Network timeout"));

    const poller = new TodoistPoller(
      { syncOnce },
      {
        onError,
        observability: mockObs,
        diagnosticRingBuffer: diagBuffer,
        logRingBuffer: logBuffer
      }
    );

    // First failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");
    expect(onError).toHaveBeenCalledTimes(1);
    expect(mockObs.handledCaptures).toHaveLength(0);

    // Second failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(2);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");
    expect(onError).toHaveBeenCalledTimes(2);
    expect(mockObs.handledCaptures).toHaveLength(0);

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(2);
    expect(logSnapshot[0]).toContain("WARN Todoist poll failed (consecutive=2");
    expect(logSnapshot[1]).toContain("WARN Todoist poll failed (consecutive=1");
  });

  it("captures handled non-fatal buzer.backend.todoist_poll_failure on consecutiveFailures >= 3", async () => {
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const syncOnce = vi.fn().mockRejectedValue(new Error("Todoist 503 Service Unavailable"));

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs,
        diagnosticRingBuffer: diagBuffer,
        logRingBuffer: logBuffer
      }
    );

    // Failure 1 & 2
    await poller.tick();
    await poller.tick();
    expect(mockObs.handledCaptures).toHaveLength(0);

    // Failure 3: Triggers alert!
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(3);
    expect(mockObs.handledCaptures).toHaveLength(1);
    expect(mockObs.handledCaptures[0]?.marker).toBe("buzer.backend.todoist_poll_failure");
    expect(mockObs.handledCaptures[0]?.correlation).toMatchObject({
      consecutiveFailureCount: 3
    });

    // Failure 4: Continues alerting on persistent failure
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(4);
    expect(mockObs.handledCaptures).toHaveLength(2);
    expect(mockObs.handledCaptures[1]?.correlation).toMatchObject({
      consecutiveFailureCount: 4
    });
  });

  it("resets consecutiveFailures to 0 on subsequent successful poll", async () => {
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    let fail = true;
    const syncOnce = vi.fn().mockImplementation(async () => {
      if (fail) {
        throw new Error("Failed");
      }
    });

    const poller = new TodoistPoller(
      { syncOnce },
      {
        observability: mockObs,
        diagnosticRingBuffer: diagBuffer,
        logRingBuffer: logBuffer
      }
    );

    // 3 failures
    await poller.tick();
    await poller.tick();
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(3);
    expect(mockObs.handledCaptures).toHaveLength(1);

    // Now succeeds
    fail = false;
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(0);
    expect(poller.getDiagnostics().lastOutcome).toBe("success");

    // Next failure is count 1 (transient) and does not capture Sentry error
    fail = true;
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(mockObs.handledCaptures).toHaveLength(1); // No new capture
  });
});
