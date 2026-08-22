# Lean Observability Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the custom paired diagnostic-capsule and automated GitHub-triage system with a small, reliable Sentry integration for Android and the backend.

**Architecture:** Android sends crashes, ANRs, error replays, native screenshots/view hierarchies, sanitized `AppLog` breadcrumbs, selected handled exceptions, and native user feedback directly to Sentry. The backend sends explicit request/background failures and local structured logs to Sentry, keyed only by stable release/environment plus narrow correlation tags such as request/action/task identifiers. No custom diagnostic endpoint, attachment assembly, trace propagation, persistence table, or Sentry-to-GitHub bot remains.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Sentry Android SDK, Ktor client, Node.js/TypeScript, Fastify 5, Sentry Node SDK, SQLite, Vitest, Gradle/JUnit/Android instrumentation.

---

## File map and ownership

The implementation deliberately collapses the system into four boundaries:

- `client/shared/.../diagnostics/AppLog.kt` owns local Kermit output and an optional process-wide breadcrumb sink.
- `client/androidApp/.../observability/AndroidObservability.kt` owns the small platform-neutral Android interface and test recorder; `SentryAndroidObservability.kt` owns Sentry setup, sanitization, handled capture, breadcrumbs, and feedback.
- `backend/src/diagnostics/backendObservability.ts` owns backend Sentry setup, sanitization, request/background capture, and flush. Fastify owns stdout logging and request lifecycle hooks.
- The three runbooks plus `docs/notes/observability-operations.md` own the complete manual setup, rollout, and triage procedure.

All custom capsule collectors, context stores, ring buffers, report DTOs, report endpoint/storage, trace-header plumbing, and GitHub-sync automation are deleted.

### Task 1: Reduce the shared client diagnostics contract

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt`
- Delete: `client/shared/src/androidHostTest/kotlin/app/buzer/shared/diagnostics/RedactionCorpusParityTest.kt` only if it exclusively asserts removed capsule payloads; otherwise retain its general redaction assertions.

- [ ] **Step 1: Rewrite the API diagnostics test to assert the lean HTTP contract**

Keep tests proving every request gets `X-Request-ID`, but delete expectations for captured request/response bodies, `NetworkDiagnosticSink`, `sentry-trace`, `baggage`, `TraceHeaders`, and `/diagnostics/reports`. Add a negative assertion:

```kotlin
assertNull(request.headers["sentry-trace"])
assertNull(request.headers["baggage"])
assertEquals("request-123", request.headers["X-Request-ID"])
```

- [ ] **Step 2: Run the focused shared test and verify it fails**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientDiagnosticsTest*'`

Expected: FAIL because the current client still emits trace headers and exposes removed diagnostic hooks.

- [ ] **Step 3: Remove the report/network/trace types and simplify the client**

`DiagnosticModels.kt` retains only `DiagnosticCorrelation` and `DiagnosticLogRecord`. Remove `traceId` and `reportSessionId` from correlation. In `BuzerApiClient`, remove `NetworkDiagnosticSink`, `TraceHeaders`, body serialization/redaction/history capture, `createDiagnosticReport`, and diagnostic-only constructor arguments. Generate and send `X-Request-ID` inside `execute`:

```kotlin
val requestId = requestIdProvider()
val response = httpClient.request("$baseUrl$path") {
    method = httpMethod
    header("X-Request-ID", requestId)
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
```

Keep existing status/error decoding, timeouts, bearer authentication, and header-only Ktor logging unchanged.

- [ ] **Step 4: Run shared tests**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`

Expected: PASS.

- [ ] **Step 5: Commit the shared simplification**

```bash
git add client/shared
git commit -m "refactor: remove client diagnostic transport"
```

### Task 2: Collapse Android observability to native Sentry primitives

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidAttachmentCollector.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidDiagnosticCollector.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticContextStore.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticRingBuffer.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/observability/ManualReportCoordinator.kt`
- Delete their five corresponding JVM test files plus `AndroidContextWiringTest.kt`.

- [ ] **Step 1: Add failing privacy-boundary tests**

