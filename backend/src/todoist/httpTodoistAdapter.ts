import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistLegacyUpdateCommandIdentities,
  TodoistRescheduleInput,
  TodoistUpdateTaskInput,
  TodoistSyncResult
} from "./todoistAdapter.js";
import type {
  TodoistDueMutation,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistRecurringCompletionInput
} from "./todoistMutationGateway.js";
import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror,
  TodoistProjectMirror,
  TodoistTaskMirror
} from "./todoistTypes.js";

interface TodoistApiTask {
  id: string;
  content?: string;
  description?: string | null;
  project_id?: string | null;
  parent_id?: string | number | null;
  child_order?: number | null;
  responsible_uid?: string | null;
  assignee_id?: string | number | null;
  added_by_uid?: string | null;
  assigned_by_uid?: string | null;
  user_id?: string | null;
  due?: {
    datetime?: string | null;
    date?: string | null;
    string?: string | null;
    lang?: string | null;
    timezone?: string | null;
    is_recurring?: boolean;
  } | null;
  due_datetime?: string | null;
  duration?: { amount?: number; unit?: string } | number | null;
  duration_unit?: string | null;
  completed_at?: string | null;
  checked?: boolean | number;
  is_recurring?: boolean;
  is_deleted?: boolean;
  updated_at?: string;
}

interface SyncResponse {
  sync_token?: string;
  updated_at?: string;
  items?: TodoistApiTask[];
  tasks?: TodoistApiTask[];
  projects?: TodoistApiProject[];
  collaborators?: TodoistApiCollaborator[];
  collaborator_states?: TodoistApiCollaboratorState[];
  sync_status?: Record<string, unknown>;
  temp_id_mapping?: Record<string, string>;
}

interface TodoistApiProject {
  id: string;
  name?: string;
  shared?: boolean;
  can_assign_tasks?: boolean;
  inbox_project?: boolean;
  is_deleted?: boolean;
  is_archived?: boolean;
  updated_at?: string;
}

interface TodoistApiCollaborator {
  id: string | number;
  full_name?: string;
  email?: string | null;
  timezone?: string | null;
  image_id?: string | null;
  is_deleted?: boolean;
  updated_at?: string;
}

interface TodoistApiCollaboratorState {
  project_id: string | number;
  user_id: string | number;
  state?: string;
  role?: string | null;
  is_deleted?: boolean;
  updated_at?: string;
}

export class HttpTodoistAdapter implements TodoistAdapter {
  constructor(
    private readonly apiToken: string,
    private readonly baseUrl = "https://api.todoist.com/api/v1"
  ) {}

  async sync(syncToken: string): Promise<TodoistSyncResult> {
    const response = await this.request<SyncResponse>("/sync", {
      method: "POST",
      body: this.encodeForm({
        sync_token: syncToken,
        resource_types: JSON.stringify(["items", "projects", "collaborators", "collaborator_states"])
      })
    });
    const responseUpdatedAt = response.updated_at ?? new Date().toISOString();

    return {
      syncToken: response.sync_token ?? syncToken,
      tasks: (response.items ?? response.tasks ?? []).map((task) => this.mapTask(task)),
      projects: (response.projects ?? []).map((project) => this.mapProject(project)),
      collaborators: (response.collaborators ?? []).map((collaborator) =>
        this.mapCollaborator(collaborator, responseUpdatedAt)
      ),
      collaboratorStates: (response.collaborator_states ?? []).map((state) =>
        this.mapCollaboratorState(state, responseUpdatedAt)
      )
    };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string; task: TodoistTaskMirror }> {
    const body: Record<string, unknown> = {
      content: input.title
    };
    if (input.projectId !== null) {
      body.project_id = input.projectId;
    }
    if (input.dueDatetime !== null) {
      body.due_datetime = input.dueDatetime;
    }
    if (input.durationMinutes !== null) {
      body.duration = input.durationMinutes;
      body.duration_unit = "minute";
    }
    if (input.assigneeId !== null) {
      body.assignee_id = parseTodoistNumericId(input.assigneeId);
    }
    if (input.description !== null && input.description !== undefined && input.description.trim().length > 0) {
      body.description = input.description.trim();
    }

    const response = await this.request<TodoistApiTask>("/tasks", {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" }
    });
    const task = this.mapTask(response);
    return { todoistTaskId: task.todoistTaskId, task };
  }

  async completeTask(todoistTaskId: string, commandUuid: string): Promise<void> {
    await this.itemClose({ commandUuid, todoistTaskId });
  }

  async rescheduleTask(input: TodoistRescheduleInput, commandUuid: string): Promise<void> {
    await this.itemUpdate({
      commandUuid,
      todoistTaskId: input.todoistTaskId,
      due: {
        kind: "replace",
        datetime: input.dueDatetime,
        string: null,
        lang: null,
        timezone: null
      },
      assignment: { kind: "preserve" },
      duration: { amount: input.durationMinutes, unit: "minute" },
      ...("description" in input
        ? { description: normalizeLegacyDescription(input.description) }
        : {})
    });
  }

