# Production Rollout & Canary Runbook

**Purpose:** Step-by-step procedure for deploying, verifying, and rolling out the Buzer Lean Observability system to production VPS and Android devices.

---

## 1. Staged Rollout Overview

```text
Phase 1: Sentry Cloud Provisioning & Secrets Setup
   │
   ▼
Phase 2: Backend Production VPS Deployment
   │
   ▼
Phase 3: Android Release APK Build & ProGuard Mapping Upload
   │
   ▼
Phase 4: Live Canary Smoke Verification (Device + VPS)
```

---

## 2. Step-by-Step Rollout Procedure

### Phase 1: Sentry Cloud Provisioning & Secrets Setup
1. **Verify Projects in Sentry**:
   - Organization: `jans-gi`
   - Project 1: `buzer-android` (`4511950606172240`)
   - Project 2: `buzer-backend` (`4511950617641040`)
2. **Obtain DSNs & API Tokens**:
   - `SENTRY_ANDROID_DSN`: Client Keys under `buzer-android` settings.
   - `SENTRY_BACKEND_DSN`: Client Keys under `buzer-backend` settings.
   - `SENTRY_AUTH_TOKEN`: Created under Org/User Settings with scopes `project:read`, `project:releases`, `org:read`.
3. **Configure Secrets**:
   - **Android Local Build**: Add `SENTRY_ANDROID_DSN=<dsn>` to `client/local.properties` (or export in environment).
   - **VPS Environment**: Add `SENTRY_BACKEND_DSN=<dsn>` to `/home/skclaw/buzer-app/.env`.
   - **CI / Local Release Environment**: Set `SENTRY_AUTH_TOKEN` in environment for ProGuard mapping and source map uploads.

---

### Phase 2: Backend Production VPS Deployment
1. SSH into the VPS host:
   ```bash
   ssh skclaw@<vps-ip>
   cd /home/skclaw/buzer-app
   ```
2. Verify `.env` contains:
   ```dotenv
   SENTRY_BACKEND_DSN=https://<key>@<org>.ingest.sentry.io/4511950617641040
   SENTRY_ENVIRONMENT=production-vps
   SENTRY_RELEASE=app.buzer@0.1.0+vps
   ```
3. Deploy the latest release using the deployment script:
   ```bash
   scripts/deploy-vps.sh
   ```
4. Verify backend health check returns 200 OK:
   ```bash
   curl -i http://127.0.0.1:18432/health
   ```
5. Check backend logs for clean Sentry initialization:
   ```bash
   docker compose logs --tail=50 backend
   ```

---

### Phase 3: Android Release APK Build & ProGuard Upload
1. Set the Sentry auth token and SDK root in your local build environment:
   ```bash
   export SENTRY_AUTH_TOKEN="<your-sentry-auth-token>"
   export ANDROID_HOME=/Users/svehla/Library/Android/sdk
   ```
2. Build the Android release APK with ProGuard obfuscation and mapping upload:
   ```bash
   cd client
   ./gradlew :androidApp:assembleRelease
   ```
3. If ProGuard mappings were not uploaded automatically (e.g. offline build), upload manually:
   ```bash
   ./gradlew :androidApp:uploadSentryProguardMappingsRelease
   ```
4. Verify in Sentry dashboard (**Settings > Projects > buzer-android > ProGuard & Native**) that mapping files are present for the build.
5. Install the release APK onto the target physical Android device:
   ```bash
   adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
   ```

---

### Phase 4: Live Canary Smoke Verification
Perform the following smoke tests on the physical device and VPS:

1. **Native Feedback Form (Shake & UI Button)**:
   - Shake the physical Android device, or click `Report a bug` on the Home screen or Foreground Readiness gate.
   - Verify Sentry's native feedback dialog opens without permission prompts or crashes.
   - Enter a test description (e.g. `Canary smoke test feedback`) and tap **Send report**.
   - Verify submission succeeds and feedback appears in `buzer-android` in Sentry.

2. **Android Telemetry Verification**:
   - Check the event in Sentry `buzer-android`.
   - Verify:
     - Release tag matches `app.buzer@<version>+<sha>`.
     - Environment matches `production-device`.
     - Error Replay and native screenshot / view hierarchy are attached.
     - Breadcrumbs show recent `AppLog` lines and UI events.
     - ProGuard stack frames are properly symbolicated.
     - **Zero secret leaks** (no bearer tokens, no API tokens, no cookies).

3. **Backend 5xx & Background Error Verification**:
   - Trigger a controlled 5xx route error or observe normal operation.
   - In Sentry `buzer-backend`, verify:
     - Release tag matches `app.buzer@0.1.0+vps`.
     - Environment matches `production-vps`.
     - Tags include `request_id`, `route`, and `status_code`.
     - Request body, cookies, and user fields are absent/null.
     - **Zero secret leaks**.
