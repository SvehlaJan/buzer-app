import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import type { AccountabilitySender } from "../accountability/accountabilitySender.js";
import { buildEscalationMessage } from "../accountability/escalationMessage.js";
import { computeDeadlineAt } from "../domain/time.js";
import type { TaskRepository } from "../repositories/taskRepository.js";
import { recurrenceFingerprint } from "./occurrenceReconciler.js";
import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";
import { backendDiagnosticRingBuffer, backendLogRingBuffer, type RingBuffer } from "../diagnostics/ringBuffer.js";
import { redactString } from "../diagnostics/redaction.js";

export type EscalationResult =
  | { status: "sent"; escalationId: string; sentAt: string }
  | { status: "failed"; escalationId: string; errorMessage: string }
  | { status: "already_claimed"; escalationId: string | null };

export interface EscalationServiceOptions {
  observability?: BackendObservability;
  diagnosticRingBuffer?: RingBuffer<unknown>;
  logRingBuffer?: RingBuffer<string>;
}

export class EscalationService {
  private readonly observability: BackendObservability;
  private readonly diagnosticRingBuffer: RingBuffer<unknown>;
  private readonly logRingBuffer: RingBuffer<string>;

  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly sender: AccountabilitySender,
    private readonly recipientId: string,
    options?: EscalationServiceOptions | BackendObservability
  ) {
    if (options && "captureHandled" in options) {
      this.observability = options;
      this.diagnosticRingBuffer = backendDiagnosticRingBuffer;
      this.logRingBuffer = backendLogRingBuffer;
    } else {
      this.observability = options?.observability ?? backendObservability;
      this.diagnosticRingBuffer = options?.diagnosticRingBuffer ?? backendDiagnosticRingBuffer;
      this.logRingBuffer = options?.logRingBuffer ?? backendLogRingBuffer;
    }
  }

  async getHealth(): Promise<{ connected: boolean; detail: string }> {
    return this.sender.getHealth();
  }

  async sendForTask(taskId: string, dismissCount: number): Promise<EscalationResult> {
    let task = this.tasks.getById(taskId);
    if (task === null) {
      throw Object.assign(new Error("Task not found"), { code: "task_not_found", statusCode: 404 });
    }
    if (task.dueDatetime === null || task.durationMinutes === null) {
      throw Object.assign(new Error("Task is missing a deadline"), {
        code: "task_missing_deadline",
        statusCode: 422
      });
    }
    if (task.occurrenceKey === null || task.occurrenceKey.trim() === "") {
      this.tasks.initializeOccurrence(task.id, {
        occurrenceKey: randomUUID(),
        occurrenceDueDatetime: task.dueDatetime,
        occurrenceRecurrenceFingerprint: recurrenceFingerprint(task),
        updatedAt: new Date().toISOString()
      });
      task = this.tasks.getById(taskId);
      if (task === null || task.occurrenceKey === null) {
        throw new Error("Task occurrence initialization failed");
      }
    }
    const dueDatetime = task.dueDatetime;
    const durationMinutes = task.durationMinutes;
    const occurrenceKey = task.occurrenceKey;
    if (dueDatetime === null || durationMinutes === null || occurrenceKey === null) {
      throw new Error("Task occurrence initialization failed");
    }

    const message = buildEscalationMessage({
      taskTitle: task.title,
      deadlineAt: computeDeadlineAt(dueDatetime, durationMinutes),
      dismissCount
    });
    const id = randomUUID();
    const createdAt = new Date().toISOString();

    const claim = this.db.prepare(`
      insert or ignore into whatsapp_escalations (
        id, task_id, occurrence_key, recipient_id, message, status, created_at
      ) values (?, ?, ?, ?, ?, 'pending', ?)
    `).run(id, taskId, occurrenceKey, this.recipientId, message, createdAt);
    if (claim.changes === 0) {
      const row = this.db.prepare(
        "select id from whatsapp_escalations where task_id = ? and occurrence_key = ?"
      ).get(taskId, occurrenceKey) as
        | { id: string }
        | undefined;
      const escalationId = row?.id ?? null;
      this.diagnosticRingBuffer.add({
        type: "whatsapp_escalation",
        timestamp: createdAt,
        taskId,
        occurrenceKey,
        escalationId,
        status: "already_claimed",
        dismissCount
      });
      return { status: "already_claimed", escalationId };
    }

    try {
      await this.sender.sendEscalation({
        taskTitle: task.title,
        deadlineAt: computeDeadlineAt(dueDatetime, durationMinutes),
        dismissCount,
        recipient: this.recipientId
      });
    } catch (error) {
      const messageText = error instanceof Error ? error.message : String(error);
      this.db.prepare("update whatsapp_escalations set status = 'failed', error_message = ? where id = ?").run(messageText, id);

      this.diagnosticRingBuffer.add({
        type: "whatsapp_escalation",
        timestamp: new Date().toISOString(),
        taskId,
        occurrenceKey,
        escalationId: id,
        status: "failed",
        dismissCount,
        error: redactString(messageText)
      });

      this.logRingBuffer.add(
        `[${new Date().toISOString()}] ERROR WhatsApp escalation delivery failed for task ${taskId}: ${redactString(messageText)}`
      );

      this.observability.captureHandled("buzer.backend.escalation_delivery_failure", error, {
        taskId,
        occurrenceKey,
        task_id: taskId,
        occurrence_key: occurrenceKey,
        recipient_configured: this.recipientId.trim() !== "" ? "true" : "false",
        recipientConfigured: this.recipientId.trim() !== "" ? "true" : "false",
        escalationId: id,
        escalation_id: id
      });

      return { status: "failed", escalationId: id, errorMessage: messageText };
    }

    const sentAt = new Date().toISOString();
    const tx = this.db.transaction(() => {
      this.db.prepare("update whatsapp_escalations set status = 'sent', sent_at = ? where id = ?").run(sentAt, id);
      this.tasks.updateCommitmentState(taskId, {
        escalationSentAt: sentAt,
        updatedAt: sentAt
      });
    });
    tx();

    this.diagnosticRingBuffer.add({
      type: "whatsapp_escalation",
      timestamp: sentAt,
      taskId,
      occurrenceKey,
      escalationId: id,
      status: "sent",
      dismissCount
    });

    this.logRingBuffer.add(
      `[${sentAt}] INFO WhatsApp escalation sent for task ${taskId}`
    );

    return { status: "sent", escalationId: id, sentAt };
  }
}
