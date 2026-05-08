import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { DeviceRepository } from "../../src/repositories/deviceRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";

describe("TaskRepository", () => {
  it("upserts task mirror and initializes commitment state", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);

    repo.upsertTodoistTask({
      id: "local-task-1",
      todoistTaskId: "todoist-1",
      title: "Write report",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: "todoist-1" }),
      updatedAt: "2026-05-08T10:00:00Z"
    });

    const snapshot = repo.getByTodoistTaskId("todoist-1");
    expect(snapshot?.title).toBe("Write report");
    expect(snapshot?.state).toBe("planned");
    expect(snapshot?.scheduleVersion).toBe(1);
  });
});

describe("DeviceRepository", () => {
  it("upserts device check-in payloads", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new DeviceRepository(db);

    repo.upsertCheckIn({
      id: "android-1",
      appVersion: "0.1.0-dev",
      permissions: {
        exactAlarm: "granted",
        notifications: "granted",
        fullScreenIntent: "granted",
        batteryOptimizationIgnored: false,
        bootReceiverEnabled: true
      },
      checkedInAt: "2026-05-08T10:00:00Z"
    });

    expect(repo.getById("android-1")).toEqual({
      id: "android-1",
      appVersion: "0.1.0-dev",
      permissions: {
        exactAlarm: "granted",
        notifications: "granted",
        fullScreenIntent: "granted",
        batteryOptimizationIgnored: false,
        bootReceiverEnabled: true
      },
      lastCheckInAt: "2026-05-08T10:00:00Z"
    });
  });
});
