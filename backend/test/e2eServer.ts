import Database from "better-sqlite3";
import { DryRunSender } from "../src/accountability/dryRunSender.js";
import { migrate } from "../src/db/migrations.js";
import { ProjectRepository } from "../src/repositories/projectRepository.js";
import { TaskRepository } from "../src/repositories/taskRepository.js";
import { buildServer } from "../src/server.js";
import { EscalationService } from "../src/services/escalationService.js";
import { TaskSnapshotService } from "../src/services/taskSnapshotService.js";
import { TodoistSyncService } from "../src/services/todoistSyncService.js";
import {
  e2eFixtureSeed,
  e2eMorningReviewScenarioSeed,
  type E2EMorningReviewScenario
} from "./fakes/e2eFixtures.js";
import { E2E_SCENARIOS, E2ETodoistAdapter, type E2EScenario } from "./fakes/e2eTodoistAdapter.js";

const CURRENT_USER_ID = "user-me";
const ANDROID_API_TOKEN = "test-token";

export interface RecordedCall {
  method: string;
  path: string;
  body: Record<string, unknown>;
  at: string;
  responseCompletedAt: string | null;
  responseStatusCode: number | null;
  responseAuthority: RecordedResponseAuthority | null;
}

export interface RecordedResponseAuthority {
  serverTime: string;
  snapshotGeneration: number;
}

interface RecordedFullCall extends RecordedCall {
  payload: Record<string, unknown>;
}

export interface E2EServer {
  app: ReturnType<typeof buildServer>;
  adapter: E2ETodoistAdapter;
  db: Database.Database;
  reset: (morningReviewScenario?: E2EMorningReviewScenario) => Promise<void>;
}

type SyncHoldState = "armed" | "waiting" | "released";

interface SyncHold {
  state: SyncHoldState;
  waiting: Promise<void>;
  release: () => void;
}

export async function buildE2EServer(): Promise<E2EServer> {
  const db = new Database(":memory:");
  migrate(db);

  const adapter = new E2ETodoistAdapter(CURRENT_USER_ID);
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const synchronizer = new TodoistSyncService(db, tasks, projects, adapter, CURRENT_USER_ID);
  const snapshots = new TaskSnapshotService(tasks, projects, CURRENT_USER_ID, true);
  const escalationService = new EscalationService(db, tasks, new DryRunSender(), "e2e-recipient");
  const app = buildServer({
    db,
    currentUserId: CURRENT_USER_ID,
    androidApiToken: ANDROID_API_TOKEN,
    todoist: adapter,
    escalationService,
    rateLimit: { max: 100_000, timeWindowMs: 60_000 }
  });

  const calls: RecordedCall[] = [];
  const fullCalls: RecordedFullCall[] = [];
  const controls = {
    failActions: false,
    failSnapshot: false,
    failProjects: false,
    failNextChildAdd: false,
    syncHold: null as SyncHold | null
  };

  const seed = async (morningReviewScenario?: E2EMorningReviewScenario): Promise<void> => {
    const fixture = morningReviewScenario === undefined
      ? e2eFixtureSeed(new Date())
      : e2eMorningReviewScenarioSeed(new Date(), morningReviewScenario);
    adapter.world.load({
      tasks: fixture.tasks.map((task) => task.mirror),
      projects: fixture.projects,
      collaborators: fixture.collaborators,
      collaboratorStates: fixture.collaboratorStates
    });

    for (const { id, mirror } of fixture.tasks) {
      tasks.upsertTodoistTask({ ...mirror, id, rawJson: JSON.stringify(mirror.rawJson) });
    }
    await synchronizer.syncOnce();

    const nowIso = new Date().toISOString();
    for (const nudge of fixture.nudges) {
      if (nudge.kind === "started") {
        tasks.updateCommitmentState(nudge.id, {
          state: "started",
          startedAt: nudge.startedAt ?? nowIso,
          incrementScheduleVersion: true,
          updatedAt: nowIso
        });
      } else {
        tasks.markOccurrenceReconciliationPending(nudge.id, nowIso);
      }
    }

    const pinOccurrence = db.prepare("update commitment_states set occurrence_key = ? where task_id = ?");
    for (const pin of fixture.occurrencePins) {
      pinOccurrence.run(pin.occurrenceKey, pin.id);
    }
  };

  const reset = async (morningReviewScenario?: E2EMorningReviewScenario): Promise<void> => {
    controls.syncHold?.release();
    controls.syncHold = null;
    db.transaction(() => {
      for (const table of [
        "task_action_commands",
        "task_action_journal",
        "whatsapp_escalations",
        "devices",
        "commitment_states",
        "todoist_tasks",
        "todoist_projects",
        "todoist_project_collaborators",
        "todoist_collaborators",
        "todoist_sync_state"
      ]) {
        db.prepare(`delete from ${table}`).run();
      }
    })();
    calls.splice(0);
    fullCalls.splice(0);
    controls.failActions = false;
    controls.failSnapshot = false;
    controls.failProjects = false;
    controls.failNextChildAdd = false;
    adapter.setScenario("none");
    adapter.setFailNextChildAdd(false);
    await seed(morningReviewScenario);
  };

  registerDebugSurface(app, { adapter, calls, fullCalls, controls, snapshots, reset });
  await seed();
  return { app, adapter, db, reset };
}

