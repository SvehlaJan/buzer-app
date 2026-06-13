import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";

describe("ProjectRepository", () => {
  it("upserts projects and lists active projects by name", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ProjectRepository(db);

    repo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: JSON.stringify({ id: "project-family" }),
      updatedAt: "2026-06-13T10:00:00Z"
    });
    repo.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: JSON.stringify({ id: "project-personal" }),
      updatedAt: "2026-06-13T10:01:00Z"
    });
    repo.upsertProject({
      todoistProjectId: "project-archived",
      name: "Archived",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: true,
      rawJson: JSON.stringify({ id: "project-archived" }),
      updatedAt: "2026-06-13T10:02:00Z"
    });

    expect(repo.listActive()).toEqual([
      {
        todoistProjectId: "project-family",
        name: "Family",
        shared: true,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: JSON.stringify({ id: "project-family" }),
        updatedAt: "2026-06-13T10:00:00Z"
      },
      {
        todoistProjectId: "project-personal",
        name: "Personal",
        shared: false,
        canAssignTasks: false,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: JSON.stringify({ id: "project-personal" }),
        updatedAt: "2026-06-13T10:01:00Z"
      }
    ]);
    expect(repo.getActiveByTodoistProjectId("project-personal")?.canAssignTasks).toBe(false);
    expect(repo.getActiveByTodoistProjectId("project-archived")).toBeNull();
  });
});
