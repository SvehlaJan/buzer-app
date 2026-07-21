# Handoff: Real-Backend E2E Runner — Task 7

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 7 only: add the real-backend Android E2E runner. The parent controller
owns plan progress, so no plan checkbox was changed. Task 7 steps are complete on this
branch: the runner was created, made executable, syntax-checked, contract-compared to
the approved plan, and smoke-checked against an already-running real E2E endpoint. The
requested commit is ready but was not created because this worktree cannot acquire its
shared Git index lock without external approval.

## Files changed

- `scripts/e2e-real-backend.sh` (new, mode 755)
- `docs/handoffs/2026-07-21-real-backend-e2e-task7.md`

The existing fake runner, backend/client code, CI, and plan were not changed.

## Implementation

- Added a strict Bash runner structurally identical to the approved Task 7 contract.
- It reuses a healthy `http://127.0.0.1:${PORT}/health` endpoint, otherwise starts
  `backend` with `npm run e2e-server`, detects early process exit during a 60-second
  readiness wait, and terminates only the process it starts.
- It selects the connected-device Gradle task when `ANDROID_SERIAL` is set, otherwise
  the managed-device task; it supplies the Android backend/token/assignee and test
  helper URL properties, plus optional GPU and test-class arguments.

## Verification

Verification:

- Command: `bash -n scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: syntax check exited 0.

Verification:

- Command: `stat -f '%Sp %N' scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: reported `-rwxr-xr-x scripts/e2e-real-backend.sh`.

Verification:

- Command: `diff -u <(sed -n '1410,1488p' docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md) scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: produced no diff; the runner exactly matches the approved Task 7 script.

Verification:

- Command: `curl --silent --fail http://127.0.0.1:18455/health`
  - Result: PASS
  - Evidence: exited 0 against a local real E2E server already listening on port 18455.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## Manual verification

- Inspected all branch conditions and Gradle arguments against
  `scripts/e2e-fake-backend.sh` and the exact Task 7 plan block.
- Confirmed that a local port already owned by another E2E Node process was not stopped;
  the runner's existing-healthy-endpoint path intentionally supports this situation.

## Known failures or skipped checks

- No full Android Gradle/instrumentation run was started. Task 9 owns the full runner
  validation; this task's specified focused verification is syntax, executable mode,
  contract equivalence, and boot-target health smoke.
- `git add scripts/e2e-real-backend.sh docs/handoffs/2026-07-21-real-backend-e2e-task7.md`
  was blocked by `Operation not permitted` while creating the shared worktree index
  lock. The subsequent scoped approval request was aborted before staging occurred.

## Assumptions

- `/health` is intentionally unauthenticated and remains the readiness authority for
  the real E2E server, as specified in Task 7.

## Next recommended step

Proceed with Task 8: add the Android test-client scenario support and the two
real-backend preset instrumentation tests, leaving this runner unchanged.
