# Production Rollout & Canary Runbook

**Purpose:** Step-by-step procedure for deploying, verifying, and rolling out the Buzer Observability and Bug Reporting system to production VPS and Android devices.

---

## 1. Staged Rollout Overview

```text
Phase 1: Sentry Cloud Provisioning & Secrets Configuration
   │
   ▼
Phase 2: Backend Production VPS Deployment
   │
   ▼
Phase 3: Android Release APK Build & ProGuard Mapping Upload
   │
   ▼
Phase 4: Live Canary Smoke Verification (Device + VPS)
   │
   ▼
Phase 5: Activate Automated GitHub Triage Workflow
```

---

## 2. Step-by-Step Rollout Procedure

### Phase 1: Sentry Cloud Provisioning & Secrets Setup
1. **Verify Projects in Sentry**:
   - Organization: `svehla`
   - Project 1: `buzer-android`
   - Project 2: `buzer-backend`
2. **Obtain DSNs & API Tokens**:
   - `SENTRY_ANDROID_DSN`: Client Keys under `buzer-android` settings.
   - `SENTRY_BACKEND_DSN`: Client Keys under `buzer-backend` settings.
   - `SENTRY_AUTH_TOKEN`: Created under Org/User Settings with `project:releases`, `org:read`, `event:read`, `issue:read`.
3. **Configure Secrets**:
   - Android Local: Add `SENTRY_ANDROID_DSN=<dsn>` to `client/local.properties`.
   - VPS Environment: Add `SENTRY_BACKEND_DSN=<dsn>` to `/home/skclaw/buzer-app/.env`.
   - GitHub Actions: Add `SENTRY_AUTH_TOKEN` secret to repository `SvehlaJan/buzer-app`.

---

### Phase 2: Backend Production VPS Deployment
1. SSH into VPS host:
   ```bash
   ssh skclaw@<vps-ip>
   cd /home/skclaw/buzer-app
   ```
2. Verify `.env` contains:
   ```dotenv
   SENTRY_BACKEND_DSN=https://<key>@<org>.ingest.sentry.io/<project-id>
   SENTRY_ENVIRONMENT=production-vps
   SENTRY_RELEASE=buzer-backend@0.1.0+vps
   ```
3. Deploy latest release:
   ```bash
   scripts/deploy-vps.sh
   ```
4. Verify backend health check returns 200 OK:
   ```bash
   curl -i http://127.0.0.1:18432/health
   ```
5. Check backend logs for Sentry initialization:
   ```bash
   docker compose logs --tail=50 backend
   ```

---

### Phase 3: Android Alpha APK Build & ProGuard Upload
1. Set the Sentry auth token in the build environment:
   ```bash
   export SENTRY_AUTH_TOKEN="<your-sentry-auth-token>"
   ```
2. Build Android release bundle/APK with ProGuard obfuscation and automatic mapping upload:
   ```bash
   cd client
   ./gradlew :androidApp:assembleRelease
   ```
3. Verify in Sentry dashboard (**Project Settings > ProGuard & Native**) that the ProGuard mapping file was successfully uploaded for the release version.
4. Install release APK onto target physical Android device:
   ```bash
   adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
   ```

---

### Phase 4: Live Canary Smoke Verification
Perform the following smoke tests on the physical device and VPS:

1. **Physical Shake & Bug Report Form**:
   - Shake the physical Android device.
   - Verify Sentry's native `Report a bug` dialog opens without requesting additional permissions.
   - Enter a test description (e.g. `Canary smoke test verification`) and submit.
   - Verify submission succeeds with visual confirmation.

2. **Verify Cross-Process Report Ingest in Sentry**:
   - Log into Sentry Cloud.
   - Under `buzer-android`: Locate the newly created `buzer.android.manual_report` issue.
   - Check tags for `report_session_id`.
   - Under `buzer-backend`: Search for the same `report_session_id`.
   - Locate the correlated `buzer.backend.manual_report` issue.
   - Verify attachments are present on both events (`android-diagnostics-v1.json`, `android-app-log.txt`, `backend-diagnostics-v1.json`, `backend-log.txt`).
   - Verify **ZERO token or secret leaks** exist in tags, context, breadcrumbs, or attached JSON files.

3. **Verify Handled Non-Fatal Telemetry**:
   - Trigger a non-fatal operation error (or test alarm reconcile).
   - Verify handled telemetry is captured in `buzer-android` without interrupting UI or app execution.

---

### Phase 5: Activate Automated GitHub Triage
1. Test triage dry-run locally:
   ```bash
   cd tools/sentry-github-sync
   npm run start -- --dry-run \
     --sentry-token="$SENTRY_AUTH_TOKEN" \
     --sentry-org="svehla" \
     --sentry-project="buzer-android" \
     --github-token="$GITHUB_TOKEN" \
     --repo="SvehlaJan/buzer-app"
   ```
2. Trigger the GitHub Actions workflow manually via `workflow_dispatch`:
   ```bash
   gh workflow run sentry-triage.yml
   ```
3. Verify that GitHub Issue was created or updated with deterministic Sentry tags and correlation markers (`<!-- sentry-fingerprint: ... -->`).
4. Enable the scheduled cron in `.github/workflows/sentry-triage.yml` if automated periodic syncing is desired.
