# Backend Internet Exposure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the accidental public exposure of the backend and replace it with an intentional one: HTTPS via Tailscale Funnel at `https://oc-vps.taila50381.ts.net/buzer`, with the backend rebound off the public interface and hardened for public reachability.

**Architecture:** All Fastify routes move under a `/buzer` prefix (Tailscale Funnel does **not** strip its `--set-path` mount from proxied requests — tailscale/tailscale#6571). Docker publishes only to `127.0.0.1` and the VPS's tailscale IP. Auth comparison becomes constant-time, unauthenticated `/health` responses are trimmed, and a proxy-aware per-IP rate limit is added (Fastify `trustProxy` scoped to loopback + a pinned compose subnet so `X-Forwarded-For` from `tailscaled` is honored but not spoofable).

**Tech Stack:** Fastify 5.8.5, `@fastify/rate-limit` v10, Vitest 4, better-sqlite3 (in-memory for tests), Docker Compose, Kotlin/Android (one manifest change only).

**Spec:** `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` — read it before starting. Also follow `AGENTS.md` (start-of-session routine, handoff file at the end).

**Boundaries for the executing agent:**
- Everything in Tasks 0–9 is local repo work: code, tests, config, docs. Commit per task.
- Do **NOT** deploy, do **NOT** SSH to the VPS, do **NOT** touch the Tailscale admin console, and do **NOT** edit `client/local.properties`. Those are operator steps, listed in the final "Operator Rollout Runbook" appendix for the human.
- Backend commands run from `backend/`. Test command: `npm test` (runs `vitest run`). Typecheck: `npm run typecheck`.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `backend/src/http/prefix.ts` | Create | Single source of truth for the `/buzer` API prefix (avoids an import cycle between `server.ts` and `routes.ts`) |
| `backend/src/http/auth.ts` | Modify | Constant-time bearer comparison; new `isBearerTokenValid` helper |
| `backend/src/http/routes.ts` | Modify | Prefix-aware `/health` auth exemption; trimmed/authenticated `/health` shapes |
| `backend/src/server.ts` | Modify | Prefixed route registration, `trustProxy`, rate-limit registration, rate-limited 404 handler, `rateLimit` test override in `BuildServerOptions` |
| `backend/test/http/auth.test.ts` | Create | Unit tests for token comparison |
| `backend/test/http/routes.test.ts` | Modify | All request paths gain the prefix; health-shape tests rewritten; 503 coverage added |
| `backend/test/http/rateLimit.test.ts` | Create | Per-IP limiting, proxy keying, spoof resistance, 404 limiting |
| `docker-compose.yml` | Modify | Hardcoded dual binds, `/buzer/health` healthcheck, pinned subnet |
| `scripts/deploy-vps.sh` | Modify | Health-wait URL gains `/buzer`; stale env exports removed |
| `client/androidApp/src/debug/AndroidManifest.xml` | Modify | Remove debug cleartext override |
| `docs/notes/vps-deployment.md` | Modify | Runbook reflects Funnel reality |
| `docs/notes/github-actions-tailscale-deploy.md` | Modify | Remove stale "Tailscale-only" framing |
| `docs/notes/api-contract.md` | Modify | New Base URL section |
| `docs/handoffs/2026-07-13-backend-internet-exposure-implementation.md` | Create | Session handoff per AGENTS.md |

---

### Task 0: Branch setup

The spec (`docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md`) and this plan may exist only as uncommitted working-tree changes. They must be committed **before** any worktree isolation, or they will be lost.

**Files:**
- Commit: `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md`
- Commit: `docs/superpowers/plans/2026-07-13-backend-internet-exposure.md`

- [ ] **Step 1: Create the feature branch off master, carrying working-tree changes**

```bash
git checkout master
git pull
git checkout -b feature/backend-internet-exposure
```

Uncommitted modifications (the spec update and this plan) survive the checkout. If `git checkout master` fails because of conflicting modified files, stash only unrelated files — never the spec or this plan.

- [ ] **Step 2: Commit the spec and plan**

```bash
git add docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md docs/superpowers/plans/2026-07-13-backend-internet-exposure.md
git commit -m "docs: revise internet exposure design and add implementation plan"
```

- [ ] **Step 3: Verify a clean baseline**

Run: `cd backend && npm install && npm test && npm run typecheck`
Expected: all existing tests PASS, typecheck clean. If the baseline is red, STOP and report — do not build on a broken base.

---

### Task 1: Constant-time bearer-token comparison

**Files:**
- Modify: `backend/src/http/auth.ts`
- Create: `backend/test/http/auth.test.ts`

- [ ] **Step 1: Write the failing test**

Create `backend/test/http/auth.test.ts`:

```ts
import type { FastifyRequest } from "fastify";
import { describe, expect, it } from "vitest";
import { isBearerTokenValid, requireBearerToken } from "../../src/http/auth.js";

function fakeRequest(authorization?: string): FastifyRequest {
  return { headers: authorization === undefined ? {} : { authorization } } as FastifyRequest;
}

describe("isBearerTokenValid", () => {
  it("accepts the exact bearer token", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer secret"), "secret")).toBe(true);
  });

  it("rejects a wrong token of the same length", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer secreX"), "secret")).toBe(false);
  });

  it("rejects a token of a different length without throwing", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer s"), "secret")).toBe(false);
    expect(isBearerTokenValid(fakeRequest("Bearer secret-and-more"), "secret")).toBe(false);
  });

  it("rejects a missing authorization header", () => {
    expect(isBearerTokenValid(fakeRequest(), "secret")).toBe(false);
  });

  it("rejects a non-bearer scheme", () => {
    expect(isBearerTokenValid(fakeRequest("Basic secret"), "secret")).toBe(false);
  });
});

describe("requireBearerToken", () => {
  it("throws a 401-coded error for an invalid token", () => {
    expect(() => requireBearerToken(fakeRequest("Bearer wrong!"), "secret")).toThrowError(
      expect.objectContaining({ statusCode: 401, code: "unauthorized" })
    );
  });

  it("does not throw for a valid token", () => {
    expect(() => requireBearerToken(fakeRequest("Bearer secret"), "secret")).not.toThrow();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && npm test -- test/http/auth.test.ts`
Expected: FAIL — `isBearerTokenValid` is not exported ("does not provide an export named 'isBearerTokenValid'").

- [ ] **Step 3: Implement the constant-time comparison**

Replace the entire content of `backend/src/http/auth.ts` with:

```ts
import { timingSafeEqual } from "node:crypto";
import type { FastifyRequest } from "fastify";

export function isBearerTokenValid(request: FastifyRequest, expectedToken: string): boolean {
  const header = request.headers.authorization;
  if (header === undefined) {
    return false;
  }
  const expected = Buffer.from(`Bearer ${expectedToken}`);
  const received = Buffer.from(header);
  if (received.length !== expected.length) {
    return false;
  }
  return timingSafeEqual(received, expected);
}

export function requireBearerToken(request: FastifyRequest, expectedToken: string): void {
  if (!isBearerTokenValid(request, expectedToken)) {
    throw Object.assign(new Error("unauthorized"), {
      statusCode: 401,
      code: "unauthorized"
    });
  }
}
```

The length guard is required because `timingSafeEqual` throws on unequal-length buffers; leaking the token length is acceptable (the token is 256-bit).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && npm test -- test/http/auth.test.ts test/http/routes.test.ts`
Expected: PASS (routes tests still green — `requireBearerToken` behavior is unchanged for callers).

- [ ] **Step 5: Commit**

```bash
git add backend/src/http/auth.ts backend/test/http/auth.test.ts
git commit -m "fix: constant-time bearer token comparison"
```

---

### Task 2: Serve all routes under the `/buzer` prefix

> **Reverted 2026-07-15 — this task's premise was wrong.** Live verification after the first real deploy showed Tailscale Funnel *does* strip its `--set-path` mount before forwarding, the opposite of what this section assumes. The `/buzer` prefix added here was removed from the backend; see `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`. Left below unedited as a historical record of what was originally built and why — do not follow this task's steps for new work.

Tailscale Funnel forwards `https://…/buzer/health` to the backend as `GET /buzer/health` — the mount path is NOT stripped. The backend must own the prefix.

**Files:**
- Create: `backend/src/http/prefix.ts`
- Modify: `backend/src/server.ts` (the `buildServer` function)
- Modify: `backend/src/http/routes.ts` (only the auth `preHandler` hook)
- Modify: `backend/test/http/routes.test.ts` (every `url:` value)

- [ ] **Step 1: Write the failing test**

Add to the top-level `describe` block in `backend/test/http/routes.test.ts` (imports for this test are added in Step 4; for now add the import line `import { API_PREFIX } from "../../src/http/prefix.js";` after the existing imports — it will not resolve yet, which is part of the expected failure):

```ts
  it("serves routes only under the API prefix", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const prefixed = await app.inject({ method: "GET", url: `${API_PREFIX}/health` });
    const unprefixed = await app.inject({ method: "GET", url: "/health" });
    const unprefixedApi = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(prefixed.statusCode).toBe(200);
    expect(unprefixed.statusCode).toBe(404);
    expect(unprefixedApi.statusCode).toBe(404);
  });
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && npm test -- test/http/routes.test.ts`
Expected: FAIL — module `../../src/http/prefix.js` not found.

- [ ] **Step 3: Implement the prefix**

Create `backend/src/http/prefix.ts`:

```ts
export const API_PREFIX = "/buzer";
```

In `backend/src/server.ts`, add the import:

```ts
import { API_PREFIX } from "./http/prefix.js";
```

and replace the `buildServer` function:

```ts
export function buildServer(options: BuildServerOptions) {
  const app = Fastify({ logger: false });
  app.register(async (scope) => {
    registerRoutes(scope, options);
  }, { prefix: API_PREFIX });
  return app;
}
```

In `backend/src/http/routes.ts`, add the import:

```ts
import { API_PREFIX } from "./prefix.js";
```

and replace the auth hook (currently `if (request.url === "/health") { return; }`) with:

```ts
  app.addHook("preHandler", async (request) => {
    if (request.routeOptions.url === `${API_PREFIX}/health`) {
      return;
    }

    requireBearerToken(request, options.androidApiToken);
  });
```

`request.routeOptions.url` is the registered route pattern including the prefix, so this is immune to query strings — unlike the old `request.url` comparison.

- [ ] **Step 4: Update every existing test path**

Run: `cd backend && npm test -- test/http/routes.test.ts`
Expected at this point: the new test PASSES, every pre-existing test FAILS with 404s.

In `backend/test/http/routes.test.ts`, change every `url:` value to a template literal using `API_PREFIX` (the import was added in Step 1). Complete enumeration — no other URLs exist in the file:

| Old | New |
|---|---|
| `url: "/health"` (2×) | `` url: `${API_PREFIX}/health` `` |
| `url: "/tasks/snapshot"` (2×) | `` url: `${API_PREFIX}/tasks/snapshot` `` |
| `url: "/projects"` | `` url: `${API_PREFIX}/projects` `` |
| `url: "/tasks"` (4×) | `` url: `${API_PREFIX}/tasks` `` |
| `url: "/tasks/task-1/start"` | `` url: `${API_PREFIX}/tasks/task-1/start` `` |
| `url: "/tasks/task-1/extend"` | `` url: `${API_PREFIX}/tasks/task-1/extend` `` |
| `url: "/tasks/task-1/reschedule"` (4×) | `` url: `${API_PREFIX}/tasks/task-1/reschedule` `` |
| `url: "/tasks/task-1/update"` | `` url: `${API_PREFIX}/tasks/task-1/update` `` |
| `url: "/tasks/task-1/postpone-start"` (2×) | `` url: `${API_PREFIX}/tasks/task-1/postpone-start` `` |
| `url: "/device/check-in"` | `` url: `${API_PREFIX}/device/check-in` `` |

Do NOT touch the two URLs inside the new test from Step 1 (`"/health"` and `"/tasks/snapshot"` there intentionally stay unprefixed — they assert the 404).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && npm test && npm run typecheck`
Expected: all PASS, typecheck clean.

- [ ] **Step 6: Commit**

```bash
git add backend/src/http/prefix.ts backend/src/server.ts backend/src/http/routes.ts backend/test/http/routes.test.ts
git commit -m "feat: serve backend API under /buzer prefix"
```

---

### Task 3: Trim unauthenticated `/health` responses

Unauthenticated `/health` must return only `{ok, service, timestamp}` on both the 200 and 503 paths. `checks` detail (database, WhatsApp connection) and DB error messages appear only with a valid bearer token.

**Files:**
- Modify: `backend/src/http/routes.ts` (the `/health` handler only)
- Modify: `backend/test/http/routes.test.ts` (the two health tests; add two new ones)

- [ ] **Step 1: Rewrite the health tests to the new contract (failing first)**

In `backend/test/http/routes.test.ts`, replace the test `"returns database health without auth token"` with:

```ts
  it("returns trimmed health without auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({ method: "GET", url: `${API_PREFIX}/health` });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      ok: true,
      service: "buzer-backend",
      timestamp: expect.any(String)
    });
  });
