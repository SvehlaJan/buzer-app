# Recurring Checklists Task 10: Stateful Fake Backend

Plan/task/steps attempted:
- Executed Task 10 from `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` using strict TDD.
- Replaced the legacy flat fixtures and `204` mutation stubs with deterministic in-memory nested root/checklist state and fixed-route responses.
- Added RED tests before implementation; they failed against the old four-flat-task/`204` backend as expected.

Files changed:
- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- this handoff

Behavior added:
- Seven named deterministic root scenarios: one-time empty, partially checked, recurring all checked, recurring Extend disabled, custom recurrence, unsupported hierarchy, and reconciliation pending.
- All fixed task/checklist routes now return authoritative snapshot envelopes. Focused changes advance only their child revision; Done rejects incomplete checklists; recurring Done rolls over to a deterministic occurrence/due and resets child checks.
- Action metadata-only call recording, durable identical-body replay, and `409 action_id_reused` for changed action bodies.
- Partial create/edit child outcomes plus test controls for forced 500, 202 pending, 409 stale, 422 activity-log-full, and 429 with `Retry-After: 7`.

TDD evidence:
- RED command: `npm test`
- Result: FAIL (10/10)
- Evidence: legacy snapshot returned `task-start`, `task-deadline`, `task-invalid`, and `task-floating-time`; legacy mutations returned `204` rather than the required authoritative mutation responses.
- GREEN command: `npm test`
- Result: PASS (10/10)
- Evidence: nested fixtures, child-only revision behavior, recurrence rollover, replay/stale behavior, partial outcomes, controls, and redacted recordings all passed.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 10 tests passed, 0 failed.
- Command: `npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.json` exited 0.
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification:
- Inspected the emitted `__calls` fixture response through the focused test. It contains only action/device/occurrence/revision metadata and omits bearer credentials and arbitrary request fields.

Known failures or skipped checks:
- None. `npm ci` was needed in this worktree because its local fake-backend `node_modules` directory was absent; npm reported the existing Node 22 engine expectation while the local runtime is Node 26, but the test and build gates passed.

Assumptions:
- Fake-backend control paths are intentionally test-only and unauthenticated, matching the existing `__` helper convention.
- A `"[partial-failure]"` checklist draft is the deterministic fixture trigger for create partial outcomes.

Next recommended step:
- Use this fake backend in the Task 11 shared-client contract tests, then continue with occurrence-aware state/alarm work.
