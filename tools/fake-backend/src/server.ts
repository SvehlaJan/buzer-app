import cors from "@fastify/cors";
import Fastify, { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { createFixtureState, FakeChecklistItem, FakeState, FakeTask, snapshot } from "./fixtures.js";

export type RecordedCall = { method: string; path: string; body: Record<string, unknown>; at: string };
export type FakeBackendOptions = { token?: string; failActions?: boolean; delayMs?: number };
type Replay = { fingerprint: string; statusCode: number; body: unknown; headers?: Record<string, string> };
type Controls = { pending: boolean; stale: boolean; activityLogFull: boolean; rateLimit: boolean };
type ActionBody = { actionId?: unknown; deviceId?: unknown; expectedOccurrenceKey?: unknown; expectedTaskRevision?: unknown; expectedChildRevision?: unknown };

export async function buildServer(options: FakeBackendOptions = {}): Promise<FastifyInstance> {
  const token = options.token ?? "test-token";
  let failActions = options.failActions ?? false;
  let failSnapshot = false;
  let failProjects = false;
  let state = createFixtureState();
  const controls: Controls = { pending: false, stale: false, activityLogFull: false, rateLimit: false };
  const replays = new Map<string, Replay>();
  const calls: RecordedCall[] = [];
  const app = Fastify({ logger: false });

  await app.register(cors, { origin: true });
  app.addHook("preHandler", async (request, reply) => {
    if (request.url.startsWith("/__")) return;
    if (request.headers.authorization !== `Bearer ${token}`) return reply.code(401).send({ error: "unauthorized" });
  });

  const currentSnapshot = () => snapshot(state);
  const respond = (reply: FastifyReply, statusCode: number, body: unknown, headers?: Record<string, string>) => {
    for (const [name, value] of Object.entries(headers ?? {})) reply.header(name, value);
    return reply.code(statusCode).send(body);
  };
  async function maybeDelay() { if ((options.delayMs ?? 0) > 0) await new Promise((resolve) => setTimeout(resolve, options.delayMs)); }
  function bodyOf(request: FastifyRequest): Record<string, unknown> { return typeof request.body === "object" && request.body !== null ? request.body as Record<string, unknown> : {}; }
  function actionMetadata(body: ActionBody): Record<string, unknown> {
    const keys = ["actionId", "deviceId", "expectedOccurrenceKey", "expectedTaskRevision", "expectedChildRevision"] as const;
    return Object.fromEntries(keys.filter((key) => body[key] !== undefined).map((key) => [key, body[key]]));
  }
  function record(request: FastifyRequest, body: ActionBody) { calls.push({ method: request.method, path: request.url, body: actionMetadata(body), at: "2026-06-01T10:00:00.000Z" }); }
  function task(id: string): FakeTask | undefined { return state.tasks.find((candidate) => candidate.id === id && !candidate.isCompleted); }
  function error(code: string, statusCode: number) { return { statusCode, body: { code, snapshot: currentSnapshot() } }; }
  function staleFor(taskValue: FakeTask, body: ActionBody, child?: FakeChecklistItem) {
    if (controls.stale || body.expectedOccurrenceKey !== taskValue.occurrenceKey) return error("stale_occurrence", 409);
    if (child !== undefined && body.expectedChildRevision !== `r:${child.childRevision}`) return error("stale_child", 409);
    return null;
  }
  function replay(request: FastifyRequest, body: ActionBody): Replay | null {
    if (typeof body.actionId !== "string" || body.actionId.length === 0) return null;
    const fingerprint = JSON.stringify({ method: request.method, path: request.url.split("?")[0], body });
    const saved = replays.get(body.actionId);
    if (saved === undefined) return { fingerprint, statusCode: 0, body: null };
    if (saved.fingerprint !== fingerprint) return { fingerprint, statusCode: 409, body: { code: "action_id_reused", snapshot: currentSnapshot() } };
    return saved;
  }
  function saveReplay(body: ActionBody, result: Replay): void { if (typeof body.actionId === "string" && body.actionId.length > 0) replays.set(body.actionId, result); }
  async function mutation(request: FastifyRequest, reply: FastifyReply, execute: (body: Record<string, unknown>) => { statusCode: number; body: unknown; headers?: Record<string, string> }) {
    await maybeDelay();
    const body = bodyOf(request);
    record(request, body);
    const cached = replay(request, body);
    if (cached !== null && cached.statusCode !== 0) return respond(reply, cached.statusCode, cached.body, cached.headers);
    let result: Replay;
    if (failActions) result = { ...(error("forced_failure", 500)), fingerprint: cached?.fingerprint ?? "" };
    else if (controls.rateLimit) result = { ...(error("rate_limited", 429)), headers: { "Retry-After": "7" }, fingerprint: cached?.fingerprint ?? "" };
    else if (controls.activityLogFull) result = { ...(error("activity_log_full", 422)), fingerprint: cached?.fingerprint ?? "" };
    else result = { ...execute(body), fingerprint: cached?.fingerprint ?? "" };
    saveReplay(body, result);
    return respond(reply, result.statusCode, result.body, result.headers);
  }
  function completed(actionId: unknown, outcome: "completed" | "partial" = "completed", childOutcomes?: unknown[]) {
    return { statusCode: 200, body: { ...(typeof actionId === "string" ? { actionId } : {}), outcome, snapshot: currentSnapshot(), ...(childOutcomes === undefined ? {} : { childOutcomes }) } };
  }
  function pending(actionId: unknown) { return { statusCode: 202, body: { ...(typeof actionId === "string" ? { actionId } : {}), outcome: "reconciliation_pending", snapshot: currentSnapshot() } }; }

  app.get("/tasks/snapshot", async (_request, reply) => failSnapshot ? reply.code(500).send({ error: "forced_snapshot_failure" }) : currentSnapshot());
  app.get("/projects", async (_request, reply) => {
    await maybeDelay();
    return failProjects ? reply.code(500).send({ error: "forced_project_failure" }) : { projects: [
      { id: "project-test", name: "Test Shared", canAssignTasks: true, shared: true, isInboxProject: false },
      { id: "project-personal", name: "Test Personal", canAssignTasks: false, shared: false, isInboxProject: false }
    ] };
  });
  app.get("/__calls", async () => ({ calls }));
  app.post("/__reset", async () => { calls.splice(0); state = createFixtureState(); replays.clear(); Object.assign(controls, { pending: false, stale: false, activityLogFull: false, rateLimit: false }); failActions = options.failActions ?? false; failSnapshot = false; failProjects = false; return { ok: true }; });
  app.post<{ Body: { enabled?: boolean } }>("/__fail-actions", async (request) => ({ failActions: failActions = request.body?.enabled === true }));
  app.post<{ Body: { enabled?: boolean } }>("/__fail-snapshot", async (request) => ({ failSnapshot: failSnapshot = request.body?.enabled === true }));
  app.post<{ Body: { enabled?: boolean } }>("/__fail-projects", async (request) => ({ failProjects: failProjects = request.body?.enabled === true }));
  for (const [path, key] of [["/__pending-actions", "pending"], ["/__stale-actions", "stale"], ["/__activity-log-full", "activityLogFull"], ["/__rate-limit", "rateLimit"]] as const) {
    app.post<{ Body: { enabled?: boolean } }>(path, async (request) => ({ [key]: controls[key] = request.body?.enabled === true }));
  }

  app.post("/tasks", async (request, reply) => mutation(request, reply, (body) => {
    if (controls.stale) return error("stale_task", 409);
    if (controls.pending) return pending(body.actionId);
    const id = `created-task-${state.tasks.filter((item) => item.id.startsWith("created-task-")).length + 1}`;
    const drafts = Array.isArray(body.checklistDrafts) ? body.checklistDrafts : [];
    const items: FakeChecklistItem[] = [];
    const childOutcomes = drafts.map((draft, index) => {
      const item = draft as Record<string, unknown>;
      if (item.title === "[partial-failure]") return { clientMutationId: item.clientMutationId, outcome: "failed", code: "simulated_child_failure" };
      const created = { id: `${id}-child-${index + 1}`, title: String(item.title ?? "Checklist item"), description: typeof item.description === "string" ? item.description : null, checked: false, childRevision: 1, order: index + 1 };
      items.push(created);
      return { clientMutationId: item.clientMutationId, childId: created.id, outcome: "completed", created };
    });
    state.tasks.push({
      id, todoistTaskId: `todoist-${id}`, title: String(body.title ?? "Created task"), description: typeof body.description === "string" ? body.description : null,
      dueDatetime: typeof body.dueDatetime === "string" ? body.dueDatetime : null, durationMinutes: typeof body.durationMinutes === "number" ? body.durationMinutes : null,
      state: "planned", occurrenceNumber: 1, occurrenceKey: `occurrence-${id}-1`, taskRevision: 1,
      recurrence: body.recurrencePreset && body.recurrencePreset !== "never" ? { kind: "supported", preset: String(body.recurrencePreset), rule: `every ${String(body.recurrencePreset)}`, language: "en", timeZone: typeof body.timeZone === "string" ? body.timeZone : null } : { kind: "none", preset: "never", rule: null, language: null, timeZone: null },
      hierarchyStatus: "valid", hierarchyViolation: null, reconciliationStatus: "none", checklistItems: items, startedAt: null, startDismissCount: 0, deadlineExtensionCount: 0, isCompleted: false
    });
    state.scheduleVersion++;
    return { statusCode: 201, body: { actionId: body.actionId, outcome: childOutcomes.some((outcome) => outcome.outcome === "failed") ? "partial" : "completed", childOutcomes, snapshot: currentSnapshot() } };
  }));

  app.post("/tasks/:id/start", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    root.state = "started"; root.startedAt = "2026-06-01T10:00:00.000Z"; state.scheduleVersion++; return completed(body.actionId);
  }));
  app.post("/tasks/:id/done", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    if (root.checklistItems.some((item) => !item.checked)) return error("incomplete_checklist", 409);
    if (root.recurrence.kind !== "none") { root.occurrenceNumber++; root.occurrenceKey = `occurrence-${root.id.replace(/^task-/, "")}-${root.occurrenceNumber}`; root.dueDatetime = nextDue(root.dueDatetime, root.recurrence.preset); root.checklistItems.forEach((item) => { item.checked = false; item.childRevision++; }); root.state = "planned"; root.startedAt = null; }
    else root.isCompleted = true;
    state.scheduleVersion++; return completed(body.actionId);
  }));
  app.post("/tasks/:id/postpone-start", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    root.startDismissCount++; state.scheduleVersion++; return completed(body.actionId);
  }));
  app.post("/tasks/:id/extend", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    if (root.recurrence.kind !== "none") return error("extend_not_available", 409);
    root.dueDatetime = new Date(new Date(root.dueDatetime ?? "2026-06-01T10:00:00.000Z").getTime() + 30 * 60_000).toISOString(); root.deadlineExtensionCount++; root.taskRevision++; state.scheduleVersion++; return completed(body.actionId);
  }));
  app.post("/tasks/:id/reschedule", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    root.dueDatetime = typeof body.dueDatetime === "string" ? body.dueDatetime : root.dueDatetime; root.durationMinutes = typeof body.durationMinutes === "number" ? body.durationMinutes : root.durationMinutes; root.taskRevision++; state.scheduleVersion++; return completed(body.actionId);
  }));
  app.post("/tasks/:id/update", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    const patch = body.rootPatch as Record<string, unknown> | null | undefined;
    if (patch !== null && patch !== undefined) { if (patch.expectedTaskRevision !== `r:${root.taskRevision}`) return error("stale_task", 409); for (const key of ["title", "description", "dueDatetime", "durationMinutes"] as const) if (patch[key] !== undefined) (root as unknown as Record<string, unknown>)[key] = patch[key]; root.taskRevision++; }
    const outcomes = applyChildMutations(root, Array.isArray(body.childMutations) ? body.childMutations : []);
    state.scheduleVersion++;
    return completed(body.actionId, outcomes.some((item) => item.outcome === "failed") ? "partial" : "completed", outcomes);
  }));
  app.post("/tasks/:id/checklist", async (request, reply) => mutation(request, reply, (body) => {
    const root = task(String((request.params as { id: string }).id)); if (!root) return error("not_found", 404);
    const stale = staleFor(root, body); if (stale) return stale; if (controls.pending) return pending(body.actionId);
    const item = addChild(root, body); state.scheduleVersion++; return completed(body.actionId, "completed", [{ childId: item.id, outcome: "completed" }]);
  }));
  app.post("/tasks/:id/checklist/:childId/update", async (request, reply) => mutateFocused("edit", request, reply));
  app.post("/tasks/:id/checklist/:childId/delete", async (request, reply) => mutateFocused("delete", request, reply));
  app.post("/tasks/:id/checklist/:childId/check", async (request, reply) => mutateFocused("check", request, reply));
  app.post("/tasks/:id/checklist/:childId/uncheck", async (request, reply) => mutateFocused("uncheck", request, reply));
  function mutateFocused(kind: "edit" | "delete" | "check" | "uncheck", request: FastifyRequest, reply: FastifyReply) {
    return mutation(request, reply, (body) => {
      const params = request.params as { id: string; childId: string }; const root = task(params.id); if (!root) return error("not_found", 404);
      const item = root.checklistItems.find((candidate) => candidate.id === params.childId); if (!item) return error("child_not_found", 404);
      const stale = staleFor(root, body, item); if (stale) return stale; if (controls.pending) return pending(body.actionId);
      if (kind === "edit") { item.title = typeof body.title === "string" ? body.title : item.title; item.description = typeof body.description === "string" ? body.description : item.description; item.childRevision++; }
      if (kind === "delete") root.checklistItems = root.checklistItems.filter((candidate) => candidate !== item);
      if (kind === "check") { item.checked = true; item.childRevision++; }
      if (kind === "uncheck") { item.checked = false; item.childRevision++; }
      state.scheduleVersion++; return completed(body.actionId, "completed", [{ childId: item.id, outcome: "completed" }]);
    });
  }
  app.post("/review/dismiss", async (request, reply) => mutation(request, reply, (body) => ({ statusCode: 200, body: { actionId: body.actionId, outcome: "completed", snoozedUntil: "2026-06-01T10:05:00.000Z", snapshot: currentSnapshot() } })));
  app.post("/device/check-in", async () => ({ ok: true }));
  return app;
}

