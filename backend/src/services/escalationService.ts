import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import type { AccountabilitySender } from "../accountability/accountabilitySender.js";
import { buildEscalationMessage } from "../accountability/escalationMessage.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class EscalationService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly sender: AccountabilitySender,
    private readonly recipientId: string
  ) {}

  async sendForTask(taskId: string, dismissCount: number): Promise<void> {
    const task = this.tasks.getById(taskId);
    if (task === null) {
      throw Object.assign(new Error("Task not found"), { code: "task_not_found", statusCode: 404 });
    }
    if (task.dueDatetime === null) {
      throw Object.assign(new Error("Task is missing a deadline"), {
        code: "task_missing_deadline",
        statusCode: 422
      });
    }

    const message = buildEscalationMessage({
      taskTitle: task.title,
      deadlineAt: task.dueDatetime,
      dismissCount
    });
    const id = randomUUID();
    const createdAt = new Date().toISOString();

    this.db.prepare(`
      insert into whatsapp_escalations (id, task_id, recipient_id, message, status, created_at)
      values (?, ?, ?, ?, 'pending', ?)
    `).run(id, taskId, this.recipientId, message, createdAt);

    try {
      await this.sender.sendEscalation({
        taskTitle: task.title,
        deadlineAt: task.dueDatetime,
        dismissCount,
        recipient: this.recipientId
      });

      const sentAt = new Date().toISOString();
      const tx = this.db.transaction(() => {
        this.db.prepare("update whatsapp_escalations set status = 'sent', sent_at = ? where id = ?").run(sentAt, id);
        this.tasks.updateCommitmentState(taskId, {
          escalationSentAt: sentAt,
          updatedAt: sentAt
        });
      });
      tx();
    } catch (error) {
      const messageText = error instanceof Error ? error.message : String(error);
      this.db.prepare("update whatsapp_escalations set status = 'failed', error_message = ? where id = ?").run(messageText, id);
      throw error;
    }
  }
}
