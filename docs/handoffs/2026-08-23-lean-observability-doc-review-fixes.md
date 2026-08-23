# Handoff: Lean Observability Documentation Review Fixes

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review documentation corrections)

## Plan and task status

- Task 7 documentation is corrected for the currently implemented lean runtime.
- Task 8, Step 3 remains unchecked and explicitly `SKIPPED`: hosted-Sentry provisioning, credentials, and the physical-device/VPS canary are operator-owned prerequisites that have not been evidenced.

## Files changed

- `docs/runbooks/production-rollout.md`
- `docs/runbooks/sentry-setup.md`
- `docs/runbooks/observability-triage.md`
- `docs/notes/observability-operations.md`
- `docs/notes/vps-deployment.md`
- `docs/notes/local-development.md`
- `docs/superpowers/plans/2026-08-22-lean-observability.md`
- `docs/handoffs/2026-08-22-lean-observability.md`
- `docs/handoffs/2026-08-23-lean-observability-doc-review-fixes.md`

## Changes made

- Removed static VPS `.env` release guidance. The runbooks now document that the deploy script derives `app.buzer@0.1.0+<git-sha-12>` after checkout; a deliberately exported deploy-shell value is the only override.
- Documented Android's actual Gradle configuration precedence and release derivation: Gradle property, `local.properties`, then environment for configuration values; `SENTRY_RELEASE` is always derived from the version and `GITHUB_SHA` or local Git SHA.
- Replaced stale alert and triage marker examples with all emitted backend markers: `buzer.backend.todoist_poll_failure`, `buzer.backend.whatsapp_auth_failure`, `buzer.backend.whatsapp_disconnected`, and `buzer.backend.escalation_delivery_failure`.
- Corrected local-development Sentry organization/environment/release guidance, marked the hosted canary as pending in the plan and prior handoff, and removed the plan's trailing blank line that made `git diff --check` fail.

## Verification

- Command: `git diff --check 025b104..HEAD`
  Result: FAIL (expected RED before the documentation edit)
  Evidence: reported `docs/superpowers/plans/2026-08-22-lean-observability.md:495: new blank line at EOF`.

- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: exited 0 after the plan EOF correction.

- Command: `rg -n 'todoist_poller_error|whatsapp_disconnect|whatsapp_escalation_failure|buzer-backend@0\\.1\\.0\\+(vps|local)|SENTRY_RELEASE=' docs/runbooks docs/notes/observability-operations.md docs/notes/vps-deployment.md docs/notes/local-development.md`
  Result: PASS
  Evidence: no matches, confirming stale active-runbook marker and static-release examples are absent.

- Command: `rg -n 'buzer\\.backend\\.(todoist_poll_failure|whatsapp_auth_failure|whatsapp_disconnected|escalation_delivery_failure)' docs/runbooks docs/notes/observability-operations.md`
  Result: PASS
  Evidence: all four source-verified markers are documented in setup, triage, and operations guidance.

- Command: `rg -n 'diagnostics/reports|android-diagnostics-v1|backend-diagnostics-v1|sentry-github-sync|sentry-trace|baggage' AGENTS.md docs/runbooks docs/notes/observability-operations.md`
  Result: PASS
  Evidence: removed-system terms occur only in explicit non-restoration guidance.

## Manual verification

SKIPPED. This is documentation-only; no production DSNs, tokens, device, VPS deployment, or Sentry canary was used.

## Assumptions and known limitations

- The documentation reflects the current source: `scripts/deploy-vps.sh`, `client/androidApp/build.gradle.kts`, `backend/src/services/todoistPoller.ts`, `backend/src/accountability/whatsappWebSender.ts`, and `backend/src/services/escalationService.ts`.
- The old 2026-08-21 design/plan/handoffs retain obsolete terms solely as prominently marked historical records and were not rewritten.

## Next recommended step

An authorized operator should provision hosted Sentry credentials/alerts and complete the physical-device plus deployed-VPS canary using `docs/runbooks/production-rollout.md`, then record the evidence before calling rollout complete.

## Follow-up review corrections

- Removed static `SENTRY_RELEASE` assignments from root and backend example environment files; both now preserve deploy/build SHA derivation unless an operator deliberately overrides it.
- Corrected Android local configuration precedence and the `jans-gi` organization in local-development guidance.
- Added the same prominent supersession banner to every historical 2026-08-21 observability task handoff without changing its original evidence.
- Clarified that the Sentry organization/projects are already provisioned, while credentials, integration, alerts, deployment, and canaries remain pending.
- Removed the unsupported claim that the current release build is minified or requires ProGuard mappings; mapping upload/symbolication is now conditional on a future minified build.

Verification:
- Command: `rg -n '^SENTRY_RELEASE=' .env.example backend/.env.example`
  Result: PASS
  Evidence: no static release assignment remains.
- Command: `rg -l '^> \\[!WARNING\\]' docs/handoffs/2026-08-21-observability-task-*.md | wc -l`
  Result: PASS
  Evidence: `13`, matching all historical task handoffs.
- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: exited 0 before final staged verification.
