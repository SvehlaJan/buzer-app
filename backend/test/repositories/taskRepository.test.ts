import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { DeviceRepository } from "../../src/repositories/deviceRepository.js";
import { TaskRepository, type TodoistTaskUpsert } from "../../src/repositories/taskRepository.js";

describe("TaskRepository", () => {
  it("keeps task mirror readiness at version 2 while accepting newer mirror contracts", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);

    expect(repo.isMirrorContractReady()).toBe(false);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'v2-token', 2, '2026-07-22T10:00:00Z')
    `).run();
    expect(repo.isMirrorContractReady()).toBe(true);
    db.prepare("update todoist_sync_state set mirror_contract_version = 3 where id = 1").run();
    expect(repo.isMirrorContractReady()).toBe(true);
  });

  it("upserts task mirror and initializes commitment state", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);

    repo.upsertTodoistTask({
      id: "local-task-1",
      todoistTaskId: "todoist-1",
      title: "Write report",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: "todoist-1" }),
      updatedAt: "2026-05-08T10:00:00Z",
      commitmentOwnerId: "user-self"
    });

    const snapshot = repo.getByTodoistTaskId("todoist-1");
    expect(snapshot?.title).toBe("Write report");
    expect(snapshot?.state).toBe("planned");
    expect(snapshot?.scheduleVersion).toBe(1);
    expect(snapshot?.commitmentOwnerId).toBe("user-self");
  });

  it("round trips typed hierarchy, due, recurrence, duration, revision, and occurrence fields", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);

    repo.upsertTodoistTask({
      id: "local-recurring-root",
      todoistTaskId: "todoist-recurring-root",
      title: "Weekly review",
      description: "Review the week",
      projectId: "project-1",
      parentId: null,
      childOrder: 7,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDate: "2026-07-17",
      dueDatetime: "2026-07-17T16:00:00",
      dueString: "every Friday at 16:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 2,
      durationUnit: "hour",
      durationMinutes: 120,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: "todoist-recurring-root" }),
      updatedAt: "2026-07-15T08:00:00Z",
      commitmentOwnerId: "user-self"
    });
    db.prepare(`
      update commitment_states
      set
        occurrence_key = 'opaque-occurrence-key',
        occurrence_due_datetime = '2026-07-17T16:00:00',
        occurrence_recurrence_fingerprint = 'opaque-fingerprint',
        reconciliation_status = 'pending'
      where task_id = 'local-recurring-root'
    `).run();

    expect(repo.getByTodoistTaskId("todoist-recurring-root")).toMatchObject({
      parentId: null,
      childOrder: 7,
      dueDate: "2026-07-17",
      dueDatetime: "2026-07-17T16:00:00",
      dueString: "every Friday at 16:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 2,
      durationUnit: "hour",
      durationMinutes: 120,
      mirrorRevision: 1,
      occurrenceKey: "opaque-occurrence-key",
      occurrenceDueDatetime: "2026-07-17T16:00:00",
      occurrenceRecurrenceFingerprint: "opaque-fingerprint",
      reconciliationStatus: "pending"
    });
    expect(typeof repo.getByTodoistTaskId("todoist-recurring-root")?.isRecurring).toBe("boolean");
  });

  it("keeps revisions stable for identical replays and advances only the changed child", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const root = taskUpsert({
      id: "root-local",
      todoistTaskId: "root-todoist",
      title: "Root",
      parentId: null,
      childOrder: 1
    });
    const child = taskUpsert({
      id: "child-local",
      todoistTaskId: "child-todoist",
      title: "First child title",
      parentId: "root-todoist",
      childOrder: 2
    });

    repo.upsertTodoistTask(root);
    repo.upsertTodoistTask(child);
    repo.upsertTodoistTask(root);
    repo.upsertTodoistTask(child);

    expect(repo.getByTodoistTaskId("root-todoist")?.mirrorRevision).toBe(1);
    expect(repo.getByTodoistTaskId("child-todoist")?.mirrorRevision).toBe(1);

    repo.upsertTodoistTask({
      ...child,
      title: "Changed child title",
      rawJson: JSON.stringify({ id: "child-todoist", content: "Changed child title" }),
      updatedAt: "2026-07-15T08:05:00Z"
    });

    expect(repo.getByTodoistTaskId("root-todoist")?.mirrorRevision).toBe(1);
    expect(repo.getByTodoistTaskId("child-todoist")).toMatchObject({
      title: "Changed child title",
      mirrorRevision: 2
    });
  });

  it("advances mirror revision for changed direct schedule writes but not identical replays", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(taskUpsert({
      id: "schedule-local",
      todoistTaskId: "schedule-todoist",
      description: "Original details",
      dueDatetime: "2026-07-16T09:00:00Z",
      durationMinutes: 30
    }));

    repo.updateTaskSchedule(
      "schedule-local",
      "2026-07-16T09:00:00Z",
      30,
      "2026-07-15T10:00:00Z",
      "Changed details"
    );
    expect(repo.getById("schedule-local")).toMatchObject({
      description: "Changed details",
      mirrorRevision: 2
    });

    repo.updateTaskSchedule(
      "schedule-local",
      "2026-07-16T09:00:00Z",
      30,
      "2026-07-15T10:01:00Z",
      "Changed details"
    );
    expect(repo.getById("schedule-local")?.mirrorRevision).toBe(2);

    repo.updateTaskSchedule(
      "schedule-local",
      "2026-07-16T09:30:00Z",
      45,
      "2026-07-15T10:02:00Z"
    );
    expect(repo.getById("schedule-local")).toMatchObject({
      description: "Changed details",
      dueDatetime: "2026-07-16T09:30:00Z",
      durationMinutes: 45,
      mirrorRevision: 3
    });
  });

  it("advances mirror revision for changed direct field writes but not commitment-only changes", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(taskUpsert({
      id: "fields-local",
      todoistTaskId: "fields-todoist",
      title: "Original title",
      description: null,
      projectId: "project-1",
      assigneeId: null,
      dueDatetime: "2026-07-16T09:00:00Z",
      durationMinutes: 30,
      commitmentOwnerId: "owner-1"
    }));
    const changedFields = {
      title: "Changed title",
      description: "Changed details",
      projectId: "project-2",
      assigneeId: "owner-2",
      dueDatetime: "2026-07-16T10:00:00Z",
      durationMinutes: 45,
      updatedAt: "2026-07-15T10:10:00Z",
      commitmentOwnerId: "owner-2"
    };

    repo.updateTaskFields("fields-local", changedFields);
    expect(repo.getById("fields-local")).toMatchObject({
      ...changedFields,
      mirrorRevision: 2
    });

    repo.updateTaskFields("fields-local", {
      ...changedFields,
      updatedAt: "2026-07-15T10:11:00Z"
    });
    expect(repo.getById("fields-local")?.mirrorRevision).toBe(2);

    repo.updateTaskFields("fields-local", {
      ...changedFields,
      updatedAt: "2026-07-15T10:12:00Z",
      commitmentOwnerId: "owner-3"
    });
    expect(repo.getById("fields-local")).toMatchObject({
      commitmentOwnerId: "owner-3",
      mirrorRevision: 2
    });
  });

  it("advances mirror revision for changed direct completion writes but not identical replays", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(taskUpsert({
      id: "completion-local",
      todoistTaskId: "completion-todoist",
      completedAt: null
    }));

    repo.markCompleted("completion-local", "2026-07-15T10:20:00Z");
    expect(repo.getById("completion-local")).toMatchObject({
      completedAt: "2026-07-15T10:20:00Z",
      mirrorRevision: 2
    });

    repo.markCompleted("completion-local", "2026-07-15T10:20:00Z");
    expect(repo.getById("completion-local")?.mirrorRevision).toBe(2);

    repo.markCompleted("completion-local", "2026-07-15T10:21:00Z");
    expect(repo.getById("completion-local")).toMatchObject({
      completedAt: "2026-07-15T10:21:00Z",
      mirrorRevision: 3
    });
  });

  it("confirms a root after an action reconciliation retry", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(taskUpsert({ id: "pending-local", todoistTaskId: "pending-todoist" }));
    repo.markOccurrenceReconciliationPending("pending-local", "2026-07-15T10:30:00Z");

    repo.confirmOccurrenceReconciliation("pending-local", "2026-07-15T10:31:00Z");

    expect(repo.getById("pending-local")).toMatchObject({
      reconciliationStatus: "confirmed"
    });
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

function taskUpsert(overrides: Partial<TodoistTaskUpsert>): TodoistTaskUpsert {
  return {
    id: "task-local",
    todoistTaskId: "task-todoist",
    title: "Task",
    description: null,
    projectId: "project-1",
    parentId: null,
    childOrder: null,
    assigneeId: null,
    creatorId: "user-self",
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
    rawJson: JSON.stringify({ id: "task-todoist" }),
    updatedAt: "2026-07-15T08:00:00Z",
    ...overrides
  };
}