  async updateTask(input: TodoistUpdateTaskInput, commands: TodoistLegacyUpdateCommandIdentities): Promise<void> {
    if (commands.moveCommandUuid !== undefined) {
      await this.itemMove({
        commandUuid: commands.moveCommandUuid,
        todoistTaskId: input.todoistTaskId,
        projectId: input.projectId
      });
    }
    await this.itemUpdate({
      commandUuid: commands.updateCommandUuid,
      todoistTaskId: input.todoistTaskId,
      title: input.title.trim(),
      description: input.description?.trim() ?? null,
      due: {
        kind: "replace",
        datetime: input.dueDatetime,
        string: null,
        lang: null,
        timezone: null
      },
      assignment: input.assigneeId === null
        ? { kind: "unassign" }
        : { kind: "assign", assigneeId: input.assigneeId },
      duration: { amount: input.durationMinutes, unit: "minute" }
    });
  }

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    const args: Record<string, unknown> = {
      content: input.title,
      project_id: input.projectId
    };
    if (input.description !== null && input.description !== undefined) {
      args.description = input.description;
    }
    if (input.kind === "child") {
      args.parent_id = input.parentTodoistTaskId;
    } else {
      if (input.assigneeId !== null) {
        args.responsible_uid = input.assigneeId;
      }
      applyDueMutation(args, input.due);
      if (input.duration !== null) {
        args.duration = input.duration;
      }
    }
    const response = await this.command(input.commandUuid, "item_add", args, input.tempId);
    return { todoistTaskId: response.temp_id_mapping?.[input.tempId] ?? null };
  }

  async itemUpdate(input: TodoistItemUpdateInput): Promise<void> {
    const args: Record<string, unknown> = { id: input.todoistTaskId };
    if (input.title !== undefined) {
      args.content = input.title;
    }
    if (input.description !== undefined) {
      args.description = input.description ?? "";
    }
    if (input.duration !== undefined) {
      args.duration = input.duration;
    }
    applyDueMutation(args, input.due);
    if (input.assignment.kind === "assign") {
      args.responsible_uid = input.assignment.assigneeId;
    } else if (input.assignment.kind === "unassign") {
      args.responsible_uid = null;
    }
    await this.command(input.commandUuid, "item_update", args);
  }

  async itemMove(input: TodoistItemMoveInput): Promise<void> {
    await this.command(input.commandUuid, "item_move", {
      id: input.todoistTaskId,
      project_id: input.projectId
    });
  }

  async itemClose(input: TodoistItemIdentityInput): Promise<void> {
    await this.command(input.commandUuid, "item_close", { id: input.todoistTaskId });
  }

  async itemUncomplete(input: TodoistItemIdentityInput): Promise<void> {
    await this.command(input.commandUuid, "item_uncomplete", { id: input.todoistTaskId });
  }

  async itemDelete(input: TodoistItemIdentityInput): Promise<void> {
    await this.command(input.commandUuid, "item_delete", { id: input.todoistTaskId });
  }

  async itemUpdateDateComplete(input: TodoistRecurringCompletionInput): Promise<void> {
    const due: Record<string, unknown> = { is_recurring: true };
    const dueDate = input.task.dueDate ?? input.task.dueDatetime;
    if (dueDate !== null) {
      due.date = dueDate;
    }
    if (input.task.dueString !== null) {
      due.string = input.task.dueString;
    }
    if (input.task.dueLang !== null) {
      due.lang = input.task.dueLang;
    }
    if (input.task.dueTimezone !== null) {
      due.timezone = input.task.dueTimezone;
    }
    await this.command(input.commandUuid, "item_update_date_complete", {
      id: input.task.todoistTaskId,
      due,
      is_forward: 1,
      reset_subtasks: true
    });
  }

  private async command(uuid: string, type: string, args: Record<string, unknown>, tempId?: string): Promise<SyncResponse> {
    const command = tempId === undefined
      ? { type, uuid, args }
      : { type, uuid, temp_id: tempId, args };
    let response: SyncResponse;
    try {
      response = await this.request<SyncResponse>("/sync", {
        method: "POST",
        body: this.encodeForm({
          commands: JSON.stringify([command])
        })
      });
    } catch (error) {
      const status = error instanceof TodoistHttpError ? `http_${error.status}` : "transport_error";
      throw new Error(formatCommandFailure(uuid, type, status));
    }
    const status = response.sync_status?.[uuid];
    if (status !== "ok") {
      throw new Error(formatCommandFailure(uuid, type, status ?? "missing_status"));
    }
    return response;
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        authorization: `Bearer ${this.apiToken}`,
        "content-type": "application/x-www-form-urlencoded",
        ...init.headers
      }
    });

    if (!response.ok) {
      throw new TodoistHttpError(response.status);
    }

    return await response.json() as T;
  }

  private encodeForm(values: Record<string, string>): string {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(values)) {
      params.set(key, value);
    }
    return params.toString();
  }

  private mapTask(task: TodoistApiTask): TodoistTaskMirror {
    const durationAmount = typeof task.duration === "number"
      ? task.duration
      : task.duration?.amount ?? null;
    const durationUnit = typeof task.duration === "number"
      ? task.duration_unit ?? "minute"
      : task.duration?.unit ?? task.duration_unit ?? null;
    const durationMinutes = normalizeDurationMinutes(durationAmount, durationUnit);
    const checked = task.checked === true || task.checked === 1;
    const completedAt = task.completed_at ?? (checked ? task.updated_at ?? null : null);
    const dueDate = task.due?.date ?? null;
    const dueDatetime = task.due?.datetime
      ?? task.due_datetime
      ?? (dueDate?.includes("T") ? dueDate : null);

    return {
      todoistTaskId: String(task.id),
      title: task.content ?? "",
      description: task.description ?? null,
      projectId: task.project_id ?? null,
      parentId: stringifyNullable(task.parent_id ?? null),
      childOrder: task.child_order ?? null,
      assigneeId: stringifyNullable(task.responsible_uid ?? task.assignee_id ?? null),
      creatorId: stringifyNullable(task.added_by_uid ?? task.assigned_by_uid ?? task.user_id ?? null),
      dueDate,
      dueDatetime: normalizeTodoistDueDatetime(dueDatetime),
      dueString: task.due?.string ?? null,
      dueLang: task.due?.lang ?? null,
      dueTimezone: task.due?.timezone ?? null,
      isRecurring: task.due?.is_recurring === true || task.is_recurring === true,
      durationAmount,
      durationUnit,
      durationMinutes,
      completedAt,
      deletedAt: task.is_deleted === true ? task.updated_at ?? null : null,
      rawJson: task,
      updatedAt: task.updated_at ?? new Date().toISOString()
    };
  }

  private mapProject(project: TodoistApiProject): TodoistProjectMirror {
    return {
      todoistProjectId: String(project.id),
      name: project.name ?? "",
      shared: project.shared === true,
      canAssignTasks: project.can_assign_tasks === true,
      isInboxProject: project.inbox_project === true,
      isDeleted: project.is_deleted === true,
      isArchived: project.is_archived === true,
      rawJson: project,
      updatedAt: project.updated_at ?? new Date().toISOString()
    };
  }

  private mapCollaborator(
    collaborator: TodoistApiCollaborator,
    fallbackUpdatedAt: string
  ): TodoistCollaboratorMirror {
    return {
      todoistUserId: String(collaborator.id),
      fullName: collaborator.full_name ?? "",
      email: collaborator.email ?? null,
      timeZone: collaborator.timezone ?? null,
      imageId: collaborator.image_id ?? null,
      isDeleted: collaborator.is_deleted === true,
      rawJson: collaborator,
      updatedAt: collaborator.updated_at ?? fallbackUpdatedAt
    };
  }

  private mapCollaboratorState(
    state: TodoistApiCollaboratorState,
    fallbackUpdatedAt: string
  ): TodoistCollaboratorStateMirror {
    return {
      todoistProjectId: String(state.project_id),
      todoistUserId: String(state.user_id),
      state: state.state === "active" ? "active" : "invited",
      role: state.role ?? null,
      isDeleted: state.is_deleted === true,
      rawJson: state,
      updatedAt: state.updated_at ?? fallbackUpdatedAt
    };
  }
}

