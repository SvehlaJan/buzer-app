import Database from "better-sqlite3";
import { SnapshotGenerationRepository } from "./snapshotGenerationRepository.js";

export interface TodoistTaskUpsert {
  id: string;
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string | null;
  parentId?: string | null;
  childOrder?: number | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDate?: string | null;
  dueDatetime: string | null;
  dueString?: string | null;
  dueLang?: string | null;
  dueTimezone?: string | null;
  isRecurring?: boolean;
  durationAmount?: number | null;
  durationUnit?: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: string;
  updatedAt: string;
  commitmentOwnerId?: string | null;
}

export interface TaskWithCommitment extends TodoistTaskUpsert {
  parentId: string | null;
  childOrder: number | null;
  dueDate: string | null;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
  isRecurring: boolean;
  durationAmount: number | null;
  durationUnit: string | null;
  mirrorRevision: number;
  state: string;
  startedAt: string | null;
  commitmentOwnerId: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  lastSnoozedAt: string | null;
  escalationSentAt: string | null;
  scheduleVersion: number;
  occurrenceKey: string | null;
  occurrenceDueDatetime: string | null;
  occurrenceRecurrenceFingerprint: string | null;
  reconciliationStatus: "confirmed" | "pending";
}

const taskWithCommitmentSelect = `
  select
    t.id,
    t.todoist_task_id as todoistTaskId,
    t.title,
    t.description,
    t.project_id as projectId,
    t.parent_id as parentId,
    t.child_order as childOrder,
    t.assignee_id as assigneeId,
    t.creator_id as creatorId,
    t.due_date as dueDate,
    t.due_datetime as dueDatetime,
    t.due_string as dueString,
    t.due_lang as dueLang,
    t.due_timezone as dueTimezone,
    t.is_recurring as isRecurring,
    t.duration_amount as durationAmount,
    t.duration_unit as durationUnit,
    t.duration_minutes as durationMinutes,
    t.completed_at as completedAt,
    t.deleted_at as deletedAt,
    t.raw_json as rawJson,
    t.mirror_revision as mirrorRevision,
    t.updated_at as updatedAt,
    c.state,
    c.started_at as startedAt,
    c.commitment_owner_id as commitmentOwnerId,
    c.start_dismiss_count as startDismissCount,
    c.deadline_extension_count as deadlineExtensionCount,
    c.last_snoozed_at as lastSnoozedAt,
    c.escalation_sent_at as escalationSentAt,
    c.schedule_version as scheduleVersion,
    c.occurrence_key as occurrenceKey,
    c.occurrence_due_datetime as occurrenceDueDatetime,
    c.occurrence_recurrence_fingerprint as occurrenceRecurrenceFingerprint,
    c.reconciliation_status as reconciliationStatus
  from todoist_tasks t
  join commitment_states c on c.task_id = t.id
`;

export class TaskRepository {
  constructor(private readonly db: Database.Database) {}

  currentSnapshotGeneration(): number {
    return new SnapshotGenerationRepository(this.db).current();
  }

  /** Executes the complete projection read in one SQLite transaction. */
  withSnapshotRead<T>(read: () => T): T {
    return this.db.transaction(read).deferred();
  }

  isMirrorContractReady(): boolean {
    const row = this.db.prepare(`
      select mirror_contract_version as mirrorContractVersion
      from todoist_sync_state
      where id = 1
    `).get() as { mirrorContractVersion: number } | undefined;
    return row?.mirrorContractVersion === 2;
  }

