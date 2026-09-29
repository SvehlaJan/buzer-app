import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { TaskActionService } from "../../src/services/taskActionService.js";
import { TaskMutationRunner } from "../../src/services/taskMutationRunner.js";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { TodoistCommandExecutor } from "../../src/services/todoistCommandExecutor.js";
import type { AccountabilitySender, EscalationInput } from "../../src/accountability/accountabilitySender.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

function insertCommittedTask(repo: TaskRepository): void {
  repo.upsertTodoistTask({
    id: "task-1",
    todoistTaskId: "todoist-1",
    title: "Send invoice",
    description: null,
    projectId: "project-personal",
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-05-08T16:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-05-08T10:00:00Z"
  });
  repo.initializeOccurrence("task-1", {
    occurrenceKey: "occurrence-1",
    occurrenceDueDatetime: "2026-05-08T16:00:00Z",
    occurrenceRecurrenceFingerprint: "one-time",
    updatedAt: "2026-05-08T10:00:00Z"
  });
}

function markCollaboratorMirrorReady(db: Database.Database): void {
  db.prepare(`
    insert or replace into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
    values (1, 'collaborators-ready', 3, '2026-07-22T10:00:00Z')
  `).run();
}

function setupTestService(options: {
  db?: Database.Database;
  sync?: () => Promise<void>;
  escalationSender?: AccountabilitySender;
  escalationService?: EscalationService;
  requireMirrorReady?: boolean;
  queue?: TodoistOperationQueue;
} = {}) {
  const db = options.db ?? new Database(":memory:");
  migrate(db);
  markCollaboratorMirrorReady(db);

  const repo = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  projects.upsertProject({
    todoistProjectId: "project-personal",
    name: "Personal",
    shared: false,
    canAssignTasks: false,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-07-15T09:00:00Z"
  });
  projects.upsertProject({
    todoistProjectId: "project-work",
    name: "Work",
    shared: true,
    canAssignTasks: true,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: "{}",
    updatedAt: "2026-07-15T09:00:00Z"
  });

  const adapter = new FakeTodoistAdapter();
  const queue = options.queue ?? new TodoistOperationQueue();
  const executor = new TodoistCommandExecutor(adapter);
  const syncService = { syncOnce: options.sync ?? (async () => undefined) };
  const snapshotService = () => ({ tasks: repo.listAll() });
  const runner = new TaskMutationRunner({
    queue,
    executor,
    syncService,
    snapshotService: snapshotService as any
  });

  const escalationService = options.escalationService ?? (options.escalationSender
    ? new EscalationService(db, repo, options.escalationSender, "wife-chat")
    : null);

  const service = new TaskActionService(
    db,
    repo,
    projects,
    runner,
    snapshotService as any,
    "user-self",
    escalationService,
    options.requireMirrorReady ?? false
  );

  return { service, repo, projects, adapter, runner, db, escalationService, queue };
}

