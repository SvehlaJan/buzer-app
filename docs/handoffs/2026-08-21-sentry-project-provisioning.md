# Sentry Project Provisioning Handoff

Date: 2026-08-21

## Task And Status

- Task: record the externally provisioned hosted Sentry organization and project
  identifiers for the observability implementation.
- Plan: `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`, external
  operator prerequisites and Task 12.
- Status: organization and two projects provisioned; DSNs, tokens, integration, alerts,
  deployment, and canaries remain pending.

## Files Changed

- Created `docs/notes/observability-operations.md` with the safe-to-commit organization
  slug, project slugs/IDs, platform selections, secret-location contract, and complete
  external checklist.
- Created this handoff.
- No DSN, auth token, API token, or other credential was recorded.
- No runtime code, dependency, build, workflow, VPS, Android, or Sentry configuration was
  changed by this repository session.

## Verification

Verification:
- Command: `git worktree list --porcelain` and `git status --short`
- Result: PASS
- Evidence: primary `master` was clean at `0739b1a`; Task 8 continues independently in
  `.worktrees/observability-task8`.

Verification:
- Command: repository search for the supplied organization/project identifiers before
  editing
- Result: PASS
- Evidence: the identifiers had not previously been recorded in project documentation.

Verification:
- Command: `git diff --check` and credential-pattern scan
- Result: PASS
- Evidence: documentation formatting is valid and contains no DSN or auth-token value.

Verification:
- Command: product tests/builds/live Sentry/VPS/device checks
- Result: SKIPPED
- Evidence: documentation-only recording of operator-provided non-secret identifiers.

## Recorded Identifiers

- Organization slug: `jans-gi`
- Android project: `buzer-android` (`4511950606172240`)
- Backend project: `buzer-backend` (`4511950617641040`), Node.js/Fastify

## Next Recommended Step

Continue Task 8. Store DSNs only when the integration/canary worktree is ready. Defer
tokens and alert rules until their exact scopes and emitted event tags have been verified.
