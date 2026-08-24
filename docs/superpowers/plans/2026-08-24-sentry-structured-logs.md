# Sentry Structured Logs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send existing Android `AppLog` records to searchable Sentry Logs and add simple native-feedback lifecycle markers without introducing report correlation state.

**Architecture:** The existing Android observability adapter remains the only Sentry boundary. It continues producing breadcrumbs and additionally emits sanitized structured logs; native feedback callbacks log stable open/submitted markers. No event ID, diagnostic event, report coordinator, attachment, or backend change is introduced.

**Tech Stack:** Kotlin 2.4.0, Sentry Android SDK 8.53.0, Gradle/JUnit, native Sentry User Feedback and Logs APIs.

---

## File Map

- `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`: enable and sanitize structured logs, mirror `AppLog` records, and configure feedback callbacks.
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`: retain only lightweight configuration assertions.
- `AGENTS.md`: route future observability work to this structured-logs amendment.
- `docs/superpowers/specs/2026-08-22-lean-observability-design.md`: record structured logs as the approved Android log destination.
- `docs/notes/observability-operations.md`: document runtime behavior and manual triage.
- `docs/runbooks/observability-triage.md`: explain timestamp-based feedback/log lookup.
- `docs/handoffs/2026-08-24-sentry-structured-logs.md`: verification and handoff evidence.

### Task 1: Enable searchable Android logs

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`

- [x] **Step 1: Add minimal configuration assertions**

Extend `sentryOptionsConfiguresPrivacySecurityAndFeedbackDefaults` with:

```kotlin
assertTrue(options.logs.isEnabled)
assertNotNull(options.logs.beforeSend)
assertNotNull(feedback.onFormOpen)
assertNotNull(feedback.onSubmitSuccess)
```

- [x] **Step 2: Run the focused test and verify it fails**

Run from `client/`:

```bash
ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AndroidObservabilityConfigTest*' --console=plain
```

Expected: FAIL because structured logs and feedback lifecycle callbacks are not configured.

- [x] **Step 3: Add log sanitization and delivery**

Add `sanitizeLogEvent(event: SentryLogEvent)` beside the existing event and breadcrumb sanitizers. Redact the log body with `DiagnosticRedactor.redactString`. Replace each attribute whose key is credential-like with `[REDACTED]`; redact every other attribute value with `redactAny` while preserving its Sentry attribute type.

In `configureSentryOptions`, enable `options.logs.isEnabled` and assign its `beforeSend` callback to `sanitizeLogEvent`.

In `SentryAndroidObservability.addLog`, retain the current breadcrumb and also send the sanitized record through `Sentry.logger()` at the corresponding debug/info/warn/error level. Build one bounded message from `record.message`, `throwableType`, and `throwableMessage`; do not add event IDs or custom correlation state.

- [x] **Step 4: Add native-feedback lifecycle markers**

Configure the existing native feedback options with:

```kotlin
onFormOpen = Runnable { AppLog.info("bug_report_form_opened") }
onSubmitSuccess = SentryFeedbackOptions.SentryFeedbackCallback {
    AppLog.info("bug_report_submitted")
}
```

Do not change `showFeedback()`: it remains `Sentry.feedback().show()`.

- [x] **Step 5: Run the focused test and Android regression build**

Run from `client/`:

```bash
ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AndroidObservabilityConfigTest*' --console=plain
ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [x] **Step 6: Commit the runtime change**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt
git commit -m "feat: send Android diagnostics to Sentry Logs"
```

### Task 2: Document and verify the operational flow

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-08-22-lean-observability-design.md`
- Modify: `docs/notes/observability-operations.md`
- Modify: `docs/runbooks/observability-triage.md`
- Modify: `docs/superpowers/plans/2026-08-24-sentry-structured-logs.md`
- Create: `docs/handoffs/2026-08-24-sentry-structured-logs.md`

- [x] **Step 1: Update self-contained project documentation**

Document that Android `AppLog` records go to both breadcrumbs and structured Sentry Logs, feedback emits open/submitted markers, and operators correlate feedback with logs by timestamp, release, and environment. Explicitly state that logs are not embedded in feedback issues and no event-ID association exists.

- [x] **Step 2: Run formatting and secret scans**

```bash
git -c core.fsmonitor=false diff --check
git grep -n -E 'sntryu_|SENTRY_AUTH_TOKEN=.+|https://[a-f0-9]+@.*ingest.*sentry.io/[0-9]+'
```

Expected: whitespace check passes and the secret scan reports no committed credential values (synthetic test DSNs may be reviewed separately).

- [x] **Step 3: Write the required handoff and commit documentation**

Record files changed, commands and results, manual verification status, assumptions, and the remaining physical-device Sentry Logs canary. Mark all completed plan checkboxes.

```bash
git add AGENTS.md docs/superpowers/specs/2026-08-22-lean-observability-design.md docs/notes/observability-operations.md docs/runbooks/observability-triage.md docs/superpowers/plans/2026-08-24-sentry-structured-logs.md docs/handoffs/2026-08-24-sentry-structured-logs.md
git commit -m "docs: document Sentry Logs feedback triage"
```

- [x] **Step 4: Physical-device canary**

Install the resulting Android build, submit a report using shake or the visible action,
and verify `bug_report_form_opened`, `bug_report_submitted`, and nearby `AppLog` records
are searchable in the `buzer-android` Logs view for the same release/environment and
timestamp.

Result: PASS on 2026-08-24. Feedback issue `BUZER-ANDROID-4`, replay
`cb60122626864f04aabbe5fe479edfe1`, and both lifecycle markers were received for release
`app.buzer@0.1.0-dev+b1b0cd202dcf` in `production-device`. Sentry also assigned the
feedback and nearby logs the same native trace ID; no custom event-ID association was
required.
