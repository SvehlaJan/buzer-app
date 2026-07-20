import Database from "better-sqlite3";
import { DryRunSender } from "../src/accountability/dryRunSender.js";
import { migrate } from "../src/db/migrations.js";
import { ProjectRepository } from "../src/repositories/projectRepository.js";
import { TaskRepository } from "../src/repositories/taskRepository.js";
import { buildServer } from "../src/server.js";
import { EscalationService } from "../src/services/escalationService.js";
import { TaskSnapshotService } from "../src/services/taskSnapshotService.js";
import { TodoistSyncService } from "../src/services/todoistSyncService.js";
import { e2eFixtureSeed } from "./fakes/e2eFixtures.js";
import { E2ETodoistAdapter } from "./fakes/e2eTodoistAdapter.js";

const CURRENT_USER_ID = "user-me";
const ANDROID_API_TOKEN = "test-token";

export interface RecordedCall {
  method: string;
  path: string;
  body: Record<string, unknown>;
  at: string;
}

export interface E2EServer {
  app: ReturnType<typeof buildServer>;
  adapter: E2ETodoistAdapter;
  db: Database.Database;
  reset: () => Promise<void>;
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
  const controls = { failActions: false, failSnapshot: false, failProjects: false };

  const seed = async (): Promise<void> => {
    const fixture = e2eFixtureSeed(new Date());
    adapter.world.load({
      tasks: fixture.tasks.map((task) => task.mirror),
      projects: fixture.projects
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

  const reset = async (): Promise<void> => {
    db.transaction(() => {
      for (const table of [
        "task_action_commands",
        "task_action_journal",
        "whatsapp_escalations",
        "devices",
        "commitment_states",
        "todoist_tasks",
        "todoist_projects",
        "todoist_sync_state"
      ]) {
        db.prepare(`delete from ${table}`).run();
      }
    })();
    calls.splice(0);
    controls.failActions = false;
    controls.failSnapshot = false;
    controls.failProjects = false;
    adapter.setScenario("none");
    await seed();
  };

  registerDebugSurface(app, { adapter, calls, controls, snapshots, reset });
  await seed();
  return { app, adapter, db, reset };
}

interface DebugSurfaceOptions {
  adapter: E2ETodoistAdapter;
  calls: RecordedCall[];
  controls: { failActions: boolean; failSnapshot: boolean; failProjects: boolean };
  snapshots: TaskSnapshotService;
  reset: () => Promise<void>;
}

function registerDebugSurface(_app: ReturnType<typeof buildServer>, _options: DebugSurfaceOptions): void {
  // Task 5 registers the old-fake-parity debug endpoints outside production code.
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 18433);
  const { app } = await buildE2EServer();
  await app.listen({ host: "0.0.0.0", port });
  console.log(`e2e real backend listening on ${port}`);
}
