import { randomUUID } from "node:crypto";
import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistRescheduleInput,
  TodoistSyncResult
} from "./todoistAdapter.js";
import type { TodoistProjectMirror, TodoistTaskMirror } from "./todoistTypes.js";

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
  projects?: TodoistApiProject[];
  sync_status?: Record<string, string>;
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
      tasks: (response.items ?? response.tasks ?? []).map((task) => this.mapTask(task)),
      projects: (response.projects ?? []).map((project) => this.mapProject(project))
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
      body.assignee_id = input.assigneeId;
    }

    const response = await this.request<TodoistApiTask>("/tasks", {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" }
    });
    const task = this.mapTask(response);
    return { todoistTaskId: task.todoistTaskId, task };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    await this.command(randomUUID(), "item_complete", { id: todoistTaskId });
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    await this.command(randomUUID(), "item_update", {
      id: input.todoistTaskId,
      due: { datetime: input.dueDatetime },
      duration: { amount: input.durationMinutes, unit: "minute" }
    });
  }

  private async command(uuid: string, type: string, args: Record<string, unknown>, tempId?: string): Promise<SyncResponse> {
    const command = tempId === undefined
      ? { type, uuid, args }
      : { type, uuid, temp_id: tempId, args };
    const response = await this.request<SyncResponse>("/sync", {
      method: "POST",
      body: this.encodeForm({
        commands: JSON.stringify([command])
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
      : task.duration !== null && task.duration !== undefined && (task.duration.unit ?? task.duration_unit) === "minute"
        ? task.duration.amount ?? null
        : null;
    const completedAt = task.completed_at ?? (task.checked === true ? task.updated_at ?? new Date().toISOString() : null);

    return {
      todoistTaskId: String(task.id),
      title: task.content ?? "",
      projectId: task.project_id ?? null,
      assigneeId: stringifyNullable(task.responsible_uid ?? task.assignee_id ?? null),
      creatorId: stringifyNullable(task.added_by_uid ?? task.assigned_by_uid ?? task.user_id ?? null),
      dueDatetime: task.due?.datetime ?? task.due_datetime ?? null,
      durationMinutes,
      completedAt,
      deletedAt: task.is_deleted === true ? task.updated_at ?? new Date().toISOString() : null,
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
}

function stringifyNullable(value: string | number | null): string | null {
  return value === null ? null : String(value);
}