describe("TaskActionService", () => {
  describe("createTask", () => {
    it("creates a one-time task and checklist items without actionId or deviceId", async () => {
      const { service, adapter } = setupTestService();

      const result = await service.createTask({
        title: "Buy groceries",
        projectId: "project-personal",
        assigneeId: null,
        dueDatetime: "2026-07-16T08:30:00Z",
        durationMinutes: 30,
        description: "Milk, bread",
        checklist: [
          { title: "Organic milk" },
          { title: "Sourdough bread", description: "Fresh from bakery" }
        ]
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemAdds).toHaveLength(3);
      expect(adapter.itemAdds[0]).toMatchObject({
        kind: "root",
        title: "Buy groceries",
        projectId: "project-personal",
        description: "Milk, bread"
      });
      expect(adapter.itemAdds[1]).toMatchObject({
        kind: "child",
        title: "Organic milk",
        projectId: "project-personal"
      });
      expect(adapter.itemAdds[2]).toMatchObject({
        kind: "child",
        title: "Sourdough bread",
        description: "Fresh from bakery",
        projectId: "project-personal"
      });
      const permanentRootId = `created-${adapter.itemAdds[0]!.tempId}`;
      expect(adapter.itemAdds[1]).toMatchObject({ parentTodoistTaskId: permanentRootId });
      expect(adapter.itemAdds[2]).toMatchObject({ parentTodoistTaskId: permanentRootId });
    });

    it("validates recurrence preset and scheduled duration before dispatching commands", async () => {
      const { service, adapter } = setupTestService();

      await expect(service.createTask({
        title: "Invalid task",
        projectId: "project-personal",
        assigneeId: null,
        dueDatetime: "2026-07-16T08:30:00Z",
        durationMinutes: 30,
        recurrencePreset: "invalid-preset" as any
      })).rejects.toMatchObject({
        code: "task_validation_failed",
        details: { errors: expect.arrayContaining(["recurrence_preset_invalid"]) }
      });

      expect(adapter.itemAdds).toHaveLength(0);
    });

    it("rejects blank title and child titles", async () => {
      const { service, adapter } = setupTestService();

      await expect(service.createTask({
        title: "   ",
        projectId: "project-personal",
        assigneeId: null,
        dueDatetime: "2026-07-16T08:30:00Z",
        durationMinutes: 30,
        checklist: [{ title: "   " }]
      })).rejects.toMatchObject({
        code: "task_validation_failed",
        details: { errors: expect.arrayContaining(["title_required", "child_title_required"]) }
      });

      expect(adapter.itemAdds).toHaveLength(0);
    });
  });

  describe("startTask", () => {
    it("updates started_at in SQLite directly without Todoist queue or sync", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      const result = await service.startTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:05:00Z"
      });

      expect(result.kind).toBe("success");
      const task = repo.getById("task-1");
      expect(task?.state).toBe("started");
      expect(task?.startedAt).toBe("2026-05-08T16:05:00Z");
      expect(adapter.itemUpdates).toHaveLength(0);
      expect(adapter.itemCloses).toHaveLength(0);
    });

    it("rejects start when occurrence key is stale", async () => {
      const { service, repo } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.startTask("task-1", {
        expectedOccurrenceKey: "stale-occurrence-key"
      })).rejects.toMatchObject({
        code: "stale_occurrence",
        statusCode: 409
      });
    });
  });

  describe("postponeTask", () => {
    it("updates snoozed_until directly in SQLite", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      const result = await service.postponeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:00:00Z"
      });

      expect(result.kind).toBe("success");
      expect(result.snoozedUntil).toBe("2026-05-08T16:05:00.000Z");
      expect(result.escalationTriggered).toBe(false);

      const task = repo.getById("task-1");
      expect(task?.lastSnoozedAt).toBe("2026-05-08T16:05:00.000Z");
      expect(task?.startDismissCount).toBe(1);
      expect(adapter.itemUpdates).toHaveLength(0);
    });

    it("triggers WhatsApp escalation on second postponement and succeeds even if WhatsApp fails", async () => {
      class FailingSender implements AccountabilitySender {
        sentCount = 0;
        async sendEscalation(): Promise<void> {
          this.sentCount += 1;
          throw new Error("WhatsApp disconnected");
        }
        async getHealth(): Promise<{ connected: boolean; detail: string }> {
          return { connected: false, detail: "disconnected" };
        }
      }

      const failingSender = new FailingSender();
      const { service, repo } = setupTestService({ escalationSender: failingSender });
      insertCommittedTask(repo);

      // First postpone: dismissCount becomes 1
      await service.postponeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:00:00Z"
      });
      expect(failingSender.sentCount).toBe(0);

      // Second postpone: dismissCount becomes 2 -> triggers escalation
      const result = await service.postponeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:05:00Z"
      });

      // Postponement in SQLite must still succeed!
      expect(result.kind).toBe("success");
      expect(result.escalationTriggered).toBe(true);
      expect(result.snoozedUntil).toBe("2026-05-08T16:10:00.000Z");
      expect(failingSender.sentCount).toBe(1);

      const task = repo.getById("task-1");
      expect(task?.startDismissCount).toBe(2);
      expect(task?.lastSnoozedAt).toBe("2026-05-08T16:10:00.000Z");
    });

    it("preserves the local postpone but propagates unexpected escalation defects", async () => {
      const internalDefect = new Error("whatsapp escalation invariant failed");
      const escalationService = {
        handleSecondDismissal: vi.fn().mockRejectedValue(internalDefect)
      } as unknown as EscalationService;
      const { service, repo } = setupTestService({ escalationService });
      insertCommittedTask(repo);

      await service.postponeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:00:00Z"
      });

      await expect(service.postponeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        now: "2026-05-08T16:05:00Z"
      })).rejects.toBe(internalDefect);

      expect(repo.getById("task-1")).toMatchObject({
        startDismissCount: 2,
        lastSnoozedAt: "2026-05-08T16:10:00.000Z"
      });
    });
  });

  describe("completeTask (done)", () => {
    it("builds standard item_close for one-time task", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      const result = await service.completeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1"
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemCloses).toHaveLength(1);
      expect(adapter.itemCloses[0]).toMatchObject({
        todoistTaskId: "todoist-1"
      });
      expect(adapter.itemCloses[0]?.resetSubtasks).toBeUndefined();
    });

    it("builds item_close with resetSubtasks: true for recurring task", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-rec",
        todoistTaskId: "todoist-rec",
        title: "Daily standup",
        description: null,
        projectId: "project-personal",
        assigneeId: "user-self",
        creatorId: "user-self",
        dueDatetime: "2026-05-08T16:00:00Z",
        dueString: "every day at 16:00",
        dueLang: "en",
        dueTimezone: "Europe/Bratislava",
        isRecurring: true,
        durationMinutes: 30,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-05-08T10:00:00Z"
      });
      repo.initializeOccurrence("task-rec", {
        occurrenceKey: "occurrence-rec-1",
        occurrenceDueDatetime: "2026-05-08T16:00:00Z",
        occurrenceRecurrenceFingerprint: "daily",
        updatedAt: "2026-05-08T10:00:00Z"
      });

      const result = await service.completeTask("task-rec", {
        expectedOccurrenceKey: "occurrence-rec-1"
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemCloses).toHaveLength(1);
      expect(adapter.itemCloses[0]).toMatchObject({
        todoistTaskId: "todoist-rec",
        resetSubtasks: true
      });
    });

    it("rejects completion when checklist items are incomplete", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);
      repo.createChecklistChild({
        id: "child-1",
        todoistTaskId: "todoist-child-1",
        parentTodoistTaskId: "todoist-1",
        projectId: "project-personal",
        title: "Subtask 1",
        description: null,
        childOrder: 0,
        updatedAt: "2026-05-08T10:00:00Z"
      });

      await expect(service.completeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1"
      })).rejects.toMatchObject({
        code: "children_incomplete",
        statusCode: 422
      });

      expect(adapter.itemCloses).toHaveLength(0);
    });

    it("rejects completion when occurrence is stale", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.completeTask("task-1", {
        expectedOccurrenceKey: "wrong-occurrence"
      })).rejects.toMatchObject({
        code: "stale_occurrence",
        statusCode: 409
      });

      expect(adapter.itemCloses).toHaveLength(0);
    });

    it("rechecks completion eligibility inside the FIFO immediately before send", async () => {
      const queue = new TodoistOperationQueue();
      const { service, repo, adapter } = setupTestService({ queue });
      insertCommittedTask(repo);
      let releaseBlocker!: () => void;
      const blocker = queue.run(
        { operation: "blocking_poll", deadlineMs: 30_000 },
        () => new Promise<void>((resolve) => { releaseBlocker = resolve; })
      );

      const mutation = service.completeTask("task-1", {
        expectedOccurrenceKey: "occurrence-1"
      });

      repo.markCompleted("task-1", "2026-05-08T16:01:00Z");
      releaseBlocker();
      await blocker;

      await expect(mutation).rejects.toMatchObject({ code: "stale_occurrence", statusCode: 409 });
      expect(adapter.itemCloses).toHaveLength(0);
    });
  });

  describe("extendTask", () => {
    it("extends duration by 30 minutes and appends extension log", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      const result = await service.extendTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`,
        now: "2026-05-08T16:20:00Z"
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemUpdates).toHaveLength(1);
      expect(adapter.itemUpdates[0]).toMatchObject({
        todoistTaskId: "todoist-1",
        duration: { kind: "replace", amount: 60, unit: "minute" },
        description: expect.stringContaining("Extended +30 min")
      });
    });

    it("rejects extension for recurring tasks", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-rec",
        todoistTaskId: "todoist-rec",
        title: "Daily",
        description: null,
        projectId: "project-personal",
        assigneeId: "user-self",
        creatorId: "user-self",
        dueDatetime: "2026-05-08T16:00:00Z",
        dueString: "every day",
        dueLang: "en",
        dueTimezone: "UTC",
        isRecurring: true,
        durationMinutes: 30,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-05-08T10:00:00Z"
      });

      await expect(service.extendTask("task-rec", {
        expectedOccurrenceKey: "occurrence-rec-1",
        expectedTaskRevision: "r:1"
      })).rejects.toMatchObject({
        code: "recurring_extension_unsupported",
        statusCode: 422
      });

      expect(adapter.itemUpdates).toHaveLength(0);
    });

    it("rejects extension when task revision is stale", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.extendTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        expectedTaskRevision: "r:999"
      })).rejects.toMatchObject({
        code: "stale_task",
        statusCode: 409
      });

      expect(adapter.itemUpdates).toHaveLength(0);
    });
  });

  describe("editTask (update)", () => {
    it("builds move and update commands along with child mutations", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);
      repo.createChecklistChild({
        id: "child-1",
        todoistTaskId: "todoist-child-1",
        parentTodoistTaskId: "todoist-1",
        projectId: "project-personal",
        title: "Subtask 1",
        description: null,
        childOrder: 0,
        updatedAt: "2026-05-08T10:00:00Z"
      });

      const childRevision = `r:${repo.getByTodoistTaskId("todoist-child-1")?.mirrorRevision}`;

      const result = await service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`,
          title: "Updated invoice",
          projectId: "project-work",
          assigneeId: "user-self",
          dueDatetime: "2026-05-08T18:00:00Z",
          durationMinutes: 45
        },
        childMutations: [
          {
            type: "check",
            clientMutationId: "check-1",
            childId: "child-1",
            expectedChildRevision: childRevision
          }
        ]
      });

      expect(result.kind).toBe("success");
      // Move + update root + check child
      expect(adapter.itemMoves).toHaveLength(1);
      expect(adapter.itemMoves[0]).toMatchObject({
        todoistTaskId: "todoist-1",
        projectId: "project-work"
      });
      expect(adapter.itemUpdates).toHaveLength(1);
      expect(adapter.itemUpdates[0]).toMatchObject({
        todoistTaskId: "todoist-1",
        title: "Updated invoice",
        duration: { kind: "replace", amount: 45, unit: "minute" }
      });
      expect(adapter.itemCloses).toHaveLength(1);
      expect(adapter.itemCloses[0]).toMatchObject({
        todoistTaskId: "todoist-child-1"
      });
    });

    it("rejects edit when root revision is stale", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: {
          expectedTaskRevision: "r:999",
          title: "Stale edit"
        },
        childMutations: []
      })).rejects.toMatchObject({
        code: "stale_task",
        statusCode: 409
      });

      expect(adapter.itemUpdates).toHaveLength(0);
    });

    it("rechecks the root revision inside the FIFO immediately before the first send", async () => {
      const queue = new TodoistOperationQueue();
      const { service, repo, adapter } = setupTestService({ queue });
      insertCommittedTask(repo);
      const expectedRevision = `r:${repo.getById("task-1")?.mirrorRevision}`;
      let releaseBlocker!: () => void;
      const blocker = queue.run(
        { operation: "blocking_poll", deadlineMs: 30_000 },
        () => new Promise<void>((resolve) => { releaseBlocker = resolve; })
      );

      const mutation = service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: {
          expectedTaskRevision: expectedRevision,
          title: "Queued edit",
          assigneeId: null
        },
        childMutations: []
      });

      repo.updateTaskSchedule("task-1", "2026-05-08T16:00:00Z", 45, "2026-05-08T10:30:00Z");
      releaseBlocker();
      await blocker;

      await expect(mutation).rejects.toMatchObject({ code: "stale_task", statusCode: 409 });
      expect(adapter.itemUpdates).toHaveLength(0);
      expect(adapter.itemMoves).toHaveLength(0);
    });

    it("rejects edit when root revision is malformed attaching snapshot", async () => {
      const { service, repo, adapter } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: {
          expectedTaskRevision: "bad-revision-format",
          title: "Malformed edit"
        },
        childMutations: []
      })).rejects.toMatchObject({
        code: "stale_task",
        statusCode: 409,
        details: { snapshot: expect.any(Object) }
      });

      expect(adapter.itemUpdates).toHaveLength(0);
    });

    it("does not rewrite an unchanged floating due time echoed back as an instant", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-floating",
        todoistTaskId: "todoist-floating",
        title: "Visit",
        description: null,
        projectId: "project-personal",
        assigneeId: null,
        creatorId: "user-self",
        dueDatetime: "2026-10-11T09:00:00",
        durationMinutes: 15,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-09-27T08:50:18Z"
      });
      repo.initializeOccurrence("task-floating", {
        occurrenceKey: "occurrence-floating",
        occurrenceDueDatetime: "2026-10-11T09:00:00",
        occurrenceRecurrenceFingerprint: "one-time",
        updatedAt: "2026-09-27T08:50:18Z"
      });

      const result = await service.editTask("task-floating", {
        expectedOccurrenceKey: "occurrence-floating",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-floating")?.mirrorRevision}`,
          title: "Visit grandparents",
          dueDatetime: "2026-10-11T07:00:00.000Z",
          durationMinutes: 15
        },
        childMutations: []
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemUpdates).toHaveLength(1);
      expect(adapter.itemUpdates[0]).toMatchObject({ title: "Visit grandparents", due: { kind: "preserve" } });
    });

    it("writes a changed due time for a floating task as the new instant", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-floating",
        todoistTaskId: "todoist-floating",
        title: "Visit",
        description: null,
        projectId: "project-personal",
        assigneeId: null,
        creatorId: "user-self",
        dueDatetime: "2026-10-11T09:00:00",
        durationMinutes: 15,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-09-27T08:50:18Z"
      });
      repo.initializeOccurrence("task-floating", {
        occurrenceKey: "occurrence-floating",
        occurrenceDueDatetime: "2026-10-11T09:00:00",
        occurrenceRecurrenceFingerprint: "one-time",
        updatedAt: "2026-09-27T08:50:18Z"
      });

      await service.editTask("task-floating", {
        expectedOccurrenceKey: "occurrence-floating",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-floating")?.mirrorRevision}`,
          dueDatetime: "2026-10-11T08:00:00.000Z",
          durationMinutes: 15
        },
        childMutations: []
      });

      expect(adapter.itemUpdates[0]).toMatchObject({
        due: { kind: "replace", datetime: "2026-10-11T08:00:00.000Z" }
      });
    });

    it("does not rewrite an unchanged floating due time on a duration-only edit", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-floating",
        todoistTaskId: "todoist-floating",
        title: "Visit",
        description: null,
        projectId: "project-personal",
        assigneeId: null,
        creatorId: "user-self",
        dueDatetime: "2026-10-11T09:00:00",
        durationMinutes: 15,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-09-27T08:50:18Z"
      });
      repo.initializeOccurrence("task-floating", {
        occurrenceKey: "occurrence-floating",
        occurrenceDueDatetime: "2026-10-11T09:00:00",
        occurrenceRecurrenceFingerprint: "one-time",
        updatedAt: "2026-09-27T08:50:18Z"
      });

      await service.editTask("task-floating", {
        expectedOccurrenceKey: "occurrence-floating",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-floating")?.mirrorRevision}`,
          dueDatetime: "2026-10-11T07:00:00.000Z",
          durationMinutes: 30
        },
        childMutations: []
      });

      expect(adapter.itemUpdates[0]).toMatchObject({
        due: { kind: "preserve" },
        duration: { kind: "replace", amount: 30, unit: "minute" }
      });
    });
  });
});