function addChild(root: FakeTask, body: Record<string, unknown>): FakeChecklistItem {
  const item = { id: `${root.id}-child-${root.checklistItems.length + 1}`, title: String(body.title ?? "Checklist item"), description: typeof body.description === "string" ? body.description : null, checked: false, childRevision: 1, order: root.checklistItems.length + 1 };
  root.checklistItems.push(item); return item;
}

function applyChildMutations(root: FakeTask, mutations: unknown[]) {
  return mutations.map((raw) => {
    const mutation = raw as Record<string, unknown>; const type = mutation.type; const item = root.checklistItems.find((candidate) => candidate.id === mutation.childId);
    if (type === "add") { const child = addChild(root, mutation); return { clientMutationId: mutation.clientMutationId, childId: child.id, outcome: "completed" }; }
    if (!item || mutation.expectedChildRevision !== `r:${item.childRevision}`) return { clientMutationId: mutation.clientMutationId, childId: mutation.childId, outcome: "failed", code: "stale_child" };
    if (type === "edit") { item.title = typeof mutation.title === "string" ? mutation.title : item.title; item.description = typeof mutation.description === "string" ? mutation.description : item.description; item.childRevision++; }
    if (type === "delete") root.checklistItems = root.checklistItems.filter((candidate) => candidate !== item);
    if (type === "check") { item.checked = true; item.childRevision++; }
    if (type === "uncheck") { item.checked = false; item.childRevision++; }
    return { clientMutationId: mutation.clientMutationId, childId: item.id, outcome: "completed" };
  });
}

function nextDue(dueDatetime: string | null, preset: string | null): string | null {
  if (dueDatetime === null) return null;
  const days = preset === "weekly" ? 7 : preset === "monthly" ? 30 : preset === "yearly" ? 365 : 1;
  return new Date(new Date(dueDatetime).getTime() + days * 86_400_000).toISOString();
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? "18433");
  const app = await buildServer({ failActions: process.env.FAIL_ACTIONS === "1", delayMs: Number(process.env.DELAY_MS ?? "0") });
  await app.listen({ host: "0.0.0.0", port });
  console.log(`fake backend listening on ${port}`);
}
