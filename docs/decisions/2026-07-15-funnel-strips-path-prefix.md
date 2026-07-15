# Decision: Tailscale Funnel Strips Its `--set-path` Mount — Drop The Backend's Self-Imposed Prefix

Date: 2026-07-15
Status: accepted (confirmed by live verification against the deployed VPS)
Amends: docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md, docs/superpowers/plans/2026-07-13-backend-internet-exposure.md (Task 2)

## What was assumed

The internet-exposure design (`docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md`, §1) stated that Tailscale Funnel does **not** strip its `--set-path` mount before proxying, citing `tailscale/tailscale#6571`. On that assumption, Task 2 of the implementation plan moved every backend route under a self-registered `/buzer` prefix (`backend/src/http/prefix.ts`, `API_PREFIX = "/buzer"`, wrapping route registration in a Fastify `{ prefix: API_PREFIX }` scope), reasoning that a request to `https://oc-vps.taila50381.ts.net/buzer/health` would reach the backend as `GET /buzer/health`.

## What was observed

After the first real deploy and `sudo tailscale funnel --bg --set-path /buzer 18432`, the public URL returned 404 for real paths (e.g. `/buzer/health`), while a deliberately-doubled path (`https://oc-vps.taila50381.ts.net/buzer/buzer/health`) returned 200. This is only possible if Funnel strips exactly one `/buzer` segment before forwarding to the backend — i.e. **Funnel does strip the mount path**, the opposite of the original assumption. Whether `tailscale/tailscale#6571` was fixed upstream since the design was written, or the original reading of that issue was mistaken, is not determined — the point is moot given the directly observed, reproducible behavior on the actual running Tailscale client.

## Decision

Drop the backend's self-imposed `/buzer` prefix entirely. The backend now serves plain, unprefixed routes (`/health`, `/tasks/snapshot`, etc.) at its own root, matching what Funnel actually delivers after stripping.

`/buzer` still exists, but only as Funnel's own `--set-path /buzer` mapping — external to the application, configured with `sudo tailscale funnel --bg --set-path /buzer 18432` (unchanged). This still achieves the original goal (reserving `/buzer` on this shared `*.ts.net` hostname for this specific service, leaving room for other services later) — it's just enforced one layer up, at the Funnel config, rather than duplicated into the backend's own routing.

## Consequences

- **Backend code:** `backend/src/http/prefix.ts` deleted. `backend/src/server.ts`'s `buildServer` goes back to registering routes without a `{ prefix }` option (still wrapped in a bare `app.register(async (scope) => {...})`, not a direct synchronous call — see the `@fastify/rate-limit` note below). `backend/src/http/routes.ts`'s auth `preHandler` health-exemption check compares against plain `"/health"` instead of `` `${API_PREFIX}/health` ``, keeping the `request.routeOptions.url` improvement (immune to query strings) from Task 2 — only the compared value changed, not the underlying mechanism.
- **`@fastify/rate-limit` requires routes to go through Fastify's plugin/`.register()` boot sequence to be tracked correctly.** During this fix, temporarily replacing the `app.register(async (scope) => {...})` wrapper with a direct synchronous `registerRoutes(app, options)` call silently broke rate limiting (no `x-ratelimit-*` headers appeared at all, confirmed via a throwaway diagnostic test) and made the root-level auth hook apply to the 404 handler too (unmatched paths returned 401 instead of 404). Restoring the plain (no-prefix) `.register()` wrapper fixed both. **Route registration in this codebase must always go through `app.register()`, even without a path prefix** — this is now load-bearing for both rate-limiting and correct 404 semantics, not just an artifact of the old prefix design.
- **`docker-compose.yml`:** healthcheck path reverted from `/buzer/health` to `/health`.
- **`scripts/deploy-vps.sh`:** health-wait URL reverted from `/buzer/health` to `/health`.
- **Client-facing behavior is unchanged.** The Android app only ever talks to the public Funnel URL (`https://oc-vps.taila50381.ts.net/buzer`) — `BuzerApiClient` concatenates `baseUrl + path`, so it doesn't matter whether `/buzer` is "real" on the backend or added/stripped by Funnel in between. No client code or config changes as a result of this correction.
- **The direct tailnet-fallback URL loses its `/buzer` segment**, since that path (`http://100.105.180.34:18432/...`) goes straight to the backend and never passes through Funnel's stripping. Was `http://100.105.180.34:18432/buzer`, now `http://100.105.180.34:18432`. This is a dev-machine-`curl`-only path (Android debug builds can't reach it at all post-Task-7's cleartext removal), so the blast radius is documentation only.
- **Tests:** the "serves routes only under the API prefix" test added in Task 2 was deleted (it tested behavior that no longer exists). All other route/rate-limit tests had their expected paths reverted from `` `${API_PREFIX}/x` `` back to plain `"/x"` strings.
- **Docs updated:** `docs/notes/vps-deployment.md`, `docs/notes/api-contract.md` — see those files' current content for the corrected URLs and the corrected Funnel-behavior explanation.

## Why this wasn't caught before the live deploy

The full local test suite, the code reviews, and the local `curl` verification against the VPS's loopback and tailnet-IP binds (all pre-Funnel, direct-to-backend paths) were all internally consistent with the `/buzer`-prefixed design and passed cleanly — none of them exercised the actual Funnel proxy layer, which is the one place the mount-stripping behavior manifests. The discrepancy was only detectable by testing the real public Funnel URL after `tailscale funnel` was actually configured and running, which is necessarily a live-infrastructure step outside the local plan's Tasks 0-9. No process change is proposed here beyond noting it: Funnel-specific behavior can only be verified against a live Funnel mapping, by design.
