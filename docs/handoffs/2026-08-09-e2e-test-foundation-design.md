# Android E2E Test Foundation Design Handoff

Date: 2026-08-09

## Task And Status

- Task: brainstorm and document the foundation phase of the Android E2E test overhaul.
- Design: `docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md`.
- Status: accepted by the user; implementation plan prepared for Claude Code with Sonnet 5.
- Implementation: not started. The brainstorming hard gate prohibits implementation
  before written-spec approval and a separate implementation plan.
- Branch: `feature/e2e-foundation-design`.
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/e2e-foundation-design`.
- Starting revision: `c83c16448623e0dc17e0c587bc25c609aae2ee8e`.

## Decisions Recorded

- Scope is shared TestKit plus hermetic runner, not the entire five-phase overhaul.
- `BlockerActivityBackendTest` is the first and only deeply migrated backend suite in
  this foundation.
- Runner uses a server-selected dynamic port by default; an explicitly occupied `PORT`
  fails without reuse or termination.
- Test isolation is centralized through an explicit, typed `E2ETestSession`.
- Ordinary sessions establish healthy foreground readiness; dedicated readiness tests
  can own capability state.
- Failure artifacts are compact and structured; screenshots are opt-in.
- Static guardrails use normalized exact fingerprints for legacy violations and run in
  current push CI.
- All active Kotlin/Gradle FakeBackend and RealBackend names are cleaned up in favor of
  `*BackendTest`, while the established `scripts/e2e-real-backend.sh` filename remains.
- Deferred suites receive descriptive TODOs linked to stable items in the forthcoming
  implementation plan.
- No test runtime acceptance target is imposed.

## Files Changed

- Created `docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md`.
- Created this handoff.

No production source, test source, script, workflow, manifest, or dependency was changed.

## Context Audited

- `AGENTS.md` and the approved product specification.
- The earlier deferred E2E overhaul design and handoff.
- The fake-backend removal decision.
- Token-efficient Android testing guidance.
- Daily Morning Review and Smart Home isolation/debugging handoffs.
- Current real-backend runner, both Android backend-control clients, instrumentation
  sleep/selector/wait-helper inventory, E2E server correlation and sync-hold controls,
  worktrees, and recent commits.

## Verification

Verification:
- Command: `rg -n "TBD|FIXME|PLACEHOLDER|later decide|to be decided|maybe|or UUID|previous session" docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md`
- Result: PASS
- Evidence: no unresolved placeholder or ambiguity markers were found.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors were reported before commit.

Verification:
- Command: product tests and builds
- Result: SKIPPED
- Evidence: this brainstorming task changes documentation only. The design defines the
  required backend, runner, scanner, focused instrumentation, full-suite, and CI gates
  for the later implementation plan.

## Assumptions And Dependencies

- Foreground enforcement readiness is implemented and merged before this foundation;
  the TestKit reuses its instrumentation-only capability controller.
- The integrated repository must be re-audited before implementation because current
  master does not yet include readiness.
- `backend/test/e2eServer.ts` remains the only test backend and does not leak controls
  into the production server.
- The current unfiltered real-backend E2E requirement remains the release authority.

## Next Recommended Step

Use `docs/superpowers/plans/2026-08-09-e2e-test-foundation.md` and the planning handoff
`docs/handoffs/2026-08-09-e2e-test-foundation-planning.md` for execution by Claude Code
with Sonnet 5. Do not implement until the foreground-readiness prerequisite is
integrated.
