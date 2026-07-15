import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import {
  TaskActionCoordinator,
  type CoordinatorRoot,
  type RootTaskStore
} from "../../src/services/taskActionCoordinator.js";

describe("TaskActionCoordinator", () => {
  it("reserves a first action, synchronizes around its command, and stores a compact response", async () => {
    const { coordinator, db, sync } = createCoordinator();
    const send = vi.fn(async () => undefined);

    const result = await coordinator.execute(action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    }, isEffectPresent: () => true }));

    expect(result).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(sync).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenCalledOnce();
    expect(journal(db, "action-1")).toMatchObject({
      status: "completed",
      response: { snoozedUntil: "2026-07-15T09:05:00Z" }
    });
  });

  it("persists only an explicitly compact action response", async () => {
    const { coordinator, db } = createCoordinator();

    const result = await coordinator.execute(action({
      execute: async () => ({ snoozedUntil: "2026-07-15T09:05:00Z", description: "private task text" }),
      compactResponse: (response: { snoozedUntil: string; description: string }) => ({
        snoozedUntil: response.snoozedUntil
      })
    }));

    expect(result).toEqual({
      kind: "completed",
      response: { snoozedUntil: "2026-07-15T09:05:00Z", description: "private task text" }
    });
    expect(journal(db, "action-1")).toEqual({
      status: "completed",
      response: { snoozedUntil: "2026-07-15T09:05:00Z" }
    });
  });

  it("leaves a sent action pending after a no-op confirmation sync when no verifier is supplied", async () => {
    const { coordinator, db } = createCoordinator();
    const send = vi.fn(async () => undefined);

    const result = await coordinator.execute(action({
      execute: async (context) => {
        await context.executeCommand("postpone", send);
        return { snoozedUntil: "2026-07-15T09:05:00Z" };
      }
    }));

    expect(result).toEqual({ kind: "pending", actionId: "action-1" });
    expect(send).toHaveBeenCalledOnce();
    expect(journal(db, "action-1")).toMatchObject({ status: "reconciliation_pending" });
  });

  it("keeps a no-op pending action pending on retry until a verifier proves its state", async () => {
    const { coordinator } = createCoordinator();
    const send = vi.fn(async () => undefined);
    const request = action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    } });

    await expect(coordinator.execute(request)).resolves.toEqual({ kind: "pending", actionId: "action-1" });
    const retryExecute = vi.fn();
    await expect(coordinator.execute({ ...request, execute: retryExecute })).resolves.toEqual({
      kind: "pending",
      actionId: "action-1"
    });
    expect(retryExecute).not.toHaveBeenCalled();
    expect(send).toHaveBeenCalledOnce();
  });

  it("reasserts root reconciliation pending when an unresolved journal action is retried", async () => {
    const db = database();
    const roots = new MemoryRoots();
    const coordinator = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });
    const send = vi.fn(async () => undefined);
    const request = action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    } });

    await expect(coordinator.execute(request)).resolves.toEqual({ kind: "pending", actionId: "action-1" });
    roots.root.reconciliationStatus = "confirmed";

    await expect(coordinator.execute({ ...request, execute: vi.fn() })).resolves.toEqual({
      kind: "pending",
      actionId: "action-1"
    });
    expect(roots.root.reconciliationStatus).toBe("pending");
    expect(send).toHaveBeenCalledOnce();
  });

  it("replays the same completed action without synchronizing or invoking its command", async () => {
    const { coordinator } = createCoordinator();
    const send = vi.fn(async () => undefined);
    const first = action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    }, isEffectPresent: () => true });

    await coordinator.execute(first);
    const replay = await coordinator.execute({ ...first, execute: vi.fn() });

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(send).toHaveBeenCalledOnce();
  });

  it("rejects an action ID reused with a different parsed domain payload", async () => {
    const { coordinator } = createCoordinator();
    await coordinator.execute(action({ payload: { minutes: 5 } }));

    await expect(coordinator.execute(action({ payload: { minutes: 10 } }))).rejects.toMatchObject({
      code: "action_id_reused",
      statusCode: 409
    });
  });

  it("replays a completed action after process-like coordinator reconstruction", async () => {
    const db = database();
    const roots = new MemoryRoots();
    const sync = vi.fn(async () => undefined);
    const first = new TaskActionCoordinator({ db, roots, sync: { syncOnce: sync } });
    await first.execute(action());
    const reconstructed = new TaskActionCoordinator({ db, roots, sync: { syncOnce: sync } });

    const replay = await reconstructed.execute(action({ execute: vi.fn() }));

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(sync).toHaveBeenCalledTimes(2);
  });

  it("restores a confirmed command's typed result after a crash without resending", async () => {
    const db = database();
    const roots = new MemoryRoots();
    const first = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });
    const send = vi.fn(async () => ({ todoistTaskId: "todoist-add-1" }));
    const request = action({
      actionType: "create_child",
      payload: { title: "First child" },
      isEffectPresent: () => false,
      execute: async (context) => {
        await context.executeCommand("child:add", send);
        throw new Error("simulated crash");
      }
    });

    await expect(first.execute(request)).rejects.toThrow("simulated crash");
    const recovered = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });
    let recoveredTodoistId: string | undefined;
    const replay = await recovered.execute({
      ...request,
      execute: async (context) => {
        const result = await context.executeCommand<{ todoistTaskId: string }>("child:add", send);
        recoveredTodoistId = result.todoistTaskId;
        return { snoozedUntil: "2026-07-15T09:05:00Z" };
      }
    });

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(recoveredTodoistId).toBe("todoist-add-1");
    expect(send).toHaveBeenCalledOnce();
  });

  it("recovers a partially completed project move by issuing only the unfinished field update", async () => {
    const { coordinator, db } = createCoordinator();
    const move = vi.fn(async () => undefined);
    let effectPresent = false;
    const update = vi.fn(async () => { effectPresent = true; });
    const request = action({
      actionType: "edit_task",
      payload: { projectId: "project-new", title: "Renamed" },
      isEffectPresent: () => effectPresent,
      execute: async (context) => {
        await context.executeCommand("item_move", move);
        throw new Error("interrupted after move");
      }
    });

    await expect(coordinator.execute(request)).rejects.toThrow("interrupted after move");
    const replay = await coordinator.execute({
      ...request,
      execute: async (context) => {
        await context.executeCommand("item_move", move);
        await context.executeCommand("item_update", update);
        return { snoozedUntil: "2026-07-15T09:05:00Z" };
      }
    });

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(move).toHaveBeenCalledOnce();
    expect(update).toHaveBeenCalledOnce();
    expect(command(db, "action-1", "item_move")).toMatchObject({ status: "confirmed" });
    expect(command(db, "action-1", "item_update")).toMatchObject({ status: "confirmed" });
  });

  it("reuses a persisted command UUID after an ambiguous Todoist timeout only once sync shows the effect absent", async () => {
    const { coordinator, db } = createCoordinator();
    const sentUuids: string[] = [];
    let effectPresent = false;
    const timeout = action({ execute: async (context) => {
      await context.executeCommand("postpone", async (commandUuid) => {
        sentUuids.push(commandUuid);
        throw new Error("timeout");
      });
      return { snoozedUntil: "never" };
    }, isEffectPresent: () => effectPresent });

    await expect(coordinator.execute(timeout)).rejects.toThrow("timeout");
    const retry = await coordinator.execute(action({ execute: async (context) => {
      await context.executeCommand("postpone", async (commandUuid) => {
        sentUuids.push(commandUuid);
        effectPresent = true;
      });
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    }, isEffectPresent: () => effectPresent }));

    expect(retry).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(sentUuids).toHaveLength(2);
    expect(sentUuids[1]).toBe(sentUuids[0]);
    expect(command(db, "action-1", "postpone")).toMatchObject({ status: "confirmed" });
  });

  it("does not resend a timed-out command UUID when retry synchronization cannot prove absence", async () => {
    const { coordinator, db } = createCoordinator();
    const sentUuids: string[] = [];
    const timeout = action({ execute: async (context) => {
      await context.executeCommand("postpone", async (commandUuid) => {
        sentUuids.push(commandUuid);
        throw new Error("timeout");
      });
      return { snoozedUntil: "never" };
    } });

    await expect(coordinator.execute(timeout)).rejects.toThrow("timeout");
    const retry = await coordinator.execute(action({ execute: async (context) => {
      await context.executeCommand("postpone", async (commandUuid) => { sentUuids.push(commandUuid); });
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    } }));

    expect(retry).toEqual({ kind: "pending", actionId: "action-1" });
    expect(sentUuids).toHaveLength(1);
    expect(command(db, "action-1", "postpone")).toMatchObject({ status: "sent" });
  });

  it("completes a timed-out action without resend when sync proves its effect present", async () => {
    const { coordinator, db } = createCoordinator();
    const sentUuids: string[] = [];
    let effectPresent = false;
    const request = action({
      execute: async (context) => {
        await context.executeCommand("postpone", async (commandUuid) => {
          sentUuids.push(commandUuid);
          throw new Error("timeout");
        });
        return { snoozedUntil: "never" };
      },
      isEffectPresent: () => effectPresent,
      responseAfterReconciliation: () => ({ snoozedUntil: "2026-07-15T09:05:00Z" })
    });

    await expect(coordinator.execute(request)).rejects.toThrow("timeout");
    effectPresent = true;
    const retryExecute = vi.fn();
    const replay = await coordinator.execute({ ...request, execute: retryExecute });

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(retryExecute).not.toHaveBeenCalled();
    expect(sentUuids).toHaveLength(1);
    expect(journal(db, "action-1")).toMatchObject({
      status: "completed",
      response: { snoozedUntil: "2026-07-15T09:05:00Z" }
    });
  });

  it("requires a reconciliation response provider before dispatching an external command", async () => {
    const { coordinator } = createCoordinator();
    const send = vi.fn(async () => undefined);
    const request = action({
      execute: async (context) => {
        await context.executeCommand("postpone", send);
        return { snoozedUntil: "2026-07-15T09:05:00Z" };
      }
    });
    delete (request as { responseAfterReconciliation?: unknown }).responseAfterReconciliation;

    await expect(coordinator.execute(request)).rejects.toThrow("reconciliation_response_provider_required");
    expect(send).not.toHaveBeenCalled();
  });

  it("marks the journal and root pending when Todoist succeeds but confirmation sync fails", async () => {
    const roots = new MemoryRoots();
    const sync = vi.fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error("sync down"));
    const coordinator = new TaskActionCoordinator({ db: database(), roots, sync: { syncOnce: sync } });
    const send = vi.fn(async () => undefined);

    const result = await coordinator.execute(action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    } }));

    expect(result).toEqual({ kind: "pending", actionId: "action-1" });
    expect(roots.getById("root-1")?.reconciliationStatus).toBe("pending");
    expect(send).toHaveBeenCalledOnce();
  });

  it("confirms a pending action after retry synchronization proves its effect without resending", async () => {
    const roots = new MemoryRoots();
    const db = database();
    const firstSync = vi.fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error("sync down"));
    const first = new TaskActionCoordinator({ db, roots, sync: { syncOnce: firstSync } });
    const send = vi.fn(async () => undefined);
    const pendingAction = action({
      execute: async (context) => {
        await context.executeCommand("postpone", send);
        return { snoozedUntil: "2026-07-15T09:05:00Z" };
      },
      isEffectPresent: (root) => root.mirrorRevision === 2
    });
    await first.execute(pendingAction);
    roots.root.mirrorRevision = 2;
    const retrySync = vi.fn(async () => undefined);
    const reconstructed = new TaskActionCoordinator({ db, roots, sync: { syncOnce: retrySync } });

    const replay = await reconstructed.execute({ ...pendingAction, execute: vi.fn() });

    expect(replay).toEqual({ kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" } });
    expect(send).toHaveBeenCalledOnce();
    expect(roots.getById("root-1")?.reconciliationStatus).toBe("confirmed");
  });

  it("keeps a new action retryable while another action is reconciling", async () => {
    const roots = new MemoryRoots();
    roots.root.reconciliationStatus = "pending";
    const db = database();
    const coordinator = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });

    await expect(coordinator.execute(action())).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(journal(db, "action-1")).toMatchObject({ status: "processing" });

    roots.root.reconciliationStatus = "confirmed";
    await expect(coordinator.execute(action())).resolves.toEqual({
      kind: "completed",
      response: { snoozedUntil: "2026-07-15T09:05:00Z" }
    });
  });

  it("returns a completed response replay without incrementing counters or reissuing Todoist", async () => {
    const { coordinator } = createCoordinator();
    let counter = 0;
    const send = vi.fn(async () => undefined);
    const request = action({ execute: async (context) => {
      await context.executeCommand("postpone", send);
      counter += 1;
      return { snoozedUntil: "2026-07-15T09:05:00Z" };
    }, isEffectPresent: () => true });

    await coordinator.execute(request);
    await coordinator.execute(request);

    expect(counter).toBe(1);
    expect(send).toHaveBeenCalledOnce();
  });

  it("accepts distinct phone action IDs for two serialized postponements", async () => {
    const { coordinator } = createCoordinator();
    const results: number[] = [];
    const postpone = (actionId: string, deviceId: string) => coordinator.execute(action({
      actionId,
      deviceId,
      execute: async () => {
        results.push(results.length + 1);
        return { snoozedUntil: `2026-07-15T09:0${results.length}:00Z` };
      }
    }));

    await Promise.all([postpone("phone-a", "device-a"), postpone("phone-b", "device-b")]);

    expect(results).toEqual([1, 2]);
  });

  it("waits for canonical-root work before recovering a reassociated create", async () => {
    const db = database();
    const canonicalRoot: CoordinatorRoot = {
      id: "canonical-root", mirrorRevision: 1, occurrenceKey: null, reconciliationStatus: "confirmed"
    };
    const roots: RootTaskStore = {
      getById: (id) => id === canonicalRoot.id ? canonicalRoot : null,
      markOccurrenceReconciliationPending: () => undefined,
      confirmOccurrenceReconciliation: () => undefined
    };
    let resolvedRootId: string | null = null;
    let releaseCanonical: (() => void) | undefined;
    const canonicalGate = new Promise<void>((resolve) => { releaseCanonical = resolve; });
    let canonicalStarted: (() => void) | undefined;
    const canonicalStartedGate = new Promise<void>((resolve) => { canonicalStarted = resolve; });
    const order: string[] = [];
    let syncCalls = 0;
    let canonicalAction: Promise<unknown> | undefined;
    const coordinator = new TaskActionCoordinator({
      db,
      roots,
      sync: {
        syncOnce: async () => {
          syncCalls += 1;
          if (syncCalls === 3) {
            resolvedRootId = canonicalRoot.id;
            canonicalAction = coordinator.execute(action({
              actionId: "canonical-edit",
              rootTaskId: canonicalRoot.id,
              expectedOccurrenceKey: null,
              actionType: "edit_task",
              payload: { title: "Canonical edit" },
              execute: async () => {
                order.push("canonical:execute");
                return { snoozedUntil: "2026-07-15T09:10:00Z" };
              }
            }));
            await canonicalStartedGate;
            return;
          }
          if (syncCalls === 4) {
            order.push("canonical:locked");
            canonicalStarted?.();
            await canonicalGate;
            order.push("canonical:released");
          }
        }
      }
    });
    const { expectedTaskRevision: _ignored, ...create } = action({
      actionId: "create-root",
      rootTaskId: "provisional-root",
      expectedOccurrenceKey: null,
      actionType: "create_task",
      payload: { title: "Root" },
      allowRootAbsent: true,
      resolveRootTaskId: () => resolvedRootId,
      isEffectPresent: (root) => root.id === canonicalRoot.id,
      responseAfterReconciliation: (root) => ({ snoozedUntil: root.id }),
      execute: async (context) => {
        await context.executeCommand("root:add", async () => undefined);
        return { snoozedUntil: "provisional-root" };
      }
    });

    await expect(coordinator.execute(create)).resolves.toEqual({ kind: "pending", actionId: "create-root" });
    const recovery = coordinator.execute(create);
    await vi.waitFor(() => expect(order).toContain("canonical:locked"));
    let recoverySettled = false;
    void recovery.finally(() => { recoverySettled = true; });
    await Promise.resolve();
    expect(recoverySettled).toBe(false);

    releaseCanonical?.();
    await canonicalAction;
    await expect(recovery).resolves.toEqual({ kind: "completed", response: { snoozedUntil: "canonical-root" } });
    expect(order).toEqual(["canonical:locked", "canonical:released", "canonical:execute"]);
    expect(journal(db, "create-root")).toMatchObject({ status: "completed" });
  });

  it("rolls back a crashed extend finalizer before replaying journal completion", async () => {
    const db = database();
    db.exec("create table finalizer_state (extension_count integer not null, schedule_version integer not null)");
    db.prepare("insert into finalizer_state (extension_count, schedule_version) values (0, 1)").run();
    const roots = new MemoryRoots();
    let crashAfterFinalize = true;
    const request = action({
      actionId: "extend-crash",
      actionType: "extend",
      payload: { durationMinutes: 60 },
      isEffectPresent: () => true,
      finalize: () => {
        db.prepare("update finalizer_state set extension_count = extension_count + 1, schedule_version = schedule_version + 1").run();
        if (crashAfterFinalize) {
          crashAfterFinalize = false;
          throw new Error("simulated crash after extend finalizer");
        }
      }
    });
    const first = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });

    await expect(first.execute(request)).rejects.toThrow("simulated crash after extend finalizer");
    expect(db.prepare("select extension_count as extensionCount, schedule_version as scheduleVersion from finalizer_state").get())
      .toEqual({ extensionCount: 0, scheduleVersion: 1 });

    const recovered = new TaskActionCoordinator({ db, roots, sync: { syncOnce: async () => undefined } });
    await expect(recovered.execute(request)).resolves.toEqual({
      kind: "completed", response: { snoozedUntil: "2026-07-15T09:05:00Z" }
    });
    expect(db.prepare("select extension_count as extensionCount, schedule_version as scheduleVersion from finalizer_state").get())
      .toEqual({ extensionCount: 1, scheduleVersion: 2 });
    expect(journal(db, "extend-crash")).toMatchObject({ status: "completed" });
  });
});

