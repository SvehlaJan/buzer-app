# Final Handoff: Buzer Observability and Bug Reporting (Tasks 1–13)

**Date:** 2026-08-22  
**Implementation Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Design Spec:** `docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`  
**ADR:** `docs/decisions/2026-08-21-hosted-sentry-observability.md`  
**Status:** IMPLEMENTATION COMPLETE — LIVE ROLLOUT CANARY PENDING  

---

## 1. Executive Summary

The comprehensive Observability and Bug Reporting subsystem for the Buzer multiplatform application (Android Kotlin Multiplatform client and Node.js/Fastify backend service) has been fully implemented, verified, and audited across Tasks 1 through 13.

The system provides:
1. **Zero-Friction In-App Bug Reporting**: Users can shake their physical Android device or tap the Home overflow menu ("Report a bug") to open Sentry's native user feedback dialog.
2. **Paired Diagnostic Capsules**: Every report captures an `android-diagnostics-v1.json` capsule (device state, task snapshot, alarms, logs, network history, screenshot, thread dump) and a paired `backend-diagnostics-v1.json` capsule (SQLite state, Todoist poller status, WhatsApp health, server logs), correlated deterministically by `report_session_id`.
3. **Hardened Multi-Stage Redaction**: Secrets (Bearer tokens, Todoist API keys, Sentry auth tokens, WhatsApp QR/session keys, passwords, cookies) are scrubbed before storage in ring buffers and before network transmission.
4. **Critical Runtime Non-Fatal Telemetry**: Automatic telemetry captures alarm drift, overlay failures, database inconsistencies, Todoist poller outages (threshold >= 3 failures or 15m), and WhatsApp disconnects/escalation failures with bounded ring buffers.
5. **Deduplicated Sentry-to-GitHub Triage**: Standalone automated sync tool (`tools/sentry-github-sync`) queries unresolved Sentry issues and files/updates deduplicated GitHub issues with structured reproduction checklists.
6. **Production Deployment & Release Operations**: Source-map uploads, release tags (`app.buzer@<version>+<sha>`), docker compose wiring, and operational runbooks for Sentry setup, triage, and production rollout.

---

## 2. Architecture & Subsystem Summary

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             Android Client Subsystem                             │
│                                                                                  │
│  ┌──────────────────────┐   ┌───────────────────────┐   ┌─────────────────────┐  │
│  │ Accelerometer Shake  │──▶│ ManualReport          │──▶│ Sentry Native       │  │
│  │ / Overflow Menu      │   │ Coordinator (Mutex)   │   │ Feedback Dialog     │  │
│  └──────────────────────┘   └───────────┬───────────┘   └──────────┬──────────┘  │
│                                         │                          │             │
│                                         ▼                          ▼             │
│                             ┌───────────────────────┐   ┌─────────────────────┐  │
│                             │ Diagnostic Collectors │   │ Sentry Android SDK  │  │
│                             │ - DeviceContext       │   │ (Envelope & Replay) │  │
│                             │ - ApplicationState    │   └──────────┬──────────┘  │
│                             │ - AlarmStateCollector │              │             │
│                             │ - ScreenshotCollector │              │             │
│                             │ - ThreadDumpCollector │              │             │
│                             │ - AppLog (1000 lines) │              │             │
│                             │ - NetworkHistory(100) │              │             │
│                             └───────────┬───────────┘              │             │
│                                         │ (android-diagnostics-v1) │             │
│                                         ▼                          │             │
│                             ┌───────────────────────┐              │             │
│                             │ POST /diagnostics/    │              │             │
│                             │ reports (Ktor Client) │              │             │
│                             └───────────┬───────────┘              │             │
└─────────────────────────────────────────┼──────────────────────────┼─────────────┘
                                          │                          │
                                          │ HTTP (X-Request-ID,      │ Sentry DSN
                                          │   traceparent, bearer)   │
                                          ▼                          ▼
