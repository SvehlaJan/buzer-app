# 2026-06-09 VPS Manual Deployment

## Task / Plan

- Plan: `docs/superpowers/plans/2026-06-09-vps-deployment-port.md`
- Result: all plan tasks completed.

## Files Changed

- `.dockerignore`
- `Dockerfile`
- `backend/package.json`
- `backend/src/http/routes.ts`
- `backend/test/http/routes.test.ts`
- `docker-compose.yml`
- `docs/notes/github-actions-tailscale-deploy.md`
- `docs/notes/vps-deployment.md`
- `docs/superpowers/plans/2026-06-09-vps-deployment-port.md`
- `scripts/backup-sqlite.sh`
- `scripts/deploy-vps.sh`

## Commands Run

- `npm test` from `backend`
- `npm run typecheck` from `backend`
- `npm run build` from `backend`
- `bash -n scripts/deploy-vps.sh scripts/backup-sqlite.sh`
- `git diff --check`
- `git commit -m "chore: prepare manual VPS deployment"`
- `git push origin master`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && git fetch origin master && git checkout -B master origin/master && /home/skclaw/buzer-app/scripts/deploy-vps.sh'`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && git rev-parse HEAD && git status --short && curl --fail --silent --show-error http://127.0.0.1:18432/health && docker ps --filter name=buzer-backend --format "{{.Names}} {{.Status}} {{.Ports}}"'`
- `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && scripts/backup-sqlite.sh'`

## Verification

- Command: `npm test`
- Result: PASS
- Evidence: 13 test files and 36 tests passed.

- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript completed with no reported errors.

- Command: `npm run build`
- Result: PASS
- Evidence: backend build completed and copied `src/db/schema.sql` into `dist/db/schema.sql`.

- Command: `bash -n scripts/deploy-vps.sh scripts/backup-sqlite.sh`
- Result: PASS
- Evidence: shell syntax check completed with no output.

- Command: `git diff --check`
- Result: PASS
- Evidence: whitespace check completed with no output.

- Command: `/home/skclaw/buzer-app/scripts/deploy-vps.sh` on VPS after resetting to `origin/master`
- Result: PASS
- Evidence: Docker rebuilt `buzer-app-backend`, recreated `buzer-backend`, and reported `Buzer backend is healthy at http://127.0.0.1:18432/health`.

- Command: `curl --fail --silent --show-error http://127.0.0.1:18432/health` on VPS
- Result: PASS
- Evidence: returned `{"ok":true,"service":"buzer-backend","checks":{"database":"ok"}}`.

- Command: `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- Result: PASS
- Evidence: returned `{"ok":true,"service":"buzer-backend","checks":{"database":"ok"}}`.

- Command: `docker ps --filter name=buzer-backend --format "{{.Names}} {{.Status}} {{.Ports}}"` on VPS
- Result: PASS
- Evidence: `buzer-backend Up About a minute (healthy) 0.0.0.0:18432->18432/tcp`.

- Command: `scripts/backup-sqlite.sh` on VPS
- Result: PASS
- Evidence: wrote `./data/backups/buzer-20260609T204755Z.sqlite` inside the data volume.

## Manual Verification

- Confirmed pre-deploy `/health` on `http://oc-vps.taila50381.ts.net:18432/health` returned `401`.
- Confirmed post-deploy `/health` is unauthenticated and returns database status.
- Confirmed the VPS checkout is on commit `09de96c9859ce8097c5ca579375e7716da0212a8`.

## Known Failures Or Skipped Checks

- Did not enable live WhatsApp. `WHATSAPP_DRY_RUN` should remain true until Android/backend smoke tests pass.
- Did not create an active GitHub Actions deploy workflow because the MVP deployment remains manual behind Tailscale.

## Assumptions

- Existing Docker named volumes `buzer-app_buzer-data` and `buzer-app_buzer-whatsapp` are the source of truth for runtime data and must be preserved.
- Host port `18432` remains the backend port for MVP.
- `/home/skclaw/buzer-app` may be reset to `origin/master` by the deploy script.

## Next Recommended Step

Run an Android client smoke test against `http://oc-vps.taila50381.ts.net:18432` with the current VPS Android API token, then decide whether to enable live WhatsApp or keep dry-run mode longer.