```

Replace the test `"returns WhatsApp health when escalation service is configured"` — the only changes are the request now sends the auth header and the test name is updated:

```ts
  it("returns WhatsApp health with auth token when escalation service is configured", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const sender = new DryRunSender();
    const escalationService = new EscalationService(db, repo, sender, "wife-chat");
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      escalationService
    });

    const response = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      ok: true,
      checks: {
        database: "ok",
        whatsapp: {
          connected: true,
          detail: "dry-run"
        }
      }
    });
  });
```

Add two new tests after them:

```ts
  it("trims the 503 health response without auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    db.close();

    const response = await app.inject({ method: "GET", url: `${API_PREFIX}/health` });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({
      ok: false,
      service: "buzer-backend",
      timestamp: expect.any(String)
    });
  });

  it("includes failure detail in the 503 health response with auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    db.close();

    const response = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toMatchObject({
      ok: false,
      checks: { database: "failed" },
      message: expect.any(String)
    });
  });
```

- [ ] **Step 2: Run tests to verify the new expectations fail**

Run: `cd backend && npm test -- test/http/routes.test.ts`
Expected: FAIL — the trimmed-health test fails because the body still contains `checks`; the 503 tests fail on shape.

- [ ] **Step 3: Implement the trimmed handler**

In `backend/src/http/routes.ts`, change the auth import line to also pull in the helper:

```ts
import { isBearerTokenValid, requireBearerToken } from "./auth.js";
```

Replace the entire `app.get("/health", …)` handler with:

```ts
  app.get("/health", async (request, reply) => {
    const authenticated = isBearerTokenValid(request, options.androidApiToken);
    const timestamp = new Date().toISOString();
    const checks: {
      database: "ok" | "failed";
      whatsapp?: { connected: boolean; detail: string };
    } = { database: "ok" };

    try {
      options.db.prepare("select 1").get();
    } catch (error) {
      checks.database = "failed";
      reply.status(503).send({
        ok: false,
        service: "buzer-backend",
        timestamp,
        ...(authenticated
          ? { checks, message: error instanceof Error ? error.message : "Database health check failed" }
          : {})
      });
      return;
    }

    if (authenticated && options.escalationService !== null && options.escalationService !== undefined) {
      checks.whatsapp = await options.escalationService.getHealth();
    }

    return {
      ok: true,
      service: "buzer-backend",
      timestamp,
      ...(authenticated ? { checks } : {})
    };
  });
