import type Database from "better-sqlite3";
import "dotenv/config";
import Fastify from "fastify";
import { openDatabase } from "./db/connection.js";
import { registerRoutes } from "./http/routes.js";
import { TaskRepository } from "./repositories/taskRepository.js";
import { TodoistPoller } from "./services/todoistPoller.js";
import { TodoistSyncService } from "./services/todoistSyncService.js";
import { HttpTodoistAdapter } from "./todoist/httpTodoistAdapter.js";
import type { TodoistAdapter } from "./todoist/todoistAdapter.js";

export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
  todoist: TodoistAdapter;
}

export function buildServer(options: BuildServerOptions) {
  const app = Fastify({ logger: false });
  registerRoutes(app, options);
  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const dbPath = process.env.DATABASE_PATH ?? "data/buzer.sqlite";
  const currentUserId = process.env.TODOIST_USER_ID ?? process.env.TODOIST_CURRENT_USER_ID;
  const androidApiToken = process.env.ANDROID_API_TOKEN;
  const todoistApiToken = process.env.TODOIST_API_TOKEN;
  const port = Number(process.env.PORT ?? 3000);

  if (currentUserId === undefined || androidApiToken === undefined || todoistApiToken === undefined) {
    throw new Error("TODOIST_USER_ID, ANDROID_API_TOKEN, and TODOIST_API_TOKEN are required");
  }

  const db = openDatabase(dbPath);
  const todoist = new HttpTodoistAdapter(todoistApiToken);
  const app = buildServer({
    db,
    currentUserId,
    androidApiToken,
    todoist
  });
  const poller = new TodoistPoller(
    new TodoistSyncService(db, new TaskRepository(db), todoist),
    {
      runImmediately: true,
      onError: (error) => app.log.error({ err: error }, "Todoist sync failed")
    }
  );
  app.addHook("onClose", async () => {
    poller.stop();
  });

  poller.start();
  await app.listen({ host: "0.0.0.0", port });
}
