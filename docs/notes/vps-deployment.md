# VPS Deployment Runbook

Date: 2026-06-09

The backend deploys manually on the VPS. The public API entry point is Tailscale Funnel at `https://oc-vps.taila50381.ts.net/buzer`; the VPS itself is reachable from the tailnet as:

```text
oc-vps.taila50381.ts.net
```

Current live deployment shape:

- repository checkout: `/home/skclaw/buzer-app`
- Docker Compose project: `buzer-app`
- service/container: `backend` / `buzer-backend`
- host port: `18432`
- container port: `18432`
- SQLite data volume: `buzer-app_buzer-data`
- WhatsApp session volume: `buzer-app_buzer-whatsapp`

The backend is intentionally exposed to the public internet **only** through Tailscale Funnel (`https://oc-vps.taila50381.ts.net/buzer`, TLS terminated by `tailscaled`). Docker binds the backend to `127.0.0.1:18432` (Funnel forwarding target) and `100.105.180.34:18432` (direct tailnet fallback). Nothing listens on the public interface; `ufw` stays `default deny (incoming)`. See `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` for the full design, including why Docker-published `0.0.0.0` ports bypass `ufw` (verified: `sudo iptables -t nat -L DOCKER -n` shows a DNAT rule for any destination when a port is published to `0.0.0.0`).

## Repository Files

- `Dockerfile`: production backend image with Node 22 and Chromium for `whatsapp-web.js`.
- `docker-compose.yml`: current VPS service definition with healthcheck and named volumes.
- `scripts/deploy-vps.sh`: manual deploy command for the VPS.
- `scripts/backup-sqlite.sh`: SQLite backup helper that runs inside the backend container.
- `docs/notes/github-actions-tailscale-deploy.md`: future push-to-deploy notes. No active GitHub Actions deploy workflow is installed for MVP.

## Environment

Secrets live in `/home/skclaw/buzer-app/.env` on the VPS. Do not commit it.

Expected shape:

```env
NODE_ENV=production
PORT=18432
DATABASE_PATH=./data/buzer.sqlite
ANDROID_API_TOKEN=replace-with-long-random-token
TODOIST_API_TOKEN=replace-with-todoist-token
TODOIST_USER_ID=replace-with-current-user-id
WHATSAPP_RECIPIENT_ID=replace-with-chat-id
WHATSAPP_DRY_RUN=true
WHATSAPP_SESSION_PATH=./whatsapp-sessions
WHATSAPP_CLIENT_ID=buzer
```

Use a long random Android API token:

```bash
openssl rand -hex 32
```

## Manual Deploy

Run from the VPS:

```bash
ssh oc-vps.taila50381.ts.net
/home/skclaw/buzer-app/scripts/deploy-vps.sh
```

The deploy script:

- fetches `origin/master`
- resets the local checkout to `origin/master`
- rebuilds and restarts `buzer-backend`
- waits for `/health`
- prints recent backend logs if health does not pass

This script is allowed to overwrite local repository changes in `/home/skclaw/buzer-app`. Runtime data stays in Docker named volumes unless the volumes are explicitly removed.

## Health Checks

From the VPS:

```bash
curl --fail http://127.0.0.1:18432/health
```

From a Tailscale-connected machine (direct fallback, bypasses Funnel):

```bash
curl --fail http://100.105.180.34:18432/health
```

From anywhere (public path through Funnel — Funnel strips its own `/buzer` mount before forwarding, so the backend never sees it; see "Internet Exposure" below):

```bash
curl --fail https://oc-vps.taila50381.ts.net/buzer/health
```

Unauthenticated expected shape (trimmed on purpose — no `checks` detail for the public internet):

```json
{
  "ok": true,
  "service": "buzer-backend",
  "timestamp": "2026-07-13T20:00:00.000Z"
}
```

With a valid `Authorization: Bearer <ANDROID_API_TOKEN>` header the response additionally contains `checks` (database, WhatsApp connection status).

`/health` is unauthenticated but trimmed; all other routes require bearer auth. A global per-IP rate limit (60 req/min) applies behind `trustProxy` — the compose network subnet is pinned to `172.28.0.0/16` to match, so do not change one without the other.

