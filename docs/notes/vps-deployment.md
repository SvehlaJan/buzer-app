# VPS Deployment Runbook

Date: 2026-05-29

This runbook deploys the Buzer backend as the first service on a VPS. It assumes:

- Ubuntu or Debian-style Linux.
- A DNS name such as `buzer.example.com` points at the VPS.
- Docker Compose runs the backend container.
- Caddy terminates HTTPS and reverse-proxies to the backend on `127.0.0.1:3001`.
- Persistent state lives outside the repo in `/srv/buzer/data`.

## Repository Files

- `backend/Dockerfile`: production image for the Node.js backend and Chromium.
- `deploy/docker-compose.yml`: backend service definition.
- `deploy/Caddyfile.example`: minimal HTTPS reverse proxy config.
- `scripts/deploy-vps.sh`: idempotent deploy command for manual and GitHub-triggered deploys.
- `scripts/backup-sqlite.sh`: SQLite backup helper.
- `.github/workflows/deploy-vps.yml`: push-to-deploy workflow over SSH.

## VPS Directory Layout

```text
/srv/buzer/
  repo/                 # git clone
  data/
    buzer.sqlite
    whatsapp-sessions/
    backups/
  .env                  # production secrets, never committed
```

## One-Time VPS Setup

Run these as root or through sudo-capable automation.

```bash
apt-get update
apt-get install -y ca-certificates curl git ufw sqlite3
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin caddy
```

Create the deploy user and folders:

```bash
adduser --disabled-password --gecos "" buzer
usermod -aG docker buzer
mkdir -p /srv/buzer/data/backups /srv/buzer/data/whatsapp-sessions
chown -R buzer:buzer /srv/buzer
```

Firewall:

```bash
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
```

Clone the repository as `buzer`:

```bash
sudo -iu buzer
git clone <repository-url> /srv/buzer/repo
```

Create `/srv/buzer/.env`:

```env
NODE_ENV=production
PORT=3000
DATABASE_PATH=/data/buzer.sqlite
ANDROID_API_TOKEN=replace-with-long-random-token
TODOIST_API_TOKEN=replace-with-todoist-token
TODOIST_USER_ID=replace-with-current-user-id
WHATSAPP_RECIPIENT_ID=replace-with-chat-id
WHATSAPP_DRY_RUN=true
WHATSAPP_SESSION_PATH=/data/whatsapp-sessions
WHATSAPP_CLIENT_ID=buzer
```

Use a long random Android API token:

```bash
openssl rand -base64 48
```

## Caddy

Copy `deploy/Caddyfile.example` to `/etc/caddy/Caddyfile`, replace `buzer.example.com`, then reload:

```bash
sudo cp /srv/buzer/repo/deploy/Caddyfile.example /etc/caddy/Caddyfile
sudo editor /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Expected shape:

```caddyfile
buzer.example.com {
	reverse_proxy 127.0.0.1:3001
}
```

## First Manual Deploy

Run as `buzer`:

```bash
/srv/buzer/repo/scripts/deploy-vps.sh
```

Check health:

```bash
curl --fail https://buzer.example.com/health
docker compose -f /srv/buzer/repo/deploy/docker-compose.yml ps
docker compose -f /srv/buzer/repo/deploy/docker-compose.yml logs --tail=120 buzer-backend
```

Keep `WHATSAPP_DRY_RUN=true` for the first deploy. Verify Todoist sync and Android API calls before enabling live WhatsApp sends.

## Enabling WhatsApp

Set this in `/srv/buzer/.env`:

```env
WHATSAPP_DRY_RUN=false
```

Redeploy:

```bash
/srv/buzer/repo/scripts/deploy-vps.sh
```

Then watch logs for the QR code:

```bash
docker compose -f /srv/buzer/repo/deploy/docker-compose.yml logs -f buzer-backend
```

Scan the QR once. The session is persisted in `/srv/buzer/data/whatsapp-sessions`, so normal restarts should not require a new scan.

## SQLite Backups

Install a daily cron job for the `buzer` user:

```bash
crontab -e
```

Add:

```cron
17 3 * * * /srv/buzer/repo/scripts/backup-sqlite.sh >> /srv/buzer/data/backups/backup.log 2>&1
```

The script keeps 30 days of local backups by default. Use `BUZER_BACKUP_RETENTION_DAYS` to adjust retention.

## GitHub Push-To-Deploy

The workflow `.github/workflows/deploy-vps.yml` redeploys on pushes to `master` and can also be run manually with `workflow_dispatch`.

Create a dedicated deploy SSH key on your local machine:

```bash
ssh-keygen -t ed25519 -C "github-actions-buzer-vps" -f ./buzer_github_actions_ed25519
```

Add the public key to `/home/buzer/.ssh/authorized_keys` on the VPS.

Add these GitHub repository secrets:

```text
VPS_HOST          # hostname or IP
VPS_USER          # buzer
VPS_PORT          # optional, defaults to 22 if empty
VPS_SSH_KEY       # private key contents from buzer_github_actions_ed25519
VPS_KNOWN_HOSTS   # output of ssh-keyscan for the VPS after checking fingerprint
```

Generate known hosts:

```bash
ssh-keyscan -p 22 buzer.example.com
```

For better security, compare the fingerprint against the VPS directly before saving it in GitHub:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

After secrets are configured, push to `master` or run the workflow manually from GitHub Actions. The workflow SSHes into the VPS and runs:

```bash
BUZER_DEPLOY_BRANCH=master /srv/buzer/repo/scripts/deploy-vps.sh
```

## Claude Code Prompt For The VPS

Use this prompt on the VPS after cloning the repository:

```text
Read /srv/buzer/repo/AGENTS.md and /srv/buzer/repo/docs/notes/vps-deployment.md.

Set up this VPS for the Buzer backend as the first service on the machine:
- install Docker Engine, Docker Compose plugin, Caddy, sqlite3, git, curl, and firewall basics if missing
- create or verify the /srv/buzer directory layout
- create the buzer Linux user if missing and ensure it can run Docker
- help me create /srv/buzer/.env from backend/.env.example without committing secrets
- configure Caddy for my Buzer hostname
- run /srv/buzer/repo/scripts/deploy-vps.sh
- verify https://<my-hostname>/health
- configure the daily SQLite backup cron
- tell me exactly what GitHub secrets I need to add for .github/workflows/deploy-vps.yml

Do not print secrets back to me. Do not delete existing /srv/buzer/data contents. If a command would overwrite existing service state, stop and ask first.
```

## Rollback

Manual rollback is a git checkout plus redeploy:

```bash
cd /srv/buzer/repo
git fetch origin
git checkout <known-good-commit>
docker compose -f deploy/docker-compose.yml up -d --build --remove-orphans
curl --fail http://127.0.0.1:3001/health
```

After rollback, switch back to `master` before the next normal deploy:

```bash
git checkout master
```
