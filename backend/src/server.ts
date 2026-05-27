import type Database from "better-sqlite3";
import Fastify from "fastify";
import { openDatabase } from "./db/connection.js";
import { registerRoutes } from "./http/routes.js";
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
  const currentUserId = process.env.TODOIST_CURRENT_USER_ID;
  const androidApiToken = process.env.ANDROID_API_TOKEN;
  const todoistApiToken = process.env.TODOIST_API_TOKEN;
  const port = Number(process.env.PORT ?? 3000);

  if (currentUserId === undefined || androidApiToken === undefined || todoistApiToken === undefined) {
    throw new Error("TODOIST_CURRENT_USER_ID, ANDROID_API_TOKEN, and TODOIST_API_TOKEN are required");
  }

  const app = buildServer({
    db: openDatabase(dbPath),
    currentUserId,
    androidApiToken,
    todoist: new HttpTodoistAdapter(todoistApiToken)
  });

  await app.listen({ host: "0.0.0.0", port });
}