class MemoryRoots implements RootTaskStore {
  readonly root: CoordinatorRoot = {
    id: "root-1",
    mirrorRevision: 1,
    occurrenceKey: "occurrence-1",
    reconciliationStatus: "confirmed"
  };

  getById(id: string): CoordinatorRoot | null {
    return id === this.root.id ? this.root : null;
  }

  markOccurrenceReconciliationPending(id: string): void {
    if (id === this.root.id) this.root.reconciliationStatus = "pending";
  }

  confirmOccurrenceReconciliation(id: string): void {
    if (id === this.root.id) this.root.reconciliationStatus = "confirmed";
  }
}

function createCoordinator() {
  const db = database();
  const roots = new MemoryRoots();
  const sync = vi.fn(async () => undefined);
  return { db, coordinator: new TaskActionCoordinator({ db, roots, sync: { syncOnce: sync } }), sync };
}

function action(overrides: Partial<Parameters<TaskActionCoordinator["execute"]>[0]> = {}) {
  return {
    actionId: "action-1",
    deviceId: "device-1",
    rootTaskId: "root-1",
    expectedOccurrenceKey: "occurrence-1",
    expectedTaskRevision: "r:1",
    actionType: "postpone_start",
    payload: { minutes: 5 },
    compactResponse: (response: unknown) => response,
    responseAfterReconciliation: () => ({ snoozedUntil: "2026-07-15T09:05:00Z" }),
    execute: async () => ({ snoozedUntil: "2026-07-15T09:05:00Z" }),
    ...overrides
  };
}

function database(): Database.Database {
  const db = new Database(":memory:");
  migrate(db);
  return db;
}

function journal(db: Database.Database, actionId: string) {
  const row = db.prepare("select status, response_json as responseJson from task_action_journal where action_id = ?").get(actionId) as {
    status: string;
    responseJson: string | null;
  };
  return { status: row.status, response: row.responseJson === null ? null : JSON.parse(row.responseJson) as unknown };
}

function command(db: Database.Database, actionId: string, stepKey: string) {
  return db.prepare("select status, command_uuid as commandUuid from task_action_commands where action_id = ? and step_key = ?")
    .get(actionId, stepKey);
}
