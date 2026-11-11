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

// The owner's timezone comes from the collaborator mirror; seed it so floating-time tests do not
// depend on the OWNER_TIMEZONE environment fallback.
function seedOwnerTimeZone(db: Database.Database, timeZone = "Europe/Bratislava"): void {
  new CollaboratorRepository(db).upsertCollaborator({
    todoistUserId: "user-self",
    fullName: "Owner",
    email: null,
    timeZone,
    imageId: null,
    isDeleted: false,
    rawJson: {},
    updatedAt: "2026-09-29T10:00:00Z"
  });
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

  const collaborators = new CollaboratorRepository(db);
  const adapter = new FakeTodoistAdapter();
  const queue = options.queue ?? new TodoistOperationQueue();
  const executor = new TodoistCommandExecutor(adapter);
  const syncService = {
    syncOnce: async () => {
      for (const close of adapter.itemCloses) {
        const target = repo.getByTodoistTaskId(close.todoistTaskId);
        if (target !== null && target.parentId !== null) {
          repo.setChecklistChildChecked(target.id, true, "2026-07-15T09:30:00Z");
        }
      }
      for (const uncomplete of adapter.itemUncompletes) {
        const target = repo.getByTodoistTaskId(uncomplete.todoistTaskId);
        if (target !== null && target.parentId !== null) {
          repo.setChecklistChildChecked(target.id, false, "2026-07-15T09:30:00Z");
        }
      }
      if (options.sync) {
        await options.sync();
      }
    }
  };
  const snapshotService = () => ({ authoritative: true, tasks: repo.listAll() });
  const runner = new TaskMutationRunner({
    queue,
    executor,
    syncService,
    snapshotService: snapshotService as any
  });

  const escalationService = options.escalationService ?? (options.escalationSender
    ? new EscalationService(db, repo, options.escalationSender, "wife-chat")
    : null);

  const service = new TaskActionService({
    tasks: repo,
    projects,
    collaborators,
    snapshots: snapshotService as any,
    runtime: {
      executeMutation: (mutationOptions) => runner.execute(mutationOptions)
    },
    escalationService,
    currentUserId: "user-self",
    requireMirrorReady: options.requireMirrorReady ?? false
  });

  return { service, repo, projects, collaborators, adapter, runner, db, escalationService, queue };
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
      const { service, repo, adapter, db } = setupTestService();
      seedOwnerTimeZone(db);
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
      const { service, repo, adapter, db } = setupTestService();
      seedOwnerTimeZone(db);
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
      const { service, repo, adapter, db } = setupTestService();
      seedOwnerTimeZone(db);
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

  describe("checklist toggle and batched childMutations", () => {
    function seedChild(
      repo: TaskRepository,
      name: string,
      input: { parentTodoistTaskId?: string; projectId?: string; checked?: boolean } = {}
    ) {
      const id = `local-child-${name}`;
      const todoistTaskId = `child-${name}`;
      repo.createChecklistChild({
        id,
        todoistTaskId,
        parentTodoistTaskId: input.parentTodoistTaskId ?? "todoist-1",
        projectId: input.projectId ?? "project-personal",
        title: `Child ${name}`,
        description: null,
        childOrder: 0,
        updatedAt: "2026-07-15T09:00:00Z"
      });
      if (input.checked) {
        repo.setChecklistChildChecked(id, true, "2026-07-15T09:30:00Z");
      }
      return { id, todoistTaskId };
    }

    it("exposes canonical methods only without legacy aliases", () => {
      const { service } = setupTestService();
      const untyped = service as unknown as Record<string, unknown>;
      expect(typeof service.checkChecklistItem).toBe("function");
      expect(typeof service.uncheckChecklistItem).toBe("function");
      expect(untyped.create).toBeUndefined();
      expect(untyped.start).toBeUndefined();
      expect(untyped.postponeStart).toBeUndefined();
      expect(untyped.done).toBeUndefined();
      expect(untyped.extend).toBeUndefined();
      expect(untyped.update).toBeUndefined();
      expect(untyped.mutateChecklist).toBeUndefined();
    });

    it("checks and unchecks with dedicated Todoist commands without completing the root", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);
      seedChild(repo, "one");

      const initial = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
      const checkedResult = await service.checkChecklistItem("task-1", "child-one", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: initial
      });
      expect(checkedResult).toMatchObject({ kind: "success", snapshot: { authoritative: true } });

      const afterCheck = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
      const uncheckedResult = await service.uncheckChecklistItem("task-1", "child-one", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: afterCheck
      });
      expect(uncheckedResult).toMatchObject({ kind: "success", snapshot: { authoritative: true } });

      expect(adapter.itemCloses).toHaveLength(1);
      expect(adapter.itemUncompletes).toHaveLength(1);
      expect(repo.getById("task-1")?.completedAt).toBeNull();
    });

    it("accepts an already observed requested checked state without a second Todoist command", async () => {
      const sync = vi.fn(async () => undefined);
      const { service, adapter, repo } = setupTestService({ sync });
      insertCommittedTask(repo);
      seedChild(repo, "one", { checked: true });
      const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

      const result = await service.checkChecklistItem("task-1", "child-one", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: revision
      });

      expect(result).toMatchObject({ kind: "success", snapshot: { authoritative: true } });
      expect(adapter.itemCloses).toEqual([]);
      expect(sync).toHaveBeenCalledTimes(1);
    });

    it("adds, edits, and deletes checklist children through batched editTask childMutations", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);
      seedChild(repo, "one");
      const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

      const added = await service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [
          { type: "add", clientMutationId: "draft-1", title: "  First step ", description: " Details " },
          { type: "edit", clientMutationId: "edit-1", childId: "child-one", expectedChildRevision: revision, title: "Renamed", description: "New details" }
        ]
      });
      expect(added).toMatchObject({ kind: "success", snapshot: { authoritative: true } });
      expect(adapter.itemAdds).toEqual([
        expect.objectContaining({
          kind: "child",
          parentTodoistTaskId: "todoist-1",
          projectId: "project-personal",
          title: "First step",
          description: "Details"
        })
      ]);
      expect(adapter.itemUpdates).toEqual([
        expect.objectContaining({
          todoistTaskId: "child-one",
          title: "Renamed",
          description: "New details",
          due: { kind: "preserve" }
        })
      ]);

      await service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [
          { type: "delete", clientMutationId: "delete-1", childId: "child-one", expectedChildRevision: revision }
        ]
      });
      expect(adapter.itemDeletes).toEqual([expect.objectContaining({ todoistTaskId: "child-one" })]);
    });

    it("rejects stale expectedOccurrenceKey on checkChecklistItem and batched childMutations", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);
      seedChild(repo, "one");
      const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

      await expect(service.checkChecklistItem("task-1", "child-one", {
        expectedOccurrenceKey: "obsolete-occurrence",
        expectedChildRevision: revision
      })).rejects.toMatchObject({
        code: "stale_occurrence",
        statusCode: 409,
        details: { snapshot: { authoritative: true } }
      });

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "mismatched-key",
        rootPatch: null,
        childMutations: [{ type: "add", clientMutationId: "draft-1", title: "Valid title", description: null }]
      })).rejects.toMatchObject({
        code: "stale_occurrence",
        statusCode: 409,
        details: { snapshot: { authoritative: true } }
      });

      expect(adapter.itemCloses).toHaveLength(0);
      expect(adapter.itemAdds).toHaveLength(0);
    });

    it("rejects stale or malformed expectedChildRevision and rechecks inside the FIFO before send", async () => {
      const queue = new TodoistOperationQueue();
      const { service, adapter, repo } = setupTestService({ queue });
      insertCommittedTask(repo);
      seedChild(repo, "one");
      seedChild(repo, "two");
      const currentTwo = `r:${repo.getByTodoistTaskId("child-two")?.mirrorRevision}`;

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [
          { type: "edit", clientMutationId: "stale", childId: "child-one", expectedChildRevision: "r:0", title: "Ignored", description: null },
          { type: "check", clientMutationId: "safe", childId: "child-two", expectedChildRevision: currentTwo }
        ]
      })).rejects.toMatchObject({ code: "stale_child", statusCode: 409 });

      await expect(service.checkChecklistItem("task-1", "child-one", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: "not-a-valid-revision"
      })).rejects.toMatchObject({
        code: "stale_child",
        statusCode: 409,
        details: { snapshot: { authoritative: true } }
      });

      const expectedRevision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
      let releaseBlocker!: () => void;
      const blocker = queue.run(
        { operation: "blocking_poll", deadlineMs: 30_000 },
        () => new Promise<void>((resolve) => { releaseBlocker = resolve; })
      );

      const mutation = service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [{
          type: "edit",
          clientMutationId: "edit-queued",
          childId: "child-one",
          expectedChildRevision: expectedRevision,
          title: "Queued edit",
          description: null
        }]
      });

      repo.updateChecklistChild("local-child-one", "Changed elsewhere", null, "2026-07-15T09:45:00Z");
      releaseBlocker();
      await blocker;

      await expect(mutation).rejects.toMatchObject({ code: "stale_child", statusCode: 409 });
      expect(adapter.itemCloses).toHaveLength(0);
      expect(adapter.itemUpdates).toHaveLength(0);
    });

    it("rejects duplicate clientMutationId values across batched childMutations before dispatching any command", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [
          { type: "add", clientMutationId: "dup-id", title: "First", description: null },
          { type: "add", clientMutationId: "dup-id", title: "Second", description: null }
        ]
      })).rejects.toMatchObject({
        code: "duplicate_client_mutation_id",
        statusCode: 422
      });

      expect(adapter.itemAdds).toHaveLength(0);
    });

    it("rejects forbidden child fields, blank child titles, and children not under the requested root", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);
      seedChild(repo, "one");
      seedChild(repo, "other", { parentTodoistTaskId: "different-root" });

      const otherRevision = `r:${repo.getByTodoistTaskId("child-other")?.mirrorRevision}`;
      await expect(service.checkChecklistItem("task-1", "child-other", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: otherRevision
      })).rejects.toMatchObject({ code: "child_not_under_root", statusCode: 404 });

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [{
          type: "add",
          clientMutationId: "bad",
          title: "No due",
          description: null,
          dueDatetime: "2026-07-15T12:00:00Z"
        } as any]
      })).rejects.toMatchObject({ code: "forbidden_child_field", statusCode: 422 });

      const oneRevision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [{
          type: "edit",
          clientMutationId: "blank-title",
          childId: "child-one",
          expectedChildRevision: oneRevision,
          title: "   ",
          description: null
        }]
      })).rejects.toMatchObject({ code: "child_title_required", statusCode: 422 });

      expect(adapter.itemAdds).toEqual([]);
      expect(adapter.itemUpdates).toEqual([]);
      expect(adapter.itemCloses).toEqual([]);
    });

    it("rejects a batch projecting > 30 children before adding any children", async () => {
      const { service, adapter, repo } = setupTestService();
      insertCommittedTask(repo);
      for (let index = 0; index < 28; index += 1) {
        seedChild(repo, `seed-${index}`);
      }

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [
          { type: "add", clientMutationId: "add-1", title: "Twenty-nine", description: null },
          { type: "add", clientMutationId: "add-2", title: "Thirty", description: null },
          { type: "add", clientMutationId: "add-3", title: "Too many", description: null }
        ]
      })).rejects.toMatchObject({ code: "too_many_children", statusCode: 422 });

      expect(adapter.itemAdds).toHaveLength(0);
    });

    it.each([
      ["dueDatetime", "2026-07-15T12:00:00Z"],
      ["dueDate", "2026-07-15"],
      ["dueString", "every day"],
      ["dueLang", "en"],
      ["dueTimezone", "Europe/Bratislava"],
      ["isRecurring", true],
      ["durationMinutes", 30],
      ["durationAmount", 30],
      ["durationUnit", "minute"],
      ["assigneeId", "user-self"],
      ["projectId", "project-work"]
    ] as const)("rejects childMutations and checkChecklistItem when root hierarchy is unsupported (%s)", async (field, value) => {
      const { service, repo } = setupTestService();
      insertCommittedTask(repo);
      repo.upsertTodoistTask({
        id: "invalid-child",
        todoistTaskId: "invalid-child",
        title: "Invalid child",
        description: null,
        projectId: field === "projectId" ? (value as string) : "project-personal",
        parentId: "todoist-1",
        childOrder: 0,
        assigneeId: field === "assigneeId" ? (value as string) : null,
        creatorId: null,
        dueDatetime: field === "dueDatetime" ? (value as string) : null,
        dueDate: field === "dueDate" ? (value as string) : null,
        dueString: field === "dueString" ? (value as string) : null,
        dueLang: field === "dueLang" ? (value as string) : null,
        dueTimezone: field === "dueTimezone" ? (value as string) : null,
        isRecurring: field === "isRecurring" ? (value as boolean) : false,
        durationMinutes: field === "durationMinutes" ? (value as number) : null,
        durationAmount: field === "durationAmount" ? (value as number) : null,
        durationUnit: field === "durationUnit" ? (value as string) : null,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-07-15T09:00:00Z"
      });

      await expect(service.editTask("task-1", {
        expectedOccurrenceKey: "occurrence-1",
        rootPatch: null,
        childMutations: [{ type: "add", clientMutationId: "blocked", title: "Blocked", description: null }]
      })).rejects.toMatchObject({
        code: "unsupported_hierarchy",
        statusCode: 422
      });

      const invalidRevision = `r:${repo.getById("invalid-child")?.mirrorRevision}`;
      await expect(service.checkChecklistItem("task-1", "invalid-child", {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: invalidRevision
      })).rejects.toMatchObject({
        code: "unsupported_hierarchy",
        statusCode: 422
      });
    });
  });
});