```

Note the WhatsApp health probe is skipped entirely for unauthenticated requests — no wasted work, nothing to leak. The `ProjectRepository` import in the test file remains used by other tests; do not remove imports.

- [ ] **Step 4: Run the full backend suite**

Run: `cd backend && npm test && npm run typecheck`
Expected: all PASS, typecheck clean.

- [ ] **Step 5: Commit**

```bash
git add backend/src/http/routes.ts backend/test/http/routes.test.ts
git commit -m "fix: trim unauthenticated health responses"
```

---

### Task 4: Proxy-aware per-IP rate limiting

Funnel traffic reaches Fastify as `tailscaled` → host `127.0.0.1` → `docker-proxy` → container, so the socket peer is the compose network gateway for every public request. Without `trustProxy`, all internet traffic shares one rate-limit bucket and a scanner flood 429s the legitimate phone. `trustProxy` is scoped to loopback plus the pinned compose subnet (`172.28.0.0/16`, pinned in Task 5) so `request.ip` resolves to the client address `tailscaled` appends to `X-Forwarded-For` — and an untrusted (direct-tailnet) peer cannot spoof the header.

**Files:**
- Modify: `backend/package.json` / `backend/package-lock.json` (new dependency)
- Modify: `backend/src/server.ts`
- Create: `backend/test/http/rateLimit.test.ts`

- [ ] **Step 1: Install the dependency**

Run: `cd backend && npm install --save-exact @fastify/rate-limit@10`
Expected: installs the latest v10.x (compatible with Fastify 5), saved as an exact version like the other deps.

- [ ] **Step 2: Write the failing tests**

Create `backend/test/http/rateLimit.test.ts`:

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { API_PREFIX } from "../../src/http/prefix.js";
import { buildServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

function createApp() {
  const db = new Database(":memory:");
  migrate(db);
  return buildServer({
    db,
    currentUserId: "user-self",
    androidApiToken: "secret",
    todoist: new FakeTodoistAdapter(),
    rateLimit: { max: 3, timeWindowMs: 60_000 }
  });
}

describe("rate limiting", () => {
  it("throttles a burst from a single client IP", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const ok = await app.inject({ method: "GET", url: `${API_PREFIX}/health`, remoteAddress: "198.51.100.10" });
      expect(ok.statusCode).toBe(200);
    }
    const blocked = await app.inject({ method: "GET", url: `${API_PREFIX}/health`, remoteAddress: "198.51.100.10" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });

  it("keys by X-Forwarded-For client when the peer is a trusted proxy", async () => {
    const app = createApp();
    const proxyPeer = "172.28.0.1";

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: `${API_PREFIX}/health`,
        remoteAddress: proxyPeer,
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const exhaustedClient = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    const otherClient = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.2" }
    });
    await app.close();

    expect(exhaustedClient.statusCode).toBe(429);
    expect(otherClient.statusCode).toBe(200);
  });

  it("ignores X-Forwarded-For from untrusted peers", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: `${API_PREFIX}/health`,
        remoteAddress: "100.100.7.7",
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const samePeer = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      remoteAddress: "100.100.7.7",
      headers: { "x-forwarded-for": "203.0.113.9" }
    });
    const differentPeerSameHeader = await app.inject({
      method: "GET",
      url: `${API_PREFIX}/health`,
      remoteAddress: "100.100.7.8",
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    await app.close();

    expect(samePeer.statusCode).toBe(429);
    expect(differentPeerSameHeader.statusCode).toBe(200);
  });

  it("rate limits unknown paths", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const miss = await app.inject({ method: "GET", url: `${API_PREFIX}/nope`, remoteAddress: "198.51.100.20" });
      expect(miss.statusCode).toBe(404);
    }
    const blocked = await app.inject({ method: "GET", url: `${API_PREFIX}/nope`, remoteAddress: "198.51.100.20" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && npm test -- test/http/rateLimit.test.ts`