  upsertTodoistTask(task: TodoistTaskUpsert): void {
    const taskParams = {
      id: task.id,
      todoistTaskId: task.todoistTaskId,
      title: task.title,
      description: task.description ?? null,
      projectId: task.projectId,
      parentId: task.parentId ?? null,
      childOrder: task.childOrder ?? null,
      assigneeId: task.assigneeId,
      creatorId: task.creatorId,
      dueDate: task.dueDate ?? null,
      dueDatetime: task.dueDatetime,
      dueString: task.dueString ?? null,
      dueLang: task.dueLang ?? null,
      dueTimezone: task.dueTimezone ?? null,
      isRecurring: task.isRecurring === true ? 1 : 0,
      durationAmount: task.durationAmount ?? null,
      durationUnit: task.durationUnit ?? null,
      durationMinutes: task.durationMinutes,
      completedAt: task.completedAt,
      deletedAt: task.deletedAt,
      rawJson: task.rawJson,
      updatedAt: task.updatedAt
    };
    const tx = this.db.transaction(() => {
      this.db.prepare(`
        insert into todoist_tasks (
          id, todoist_task_id, title, description, project_id, parent_id, child_order,
          assignee_id, creator_id, due_date, due_datetime, due_string, due_lang, due_timezone,
          is_recurring, duration_amount, duration_unit, duration_minutes, completed_at,
          deleted_at, raw_json, updated_at
        ) values (
          @id, @todoistTaskId, @title, @description, @projectId, @parentId, @childOrder,
          @assigneeId, @creatorId, @dueDate, @dueDatetime, @dueString, @dueLang, @dueTimezone,
          @isRecurring, @durationAmount, @durationUnit, @durationMinutes, @completedAt,
          @deletedAt, @rawJson, @updatedAt
        )
        on conflict(todoist_task_id) do update set
          title = excluded.title,
          description = excluded.description,
          project_id = excluded.project_id,
          parent_id = excluded.parent_id,
          child_order = excluded.child_order,
          assignee_id = excluded.assignee_id,
          creator_id = excluded.creator_id,
          due_date = excluded.due_date,
          due_datetime = excluded.due_datetime,
          due_string = excluded.due_string,
          due_lang = excluded.due_lang,
          due_timezone = excluded.due_timezone,
          is_recurring = excluded.is_recurring,
          duration_amount = excluded.duration_amount,
          duration_unit = excluded.duration_unit,
          duration_minutes = excluded.duration_minutes,
          completed_at = excluded.completed_at,
          deleted_at = excluded.deleted_at,
          raw_json = excluded.raw_json,
          mirror_revision = case
            when todoist_tasks.title is not excluded.title
              or todoist_tasks.description is not excluded.description
              or todoist_tasks.project_id is not excluded.project_id
              or todoist_tasks.parent_id is not excluded.parent_id
              or todoist_tasks.child_order is not excluded.child_order
              or todoist_tasks.assignee_id is not excluded.assignee_id
              or todoist_tasks.creator_id is not excluded.creator_id
              or todoist_tasks.due_date is not excluded.due_date
              or todoist_tasks.due_datetime is not excluded.due_datetime
              or todoist_tasks.due_string is not excluded.due_string
              or todoist_tasks.due_lang is not excluded.due_lang
              or todoist_tasks.due_timezone is not excluded.due_timezone
              or todoist_tasks.is_recurring is not excluded.is_recurring
              or todoist_tasks.duration_amount is not excluded.duration_amount
              or todoist_tasks.duration_unit is not excluded.duration_unit
              or todoist_tasks.duration_minutes is not excluded.duration_minutes
              or todoist_tasks.completed_at is not excluded.completed_at
              or todoist_tasks.deleted_at is not excluded.deleted_at
            then todoist_tasks.mirror_revision + 1
            else todoist_tasks.mirror_revision
          end,
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
    `).get(todoistTaskId) as TaskWithCommitmentRow | undefined;

    return row === undefined ? null : mapTaskWithCommitment(row);
  }

  getById(id: string): TaskWithCommitment | null {
    const row = this.db.prepare(`
      ${taskWithCommitmentSelect}
      where t.id = ?
    `).get(id) as TaskWithCommitmentRow | undefined;

    return row === undefined ? null : mapTaskWithCommitment(row);
  }

  listAll(): TaskWithCommitment[] {
    const rows = this.db.prepare(`
      ${taskWithCommitmentSelect}
      order by coalesce(t.due_datetime, '9999-12-31T23:59:59Z'), t.title
    `).all() as TaskWithCommitmentRow[];
    return rows.map(mapTaskWithCommitment);
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

  rotateOccurrence(
    id: string,
    values: {
      occurrenceKey: string;
      occurrenceDueDatetime: string | null;
      occurrenceRecurrenceFingerprint: string;
      updatedAt: string;
    }
  ): void {
    this.db.prepare(`
      update commitment_states
      set
        state = 'planned',
        started_at = null,
        start_dismiss_count = 0,
        deadline_extension_count = 0,
        last_snoozed_at = null,
        escalation_sent_at = null,
        occurrence_key = @occurrenceKey,
        occurrence_due_datetime = @occurrenceDueDatetime,
        occurrence_recurrence_fingerprint = @occurrenceRecurrenceFingerprint,
        reconciliation_status = 'confirmed',
        schedule_version = schedule_version + 1,
        updated_at = @updatedAt
      where task_id = @id
    `).run({ id, ...values });
  }

  markOccurrenceReconciliationPending(id: string, updatedAt: string): void {
    this.db.prepare(`
      update commitment_states
      set reconciliation_status = 'pending', updated_at = @updatedAt
      where task_id = @id
    `).run({ id, updatedAt });
  }

  confirmOccurrenceReconciliation(id: string, updatedAt: string): void {
    this.db.prepare(`
      update commitment_states
      set reconciliation_status = 'confirmed', updated_at = @updatedAt
      where task_id = @id
    `).run({ id, updatedAt });
  }

  leaveOccurrenceOwnership(id: string, updatedAt: string): void {
    this.db.prepare(`
      update commitment_states
      set
        state = 'planned',
        started_at = null,
        start_dismiss_count = 0,
        deadline_extension_count = 0,
        last_snoozed_at = null,
        escalation_sent_at = null,
        reconciliation_status = 'confirmed',
        schedule_version = schedule_version + 1,
        updated_at = @updatedAt
      where task_id = @id
    `).run({ id, updatedAt });
  }

  initializeOccurrence(
    id: string,
    values: {
      occurrenceKey: string;
      occurrenceDueDatetime: string | null;
      occurrenceRecurrenceFingerprint: string;
      updatedAt: string;
    }
  ): void {
    this.db.prepare(`
      update commitment_states
      set
        occurrence_key = coalesce(nullif(trim(occurrence_key), ''), @occurrenceKey),
        occurrence_due_datetime = coalesce(occurrence_due_datetime, @occurrenceDueDatetime),
        occurrence_recurrence_fingerprint = coalesce(
          nullif(trim(occurrence_recurrence_fingerprint), ''),
          @occurrenceRecurrenceFingerprint
        ),
        updated_at = @updatedAt
      where task_id = @id
    `).run({ id, ...values });
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
    const revisionChanges = [
      "due_datetime is not @dueDatetime",
      "duration_minutes is not @durationMinutes"
    ];
    const params: Record<string, string | number | null> = { id, dueDatetime, durationMinutes, updatedAt };
    if (description !== undefined) {
      sets.push("description = @description");
      revisionChanges.push("description is not @description");
      params.description = description;
    }
    sets.push(`mirror_revision = mirror_revision + case when ${revisionChanges.join(" or ")} then 1 else 0 end`);
    this.db.prepare(`
      update todoist_tasks
      set ${sets.join(", ")}
      where id = @id
    `).run(params);
  }

  updateTaskFields(
    id: string,
    values: {
      title: string;
      description: string | null;
      projectId: string;
      assigneeId: string | null;
      dueDatetime: string;
      durationMinutes: number;
      updatedAt: string;
      commitmentOwnerId: string | null;
    }
  ): void {
    this.db.prepare(`
      update todoist_tasks
      set
        title = @title,
        description = @description,
        project_id = @projectId,
        assignee_id = @assigneeId,
        due_datetime = @dueDatetime,
        duration_minutes = @durationMinutes,
        mirror_revision = mirror_revision + case
          when title is not @title
            or description is not @description
            or project_id is not @projectId
            or assignee_id is not @assigneeId
            or due_datetime is not @dueDatetime
            or duration_minutes is not @durationMinutes
          then 1
          else 0
        end,
        updated_at = @updatedAt
      where id = @id
    `).run({ id, ...values });
    this.db.prepare(`
      update commitment_states
      set commitment_owner_id = @commitmentOwnerId, updated_at = @updatedAt
      where task_id = @id
    `).run({ id, commitmentOwnerId: values.commitmentOwnerId, updatedAt: values.updatedAt });
  }

  markCompleted(id: string, completedAt: string): void {
    this.db.prepare(`
      update todoist_tasks
      set
        completed_at = @completedAt,
        mirror_revision = mirror_revision + case when completed_at is not @completedAt then 1 else 0 end,
        updated_at = @completedAt
      where id = @id
    `).run({ id, completedAt });
  }

  createChecklistChild(input: {
    id: string;
    todoistTaskId: string;
    parentTodoistTaskId: string;
    projectId: string | null;
    title: string;
    description: string | null;
    childOrder: number;
    updatedAt: string;
  }): void {
    this.upsertTodoistTask({
      id: input.id,
      todoistTaskId: input.todoistTaskId,
      title: input.title,
      description: input.description,
      projectId: input.projectId,
      parentId: input.parentTodoistTaskId,
      childOrder: input.childOrder,
      assigneeId: null,
      creatorId: null,
      dueDate: null,
      dueDatetime: null,
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: null,
      durationUnit: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: input.updatedAt
    });
  }

  updateChecklistChild(id: string, title: string, description: string | null, updatedAt: string): void {
    this.db.prepare(`
      update todoist_tasks
      set title = @title,
          description = @description,
          mirror_revision = mirror_revision + case when title is not @title or description is not @description then 1 else 0 end,
          updated_at = @updatedAt
      where id = @id
    `).run({ id, title, description, updatedAt });
  }

  setChecklistChildChecked(id: string, checked: boolean, updatedAt: string): void {
    const completedAt = checked ? updatedAt : null;
    this.db.prepare(`
      update todoist_tasks
      set completed_at = @completedAt,
          mirror_revision = mirror_revision + case when completed_at is not @completedAt then 1 else 0 end,
          updated_at = @updatedAt
      where id = @id
    `).run({ id, completedAt, updatedAt });
  }

  deleteChecklistChild(id: string, updatedAt: string): void {
    this.db.prepare(`
      update todoist_tasks
      set deleted_at = @updatedAt,
          mirror_revision = mirror_revision + case when deleted_at is not @updatedAt then 1 else 0 end,
          updated_at = @updatedAt
      where id = @id
    `).run({ id, updatedAt });
  }
}

type TaskWithCommitmentRow = Omit<TaskWithCommitment, "isRecurring"> & { isRecurring: number };

function mapTaskWithCommitment(row: TaskWithCommitmentRow): TaskWithCommitment {
  return {
    ...row,
    isRecurring: row.isRecurring === 1
  };
}
