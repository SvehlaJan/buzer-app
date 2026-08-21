import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { RingBuffer } from "../../src/diagnostics/ringBuffer.js";
import type { AccountabilitySender } from "../../src/accountability/accountabilitySender.js";
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
      return "mock-event-id-002";
    }),
    captureFatal: vi.fn(),
    newEventId: () => "mock-event-id",
    captureDiagnosticEvent: vi.fn(),
    flush: vi.fn(async () => true)
  };
}

describe("EscalationService Diagnostics", () => {
  function setupTestDb() {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-esc-1",
      todoistTaskId: "todoist-esc-1",
      title: "Clean the garage",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-06-15T14:00:00Z",
      durationMinutes: 45,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-06-15T08:00:00Z"
    });
    return { db, repo };
  }

  it("records escalation attempt to diagnostic ring buffer and log ring buffer on successful delivery", async () => {
    const { db, repo } = setupTestDb();
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockResolvedValue(undefined),
      getHealth: vi.fn().mockResolvedValue({ connected: true, detail: "ready" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs,
      diagnosticRingBuffer: diagBuffer,
      logRingBuffer: logBuffer
    });

    const result = await service.sendForTask("task-esc-1", 1);
    expect(result.status).toBe("sent");

    const diagSnapshot = diagBuffer.snapshot() as Array<{
      type: string;
      taskId: string;
      occurrenceKey: string;
      status: string;
      dismissCount: number;
    }>;
    expect(diagSnapshot).toHaveLength(1);
    expect(diagSnapshot[0]?.type).toBe("whatsapp_escalation");
    expect(diagSnapshot[0]?.taskId).toBe("task-esc-1");
    expect(diagSnapshot[0]?.status).toBe("sent");
    expect(diagSnapshot[0]?.dismissCount).toBe(1);

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("INFO WhatsApp escalation sent for task task-esc-1");
    expect(logSnapshot[0]).not.toContain("wife-123456"); // Never leaks recipient

    expect(mockObs.handledCaptures).toHaveLength(0);
  });

  it("captures handled non-fatal buzer.backend.escalation_delivery_failure and records failure on send error", async () => {
    const { db, repo } = setupTestDb();
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockRejectedValue(new Error("whatsapp_not_ready: disconnected")),
      getHealth: vi.fn().mockResolvedValue({ connected: false, detail: "disconnected" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs,
      diagnosticRingBuffer: diagBuffer,
      logRingBuffer: logBuffer
    });

    const result = await service.sendForTask("task-esc-1", 2);
    expect(result.status).toBe("failed");

    const diagSnapshot = diagBuffer.snapshot() as Array<{
      type: string;
      taskId: string;
      status: string;
      error: string;
    }>;
    expect(diagSnapshot).toHaveLength(1);
    expect(diagSnapshot[0]?.type).toBe("whatsapp_escalation");
    expect(diagSnapshot[0]?.taskId).toBe("task-esc-1");
    expect(diagSnapshot[0]?.status).toBe("failed");
    expect(diagSnapshot[0]?.error).toContain("whatsapp_not_ready");

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("ERROR WhatsApp escalation delivery failed for task task-esc-1");

    expect(mockObs.handledCaptures).toHaveLength(1);
    expect(mockObs.handledCaptures[0]?.marker).toBe("buzer.backend.escalation_delivery_failure");
    expect(mockObs.handledCaptures[0]?.correlation).toMatchObject({
      taskId: "task-esc-1",
      recipient_configured: "true"
    });
    // Ensure raw recipient string is NOT in correlation tags
    expect(JSON.stringify(mockObs.handledCaptures[0]?.correlation)).not.toContain("wife-123456");
  });

  it("records already_claimed status to diagnostic ring buffer without error alert", async () => {
    const { db, repo } = setupTestDb();
    const diagBuffer = new RingBuffer<unknown>(50);
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockResolvedValue(undefined),
      getHealth: vi.fn().mockResolvedValue({ connected: true, detail: "ready" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs,
      diagnosticRingBuffer: diagBuffer,
      logRingBuffer: logBuffer
    });

    const first = await service.sendForTask("task-esc-1", 1);
    expect(first.status).toBe("sent");

    const second = await service.sendForTask("task-esc-1", 2);
    expect(second.status).toBe("already_claimed");

    const diagSnapshot = diagBuffer.snapshot() as Array<{
      type: string;
      taskId: string;
      status: string;
    }>;
    expect(diagSnapshot).toHaveLength(2);
    expect(diagSnapshot[0]?.status).toBe("already_claimed");
    expect(diagSnapshot[1]?.status).toBe("sent");
    expect(mockObs.handledCaptures).toHaveLength(0);
  });
});
