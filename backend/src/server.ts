import type Database from "better-sqlite3";
import "dotenv/config";
import { DryRunSender } from "./accountability/dryRunSender.js";
import { WhatsAppWebSender } from "./accountability/whatsappWebSender.js";
import Fastify from "fastify";
import { openDatabase } from "./db/connection.js";
import { registerRoutes } from "./http/routes.js";
import { ProjectRepository } from "./repositories/projectRepository.js";
import { TaskRepository } from "./repositories/taskRepository.js";
import { EscalationService } from "./services/escalationService.js";
import { TodoistPoller } from "./services/todoistPoller.js";
import { TodoistSyncService } from "./services/todoistSyncService.js";
import { HttpTodoistAdapter } from "./todoist/httpTodoistAdapter.js";
import type { TodoistAdapter } from "./todoist/todoistAdapter.js";

export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
  todoist: TodoistAdapter;
  escalationService?: EscalationService | null;
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
  const whatsappRecipientId = process.env.WHATSAPP_RECIPIENT_ID;
  const port = Number(process.env.PORT ?? 3000);

  if (currentUserId === undefined || androidApiToken === undefined || todoistApiToken === undefined || whatsappRecipientId === undefined) {
    throw new Error("TODOIST_USER_ID, ANDROID_API_TOKEN, TODOIST_API_TOKEN, and WHATSAPP_RECIPIENT_ID are required");
  }

  const db = openDatabase(dbPath);
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const todoist = new HttpTodoistAdapter(todoistApiToken);
  const whatsappDryRun = process.env.WHATSAPP_DRY_RUN !== "false";
  const whatsappSender = whatsappDryRun
    ? new DryRunSender()
    : new WhatsAppWebSender({
      sessionPath: process.env.WHATSAPP_SESSION_PATH ?? "whatsapp-sessions",
      clientId: process.env.WHATSAPP_CLIENT_ID ?? "buzer"
    });
  if (whatsappSender instanceof WhatsAppWebSender) {
    await whatsappSender.initialize();
  }
  const escalationService = new EscalationService(db, tasks, whatsappSender, whatsappRecipientId);
  const app = buildServer({
    db,
    currentUserId,
    androidApiToken,
    todoist,
    escalationService
  });
  const poller = new TodoistPoller(
    new TodoistSyncService(db, tasks, projects, todoist),
    {
      runImmediately: true,
      onError: (error) => app.log.error({ err: error }, "Todoist sync failed")
    }
  );
  app.addHook("onClose", async () => {
    poller.stop();
    if (whatsappSender instanceof WhatsAppWebSender) {
      await whatsappSender.destroy();
    }
  });

  poller.start();
  await app.listen({ host: "0.0.0.0", port });
}
