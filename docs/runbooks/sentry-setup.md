# Sentry Setup Runbook

**Organization:** `jans-gi`
**Projects:** `buzer-android` (`4511950606172240`), `buzer-backend` (`4511950617641040`)
**Purpose:** Setup guide for Hosted Sentry projects, DSNs, API tokens, alert rules, and GitHub repository integration for source context.

---

## 1. Sentry Projects Setup

### Organization Configuration
1. Log into Sentry Cloud: [https://sentry.io](https://sentry.io).
2. Verify Organization slug: `jans-gi`.
3. Verify the organization timezone is set to `Europe/Prague` (or your local timezone) and data scrubbers are enabled.

### Project 1: `buzer-android`
1. Navigate to **Settings > Projects > Create Project** (or verify existing).
2. Platform: **Android** (Kotlin).
3. Project Slug: `buzer-android` (Project ID: `4511950606172240`).
4. In **Project Settings > Client Keys (DSN)**:
   - Copy the DSN for use as `SENTRY_ANDROID_DSN`.
5. In **Project Settings > ProGuard & Native**:
   - Ensure ProGuard / R8 mapping file processing is enabled.
   - Ensure Source Context repository linking is enabled.
6. In **Project Settings > Replays**:
   - Verify Session Replay is enabled (Error Replay rate: 100%, Session Replay: 0%).
7. In **Project Settings > User Feedback**:
   - Verify User Feedback widget is enabled.

### Project 2: `buzer-backend`
1. Navigate to **Settings > Projects > Create Project** (or verify existing).
2. Platform: **Node.js** (Fastify / TypeScript).
3. Project Slug: `buzer-backend` (Project ID: `4511950617641040`).
4. In **Project Settings > Client Keys (DSN)**:
   - Copy the DSN for use as `SENTRY_BACKEND_DSN`.
5. In **Project Settings > Source Maps**:
   - Ensure JavaScript/TypeScript source map symbolication is enabled.

---

## 2. API & Auth Token Generation

### Release & Source Map Upload Token (`SENTRY_AUTH_TOKEN`)
Used by Gradle (`sentry-android-gradle-plugin`) during Android release APK builds and deploy scripts for source-map/ProGuard mapping uploads.

1. Navigate to **User Settings > Auth Tokens > Create New Token** (or **Org Settings > Developer Settings > Internal Integrations**).
2. Name: `buzer-release-uploader`.
3. Required Scopes:
   - `project:read`
   - `project:releases`
   - `org:read`
4. Securely store the generated token in your password manager / CI secret store as `SENTRY_AUTH_TOKEN`.
   > **Note**: Never commit `SENTRY_AUTH_TOKEN` to git or bake it into production Docker images.

---

## 3. Environment Variables Configuration

| Variable | Location | Description |
| :--- | :--- | :--- |
| `SENTRY_ANDROID_DSN` | Gradle property, `client/local.properties`, or environment (in that order) | Client DSN for Android app |
| `SENTRY_BACKEND_DSN` | VPS `/home/skclaw/buzer-app/.env` | Client DSN for backend Node service |
| `SENTRY_ENVIRONMENT` | Android Gradle property / `local.properties` / environment, or VPS `.env` | `development-device`, `production-device`, `development-vps`, `production-vps` |
| `SENTRY_RELEASE` | Derived; do not configure as a static value | Android: `app.buzer@<versionName>+<git-sha-12>` from `GITHUB_SHA` or local Git. VPS: `app.buzer@0.1.0+<git-sha-12>` from `scripts/deploy-vps.sh` after checkout. |
| `SENTRY_AUTH_TOKEN` | Android Gradle property / `local.properties` / environment; deploy shell / CI environment for source maps | Only needed when uploading ProGuard mappings or source maps; never commit it. |

For the VPS, leave `SENTRY_RELEASE` out of `.env`. The deploy script exports the post-checkout SHA-derived value before invoking Docker Compose. An explicitly exported deploy-shell value overrides that default; use no override for normal deployments.

---

## 4. Alert Rules Configuration

Configure Sentry Alert Rules under **Alerts > Create Alert** for both projects:

### 4.1 `buzer-android` Alerts

1. **Fatal App Crashes & ANRs (Immediate Paging)**:
   - **Type**: Issues
   - **Condition**: When a new issue is created, OR when an issue changes state from resolved to unresolved (regression).
   - **Filter**: `level:fatal OR level:error OR is:unhandled`
   - **Action**: Send notification to Email / Telegram / Mobile Push.
   - **Frequency**: Perform actions every time conditions are met.

2. **User Feedback & Bug Reports**:
   - **Type**: Issues
   - **Condition**: When a new User Feedback issue is submitted (via shake gesture or UI button).
   - **Action**: Alert notification with feedback message and device metadata.

### 4.2 `buzer-backend` Alerts

1. **Backend 5xx Server Exceptions & Unhandled Crashes**:
   - **Type**: Issues
   - **Condition**: When a new issue is created with `level:fatal OR level:error`.
   - **Action**: Immediate alert notification.

2. **Todoist Polling Outage**:
   - **Type**: Issues / Metric
   - **Condition**: When an event is captured with `marker:buzer.backend.todoist_poll_failure` and `consecutive_failure_count >= 3`.
   - **Action**: High-priority alert to operator.

3. **WhatsApp Escalation & Disconnection Failures**:
   - **Type**: Issues
   - **Condition**: When an event is captured with any emitted marker: `buzer.backend.whatsapp_auth_failure`, `buzer.backend.whatsapp_disconnected`, or `buzer.backend.escalation_delivery_failure`.
   - **Action**: Immediate escalation alert.

---

## 5. GitHub Integration for Source Context

1. Navigate to **Settings > Integrations > GitHub**.
2. Install and authorize the Sentry GitHub App on repository `SvehlaJan/buzer-app`.
3. In **Settings > Projects > buzer-android > Repositories**:
   - Add repository `SvehlaJan/buzer-app`.
4. In **Settings > Projects > buzer-backend > Repositories**:
   - Add repository `SvehlaJan/buzer-app`.
5. **Code Mapping**:
   - Repository: `SvehlaJan/buzer-app`
   - Branch: `master`
   - Android Stack Trace root: `client/androidApp/src/main/kotlin/` -> `client/androidApp/src/main/kotlin/`
   - Backend Stack Trace root: `backend/src/` -> `backend/src/`
6. **No Automated Issue Bot**:
   - Sentry GitHub integration is used **exclusively for source context and stack trace linking**.
   - Automated sync tools (`tools/sentry-github-sync`) have been removed. Triage is performed directly in the Sentry UI.