## Operational Commands

Run from `/home/skclaw/buzer-app` on the VPS:

```bash
docker compose ps
docker compose logs --tail=120 backend
docker compose logs -f backend
```

Restart without rebuilding:

```bash
docker compose up -d
```

Rebuild manually:

```bash
docker compose up -d --build --remove-orphans
```

## SQLite Backups

Backups are written inside the existing `buzer-app_buzer-data` named volume under `./data/backups`.

Run manually:

```bash
cd /home/skclaw/buzer-app
scripts/backup-sqlite.sh
```

Install a daily cron job for the VPS user:

```bash
crontab -e
```

Add:

```cron
17 3 * * * cd /home/skclaw/buzer-app && scripts/backup-sqlite.sh >> /home/skclaw/buzer-app/backup.log 2>&1
```

The script keeps 30 days of backups by default. Use `BUZER_BACKUP_RETENTION_DAYS` to adjust retention.

## Enabling WhatsApp

Keep `WHATSAPP_DRY_RUN=true` until the backend and Android smoke tests pass.

To enable live WhatsApp:

```env
WHATSAPP_DRY_RUN=false
```

Then redeploy and watch logs for the QR code:

```bash
/home/skclaw/buzer-app/scripts/deploy-vps.sh
cd /home/skclaw/buzer-app
docker compose logs -f backend
```

Scan the QR once. The session is persisted in `buzer-app_buzer-whatsapp`, so normal restarts should not require a new scan.

## Rollback

Manual rollback is a git checkout plus redeploy:

```bash
cd /home/skclaw/buzer-app
git fetch origin
git checkout <known-good-commit>
docker compose up -d --build --remove-orphans
curl --fail http://127.0.0.1:18432/health
```

After rollback, switch back to `master` before the next normal deploy:

```bash
git checkout master
```

## Internet Exposure (Tailscale Funnel)

One-time host setup (already applied; recorded for rebuilds):

```bash
# Boot-order safety: Docker may start the container before tailscale0 has its
# address; without this, the 100.105.180.34 port bind fails at boot and Docker
# restart policies do not reliably retry start-time bind failures.
echo 'net.ipv4.ip_nonlocal_bind=1' | sudo tee /etc/sysctl.d/99-tailscale-bind.conf
sudo sysctl -p /etc/sysctl.d/99-tailscale-bind.conf

# Funnel mapping (persists across reboots in tailscaled state):
sudo tailscale funnel --bg --set-path /buzer 18432
```

Tailnet prerequisites (admin console, one-time): MagicDNS enabled, HTTPS certificates enabled, `funnel` node attribute on `oc-vps` in the tailnet policy file.

**Correction (2026-07-15, superseding the original design assumption):** the original design assumed Funnel does *not* strip its `--set-path` mount before forwarding (citing tailscale/tailscale#6571) and had the backend serve all routes under a self-imposed `/buzer` prefix to compensate. Live verification after the first real deploy showed the opposite: **Funnel does strip the mount path.** A request to `https://oc-vps.taila50381.ts.net/buzer/health` arrives at the backend as `GET /health`, not `GET /buzer/health` — confirmed by testing a deliberately-doubled path (`.../buzer/buzer/health` returned 200 — one `/buzer` was stripped by Funnel, the other landed on the backend as a literal, matching `/buzer/health` route that existed at the time). The backend was reverted to serve plain, unprefixed routes; `/buzer` now exists *only* as Funnel's own `--set-path` mapping, external to the backend entirely. See `docs/decisions/2026-07-15-funnel-strips-path-prefix.md` for the full incident writeup. The "reserve `/buzer` on this hostname for this service" goal is unaffected — it's just enforced one layer up, at the Funnel mapping itself, not in application code. Any additional service exposed on this hostname still needs its own `--set-path` mount (and, like this one, should serve plain unprefixed routes internally), or use public ports 8443/10000, or run its own tailscale sidecar node.

`fail2ban` is deliberately not installed: SSH is accepted only on `tailscale0`, so it could only ban tailnet peers.
