import Database from "better-sqlite3";

export interface TodoistProjectUpsert {
  todoistProjectId: string;
  name: string;
  shared: boolean;
  canAssignTasks: boolean;
  isInboxProject: boolean;
  isDeleted: boolean;
  isArchived: boolean;
  rawJson: string;
  updatedAt: string;
}

export interface TodoistProject extends TodoistProjectUpsert {}

export class ProjectRepository {
  constructor(private readonly db: Database.Database) {}

  upsertProject(project: TodoistProjectUpsert): void {
    this.db.prepare(`
      insert into todoist_projects (
        todoist_project_id, name, shared, can_assign_tasks, is_inbox_project,
        is_deleted, is_archived, raw_json, updated_at
      ) values (
        @todoistProjectId, @name, @shared, @canAssignTasks, @isInboxProject,
        @isDeleted, @isArchived, @rawJson, @updatedAt
      )
      on conflict(todoist_project_id) do update set
        name = excluded.name,
        shared = excluded.shared,
        can_assign_tasks = excluded.can_assign_tasks,
        is_inbox_project = excluded.is_inbox_project,
        is_deleted = excluded.is_deleted,
        is_archived = excluded.is_archived,
        raw_json = excluded.raw_json,
        updated_at = excluded.updated_at
    `).run(toRow(project));
  }

  getActiveByTodoistProjectId(todoistProjectId: string): TodoistProject | null {
    const row = this.db.prepare(`
      select ${projectSelect}
      from todoist_projects
      where todoist_project_id = ?
        and is_deleted = 0
        and is_archived = 0
    `).get(todoistProjectId) as ProjectRow | undefined;

    return row === undefined ? null : fromRow(row);
  }

  listActive(): TodoistProject[] {
    return this.db.prepare(`
      select ${projectSelect}
      from todoist_projects
      where is_deleted = 0
        and is_archived = 0
      order by lower(name), todoist_project_id
    `).all().map((row) => fromRow(row as ProjectRow));
  }
}

const projectSelect = `
  todoist_project_id as todoistProjectId,
  name,
  shared,
  can_assign_tasks as canAssignTasks,
  is_inbox_project as isInboxProject,
  is_deleted as isDeleted,
  is_archived as isArchived,
  raw_json as rawJson,
  updated_at as updatedAt
`;

interface ProjectRow {
  todoistProjectId: string;
  name: string;
  shared: 0 | 1;
  canAssignTasks: 0 | 1;
  isInboxProject: 0 | 1;
  isDeleted: 0 | 1;
  isArchived: 0 | 1;
  rawJson: string;
  updatedAt: string;
}

function toRow(project: TodoistProjectUpsert): ProjectRow {
  return {
    todoistProjectId: project.todoistProjectId,
    name: project.name,
    shared: project.shared ? 1 : 0,
    canAssignTasks: project.canAssignTasks ? 1 : 0,
    isInboxProject: project.isInboxProject ? 1 : 0,
    isDeleted: project.isDeleted ? 1 : 0,
    isArchived: project.isArchived ? 1 : 0,
    rawJson: project.rawJson,
    updatedAt: project.updatedAt
  };
}

function fromRow(row: ProjectRow): TodoistProject {
  return {
    todoistProjectId: row.todoistProjectId,
    name: row.name,
    shared: row.shared === 1,
    canAssignTasks: row.canAssignTasks === 1,
    isInboxProject: row.isInboxProject === 1,
    isDeleted: row.isDeleted === 1,
    isArchived: row.isArchived === 1,
    rawJson: row.rawJson,
    updatedAt: row.updatedAt
  };
}
