import Database from "better-sqlite3";

export interface TodoistTaskUpsert {
  id: string;
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: string;
  updatedAt: string;
}

export interface TaskWithCommitment extends TodoistTaskUpsert {
  state: string;
  startedAt: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  lastSnoozedAt: string | null;
  escalationSentAt: string | null;
  scheduleVersion: number;
}

export class TaskRepository {
  constructor(private readonly db: Database.Database) {}

  upsertTodoistTask(task: TodoistTaskUpsert): void {
    const tx = this.db.transaction(() => {
      this.db.prepare(`
        insert into todoist_tasks (
          id, todoist_task_id, title, project_id, assignee_id, creator_id,
          due_datetime, duration_minutes, completed_at, deleted_at, raw_json, updated_at
        ) values (
          @id, @todoistTaskId, @title, @projectId, @assigneeId, @creatorId,
          @dueDatetime, @durationMinutes, @completedAt, @deletedAt, @rawJson, @updatedAt
        )
        on conflict(todoist_task_id) do update set
          title = excluded.title,
          project_id = excluded.project_id,
          assignee_id = excluded.assignee_id,
          creator_id = excluded.creator_id,
          due_datetime = excluded.due_datetime,
          duration_minutes = excluded.duration_minutes,
          completed_at = excluded.completed_at,
          deleted_at = excluded.deleted_at,
          raw_json = excluded.raw_json,
          updated_at = excluded.updated_at
      `).run(task);

      this.db.prepare(`
        insert or ignore into commitment_states (
          task_id, state, start_dismiss_count, deadline_extension_count, schedule_version, updated_at
        ) values (
          @id, 'planned', 0, 0, 1, @updatedAt
        )
      `).run(task);
    });

    tx();
  }

  getByTodoistTaskId(todoistTaskId: string): TaskWithCommitment | null {
    const row = this.db.prepare(`
      select
        t.id,
        t.todoist_task_id as todoistTaskId,
        t.title,
        t.project_id as projectId,
        t.assignee_id as assigneeId,
        t.creator_id as creatorId,
        t.due_datetime as dueDatetime,
        t.duration_minutes as durationMinutes,
        t.completed_at as completedAt,
        t.deleted_at as deletedAt,
        t.raw_json as rawJson,
        t.updated_at as updatedAt,
        c.state,
        c.started_at as startedAt,
        c.start_dismiss_count as startDismissCount,
        c.deadline_extension_count as deadlineExtensionCount,
        c.last_snoozed_at as lastSnoozedAt,
        c.escalation_sent_at as escalationSentAt,
        c.schedule_version as scheduleVersion
      from todoist_tasks t
      join commitment_states c on c.task_id = t.id
      where t.todoist_task_id = ?
    `).get(todoistTaskId) as TaskWithCommitment | undefined;

    return row ?? null;
  }
}