Extend `SentryEventSanitizerTest` with a `SentryEvent` containing a URL/query/header in `event.request` and an id/email/IP in `event.user`. Assert the sanitizer removes the request and user entirely:

```kotlin
val sanitized = SentryEventSanitizer.sanitizeEvent(event)
assertNull(sanitized.request)
assertNull(sanitized.user)
```

Update config tests to assert `isSendDefaultPii == false`, no trace sampling, error-only replay, screenshots/view hierarchy enabled, and shake/native feedback enabled.

- [ ] **Step 2: Run focused Android JVM tests and verify they fail**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*SentryEventSanitizerTest*' --tests '*AndroidObservabilityConfigTest*'`

Expected: FAIL because the current sanitizer retains request/user and initialization sends default PII.

- [ ] **Step 3: Replace the interface with the lean contract**

The interface becomes:

```kotlin
interface AndroidObservability {
    fun isEnabled(): Boolean
    fun addLog(record: DiagnosticLogRecord, throwable: Throwable?)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation = DiagnosticCorrelation()): String?
    fun showFeedback()
    fun flush(timeoutMillis: Long = 2_000L): Boolean
}
```

Update no-op and recording implementations to match. Remove attachment/event/session APIs and mutable global coordinator state.

- [ ] **Step 4: Implement safe Sentry initialization and event capture**

In `SentryAndroidObservability.kt`:

```kotlin
options.isSendDefaultPii = false
options.tracesSampleRate = null
options.sessionReplay.onErrorSampleRate = 1.0
options.sessionReplay.sessionSampleRate = 0.0
options.isAnrEnabled = true
options.isAttachScreenshot = true
options.isAttachViewHierarchy = true
```

`sanitizeEvent` must set `event.request = null` and `event.user = null` before redacting message, exception values, tags, extras, contexts, fingerprints, and breadcrumbs. `AppLog.installSink` continues forwarding sanitized records as breadcrumbs. `captureHandled` creates one exception event with marker plus only nonblank `device_id`, `task_id`, `occurrence_key`, `action_id`, `request_id`, `screen`, `blocker_type`, and `app_section` tags. `showFeedback()` calls `Sentry.feedback().show()`; configure shake and hide name/email/branding.

- [ ] **Step 5: Delete custom capsule code and obsolete tests**

Delete the five custom implementation files and tests listed above. Do not replace them with wrappers. Keep `CurrentActivityTracker` only if the installed Sentry feedback integration requires it; otherwise delete it and remove its application hook.

- [ ] **Step 6: Run focused Android JVM tests**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest`

Expected: compilation succeeds and all Android JVM tests PASS.

- [ ] **Step 7: Commit the Android observability core**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/observability client/androidApp/src/test/kotlin/app/buzer/android/observability
git commit -m "refactor: use native Android Sentry reporting"
```

### Task 3: Remove Android diagnostic context wiring and keep feedback reachable

**Files:**
- Modify all production files returned by: `rg -l 'DiagnosticContextStore|DiagnosticRingBuffer|setContext|traceHeaderProvider|networkSink' client/androidApp/src/main`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/ForegroundReadinessScreen.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessScreenTest.kt`

- [ ] **Step 1: Add a failing readiness-gate feedback test**

Render a blocking `ForegroundReadinessGate`, click the control tagged `readiness-report-bug`, and assert the callback count increments:

```kotlin
composeRule.onNodeWithTag("readiness-report-bug").assertIsDisplayed().performClick()
assertEquals(1, reportCount)
```

Rewrite `BugReportFlowInstrumentationTest` to assert Home feedback and blocking-gate feedback each call `RecordingAndroidObservability.showFeedback()` once. Remove all capsule/session/attachment assertions.

- [ ] **Step 2: Run focused instrumentation and verify the new test fails**

Run: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessScreenTest scripts/e2e-real-backend.sh`

Expected: FAIL because the readiness dialog currently has no feedback control.

- [ ] **Step 3: Add a secondary report action to the gate**

Add `onReportBug: () -> Unit` to `ForegroundReadinessGate` and render a full-width `TextButton` after the primary button:

```kotlin
TextButton(
    onClick = onReportBug,
    modifier = Modifier.fillMaxWidth().testTag("readiness-report-bug")
) { Text("Report a bug") }
```

Pass `{ ObservabilityRuntime.instance.showFeedback() }` from `MainActivity`. Keep the gate non-dismissible and preserve primary-action semantics.

- [ ] **Step 4: Remove context/ring/network plumbing from Android call sites**

Delete all imports and calls to the removed context store/ring buffer and remove diagnostic constructor arguments. For meaningful handled failures, pass `DiagnosticCorrelation(...)` directly at the capture site; otherwise retain the existing `AppLog` entry and avoid a new abstraction.

- [ ] **Step 5: Run JVM, compile, and focused instrumentation checks**

Run:

```bash
ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
ANDROID_TEST_CLASS=app.buzer.android.observability.BugReportFlowInstrumentationTest,app.buzer.android.readiness.ForegroundReadinessScreenTest scripts/e2e-real-backend.sh
```

Expected: PASS.

- [ ] **Step 6: Commit Android wiring and UI**

```bash
git add client/androidApp client/shared
git commit -m "feat: keep Sentry feedback globally reachable"
```

### Task 4: Collapse backend observability and protect the Sentry boundary

**Files:**
- Modify: `backend/src/diagnostics/backendObservability.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/test/backendObservability.test.ts`
- Modify tests for poller, escalation, WhatsApp, and server hooks only where signatures shrink.
- Delete: `backend/src/diagnostics/backendDiagnosticCollector.ts`
- Delete: `backend/src/diagnostics/ringBuffer.ts`
- Delete their dedicated test files.

- [ ] **Step 1: Add failing sanitization/config tests**

Assert Sentry initialization passes `sendDefaultPii: false`, no tracing sample rate, and a `beforeSend` function that removes `request` and `user` while recursively sanitizing message, exception, tags, extra, contexts, and breadcrumbs. Also assert `captureRequestFailure` ignores 4xx and reports 5xx with `request_id`, method, route, and status.

- [ ] **Step 2: Run the focused backend test and verify it fails**

Run: `npm test -- --run backendObservability`

Working directory: `backend`

Expected: FAIL because current initialization enables PII/tracing and retains custom diagnostic state.

- [ ] **Step 3: Replace the backend adapter with a narrow interface**

Retain only:

```ts
export interface BackendObservability {
  isEnabled(): boolean;
  addBreadcrumb(category: string, message: string, data?: Record<string, unknown>): void;
  captureRequestFailure(error: unknown, context: RequestFailureContext): string | undefined;
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): string | undefined;
  flush(timeoutMillis?: number): Promise<boolean>;
}
```

Keep a no-op implementation and a Sentry implementation. Use `Sentry.captureException` with an event scope containing only the approved tags. Do not maintain request/body/log rings, recent state, attachments, synthetic trace IDs, or a global context object.

- [ ] **Step 4: Use Fastify hooks for explicit request failure capture**

Keep `X-Request-ID` generation/echo. In `onError`, call `captureRequestFailure` only for 5xx responses. Continue logging through Fastify/Pino stdout. Keep explicit background capture in the poller, WhatsApp lifecycle, and escalation service; remove context-update calls and diagnostic collectors.

- [ ] **Step 5: Remove duplicate process-level handlers**

The Sentry SDK owns uncaught exception/unhandled rejection integration. Remove hand-written `process.on("unhandledRejection")` and `process.on("uncaughtException")` capture/exit handlers, retaining graceful `SIGTERM`/`SIGINT` close and flush.

- [ ] **Step 6: Run backend unit tests, typecheck, and build**

Run:

```bash
npm test
npm run typecheck
npm run build
```

Working directory: `backend`

Expected: all PASS.

- [ ] **Step 7: Commit backend signal simplification**

```bash
git add backend/src/diagnostics backend/src/server.ts backend/test
git commit -m "refactor: simplify backend Sentry reporting"
```

### Task 5: Remove the diagnostic endpoint and persistence safely