interface DebugSurfaceOptions {
  adapter: E2ETodoistAdapter;
  calls: RecordedCall[];
  fullCalls: RecordedFullCall[];
  controls: {
    failActions: boolean;
    failSnapshot: boolean;
    failProjects: boolean;
    failNextChildAdd: boolean;
    syncHold: SyncHold | null;
  };
  snapshots: TaskSnapshotService;
  reset: (morningReviewScenario?: E2EMorningReviewScenario) => Promise<void>;
}

const ACTION_METADATA_KEYS = [
  "actionId",
  "deviceId",
  "expectedOccurrenceKey",
  "expectedTaskRevision",
  "expectedChildRevision"
] as const;

function isMorningReviewScenario(value: unknown): value is E2EMorningReviewScenario {
  return value === "clean" || value === "invalid" || value === "overdue" || value === "today" ||
    value === "blockingMix" || value === "unassigned" || value === "priorityDeadline";
}

function registerDebugSurface(app: ReturnType<typeof buildServer>, options: DebugSurfaceOptions): void {
  const { adapter, calls, fullCalls, controls, snapshots, reset } = options;
  const correlatedCalls = new Map<string, RecordedCall>();

  app.addHook("preHandler", async (request, reply) => {
    const path = request.url.split("?")[0] ?? request.url;
    if (path.startsWith("/__")) return;

    if (request.method === "POST" && (path.startsWith("/tasks") || path.startsWith("/review"))) {
      const body = typeof request.body === "object" && request.body !== null
        ? request.body as Record<string, unknown>
        : {};
      const call: RecordedCall = {
        method: request.method,
        path,
        body: Object.fromEntries(
          ACTION_METADATA_KEYS
            .filter((key) => body[key] !== undefined)
            .map((key) => [key, body[key]])
        ),
        at: new Date().toISOString(),
        responseCompletedAt: null,
        responseStatusCode: null,
        responseAuthority: null
      };
      calls.push(call);
      fullCalls.push({ ...call, payload: structuredClone(body) });
      correlatedCalls.set(request.id, call);
      if (controls.failActions) {
        return reply.code(500).send({
          code: "forced_failure",
          snapshot: snapshots.getSnapshot(new Date().toISOString())
        });
      }
    }

    if (request.method === "POST" && path === "/tasks/sync" && controls.syncHold?.state === "armed") {
      const hold = controls.syncHold;
      hold.state = "waiting";
      await hold.waiting;
    }

    if (controls.failSnapshot && ((request.method === "GET" && path === "/tasks/snapshot") || (request.method === "POST" && path === "/tasks/sync"))) {
      return reply.code(500).send({ error: "forced_snapshot_failure" });
    }
    if (controls.failProjects && request.method === "GET" && path === "/projects") {
      return reply.code(500).send({ error: "forced_project_failure" });
    }
  });

  app.addHook("onSend", async (request, _reply, payload) => {
    const call = correlatedCalls.get(request.id);
    if (call !== undefined) {
      call.responseAuthority = responseAuthorityFromSerializedPayload(payload);
    }
    return payload;
  });

  app.addHook("onResponse", async (request, reply) => {
    const call = correlatedCalls.get(request.id);
    if (call === undefined) return;
    call.responseCompletedAt = new Date().toISOString();
    call.responseStatusCode = reply.statusCode;
    correlatedCalls.delete(request.id);
    const path = request.url.split("?")[0] ?? request.url;
    if (request.method === "POST" && path === "/tasks/sync" && controls.syncHold?.state === "released") {
      controls.syncHold = null;
    }
  });

  app.post("/__scenario", async (request, reply) => {
    const name = (request.body as { name?: unknown } | null)?.name;
    if (typeof name !== "string" || !E2E_SCENARIOS.includes(name as E2EScenario)) {
      return reply.code(400).send({ error: "unknown_scenario", known: E2E_SCENARIOS });
    }
    adapter.setScenario(name as E2EScenario);
    return { scenario: name };
  });
  app.post("/__reset", async (request, reply) => {
    const morningReviewScenario = (request.body as { morningReviewScenario?: unknown } | null)?.morningReviewScenario;
    if (morningReviewScenario !== undefined && !isMorningReviewScenario(morningReviewScenario)) {
      return reply.code(400).send({ error: "unknown_morning_review_scenario" });
    }
    await reset(morningReviewScenario);
    return { ok: true, morningReviewScenario: morningReviewScenario ?? null };
  });
  app.get("/__calls", async () => ({ calls }));
  app.get("/__calls/full", async () => ({ calls: fullCalls }));
  app.post("/__fail-actions", async (request) => ({
    failActions: controls.failActions = (request.body as { enabled?: unknown } | null)?.enabled === true
  }));
  app.post("/__fail-snapshot", async (request) => ({
    failSnapshot: controls.failSnapshot = (request.body as { enabled?: unknown } | null)?.enabled === true
  }));
  app.post("/__fail-projects", async (request) => ({
    failProjects: controls.failProjects = (request.body as { enabled?: unknown } | null)?.enabled === true
  }));
  app.post("/__fail-next-child-add", async (request) => {
    const enabled = (request.body as { enabled?: unknown } | null)?.enabled === true;
    controls.failNextChildAdd = enabled;
    adapter.setFailNextChildAdd(enabled);
    return { failNextChildAdd: enabled };
  });
  app.post("/__hold-next-sync", async (_request, reply) => {
    if (controls.syncHold !== null) {
      return reply.code(409).send({ state: controls.syncHold.state });
    }
    controls.syncHold = createSyncHold();
    return { state: controls.syncHold.state };
  });
  app.get("/__sync-hold", async () => ({ state: controls.syncHold?.state ?? "idle" }));
  app.post("/__release-sync", async () => {
    const hold = controls.syncHold;
    if (hold === null) return { state: "idle" };
    hold.release();
    return { state: hold.state };
  });
}