class TodoistHttpError extends Error {
  constructor(readonly status: number) {
    super(`todoist_http_${status}`);
  }
}

function applyDueMutation(args: Record<string, unknown>, due: TodoistDueMutation): void {
  if (due.kind === "preserve") {
    return;
  }
  if (due.kind === "clear") {
    args.due = null;
    return;
  }
  const replacement: Record<string, unknown> = { date: due.datetime };
  if (due.string !== null) {
    replacement.string = due.string;
  }
  if (due.lang !== null) {
    replacement.lang = due.lang;
  }
  if (due.timezone !== null) {
    replacement.timezone = due.timezone;
  }
  args.due = replacement;
}

function normalizeDurationMinutes(amount: number | null, unit: string | null): number | null {
  if (amount === null) {
    return null;
  }
  if (unit === "minute") {
    return amount;
  }
  if (unit === "hour") {
    return amount * 60;
  }
  return null;
}

function normalizeLegacyDescription(value: string | null | undefined): string | null {
  return value === null || value === undefined || value.trim().length === 0 ? null : value.trim();
}

function formatCommandFailure(uuid: string, type: string, status: unknown): string {
  const serializedStatus = typeof status === "string" ? status : JSON.stringify(status);
  return `todoist_command_failed uuid=${uuid} type=${type} status=${serializedStatus}`;
}

function stringifyNullable(value: string | number | null): string | null {
  return value === null ? null : String(value);
}

function parseTodoistNumericId(value: string): number {
  if (!/^\d+$/.test(value)) {
    throw new Error("todoist_assignee_id_must_be_numeric");
  }
  const numericValue = Number(value);
  if (!Number.isSafeInteger(numericValue)) {
    throw new Error("todoist_assignee_id_must_be_numeric");
  }
  return numericValue;
}

function normalizeTodoistDueDatetime(value: string | null): string | null {
  if (value === null || !value.includes("T")) {
    return null;
  }
  if (!/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
    return value;
  }
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return value;
  }
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}
