# 2026-06-09 WhatsApp Readiness Health

## Task / Plan

- Task: add minimal WhatsApp readiness observability, redeploy, and verify the scanned WhatsApp Web session.
- Related handoff: `docs/handoffs/2026-06-09-whatsapp-session-verification.md`
- Result: completed. `/health` now reports WhatsApp readiness and the VPS reports `connected=true`, `detail=ready`.

## Files Changed

- `backend/src/accountability/whatsappWebSender.ts`
- `backend/src/http/routes.ts`
- `backend/src/services/escalationService.ts`
- `backend/test/accountability/whatsappWebSender.test.ts`
- `backend/test/http/routes.test.ts`
- `scripts/deploy-vps.sh`

## Commands Run

- `npx ctx7@latest library "Fastify" "async route handler response object health endpoint Node TypeScript"`
- `npx ctx7@latest docs "/fastify/fastify" "async route handler reply status send inject health endpoint TypeScript"`
- `npm test -- test/http/routes.test.ts`
- `npm test -- test/accountability/whatsappWebSender.test.ts`
- `npm test`
- `npm run typecheck`
- `npm run build`
- `git diff --check`
- `bash -n scripts/deploy-vps.sh scripts/backup-sqlite.sh`
- `git commit -m "feat: expose WhatsApp readiness in health"`
- `git commit -m "fix: clear stale WhatsApp Chromium locks"`
- `git commit -m "fix: use compose service in deploy logs"`
- `git push origin master`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && /home/skclaw/buzer-app/scripts/deploy-vps.sh'`
- `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && git rev-parse HEAD && git status --short && docker compose ps'`

## Verification

- Command: `npm test -- test/http/routes.test.ts`
- Result: PASS
- Evidence: 7 HTTP route tests passed after first observing the new WhatsApp health assertion fail.

- Command: `npm test -- test/accountability/whatsappWebSender.test.ts`
- Result: PASS
- Evidence: regression test verifies stale `SingletonLock`, `SingletonSocket`, and `SingletonCookie` files are removed while unrelated session data remains.

- Command: `npm test`
- Result: PASS
- Evidence: 14 test files and 38 tests passed.

- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript completed with no reported errors.

- Command: `npm run build`
- Result: PASS
- Evidence: backend production build completed and copied `schema.sql` into `dist/db/schema.sql`.

- Command: `git diff --check`
- Result: PASS
- Evidence: whitespace check completed with no output.

- Command: `bash -n scripts/deploy-vps.sh scripts/backup-sqlite.sh`
- Result: PASS
- Evidence: shell syntax check completed with no output.

- Command: VPS deploy script
- Result: PASS after stale-lock hardening
- Evidence: final deploy pulled commit `883579d`, rebuilt from cache, and reported `Buzer backend is healthy at http://127.0.0.1:18432/health`.

- Command: `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- Result: PASS
- Evidence: returned `{"ok":true,"service":"buzer-backend","checks":{"database":"ok","whatsapp":{"connected":true,"detail":"ready"}}}`.

- Command: VPS container status
- Result: PASS
- Evidence: `buzer-backend` was `Up About a minute (healthy)` on `0.0.0.0:18432->18432/tcp`.

## Manual Verification

- Confirmed the first deployed readiness commit exposed the scanned WhatsApp session as `connected=true`, `detail=ready`.
- Observed a restart failure caused by stale Chromium profile lock files in the persisted WhatsApp session volume.
- Removed only `SingletonLock`, `SingletonSocket`, and `SingletonCookie` once on the VPS to recover service, then added startup cleanup for those same stale lock files.
- Confirmed logs after redeploy included `Removed 3 stale WhatsApp Chromium profile lock file(s)`, `WhatsApp authenticated`, and `WhatsApp ready`.
- Confirmed the VPS checkout is on `883579d5e264df289c312d55a35c3f7d240ac76d`.

## Known Failures Or Skipped Checks

- Did not send a real WhatsApp message.
- Did not trigger an Android start-postpone escalation against a live Todoist task.
- The backend remains in `WHATSAPP_DRY_RUN=false` because the user scanned and activated the session.

## Assumptions

- Removing Chromium `Singleton*` profile lock files is safe at backend startup because this deployment runs one backend container and no concurrent Chromium process should own the profile before the new process launches.
- The persisted `buzer-app_buzer-whatsapp` volume remains the source of truth for WhatsApp LocalAuth session data.

## Next Recommended Step

Run one controlled real WhatsApp send through the normal escalation path only when a live Todoist test task is acceptable. Until then, `/health` provides the non-destructive readiness signal.
