# Backend Internet Exposure Design

Date: 2026-07-13

## Context

The backend has been documented as "Tailscale-only" since MVP (`docs/notes/vps-deployment.md`, `docs/notes/github-actions-tailscale-deploy.md`), but a live audit of the VPS (`oc-vps.taila50381.ts.net`, Contabo-hosted Ubuntu 24.04) found it is already reachable from the public internet. `docker-compose.yml` publishes the backend container as `0.0.0.0:18432->18432`, and Docker's own iptables rules for published ports bypass `ufw`'s `INPUT` chain filtering entirely. `sudo ufw status verbose` confirms `default deny (incoming)` with no rule for `18432` anywhere, yet an external request to `http://167.86.68.104:18432/health` from outside the tailnet returned a live 200 response. Ports 22/80/443 on the same public IP are correctly filtered — only the Docker-published port slips through.

Separately, the product goal is to stop depending on the Tailscale client running on the Android phone at all ("fewer moving parts"), which requires the backend to be reachable over the ordinary internet regardless of the leak above.

This design closes the accidental exposure and replaces it with an intentional one.

## Goal

Make the backend safely reachable from the public internet without requiring Tailscale on the Android client, while closing the existing accidental exposure and keeping the security bar appropriate for what's behind it (a personal Todoist account, task data, and a live authenticated WhatsApp session).

## Scope

In scope:

- Rebinding the backend off the public interface.
- Tailscale Funnel as the public HTTPS entry point.
- Minimal auth hardening appropriate for a now-public endpoint (rate limiting, constant-time token comparison, trimmed `/health` response, `fail2ban`).
- Android client configuration to use the new HTTPS URL and drop the debug-only cleartext override.
- Recovery-access pre-flight so tightening network access doesn't risk locking out the operator.
- Documentation updates to keep `docs/notes/` accurate.

Out of scope:

- Token rotation. Deferred by explicit decision — the current token is 384 bits of entropy via `openssl rand -base64 48` and isn't itself compromised; rotating it is orthogonal to this change.
- Multi-user/per-device tokens, OAuth, or any move toward `public multi-user product behavior` (remains out of scope per `AGENTS.md`).
- A general-purpose reverse proxy (Caddy/nginx) or custom domain — considered and rejected, see Alternatives.
- A structural `DOCKER-USER` iptables safeguard against future accidental `0.0.0.0` publishes. Real hardening, but the direct fix (never bind `0.0.0.0`) resolves the current issue; noted as a possible future follow-up, not required here.

## Alternatives Considered