Expected: FAIL — TypeScript rejects the unknown `rateLimit` option on `BuildServerOptions` (or, at runtime, every response is 200 because no limiter exists).

- [ ] **Step 4: Implement rate limiting and trustProxy**

In `backend/src/server.ts`:

Add the import (with the other imports):

```ts
import rateLimit from "@fastify/rate-limit";
```

Add the optional field to `BuildServerOptions`:

```ts
export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
  todoist: TodoistAdapter;
  escalationService?: EscalationService | null;
  rateLimit?: { max: number; timeWindowMs: number };
}
```

Replace `buildServer` with:

```ts
export function buildServer(options: BuildServerOptions) {
  const app = Fastify({
    logger: false,
    // Trust only loopback and the compose network (pinned to 172.28.0.0/16 in
    // docker-compose.yml) so X-Forwarded-For set by tailscaled is honored while
    // direct tailnet peers cannot spoof it.
    trustProxy: ["127.0.0.0/8", "172.28.0.0/16"]
  });
  app.register(rateLimit, {
    max: options.rateLimit?.max ?? 60,
    timeWindow: options.rateLimit?.timeWindowMs ?? 60_000
  });
  app.register(async (scope) => {
    registerRoutes(scope, options);
  }, { prefix: API_PREFIX });
  app.after(() => {
    app.setNotFoundHandler({ preHandler: app.rateLimit() }, (_request, reply) => {
      reply.status(404).send({ code: "not_found", message: "Not Found" });
    });
  });
  return app;
}
```