function createSyncHold(): SyncHold {
  let resolveWaiting: (() => void) | null = null;
  let timeout: NodeJS.Timeout | null = null;
  const hold: SyncHold = {
    state: "armed",
    waiting: new Promise<void>((resolve) => {
      resolveWaiting = resolve;
    }),
    release: () => {
      if (hold.state === "released") return;
      hold.state = "released";
      if (timeout !== null) clearTimeout(timeout);
      resolveWaiting?.();
    }
  };
  timeout = setTimeout(hold.release, 30_000);
  timeout.unref();
  return hold;
}

function responseAuthorityFromSerializedPayload(payload: unknown): RecordedResponseAuthority | null {
  const serialized = typeof payload === "string"
    ? payload
    : Buffer.isBuffer(payload)
      ? payload.toString("utf8")
      : null;
  if (serialized === null) return null;

  const parsed = (() => {
    try {
      return JSON.parse(serialized) as unknown;
    } catch {
      return null;
    }
  })();
  if (typeof parsed !== "object" || parsed === null) return null;

  const body = parsed as Record<string, unknown>;
  const nestedSnapshot = body.snapshot;
  const authority = typeof nestedSnapshot === "object" && nestedSnapshot !== null
    ? nestedSnapshot as Record<string, unknown>
    : body;
  return typeof authority.serverTime === "string" &&
    typeof authority.snapshotGeneration === "number"
    ? {
        serverTime: authority.serverTime,
        snapshotGeneration: authority.snapshotGeneration
      }
    : null;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 18433);
  const { app } = await buildE2EServer();
  await app.listen({ host: "0.0.0.0", port });
  console.log(`e2e real backend listening on ${port}`);
}
