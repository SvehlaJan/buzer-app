# Observability Triage Runbook

**Purpose:** Operational guide for triaging, investigating, and resolving bugs, crashes, ANRs, backend 5xx errors, and user feedback reported through Hosted Sentry.

---

## 1. Triage Workflow Overview

```text
┌────────────────────────────────────────────────────────┐
│                   New Sentry Event                     │
│  (Crash, ANR, 5xx, Background Failure, User Feedback)  │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                Inspect in Sentry UI                    │
│  1. Check Release & Environment                        │
│  2. Inspect Stack Trace (ProGuard / Node Source Maps)  │
│  3. Watch Error Replay & Screenshot / View Hierarchy   │
│  4. Read Breadcrumbs (AppLog, navigations, requests)   │
│  5. Check Tags (request_id, task_id, action_id)        │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                 Correlate & Reproduce                  │
│  • Search cross-project events by request_id / task_id │
│  • Write failing test (JVM unit, Vitest, or e2e)       │
│  • (Optional) Create GitHub issue if work is queued    │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                  Fix & Resolve                         │
│  1. Implement and verify scoped fix                    │
│  2. Commit and deploy release                          │
│  3. Mark Sentry Issue "Resolved in next release"       │
└────────────────────────────────────────────────────────┘
```

---

## 2. Investigating Issues in Sentry

When an alert triggers or during regular triage, open the issue in [Sentry Cloud](https://sentry.io/organizations/jans-gi/issues/):

### 2.1 Stack Trace & Source Context
- **Android**: Release builds are de-obfuscated using uploaded ProGuard mappings. Verify the line number and function names. Source context will show surrounding code when GitHub integration is active.
  - If stack frames appear obfuscated (e.g. `a.b.c(SourceFile:1)`), check that the release mapping was uploaded via Gradle `uploadSentryProguardMappingsRelease`.
- **Backend**: Node.js stack traces are symbolicated with TypeScript source maps.

### 2.2 Error Replay & Visual Evidence
- **Error Replay**: For Android errors and feedback submissions, Sentry captures a session replay up to the moment of failure. Click the **Replays** tab on the issue to view user interactions, screen transitions, and UI state leading to the issue.
- **Screenshots & View Hierarchy**: If captured, inspect the native window screenshot and view hierarchy attached to the event.

### 2.3 Breadcrumbs Timeline
- Review the chronological breadcrumb trail:
  - `AppLog` entries (debug, info, warning, error) converted into breadcrumbs.
  - UI navigation breadcrumbs (screen changes, blocker activity launches).
  - HTTP network breadcrumbs (method, URL, status code, duration).
  - System lifecycle events (background/foreground transitions).

For standalone native feedback, breadcrumbs may not be embedded in the feedback event.
Open the project's **Logs** view and search the matching release/environment and a narrow
timestamp window instead.

### 2.4 Correlation Tags
Inspect standard tags to pinpoint context:
- `request_id`: The HTTP `X-Request-ID` header. Search across `buzer-android` and `buzer-backend` with `request_id:<id>` to correlate client-server interactions.
- `task_id` / `occurrence_key`: The Todoist / Buzer task identity involved in the failure.
- `action_id`: The client-side offline action mutation ID.
- `screen` / `app_section`: The UI location where the error occurred.
- `blocker_type`: The active enforcement blocker (`start`, `deadline`, `morning_review`, `none`).
- `marker`: Backend background-failure marker. Current emitted values are `buzer.backend.todoist_poll_failure` (after three consecutive failures), `buzer.backend.whatsapp_auth_failure`, `buzer.backend.whatsapp_disconnected`, and `buzer.backend.escalation_delivery_failure`.

---

## 3. Investigating User Feedback Submissions

When user feedback is submitted (via shake gesture, Home screen button, or Readiness Gate):

1. Navigate to **User Feedback** in the `buzer-android` project.
2. Read the user's description in the feedback issue.
3. Review the attached **Error Replay** to see what actions preceded the report.
4. Open **Logs**, filter to the feedback's release/environment and a narrow timestamp
   window, and locate `bug_report_submitted`. Read the preceding `AppLog` records; use
   `bug_report_form_opened` as the beginning of the report interaction.
5. If an associated error or crash occurred, follow the related Sentry issue and inspect
   its breadcrumbs and correlation tags (`screen`, `task_id`, `request_id`).

There is deliberately no report-session ID or associated diagnostic event. Timestamp,
release, environment, and the two stable markers are the complete manual correlation
contract.

---

## 4. Cross-System Correlation

Because there is no custom paired capsule or distributed trace header, client and server events are correlated cleanly via standard identifiers:

1. **By Request ID**:
   - If an Android event shows a failed API call with `X-Request-ID: req-abc-123`, search `buzer-backend` for `request_id:req-abc-123` to find the exact 5xx backend exception.
2. **By Task ID / Occurrence Key**:
   - Search either project for `task_id:<id>` or `occurrence_key:<key>` to find all related frontend actions, backend polling updates, or WhatsApp escalation events.
3. **By Timestamp & Release**:
   - Filter events within a 5-minute window matching the release identifier `app.buzer@<version>+<sha>`.

---

## 5. Reproduction & Local Testing

Before implementing a fix, write a focused reproduction test:

- **Shared / Client Logic**:
  ```bash
  cd client
  ANDROID_HOME=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --tests '*YourTest*'
  ```
- **Android UI / Enforcement**:
  ```bash
  ANDROID_TEST_CLASS=app.buzer.android.YourScreenTest scripts/e2e-real-backend.sh
  ```
- **Backend Service / Route**:
  ```bash
  cd backend
  npm test -- --run yourTest
  ```

---

## 6. Resolving Sentry Issues

1. **Mark as Resolved in Next Release**:
   - After merging the fix, click **Resolve** -> **Resolved in next release** in Sentry.
   - Or specify the specific release string: `app.buzer@<version>+<sha>`.
2. **Regression Tracking**:
   - If the same error signature occurs in a newer release, Sentry will automatically mark the issue as **Regressed** and trigger the regression alert.
3. **Manual GitHub Issues**:
   - For bugs requiring substantial work or tracking across sessions, create a GitHub issue manually on `SvehlaJan/buzer-app` with the Sentry issue link.
