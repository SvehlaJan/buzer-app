# Backend Internet Exposure Design

Date: 2026-07-13

> **Correction (2026-07-15):** §1's claim that "Funnel does not strip the `/buzer` mount path" was wrong — live verification against the deployed VPS showed Funnel *does* strip it. The backend's self-imposed `/buzer` prefix (Task 2 below) was reverted as a result. See `docs/decisions/2026-07-15-funnel-strips-path-prefix.md` for the full correction; treat any `/buzer`-prefix-on-the-backend claim in this document as historical, not current.

## Context

The backend has been documented as "Tailscale-only" since MVP (`docs/notes/vps-deployment.md`, `docs/notes/github-actions-tailscale-deploy.md`), but a live audit of the VPS (`oc-vps.taila50381.ts.net`, Contabo-hosted Ubuntu 24.04) found it is already reachable from the public internet. `docker-compose.yml` publishes the backend container as `0.0.0.0:18432->18432`, and Docker's own iptables rules for published ports bypass `ufw`'s `INPUT` chain filtering entirely. `sudo ufw status verbose` confirms `default deny (incoming)` with no rule for `18432` anywhere, yet an external request to `http://167.86.68.104:18432/health` from outside the tailnet returned a live 200 response. Ports 22/80/443 on the same public IP are correctly filtered — only the Docker-published port slips through. A follow-up live audit (2026-07-13) confirmed all of this: the off-tailnet `curl` still returns 200, `sudo ufw status verbose` shows no `18432` rule (SSH accepted only on `tailscale0`), and the bypass rule itself was captured — `sudo iptables -t nat -L DOCKER -n | grep 18432` → `DNAT tcp dpt:18432 to:172.22.0.2:18432` with source `0.0.0.0/0`. The audit also found the `0.0.0.0` bind comes from a fail-open default: the live `.env` does not set `BUZER_BIND_HOST` at all, so `docker-compose.yml`'s `${BUZER_BIND_HOST:-0.0.0.0}` default is what published the port publicly.

Separately, the product goal is to stop depending on the Tailscale client running on the Android phone at all ("fewer moving parts"), which requires the backend to be reachable over the ordinary internet regardless of the leak above.

This design closes the accidental exposure and replaces it with an intentional one.

## Goal

Make the backend safely reachable from the public internet without requiring Tailscale on the Android client, while closing the existing accidental exposure and keeping the security bar appropriate for what's behind it (a personal Todoist account, task data, and a live authenticated WhatsApp session).

## Scope

In scope:

- Rebinding the backend off the public interface.
- Tailscale Funnel as the public HTTPS entry point.
- Serving the backend under a `/buzer` path prefix, so the shared `ts.net` hostname stays free for other services on this VPS later.
- Minimal auth hardening appropriate for a now-public endpoint (proxy-aware rate limiting, constant-time token comparison, trimmed `/health` responses).
- Android client configuration to use the new HTTPS URL and drop the debug-only cleartext override.
- Recovery-access pre-flight so tightening network access doesn't risk locking out the operator.
- Documentation updates to keep `docs/notes/` accurate.

Out of scope:

- Token rotation. Deferred by explicit decision — the current token is a 256-bit hex token (`openssl rand -hex 32`-shaped; an earlier draft claimed 384 bits via `-base64 48`, corrected against the live `.env` on 2026-07-13) and isn't itself compromised; rotating it is orthogonal to this change.
- `fail2ban`. An earlier draft planned to enable it on the assumption it was already installed; a live check shows it is not (`dpkg -l fail2ban` → not found). With `ufw` accepting SSH only on `tailscale0`, it could only ever ban tailnet peers — a small operator-lockout risk in exchange for no public-surface gain. Dropped; revisit only if SSH exposure ever changes.
- Multi-user/per-device tokens, OAuth, or any move toward `public multi-user product behavior` (remains out of scope per `AGENTS.md`).
- A general-purpose reverse proxy (Caddy/nginx) or custom domain — considered and rejected, see Alternatives.
- A structural `DOCKER-USER` iptables safeguard against future accidental `0.0.0.0` publishes. Real hardening, but the direct fix (never bind `0.0.0.0`) resolves the current issue; noted as a possible future follow-up, not required here.

