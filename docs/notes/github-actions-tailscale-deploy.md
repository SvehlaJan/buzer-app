# Future GitHub Actions Tailscale Deploy

Date: 2026-06-09

The VPS is intentionally reachable only inside the Tailscale network. A normal GitHub-hosted Actions runner cannot SSH to `oc-vps.taila50381.ts.net` unless the workflow first joins the tailnet.

For MVP, deployment remains manual through:

```bash
ssh oc-vps.taila50381.ts.net
/home/skclaw/buzer-app/scripts/deploy-vps.sh
```

## Future Option A: Tailscale In GitHub Actions

Add a workflow that:

1. checks out the repo
2. uses Tailscale's GitHub Action to join the tailnet with an OAuth client or ephemeral auth key
3. SSHes to `oc-vps.taila50381.ts.net`
4. runs `/home/skclaw/buzer-app/scripts/deploy-vps.sh`

Required secrets would include:

```text
TS_OAUTH_CLIENT_ID
TS_OAUTH_SECRET
VPS_SSH_KEY
VPS_KNOWN_HOSTS
```

Use Tailscale ACLs to restrict the GitHub Actions identity to SSH only into this VPS.

## Future Option B: Self-Hosted Runner

Run a GitHub self-hosted runner on the VPS or another tailnet machine. This avoids joining GitHub-hosted runners to the tailnet, but adds runner lifecycle and security maintenance on the VPS.

## Current Decision

Do not add an active `.github/workflows/deploy-vps.yml` for MVP. Keep the repo prepared with a deploy script, Docker Compose file, health endpoint, and runbook.
