# Handoff: Real-Backend E2E Runner — Task 7

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 7 only: add the real-backend Android E2E runner. The parent controller
owns plan progress, so no plan checkbox was changed. Task 7 steps are complete on this
branch: the runner was created, made executable, syntax-checked, contract-compared to
the approved plan, smoke-checked against an already-running real E2E endpoint, and
committed as `514dbcc feat(e2e): add real-backend E2E runner script` after the staged
retry succeeded.

## Files changed

- `scripts/e2e-real-backend.sh` (new, mode 755)
- `docs/handoffs/2026-07-21-real-backend-e2e-task7.md`

The existing fake runner, backend/client code, CI, and plan were not changed.

## Implementation

- Added a strict Bash runner structurally identical to the approved Task 7 contract.
- It reuses only an endpoint that passes both `GET /health` and the E2E-only
  `GET /__calls` probe. A different service answering `/health` causes a clear failure
  before Gradle/instrumentation can run; otherwise it starts `backend` with
  `npm run e2e-server`, detects early process exit during a 60-second readiness wait,
  and terminates only the process it starts.
- Every health/E2E curl probe is quiet on success, shows errors on failure, and has a
  two-second connect timeout plus a bounded overall timeout. Readiness derives each
  request's overall timeout from the remaining 60-second deadline.
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
  - Evidence: the original committed runner exactly matched the approved Task 7 script;
    the subsequent reviewed hardening below intentionally adds E2E identity and timeout
    safeguards.

Verification:

- Command: `curl --silent --fail http://127.0.0.1:18455/health`
  - Result: PASS
  - Evidence: exited 0 against a local real E2E server already listening on port 18455.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

Verification:

- Command: `rg -n --fixed-strings '\"/__calls\"' scripts/e2e-real-backend.sh`
  - Result: FAIL (expected RED before review fix)
  - Evidence: the original health-only runner contained no E2E identity probe.

Verification:

- Command: `rg -n --fixed-strings 'curl --silent --show-error --fail --connect-timeout' scripts/e2e-real-backend.sh`
  - Result: FAIL (expected RED before review fix)
  - Evidence: the original probes had neither bounded connect nor overall timeouts.

## Manual verification

- Inspected all branch conditions and Gradle arguments against
  `scripts/e2e-fake-backend.sh` and the exact Task 7 plan block.
- Confirmed that a local port already owned by another E2E Node process was not stopped;
  the runner's existing-healthy-endpoint path intentionally supports this situation.

## Review follow-up

- Tightened existing-server reuse: `/health` must now be followed by the E2E-only
  `/__calls` probe. A generic healthy service prints a clear refusal and exits before
  Gradle can run.
- Added `--show-error`, `--connect-timeout 2`, and bounded `--max-time` to the shared
  curl helper. The startup readiness loop passes the remaining time until its original
  60-second deadline to each probe.
- Corrected the stale commit status above: the original runner was committed as
  `514dbcc` after the staging retry succeeded.

Verification:

- Command: `bash -n scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: the hardened script has valid Bash syntax.

Verification:

- Command: focused `rg` checks for the shared timeout helper, `/health`, `/__calls`,
  existing-server identity branch, and refusal message
  - Result: PASS
  - Evidence: every health/E2E probe is routed through one bounded helper and generic
    healthy services cannot reach Gradle.

Verification:

- Command: `bash -n scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: the final safety-hardened runner exited 0.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics in the two-file safety patch.

Verification:

- Command: `rg -n -e 'curl_probe\\(\\)' -e '--silent --show-error --fail --connect-timeout 2 --max-time' -e 'health_is_ready' -e 'e2e_backend_is_ready' -e '\"\\$\\{BACKEND_URL\\}/__calls\"' -e 'Refusing to run Android tests' scripts/e2e-real-backend.sh`
  - Result: PASS
  - Evidence: confirmed the shared bounded curl helper, `/health` and E2E-only
    `/__calls` probes, and the collision refusal before Gradle arguments are built.

Verification:

- Command: `shellcheck scripts/e2e-real-backend.sh`
  - Result: SKIPPED
  - Evidence: `shellcheck` is not installed in this worktree environment.

Verification:

- Command: temporary localhost health-only mock invocation
  - Result: SKIPPED
  - Evidence: the sandbox rejects local port binding with `listen EPERM`; the scoped
    approval request was interrupted before it could run. No Android invocation ran.

## Known failures or skipped checks

- No full Android Gradle/instrumentation run was started. Task 9 owns the full runner
  validation; this task's specified focused verification is syntax, executable mode,
  contract equivalence, and boot-target health smoke.

## Assumptions

- `/health` is intentionally unauthenticated and remains the readiness authority for
  the real E2E server, as specified in Task 7.

## Next recommended step

Proceed with Task 8: add the Android test-client scenario support and the two
real-backend preset instrumentation tests, leaving this runner unchanged.
