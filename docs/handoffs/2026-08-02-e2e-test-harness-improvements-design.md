# E2E Test Harness Improvements Design Handoff

Date: 2026-08-02

## Task

Document a self-contained, deferred design for improving the Android real-backend E2E
test harness, based on the Smart Home isolation and debugging work.

Plan/status:

- Design direction: documented and ready for product/engineering review.
- Implementation: intentionally deferred.
- Next implementation unit: Phase 1, shared TestKit and guardrails.

## Files Changed

- `docs/superpowers/specs/2026-08-02-e2e-test-harness-improvements-design.md`
- `docs/handoffs/2026-08-02-e2e-test-harness-improvements-design.md`

## What The Design Records

- The observed scheduler starvation, matcher ambiguity, stale-server reuse,
  cross-boundary evidence, wall-clock fixture, and suite-isolation failures.
- The decision to evolve the existing real-backend lane incrementally instead of
  replacing it.
- A three-layer testing architecture, shared Android E2E TestKit, stable semantic IDs,
  hermetic runner identity, correlated timelines, deterministic response barriers,
  fixture builders, and focused suite decomposition.
- Placement of the four remaining Smart Home coverage gaps.
- A five-phase backlog with acceptance criteria, risks, mitigations, and non-goals.

## Verification

- Command: `rg -n "TBD|TODO|FIXME|PLACEHOLDER" docs/superpowers/specs/2026-08-02-e2e-test-harness-improvements-design.md`
- Result: PASS
- Evidence: no unresolved placeholders.

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

- Tests: SKIPPED
- Reason: documentation-only change; no executable behavior changed.

## Assumptions

- `scripts/e2e-real-backend.sh` remains the sole Android instrumentation authority.
- The current unfiltered-suite requirement remains in force until a later decision
  explicitly changes it.
- Each implementation phase will receive its own plan, worktree, verification, and
  handoff rather than being implemented as one large migration.

## Next Recommended Step

Review and approve the design. When work resumes, create a Phase 1 implementation plan
that first inventories every instrumentation wait and sleep, then migrates one focused
test class at a time while keeping the existing real-backend command green.
