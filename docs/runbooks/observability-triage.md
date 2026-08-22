# Observability Triage Runbook

**Purpose:** Operational guide for triaging, investigating, and resolving bugs, crashes, ANRs, and manual user reports reported through Sentry and GitHub Issues.

---

## 1. Triage Workflow Overview

```text
[ Sentry Issue Detected ]
           │
           ▼
[ Automated GitHub Sync (tools/sentry-github-sync) ]
           │
           ├─► New Issue: Creates [Sentry][platform] Issue with correlation tags
           ├─► Existing Issue: Appends comment with latest event context
           └─► Regressed Issue: Reopens issue and comments with regression details
           │
           ▼
[ Developer Investigation ]
  1. Inspect Sentry Event & Stack Trace (ProGuard / Node Source Maps)
  2. Locate Paired Android + Backend Reports using `report_session_id`
  3. Download & Decode Diagnostic Capsules (`android-diagnostics-v1.json`, `backend-diagnostics-v1.json`)
  4. Analyze Ring Buffer Logs & Network History
  5. Watch Session Replay (for UI/Visual reports)
           │
           ▼
[ Reproduction & Fix ]
  1. Write failing regression test in local worktree
  2. Implement scoped bugfix
  3. Verify against full test suite
           │
           ▼
[ Release & Verification ]
  1. Deploy Backend / Build Android APK with new Git SHA release
  2. Mark Sentry Issue "Resolved in next release"
  3. Verify resolution in production canary
```

---

## 2. Investigating Correlated Bug Reports

Manual bug reports submit correlated telemetry across both Android and backend systems.

### 2.1 Locating Paired Reports
1. In the GitHub Issue or Sentry Issue, locate the `report_session_id` tag (e.g. `report_session_id:550e8400-e29b-41d4-a716-446655440000`).
2. In Sentry:
   - Search across projects: `report_session_id:550e8400-e29b-41d4-a716-446655440000`
   - You will see two linked events:
     - **Android Project (`buzer-android`)**: `message:buzer.android.manual_report`
     - **Backend Project (`buzer-backend`)**: `message:buzer.backend.manual_report`
3. Cross-Reference IDs:
   - The Android event contains `backend_diagnostic_event_id` (if backend was reachable).
   - The Backend event contains `android_diagnostic_event_id` and tags matching `taskId`, `occurrenceKey`, and `actionId`.

### 2.2 Inspecting Diagnostic Capsules & Attachments
In Sentry, navigate to the event's **Attachments** tab:

1. **`android-diagnostics-v1.json`**:
   - Check `currentActivity`, `foreground`, `activeBlocker`.
   - Inspect `cachedSnapshot` for task state discrepancies.
   - Inspect `scheduledAlarms` to verify if alarm triggers were missed or suppressed.
   - Check `readinessStatus` for permission revocations (Exact Alarms, Overlays).
2. **`android-app-log.txt`**:
   - Contains the last 1,000 log messages from Kermit/AppLog (15-minute window).
   - Look for `ERROR` and `WARN` lines preceding the incident.
3. **`android-network-history.json`**:
   - Contains recent HTTP exchanges with the backend.
   - Check for HTTP 4xx/5xx status codes, request durations, and timeout errors.
4. **`android-threads.txt`**:
   - Thread dump for diagnosing ANRs, lock contention, or background worker stalls.
5. **`android-screenshot.webp` & Session Replay**:
   - View visual UI state and user interaction breadcrumbs leading up to the report.
6. **`backend-diagnostics-v1.json` & `backend-log.txt`**:
   - Check Todoist sync status (`consecutiveFailureCount`, `lastPollSuccess`).
   - Check WhatsApp bridge status (`providerMode`, `clientStatus`).
   - Check SQLite database status and recent action journal executions.

---

## 3. Stack Trace Demangling & Source Maps

### 3.1 Android ProGuard Demangling
- Android release builds are obfuscated via R8/ProGuard.
- Mapping files are automatically uploaded during CI/release builds when `SENTRY_AUTH_TOKEN` is present.
- If stack frames appear obfuscated (e.g. `a.b.c(SourceFile:1)`):
  1. Verify the release version in Sentry matches `app.buzer@<version>+<sha>`.
  2. Check **Project Settings > ProGuard & Native** for uploaded mapping files matching the build's UUID.
  3. To upload manually if CI upload failed:
     ```bash
     cd client
     ./gradlew :androidApp:uploadSentryProguardMappingsRelease
     ```

### 3.2 Backend Source Maps
- TypeScript build artifacts in `backend/dist` include inline or external `.map` files.
- Sentry Node SDK automatically maps stack frames when Node source maps are available or when debug IDs match the release artifacts.

---

## 4. Issue Resolution & Regression Detection

1. **Resolving Sentry Issues**:
   - When a bug fix is committed and merged to master, note the commit SHA.
   - In Sentry, click **Resolve** -> **Resolved in next release** (or specify release `app.buzer@0.1.0+<commit-sha>`).
2. **Automated Regression Detection**:
   - If the same error signature recurs in a subsequent release or build:
     - Sentry marks the issue as **Regressed**.
     - `tools/sentry-github-sync` detects the regression status, automatically reopens the corresponding GitHub issue, and posts a comment with the new event link, release ID, and stack trace.
3. **Closing GitHub Issues**:
   - Only close GitHub issues after the fix has been verified via the canary deployment or test suite.
