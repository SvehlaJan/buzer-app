# Sentry Setup Runbook

**Organization:** `svehla` (or `jans-gi`)  
**Projects:** `buzer-android`, `buzer-backend`  
**Purpose:** Setup guide for Hosted Sentry projects, DSNs, API tokens, metric & alert rules, and GitHub repository integration.

---

## 1. Sentry Projects Setup

### Organization Configuration
1. Log into Sentry Cloud: [https://sentry.io](https://sentry.io).
2. Organization name: `svehla` (slug: `svehla` or `jans-gi`).
3. Ensure the organization timezone is set to `Europe/Prague` (or your local timezone) and data scrubbers are enabled.

### Project 1: `buzer-android`
1. Navigate to **Settings > Projects > Create Project**.
2. Platform: **Android** (Kotlin).
3. Project name: `buzer-android`.
4. Set default alert rule: Alert me for every new issue.
5. In **Project Settings > General Settings**:
   - Verify Project Slug: `buzer-android`.
   - Store the generated Client Key (DSN) securely (for `SENTRY_ANDROID_DSN`).
6. In **Project Settings > ProGuard & Native**:
   - Ensure ProGuard / R8 mapping file processing is enabled.
   - Ensure Source Context repository linking is enabled.

### Project 2: `buzer-backend`
1. Navigate to **Settings > Projects > Create Project**.
2. Platform: **Node.js** (Fastify / Express).
3. Project name: `buzer-backend`.
4. In **Project Settings > General Settings**:
   - Verify Project Slug: `buzer-backend`.
   - Store the generated Client Key (DSN) securely (for `SENTRY_BACKEND_DSN`).
5. In **Project Settings > Source Maps**:
   - Ensure JavaScript/TypeScript source map symbolication is enabled.

---

## 2. API & Auth Token Generation

### 2.1 Release & Source Map Upload Token (`SENTRY_AUTH_TOKEN`)
Used by Gradle (`sentry-android-gradle-plugin`) during Android APK builds and deploy scripts for source-map/ProGuard mapping uploads.

1. Navigate to **User Settings > Auth Tokens > Create New Token** (or **Org Settings > Developer Settings > Internal Integrations**).
2. Name: `buzer-release-uploader`.
3. Required Scopes:
   - `project:read`
   - `project:releases`
   - `org:read`
   - `event:read`
4. Securely store the generated token in your password manager / CI secret store as `SENTRY_AUTH_TOKEN`.
   > **Note**: Never commit `SENTRY_AUTH_TOKEN` to git or bake it into production Docker images.

### 2.2 Triage & Issue Sync Token
Used by `tools/sentry-github-sync` and `.github/workflows/sentry-triage.yml` to query unresolved diagnostic issues and attachment metadata.

1. Navigate to **User Settings > Auth Tokens > Create New Token**.
2. Name: `buzer-github-triage-sync`.
3. Required Scopes:
   - `event:read`
   - `project:read`
   - `org:read`
   - `issue:read`
   - `member:read`
4. Save as GitHub Actions Secret `SENTRY_AUTH_TOKEN` in GitHub repository settings.

---

## 3. Metric & Alert Rules Configuration

Configure Sentry Alert Rules under **Alerts > Create Alert** for both projects:

### 3.1 `buzer-android` Alerts

1. **Fatal App Crashes & ANRs (Immediate Paging)**:
   - **Type**: Issues
   - **Condition**: When a new issue is created, OR when an issue changes state from resolved to unresolved (regression).
   - **Filter**: `level:fatal OR level:error OR is:unhandled`
   - **Action**: Send notification to Email / Telegram / Mobile Push.
   - **Frequency**: Perform actions every time conditions are met.

2. **Handled Non-Fatal Enforcement Failures**:
   - **Type**: Issues
   - **Condition**: When an event is captured with `enforcement_critical:true` or `data_consistency:true`.
   - **Action**: High-priority alert notification.

3. **User Feedback & Bug Reports**:
   - **Type**: Issues
   - **Condition**: When an event is captured with tag `feedback_submitted:true` or `message:"buzer.android.manual_report"`.
   - **Action**: Alert notification with feedback text and session ID.
   - *Exclusion*: Filter out events with `feedback_submitted:false` (abandoned/cancelled report sessions) from paging.

### 3.2 `buzer-backend` Alerts

1. **Backend Server Exceptions & Unhandled Crashes**:
   - **Type**: Issues
   - **Condition**: When a new issue is created with `level:fatal OR level:error`.
   - **Action**: Immediate alert notification.

2. **Todoist Polling Failure Outage**:
   - **Type**: Metric / Issues
   - **Condition**: When `consecutive_failure_count >= 3` OR when poll failure persists for > 15 minutes.
   - **Fingerprint**: `["todoist-poller-failure"]`
   - **Action**: Paging alert to operator.

3. **WhatsApp Escalation & Connection Failures**:
   - **Type**: Issues
   - **Condition**: When an event is captured with `component:whatsapp AND (status:disconnect_timeout OR action:escalation_send_failure)`.
   - **Action**: Immediate escalation alert.

---

## 4. GitHub Integration Configuration

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
6. **Note on Issue Sync**:
   - Do NOT enable Sentry's automated native GitHub issue creation plugin to avoid duplicate/unredacted issue spam.
   - Deduplicated issue creation is handled exclusively by the curated repository tool `tools/sentry-github-sync`.
