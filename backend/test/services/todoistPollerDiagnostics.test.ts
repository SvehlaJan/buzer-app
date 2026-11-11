import { describe, expect, it, vi } from "vitest";
import { TodoistPoller } from "../../src/services/todoistPoller.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

describe("TodoistPoller Diagnostics", () => {
  it("tracks diagnostics on successful empty poll cycle without emitting logs or capturing errors", async () => {
    const mockObs = new RecordingBackendObservability();
    const pollOnce = vi.fn().mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });

    const poller = new TodoistPoller(
      { pollOnce },
      {
        observability: mockObs
      }
    );

    await poller.tick();

    expect(pollOnce).toHaveBeenCalledTimes(1);
    const diag = poller.getDiagnostics();
    expect(diag.consecutiveFailures).toBe(0);
    expect(diag.lastOutcome).toBe("success");
    expect(diag.lastSyncAt).not.toBeNull();
    expect(typeof diag.lastDurationMs).toBe("number");

    // Empty success emits no searchable logs and no Sentry issues
    expect(mockObs.logs).toHaveLength(0);
    expect(mockObs.exceptions).toHaveLength(0);
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("emits todoist_poll_changed info log on successful poll that discovers changes", async () => {
    const mockObs = new RecordingBackendObservability();
    const pollOnce = vi.fn().mockResolvedValue({
      hasChanges: true,
      changedTaskCount: 3,
      changedTaskIds: ["task-1", "task-2", "task-3"],
      changedProjectCount: 1,
      changedCollaboratorCount: 0
    });

    const poller = new TodoistPoller(
      { pollOnce },
      {
        observability: mockObs
      }
    );

    await poller.tick();

    expect(pollOnce).toHaveBeenCalledTimes(1);
    expect(mockObs.logs).toHaveLength(1);
    expect(mockObs.logs[0]).toMatchObject({
      level: "info",
      marker: "todoist_poll_changed",
      attributes: expect.objectContaining({
        changed_task_count: 3,
        changed_task_ids: ["task-1", "task-2", "task-3"],
        changed_project_count: 1,
        changed_collaborator_count: 0
      })
    });
    expect(mockObs.exceptions).toHaveLength(0);
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("emits todoist_poll_failed warning log on operational failure and does NOT capture Sentry issue", async () => {
    const mockObs = new RecordingBackendObservability();
    const onError = vi.fn();
    const pollOnce = vi.fn()
      .mockRejectedValueOnce(new TodoistOperationError("transport"))
      .mockRejectedValueOnce(new Error("Network timeout"))
      .mockRejectedValueOnce(new Error("Todoist 503 Service Unavailable"))
      .mockRejectedValueOnce(new TodoistOperationError("rate_limited"));

    const poller = new TodoistPoller(
      { pollOnce },
      {
        onError,
        observability: mockObs
      }
    );

    // 4 consecutive operational failures
    for (let i = 1; i <= 4; i++) {
      await poller.tick();
      expect(poller.getDiagnostics().consecutiveFailures).toBe(i);
      expect(poller.getDiagnostics().lastOutcome).toBe("error");
    }

    expect(onError).toHaveBeenCalledTimes(4);
    // Every operational failure emits a warning log
    expect(mockObs.logs).toHaveLength(4);
    for (const log of mockObs.logs) {
      expect(log).toMatchObject({
        level: "warn",
        marker: "todoist_poll_failed",
        attributes: expect.objectContaining({
          operational: true
        })
      });
    }

    // Operational failures must NEVER capture a Sentry issue, even after >= 3 failures
    expect(mockObs.exceptions).toHaveLength(0);
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("emits todoist_poll_recovered info log on first subsequent successful poll after failure", async () => {
    const mockObs = new RecordingBackendObservability();
    let fail = true;
    const pollOnce = vi.fn().mockImplementation(async () => {
      if (fail) {
        throw new TodoistOperationError("transport");
      }
      return {
        hasChanges: false,
        changedTaskCount: 0,
        changedTaskIds: [],
        changedProjectCount: 0,
        changedCollaboratorCount: 0
      };
    });

    const poller = new TodoistPoller(
      { pollOnce },
      {
        observability: mockObs
      }
    );

    // 2 failures
    await poller.tick();
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(2);
    expect(mockObs.logs).toHaveLength(2);

    // First recovery success
    fail = false;
    await poller.tick();
    expect(poller.getDiagnostics().consecutiveFailures).toBe(0);
    expect(poller.getDiagnostics().lastOutcome).toBe("success");

    // Recovery info log emitted with previous_failure_count = 2
    expect(mockObs.logs).toHaveLength(3);
    expect(mockObs.logs[2]).toMatchObject({
      level: "info",
      marker: "todoist_poll_recovered",
      attributes: expect.objectContaining({
        previous_failure_count: 2
      })
    });

    // Next successful empty poll does not emit recovery or changed log
    await poller.tick();
    expect(mockObs.logs).toHaveLength(3);
  });

  it("captures Sentry exception on parse / internal defect", async () => {
    const mockObs = new RecordingBackendObservability();
    const pollOnce = vi.fn().mockRejectedValue(new TodoistOperationError("parse"));

    const poller = new TodoistPoller(
      { pollOnce },
      {
        observability: mockObs
      }
    );

    await poller.tick();

    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(poller.getDiagnostics().lastOutcome).toBe("error");

    // Warning log emitted
    expect(mockObs.logs).toHaveLength(1);
    expect(mockObs.logs[0]).toMatchObject({
      level: "warn",
      marker: "todoist_poll_failed",
      attributes: expect.objectContaining({
        error_kind: "parse",
        operational: false
      })
    });

    // Sentry issue captured for parse defect
    expect(mockObs.exceptions.length + mockObs.backgroundFailures.length).toBeGreaterThan(0);
  });

  it("safely handles exceptions thrown within onError callback", async () => {
    const mockObs = new RecordingBackendObservability();
    const pollOnce = vi.fn().mockRejectedValue(new TodoistOperationError("transport"));
    const throwingOnError = vi.fn(() => {
      throw new Error("Callback exploded");
    });

    const poller = new TodoistPoller(
      { pollOnce },
      {
        onError: throwingOnError,
        observability: mockObs
      }
    );

    // Should not throw
    await poller.tick();

    expect(throwingOnError).toHaveBeenCalledTimes(1);
    expect(poller.getDiagnostics().consecutiveFailures).toBe(1);
    expect(mockObs.logs).toHaveLength(1);
    expect(mockObs.logs[0]?.marker).toBe("todoist_poll_failed");
  });
});