┌─────────────────────────────────────────────────────────────────┐  │
│                    Backend Service Subsystem                    │  │
│                                                                 │  │
│  ┌───────────────────────┐   ┌───────────────────────────────┐  │  │
│  │ POST /diagnostics/    │──▶│ Idempotency Check             │  │  │
│  │ reports (Fastify)     │   │ (SQLite `diagnostic_reports`) │  │  │
│  └───────────────────────┘   └──────────────┬────────────────┘  │  │
│                                             │                   │  │
│                                             ▼                   │  │
│                              ┌───────────────────────────────┐  │  │
│                              │ Backend Capsule Assembly      │  │  │
│                              │ - SQLite schema & WAL status  │  │  │
│                              │ - Todoist poller health       │  │  │
│                              │ - WhatsApp Web bridge status  │  │  │
│                              │ - Server log buffer (1000)    │  │  │
│                              └──────────────┬────────────────┘  │  │
│                                             │ (backend-diag-v1) │  │
│                                             ▼                   │  │
│                              ┌───────────────────────────────┐  │  │
│                              │ Sentry Node SDK v9            │  │  │
│                              │ (Emits paired Sentry Event)   │  │  │
│                              └──────────────┬────────────────┘  │  │
└─────────────────────────────────────────────┼───────────────────┼──┘
                                              │                   │
                                              ▼                   ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              Sentry Cloud Platform                               │
│                                                                                  │
│   Project: `buzer-android`                     Project: `buzer-backend`          │
│   Event: `buzer.android.manual_report`         Event: `buzer.backend.manual_report`
│   Tags: `report_session_id`, `device_id`       Tags: `report_session_id`, etc.   │
│   Attachments: `.json`, `.txt`, `.webp`        Attachments: `.json`, `.txt`      │
└─────────────────────────────────────────────┬────────────────────────────────────┘
                                              │
                                              │ Polled by tools/sentry-github-sync
                                              ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            GitHub Issues & Triage                                │
│                                                                                  │
│   Issue: [Sentry][android|backend] <Issue Title>                                 │
│   Deduplication Marker: <!-- buzer-sentry-issue-id:<id> -->                      │
│   Labels: `observability`, `bug`, `android` / `backend`                          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Component Breakdown Across Tasks 1–13

| Task | Area | Key Artifacts & Modules | Purpose |
| :--- | :--- | :--- | :--- |
| **Task 1** | Shared Primitives | `DiagnosticModels.kt`, `DiagnosticRedactor.kt`, `AppLog.kt` | Deterministic models, multi-sink logging, token/secret redaction engine. |
| **Task 2** | Shared Ktor Client | `BuzerApiClientDiagnostics.kt`, `DiagnosticReportDto.kt`, `NetworkExchangeRecord.kt` | Ring buffer of 100 network exchanges, traceparent propagation, report submission client. |
| **Task 3** | Android Sentry Boundary | `Observability.kt`, `SentryAndroidObservability.kt`, `BuzerApplication.kt` | Isolated production/test boundary, foreground activity tracker, debug build isolation. |
| **Task 4** | Android Collectors | `DeviceContextCollector.kt`, `ScreenshotCollector.kt`, `ThreadDumpCollector.kt`, `AndroidDiagnosticCapsule.kt` | State snapshotting, 8 MiB budget enforcement, priority-based truncation. |
| **Task 5** | Android Feedback UI | `ManualReportCoordinator.kt`, `HomeScreen.kt`, native shake listener | Mutex state machine, accelerometer shake triggering, overflow menu fallback. |
| **Task 6** | Android Context Wiring | `EnforcementCoordinator.kt`, `AlarmCoordinator.kt`, `DataLayer.kt` | Injected telemetry for critical non-fatal failures with stable tagging. |
| **Task 7** | Backend Foundation | `instrument.ts`, `backendObservability.ts`, `redaction.ts`, `logBuffer.ts` | Sentry Node SDK v9 bootstrap, 1000-entry log buffer, Fastify request correlation. |
| **Task 8** | Backend Report Endpoint | `POST /diagnostics/reports`, SQLite `diagnostic_reports` table | Rate-limiting (6/min), idempotency, paired capsule generation and Sentry dispatch. |
| **Task 9** | Backend Signals | `todoistPoller.ts`, `whatsappWebSender.ts`, `escalationService.ts` | Outage alerts (>=3 failures or 15m), WhatsApp 5m disconnects, QR redaction. |
| **Task 10** | Cross-Process E2E | `e2eServer.ts`, `BugReportRealBackendTest.kt`, `observability-contract-v1.md` | Verification of contract, correlation round-trips, and zero secret leakage. |
| **Task 11** | Sentry-GitHub Triage | `tools/sentry-github-sync/`, `scripts/sentry-triage.mjs`, `sentry-triage.yml` | Unresolved issue polling, deduplicated GitHub issue syncing with reproduction checklists. |
| **Task 12** | Release & Runbooks | `sentry:sourcemaps`, `deploy-vps.sh`, ADRs, operational runbooks | Source maps, ProGuard mappings, release tracking, setup & rollout guides. |
| **Task 13** | Verification & Handoff | Full automated suites, secret leak audit, final comprehensive handoff | Full repo test pass, secret verification, live canary operator instructions. |

