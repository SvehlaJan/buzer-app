# VPS Deployment Runbook

Date: 2026-06-09

The MVP backend deploys manually on the Tailscale-only VPS. The VPS is reachable from the tailnet as:

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

The backend is intentionally available only on the Tailscale/private network for MVP.

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

# Compose/deploy settings
BUZER_HOST_PORT=18432
BUZER_CONTAINER_PORT=18432
BUZER_BIND_HOST=0.0.0.0
```

Use a long random Android API token:

```bash
openssl rand -base64 48
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

From a Tailscale-connected machine:

```bash
curl --fail http://oc-vps.taila50381.ts.net:18432/health
```

Expected shape:

```json
{
  "ok": true,
  "service": "buzer-backend",
  "timestamp": "2026-06-09T20:00:00.000Z",
  "checks": {
    "database": "ok"
  }
}
```

`/health` is unauthenticated. Application API routes remain protected by bearer auth.

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