Notes for the implementer:
- `app.after(...)` is required around `setNotFoundHandler` because the `app.rateLimit()` decorator only exists after the plugin loads; `after` defers the callback until previously registered plugins are ready while keeping `buildServer` synchronous.
- The default of 60 requests/minute per IP is deliberate: the single Android client plus the in-container Docker healthcheck (2/min from `127.0.0.1`) sit far below it.
- Do not set a custom `keyGenerator` — the default keys on `request.ip`, which is exactly what `trustProxy` fixes.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && npm test && npm run typecheck`
Expected: all PASS (routes tests do at most ~15 requests per fresh app instance — far under the default 60), typecheck clean.

- [ ] **Step 6: Commit**

```bash
git add backend/package.json backend/package-lock.json backend/src/server.ts backend/test/http/rateLimit.test.ts
git commit -m "feat: add proxy-aware per-ip rate limiting"
```

---

### Task 5: docker-compose.yml — hardcoded binds, prefixed healthcheck, pinned subnet

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace the file content**

Replace the entire content of `docker-compose.yml` with:

```yaml
services:
  backend:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: buzer-backend
    restart: unless-stopped
    ports:
      # Loopback: tailscaled Funnel forwarding + local health checks.
      - "127.0.0.1:18432:18432"
      # tailscale0 address: direct tailnet fallback independent of Funnel.
      # Binding this address at boot requires net.ipv4.ip_nonlocal_bind=1 on the host.
      - "100.105.180.34:18432:18432"
    volumes:
      - buzer-data:/app/backend/data
      - buzer-whatsapp:/app/backend/whatsapp-sessions
    env_file:
      - .env
    environment:
      - NODE_ENV=production
      - PORT=18432
    healthcheck:
      test: ["CMD-SHELL", "node -e \"fetch('http://127.0.0.1:' + (process.env.PORT || '18432') + '/buzer/health').then((r)=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))\""]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

networks:
  default:
    ipam:
      config:
        # Pinned so the backend's trustProxy CIDR (172.28.0.0/16) stays correct.
        - subnet: 172.28.0.0/16

volumes:
  buzer-data:
  buzer-whatsapp:
```

What changed and why:
- The `${BUZER_BIND_HOST:-0.0.0.0}` / `${BUZER_HOST_PORT}` / `${BUZER_CONTAINER_PORT}` env-var pattern is deleted — the fail-open `0.0.0.0` default is what caused the leak, and one variable can't express two binds.
- Healthcheck path gains `/buzer` (Task 2 moved the route).
- The subnet pin must match `trustProxy` in `backend/src/server.ts` exactly: `172.28.0.0/16`.

- [ ] **Step 2: Validate the YAML if Docker is available**

Run: `docker compose -f docker-compose.yml config --quiet 2>&1 || echo "docker unavailable or .env missing - validated at deploy time"`
Expected: no output (valid), or the fallback message (compose validation needs a local `.env`; the operator's deploy applies it on the VPS regardless).

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "fix: bind backend to loopback and tailscale ip only"
```

