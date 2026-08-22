import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";
import type { AccountabilitySender } from "../../src/accountability/accountabilitySender.js";

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

  it("updates task commitment state on successful delivery without reporting errors", async () => {
    const { db, repo } = setupTestDb();
    const mockObs = new RecordingBackendObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockResolvedValue(undefined),
      getHealth: vi.fn().mockResolvedValue({ connected: true, detail: "ready" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs
    });

    const result = await service.sendForTask("task-esc-1", 1);
    expect(result.status).toBe("sent");

    const task = repo.getById("task-esc-1");
    expect(task?.escalationSentAt).not.toBeNull();
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("captures handled non-fatal buzer.backend.escalation_delivery_failure on send error", async () => {
    const { db, repo } = setupTestDb();
    const mockObs = new RecordingBackendObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockRejectedValue(new Error("whatsapp_not_ready: disconnected")),
      getHealth: vi.fn().mockResolvedValue({ connected: false, detail: "disconnected" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs
    });

    const result = await service.sendForTask("task-esc-1", 2);
    expect(result.status).toBe("failed");

    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.escalation_delivery_failure");
    expect(mockObs.backgroundFailures[0]?.tags).toMatchObject({
      task_id: "task-esc-1",
      recipient_configured: "true",
      escalation_id: expect.any(String)
    });
    // Ensure raw recipient string is NOT in tags
    expect(JSON.stringify(mockObs.backgroundFailures[0]?.tags)).not.toContain("wife-123456");
  });

  it("handles already_claimed status without error alert", async () => {
    const { db, repo } = setupTestDb();
    const mockObs = new RecordingBackendObservability();

    const sender: AccountabilitySender = {
      sendEscalation: vi.fn().mockResolvedValue(undefined),
      getHealth: vi.fn().mockResolvedValue({ connected: true, detail: "ready" })
    };

    const service = new EscalationService(db, repo, sender, "wife-123456", {
      observability: mockObs
    });

    const first = await service.sendForTask("task-esc-1", 1);
    expect(first.status).toBe("sent");

    const second = await service.sendForTask("task-esc-1", 2);
    expect(second.status).toBe("already_claimed");
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });
});
