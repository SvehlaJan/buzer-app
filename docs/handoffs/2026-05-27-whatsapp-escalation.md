# Handoff: WhatsApp Escalation Worker

Date: 2026-05-27
Agent: Codex GPT-5
Plan: `docs/superpowers/plans/2026-05-08-03-whatsapp-escalation-worker.md`

## Scope

- Executed the WhatsApp escalation worker plan in `.worktrees/whatsapp-escalation` on branch `feat/whatsapp-escalation`.
- Ownership was limited to `backend/**`, this handoff, and the WhatsApp escalation plan. `client/**` and `.claude/` were not edited.

## Completed

- Completed plan Tasks 1-7 except commit was intentionally performed after this handoff was written so the handoff could be included in the commit.
- Resolved current `whatsapp-web.js` docs with Context7 ID `/wwebjs/whatsapp-web.js`.
- Added `qrcode-terminal` and `whatsapp-web.js` dependencies and updated `backend/package-lock.json`.
- Added `AccountabilitySender`, `DryRunSender`, `WhatsAppWebSender`, and the escalation message builder.
- Added `EscalationService` that logs pending/sent/failed rows in `whatsapp_escalations` and updates `commitment_states.escalation_sent_at` after a successful send.
- Wired the second start postpone to call `EscalationService` through `TaskActionService` while preserving the existing `postponeStart` API response shape.
- Wired `BuildServerOptions.escalationService` into the postpone route.
- Runtime startup now creates an escalation service. It defaults to `DryRunSender` unless `WHATSAPP_DRY_RUN=false`, in which case it initializes `WhatsAppWebSender`.
- Added `backend/.gitignore` entries for WhatsApp Web auth/cache folders under `backend/`.

Files changed:

- `backend/.env.example`
- `backend/.gitignore`
- `backend/package-lock.json`
- `backend/package.json`
- `backend/src/accountability/accountabilitySender.ts`
- `backend/src/accountability/dryRunSender.ts`
- `backend/src/accountability/escalationMessage.ts`
- `backend/src/accountability/whatsappWebSender.ts`
- `backend/src/http/routes.ts`
- `backend/src/server.ts`
- `backend/src/services/escalationService.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/types/qrcode-terminal.d.ts`
- `backend/test/accountability/dryRunSender.test.ts`
- `backend/test/accountability/escalationMessage.test.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/services/escalationService.test.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/handoffs/2026-05-27-whatsapp-escalation.md`
- `docs/superpowers/plans/2026-05-08-03-whatsapp-escalation-worker.md`

## Verification

- Command: `npx ctx7@latest library "whatsapp-web.js" "Node TypeScript LocalAuth sendMessage Puppeteer headless persistent session VPS"`
- Result: PASS
- Evidence: resolved `/wwebjs/whatsapp-web.js` as the best match.

- Command: `npx ctx7@latest docs "/wwebjs/whatsapp-web.js" "LocalAuth Client sendMessage Puppeteer headless auth_failure disconnected ready events"`
- Result: PASS
- Evidence: docs confirmed `Client` initialization, QR generation, `ready`, `auth_failure`, and event handling. Docs also show CommonJS-style package import usage.

- Command: `cd backend && npm install qrcode-terminal whatsapp-web.js`
- Result: PASS
- Evidence: added 344 packages; audit found 0 vulnerabilities.

- Command: `cd backend && npm test -- test/accountability/escalationMessage.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run failed because `src/accountability/escalationMessage.js` did not exist; after implementation, 1 test passed.

- Command: `cd backend && npm test -- test/accountability/dryRunSender.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run failed because `src/accountability/dryRunSender.js` did not exist; after implementation, 1 test passed.

- Command: `cd backend && npm test -- test/services/escalationService.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run failed because `src/services/escalationService.js` did not exist; after implementation, 1 test passed.

- Command: `cd backend && npm test -- test/services/taskActionService.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run expected one escalation row and received 0; after wiring `EscalationService`, the focused service tests passed.

