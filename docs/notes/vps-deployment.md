# VPS Deployment Notes

Date: 2026-05-31

## Prerequisites

- VPS: Ubuntu 24.04 at `vmi3085238` (`100.105.180.34` on Tailscale)
- Docker 28 + docker-compose v2
- Tailscale running on both local and VPS
- Todoist personal access token
- WhatsApp chat ID for escalation messages
- Random secure token for Android API auth

## Deployment Steps

### 1. Prepare Credentials

Generate `.env` from `.env.example`:

```bash
cp .env.example .env
```

Fill in the required values:

```bash
TODOIST_API_TOKEN=<your-todoist-token>
TODOIST_USER_ID=<your-todoist-user-id>
ANDROID_API_TOKEN=<generate-random-secure-string>
WHATSAPP_RECIPIENT_ID=<chat-id>
WHATSAPP_DRY_RUN=false
```

**Do not commit `.env` to git.**

### 2. Copy to VPS

```bash
scp -r . skclaw@vmi3085238:~/buzer-app/
```

Or via Tailscale:

```bash
scp -r . skclaw@100.105.180.34:~/buzer-app/
```

### 3. SSH to VPS

```bash
ssh skclaw@100.105.180.34
cd ~/buzer-app
```

### 4. Start Backend

```bash
docker compose up -d
```

Verify:

```bash
docker compose ps
docker compose logs -f backend
```

### 5. Test Connectivity

From local machine via Tailscale:

```bash
# Health check (unauth)
curl http://100.105.180.34:3000/

# Get task snapshot (requires auth)
curl -H "Authorization: Bearer $ANDROID_API_TOKEN" http://100.105.180.34:3000/tasks/snapshot
```

Expected response:

```json
{
  "scheduleVersion": 1,
  "serverTime": "2026-05-31T12:34:56Z",
  "tasks": [],
  "health": []
}
```

### 6. Configure Domain (Later)

When ready to route `oujee.eu` through Cloudflare:

- Add DNS A record pointing to `167.86.68.104` (VPS public IP)
- Wire through Caddy reverse proxy on VPS
- Update Android app config to use domain instead of Tailscale IP

## Monitoring

View logs:

```bash
docker compose logs -f backend
```

Check volume usage:

```bash
docker volume ls
docker volume inspect buzer-data
```

## Troubleshooting

### Container fails to start

```bash
docker compose logs backend
```

Check `.env` is present and all required vars are set.

### Todoist sync errors

Enable logging in `docker compose logs` and check:
- `TODOIST_API_TOKEN` is a valid personal access token
- `TODOIST_USER_ID` matches token owner

### WhatsApp escalation not working

If `WHATSAPP_DRY_RUN=false`:
- First run requires QR code scan for `whatsapp-web.js` authentication
- Check logs for QR output or watch `whatsapp-sessions/` volume for session file

### Port already in use

If 3000 is taken, change in `.env`:

```bash
PORT=3001
```

And update `docker-compose.yml` ports if needed.

## Stopping Backend

```bash
docker compose down
```

To also remove volumes (deletes data):

```bash
docker compose down -v
```
