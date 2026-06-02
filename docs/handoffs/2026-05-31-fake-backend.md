# Handoff: Fake Backend

Date: 2026-05-31
Agent: Codex GPT-5
Plan/task attempted: Task 3 of `docs/superpowers/plans/2026-05-31-android-emulator-testing-workflow.md`.

## Checkbox Status

- Task 3 Step 1: complete.
- Task 3 Step 2: complete.
- Task 3 Step 3: complete.
- Task 3 Step 4: complete.
- Task 3 Step 5: complete.
- Task 3 Step 6: complete.

## Files Changed

- `tools/fake-backend/package.json`
- `tools/fake-backend/package-lock.json`
- `tools/fake-backend/tsconfig.json`
- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `docs/handoffs/2026-05-31-fake-backend.md`

## Commands Run

- `npm install` in `tools/fake-backend`
- `cd tools/fake-backend && npm test`
- `cd tools/fake-backend && npm run build`
- `git status --short --ignored tools/fake-backend`

## Verification

- Command: `npm install`
- Result: PASS
- Evidence: dependencies installed and `package-lock.json` was created.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS after sandbox escalation
- Evidence: 5 tests passed: auth rejection, deterministic snapshot, action recording, reset, and forced action failure recording.

- Command: `cd tools/fake-backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.json` completed successfully.

- Command: `git status --short --ignored tools/fake-backend`
- Result: PASS
- Evidence: source files are untracked and `tools/fake-backend/node_modules/` is ignored by the root `node_modules/` ignore rule.

## Manual Verification

- Confirmed fake backend requires `Authorization: Bearer test-token` for normal endpoints.
- Confirmed host-only helper endpoints `/__calls` and `/__reset` do not require bearer auth.
- Confirmed action endpoints record calls before returning forced failures.
- Confirmed the server listens on `0.0.0.0` so the Android emulator can reach it through `10.0.2.2`.
- Tightened the auth hook to return immediately after sending `401`, then reran `npm test` and `npm run build`.

## Known Failures Or Skipped Checks

- The first `npm test` run inside the default sandbox failed with `listen EPERM` when `tsx` tried to create a local IPC pipe under `/var/folders/.../T/tsx-*`. The same command passed outside the sandbox.
- The fake backend was not yet exercised from an Android emulator; that belongs to Task 5 and Task 7.

## Assumptions

- `test-token` is intentionally non-secret and safe to commit in fake backend tests and docs.
- Fastify 5 and `@fastify/cors` 10 are acceptable for this local-only fake backend.

## Next Recommended Step

Proceed to Task 4: add shared Android emulator script helpers.