- Command: `cd backend && npm test -- test/http/routes.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run showed the postpone route did not call the sender; after route wiring, 5 tests passed.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 13 test files passed with 33 tests.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited with status 0.

- Command: `git check-ignore backend/.wwebjs_auth backend/.wwebjs_cache whatsapp-sessions/session`
- Result: PASS
- Evidence: printed all three paths.

## Decisions And Assumptions

- No real WhatsApp message was sent.
- No real QR login was attempted.
- `WHATSAPP_DRY_RUN` defaults to dry-run mode unless explicitly set to `false`, preventing accidental WhatsApp Web startup in local development.
- Current docs and installed types expose `whatsapp-web.js` as an `export =` CommonJS package. With this repo's `esModuleInterop`, `import whatsappWeb from "whatsapp-web.js"` typechecked; the implementation destructures `Client` and `LocalAuth` from that default import.
- `qrcode-terminal` does not ship TypeScript declarations in the installed package, so a narrow local declaration was added under `backend/src/types/qrcode-terminal.d.ts`.
- The plan snippet replaced the fourth `TaskActionService` constructor argument with `EscalationService`, but this branch already used the fourth argument for `currentUserId`. I preserved that API and added escalation as an optional fifth constructor argument.
- The plan's final task listed commit before handoff, but repo rules require handoffs to be committed with related work, so this handoff was written before the commit.

---

## Review Blocker Fixes

Date: 2026-05-28
Agent: Codex GPT-5
Plan/task attempted: review blockers for `docs/superpowers/plans/2026-05-08-03-whatsapp-escalation-worker.md` after the initial WhatsApp escalation implementation.

### Scope

- Fixed duplicate WhatsApp escalation sends when overlapping second-or-later start-postpone requests race.
- Fixed live sender failure behavior so `postponeStart` still returns a successful 5-minute snooze result.
- Prevented repeated failed WhatsApp attempts from creating unbounded failed rows for the same task.

### Files Changed

- `backend/src/db/schema.sql`
- `backend/src/services/escalationService.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/handoffs/2026-05-27-whatsapp-escalation.md`

### Implementation Notes

- Added a unique SQLite index on `whatsapp_escalations(task_id)` so the backend can claim one escalation attempt per task before calling the external sender.
- `EscalationService.sendForTask()` now inserts the pending escalation row with `insert or ignore` before calling WhatsApp. If another request already claimed the task, it returns `already_claimed` without sending.
- Sender failures are caught only at the external send boundary, recorded as `failed`, and returned as an escalation result. The action service still completes its snooze update and response.
- Database/update failures after a successful external send are intentionally not swallowed.

### Verification

- Command: `cd backend && npm test -- test/services/taskActionService.test.ts`
- Result: FAIL then PASS
- Evidence: the new concurrency regression initially failed with 2 escalation rows instead of 1, and the sender-failure regression initially rejected with `Error: WhatsApp not ready`. After the fix, 7 tests passed.

- Command: `cd backend && npm test -- test/services/taskActionService.test.ts test/services/escalationService.test.ts`
- Result: PASS
- Evidence: 2 test files passed with 8 tests.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 13 test files passed with 35 tests.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited with status 0.

### Known Failures Or Skipped Checks

- No real WhatsApp message was sent.
- No real QR login was attempted.
- Live WhatsApp Web auth/send still needs VPS sandbox verification with `WHATSAPP_DRY_RUN=false`.

### Assumptions

- The MVP should make at most one automatic accountability send attempt per task. If that attempt fails, the failure is logged and the nag loop continues; retry/recovery should be an explicit later feature instead of retry-spamming on every dismiss.

## Open Issues

- Live WhatsApp Web authentication and real send behavior remain unverified by design.
- Production needs the correct `WHATSAPP_RECIPIENT_ID` chat ID and an intentional `WHATSAPP_DRY_RUN=false` rollout when ready.
- There is no retry worker for failed WhatsApp sends yet; failures are logged as `failed` and skipped on later dismisses.

## Next Step

- Run the backend on a non-production VPS sandbox with `WHATSAPP_DRY_RUN=false`, scan the QR once, and verify health/readiness before allowing real escalation sends.
