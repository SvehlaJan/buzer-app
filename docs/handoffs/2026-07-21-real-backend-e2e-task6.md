# Handoff: Real-Backend E2E Server — Task 6

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 6 only: add the E2E server npm script. The parent controller owns plan
progress, so no plan checkbox was changed.

Task 6 completed:

- Added `"e2e-server": "tsx test/e2eServer.ts"` beside the existing `dev` and
  `migrate` scripts.
- Verified npm resolves the script and the server works through its real HTTP port.

## Files changed

- `backend/package.json`
- `docs/handoffs/2026-07-21-real-backend-e2e-task6.md`

No application source, test, client, existing fake backend, CI, script, or plan files
were changed.

## Verification

Verification:

- Command: `cd backend && npm run e2e-server`
  - Result: FAIL (expected RED)
  - Evidence: before the script was added, npm reported `Missing script: "e2e-server"`.

Verification:

- Command: `cd backend && npm pkg get scripts.e2e-server`
  - Result: PASS
  - Evidence: returned `"tsx test/e2eServer.ts"`.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

Verification:

- Command: `cd backend && PORT=18455 npm run e2e-server` with real-port HTTP checks
  - Result: PASS
  - Evidence: the server listened successfully; `GET /health` returned ok, an
    authorized `GET /tasks/snapshot` returned the seeded fixture snapshot, and
    `POST /__reset` returned `{ok:true}`.

## Manual verification

- Confirmed the new script is adjacent to the existing `dev` and `migrate` entries
  and invokes the direct E2E server entrypoint specified by the plan.

## Known failures or skipped checks

- The initial sandboxed real-port launch was blocked before application startup by
  `tsx` IPC socket creation: `EPERM` on a temporary `.pipe` path. The successful
  privileged real-port smoke above provides the required launch, readiness, snapshot,
  reset, and cleanup verification.
- No broad test suite was run because this task changes only npm script configuration;
  the focused real-port smoke exercises the script target directly.

## Assumptions

- The E2E server’s existing direct entrypoint remains the intended script target as
  stated in Task 6 of the plan.

## Next recommended step

Proceed with Task 7: add `scripts/e2e-real-backend.sh` without changing the existing
fake-backend lane.
