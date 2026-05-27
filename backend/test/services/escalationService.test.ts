import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";

describe("EscalationService", () => {
  it("logs successful escalation and marks the task escalated", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Book dentist",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const sender = new DryRunSender();
    const service = new EscalationService(db, repo, sender, "wife-chat");

    await service.sendForTask("task-1", 2);

    const row = db.prepare("select status, recipient_id as recipientId, sent_at as sentAt from whatsapp_escalations").get() as {
      status: string;
      recipientId: string;
      sentAt: string | null;
    };
    expect(row.status).toBe("sent");
    expect(row.recipientId).toBe("wife-chat");
    expect(row.sentAt).not.toBeNull();
    expect(repo.getById("task-1")?.escalationSentAt).not.toBeNull();
    expect(sender.sent).toHaveLength(1);
  });
});
