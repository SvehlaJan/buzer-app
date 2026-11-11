import type {
  TodoistAdapter,
  TodoistDueMutation,
  TodoistDurationMutation,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistSyncResult
} from "./todoistAdapter.js";
import { parseRetryAfterHeader, TodoistOperationError } from "./todoistErrors.js";
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

  async sync(syncToken: string, options?: { signal?: AbortSignal }): Promise<TodoistSyncResult> {
    const response = await this.request<SyncResponse>("/sync", {
      method: "POST",
      body: this.encodeForm({
        sync_token: syncToken,
        resource_types: JSON.stringify(["items", "projects", "collaborators", "collaborator_states"])
      }),
      ...(options?.signal !== undefined ? { signal: options.signal } : {})
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

  async itemAdd(input: TodoistItemAddInput, options?: { signal?: AbortSignal }): Promise<TodoistItemAddResult> {
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
        applyDurationMutation(args, input.duration);
      }
    }
    const response = await this.command(input.commandUuid, "item_add", args, input.tempId, options);
    return { todoistTaskId: response.temp_id_mapping?.[input.tempId] ?? null };
  }

  async itemUpdate(input: TodoistItemUpdateInput, options?: { signal?: AbortSignal }): Promise<void> {
    const args: Record<string, unknown> = { id: input.todoistTaskId };
    if (input.title !== undefined) {
      args.content = input.title;
    }
    if (input.description !== undefined) {
      args.description = input.description ?? "";
    }
    if (input.duration !== undefined) {
      applyDurationMutation(args, input.duration);
    }
    applyDueMutation(args, input.due);
    if (input.assignment.kind === "assign") {
      args.responsible_uid = input.assignment.assigneeId;
    } else if (input.assignment.kind === "unassign") {
      args.responsible_uid = null;
    }
    await this.command(input.commandUuid, "item_update", args, undefined, options);
  }

  async itemMove(input: TodoistItemMoveInput, options?: { signal?: AbortSignal }): Promise<void> {
    await this.command(input.commandUuid, "item_move", {
      id: input.todoistTaskId,
      project_id: input.projectId
    }, undefined, options);
  }

  async itemClose(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void> {
    const args: Record<string, unknown> = { id: input.todoistTaskId };
    if (input.resetSubtasks === true) {
      args.reset_subtasks = true;
    }
    await this.command(input.commandUuid, "item_close", args, undefined, options);
  }

  async itemUncomplete(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void> {
    await this.command(input.commandUuid, "item_uncomplete", { id: input.todoistTaskId }, undefined, options);
  }

  async itemDelete(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void> {
    await this.command(input.commandUuid, "item_delete", { id: input.todoistTaskId }, undefined, options);
  }

  private async command(
    uuid: string,
    type: string,
    args: Record<string, unknown>,
    tempId?: string,
    options?: { signal?: AbortSignal }
  ): Promise<SyncResponse> {
    const command = tempId === undefined
      ? { type, uuid, args }
      : { type, uuid, temp_id: tempId, args };
    const response = await this.request<SyncResponse>("/sync", {
      method: "POST",
      body: this.encodeForm({
        commands: JSON.stringify([command])
      }),
      ...(options?.signal !== undefined ? { signal: options.signal } : {})
    });
    const status = response.sync_status?.[uuid];
    if (status === "ok") {
      return response;
    }

    const safeAttributes: Record<string, string | number> = { command_type: type };
    if (status === undefined) {
      throw new TodoistOperationError("parse", undefined, {
        ...safeAttributes,
        response_shape: "missing_sync_status"
      });
    }
    if (typeof status === "string" && status.trim().length > 0) {
      throw new TodoistOperationError("command_rejected", undefined, {
        ...safeAttributes,
        error_tag: status
      });
    }
    if (isExplicitCommandRejection(status)) {
      if (typeof status.error_code === "number") {
        safeAttributes.error_code = status.error_code;
      }
      if (typeof status.error_tag === "string") {
        safeAttributes.error_tag = status.error_tag;
      } else if (typeof status.error === "string") {
        safeAttributes.error_tag = status.error;
      }
      throw new TodoistOperationError("command_rejected", undefined, safeAttributes);
    }
    throw new TodoistOperationError("parse", undefined, {
      ...safeAttributes,
      response_shape: "malformed_sync_status"
    });
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const fetchInit: RequestInit = {
      ...init,
      headers: {
        authorization: `Bearer ${this.apiToken}`,
        "content-type": "application/x-www-form-urlencoded",
        ...init.headers
      }
    };
    if (init.signal !== undefined) {
      fetchInit.signal = init.signal;
    }
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, fetchInit);
    } catch (error: unknown) {
      if (error instanceof TodoistOperationError) {
        throw error;
      }
      if (init.signal?.aborted || (error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError"))) {
        throw new TodoistOperationError("timeout");
      }
      throw new TodoistOperationError("transport");
    }


    if (!response.ok) {
      const status = response.status;
      const safeAttributes: Record<string, string | number> = {
        http_code: status
      };
      if (status === 429) {
        const retryAfterHeader = response.headers.get("retry-after");
        const retryAfterMs = parseRetryAfterHeader(retryAfterHeader);
        throw new TodoistOperationError("rate_limited", retryAfterMs, safeAttributes);
      }
      if (status >= 500 && status < 600) {
        throw new TodoistOperationError("server", undefined, safeAttributes);
      }
      if (status >= 400 && status < 500) {
        throw new TodoistOperationError("client", undefined, safeAttributes);
      }
      throw new TodoistOperationError("server", undefined, safeAttributes);
    }

    try {
      return (await response.json()) as T;
    } catch {
      throw new TodoistOperationError("parse", undefined, { http_code: response.status });
    }
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

function isExplicitCommandRejection(status: unknown): status is Record<string, unknown> {
  if (typeof status !== "object" || status === null || Array.isArray(status)) {
    return false;
  }
  const record = status as Record<string, unknown>;
  return typeof record.error_code === "number"
    || (typeof record.error_tag === "string" && record.error_tag.trim().length > 0)
    || (typeof record.error === "string" && record.error.trim().length > 0);
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

function applyDurationMutation(args: Record<string, unknown>, duration: TodoistDurationMutation): void {
  if (duration.kind === "preserve") {
    return;
  }
  if (duration.kind === "clear") {
    args.duration = null;
    return;
  }
  args.duration = { amount: duration.amount, unit: duration.unit };
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




function stringifyNullable(value: string | number | null): string | null {
  return value === null ? null : String(value);
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
