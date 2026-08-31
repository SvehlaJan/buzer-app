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
      description: null,
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

  it("handles second dismissal via handleSecondDismissal helper", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Book dentist",
      description: null,
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

    const result = await service.handleSecondDismissal("task-1");
    expect(result.status).toBe("sent");
    expect(sender.sent).toHaveLength(1);
    expect(sender.sent[0]?.recipient).toBe("wife-chat");
    expect(sender.sent[0]?.message).toContain("Book dentist");
  });

  it("allows one escalation for each rotated occurrence", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-rotating",
      todoistTaskId: "todoist-rotating",
      title: "Recurring report",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-07-15T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T10:00:00Z"
    });
    const sender = new DryRunSender();
    const service = new EscalationService(db, repo, sender, "wife-chat");

    await service.sendForTask("task-rotating", 2);
    const currentKey = repo.getById("task-rotating")?.occurrenceKey;
    db.prepare(`
      update commitment_states
      set occurrence_key = 'next-opaque-occurrence', escalation_sent_at = null
      where task_id = 'task-rotating'
    `).run();
    await service.sendForTask("task-rotating", 2);

    expect(sender.sent).toHaveLength(2);
    expect(db.prepare(`
      select occurrence_key as occurrenceKey from whatsapp_escalations
      where task_id = 'task-rotating' order by occurrence_key
    `).all()).toEqual([
      { occurrenceKey: currentKey },
      { occurrenceKey: "next-opaque-occurrence" }
    ]);
  });
});
