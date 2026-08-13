# E2E Test Foundation Planning Handoff

Date: 2026-08-09

## Scope and Status

- Task: turn the accepted Android E2E foundation design into a self-contained,
  implementation-ready plan.
- Design: `docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md`.
- Plan: `docs/superpowers/plans/2026-08-09-e2e-test-foundation.md`.
- Status: planning complete; implementation not started.
- Intended implementation harness: Claude Code with Sonnet 5.
- Branch: `feature/e2e-foundation-design`.
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/e2e-foundation-design`.
- Starting commit: `c83c164` (`master` when the design worktree was created).
- Design commit already on branch: `df5d75d docs: design E2E test foundation`.

## Plan/Task/Steps Attempted

- Accepted design status recorded.
- Existing backend runner, E2E server, Android instrumentation clients, mixed
  blocker/Home suite, Gradle test arguments, CI workflow, and current test inventory
  audited.
- Current Fastify 5, Compose UI Test, and Node 22 lifecycle APIs checked with Context7.
- Ten sequential implementation tasks specified with 84 evidence-gated checkbox steps.
- Exact file ownership, production/test boundaries, APIs, RED/GREEN commands, commit
  boundaries, focused regression filters, full-suite verification, connected-device
  proof, CI integration, and final handoff requirements defined.
- `*BackendTest` naming is locked for all active backend instrumentation suites.
- Five intentionally deferred suites have stable plan anchors and require descriptive
  source TODOs plus exact guardrail baseline entries.
- The blocker/Home suite is the only suite fully migrated in this foundation.

## Files Changed

- Modified `docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md` to record
  user acceptance.
- Modified `docs/handoffs/2026-08-09-e2e-test-foundation-design.md` to record acceptance
  and Claude Code/Sonnet 5 planning direction.
- Created `docs/superpowers/plans/2026-08-09-e2e-test-foundation.md`.
- Created this planning handoff.
- No production, backend, Android, runner, or CI code was changed.

## Locked Implementation Decisions

- Foreground enforcement readiness must be integrated first; the implementation must
  reuse its instrumentation-only readiness controller and begins from a fresh worktree
  based on the then-current `master`.
- The runner owns a port-0 backend process, publishes/validates run metadata, and never
  probes or kills an unrelated port owner.
- Reset and recorded-call identity is exact and monotonic within a run.
- Android uses one typed `E2EBackendClient`, explicit `E2ETestSession` presets, a
  leak-safe process registry, scheduler-aware waits, and compact correlated diagnostics.
- The five active backend classes are renamed to `*BackendTest`; `FakeBackend` and
  `RealBackend` naming and the obsolete fake URL property are removed.
- Only `BlockerActivityBackendTest` is fully migrated. Add/Edit/Scenario/Unified Editor
  and Morning Review alarm suites retain behavior and receive linked migration TODOs.
- A dependency-free source scanner prevents new sleeps, raw substring selectors, and
  private wait helpers while tracking exact legacy debt rather than allowlisting files.
- Managed-device full-suite verification remains mandatory. Connected-device proof may
  be explicitly skipped if unavailable, without weakening managed verification.

## Commands and Research Performed

Verification:
- Command: `git worktree list`
- Result: PASS
- Evidence: planning was performed in the dedicated `e2e-foundation-design` worktree.

Verification:
- Command: `git status --short --branch`
- Result: PASS
- Evidence: only the accepted design/handoff and new planning documents are in scope.

Verification:
- Command: `npx ctx7@latest library/docs` queries for Fastify 5, Compose UI Test, and Node 22
- Result: PASS
- Evidence: plan uses Fastify object-form port-0 listen/address discovery/close,
  Compose scheduler-aware `waitUntil`, and owned POSIX detached process-group cleanup.

Verification:
- Command: repository `rg` audits of Android `@Test` methods, E2E clients, sleeps/waits,
  backend debug endpoints, BuildConfig arguments, and CI jobs
- Result: PASS
- Evidence: the audited pre-foundation instrumentation inventory is 93 tests, including
  44 tests in the mixed blocker/Home class; the plan does not hard-code 93 after the
  readiness prerequisite adds tests.

Verification:
- Command: `rg -c '^- \[ \]' docs/superpowers/plans/2026-08-09-e2e-test-foundation.md`
- Result: PASS
- Evidence: 84 implementation and verification steps are present.

Verification:
- Command: placeholder and type-consistency scans over the implementation plan
- Result: PASS
- Evidence: no unresolved TBD/FIXME/placeholder text or obsolete timeout type remains;
  the only TODO instructions are the five user-approved deferred suite migrations.

Verification:
- Command: product/backend/Android test commands
- Result: SKIPPED
- Evidence: this session changed documentation only; the plan contains the exact RED,
  focused, full-suite, and CI verification commands required during implementation.

## Assumptions and Known Constraints

- Claude Code/Sonnet 5 has access to the same repository and can use the referenced
  superpowers execution/review skills. If not, it should still execute the plan
  sequentially and preserve its review checkpoints.
- The readiness work may change the baseline instrumentation count or controller public
  name. The plan permits one thin TestKit adapter for a name difference, but prohibits a
  duplicated readiness implementation.
- The plan deliberately avoids sharding, emulator CI, broad migration of all suites,
  runtime performance gates, default screenshots, and product behavior changes.

## Next Recommended Step

Merge this documentation branch so the accepted design and plan are available on
`master`. After foreground readiness is integrated and its unfiltered E2E handoff is
green, give Claude Code/Sonnet 5 the implementation plan as its primary instruction and
have it execute Tasks 1–10 sequentially in a fresh `feature/e2e-test-foundation`
worktree.