1. **Tailscale Funnel (chosen).** VPS stays a tailnet member exactly as today; Funnel exposes the backend on `https://oc-vps.taila50381.ts.net` with TLS auto-provisioned and terminated by `tailscaled`, and no client-side Tailscale install required. Smallest change from current state — no new services to run, no cert management, no domain to buy. Trade-offs accepted: the public hostname is locked to the tailnet's own `*.ts.net` domain, and Funnel bandwidth has non-configurable limits (a non-issue at single-user scale).
2. **Own reverse proxy + domain (Caddy/nginx + Let's Encrypt).** Same end-user outcome, but adds a service to run, a domain to own, and a cert lifecycle to babysit. More independent of Tailscale as a vendor, but strictly more infrastructure in exchange for removing Tailscale from the phone. Rejected for now: the stated goal is fewer moving parts, and this adds more of them on the server to remove one from the client.
3. **Stay Tailscale-only, only fix the leak.** Rebind off `0.0.0.0` and stop there. Correctly closes the accidental exposure but leaves the phone dependent on the Tailscale client, which doesn't address the actual goal. Rejected as incomplete.
4. **Open SSH on the public interface as a recovery fallback.** Raised and explicitly rejected: reintroduces real attack surface to hedge against a problem (operator lockout) that has better answers — see Recovery Access below.

## Design

### 1. Network Exposure

- `docker-compose.yml`: stop publishing to `0.0.0.0`. Bind the container port to two host addresses instead of one:
  - `127.0.0.1:18432` — for `tailscaled`'s local Funnel forwarding and local health checks.
  - `100.105.180.34:18432` (the VPS's `tailscale0` address) — preserves today's direct tailnet access (dev-machine `curl`, debug builds still pointed at the raw tailnet URL) as a fallback path independent of Funnel/Tailscale's public-facing infra.
  - Nothing binds to `eth0` / the public IP after this change.
- VPS: add the `funnel` node attribute to the tailnet policy file (Tailscale admin console, one-time, out of repo).
- VPS: `sudo tailscale funnel --bg 18432` to persist the mapping (public `443` → local `18432`) across reboots and `tailscale` restarts.
- Result: `https://oc-vps.taila50381.ts.net` becomes the public URL. `ufw`'s existing `default deny (incoming)` now actually applies to `18432`, since Docker no longer punches a hole in it.

### 2. Auth & Hardening

The token check becomes reachable by the whole internet instead of only tailnet peers, so:

- Add `@fastify/rate-limit` as a new backend dependency, registered globally (covers `/health` too — no reason to exempt a now-public endpoint from basic flood protection), with a modest per-IP threshold appropriate for a single-device client.
- `backend/src/http/auth.ts`: replace the `!==` comparison in `requireBearerToken` with `crypto.timingSafeEqual` (guard for length mismatch first, since `timingSafeEqual` throws on unequal-length buffers).
- `backend/src/http/routes.ts`: unauthenticated `/health` response shrinks to `{ok, service, timestamp}`; the current `checks` detail (database, WhatsApp connection status) stays in the response only when a valid bearer token is present. The Docker healthcheck in `docker-compose.yml` only checks HTTP success (`r.ok`) and never parses the response body, so trimming is safe without touching the healthcheck itself.
- Enable `fail2ban` (`systemctl enable --now fail2ban`) for defense-in-depth on SSH. SSH's exposure doesn't change (still `tailscale0`-only via `ufw`), but there's no reason to leave an already-installed protection turned off.
- Token rotation: explicitly deferred, see Scope.

### 3. Android Client

- `client/local.properties` (gitignored, per-developer): update `BUZER_BACKEND_BASE_URL` to `https://oc-vps.taila50381.ts.net`.
- `client/androidApp/src/debug/AndroidManifest.xml`: remove `android:usesCleartextTraffic="true"`. Once the backend is HTTPS-only, debug builds should exercise the same network path a release build would — this also closes the gap the original design flagged (`docs/superpowers/specs/2026-05-28-android-vps-connected-enforcement-design.md`: "Hardcoded debug config is acceptable for dev mode but must not become production setup").
- No `BuzerApiClient` code changes required — `baseUrl` is already a plain constructor string.

### 4. Recovery Access (pre-flight, before touching network config)

- Tailscale node key expiry: disabled for `oc-vps` (done 2026-07-13).
- Contabo out-of-band console (VNC/rescue mode via the Contabo customer control panel): confirm it works, independent of SSH/Tailscale/ufw entirely. This is on the operator to verify directly in Contabo's dashboard — not reachable from inside the VPS itself.
- Explicitly not doing: opening SSH on the public interface as a fallback (see Alternatives #4).

### 5. Rollout & Verification

1. Confirm Contabo console access (recovery pre-flight, blocking — do not proceed until this is verified).
2. Apply the network exposure change (§1) on the VPS.
3. Verify from outside the tailnet, the same method that found the original leak:
   - `curl http://167.86.68.104:18432/health` must now fail to connect (proves the leak is closed).
   - `curl https://oc-vps.taila50381.ts.net/health` must return 200 (proves Funnel works).
4. Apply auth hardening (§2), redeploy, re-verify the `/health` shape (with and without a token) and confirm a rate-limited burst of requests gets throttled.
5. Update Android config (§3) and run one real-device smoke test against the new URL (matches the existing manual real-device VPS smoke pattern in `docs/handoffs/`).
6. Update `docs/notes/vps-deployment.md` (bind addresses, new public URL, `fail2ban` step) and `docs/notes/github-actions-tailscale-deploy.md` (replace the "Tailscale-only" framing with the Funnel reality) to match.

## Testing

- Backend: existing Vitest suite plus new coverage for `requireBearerToken`'s constant-time comparison and the trimmed unauthenticated `/health` shape.
- Manual: the outside-tailnet `curl` checks in Rollout step 3 are the actual proof this works; nothing else substitutes for them.
- Android: one real-device smoke test against the new HTTPS URL per `AGENTS.md` testing expectations for VPS-connected changes.

## Open Risks

- Funnel bandwidth limits are non-configurable (Tailscale-imposed). Not expected to matter at single-user scale; revisit if that changes.
- The public hostname is locked to the tailnet's `*.ts.net` domain for as long as Funnel is the chosen path — no custom domain without switching to Alternative 2 later.
- The `DOCKER-USER` iptables bypass is closed here by removing the `0.0.0.0` bind, not by a structural rule. If a future container on this VPS publishes to `0.0.0.0` again, the same class of leak can recur silently. A `DOCKER-USER`-chain safeguard (per Docker's own documented `ufw` interaction) would close this structurally; deferred as a follow-up, not required for this change.
- This VPS also runs an unrelated project (`expense-bot`) sharing the same Docker daemon and host kernel. Out of scope here, but worth keeping in mind for blast-radius reasoning if either service's threat model changes.
