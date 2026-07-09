import Database from "better-sqlite3";

export interface TodoistTaskUpsert {
  id: string;
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: string;
  updatedAt: string;
  commitmentOwnerId?: string | null;
}

export interface TaskWithCommitment extends TodoistTaskUpsert {
  state: string;
  startedAt: string | null;
  commitmentOwnerId: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  lastSnoozedAt: string | null;
  escalationSentAt: string | null;
  scheduleVersion: number;
}

const taskWithCommitmentSelect = `
  select
    t.id,
    t.todoist_task_id as todoistTaskId,
    t.title,
    t.description,
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
    c.commitment_owner_id as commitmentOwnerId,
    c.start_dismiss_count as startDismissCount,
    c.deadline_extension_count as deadlineExtensionCount,
    c.last_snoozed_at as lastSnoozedAt,
    c.escalation_sent_at as escalationSentAt,
    c.schedule_version as scheduleVersion
  from todoist_tasks t
  join commitment_states c on c.task_id = t.id
`;

export class TaskRepository {
  constructor(private readonly db: Database.Database) {}

  upsertTodoistTask(task: TodoistTaskUpsert): void {
    const taskParams = {
      ...task,
      description: task.description ?? null
    };
    const tx = this.db.transaction(() => {
      this.db.prepare(`
        insert into todoist_tasks (
          id, todoist_task_id, title, description, project_id, assignee_id, creator_id,
          due_datetime, duration_minutes, completed_at, deleted_at, raw_json, updated_at
        ) values (
          @id, @todoistTaskId, @title, @description, @projectId, @assigneeId, @creatorId,
          @dueDatetime, @durationMinutes, @completedAt, @deletedAt, @rawJson, @updatedAt
        )
        on conflict(todoist_task_id) do update set
          title = excluded.title,
          description = excluded.description,
          project_id = excluded.project_id,
          assignee_id = excluded.assignee_id,
          creator_id = excluded.creator_id,
          due_datetime = excluded.due_datetime,
          duration_minutes = excluded.duration_minutes,
          completed_at = excluded.completed_at,
          deleted_at = excluded.deleted_at,
          raw_json = excluded.raw_json,
          updated_at = excluded.updated_at
      `).run(taskParams);

      this.db.prepare(`
        insert into commitment_states (
          task_id, state, started_at, commitment_owner_id, start_dismiss_count,
          deadline_extension_count, schedule_version, updated_at
        ) values (
          @id, 'planned', null, @commitmentOwnerId, 0, 0, 1, @updatedAt
        )
        on conflict(task_id) do update set
          commitment_owner_id = coalesce(commitment_states.commitment_owner_id, excluded.commitment_owner_id)
      `).run({
        ...taskParams,
        commitmentOwnerId: task.commitmentOwnerId ?? null
      });
    });

    tx();
  }

  getByTodoistTaskId(todoistTaskId: string): TaskWithCommitment | null {
    const row = this.db.prepare(`
      ${taskWithCommitmentSelect}
      where t.todoist_task_id = ?
    `).get(todoistTaskId) as TaskWithCommitment | undefined;

    return row ?? null;
  }

  getById(id: string): TaskWithCommitment | null {
    const row = this.db.prepare(`
      ${taskWithCommitmentSelect}
      where t.id = ?
    `).get(id) as TaskWithCommitment | undefined;

    return row ?? null;
  }

  listAll(): TaskWithCommitment[] {
    return this.db.prepare(`
      ${taskWithCommitmentSelect}
      order by coalesce(t.due_datetime, '9999-12-31T23:59:59Z'), t.title
    `).all() as TaskWithCommitment[];
  }

  updateCommitmentState(
    id: string,
    values: {
      state?: string;
      startedAt?: string | null;
      startDismissDelta?: number;
      deadlineExtensionDelta?: number;
      lastSnoozedAt?: string | null;
      escalationSentAt?: string | null;
      incrementScheduleVersion?: boolean;
      updatedAt: string;
    }
  ): void {
    const sets = ["updated_at = @updatedAt"];
    const params: Record<string, string | number | null> = { id, updatedAt: values.updatedAt };

    if (values.state !== undefined) {
      sets.push("state = @state");
      params.state = values.state;
    }
    if (values.startedAt !== undefined) {
      sets.push("started_at = @startedAt");
      params.startedAt = values.startedAt;
    }
    if (values.startDismissDelta !== undefined) {
      sets.push("start_dismiss_count = start_dismiss_count + @startDismissDelta");
      params.startDismissDelta = values.startDismissDelta;
    }
    if (values.deadlineExtensionDelta !== undefined) {
      sets.push("deadline_extension_count = deadline_extension_count + @deadlineExtensionDelta");
      params.deadlineExtensionDelta = values.deadlineExtensionDelta;
    }
    if (values.lastSnoozedAt !== undefined) {
      sets.push("last_snoozed_at = @lastSnoozedAt");
      params.lastSnoozedAt = values.lastSnoozedAt;
    }
    if (values.escalationSentAt !== undefined) {
      sets.push("escalation_sent_at = @escalationSentAt");
      params.escalationSentAt = values.escalationSentAt;
    }
    if (values.incrementScheduleVersion === true) {
      sets.push("schedule_version = schedule_version + 1");
    }

    this.db.prepare(`update commitment_states set ${sets.join(", ")} where task_id = @id`).run(params);
  }

  updateTaskSchedule(
    id: string,
    dueDatetime: string,
    durationMinutes: number,
    updatedAt: string,
    description?: string | null
  ): void {
    const sets = [
      "due_datetime = @dueDatetime",
      "duration_minutes = @durationMinutes",
      "updated_at = @updatedAt"
    ];
    const params: Record<string, string | number | null> = { id, dueDatetime, durationMinutes, updatedAt };
    if (description !== undefined) {
      sets.push("description = @description");
      params.description = description;
    }
    this.db.prepare(`
      update todoist_tasks
      set ${sets.join(", ")}
      where id = @id
    `).run(params);
  }

  markCompleted(id: string, completedAt: string): void {
    this.db.prepare(`
      update todoist_tasks
      set completed_at = ?, updated_at = ?
      where id = ?
    `).run(completedAt, completedAt, id);
  }
}
