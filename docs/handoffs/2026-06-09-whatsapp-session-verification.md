# 2026-06-09 WhatsApp Session Verification

## Task / Plan

- Task: verify whether the WhatsApp Web QR scan activated the VPS backend session.
- Related runbook: `docs/notes/vps-deployment.md`
- Result: non-invasive checks strongly suggest the QR scan was accepted, but the current backend does not expose a definitive WhatsApp `ready` health signal.

## Files Changed

- `docs/handoffs/2026-06-09-whatsapp-session-verification.md`

No app or backend code changed.

## Commands Run

- `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && printf "dry_run=" && grep -E "^WHATSAPP_DRY_RUN=" .env | cut -d= -f2- && printf "container=" && docker ps --filter name=buzer-backend --format "{{.Status}}" && printf "session_files=" && docker exec buzer-backend sh -lc "find \"${WHATSAPP_SESSION_PATH:-./whatsapp-sessions}\" -maxdepth 3 -type f 2>/dev/null | wc -l" && printf "chromium_processes=" && docker exec buzer-backend sh -lc "pgrep -fc chromium || true"'`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && docker compose logs --since 10m backend | awk ...'`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && for window in 3m 1m; do docker compose logs --since "$window" backend | awk ...; done'`

## Verification

- Command: `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- Result: PASS
- Evidence: returned `{"ok":true,"service":"buzer-backend","checks":{"database":"ok"}}`.

- Command: VPS non-secret runtime inspection
- Result: PASS
- Evidence: `WHATSAPP_DRY_RUN=false`, container status was healthy, the WhatsApp session path contained 63 files, and Chromium processes were running.

- Command: recent backend log summary over the last 10 minutes
- Result: PASS with caveat
- Evidence: QR lines were present from the initial live-mode startup, with no auth-failure, disconnect, or error lines.

- Command: narrowed backend log summary over the last 3 minutes and 1 minute
- Result: PASS with caveat
- Evidence: the 3-minute window still had QR lines, but the 1-minute window had `qr_lines=0 auth_failure_lines=0 disconnect_lines=0 error_lines=0`.

## Manual Verification

- Did not print or copy QR/session secrets into tracked files.
- Did not send a WhatsApp message.
- Did not trigger an Android start-postpone escalation against a live task.

## Known Failures Or Skipped Checks

- Current backend `/health` only checks the database. It does not report WhatsApp sender readiness.
- `WhatsAppWebSender` updates an internal `ready` flag on the `ready` event, but it does not log that event and there is no endpoint to query it.
- Therefore this session can be described as likely authenticated, not definitively send-ready, unless a controlled send is performed or readiness observability is added.

## Assumptions

- Absence of new QR output after the user scan, combined with persisted LocalAuth files and no auth/disconnect errors, indicates the QR scan was accepted.
- `WHATSAPP_DRY_RUN=false` remains intentional after the QR auth attempt.

## Next Recommended Step

Add minimal WhatsApp readiness observability: log `authenticated`, `ready`, `auth_failure`, and `disconnected`, and include WhatsApp sender health in `/health`. After redeploying that change, verify `connected=true` before running any real escalation send.