---

### Task 6: deploy-vps.sh — prefixed health wait

**Files:**
- Modify: `scripts/deploy-vps.sh`

- [ ] **Step 1: Update the health URL and drop stale exports**

In `scripts/deploy-vps.sh`, change the line:

```bash
health_url="${BUZER_HEALTH_URL:-http://127.0.0.1:$host_port/health}"
```

to:

```bash
health_url="${BUZER_HEALTH_URL:-http://127.0.0.1:$host_port/buzer/health}"
```

and delete these two lines (compose no longer consumes either variable; `env_file` is a literal path in the compose file and `--env-file` is passed explicitly):

```bash
export BUZER_ENV_FILE="$env_file"
export BUZER_HOST_PORT="$host_port"
```

Keep the `host_port` variable definition itself — it is still used in the `health_url` default.

- [ ] **Step 2: Sanity-check the script parses**

Run: `bash -n scripts/deploy-vps.sh`
Expected: no output, exit 0.

- [ ] **Step 3: Commit**

```bash
git add scripts/deploy-vps.sh
git commit -m "fix: point deploy health wait at /buzer/health"
```

---

### Task 7: Android — remove the debug cleartext override

Once the backend is HTTPS-only, debug builds must exercise the same network path as release builds. Consequence (accepted in the spec): after this lands, a debug build pointed at `http://100.105.180.34:18432` stops working — the raw tailnet URL becomes a dev-machine `curl` path only. The operator flips `client/local.properties` to the HTTPS URL during rollout (runbook step 9); do not edit that file — it is gitignored and per-developer.

**Files:**
- Modify: `client/androidApp/src/debug/AndroidManifest.xml`

- [ ] **Step 1: Remove the attribute**

Replace the entire content of `client/androidApp/src/debug/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <activity
            android:name=".debug.DebugBlockerLauncherActivity"
            android:exported="true" />
        <activity
            android:name=".debug.DebugOverlayLauncherActivity"
            android:exported="true" />
    </application>

</manifest>
```

(Only `android:usesCleartextTraffic="true"` is removed; the two debug activities stay.)

- [ ] **Step 2: Verify the debug build still compiles**

Run: `cd client && ./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. (This is a compile/manifest-merge check only; the real-device smoke test is an operator rollout step.)

- [ ] **Step 3: Commit**

```bash
git add client/androidApp/src/debug/AndroidManifest.xml
git commit -m "fix: drop debug-only cleartext traffic override"
```

---

### Task 8: Documentation updates

**Files:**
- Modify: `docs/notes/vps-deployment.md`
- Modify: `docs/notes/github-actions-tailscale-deploy.md`
- Modify: `docs/notes/api-contract.md`

- [ ] **Step 1: Update `docs/notes/vps-deployment.md`**

Make these changes (section anchors as of today's file):

1. Replace the intro sentence `The MVP backend deploys manually on the Tailscale-only VPS. The VPS is reachable from the tailnet as:` with:

```markdown
The backend deploys manually on the VPS. The public API entry point is Tailscale Funnel at `https://oc-vps.taila50381.ts.net/buzer`; the VPS itself is reachable from the tailnet as:
```

2. Replace the line `The backend is intentionally available only on the Tailscale/private network for MVP.` with:

```markdown
The backend is intentionally exposed to the public internet **only** through Tailscale Funnel (`https://oc-vps.taila50381.ts.net/buzer`, TLS terminated by `tailscaled`). Docker binds the backend to `127.0.0.1:18432` (Funnel forwarding target) and `100.105.180.34:18432` (direct tailnet fallback). Nothing listens on the public interface; `ufw` stays `default deny (incoming)`. See `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` for the full design, including why Docker-published `0.0.0.0` ports bypass `ufw` (verified: `sudo iptables -t nat -L DOCKER -n` shows a DNAT rule for any destination when a port is published to `0.0.0.0`).
```

3. In the Environment section's expected `.env` shape, delete the three-line block:

```env
# Compose/deploy settings
BUZER_HOST_PORT=18432
BUZER_CONTAINER_PORT=18432
BUZER_BIND_HOST=0.0.0.0
```

(also delete the now-empty trailing blank line). Compose hardcodes ports and binds since this change.

4. Replace the token generation command `openssl rand -base64 48` with `openssl rand -hex 32` (matches the token actually in use — 256-bit hex).

5. In the Health Checks section, replace both curl examples and the expected shape with:

```markdown
From the VPS:

​```bash
curl --fail http://127.0.0.1:18432/buzer/health
​```

From a Tailscale-connected machine (direct fallback, bypasses Funnel):

​```bash
curl --fail http://100.105.180.34:18432/buzer/health
​```

From anywhere (public path through Funnel):

​```bash
curl --fail https://oc-vps.taila50381.ts.net/buzer/health
​```

Unauthenticated expected shape (trimmed on purpose — no `checks` detail for the public internet):

​```json
{
  "ok": true,
  "service": "buzer-backend",
  "timestamp": "2026-07-13T20:00:00.000Z"
}
​```

With a valid `Authorization: Bearer <ANDROID_API_TOKEN>` header the response additionally contains `checks` (database, WhatsApp connection status).
```

(Remove the `​` zero-width characters when pasting — they only keep the nested code fences intact in this plan.)

6. Replace the sentence `` `/health` is unauthenticated. Application API routes remain protected by bearer auth. `` with:

```markdown
`/buzer/health` is unauthenticated but trimmed; all other `/buzer/*` routes require bearer auth. A global per-IP rate limit (60 req/min) applies behind `trustProxy` — the compose network subnet is pinned to `172.28.0.0/16` to match, so do not change one without the other.
```

7. In the Rollback section, change `curl --fail http://127.0.0.1:18432/health` to `curl --fail http://127.0.0.1:18432/buzer/health`.

8. Append this new section at the end of the file:

```markdown
## Internet Exposure (Tailscale Funnel)

One-time host setup (already applied; recorded for rebuilds):

​```bash
# Boot-order safety: Docker may start the container before tailscale0 has its
# address; without this, the 100.105.180.34 port bind fails at boot and Docker
# restart policies do not reliably retry start-time bind failures.
echo 'net.ipv4.ip_nonlocal_bind=1' | sudo tee /etc/sysctl.d/99-tailscale-bind.conf
sudo sysctl -p /etc/sysctl.d/99-tailscale-bind.conf

# Funnel mapping (persists across reboots in tailscaled state):
sudo tailscale funnel --bg --set-path /buzer 18432
​```

Tailnet prerequisites (admin console, one-time): MagicDNS enabled, HTTPS certificates enabled, `funnel` node attribute on `oc-vps` in the tailnet policy file.

Funnel does not strip the `/buzer` mount path (tailscale/tailscale#6571), so the backend serves all routes under `/buzer` natively. Any additional service exposed on this hostname must likewise own its path prefix, or use public ports 8443/10000, or run its own tailscale sidecar node.

`fail2ban` is deliberately not installed: SSH is accepted only on `tailscale0`, so it could only ban tailnet peers.
```

- [ ] **Step 2: Update `docs/notes/github-actions-tailscale-deploy.md`**

Replace the first paragraph `The VPS is intentionally reachable only inside the Tailscale network. A normal GitHub-hosted Actions runner cannot SSH to (…) unless the workflow first joins the tailnet.` with:

```markdown
SSH to the VPS is reachable only inside the Tailscale network (the backend HTTP API is separately exposed to the public internet via Tailscale Funnel at `https://oc-vps.taila50381.ts.net/buzer` — see `docs/notes/vps-deployment.md`). A normal GitHub-hosted Actions runner cannot SSH to `oc-vps.taila50381.ts.net` unless the workflow first joins the tailnet, so everything below still applies to deploys.
```

- [ ] **Step 3: Update `docs/notes/api-contract.md`**

Insert a new section between `## Principles` and `## Core Types`:

```markdown
## Base URL

All paths in this contract are relative to a base URL that already includes the `/buzer` prefix — the backend serves every route under it (Tailscale Funnel does not strip its path mount):

- Production (public, HTTPS via Tailscale Funnel): `https://oc-vps.taila50381.ts.net/buzer`
- Tailnet fallback (dev-machine `curl` only, plain HTTP): `http://100.105.180.34:18432/buzer`

Example: the snapshot endpoint below is `GET https://oc-vps.taila50381.ts.net/buzer/tasks/snapshot`. Clients must never assume the API lives at the host root.
```

- [ ] **Step 4: Commit**

```bash
git add docs/notes/vps-deployment.md docs/notes/github-actions-tailscale-deploy.md docs/notes/api-contract.md
git commit -m "docs: reflect funnel exposure and /buzer prefix in runbooks"
```

---

### Task 9: Final verification and handoff

- [ ] **Step 1: Full backend verification**

Run: `cd backend && npm test && npm run typecheck && npm run build`
Expected: all tests PASS, typecheck clean, build succeeds.

- [ ] **Step 2: Consistency greps**

Run these from the repo root; each must match the expectation:

```bash
grep -rn "BUZER_BIND_HOST\|BUZER_HOST_PORT\|BUZER_CONTAINER_PORT" docker-compose.yml scripts/ backend/src/
# Expected: only scripts/deploy-vps.sh's host_port/BUZER_HEALTH_URL lines (no compose or backend hits)

