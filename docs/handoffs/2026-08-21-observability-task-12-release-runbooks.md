# Handoff: Observability Task 12 - Add Release Artifacts, Deployment Configuration, And Runbooks

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-22  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 12: Add Release Artifacts, Deployment Configuration, And Runbooks  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented release configuration, source-map upload scripts, docker deployment environment contracts, architecture decision records, and operational runbooks for Sentry observability and bug reporting:

1. **Android Sentry Build & ProGuard Mapping Configuration** (`client/androidApp/build.gradle.kts`):
   - Configured Sentry Gradle plugin properties (`SENTRY_ORG` defaulting to `svehla`, `SENTRY_PROJECT` defaulting to `buzer-android`).
   - Enabled ProGuard/R8 mapping file generation and conditional auto-upload when `SENTRY_AUTH_TOKEN` is present in the build environment.

2. **Backend Release & Source Map Management** (`backend/package.json`, `scripts/deploy-vps.sh`):
   - Added pinned `@sentry/cli@3.6.2` development dependency.
   - Added `sentry:sourcemaps` script for injecting debug IDs and uploading build artifacts in `dist/`.
   - Updated `scripts/deploy-vps.sh` to derive `SENTRY_RELEASE` (`buzer-backend@0.1.0+<git-sha>`) and trigger conditional source map uploads upon healthy service deployment when `SENTRY_AUTH_TOKEN` is present, or report `SKIPPED`.

3. **Deployment & Environment Configuration** (`docker-compose.yml`, `.env.example`, `backend/.env.example`):
   - Added Sentry environment variables (`SENTRY_BACKEND_DSN`, `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`, `SENTRY_ORG`, `SENTRY_PROJECT`, `SENTRY_AUTH_TOKEN`) with documentation.
   - Wired `docker-compose.yml` service environment to pass Sentry configurations into the container runtime.

4. **Architecture Decision Record** (`docs/decisions/2026-08-21-hosted-sentry-observability.md`):
   - Documented ADR for Hosted Sentry Cloud, two-project architecture (`buzer-android`, `buzer-backend`), native accelerometer shake with visible fallback, broad PII/task capture allowance with strict secret redaction, Sentry event inbox + GitHub engineering queue separation, paired diagnostic capsules, and best-effort telemetry boundary.

5. **Operational Documentation & Runbooks** (`docs/notes/observability-operations.md`, `docs/runbooks/`):
   - Created `docs/notes/observability-operations.md` covering Schema Version 1 capsule definitions, ring buffer specifications, network history capture, execution deadlines, secret redaction engine guarantees, retention policies, and setup checklist.
   - Created `docs/runbooks/sentry-setup.md` (hosted project configuration, token scopes, alert rules, code mapping).
   - Created `docs/runbooks/observability-triage.md` (triage flow, paired report correlation via `report_session_id`, capsule decoding, regression handling).
   - Created `docs/runbooks/production-rollout.md` (staged rollout procedure across VPS and physical Android device).
   - Updated `AGENTS.md`, `docs/notes/local-development.md`, and `docs/notes/vps-deployment.md`.

---

## 2. Verification and Test Evidence

- **Android Client Suite**:
  - Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  - Result: **BUILD SUCCESSFUL** (55 unit tests passed, debug APK assembled cleanly).
- **Backend Service Suite**:
  - Command: `npm test && npm run typecheck && npm run build` in `backend/`
  - Result: **42 test files passed, 456 tests passed**, TypeScript typecheck passed with 0 errors, build completed cleanly.
- **Sentry-GitHub Triage Sync Suite**:
  - Command: `npm test && npm run typecheck && npm run build` in `tools/sentry-github-sync/`
  - Result: **3 test files passed, 38 tests passed**, TypeScript typecheck passed with 0 errors, build completed cleanly.
- **Secret & Cleanliness Verification**:
  - `git diff --check`: PASSED (0 whitespace errors).
  - Secret scan: Confirmed no real secret values committed; all secret strings confined to synthetic unit test fixtures.

---

## 3. Files Created / Modified

- `client/androidApp/build.gradle.kts` (modified)
- `docker-compose.yml` (modified)
- `.env.example` (modified)
- `backend/.env.example` (modified)
- `backend/package.json` (modified)
- `backend/package-lock.json` (modified)
- `scripts/deploy-vps.sh` (modified)
- `AGENTS.md` (modified)
- `docs/notes/local-development.md` (modified)
- `docs/notes/vps-deployment.md` (modified)
- `docs/notes/observability-operations.md` (created/modified)
- `docs/decisions/2026-08-21-hosted-sentry-observability.md` (created)
- `docs/runbooks/sentry-setup.md` (created)
- `docs/runbooks/observability-triage.md` (created)
- `docs/runbooks/production-rollout.md` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-12-release-runbooks.md` (created)

---

## 4. Next Steps

- Proceed to **Task 13: Final Automated Verification And Live Canary**.
