# VPS checkout move to ~/PROJECTS/buzer-app

Date: 2026-11-11

Task: user request (no plan file) — move the VPS repository checkout from
`/home/skclaw/buzer-app` to `/home/skclaw/PROJECTS/buzer-app` (the user's request said
`~/PROJECS`; `~/PROJECTS` already existed with `bazar-watcher` in it, so that was used).

## What changed

On the VPS (`oc-vps.taila50381.ts.net`, user `skclaw`):

- `mv ~/buzer-app ~/PROJECTS/buzer-app` (includes the gitignored `.env` and `backup.log`).
- Crontab backup line rewritten to the new path. Previous crontab saved to
  `~/crontab.bak.buzer-move`.
- Running container was **not** restarted: it uses only named volumes, and the folder basename
  is still `buzer-app`, so the Compose project name and the volumes
  (`buzer-app_buzer-data`, `buzer-app_buzer-whatsapp`) are unchanged.

In the repo:

- `scripts/deploy-vps.sh`: default `BUZER_REPO_DIR` is now `/home/skclaw/PROJECTS/buzer-app`.
- Current-state docs updated: `docs/notes/vps-deployment.md`,
  `docs/notes/github-actions-tailscale-deploy.md`, `docs/runbooks/sentry-setup.md`,
  `docs/runbooks/production-rollout.md`. Historical handoffs/plans left as written.

## Known caveats

- `docker compose ls` still shows the old config path
  (`/home/skclaw/buzer-app/docker-compose.yml`) because that is a label on the existing
  container. It updates the next time the container is recreated (next deploy that changes the
  image). Compose commands run from the new folder work, since they match on the project name.
- The VPS checkout is at `447af22`, whose `deploy-vps.sh` still defaults to the old path. Until
  the script fix is on `origin/master`, deploy with:
  `BUZER_REPO_DIR=/home/skclaw/PROJECTS/buzer-app ~/PROJECTS/buzer-app/scripts/deploy-vps.sh`.

## Verification

- Command: `ssh oc-vps.taila50381.ts.net 'cd ~/PROJECTS/buzer-app && docker compose ps && curl --fail -sS http://127.0.0.1:18432/health'`
- Result: PASS
- Evidence: `buzer-backend` Up 2 weeks (healthy); `{"ok":true,"service":"buzer-backend",...}`

- Command: `ssh oc-vps.taila50381.ts.net 'crontab -l; docker volume ls | grep buzer'`
- Result: PASS
- Evidence: cron points at `/home/skclaw/PROJECTS/buzer-app`; both named volumes present.

- Deploy script run from the new location: SKIPPED (would deploy newer master than what is
  running; not requested).

## Next step

Commit and push the script/doc changes so the next normal deploy uses the new default path.