grep -n "buzer/health" docker-compose.yml scripts/deploy-vps.sh
# Expected: exactly one hit in each file

grep -rn "usesCleartextTraffic" client/
# Expected: no hits

grep -rn '"/health"' backend/src/
# Expected: exactly one hit - the route registration in backend/src/http/routes.ts
# (it is correct: the route is registered inside the /buzer prefix scope)
```

If any grep disagrees, fix before proceeding.

- [ ] **Step 3: Write the handoff file (required by AGENTS.md)**

Create `docs/handoffs/2026-07-13-backend-internet-exposure-implementation.md` documenting: plan name and checkbox status, files changed, commands run with results, tests run with results, and the explicit note that deployment/rollout (runbook below) has NOT been performed and remains with the operator.

- [ ] **Step 4: Commit the handoff and finish the branch**

```bash
git add docs/handoffs/2026-07-13-backend-internet-exposure-implementation.md
git commit -m "docs: handoff for internet exposure implementation"
```

Then use the superpowers:finishing-a-development-branch skill to decide merge/PR handling with the user. Do not merge to master without confirmation — the deploy script resets the VPS checkout to `origin/master`, so merging is effectively arming the deploy.

---

## Appendix: Operator Rollout Runbook (manual — NOT for the executing agent)

Maps to spec §5. Execute in order after the branch is merged to `master`. Steps needing sudo are marked.

1. **Recovery pre-flight (blocking):** confirm Contabo out-of-band console access (VNC/rescue via the Contabo control panel). Do not proceed until verified. Tailscale node key expiry for `oc-vps` is already disabled (2026-07-13).
2. **Tailscale admin console:** enable **HTTPS certificates** for the tailnet (DNS page; currently off — `CertDomains: null`), and add the `funnel` node attribute for `oc-vps` to the tailnet policy file:
   ```json
   "nodeAttrs": [
     { "target": ["oc-vps"], "attr": ["funnel"] }
   ]
   ```
3. **VPS (sudo) — boot-order sysctl:**
   ```bash
   echo 'net.ipv4.ip_nonlocal_bind=1' | sudo tee /etc/sysctl.d/99-tailscale-bind.conf
   sudo sysctl -p /etc/sysctl.d/99-tailscale-bind.conf
   ```
4. **VPS — subnet collision check** (the compose network pins `172.28.0.0/16`):
   ```bash
   docker network ls -q | xargs docker network inspect --format '{{.Name}} {{range .IPAM.Config}}{{.Subnet}}{{end}}' | grep 172.28 || echo OK
   ```
   Expected `OK` (before first deploy of this change). If another network already uses `172.28.0.0/16`, pick a free `/16`, change it in BOTH `docker-compose.yml` and `trustProxy` in `backend/src/server.ts`, and redeploy.
5. **Deploy:** `ssh oc-vps.taila50381.ts.net` then `/home/skclaw/buzer-app/scripts/deploy-vps.sh`. The script now waits on `/buzer/health`.
6. **Funnel (sudo, on VPS):** `sudo tailscale funnel --bg --set-path /buzer 18432`
7. **Verify** (spec §5 step 6):
   - From off-tailnet: `curl -m 5 http://167.86.68.104:18432/buzer/health` → connection failure (leak closed; also try the bare `/health`).
   - From off-tailnet: `curl https://oc-vps.taila50381.ts.net/buzer/health` → 200 trimmed body.
   - From dev machine (tailnet): `curl http://100.105.180.34:18432/buzer/health` → 200 (fallback intact).
   - `X-Forwarded-For` reality check: from off-tailnet run a burst of >60 requests in a minute → 429s; then immediately from the dev machine (different source) request once → 200. If the dev-machine request is also 429, the per-IP keying is broken — stop and investigate before relying on it.
   - `/buzer/health` with `Authorization: Bearer <token>` → includes `checks`.
8. **Reboot test (sudo):** `sudo reboot`; after boot confirm `docker ps` shows `buzer-backend` healthy, both binds present (`ss -tln | grep 18432` → `127.0.0.1` and `100.105.180.34`, no `0.0.0.0`), and the Funnel URL still answers.
9. **Android:** edit `client/local.properties` → `BUZER_BACKEND_BASE_URL=https://oc-vps.taila50381.ts.net/buzer`, rebuild, and run one real-device smoke test per `AGENTS.md` (snapshot loads, a task action round-trips).
10. **Docs:** already updated in the branch (Task 8) — verify they match what was actually done; amend if any step deviated.