**Files:**
- Modify: `backend/src/server.ts`
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Modify: `backend/test/diagnosticEndpoint.test.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Delete: `backend/src/diagnostics/diagnosticReportService.ts`
- Delete: `backend/src/repositories/diagnosticReportRepository.ts`
- Delete their dedicated test files.

- [ ] **Step 1: Replace the endpoint test with a failing absence contract**

Build the server, inject `POST /diagnostics/reports`, and assert:

```ts
expect(response.statusCode).toBe(404);
```

Add a migration test that starts from a version-5 database containing `diagnostic_reports`, runs `migrate`, and asserts `sqlite_master` no longer contains the table and migration version 6 exists.

- [ ] **Step 2: Run the two focused tests and verify they fail**

Run: `npm test -- --run diagnosticEndpoint migrations`

Working directory: `backend`

Expected: endpoint test receives the current route response and migration test still finds the table.

- [ ] **Step 3: Remove route, service, and repository**

Delete route registration and the two files. Remove the report table/index from `schema.sql`.

- [ ] **Step 4: Add migration 6 as a tombstone migration**

Preserve migration 5 so existing databases have an auditable version history, then add:

```ts
function migrateToVersionSix(db: Database.Database): void {
  db.exec("drop table if exists diagnostic_reports");
}
```

Apply/version it after migration 5. Fresh databases briefly execute v5 then v6 in the same transaction and finish without the table; deployed v5 databases safely drop it.

- [ ] **Step 5: Run backend tests, typecheck, and build**

Run: `npm test && npm run typecheck && npm run build` as three separate commands in `backend`.

Expected: PASS.

- [ ] **Step 6: Commit endpoint removal**

```bash
git add backend
git commit -m "refactor: remove diagnostic report endpoint"
```

### Task 6: Delete Sentry-to-GitHub automation and fix release defaults

**Files:**
- Delete: `tools/sentry-github-sync/`
- Delete: `scripts/sentry-triage.mjs`
- Delete: `scripts/sentry-triage.test.mjs`
- Delete: `.github/workflows/sentry-triage.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `backend/package.json`
- Modify: Android build/release configuration found by `rg -n 'SENTRY_ORG|SENTRY_PROJECT|SENTRY_RELEASE|svehla|buzer-backend@' client backend scripts .github`

- [ ] **Step 1: Remove the automation and its CI job**

Delete the tool, wrapper, scheduled workflow, and `sentry-github-sync` job in `ci.yml`. Remove no-longer-used scripts/dependencies without changing unrelated CI jobs.

- [ ] **Step 2: Use canonical organization/project/release defaults**

Set source upload defaults to organization `jans-gi`, Android project `buzer-android`, and backend project `buzer-backend`. Both runtime events and uploaded artifacts use `app.buzer@<version>+<git-sha>`; local fallback may use `app.buzer@<version>+local`, but must never use `buzer-backend@...` or org `svehla`.

- [ ] **Step 3: Verify manifests, lockfiles, and workflow syntax**

Run:

```bash
npm ci
npm test
npm run typecheck
npm run build
git diff --check
```

Working directory for npm commands: `backend`.

Expected: PASS and no references to the removed tool/workflow remain.

- [ ] **Step 4: Commit automation/config cleanup**

```bash
git add .github backend client scripts tools
git commit -m "chore: remove custom Sentry triage automation"
```