## Alternatives Considered

1. **Tailscale Funnel (chosen).** VPS stays a tailnet member exactly as today; Funnel exposes the backend on `https://oc-vps.taila50381.ts.net/buzer` with TLS auto-provisioned and terminated by `tailscaled`, and no client-side Tailscale install required. Smallest change from current state — no new services to run, no cert management, no domain to buy. Funnel also leaves room for more services on this VPS later: additional `--set-path` mounts on the same hostname (the chosen convention — hence the `/buzer` prefix rather than mounting at `/`), two more public ports (`8443`, `10000`), or per-service tailscale sidecar nodes with their own hostnames. Trade-offs accepted: the public hostname is locked to the tailnet's own `*.ts.net` domain, and Funnel bandwidth has non-configurable limits shared across the node (a non-issue at single-user scale).
2. **Own reverse proxy + domain (Caddy/nginx + Let's Encrypt).** Same end-user outcome, but adds a service to run, a domain to own, and a cert lifecycle to babysit. More independent of Tailscale as a vendor, but strictly more infrastructure in exchange for removing Tailscale from the phone. Rejected for now: the stated goal is fewer moving parts, and this adds more of them on the server to remove one from the client.
3. **Stay Tailscale-only, only fix the leak.** Rebind off `0.0.0.0` and stop there. Correctly closes the accidental exposure but leaves the phone dependent on the Tailscale client, which doesn't address the actual goal. Rejected as incomplete.
4. **Open SSH on the public interface as a recovery fallback.** Raised and explicitly rejected: reintroduces real attack surface to hedge against a problem (operator lockout) that has better answers — see Recovery Access below.

## Design

### 1. Network Exposure

- `docker-compose.yml`: stop publishing to `0.0.0.0`, and delete the fail-open `${BUZER_BIND_HOST:-0.0.0.0}` pattern entirely — the live `.env` never set the variable (the default *is* the leak), and a single env var can't express two binds anyway. Hardcode two `ports:` entries:
  - `127.0.0.1:18432:18432` — for `tailscaled`'s local Funnel forwarding and local health checks.
  - `100.105.180.34:18432:18432` (the VPS's `tailscale0` address) — preserves direct tailnet access (dev-machine `curl`) as a fallback path independent of Funnel/Tailscale's public-facing infra.
  - Nothing binds to `eth0` / the public IP after this change.
- VPS (sudo): persist `net.ipv4.ip_nonlocal_bind=1` via `/etc/sysctl.d/`. It is currently `0`, and without it the `100.105.180.34` bind fails if Docker starts the container before `tailscale0` has its address on boot — and Docker restart policies are not reliable for start-time port-binding failures, so a reboot could otherwise leave the backend down on *both* paths (the loopback mapping dies with the container). Exercised by the reboot test in Rollout.
- Tailscale admin console (one-time, out of repo), two toggles:
  - Enable HTTPS certificates for the tailnet — currently off (`tailscale status --json` shows `CertDomains: null`); Funnel refuses to run without it.
  - Add the `funnel` node attribute to the tailnet policy file.
- VPS: `sudo tailscale funnel --bg --set-path /buzer 18432` to persist the mapping (public `443`, path `/buzer` → local `18432`) across reboots and `tailscale` restarts.
- **Path prefix — the backend must serve its routes under `/buzer` itself.** `tailscaled` does not strip the mount path before proxying (tailscale/tailscale#6571, open since 2022): a request for `https://…/buzer/health` reaches the backend as `GET /buzer/health`. So all Fastify routes are registered under a `/buzer` prefix, giving one canonical URL shape on every path (Funnel, direct tailnet, healthcheck). Touchpoints: route registration (Fastify `prefix`), the `/health` exemption in the auth `preHandler` hook, the Docker healthcheck path in `docker-compose.yml`, the `/health` wait in `scripts/deploy-vps.sh`, and existing tests. This also sets the convention for any future service mounted on this hostname: each service owns its prefix.
- Result: `https://oc-vps.taila50381.ts.net/buzer` becomes the public URL; requests outside `/buzer` are rejected by `tailscaled` before reaching any service. The public interface is protected by the bind addresses themselves (no DNAT rule matches the public IP anymore), with `ufw`'s `default deny (incoming)` as backstop for the now-listener-less port. Note that tailnet traffic to `100.105.180.34:18432` still flows through Docker's FORWARD/DNAT path rather than `ufw`'s INPUT chain — expected, and unchanged from today.

### 2. Auth & Hardening

The token check becomes reachable by the whole internet instead of only tailnet peers, so:

- Add `@fastify/rate-limit` as a new backend dependency, registered globally (covers `/buzer/health` too — no reason to exempt a now-public endpoint from basic flood protection), with a modest per-IP threshold appropriate for a single-device client.
- **The limiter must be proxy-aware or it protects nothing.** Funnel traffic reaches Fastify as `tailscaled` → host `127.0.0.1` → `docker-proxy` → container, so the default rate-limit key (`request.ip`) would be the Docker bridge gateway for every public request — one shared bucket for the whole internet, and any scanner flood 429s the legitimate phone. Fix: set Fastify `trustProxy` scoped to `127.0.0.0/8` plus the compose network subnet (pin the subnet in `docker-compose.yml` rather than trusting all of `172.16.0.0/12`), so `request.ip` resolves to the client address `tailscaled` appends to `X-Forwarded-For`. Do not use `trustProxy: true` — that would let public clients spoof the leftmost `X-Forwarded-For` entry. Rollout verifies the header actually arrives through Funnel before relying on it.
- `backend/src/http/auth.ts`: replace the `!==` comparison in `requireBearerToken` with `crypto.timingSafeEqual` (guard for length mismatch first, since `timingSafeEqual` throws on unequal-length buffers).
- `backend/src/http/routes.ts`: unauthenticated `/health` responses shrink to `{ok, service, timestamp}` on **both** the 200 and the 503 path — today the failure path also leaks `checks` plus the raw database error `message`. The current `checks` detail (database, WhatsApp connection status) stays in the response only when a valid bearer token is present. The Docker healthcheck in `docker-compose.yml` only checks HTTP success (`r.ok`) and never parses the response body, so trimming is safe; the healthcheck's request path does change to `/buzer/health` for the prefix (§1).
- `fail2ban`: dropped — see Scope (not installed, and would only ever ban tailnet peers).
- Token rotation: explicitly deferred, see Scope.

### 3. Android Client

- `client/local.properties` (gitignored, per-developer): update `BUZER_BACKEND_BASE_URL` to `https://oc-vps.taila50381.ts.net/buzer`. `BuzerApiClient` builds request URLs by plain concatenation (`"$baseUrl/tasks/…"`), so the `/buzer` prefix flows through with no client code change.
- `client/androidApp/src/debug/AndroidManifest.xml`: remove `android:usesCleartextTraffic="true"`. Once the backend is HTTPS-only, debug builds should exercise the same network path a release build would — this also closes the gap the original design flagged (`docs/superpowers/specs/2026-05-28-android-vps-connected-enforcement-design.md`: "Hardcoded debug config is acceptable for dev mode but must not become production setup"). Accepted consequence: the raw `http://100.105.180.34:18432` fallback (§1) becomes unusable from debug builds and remains a dev-machine `curl` path only. Tailnet devices that want HTTPS can still use `https://oc-vps.taila50381.ts.net/buzer` directly — `tailscale serve` answers on the tailscale IP at `:443` with the same mount, no Funnel hop involved.
- No `BuzerApiClient` code changes required — `baseUrl` is already a plain constructor string.

### 4. Recovery Access (pre-flight, before touching network config)

- Tailscale node key expiry: disabled for `oc-vps` (done 2026-07-13).
- Contabo out-of-band console (VNC/rescue mode via the Contabo customer control panel): confirm it works, independent of SSH/Tailscale/ufw entirely. This is on the operator to verify directly in Contabo's dashboard — not reachable from inside the VPS itself.
- Explicitly not doing: opening SSH on the public interface as a fallback (see Alternatives #4).

### 5. Rollout & Verification

1. Confirm Contabo console access (recovery pre-flight, blocking — do not proceed until this is verified).
2. Tailscale admin console: enable HTTPS certificates for the tailnet and add the `funnel` node attribute (§1).
3. VPS (sudo): persist `net.ipv4.ip_nonlocal_bind=1` (§1).
4. Land and deploy the backend changes (§1 `/buzer` prefix + §2 hardening) while still on the old binds; verify `http://100.105.180.34:18432/buzer/health` from the dev machine.
5. Apply the compose rebind and `sudo tailscale funnel --bg --set-path /buzer 18432` (§1).
6. Verify, using the same off-tailnet method that found the original leak:
   - `curl http://167.86.68.104:18432/buzer/health` (and the old bare `/health`) must fail to connect (proves the leak is closed).
   - `curl https://oc-vps.taila50381.ts.net/buzer/health` from outside the tailnet must return 200 (proves Funnel + prefix work).
   - `curl http://100.105.180.34:18432/buzer/health` from the dev machine must return 200 (proves the tailnet fallback survived the rebind).
   - Confirm the backend sees the real client IP through Funnel (`X-Forwarded-For`, e.g. a temporary request-log line) — this is the assumption §2's rate limiting stands on.
   - Confirm a rate-limited burst gets 429s, and that the burst does *not* throttle a parallel request from a different source address (proves per-IP keying works behind the proxy).
   - Re-verify the `/health` shape with and without a token, on both the 200 and 503 paths.
7. Reboot test (sudo): `sudo reboot`, then confirm the container comes back with both binds and the Funnel mapping persisted (exercises the §1 boot-race mitigation).
8. Update Android config (§3) and run one real-device smoke test against the new URL (matches the existing manual real-device VPS smoke pattern in `docs/handoffs/`).
9. Update `docs/notes/vps-deployment.md` (hardcoded bind addresses, `BUZER_BIND_HOST` removed from the expected `.env` shape, `/buzer` prefix, sysctl step, new public URL, the captured `ufw`/`iptables` evidence from Context) and `docs/notes/github-actions-tailscale-deploy.md` (replace the "Tailscale-only" framing with the Funnel reality) to match.

## Testing

- Backend: existing Vitest suite (updated for the `/buzer` prefix) plus new coverage for `requireBearerToken`'s constant-time comparison and the trimmed unauthenticated `/health` shape on both the 200 and 503 paths.
- Manual: the outside-tailnet `curl` checks and the reboot test in Rollout are the actual proof this works; nothing else substitutes for them.
- Android: one real-device smoke test against the new HTTPS URL per `AGENTS.md` testing expectations for VPS-connected changes.

## Open Risks

- Funnel bandwidth limits are non-configurable (Tailscale-imposed) and shared across everything Funnel-exposed on this node. Not expected to matter at single-user scale; revisit if that changes.
- The public hostname is locked to the tailnet's `*.ts.net` domain for as long as Funnel is the chosen path — no custom domain without switching to Alternative 2 later.
- `tailscaled` does not strip mount paths (tailscale/tailscale#6571), so every future service mounted on this hostname must serve its routes under its own prefix. A service that can't cope with that (absolute redirects, hardcoded root paths) needs one of the fallbacks instead: public ports `8443`/`10000`, or its own sidecar tailscale node with its own hostname.
- The boot-order race between Docker and `tailscaled` for the `100.105.180.34` bind is mitigated by `ip_nonlocal_bind=1` and checked by the rollout reboot test. If that sysctl is ever reverted, a reboot can silently take the backend down on both paths again.
- The `DOCKER-USER` iptables bypass is closed here by removing the `0.0.0.0` bind, not by a structural rule. If a future container on this VPS publishes to `0.0.0.0` again, the same class of leak can recur silently. A `DOCKER-USER`-chain safeguard (per Docker's own documented `ufw` interaction) would close this structurally; deferred as a follow-up, not required for this change.
- This VPS also runs an unrelated project (`expense-bot`) sharing the same Docker daemon and host kernel. Out of scope here, but worth keeping in mind for blast-radius reasoning if either service's threat model changes.
