# Real-Backend E2E Server — Execution Readiness

Date: 2026-07-20

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and Status

No implementation task has started. All nine plan tasks remain unchecked. This session
prepared the existing isolated worktree and the controller protocol for
subagent-driven development.

## Workspace

- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/e2e-real-backend`
- Branch: `feature/e2e-real-backend`
- Baseline: `24a2c0b459d20aea8c9c5ac2f8a308b718431a79` (same as `master`)
- State at preparation: clean; `git diff --name-only master...HEAD` produced no output.
- `.worktrees` is ignored by Git (`.gitignore:2`), and the existing worktree satisfies
  the isolation requirement. No additional worktree is needed for the sequential plan.

## Planned Delivery Sequence

Implementation remains deliberately sequential because later tasks modify the adapter
or server files established by earlier tasks.

1. Task 1 — owns `backend/test/fakes/e2eTodoistAdapter.ts` and its unit test.
2. Task 2 — owns `backend/test/fakes/e2eFixtures.ts` and its unit test.
3. Task 3 — owns `backend/test/e2eServer.ts` and the initial conformance test.
4. Task 4 — extends the server conformance suite and may correct only world semantics
   in the Task 1 adapter.
5. Task 5 — extends the same server/test pair with debug parity and HTTP scenarios.
6. Task 6 — owns only `backend/package.json`.
7. Task 7 — owns only `scripts/e2e-real-backend.sh`.
8. Task 8 — owns the additive Android test-client change and its new scenario class.
9. Task 9 — runs the broad gates and owns only the validation-triage note, unless its
   documented triage proves a server/world or fixture correction is required.

Each delivery agent receives the complete task text, file ownership, the spec link,
the existing-plan invariants (no `backend/src/`, `FakeTodoistAdapter`, old fake, or CI
changes), and must use test-first red/green work. After every task, the controller
runs a spec-compliance review followed by a code-quality review; no task advances with
Critical or Important findings unresolved. A final whole-branch review follows Task 9.

## Files Changed

- This handoff only.

## Verification

- Command: `npm test` (from `backend/` in the isolated worktree)
  - Result: PASS
  - Evidence: Vitest reported 28 files and 329 tests passed in 993ms.
- Command: `git status --short` (isolated worktree, before this handoff)
  - Result: PASS
  - Evidence: no output.
- Command: `git diff --name-only master...HEAD`
  - Result: PASS
  - Evidence: no output; the feature branch still begins at the current master head.

## Manual Verification

- Confirmed the worktree uses `feature/e2e-real-backend`, is already located under the
  ignored project-local `.worktrees/` directory, and has backend dependencies present.
- Read the approved product spec, the Phase 1 design, the complete implementation
  plan, recent handoffs, the workspace convention, and the subagent delivery/review
  protocols.

## Known Failures or Skipped Checks

- No product code was changed, so no Android compilation or instrumentation run was
  appropriate during this readiness-only session.
- The plan's intended RED runs, commits, conformance gates, real-port smoke test, and
  Android lanes remain Task 1 through Task 9 work.

## Assumptions

- The existing clean `feature/e2e-real-backend` worktree is the intended execution
  location; keeping tasks sequential avoids shared ownership of the adapter and server.
- Task-specific child agents will work in this isolated worktree one at a time, with
  read-only reviewers following each delivery.

## Next Recommended Step

Dispatch a fresh implementing agent for Task 1 only. Provide the plan's complete Task
1 text directly, require `backend/test/fakes/e2eTodoistAdapter.test.ts` to fail for the
expected missing-module reason before implementation, and review the resulting commit
for spec compliance and code quality before dispatching Task 2.
