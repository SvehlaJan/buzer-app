# Handoff: Lean Observability Backend Review Fixes

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review scoped fixes)

Task: Backend runtime/config/tests review fixes assigned by the root agent

## Checkbox status

- [x] Restore structured Fastify/Pino stdout logging in normal runtime with explicit quiet test-server option and safe serializers.
- [x] Replace terminal WhatsApp QR rendering with a 0600 local artifact in the ignored session path and clear it on lifecycle transitions.
- [x] Redact exact `auth` and arbitrary `*cookie` TypeScript keys against the shared corpus.
- [x] Refuse direct Sentry request capture for expected 4xx failures.
- [x] Remove the duplicate Sentry flush after `app.close()`.

## Files changed

- `backend/src/server.ts`
- `backend/src/accountability/whatsappWebSender.ts`
- `backend/src/diagnostics/backendObservability.ts`
- `backend/src/diagnostics/redaction.ts`
- `backend/src/types/qrcode-terminal.d.ts`
- `backend/test/backendObservability.test.ts`
- `backend/test/accountability/whatsappDiagnostics.test.ts`
- `backend/test/redaction.test.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/http/rateLimit.test.ts`
- `backend/test/diagnosticEndpoint.test.ts`
- `backend/test/e2eServer.ts`
- `docs/handoffs/2026-08-23-lean-observability-backend-review-fixes.md`

## Implementation summary

- `buildServer` now uses Fastify/Pino JSON stdout logging unless callers explicitly pass `logger: false`. Serializers emit only method, redacted URL, status, request ID, timing, and sanitized error type/message; request/response bodies, headers, and error stacks are omitted. Pino's free-form message field is redacted as a final guard against credential-bearing framework error messages.
- WhatsApp QR graphics are generated into `<WHATSAPP_SESSION_PATH>/whatsapp-qr.txt` with mode `0600`; the session directory is ignored and created with `0700`. Console output contains only the generic scan instruction and local path. Raw QR values/graphics and lifecycle reason strings are not written to stdout/stderr or retained in Sentry-facing failure messages. The artifact is removed after authentication, ready, auth failure, disconnect, and destroy.
- `captureRequestFailure` independently rejects context status codes from 400 through 499, retaining the existing 5xx/unhandled capture path.
- Signal shutdown relies on the existing `onClose` flush, preventing the former second flush after `app.close()`.

## TDD evidence

- Added focused regressions first, then ran them before implementation: expected failures covered missing 4xx capture filtering, disabled default logger, `auth` corpus handling, QR artifact creation, and raw credential-bearing URL/error logging.
- The final focused suite passed after the smallest scoped implementation.

## Verification

- Command: `npm test -- backendObservability.test.ts accountability/whatsappDiagnostics.test.ts redaction.test.ts`
  Result: PASS
  Evidence: 3 files passed, 28 tests passed.

- Command: `npm test`
  Result: PASS
  Evidence: 38 files passed, 445 tests passed.

- Command: `npm run typecheck`
  Result: PASS
  Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

- Command: `npm run build`
  Result: PASS
  Evidence: `tsc -p tsconfig.build.json` and schema copy completed with exit 0.

- Command: `git diff --check`
  Result: PASS
  Evidence: exited 0 before final handoff evidence update; the final check is repeated immediately before commit.

## Manual verification

SKIPPED. The QR artifact behavior is unit-tested with a real temporary filesystem path and mode check; no WhatsApp account/session was used.

## Assumptions and known limitations

- The local QR artifact is intentionally short-lived and restricted to the ignored WhatsApp session directory; it is intended for a local operator with filesystem access, not remote delivery.
- Tests can opt into `logger: false`; existing tests that do not specify it now emit the same safe structured records as runtime, with no request payloads or credentials.

## Next recommended step

Root agent should integrate the scoped commit with the other review-fix commits, then run branch-level verification if other worktree changes are merged.