---

## 3. Automated Verification Matrix

Every automated test suite across all modules was executed cleanly in the integration worktree:

```text
======================================================================================
 MODULE                       COMMAND                                  RESULT
======================================================================================
 1. Android Shared & App      ./gradlew :shared:testAndroidHostTest    PASS
                              :androidApp:testDebugUnitTest            (74 tasks executed,
                              :androidApp:assembleDebug                all tests passed,
                                                                       debug APK built)

 2. Backend Service           npm test                                 PASS
                              npm run typecheck                        (42 test files,
                              npm run build                            456 tests passed,
                                                                       0 type errors)

 3. Sentry-GitHub Sync Tool   npm test                                 PASS
                              npm run typecheck                        (3 test files,
                              npm run build                            38 tests passed,
                                                                       0 type errors)

 4. Sentry Triage CLI Test    node --test scripts/sentry-triage.test.mjs PASS (1 test passed)

 5. Secret Leak Audit         git grep for tokens/DSNs                 0 MATCHES (CLEAN)
======================================================================================
```

### Exact Command Outputs

#### 1. Android Client Suite
```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
# Output:
# BUILD SUCCESSFUL in 16s
# 74 actionable tasks: 74 executed
```

#### 2. Backend Service Suite
```bash
npm test
# Output:
# Test Files  42 passed (42)
# Tests       456 passed (456)
# Duration    2.57s

npm run typecheck
# Output:
# tsc -p tsconfig.json --noEmit (exit code 0)

npm run build
# Output:
# tsc -p tsconfig.build.json && mkdir -p dist/db && cp src/db/schema.sql dist/db/schema.sql (exit code 0)
```

#### 3. Sentry-GitHub Sync Tool Suite
```bash
npm test
# Output:
# Test Files  3 passed (3)
# Tests       38 passed (38)
# Duration    154ms

npm run typecheck
# Output:
# tsc --noEmit (exit code 0)

npm run build
# Output:
# tsc (exit code 0)
```

#### 4. Sentry CLI Triage Test
```bash
node --test scripts/sentry-triage.test.mjs
# Output:
# ✔ scripts/sentry-triage.mjs CLI wrapper can be invoked with --dry-run (722.6605ms)
# tests 1, pass 1, fail 0
```

---

## 4. Secret Leak Audit Evidence

A complete repository scan was conducted to verify that no live credentials, active auth tokens, or private DSNs are committed to git:

```bash
git grep -i -E "TODOIST_API_TOKEN=[a-zA-Z0-9]{10,}" || true
# Matches: 0

git grep -i -E "ANDROID_API_TOKEN=[a-zA-Z0-9]{10,}" || true
# Matches: 0

git grep -i -E "SENTRY_AUTH_TOKEN=[a-zA-Z0-9]{10,}" || true
# Matches: 0

git grep -i -E "sntrys_[a-zA-Z0-9]{10,}" || true
# Matches: 0

git grep -i -E "https://[a-f0-9]+@[a-z0-9.]+\.ingest\.sentry\.io/[0-9]+" || true
# Matches: 0
```

