import cors from "@fastify/cors";
import Fastify, { FastifyInstance } from "fastify";
import { fixedSnapshot } from "./fixtures.js";

export type RecordedCall = {
  method: string;
  path: string;
  body: unknown;
  at: string;
};

export type FakeBackendOptions = {
  token?: string;
  failActions?: boolean;
  delayMs?: number;
};

export async function buildServer(options: FakeBackendOptions = {}): Promise<FastifyInstance> {
  const token = options.token ?? "test-token";
  let failActions = options.failActions ?? false;
  let failSnapshot = false;
  const app = Fastify({ logger: false });
  const calls: RecordedCall[] = [];

  await app.register(cors, { origin: true });

  app.addHook("preHandler", async (request, reply) => {
    if (request.url.startsWith("/__")) return;
    if (request.headers.authorization !== `Bearer ${token}`) {
      return reply.code(401).send({ error: "unauthorized" });
    }
  });

  async function maybeDelay() {
    if ((options.delayMs ?? 0) > 0) {
      await new Promise((resolve) => setTimeout(resolve, options.delayMs));
    }
  }

  function record(method: string, path: string, body: unknown) {
    calls.push({ method, path, body, at: new Date().toISOString() });
  }

  app.get("/tasks/snapshot", async (_request, reply) => {
    if (failSnapshot) {
      return reply.code(500).send({ error: "forced_snapshot_failure" });
    }
    return fixedSnapshot();
  });

  app.get("/__calls", async () => ({ calls }));

  app.post("/__reset", async () => {
    calls.splice(0, calls.length);
    return { ok: true };
  });

  app.post<{ Body: { enabled?: boolean } }>("/__fail-actions", async (request) => {
    failActions = request.body?.enabled === true;
    return { failActions };
  });

  app.post<{ Body: { enabled?: boolean } }>("/__fail-snapshot", async (request) => {
    failSnapshot = request.body?.enabled === true;
    return { failSnapshot };
  });

  app.post("/tasks", async (request, reply) => {
    await maybeDelay();
    record("POST", request.url, request.body ?? null);
    if (failActions) {
      return reply.code(500).send({ error: "forced_failure" });
    }
    return reply.code(201).send({
      id: "created-task",
      todoistTaskId: "todoist-created-task"
    });
  });

  for (const action of ["start", "done", "postpone-start", "extend", "reschedule"]) {
    app.post("/tasks/:id/" + action, async (request, reply) => {
      await maybeDelay();
      record("POST", request.url, request.body ?? null);
      if (failActions) {
        return reply.code(500).send({ error: "forced_failure" });
      }
      return reply.code(204).send();
    });
  }

  app.post("/review/dismiss", async (request, reply) => {
    await maybeDelay();
    record("POST", request.url, request.body ?? null);
    if (failActions) {
      return reply.code(500).send({ error: "forced_failure" });
    }
    return reply.send({
      snoozedUntil: new Date(Date.now() + 5 * 60_000).toISOString()
    });
  });

  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? "18433");
  const failActions = process.env.FAIL_ACTIONS === "1";
  const delayMs = Number(process.env.DELAY_MS ?? "0");
  const app = await buildServer({ failActions, delayMs });
  await app.listen({ host: "0.0.0.0", port });
  console.log(`fake backend listening on ${port}`);
}