### Task 7: Rewrite operations documentation as a self-contained lean runbook

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/runbooks/sentry-setup.md`
- Modify: `docs/runbooks/observability-triage.md`
- Modify: `docs/runbooks/production-rollout.md`
- Modify: `docs/notes/observability-operations.md`
- Modify: `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`
- Modify observability handoffs dated `2026-08-21` with a short superseded banner where they otherwise look current.

- [ ] **Step 1: Rewrite the docs around the surviving system**

Document, without relying on chat history:

- Sentry org `jans-gi`; projects `buzer-android` (`4511950606172240`) and `buzer-backend` (`4511950617641040`).
- Exact DSN/auth-token environment variables, where they are configured, and that secrets are never committed.
- Canonical release naming and source/mapping upload commands.
- Android signals, feedback entry points, offline behavior, and privacy boundary.
- Backend 5xx/background capture and stdout logging.
- Manual triage flow: reproduce, open Sentry issue/event, inspect replay/breadcrumbs/tags/stack/release, create a GitHub issue only when useful, fix/test/deploy/resolve.
- Alert creation and live-canary checklist.
- Explicit removed-system list so future agents do not rebuild capsules, the diagnostic endpoint, trace headers, or the sync bot without a new decision.

- [ ] **Step 2: Mark historical implementation material superseded**

Add a prominent banner linking the 2026-08-22 design and this plan to the old plan and observability handoffs. Do not rewrite historical command/results content.

- [ ] **Step 3: Self-review documentation completeness**

Run:

```bash
rg -n 'svehla|diagnostics/reports|android-diagnostics-v1|backend-diagnostics-v1|sentry-github-sync|sentry-trace|baggage' AGENTS.md docs/runbooks docs/notes/observability-operations.md
rg -n 'jans-gi|4511950606172240|4511950617641040|app\.buzer@' docs/runbooks docs/notes/observability-operations.md
```

Expected: the first command finds removed concepts only in explicit “removed/do not restore” text; the second finds all fixed identifiers and canonical release guidance.

- [ ] **Step 4: Commit documentation**

```bash
git add AGENTS.md docs
git commit -m "docs: document lean observability operations"
```

### Task 8: Full verification, live canary, review, and handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-08-22-lean-observability.md`
- Create: `docs/handoffs/2026-08-22-lean-observability.md`

- [ ] **Step 1: Run all local automated gates**

Run separately:

```bash
ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
npm test
npm run typecheck
npm run build
scripts/e2e-real-backend.sh
git diff --check
```

The npm commands run in `backend`; all others run at repository root. Expected: every command PASS, including the full unfiltered instrumentation suite. If an unrelated pre-existing test fails, rerun it once in isolation, record both results, and do not mark the gate complete until the full suite is green or the user explicitly accepts the documented failure.

- [ ] **Step 2: Run static removal checks**

Run:

```bash
rg -n 'DiagnosticContextStore|DiagnosticRingBuffer|AndroidAttachmentCollector|AndroidDiagnosticCollector|ManualReportCoordinator|DiagnosticReportRequest|DiagnosticReportResponse|DiagnosticNetworkExchange|NetworkDiagnosticSink|TraceHeaders' client backend
rg -n 'diagnostics/reports|sentry-github-sync|sentry-triage|sentry-trace|baggage' client backend scripts tools .github
rg -n 'sendDefaultPii|isSendDefaultPii|tracesSampleRate' client backend
```

Expected: the first two return no production references; the third shows default PII disabled and no positive trace sample rate.

- [ ] **Step 3: Perform live Sentry canaries**

With real DSNs configured, perform one controlled Android handled exception/feedback submission and one controlled backend 5xx/background exception. Verify in Sentry:

- correct org/project/environment/release;
- Android stack, error replay, screenshot/view hierarchy where SDK policy permits, and recent breadcrumbs;
- backend exception plus request ID/method/route/status or background marker;
- secrets and request/user payloads absent;
- event delivery survives an offline-then-online Android cycle.

If credentials or a deployed environment are unavailable, mark these checks `SKIPPED`, name the exact missing prerequisite, and leave rollout explicitly incomplete.

- [ ] **Step 4: Review the complete diff for scope and accidental deletion**

Run: `git diff 025b104...HEAD --stat`, `git diff 025b104...HEAD --check`, and inspect every changed file. Confirm unrelated product behavior is unchanged and the net code change is materially negative.

- [ ] **Step 5: Update this plan and create the handoff**

Check completed boxes. The handoff must list the plan/task status, files changed, every exact command and PASS/FAIL/SKIPPED evidence, manual verification, assumptions, known failures, and next deployment step.

- [ ] **Step 6: Commit verification documentation**

```bash
git add docs/superpowers/plans/2026-08-22-lean-observability.md docs/handoffs/2026-08-22-lean-observability.md
git commit -m "docs: hand off lean observability implementation"
```

