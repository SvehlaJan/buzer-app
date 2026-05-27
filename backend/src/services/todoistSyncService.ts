import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import type { TaskRepository } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";

export class TodoistSyncService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistAdapter
  ) {}

  async syncOnce(): Promise<void> {
    const current = this.db
      .prepare("select sync_token as syncToken from todoist_sync_state where id = 1")
      .get() as { syncToken: string } | undefined;
    const result = await this.todoist.sync(current?.syncToken ?? "*");
    const now = new Date().toISOString();

    const tx = this.db.transaction(() => {
      for (const task of result.tasks) {
        const existing = this.tasks.getByTodoistTaskId(task.todoistTaskId);
        const id = existing?.id ?? randomUUID();
        this.tasks.upsertTodoistTask({
          id,
          todoistTaskId: task.todoistTaskId,
          title: task.title,
          projectId: task.projectId,
          assigneeId: task.assigneeId,
          creatorId: task.creatorId,
          dueDatetime: task.dueDatetime,
          durationMinutes: task.durationMinutes,
          completedAt: task.completedAt,
          deletedAt: task.deletedAt,
          rawJson: JSON.stringify(task.rawJson),
          updatedAt: task.updatedAt
        });

        const terminalState = task.deletedAt !== null
          ? "deleted"
          : task.completedAt !== null
            ? "completed"
            : null;
        if (terminalState !== null && existing?.state !== terminalState) {
          this.tasks.updateCommitmentState(id, {
            state: terminalState,
            incrementScheduleVersion: true,
            updatedAt: task.updatedAt
          });
        }
      }

      this.db.prepare(`
        insert into todoist_sync_state (id, sync_token, updated_at)
        values (1, ?, ?)
        on conflict(id) do update set sync_token = excluded.sync_token, updated_at = excluded.updated_at
      `).run(result.syncToken, now);
    });

    tx();
  }
}
