# Handoff: Lean Observability Backend Dead-Code Cleanup

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review backend dead-surface cleanup)

## Task and checkbox status

- [x] Remove inventory-proven unused `BackendObservability.isEnabled` and `addBreadcrumb` APIs, including recording/test-only breadcrumb state.
- [x] Make request/background capture fire-and-forget `void`, removing generated/normalized capture IDs while preserving Sentry capture calls and recording side effects.
- [x] Make `flush` await Sentry but return `Promise<void>`; shutdown retains its 2,000 ms flush timeout.
- [x] Remove unused named Sentry config aliases while preserving `CONFIG` fields.
- [x] Verify every supported production/dev launcher preloads `instrument.ts`, then remove the redundant server initialization.

## Files changed

- `backend/src/config.ts`
- `backend/src/diagnostics/backendObservability.ts`
- `backend/src/server.ts`
- `backend/test/backendObservability.test.ts`
- `backend/test/e2eServer.ts`
- `backend/test/fakes/recordingBackendObservability.ts`
- `docs/handoffs/2026-08-23-lean-observability-backend-dead-code.md`

## Implementation summary

- The adapter now exposes only request/background failure capture and an awaited flush. Sentry capture still executes inside the configured scope; tests assert those side effects rather than synthetic IDs.
- Removed the fake's crypto/ID formatting and breadcrumb recording. The E2E control response now exposes only actual failure collections.
- `server.ts` no longer initializes Sentry itself. A narrow static test verifies both package launchers and Docker preload instrumentation, while `server.ts` only consumes the initialized runtime. The supported test paths import `buildServer` or inject `RecordingBackendObservability`; they do not start the production entrypoint.
- Kept QR behavior, request capture/redaction, 4xx/5xx behavior, flush-on-close, instrumentation preload, and all endpoint coverage intact.

## Verification

- Command: `npm test -- --run backendObservability.test.ts`
  Result: FAIL (expected RED)
  Evidence: old capture IDs, boolean flush values, and duplicate server initialization violated the new contract.

- Command: `npm test -- --run backendObservability.test.ts`
  Result: PASS
  Evidence: 1 file, 22 tests passed.

- Command: `npm test -- --run backendObservability.test.ts services/todoistPollerDiagnostics.test.ts services/escalationServiceDiagnostics.test.ts accountability/whatsappDiagnostics.test.ts e2eServer.test.ts`
  Result: PASS
  Evidence: 5 files, 63 tests passed.

- Command: `npm test`
  Result: PASS
  Evidence: 38 files, 451 tests passed.

- Command: `npm run typecheck`
  Result: PASS
  Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

- Command: `npm run build`
  Result: PASS
  Evidence: production TypeScript compilation and schema copy exited 0.

- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: no whitespace errors.

## Manual verification

SKIPPED. This is an adapter-surface deletion; the static launcher test and focused runtime tests cover the changed contracts without contacting Sentry or WhatsApp.

## Assumptions and known limitations

- Audit found no supported/documented direct `node dist/server.js` launch path. `backend/package.json` `start`/`dev` and Docker `CMD` each preload `instrument.ts`/`instrument.js`.
- The test E2E server deliberately injects a recording adapter, so it does not need production instrumentation initialization.

## Next recommended step

Root agent can review and integrate the scoped commit with the remaining lean-observability changes.
