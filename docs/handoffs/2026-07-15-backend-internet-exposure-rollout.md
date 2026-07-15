# Handoff: Backend Internet Exposure — VPS Rollout Complete

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-13-backend-internet-exposure.md` (Appendix: Operator Rollout Runbook)
Related: `docs/handoffs/2026-07-13-backend-internet-exposure-implementation.md` (local repo work), `docs/decisions/2026-07-15-funnel-strips-path-prefix.md` (mid-rollout correction)

## Status

The full Operator Rollout Runbook is complete. The backend is live on the public internet via Tailscale Funnel at `https://oc-vps.taila50381.ts.net/buzer`, with the original accidental `0.0.0.0` exposure closed. A real bug was found and fixed mid-rollout (see below) — the system is currently verified healthy and correct.

Split of responsibility for this rollout (agreed with the user mid-session): sudo commands and Tailscale admin console configuration were performed by the user directly; SSH access and running the deploy script were delegated to the executing agent.

## Runbook steps and outcomes

1. **Contabo console access** — confirmed by user (recovery pre-flight, blocking step).
2. **Tailscale admin console** — user enabled HTTPS certificates (DNS page) and the `funnel` node attribute (via the console's "enable Funnel" toggle, which grants `"target": ["autogroup:member"]` rather than the design doc's narrower `"target": ["oc-vps"]` example — broader in scope but a normal, intended outcome of that UI control; not acted on further).
3. **VPS sysctl** (`net.ipv4.ip_nonlocal_bind=1`) — executed by user.
4. **Subnet collision check** — executed by agent via SSH: `docker network ls` showed no existing use of `172.28.0.0/16`. Clean.
5. **Deploy** — executed by agent via SSH (`scripts/deploy-vps.sh`), twice (see "Incident" below for why twice).
6. **Funnel command** (`sudo tailscale funnel --bg --set-path /buzer 18432`) — executed by user.
7. **Verification** — executed by agent: bind addresses (`ss -tln`), old-leak closure (direct off-tailnet curl to the old public IP:port — connection timeout, confirmed closed), `/health` on loopback and tailnet-IP binds, and — after the incident fix — the actual public Funnel URL for both `/health` and a real authenticated route (`/tasks/snapshot`).
8. **Reboot test** — executed by user (`sudo reboot`); agent independently verified afterward: VPS uptime ~2 min, container auto-restarted healthy, both binds correct with no `0.0.0.0`.
9. **Android client** — user updated `client/local.properties`, rebuilt, and ran a real-device smoke test (not independently verifiable by the agent; taken on the user's word).
10. **Docs** — verified and further corrected after the incident (see below).

## Incident: Funnel strips its path mount (design assumption was wrong)

Full writeup: `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`.

Summary: the original design (`docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` §1) assumed Tailscale Funnel does not strip its `--set-path /buzer` mount before forwarding, so the backend was built to serve all routes under a self-registered `/buzer` prefix. After the first live deploy, the public Funnel URL 404'd on every real path. A deliberately-doubled-path test (`.../buzer/buzer/health` → 200) proved Funnel *does* strip the mount — the opposite of the assumption.

Fixed by reverting the backend to serve plain unprefixed routes (commit `773352a`, pushed and redeployed as commit `b6b87e6`). While fixing this, a second, unrelated-looking issue was found and fixed in the same commit: `@fastify/rate-limit` requires routes to be registered via Fastify's `app.register()` plugin mechanism (even with no path prefix) to track them correctly — an initial attempt that called `registerRoutes(app, options)` as a direct synchronous function silently broke rate limiting entirely and changed 404 semantics for unauthenticated requests. Both the break and the fix were verified empirically (a throwaway diagnostic test, plus an independent reviewer who reproduced the exact bug via negative control before confirming the fix).

The second deploy attempt's own health-check loop reported failure (see next section) but the underlying deploy succeeded; a third round of verification (this time including the actual public Funnel URL, which hadn't been reachable during the first deploy attempt) confirmed everything working end-to-end.

## Two deploy attempts — both had misleading "failure" signals that were investigated, not taken at face value

**First attempt:** SSH connection dropped mid-stream while watching Docker build output (exit 255, "Broken pipe"). The remote `deploy-vps.sh` process was not tied to that SSH session's lifetime and completed successfully regardless — confirmed by checking the container's actual `Created` timestamp against the build log's start time, and independently re-verifying health/bind state from scratch afterward.

**Second attempt** (redeploying the Funnel-prefix fix): the deploy script's own health-check loop exhausted its 20-attempt/~60-90s budget and exited 1 ("Buzer backend did not become healthy"). Cause: the WhatsApp client had to clean up 3 stale Chromium profile lock files (left over from the first attempt's abrupt SSH disconnect) and fully re-authenticate before the app finished starting, which took longer than the script's timeout budget. The container came up healthy shortly after the script gave up; verified via `docker ps` (`Up ... (healthy)`) and direct curl checks immediately after.

**Known minor gap, not fixed:** `scripts/deploy-vps.sh`'s health-check budget (20 × 3s ≈ 60-90s total) can be too tight when WhatsApp needs to fully re-authenticate on startup, causing a successful deploy to be misreported as failed. Not fixed in this session (flagged to the user, they chose to wrap up rather than pursue it) — purely a script-robustness/reporting issue, not a correctness or security issue. Whoever picks this up next should widen the retry budget or add a longer initial grace period before the first health-check attempt.

## Final Verification

- Command: `ss -tln | grep 18432` (on VPS)
- Result: PASS — only `100.105.180.34:18432` and `127.0.0.1:18432` listening, no `0.0.0.0`.

- Command: `curl` to the old public IP:port from off-tailnet
- Result: PASS — connection timeout (leak closed).

- Command: `curl https://oc-vps.taila50381.ts.net/buzer/health`
- Result: PASS — 200, correct trimmed unauthenticated shape.

- Command: `curl https://oc-vps.taila50381.ts.net/buzer/tasks/snapshot` (no auth header)
- Result: PASS — 401 `{"code":"unauthorized",...}`, proving a real application route (not just health) is reachable and correctly processed through the public URL.

- Command: `docker ps --filter name=buzer-backend`
- Result: PASS — `Up ... (healthy)`.

- Reboot survival: PASS (see step 8 above).

## Known Failures Or Skipped Checks

- The rate-limit burst check and the "off-tailnet burst vs. dev-machine single request" X-Forwarded-For reality check from the design doc's §5 verification list were not performed against the live Funnel path (only unit-tested locally, pre-deploy, in the original plan's Task 4). Low priority to close given the per-IP keying logic has thorough local test coverage and the trustProxy subnet is confirmed correctly pinned.
- The deploy script's health-check timeout gap (above) is unresolved.

## Next Recommended Step

None blocking. Optional follow-ups, in rough priority order: widen `scripts/deploy-vps.sh`'s health-check budget; add the `NetworkSecurityPolicy` instrumented test flagged in Task 7b's code review; consider tightening the Tailscale ACL `funnel` grant from `autogroup:member` to `oc-vps`-only if narrower scope is wanted later.