All secret occurrences are strictly confined to synthetic unit test fixtures (e.g. `mock-secret-token-12345`) and example configuration files (`.env.example`).

---

## 5. Live Canary Rollout Guide (For Human Operator)

The codebase is ready for live canary verification once Hosted Sentry Cloud credentials are provisioned. Follow these step-by-step instructions from `docs/runbooks/sentry-setup.md` and `docs/runbooks/production-rollout.md`.

### Step 1: Provision Hosted Sentry Projects & Keys
1. Log into Sentry Cloud: [https://sentry.io](https://sentry.io).
2. Create project `buzer-android` (Platform: Android Kotlin). Copy Client Key DSN.
3. Create project `buzer-backend` (Platform: Node.js Fastify). Copy Client Key DSN.
4. Create Auth Token (`SENTRY_AUTH_TOKEN`) with scopes: `project:releases`, `org:read`, `event:read`, `issue:read`.

### Step 2: Configure Environment Secrets
- **Local Android**: Add to `client/local.properties` (ignored by git):
  ```properties
  SENTRY_ANDROID_DSN=https://<key>@<org>.ingest.sentry.io/<android-project-id>
  ```
- **VPS Backend**: Add to `/home/skclaw/buzer-app/.env`:
  ```dotenv
  SENTRY_BACKEND_DSN=https://<key>@<org>.ingest.sentry.io/<backend-project-id>
  SENTRY_ENVIRONMENT=production-vps
  SENTRY_RELEASE=buzer-backend@0.1.0+vps
  ```
- **GitHub Actions Secrets**: Add `SENTRY_AUTH_TOKEN` in repository settings (`SvehlaJan/buzer-app`).

### Step 3: Deploy Backend to VPS & Upload Source Maps
```bash
ssh skclaw@<vps-ip>
cd /home/skclaw/buzer-app
scripts/deploy-vps.sh
```
Verify health endpoint returns `200 OK`:
```bash
curl -i http://127.0.0.1:18432/health
```

### Step 4: Build & Install Release Android APK
```bash
cd client
export SENTRY_AUTH_TOKEN="<your-sentry-token>"
./gradlew :androidApp:assembleRelease
adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```
Verify ProGuard mapping upload succeeded in Sentry **Project Settings > ProGuard & Native**.

### Step 5: Execute Physical Smoke Verification
1. Launch Buzer on the physical Android device.
2. Shake the physical device to trigger the native bug report form (or open Home top-right overflow menu -> `Report a bug`).
3. Enter `Canary smoke test verification` and submit.
4. Open Sentry Cloud:
   - In `buzer-android`: verify `buzer.android.manual_report` event exists with `report_session_id`, screenshot, logs, and thread dump.
   - In `buzer-backend`: search for the same `report_session_id` and verify paired `buzer.backend.manual_report` event exists with SQLite counts and Todoist/WhatsApp health.
   - Confirm **ZERO token/secret leaks** in tags, context, breadcrumbs, or attached JSON files.

### Step 6: Test Sentry-GitHub Triage Workflow
```bash
gh workflow run sentry-triage.yml
```
Verify a new GitHub Issue is created with the `[Sentry]` prefix, reproduction checklist, and deduplication comment marker (`<!-- buzer-sentry-issue-id:<id> -->`). Once verified, enable the scheduled cron in `.github/workflows/sentry-triage.yml`.

---

## 6. Next Recommended Steps

1. **Operator Credential Provisioning**: Follow Section 5 above to provision live Hosted Sentry DSNs and Auth Tokens.
2. **Merge Integration Branch**: Merge the tested observability integration branch to master.
3. **Continuous Monitoring**: Verify Sentry Alert Rules (fatal crashes, ANRs, poller outage after 3 failures or 15m) notify the operator via preferred channel (Email / Telegram).
