# 2026-06-10 Controlled WhatsApp Escalation

## Task / Plan

- Task: run one controlled live WhatsApp escalation test through the normal backend path.
- Related handoff: `docs/handoffs/2026-06-09-whatsapp-readiness-health.md`
- Result: completed after correcting `WHATSAPP_RECIPIENT_ID` to a serialized WhatsApp chat ID.

## Files Changed

- `docs/handoffs/2026-06-10-controlled-whatsapp-escalation.md`

No app or backend code changed.

## Commands Run

- `git status --short`
- `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- VPS non-secret env/status checks for `WHATSAPP_DRY_RUN`, recipient suffix, and container health.
- Updated `/home/skclaw/buzer-app/.env` on the VPS to use `WHATSAPP_RECIPIENT_ID=421904157104@c.us`.
- Restarted backend with `docker compose up -d backend`.
- Created one live Todoist test task through `POST /tasks` and called `POST /tasks/:id/postpone-start` twice.
- Queried SQLite for the test task, commitment state, and escalation row.
- Completed both live Todoist test tasks through `POST /tasks/:id/done`.

## Verification

- Command: `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- Result: PASS
- Evidence: before and after the test, `/health` returned `{"database":"ok","whatsapp":{"connected":true,"detail":"ready"}}`.

- Command: recipient suffix check inside `buzer-backend`
- Result: PASS
- Evidence: after correction, recipient metadata showed `hasAt=true`, `suffix="@c.us"`, and length `17`.

- Command: first controlled escalation with old raw recipient
- Result: FAIL as expected after investigation
- Evidence: backend created task `55e75b5a-fccb-4989-9ff5-bf08573be139`; second postpone returned `escalationTriggered=true`, but `whatsapp_escalations.status` was `failed`, `sent_at` was null, and `commitment_states.escalation_sent_at` remained null.

- Command: corrected controlled escalation with `421904157104@c.us`
- Result: PASS
- Evidence: backend created task `3af62bb4-3319-41b7-9128-b842ef94592e`; first postpone returned `escalationTriggered=false`; second postpone returned `escalationTriggered=true`; SQLite showed `whatsapp_escalations.status="sent"`, `sent_at="2026-06-10T05:31:59.568Z"`, and `commitment_states.escalation_sent_at` set to the same timestamp.

- Command: cleanup via `POST /tasks/:id/done`
- Result: PASS
- Evidence: both controlled Todoist test tasks were completed through the backend and SQLite showed `commitment_states.state="completed"` for both.

## Manual Verification

- The old recipient value had no `@` suffix and caused WhatsApp send failure despite the backend reporting WhatsApp `ready`.
- `whatsapp-web.js` `sendMessage` expects a chat ID; personal chat IDs use the serialized `@c.us` format.
- The user's wife chat ID is now configured on the VPS as `421904157104@c.us`.
- The user's self chat ID supplied for future tests is `421902359921@c.us`.

## Known Failures Or Skipped Checks

- Did not add a code-level recipient validator in this session.
- Did not verify message content on the recipient device from this shell.
- The first failed escalation row remains in SQLite as useful evidence for the bad-recipient failure mode.

## Assumptions

- The successful SQLite `sent` status from `whatsapp-web.js` is sufficient backend evidence that WhatsApp accepted the message send.
- Completing the test tasks after verification is preferable to leaving live Todoist test commitments active.

## Next Recommended Step

Add startup validation for `WHATSAPP_RECIPIENT_ID` so non-serialized values fail fast with a clear error, and consider adding an admin/debug script that lists available WhatsApp chats as `name -> id._serialized`.
