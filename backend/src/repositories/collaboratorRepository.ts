import Database from "better-sqlite3";
import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror
} from "../todoist/todoistTypes.js";

export interface TodoistCollaborator {
  todoistUserId: string;
  fullName: string;
  email: string | null;
}

export class CollaboratorRepository {
  constructor(private readonly db: Database.Database) {}

  isMirrorContractReady(): boolean {
    const row = this.db.prepare(`
      select mirror_contract_version as mirrorContractVersion
      from todoist_sync_state
      where id = 1
    `).get() as { mirrorContractVersion: number } | undefined;
    return (row?.mirrorContractVersion ?? 0) >= 3;
  }

  upsertCollaborator(collaborator: TodoistCollaboratorMirror): void {
    this.db.prepare(`
      insert into todoist_collaborators (
        todoist_user_id, full_name, email, time_zone, image_id, is_deleted, raw_json, updated_at
      ) values (
        @todoistUserId, @fullName, @email, @timeZone, @imageId, @isDeleted, @rawJson, @updatedAt
      )
      on conflict(todoist_user_id) do update set
        full_name = excluded.full_name,
        email = excluded.email,
        time_zone = excluded.time_zone,
        image_id = excluded.image_id,
        is_deleted = excluded.is_deleted,
        raw_json = excluded.raw_json,
        updated_at = excluded.updated_at
    `).run(toCollaboratorRow(collaborator));
  }

  upsertCollaboratorState(collaboratorState: TodoistCollaboratorStateMirror): void {
    this.db.prepare(`
      insert into todoist_project_collaborators (
        todoist_project_id, todoist_user_id, state, role, is_deleted, raw_json, updated_at
      ) values (
        @todoistProjectId, @todoistUserId, @state, @role, @isDeleted, @rawJson, @updatedAt
      )
      on conflict(todoist_project_id, todoist_user_id) do update set
        state = excluded.state,
        role = excluded.role,
        is_deleted = excluded.is_deleted,
        raw_json = excluded.raw_json,
        updated_at = excluded.updated_at
    `).run(toCollaboratorStateRow(collaboratorState));
  }

  listActiveForProject(todoistProjectId: string): TodoistCollaborator[] {
    return this.db.prepare(`
      select
        collaborator.todoist_user_id as todoistUserId,
        collaborator.full_name as fullName,
        collaborator.email
      from todoist_project_collaborators membership
      join todoist_collaborators collaborator
        on collaborator.todoist_user_id = membership.todoist_user_id
      where membership.todoist_project_id = ?
        and membership.state = 'active'
        and membership.is_deleted = 0
        and collaborator.is_deleted = 0
      order by lower(collaborator.full_name), collaborator.todoist_user_id
    `).all(todoistProjectId) as TodoistCollaborator[];
  }

  isActiveMember(todoistProjectId: string, todoistUserId: string): boolean {
    return this.db.prepare(`
      select 1
      from todoist_project_collaborators membership
      join todoist_collaborators collaborator
        on collaborator.todoist_user_id = membership.todoist_user_id
      where membership.todoist_project_id = ?
        and membership.todoist_user_id = ?
        and membership.state = 'active'
        and membership.is_deleted = 0
        and collaborator.is_deleted = 0
    `).get(todoistProjectId, todoistUserId) !== undefined;
  }
}

interface CollaboratorRow {
  todoistUserId: string;
  fullName: string;
  email: string | null;
  timeZone: string | null;
  imageId: string | null;
  isDeleted: 0 | 1;
  rawJson: string;
  updatedAt: string;
}

interface CollaboratorStateRow {
  todoistProjectId: string;
  todoistUserId: string;
  state: "active" | "invited";
  role: string | null;
  isDeleted: 0 | 1;
  rawJson: string;
  updatedAt: string;
}

function toCollaboratorRow(collaborator: TodoistCollaboratorMirror): CollaboratorRow {
  return {
    todoistUserId: collaborator.todoistUserId,
    fullName: collaborator.fullName,
    email: collaborator.email,
    timeZone: collaborator.timeZone,
    imageId: collaborator.imageId,
    isDeleted: collaborator.isDeleted ? 1 : 0,
    rawJson: serializeRawJson(collaborator.rawJson),
    updatedAt: collaborator.updatedAt
  };
}

function toCollaboratorStateRow(collaboratorState: TodoistCollaboratorStateMirror): CollaboratorStateRow {
  return {
    todoistProjectId: collaboratorState.todoistProjectId,
    todoistUserId: collaboratorState.todoistUserId,
    state: collaboratorState.state,
    role: collaboratorState.role,
    isDeleted: collaboratorState.isDeleted ? 1 : 0,
    rawJson: serializeRawJson(collaboratorState.rawJson),
    updatedAt: collaboratorState.updatedAt
  };
}

function serializeRawJson(rawJson: unknown): string {
  return JSON.stringify(rawJson) ?? "null";
}
