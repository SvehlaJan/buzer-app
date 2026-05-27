import { randomUUID } from "node:crypto";
import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistRescheduleInput,
  TodoistSyncResult
} from "./todoistAdapter.js";
import type { TodoistTaskMirror } from "./todoistTypes.js";

interface TodoistApiTask {
  id: string;
  content?: string;
  project_id?: string | null;
  responsible_uid?: string | null;
  assignee_id?: string | number | null;
  added_by_uid?: string | null;
  assigned_by_uid?: string | null;
  user_id?: string | null;
  due?: { datetime?: string | null; date?: string | null } | null;
  due_datetime?: string | null;
  duration?: { amount?: number; unit?: string } | number | null;
  duration_unit?: string | null;
  completed_at?: string | null;
  checked?: boolean;
  is_deleted?: boolean;
  updated_at?: string;
}

interface SyncResponse {
  sync_token?: string;
  items?: TodoistApiTask[];
  tasks?: TodoistApiTask[];
  sync_status?: Record<string, string>;
  temp_id_mapping?: Record<string, string>;
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
        resource_types: JSON.stringify(["items"])
      })
    });

    return {
      syncToken: response.sync_token ?? syncToken,
      tasks: (response.items ?? response.tasks ?? []).map((task) => this.mapTask(task))
    };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string }> {
    const commandId = randomUUID();
    const tempId = randomUUID();
    const args: Record<string, unknown> = {
      temp_id: tempId,
      content: input.title,
      responsible_uid: input.assigneeId
    };

    if (input.projectId !== null) {
      args.project_id = input.projectId;
    }
    if (input.dueDatetime !== null) {
      args.due = { datetime: input.dueDatetime };
    }
    if (input.durationMinutes !== null) {
      args.duration = { amount: input.durationMinutes, unit: "minute" };
    }

    const response = await this.command(commandId, "item_add", args);
    return { todoistTaskId: response.temp_id_mapping?.[tempId] ?? tempId };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    await this.command(randomUUID(), "item_close", { id: todoistTaskId });
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    await this.command(randomUUID(), "item_update", {
      id: input.todoistTaskId,
      due: { datetime: input.dueDatetime },
      duration: { amount: input.durationMinutes, unit: "minute" }
    });
  }

  private async command(uuid: string, type: string, args: Record<string, unknown>): Promise<SyncResponse> {
    const response = await this.request<SyncResponse>("/sync", {
      method: "POST",
      body: this.encodeForm({
        commands: JSON.stringify([{ type, uuid, args }])
      })
    });
    const status = response.sync_status?.[uuid];
    if (status !== undefined && status !== "ok") {
      throw new Error(`todoist_command_failed:${status}`);
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
      throw new Error(`todoist_http_${response.status}`);
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
    const durationMinutes = typeof task.duration === "number"
      ? task.duration
      : task.duration?.unit === "minute"
        ? task.duration.amount ?? null
        : null;

    return {
      todoistTaskId: String(task.id),
      title: task.content ?? "",
      projectId: task.project_id ?? null,
      assigneeId: stringifyNullable(task.responsible_uid ?? task.assignee_id ?? null),
      creatorId: stringifyNullable(task.added_by_uid ?? task.assigned_by_uid ?? task.user_id ?? null),
      dueDatetime: task.due?.datetime ?? task.due_datetime ?? null,
      durationMinutes,
      completedAt: task.completed_at ?? null,
      deletedAt: task.is_deleted === true ? task.updated_at ?? new Date().toISOString() : null,
      rawJson: task,
      updatedAt: task.updated_at ?? new Date().toISOString()
    };
  }
}

function stringifyNullable(value: string | number | null): string | null {
  return value === null ? null : String(value);
}
