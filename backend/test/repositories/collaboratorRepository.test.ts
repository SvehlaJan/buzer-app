import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";

describe("CollaboratorRepository", () => {
  it("lists only active, non-deleted collaborators for a project", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new CollaboratorRepository(db);

    repo.upsertCollaborator({
      todoistUserId: "user-me",
      fullName: "Me",
      email: "me@example.test",
      timeZone: "Europe/Warsaw",
      imageId: null,
      isDeleted: false,
      rawJson: { id: "user-me" },
      updatedAt: "2026-07-21T10:00:00Z"
    });
    repo.upsertCollaborator({
      todoistUserId: "user-wife",
      fullName: "Wife",
      email: "wife@example.test",
      timeZone: "Europe/Warsaw",
      imageId: null,
      isDeleted: false,
      rawJson: { id: "user-wife" },
      updatedAt: "2026-07-21T10:01:00Z"
    });
    repo.upsertCollaboratorState({
      todoistProjectId: "project-test",
      todoistUserId: "user-wife",
      state: "active",
      role: "admin",
      isDeleted: false,
      rawJson: { project_id: "project-test", user_id: "user-wife" },
      updatedAt: "2026-07-21T10:02:00Z"
    });
    repo.upsertCollaboratorState({
      todoistProjectId: "project-former",
      todoistUserId: "user-wife",
      state: "active",
      role: null,
      isDeleted: true,
      rawJson: { project_id: "project-former", user_id: "user-wife", is_deleted: true },
      updatedAt: "2026-07-21T10:03:00Z"
    });
    repo.upsertCollaboratorState({
      todoistProjectId: "project-test",
      todoistUserId: "user-former",
      state: "active",
      role: null,
      isDeleted: true,
      rawJson: { project_id: "project-test", user_id: "user-former", is_deleted: true },
      updatedAt: "2026-07-21T10:04:00Z"
    });

    expect(repo.listActiveForProject("project-test")).toEqual([
      { todoistUserId: "user-wife", fullName: "Wife", email: "wife@example.test" }
    ]);
    expect(repo.isActiveMember("project-test", "user-wife")).toBe(true);
    expect(repo.isActiveMember("project-test", "user-former")).toBe(false);
  });
});
