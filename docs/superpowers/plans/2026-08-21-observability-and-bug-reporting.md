# Observability And Bug Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add durable, correlated Android and backend diagnostics through hosted Sentry, plus native shake/visible bug reporting and a deduplicated Sentry-to-GitHub triage loop, without making enforcement or user actions depend on telemetry.

**Architecture:** Keep Kermit and local logs as the application-facing facade, fan sanitized records into bounded in-process buffers and Sentry adapters, and correlate Android feedback with a read-only backend diagnostic endpoint using `report_session_id`. Sentry owns crash/ANR/replay/feedback transport; Buzer owns deterministic capsules, mandatory secret filtering, and operational triage. Every integration has a no-op or in-memory implementation so tests never contact Sentry Cloud.

**Tech Stack:** Kotlin 2.4.0, Kotlin Multiplatform, Compose Multiplatform, Kermit 2.1.0, Ktor 3.5.0, Sentry Android 8.53.0, Sentry Android Gradle Plugin 6.19.0, Fastify 5.8.5, Node.js 22 ESM, TypeScript 6.0.3, Sentry Node 10.70.0, Sentry CLI 3.6.2, SQLite/better-sqlite3, Vitest, Android instrumentation, GitHub Actions, Sentry REST API, GitHub REST API.

---

## Relationship To Product Truth

This plan implements
`docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`, which
extends the approved product specification at
`docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`. Read both
before editing code. A newer decision document wins over this plan; otherwise preserve
the contracts below.

This plan was written against `master` commit `2d75e9d`. Before implementation, rebase
the plan branch on the latest integrated `master`, then re-audit the named files. In
particular, do not recreate E2E helpers already integrated from
`docs/superpowers/plans/2026-08-09-e2e-test-foundation.md`.

## Non-Negotiable Safety Rules

- Observability is best-effort. No alarm, blocker, boot recovery, readiness check,
  Todoist mutation, WhatsApp escalation, or UI render may wait for Sentry or a capsule.
- Automated tests use fake sinks/transports and a blank DSN. They never contact Sentry.
- Task-visible/PII data is permitted. Authorization headers, bearer values, Android API
  tokens, Todoist tokens, Sentry auth tokens, WhatsApp credentials/QR/session data,
  cookies, environment files, `local.properties`, GitHub tokens, and private keys are
  prohibited everywhere, including local buffers and test failure output.
- The backend diagnostic endpoint is authenticated, rate-limited, idempotent, bounded,
  and read-only. It must not call Todoist, WhatsApp, action services, or sync methods.
- Do not enable unrestricted Fastify request logging. Keep `logger: false`.
- Do not add Crashlytics, Shake, Bugsee, Luciq/Instabug, or a second feedback form.
- Live projects, secrets, alerts, source-map uploads, and physical-device canaries are
  operator prerequisites, not facts inferred from code.

## Cross-Runtime Contract

Use these exact snake-case keys as Sentry tags/context and JSON fields where applicable:

```text
device_id
task_id
todoist_task_id
occurrence_key
action_id
request_id
trace_id
schedule_version
snapshot_generation
screen
blocker_type
app_section
report_session_id
android_diagnostic_event_id
backend_diagnostic_event_id
feedback_submitted
capsule_schema_version
```

Canonical release identity:

```text
app.buzer@${VERSION_NAME}+${GIT_SHA_12}
```

Manual endpoint contract:

```http
POST /diagnostics/reports
Authorization: Bearer [existing Android API token]
Content-Type: application/json

{
  "schemaVersion": 1,
  "reportSessionId": "UUID",
  "androidDiagnosticEventId": "32 lowercase hex characters",
  "deviceId": "stable Android device UUID",
  "capturedAt": "ISO-8601 instant",
  "taskId": "optional opaque value",
  "occurrenceKey": "optional opaque value",
  "actionId": "optional opaque value",
  "requestIds": ["bounded recent request IDs"],
  "traceId": "optional Sentry trace id"
}
```

Accepted response:

```json
{
  "schemaVersion": 1,
  "reportSessionId": "same UUID",
  "backendDiagnosticEventId": "32 lowercase hex characters",
  "outcome": "captured"
}
```

The endpoint has a five-session/ten-minute device/IP limit, a two-second collection
budget, a three-second server deadline, and a five-second Android timeout. Duplicate
`reportSessionId` requests return the original response without emitting another event.

## Planned File Map

### Shared Kotlin

- Modify `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`:
  retain the facade and add an injectable sanitized sink.
- Create `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`:
  log/network/correlation/report request-response serializable contracts.
- Create `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`:
  one Android/shared mandatory secret filter.
- Modify `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`:
  sanitized request history, request IDs, trace propagation, and diagnostics endpoint.
- Create matching tests under
  `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/` and extend
  `BuzerApiClientContractTest.kt`.

### Android

- Modify `client/gradle/libs.versions.toml`, `client/build.gradle.kts`, and
  `client/androidApp/build.gradle.kts`: Sentry SDK/plugin and external configuration.
- Create `client/androidApp/src/main/kotlin/app/buzer/android/BuzerApplication.kt`:
  earliest runtime initialization.
- Create `client/androidApp/src/main/kotlin/app/buzer/android/observability/` files:
  `AndroidObservability.kt`, `SentryAndroidObservability.kt`,
  `DiagnosticContextStore.kt`, `DiagnosticRingBuffer.kt`, `AndroidDiagnosticCollector.kt`,
  `AndroidAttachmentCollector.kt`, `ManualReportCoordinator.kt`, and
  `CurrentActivityTracker.kt`.
- Modify `client/androidApp/src/main/AndroidManifest.xml`, `MainActivity.kt`,
  `ui/App.kt`, `ui/screens/HomeScreen.kt`, `enforcement/BlockerErrorReporter.kt`, and
  `enforcement/EnforcementDependencies.kt`.
- Add JVM tests under `client/androidApp/src/test/kotlin/app/buzer/android/observability/`
  and extend `BlockerErrorReporterTest.kt`.
- Add `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt`
  and extend `ui/screens/HomeScreenTest.kt`.

### Backend

- Modify `backend/package.json`, `backend/package-lock.json`, `backend/tsconfig.json`,
  `backend/tsconfig.build.json`, `Dockerfile`, and `backend/.env.example`.
- Create `backend/src/instrument.ts` and `backend/src/observability/` files:
  `contracts.ts`, `redactor.ts`, `ringBuffer.ts`, `backendObservability.ts`,
  `diagnosticReportRepository.ts`, and `diagnosticReportService.ts`.
- Modify `backend/src/db/schema.sql`, `backend/src/db/migrations.ts`,
  `backend/src/server.ts`, `backend/src/http/routes.ts`, `services/todoistPoller.ts`,
  `services/escalationService.ts`, and `accountability/whatsappWebSender.ts`.
- Add tests under `backend/test/observability/` and extend
  `backend/test/db/migrations.test.ts`, `backend/test/http/routes.test.ts`,
  `backend/test/http/rateLimit.test.ts`, and relevant service/sender tests.

### Operations And Documentation

- Create `scripts/sentry-triage.mjs` and `scripts/sentry-triage.test.mjs`.
- Create `.github/workflows/sentry-triage.yml`.
- Modify `.github/workflows/ci.yml`, `scripts/deploy-vps.sh`,
  `docs/notes/local-development.md`, `docs/notes/vps-deployment.md`, and `AGENTS.md`.
- Create `docs/notes/observability-operations.md` and
  `docs/decisions/2026-08-21-hosted-sentry-observability.md`.
- Every task/session updates this plan and writes a handoff under `docs/handoffs/`.

## Execution And Ownership Protocol

Each implementation task gets its own worktree and branch using
`docs/notes/agent-workspaces.md`. The task owner claims only the files listed for that
task in the workspace manifest. Sequential shared-contract tasks merge before Android
and backend work starts. Android and backend runtime tasks may then proceed independently;
operations begins only after event/tag contracts are merged.

Before every task:

```bash
git worktree list
git status --short
npx ctx7@latest library "Sentry Android" "Android SDK API used by the assigned task"
npx ctx7@latest docs "/getsentry/sentry-java" "Android SDK API used by the assigned task"
npx ctx7@latest library "Sentry Node SDK" "Node SDK API used by the assigned task"
npx ctx7@latest docs "/websites/sentry_io_platforms_javascript_guides_node" "Node SDK API used by the assigned task"
```

If Context7 documentation differs from an API shown here, stop, record the difference in
`docs/notes/`, update this plan in a reviewed commit, and only then implement. Do not
silently improvise across agents.

## Task 1: Establish Shared Diagnostic Primitives

**Owner:** shared Kotlin diagnostic contracts and unit tests only.

**Files:**

- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactorTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/AppLogTest.kt`
- Create: `client/shared/src/androidHostTest/kotlin/app/buzer/shared/diagnostics/RedactionCorpusParityTest.kt`
- Create: `test-fixtures/observability/redaction-corpus.json`

- [x] **Step 1: Write failing redaction tests.**

Create the canonical cross-runtime fixture at
`test-fixtures/observability/redaction-corpus.json`. The Android-host parity test can use
JVM file I/O to read it; keep the common test platform-neutral. Cover case-insensitive keys and embedded values for `authorization`, `cookie`,
`set-cookie`, `token`, `api_token`, `access_token`, `refresh_token`, `password`,
`secret`, `private_key`, bearer/basic authorization, Todoist tokens, Sentry auth tokens,
WhatsApp QR/session strings, URLs containing credentials, nested maps/lists, exceptions,
and already-redacted values. Seed literal sentinels such as `SECRET_TODOIST_123` and assert
the final serialized JSON does not contain them.

```kotlin
@Test
fun redactsNestedCredentialKeysAndEmbeddedBearerValues() {
    val redactor = DiagnosticRedactor()
    val value = redactor.redactMap(
        mapOf(
            "authorization" to "Bearer SECRET_ANDROID_123",
            "nested" to mapOf("TodoistToken" to "SECRET_TODOIST_123"),
            "message" to "request failed Authorization: Bearer SECRET_ANDROID_123"
        )
    ).toString()

    assertFalse(value.contains("SECRET_ANDROID_123"))
    assertFalse(value.contains("SECRET_TODOIST_123"))
    assertTrue(value.contains("[REDACTED]"))
}
```

- [x] **Step 2: Run the new test and confirm RED.**

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests '*DiagnosticRedactorTest*' --console=plain
```

Expected: FAIL because the redactor does not exist.

- [x] **Step 3: Implement deterministic models and redaction.**

Use serializable data classes with explicit versions and capture status:

```kotlin
@Serializable
data class DiagnosticCorrelation(
    val deviceId: String? = null,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestId: String? = null,
    val traceId: String? = null,
    val screen: String? = null,
    val blockerType: String? = null,
    val appSection: String? = null,
    val reportSessionId: String? = null
)

@Serializable
data class DiagnosticLogRecord(
    val capturedAt: String,
    val severity: String,
    val tag: String,
    val message: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null
)

@Serializable
data class DiagnosticNetworkExchange(
    val startedAt: String,
    val durationMillis: Long,
    val method: String,
    val path: String,
    val status: Int? = null,
    val outcome: String,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestId: String,
    val traceId: String? = null,
    val actionId: String? = null,
    val requestTruncated: Boolean = false,
    val responseTruncated: Boolean = false
)
```

`DiagnosticRedactor` must redact before data enters any buffer. It uses a case-insensitive
credential-key set plus patterns for authorization schemes, credential URLs, QR/session
markers, and PEM blocks. Preserve field shape and replace the value with `[REDACTED]`.

- [x] **Step 4: Write failing AppLog sink tests.**

Assert severity/throwable mapping and sink failure isolation.

```kotlin
@Test
fun sinkFailureNeverBreaksApplicationLogging() {
    AppLog.installSink { throw IllegalStateException("sink down") }
    AppLog.error("enforcement still runs", IllegalArgumentException("failure"))
    AppLog.resetSinkForTests()
}
```

- [x] **Step 5: Implement the AppLog fan-out.**

Keep Kermit as the local writer. Add a small shared record sink rather than replacing the
global logger configuration:

```kotlin
fun interface DiagnosticLogSink {
    fun record(record: DiagnosticLogRecord, throwable: Throwable?)
}

object AppLog {
    private val logger = Logger.withTag("Buzer")
    @Volatile
    private var sink: DiagnosticLogSink? = null

    fun installSink(value: DiagnosticLogSink?) { sink = value }
    internal fun resetSinkForTests() { sink = null }

    fun debug(message: String) = emit("debug", message, null) { logger.d { message } }
    fun info(message: String) = emit("info", message, null) { logger.i { message } }
    fun warn(message: String, throwable: Throwable? = null) =
        emit("warning", message, throwable) { logger.w(throwable) { message } }
    fun error(message: String, throwable: Throwable? = null) =
        emit("error", message, throwable) { logger.e(throwable) { message } }

    private inline fun emit(
        severity: String,
        message: String,
        throwable: Throwable?,
        local: () -> Unit
    ) {
        local()
        runCatching {
            sink?.record(
                DiagnosticLogRecord(
                    capturedAt = Clock.System.now().toString(),
                    severity = severity,
                    tag = "Buzer",
                    message = message,
                    throwableType = throwable?.let { it::class.simpleName },
                    throwableMessage = throwable?.message
                ),
                throwable
            )
        }
    }
}
```

The production sink owns redaction; tests must prove a failing sink is swallowed. Do not
catch or suppress the application's original exception outside this fan-out.

- [x] **Step 6: Run shared diagnostic tests and the complete shared suite.**

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests '*diagnostics*' --console=plain
./gradlew :shared:testAndroidHostTest --console=plain
```

Expected: PASS.

- [x] **Step 7: Commit and hand off.**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics client/shared/src/androidHostTest/kotlin/app/buzer/shared/diagnostics test-fixtures/observability/redaction-corpus.json docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-01-shared-primitives.md
git commit -m "feat: add shared diagnostic primitives"
```

## Task 2: Instrument The Shared Ktor Client And Add The Report Contract

**Depends on:** Task 1 merged.

**Owner:** shared API client and its common tests only.

**Files:**

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt`

- [ ] **Step 1: Add serializable endpoint DTOs and failing contract tests.**

```kotlin
@Serializable
data class DiagnosticReportRequest(
    val schemaVersion: Int = 1,
    val reportSessionId: String,
    val androidDiagnosticEventId: String,
    val deviceId: String,
    val capturedAt: String,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestIds: List<String> = emptyList(),
    val traceId: String? = null
)

@Serializable
data class DiagnosticReportResponse(
    val schemaVersion: Int,
    val reportSessionId: String,
    val backendDiagnosticEventId: String,
    val outcome: String
)
```

Test exact `POST /diagnostics/reports`, bearer auth, JSON body, five-second timeout,
response decoding, and unchanged ID round-trip.

- [ ] **Step 2: Add failing network-recording tests.**

Inject `requestIdProvider`, `traceHeaderProvider`, and `networkSink` into the client.
Assert:

- only path, never scheme/host credentials, is recorded;
- `Authorization`, `baggage`, and `sentry-trace` values are never stored;
- request/response bodies stop at 64 KiB after redaction;
- status, duration, timeout/cancellation, content type, request ID, trace ID, and action ID
  are retained;
- outgoing `X-Request-ID`, `sentry-trace`, and `baggage` do not replace bearer auth;
- a broken sink does not change the HTTP result.

- [ ] **Step 3: Run focused tests and confirm RED.**

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientDiagnosticsTest*' --tests '*BuzerApiClientContractTest*' --console=plain
```

Expected: FAIL for missing API and instrumentation.

- [ ] **Step 4: Implement explicit, testable instrumentation.**

Do not depend on an Android-only Sentry Ktor integration in common code. Define:

```kotlin
fun interface NetworkDiagnosticSink {
    fun record(exchange: DiagnosticNetworkExchange)
}

data class TraceHeaders(
    val traceId: String?,
    val sentryTrace: String?,
    val baggage: String?
)
```

Add constructor defaults that preserve all existing callers. Install Ktor request/response
hooks or wrap each request through one internal `execute` helper. Generate a UUID request
ID for every call, copy only current trace headers provided by Android, infer `actionId`
from mutation DTOs, and record through a `runCatching` sink. Keep existing Ktor header
logging at `HEADERS` with authorization sanitization; the diagnostic recorder is the
only component allowed to retain bounded bodies.

Add:

```kotlin
suspend fun createDiagnosticReport(
    request: DiagnosticReportRequest
): DiagnosticReportResponse =
    httpClient.post("$baseUrl/diagnostics/reports") {
        timeout { requestTimeoutMillis = 5_000 }
        contentType(ContentType.Application.Json)
        setBody(request)
    }.decodeBody()
```

- [ ] **Step 5: Run focused and full shared tests.**

```bash
cd client
./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClient*' --console=plain
./gradlew :shared:testAndroidHostTest --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit and hand off.**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared client/shared/src/commonTest/kotlin/app/buzer/shared docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-02-shared-api.md
git commit -m "feat: instrument shared API diagnostics"
```

## Task 3: Bootstrap Android Sentry Behind A Testable Boundary

**Depends on:** Tasks 1-2 merged.

**Owner:** Android dependency/build configuration and observability bootstrap.

**Files:**

- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/build.gradle.kts`
- Modify: `client/androidApp/build.gradle.kts`
- Modify: `client/androidApp/src/main/AndroidManifest.xml`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/BuzerApplication.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/CurrentActivityTracker.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt`

- [ ] **Step 1: Re-resolve Sentry Android docs and pin versions.**

Use Context7 and official release pages. If 8.53.0/6.19.0 is no longer suitable, update
the plan and record why before changing the catalog.

Catalog entries:

```toml
sentryAndroid = "8.53.0"
sentryAndroidGradle = "6.19.0"
sentry-android = { module = "io.sentry:sentry-android", version.ref = "sentryAndroid" }
sentry-android-replay = { module = "io.sentry:sentry-android-replay", version.ref = "sentryAndroid" }
sentry-android-gradle = { id = "io.sentry.android.gradle", version.ref = "sentryAndroidGradle" }
```

Apply the Gradle plugin to `androidApp`, disable auto-installation, dependency-report
upload, SDK telemetry, Logcat capture, and broad bytecode instrumentation. Enable mapping
and source-context upload only when `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, and project values
exist. Add the two explicit SDK dependencies.

- [ ] **Step 2: Add build-time configuration without embedding an auth token.**

Extend the existing `backendConfigValue` helper usage:

```kotlin
buildConfigField("String", "SENTRY_ANDROID_DSN", "\"${backendConfigValue("SENTRY_ANDROID_DSN")}\"")
buildConfigField("String", "SENTRY_ENVIRONMENT", "\"${backendConfigValue("SENTRY_ENVIRONMENT").ifBlank { "development-device" }}\"")
buildConfigField("String", "BUZER_GIT_SHA", "\"${providers.environmentVariable("GITHUB_SHA").orNull?.take(12) ?: "local"}\"")
buildConfigField("String", "SENTRY_RELEASE", "\"app.buzer@${defaultConfig.versionName}+${providers.environmentVariable("GITHUB_SHA").orNull?.take(12) ?: "local"}\"")
```

Never create a `BuildConfig.SENTRY_AUTH_TOKEN`.

- [ ] **Step 3: Write failing configuration and event-sanitizer tests.**

Test blank DSN -> disabled no-op, nonblank DSN -> enabled, sample policies, release and
environment mapping, `beforeSend`, `beforeBreadcrumb`, `beforeSendLog`, and attachment
name/content filtering with seeded secrets.

- [ ] **Step 4: Define the production/test boundary.**

```kotlin
interface AndroidObservability {
    fun isEnabled(): Boolean
    fun setContext(context: DiagnosticCorrelation)
    fun addLog(record: DiagnosticLogRecord, throwable: Throwable?)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation): String?
    fun newEventId(): String
    fun captureDiagnosticEvent(
        eventId: String,
        reportSessionId: String,
        submitted: Boolean,
        context: DiagnosticCorrelation,
        attachments: List<DiagnosticAttachment>
    ): String
    fun showFeedback(associatedEventId: String? = null)
    fun flush(timeoutMillis: Long): Boolean
}
```

Provide `NoOpAndroidObservability` and a recording fake in test source. Production maps
returned `SentryId` values to 32 lowercase hex strings.

- [ ] **Step 5: Initialize Sentry in `BuzerApplication`.**

Register the application in the manifest with `android:name=".BuzerApplication"` and
disable manifest auto-init so the explicit config is authoritative:

```xml
<meta-data android:name="io.sentry.auto-init" android:value="false" />
```

Initialize before any activity/receiver/service work:

```kotlin
class BuzerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObservabilityRuntime.initialize(this)
    }
}
```

`SentryAndroid.init` uses the DSN, release, environment, 100% error capture,
`tracesSampleRate = 1.0`, error replay at 100%, normal-session replay disabled, ANR
integration, `sendDefaultPii = true`,
`attachScreenshot = true`, `attachViewHierarchy = true`, and the global redaction
callbacks. Configure feedback with name/email hidden, Sentry user disabled, shake enabled,
and callbacks delegated to a coordinator installed in Task 5. Blank DSN installs the
no-op adapter and still installs the local ring-buffer sink.

- [ ] **Step 6: Track the current foreground activity weakly.**

Implement `Application.ActivityLifecycleCallbacks` with `WeakReference<Activity>` and
return only a started/resumed, non-finishing activity. This supports bounded screenshot
capture without retaining activities.

- [ ] **Step 7: Run focused tests, Android unit tests, and assemble.**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests '*observability*' --console=plain
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS. Inspect the debug APK/build config and confirm no Sentry auth token is
present.

- [ ] **Step 8: Commit and hand off.**

```bash
git add client/gradle/libs.versions.toml client/build.gradle.kts client/androidApp/build.gradle.kts client/androidApp/src/main/AndroidManifest.xml client/androidApp/src/main/kotlin/app/buzer/android/BuzerApplication.kt client/androidApp/src/main/kotlin/app/buzer/android/observability client/androidApp/src/test/kotlin/app/buzer/android/observability docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-03-android-bootstrap.md
git commit -m "feat: bootstrap Android observability"
```

## Task 4: Build Android Context, Capsule, And Attachment Collectors

**Depends on:** Task 3 merged.

**Owner:** Android diagnostic context and collectors; no UI wiring.

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticContextStore.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticRingBuffer.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidDiagnosticCollector.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidAttachmentCollector.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/DiagnosticContextStoreTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/DiagnosticRingBufferTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidDiagnosticCollectorTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidAttachmentCollectorTest.kt`

- [ ] **Step 1: Write failing immutable-context tests.**

Define sections for UI, sync/snapshot, scheduling/alarms, readiness/permissions, action
journal knowledge, process/device, and correlation. Test atomic section updates, a frozen
snapshot that cannot change, reset semantics, and that updating context never throws to a
producer.

- [ ] **Step 2: Write failing ring-buffer and capsule isolation/bounds tests.**

Assert newest-first snapshot order, 1,000-record and 15-minute eviction, UTF-8 byte
truncation without splitting a code point, and JVM thread safety. Every collector returns `complete`, `partial`, `unavailable`, or `failed` plus a stable
reason code such as `timeout`. One failed/slow collector must not suppress others. Enforce:

```text
logs: 1,000 records / 15 minutes
network: 100 exchanges / 15 minutes
request or response body: 64 KiB
thread dump: 512 KiB
screenshot: 2 MiB
all Android attachments excluding replay: 8 MiB
per-collector timeout: 1 second
whole capsule budget: 2 seconds
```

Deterministic truncation priority is report/release/correlation identity; current task,
snapshot, action, alarm, and readiness state; error/warning logs; failed/slow network
exchanges; screenshot; then info/debug history. Record every drop/truncation.

- [ ] **Step 3: Implement versioned capsule serialization.**

Use a single deterministic `Json` instance and schema version `1`. Implement every
approved section: report/release identity; device/process/app version; activity/editor/
blocker UI; full cached snapshot and reduced state; sync and snapshot generation;
schedules, alarm registry, and Morning Review state; readiness permissions/app-ops;
pending/retried actions; battery/doze/connectivity/memory/storage/previous-exit state;
threads; correlation; and redacted app-owned local-store values. Always emit
`android-diagnostics-v1.json`, `android-app-log.txt`, and
`android-network-history.json`. Attempt `android-threads.txt` and
`android-screenshot.webp` (or `.png`); unavailable artifacts remain represented in JSON
status metadata.
Collect only app-process threads and app-owned state, never unrestricted Logcat or the
SQLite database file.

- [ ] **Step 4: Implement screenshot and thread collectors.**

Use the weak current activity. Capture the activity window with `PixelCopy` on API 26+,
compress to WebP/JPEG under 2 MiB, and return `unavailable` if there is no valid surface.
Build the thread dump from `Thread.getAllStackTraces()`, sorted by thread name and capped
at 512 KiB. Run both off the main thread with timeouts; never block form submission.

- [ ] **Step 5: Run focused and complete Android JVM tests.**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests '*Diagnostic*' --console=plain
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit and hand off.**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/observability client/androidApp/src/test/kotlin/app/buzer/android/observability docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-04-android-capsule.md
git commit -m "feat: collect Android diagnostic capsules"
```

## Task 5: Implement The Manual Report Session And Native Feedback UI

**Depends on:** Tasks 2-4 merged.

**Owner:** manual session coordinator and visible invocation/UI tests.

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/observability/ManualReportCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/ManualReportCoordinatorTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/HomeScreenTest.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt`

- [ ] **Step 1: Write failing state-machine tests.**

Use states `idle -> form_open -> submitted|failed|closed -> idle`. Assert exactly one
UUID/session and one reserved Sentry event ID, frozen state at form open, no stacked
scope, cleanup on every terminal callback, `feedback_submitted=false` for abandoned
forms, attachment/event capture after successful submission, stale persisted-session
expiry after process restart, and backend failure isolation. Repeated callbacks must be
idempotent.

```kotlin
@Test
fun successfulSubmissionEmitsPairedEventsAndClearsScope() = runTest {
    coordinator.onFormOpen()
    coordinator.onSubmitSuccess(recordedFeedback)
    advanceUntilIdle()

    assertEquals(1, sentry.diagnosticEvents.size)
    assertEquals(1, backend.requests.size)
    assertEquals(
        sentry.diagnosticEvents.single().reportSessionId,
        backend.requests.single().reportSessionId
    )
    assertNull(scope.currentReportSessionId)
}
```

- [ ] **Step 2: Implement the coordinator.**

The coordinator receives interfaces for UUID/time, context snapshot, attachment
collection, Sentry event IDs/capture, temporary scope, and backend report. On form open
it freezes state immediately, obtains a public `SentryId`, puts `report_session_id` and
`android_diagnostic_event_id` into temporary scope before submission, and starts
attachment collection asynchronously. On success it captures
`buzer.android.manual_report` with the reserved ID, attaches frozen artifacts, tags it
`feedback_submitted=true`, and requests the backend event without delaying the Sentry
feedback callback. On error/close it captures the reserved event as a non-alerting orphan
tagged `feedback_submitted=false` if it has not already been sent, records the reason,
and clears scope.

At Task 3, verify the selected SDK still exposes public construction of `SentryId` and an
event capture path that preserves a supplied ID. If that API has changed, stop and update
this plan to use the design-approved immediate-capture fallback. Never silently omit the
Android event ID from feedback scope. Use `report_session_id` as the primary join key.

- [ ] **Step 3: Wire current native feedback callbacks.**

Use the verified API:

```kotlin
options.feedbackOptions.apply {
    isUseShakeGesture = true
    showName = false
    showEmail = false
    useSentryUser = false
    formTitle = "Report a bug"
    messageLabel = "What went wrong?"
    messagePlaceholder = "What did you expect, and what happened?"
    submitButtonLabel = "Send report"
    successMessageText = "Report sent. Thank you."
    showBranding = false
    onFormOpen = Runnable { coordinator.onFormOpen() }
    onFormClose = Runnable { coordinator.onFormClose() }
    onSubmitSuccess = SentryFeedbackOptions.SentryFeedbackCallback {
        coordinator.onSubmitSuccess(it)
    }
    onSubmitError = SentryFeedbackOptions.SentryFeedbackCallback {
        coordinator.onSubmitError(it)
    }
}
```

The visible action invokes `Sentry.feedback().show()` through `AndroidObservability`, so
both paths use exactly the same form and callbacks.

- [ ] **Step 4: Add the Home fallback action.**

Add `onReportBug: () -> Unit = {}` to `HomeScreen` and `BuzerApp`, pass it from
`MainActivity`, and populate `IconFabStack.secondaryActions`:

```kotlin
secondaryActions = listOf(
    IconFabAction(
        contentDescription = "Report a bug",
        onClick = onReportBug,
        icon = { Text("!") }
    )
)
```

The action remains reachable in loading/error/ready Home states. Preserve Add as the
primary action.

- [ ] **Step 5: Add UI and instrumentation tests.**

Extend `HomeScreenTest` to expand the stack, find `Report a bug`, click it, and assert the
callback exactly once. `BugReportFlowInstrumentationTest` injects a fake
`AndroidObservability`, launches `MainActivity`, clicks the fallback, and verifies the
launcher/session callback without network. Do not attempt to simulate accelerometer
shake in the emulator.

- [ ] **Step 6: Run focused Android tests and the required scoped E2E authority.**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests '*ManualReportCoordinatorTest*' --console=plain
cd ..
ANDROID_TEST_CLASS=app.buzer.android.observability.BugReportFlowInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: PASS.

- [ ] **Step 7: Commit and hand off.**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android client/androidApp/src/test/kotlin/app/buzer/android/observability client/androidApp/src/androidTest/kotlin/app/buzer/android docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-05-android-feedback.md
git commit -m "feat: add native Android bug reporting"
```

## Task 6: Wire Android Runtime Context And Critical Non-Fatals

**Depends on:** Task 5 merged.

**Owner:** Android producers and existing tests named below.

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- Modify selected producers under `client/androidApp/src/main/kotlin/app/buzer/android/blockers/`, `enforcement/`, `morningreview/`, and `readiness/`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidContextWiringTest.kt`

- [ ] **Step 1: Add failing critical-capture tests.**

`BlockerErrorReporter` must continue appending its local file and showing its notification,
then asynchronously report the action/task/error to observability. A broken observer must
not suppress the file or notification. Assert selected fatality classification:

- capture: blocker/overlay action failure, coordinator creation failure, boot reconcile
  failure, alarm scheduling failure, snapshot integrity/data-consistency failure;
- breadcrumb/log only: validation failures, normal retryable HTTP failures, expected
  offline/timeouts below repetition thresholds.

Also assert throwable identity deduplication so nested error boundaries cannot capture
the same `Throwable` twice.

- [ ] **Step 2: Inject observability through dependency roots.**

Pass `ObservabilityRuntime.instance` into `EnforcementDependencies`, blocker activities,
overlay service, receivers, morning review, and readiness reporters. Do not add direct
Sentry calls to feature classes. Update `DiagnosticContextStore` at state transitions:
screen/section, current task and occurrence, snapshot generation/counts, last sync result,
scheduled alarms, readiness permissions/app-ops, blocker type, action/request IDs, and
process/device state.

- [ ] **Step 3: Guard all telemetry calls.**

Every producer call is non-suspending or dispatched to the observability scope and
wrapped by the adapter. No caller catches its business exception merely to make telemetry
succeed. Add a lint-style source assertion test or `rg` verification that production
files outside `observability/` do not import `io.sentry`.

```bash
rg -n 'import io\.sentry' client/androidApp/src/main/kotlin/app/buzer/android --glob '!observability/**'
```

Expected: no output.

- [ ] **Step 4: Run affected existing suites.**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests '*BlockerErrorReporterTest*' --tests '*EnforcementCoordinatorTest*' --tests '*ForegroundReadinessControllerTest*' --tests '*MorningReview*' --console=plain
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit and hand off.**

```bash
git add client/androidApp/src/main/kotlin client/androidApp/src/test/kotlin docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-06-android-context.md
git commit -m "feat: correlate Android enforcement diagnostics"
```

## Task 7: Add Backend Observability Foundation

**Depends on:** shared cross-runtime contract merged. May run in parallel with Tasks 3-6.

**Owner:** backend Sentry boundary, redaction, buffers, request context, and bootstrap.

**Files:**

- Modify: `backend/package.json`
- Modify: `backend/package-lock.json`
- Modify: `backend/tsconfig.json`
- Modify: `backend/tsconfig.build.json`
- Modify: `Dockerfile`
- Modify: `backend/.env.example`
- Create: `backend/src/instrument.ts`
- Create: `backend/src/observability/contracts.ts`
- Create: `backend/src/observability/redactor.ts`
- Create: `backend/src/observability/ringBuffer.ts`
- Create: `backend/src/observability/backendObservability.ts`
- Modify: `backend/src/server.ts`
- Create tests: `backend/test/observability/redactor.test.ts`, `ringBuffer.test.ts`, and `backendObservability.test.ts`

- [ ] **Step 1: Install and lock the current Node SDK.**

```bash
cd backend
npm install @sentry/node@10.70.0
```

Do not add browser replay or another logger. Add `sourceMap: true` and
`inlineSources: true` to build TypeScript options.

- [ ] **Step 2: Write failing backend redaction/buffer tests.**

Mirror Task 1's secret corpus and bounds. The two language implementations need identical
behavior for the corpus, not shared source code. Read the canonical
`test-fixtures/observability/redaction-corpus.json`; both suites assert every input's
expected output and absence of all sentinel values.

- [ ] **Step 3: Define a fakeable backend boundary.**

```ts
export interface BackendObservability {
  log(level: "debug" | "info" | "warning" | "error", message: string, data?: Record<string, unknown>): void;
  captureException(error: unknown, context: Record<string, unknown>): string | null;
  captureDiagnostic(input: BackendDiagnosticCapture): string | null;
  withRequestContext<T>(context: RequestDiagnosticContext, operation: () => Promise<T>): Promise<T>;
  flush(timeoutMs: number): Promise<boolean>;
}
```

Provide `NoopBackendObservability`, `RecordingBackendObservability` for tests, and the
Sentry implementation. Log locally as one redacted JSON line and add structured Sentry
logs/breadcrumbs. Only error paths call `captureException`.

- [ ] **Step 4: Initialize Sentry before application imports.**

`backend/src/instrument.ts` imports only `@sentry/node` and a dependency-free redactor.
It calls `Sentry.init` with blank-DSN disabled behavior, release/environment,
`sendDefaultPii: true`, `enableLogs: true`, `tracesSampleRate: 1.0`, and
`beforeSend`/`beforeBreadcrumb`/`beforeSendLog` filtering.

Use Node ESM preload so Fastify is imported after initialization:

```json
{
  "scripts": {
    "dev": "node --import tsx --import ./src/instrument.ts ./src/server.ts",
    "start": "node --import ./dist/instrument.js ./dist/server.js"
  }
}
```

Update Docker to `CMD ["npm", "run", "start"]`. Tests continue importing `buildServer`
directly and receive the no-op/recording adapter; no test preload is required.

- [ ] **Step 5: Add request context and error capture to Fastify.**

Extend `BuildServerOptions` with `observability?: BackendObservability` and configure
Fastify with `requestIdHeader: "x-request-id"`. Use Fastify's request ID as `request_id`,
copy only valid `sentry-trace`/`baggage` headers into Sentry's
supported propagation path, record sanitized route/method/status/duration, and call the
current official `setupFastifyErrorHandler(app)` after routes. Expected 4xx domain errors
are logs; 5xx and unexpected errors are captured. Keep `logger: false` and never record
the authorization header.

- [ ] **Step 6: Add graceful process handling.**

Capture `unhandledRejection` and `uncaughtException`, flush for at most two seconds, then
preserve normal process-failure semantics. On `SIGTERM`/`SIGINT`, close Fastify and flush
without exceeding the existing container shutdown window.

- [ ] **Step 7: Run focused and complete backend verification.**

```bash
cd backend
npm test -- --run test/observability
npm test
npm run typecheck
npm run build
```

Expected: PASS. Run `rg` over `dist`/test output for seeded secrets and find none.

- [ ] **Step 8: Commit and hand off.**

```bash
git add backend/package.json backend/package-lock.json backend/tsconfig.json backend/tsconfig.build.json backend/src/instrument.ts backend/src/observability backend/src/server.ts backend/test/observability backend/.env.example Dockerfile docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-07-backend-bootstrap.md
git commit -m "feat: bootstrap backend observability"
```

## Task 8: Implement The Read-Only Backend Diagnostic Endpoint

**Depends on:** Task 7 merged.

**Owner:** backend diagnostic persistence/service/route and tests.

**Files:**

- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Create: `backend/src/observability/diagnosticReportRepository.ts`
- Create: `backend/src/observability/diagnosticReportService.ts`
- Modify: `backend/src/observability/contracts.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Create: `backend/test/observability/diagnosticReportRepository.test.ts`
- Create: `backend/test/observability/diagnosticReportService.test.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/http/rateLimit.test.ts`

- [ ] **Step 1: Add failing migration/idempotency tests.**

Create migration version 5 and include the final table in `schema.sql`:

```sql
create table if not exists diagnostic_reports (
  report_session_id text primary key,
  android_diagnostic_event_id text not null,
  backend_diagnostic_event_id text not null,
  device_id text not null,
  captured_at text not null,
  response_json text not null,
  created_at text not null
);

create index if not exists diagnostic_reports_device_created
  on diagnostic_reports(device_id, created_at);
```

Test fresh migration, upgrade from version 4, duplicate insertion returns the original
response, and no existing table/trigger changes.

- [ ] **Step 2: Add failing route validation/auth/rate-limit tests.**

Use a strict Zod body: literal schema version `1`, UUID report session, 32 lowercase hex
Android event ID, ISO instant, at most 20 unique bounded request IDs, and bounded
200-character opaque IDs. Assert missing/invalid bearer -> 401, invalid body ->
400, success -> 202, duplicate -> same 202 response, and sixth request in ten minutes for
one device/IP -> 429. The diagnostic route's narrow limit must not consume the ordinary
60/minute route budget.

- [ ] **Step 3: Add failing read-only service tests.**

Inject fake `TodoistAdapter`, `AccountabilitySender`, action service, and synchronizer
that throw if called. A report must still succeed. Assert selectors query only existing
SQLite state for:

- matching task/occurrence and current commitment state;
- action journal/commands for matching `action_id`;
- current `todoist_sync_state` and snapshot generation;
- recent WhatsApp escalation status, never auth/session data;
- device check-in/readiness data;
- recent redacted backend log/request buffer.

Also capture backend release/full SHA/environment, process uptime, Node version, process
memory, event-loop health, current time, SQLite migration version, WAL/checkpoint state,
bounded table/queue counts, and a lightweight read-only database health result.

When selectors are absent, emit a general capsule. Bound logs to 1,000/15 minutes,
requests to 100/15 minutes, bodies to 64 KiB, attachments to 8 MiB, service collection to
two seconds, and route deadline to three seconds. Every missing/truncated section gets
explicit status metadata.

- [ ] **Step 4: Implement repository and service.**

Use prepared `SELECT` statements only in capsule collectors. The only write is the
idempotency result inserted after the Sentry adapter returns an event ID. Serialize
`backend-diagnostics-v1.json` and `backend-log.txt` and call:

```ts
const eventId = observability.captureDiagnostic({
  message: "buzer.backend.manual_report",
  tags: {
    report_session_id: input.reportSessionId,
    android_diagnostic_event_id: input.androidDiagnosticEventId,
    capsule_schema_version: "1"
  },
  attachments
});
```

Return `{ schemaVersion: 1, reportSessionId, backendDiagnosticEventId: eventId, outcome:
"captured" }`. If the no-op adapter returns `null`, return a bounded typed 503
`observability_unavailable` response and do not create an idempotency row. Route/service
tests inject `RecordingBackendObservability`, which returns deterministic 32-hex IDs.
When Sentry ingest is temporarily unreachable, the real SDK still returns an event ID
and caches its envelope, so the endpoint remains `202 captured`.

- [ ] **Step 5: Register the route without coupling it to action services.**

Add `diagnostics: DiagnosticReportService` to `RouteServices`. Register the route with a
route-specific rate-limit config and an `AbortController`/timeout. Authentication remains
the existing `preHandler`. Never call `services.actions` or `services.synchronizer` from
this route.

- [ ] **Step 6: Run focused and full backend suites.**

```bash
cd backend
npm test -- --run test/db/migrations.test.ts test/observability/diagnosticReportRepository.test.ts test/observability/diagnosticReportService.test.ts test/http/routes.test.ts test/http/rateLimit.test.ts
npm test
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit and hand off.**

```bash
git add backend/src/db backend/src/observability backend/src/server.ts backend/src/http/routes.ts backend/test/db/migrations.test.ts backend/test/observability backend/test/http docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-08-backend-capsule.md
git commit -m "feat: add backend diagnostic reports"
```

## Task 9: Instrument Backend Polling, WhatsApp, And Escalation Signals

**Depends on:** Tasks 7-8 merged.

**Owner:** backend service signal wiring and tests.

**Files:**

- Modify: `backend/src/services/todoistPoller.ts`
- Modify: `backend/src/services/escalationService.ts`
- Modify: `backend/src/accountability/whatsappWebSender.ts`
- Modify: `backend/src/server.ts`
- Modify matching tests under `backend/test/services/` and `backend/test/accountability/`

- [ ] **Step 1: Write failing alert-signal tests.**

Add injected callbacks/interfaces, not direct Sentry imports. Assert:

- poll success resets consecutive failure count and records last success;
- every poll failure is captured with a stable fingerprint and a
  `consecutive_failure_count` tag; the third failure reaches the immediate-alert threshold;
- 15 minutes without success captures once per outage window;
- every WhatsApp escalation send failure captures immediately;
- auth failure captures immediately;
- disconnect logs immediately and captures when still disconnected at five minutes;
- reconnect cancels the pending disconnect alert;
- QR content and recipient/auth/session values never enter logs/events.

- [ ] **Step 2: Implement stateful health signal tracking.**

Extend `TodoistPollerOptions` with `onSuccess`, retain `onError`, and expose no mutable
state publicly. Implement a small `OperationalHealthMonitor` in
`backend/src/observability/` that owns counters/timers and calls the observability adapter.
Inject a clock/timer scheduler for deterministic tests.

- [ ] **Step 3: Replace WhatsApp `console.*` with the injected observer.**

Preserve local operator-readable messages but route them through redacted
`BackendObservability.log`. On QR, log only `qr_required=true`; continue rendering the QR
locally through `qrcode-terminal` because the existing operator workflow needs it, but
never pass the QR string to observability.

- [ ] **Step 4: Capture escalation failures with task/action correlation.**

When `EscalationService.sendForTask` returns `failed`, report escalation ID, task ID,
occurrence key, and error classification. Do not attach recipient ID or raw WhatsApp
session/provider data.

- [ ] **Step 5: Run focused and full backend verification.**

```bash
cd backend
npm test -- --run test/services/todoistPoller.test.ts test/services/escalationService.test.ts test/accountability/whatsappWebSender.test.ts test/observability
npm test
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit and hand off.**

```bash
git add backend/src/services backend/src/accountability/whatsappWebSender.ts backend/src/observability backend/src/server.ts backend/test/services backend/test/accountability backend/test/observability docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-09-backend-signals.md
git commit -m "feat: report backend operational failures"
```

## Task 10: Verify The Cross-Process Report Contract End To End

**Depends on:** Android Tasks 2-6 and backend Tasks 7-9 merged into one integration branch.

**Owner:** contract fixtures and real-backend Android E2E only.

**Files:**

- Modify: `backend/test/e2eServer.ts`
- Modify: `backend/test/e2eServer.test.ts`
- Modify the integrated typed E2E client/helper selected from the latest master
- Create or modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportRealBackendTest.kt`
- Create: `docs/notes/observability-contract-v1.md`

- [ ] **Step 1: Re-audit the integrated E2E foundation.**

Use its typed control client, run/session IDs, reset, wait, and compact diagnostics. Do not
restore removed `tools/fake-backend` code. Record exact chosen helper paths in the plan and
handoff if they differ from this baseline.

- [ ] **Step 2: Add an in-memory Sentry capture sink to `e2eServer`.**

Expose captured diagnostic metadata only through the authenticated test control surface.
Reset it between tests. Never contact Sentry Cloud and never expose production secrets.

- [ ] **Step 3: Add failing real-backend instrumentation coverage.**

Launch the app with blank Sentry DSN but recording Android adapter, invoke `Report a bug`,
simulate form success through the test-only adapter, and assert:

- one Android event and one `POST /diagnostics/reports`;
- all correlation IDs round-trip unchanged;
- backend emits one event with the Android event reference;
- duplicate Android retry produces the same backend event ID;
- authorization remains present on wire but absent from both captured capsules;
- backend unavailable leaves the Android report complete and produces a local failure
  status;
- collector timeout/screenshot unavailable still yields partial evidence.

- [ ] **Step 4: Add trace/request propagation contract coverage.**

Assert `sentry-trace`/`baggage` propagation when a fake trace provider is active, unique
`X-Request-ID`, unchanged bearer auth, backend request context, and no header values in
diagnostics.

- [ ] **Step 5: Run scoped then full real-backend E2E.**

```bash
ANDROID_TEST_CLASS=app.buzer.android.observability.BugReportRealBackendTest scripts/e2e-real-backend.sh
scripts/e2e-real-backend.sh
```

Expected: PASS. The second unfiltered run is mandatory because this changes visible Home
UI and application wiring.

- [ ] **Step 6: Run all JVM/backend verification.**

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
cd ../backend
npm test
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit and hand off.**

```bash
git add backend/test client/androidApp/src/androidTest docs/notes/observability-contract-v1.md docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-10-e2e-contract.md
git commit -m "test: verify correlated bug reports end to end"
```

## Task 11: Add Deduplicated Sentry-To-GitHub Triage

**Depends on:** Runtime event/tag contracts merged. The script and tests may be written
without credentials, but the scheduled trigger must remain disabled until a preliminary
controlled Sentry canary has verified actual release, tag, fingerprint, and grouping
behavior in both projects.

**Owner:** triage script, tests, and workflow only.

**Files:**

- Create: `scripts/sentry-triage.mjs`
- Create: `scripts/sentry-triage.test.mjs`
- Create: `.github/workflows/sentry-triage.yml`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Write failing pure triage tests.**

Export pure functions and inject `fetch`. Fixture both Sentry projects. Test new/regressed
actionable issues, ignored/non-actionable issues, pagination, API failure, GitHub search,
create/update, and stable dedupe marker:

```html
<!-- buzer-sentry-issue-id:1234567890 -->
```

Exactly one GitHub issue exists per stable Sentry issue ID. Title prefix:
`[Sentry][android|backend]`. Body contains the Sentry URL, project, issue title, first/last
seen, count, level, release, environment, and reproduction checklist. It must not contain
event attachments, request bodies, user/task content, auth data, or token values.

- [ ] **Step 2: Implement API clients with native `fetch`.**

Required environment:

```text
SENTRY_AUTH_TOKEN
SENTRY_ORG
SENTRY_ANDROID_PROJECT_ID
SENTRY_BACKEND_PROJECT_ID
GITHUB_TOKEN
GITHUB_REPOSITORY
```

Query unresolved/new/regressed actionable issues from Sentry REST with pagination. Use
GitHub REST issue search/create/update. Set `observability`, `bug`, and platform labels,
creating missing labels only if the token permits. Never print request headers or full
API error bodies.

- [ ] **Step 3: Add a manual-only workflow first.**

```yaml
name: Sentry triage

on:
  workflow_dispatch:

permissions:
  contents: read
  issues: write

jobs:
  triage:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
      - run: node scripts/sentry-triage.mjs
        env:
          SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}
          SENTRY_ORG: ${{ vars.SENTRY_ORG }}
          SENTRY_ANDROID_PROJECT_ID: ${{ vars.SENTRY_ANDROID_PROJECT_ID }}
          SENTRY_BACKEND_PROJECT_ID: ${{ vars.SENTRY_BACKEND_PROJECT_ID }}
          GITHUB_TOKEN: ${{ github.token }}
          GITHUB_REPOSITORY: ${{ github.repository }}
```

The workflow may only file/update issues. It may not dispatch coding agents, commit,
merge, deploy, resolve Sentry issues, or close GitHub issues.

- [ ] **Step 4: Verify a preliminary live contract, then enable the schedule.**

With operator-provided credentials, send one controlled Android diagnostic and one
controlled backend diagnostic. Record event URLs and verify release, environment,
platform, stable fingerprint, and correlation tags. Run `workflow_dispatch` twice and
confirm the second run updates rather than duplicates the GitHub issue. Only then add:

```yaml
  schedule:
    - cron: '17 */6 * * *'
```

If credentials are unavailable, leave this step and the schedule unchecked. The pure
script may merge, but scheduled triage is rollout-incomplete.

- [ ] **Step 5: Add tests to CI and verify.**

```bash
node --test scripts/sentry-triage.test.mjs
git diff --check
```

Add the first command to `.github/workflows/ci.yml` in a small operations job or the
backend job. Expected: PASS without credentials/network.

- [ ] **Step 6: Commit and hand off.**

```bash
git add scripts/sentry-triage.mjs scripts/sentry-triage.test.mjs .github/workflows/sentry-triage.yml .github/workflows/ci.yml docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-11-triage.md
git commit -m "feat: triage Sentry issues into GitHub"
```

## Task 12: Add Release Artifacts, Deployment Configuration, And Runbooks

**Depends on:** Tasks 3, 7, and 11 merged.

**Owner:** build/deploy/docs only; no runtime behavior changes.

**Files:**

- Modify: `backend/package.json`
- Modify: `backend/package-lock.json`
- Modify: `scripts/deploy-vps.sh`
- Modify: `backend/.env.example`
- Modify: `docs/notes/local-development.md`
- Modify: `docs/notes/vps-deployment.md`
- Modify: `AGENTS.md`
- Create: `docs/notes/observability-operations.md`
- Create: `docs/decisions/2026-08-21-hosted-sentry-observability.md`

- [ ] **Step 1: Add source-map verification and upload commands.**

Add `@sentry/cli@3.6.2` as a pinned development dependency. Keep ordinary `npm run build`
credential-free. Add an explicit script that injects debug IDs and uploads only when all
required Sentry release variables exist; missing credentials fail that upload command,
not the application build. `scripts/deploy-vps.sh` computes the deployed short/full SHA,
sets `SENTRY_RELEASE`, builds, health-checks, then invokes the upload from a secret-bearing
operator environment or documents `SKIPPED`.

Never pass `SENTRY_AUTH_TOKEN` into the final Docker image or write it to `.env` if the
token is used only during build/upload.

- [ ] **Step 2: Document exact local and VPS configuration.**

Add blank examples:

```dotenv
SENTRY_DSN=
SENTRY_ENVIRONMENT=development-backend
SENTRY_RELEASE=
```

Document Android ignored `client/local.properties` keys, VPS `.env` keys, GitHub Actions
secrets/variables, disabling behavior, local fake commands, event/tag search examples,
retention/budget expectations, alert thresholds, release resolution, rollback, and how
to disable Sentry without disabling local diagnostics.

- [ ] **Step 3: Record the architecture decision.**

The decision must state: hosted Sentry; two projects; native shake/form plus visible
fallback; broad PII/task capture permitted; secrets prohibited; Sentry inbox + GitHub
queue; no autonomous fixes/deploys; paired Android/backend capsules; best-effort boundary;
and supersession rules.

- [ ] **Step 4: Write the operator setup checklist.**

`docs/notes/observability-operations.md` must list unchecked external steps:

- create organization and `buzer-android`/`buzer-backend` projects;
- store Android/backend DSNs in ignored local/VPS stores;
- create least-privilege source-map and triage tokens;
- configure GitHub repository secrets/variables;
- connect Sentry releases/commits to GitHub;
- create immediate Android enforcement/data-consistency alerts;
- create poller alert for three consecutive failures or 15 minutes without success;
- create immediate WhatsApp escalation failure and five-minute disconnect alerts;
- exclude `feedback_submitted=false` diagnostic events from paging;
- set ownership/routing and retention/budget alerts;
- run manual workflow and verify dedupe.

Each item records date, operator, Sentry/GitHub URL or identifier, and evidence. Do not
check an item merely because code supports it.

Update `AGENTS.md` with any observability verification command that becomes mandatory,
especially the triage unit test, secret-canary scan, and live-canary completion rule.

- [ ] **Step 5: Verify docs/config contain names but no values.**

```bash
git diff --check
rg -n 'SENTRY_(AUTH_TOKEN|DSN)=.+' --glob '!docs/superpowers/plans/**' --glob '!*.example' .
rg -n 'SECRET_(ANDROID|TODOIST|SENTRY|WHATSAPP)_' docs scripts backend client
```

Expected: first PASS; second has no committed secret values; third finds only intentional
test fixtures/assertions.

- [ ] **Step 6: Commit and hand off.**

```bash
git add AGENTS.md backend/package.json backend/package-lock.json backend/.env.example scripts/deploy-vps.sh docs/notes/local-development.md docs/notes/vps-deployment.md docs/notes/observability-operations.md docs/decisions/2026-08-21-hosted-sentry-observability.md docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/handoffs/2026-08-21-observability-task-12-operations.md
git commit -m "docs: add observability operations runbook"
```

## Task 13: Final Automated Verification And Live Canary

**Depends on:** Tasks 1-12 integrated and reviewed.

**Owner:** guarded canary controls, integration/canary evidence, and final handoff. Do not
change normal product behavior unless a failing verification gets its own test-first fix
commit.

**Files:**

- Create: `client/androidApp/src/debug/AndroidManifest.xml`
- Create: `client/androidApp/src/debug/kotlin/app/buzer/android/observability/ObservabilityCanaryActivity.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/ObservabilityCanaryActivityTest.kt`
- Create: `backend/src/observability/canaryRoutes.ts`
- Create: `backend/test/observability/canaryRoutes.test.ts`
- Modify: `backend/src/server.ts`
- Modify: `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`
- Modify: `docs/notes/observability-operations.md`
- Create: `docs/handoffs/2026-08-21-observability-final-verification.md`

- [ ] **Step 1: Write failing canary-guard tests.**

Assert the Android canary activity exists only in the debug manifest and offers stable
`handled`, `crash`, and `anr` modes. Instrumentation invokes only `handled`; crash/ANR are
manual because they intentionally terminate/stall the process. Assert the backend canary
route is absent by default, requires the existing bearer token when enabled, captures a
stable handled exception, and produces a 500 through the normal error handler for the
unhandled mode.

```bash
ANDROID_TEST_CLASS=app.buzer.android.observability.ObservabilityCanaryActivityTest scripts/e2e-real-backend.sh
cd backend
npm test -- --run test/observability/canaryRoutes.test.ts
```

Expected: RED because guarded canary controls do not exist.

- [ ] **Step 2: Implement strictly guarded canary controls.**

`ObservabilityCanaryActivity` is compiled from `src/debug` only and exported only by the
debug manifest. It renders no normal app navigation. Mode `handled` calls the
observability adapter with stable marker `canary.android.handled`; `crash` throws a stable
`RuntimeException` after the activity is visible; `anr` blocks the main looper long
enough for the SDK's ANR detector. Never include seeded secrets in exception messages.

Register backend `POST /diagnostics/canary/:kind` only when
`BuildServerOptions.enableObservabilityCanary === true`, sourced from
`BUZER_OBSERVABILITY_CANARY_ENABLED=true`. Kinds are `handled` and `unhandled`. Both use
stable messages; neither reads/mutates SQLite, Todoist, WhatsApp, or action state. The
route remains behind existing bearer authentication and production config defaults to
false. Disable the flag immediately after canary verification.

- [ ] **Step 3: Review the integrated diff and contract.**

Confirm no direct Sentry imports outside Android/backend observability boundaries, no
secret capture, no business behavior changes hidden in telemetry error handling, no
unbounded buffers, and no external calls from tests.

```bash
git diff --check
rg -n 'import io\.sentry' client/androidApp/src/main/kotlin/app/buzer/android --glob '!observability/**'
rg -n 'from "@sentry/node"|from '\''@sentry/node'\''' backend/src --glob '!instrument.ts' --glob '!observability/**'
```

Expected: `git diff --check` PASS and both import searches empty.

- [ ] **Step 4: Run every automated suite.**

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
cd ../backend
npm test
npm run typecheck
npm run build
cd ..
node --test scripts/sentry-triage.test.mjs
scripts/e2e-real-backend.sh
```

Expected: PASS. The unfiltered E2E run is mandatory.

- [ ] **Step 5: Create a release build and deploy backend with canonical release.**

Verify both Sentry projects receive the same `app.buzer@${VERSION_NAME}+${GIT_SHA_12}` release and full
Git SHA context. Confirm Android mapping/source context and backend source maps resolve
stack frames. Record exact commands and Sentry event URLs in the handoff; never record
token values.

- [ ] **Step 6: Run the physical-device/VPS canary checklist.**

On the target physical device and deployed VPS verify:

1. ordinary app launch and backend health;
2. controlled debug-only test crash, then restart/upload and symbolication;
3. controlled debug-only ANR followed by recovery/restart and usable thread evidence;
4. controlled handled Android enforcement diagnostic;
5. guarded backend handled and unhandled exceptions with route/request/release context;
6. physical shake opens the native form without a permission prompt;
7. visible Home fallback opens the same form;
8. submitted feedback has replay and Android diagnostic reference;
9. backend event joins by `report_session_id` and references Android event ID;
10. airplane-mode report uploads after connectivity returns;
11. backend-outage report preserves Android feedback/capsule;
12. seeded secret strings appear nowhere in event JSON, breadcrumbs, replay metadata,
    attachments, logs, or GitHub issue;
13. alert arrives and triage creates exactly one GitHub issue;
14. rerun triage and regression event update the same issue;
15. resolve the Sentry issue in a release and verify a recurrence reopens/regresses it.

Use `scripts/adb-verify.sh` for compact device evidence. Launch debug controls explicitly:

```bash
adb shell am start -n app.buzer.android/.observability.ObservabilityCanaryActivity --es mode handled
adb shell am start -n app.buzer.android/.observability.ObservabilityCanaryActivity --es mode crash
adb shell am start -n app.buzer.android/.observability.ObservabilityCanaryActivity --es mode anr
```

Verify the release APK has no canary activity. Turn
`BUZER_OBSERVABILITY_CANARY_ENABLED` back to `false` and redeploy after backend canaries.

- [ ] **Step 7: Check push-triggered CI.**

After pushing the integration branch:

```bash
gh run list --workflow=ci.yml --limit 1
```

Wait for completion and record PASS/FAIL. Do not claim completion on red CI.

- [ ] **Step 8: Complete the plan and final handoff only when all gates pass.**

Mark all checkboxes accurately. If credentials/operator access prevent Steps 3-5, leave
them unchecked and hand off the feature as code-complete but rollout-incomplete. Include
every exact command, result, event URL/ID, skipped check, assumption, and next step.

```bash
git add client/androidApp/src/debug client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/ObservabilityCanaryActivityTest.kt backend/src/observability/canaryRoutes.ts backend/test/observability/canaryRoutes.test.ts backend/src/server.ts docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md docs/notes/observability-operations.md docs/handoffs/2026-08-21-observability-final-verification.md
git commit -m "test: verify observability rollout"
```

## Completion Definition

The feature is complete only when Tasks 1-13 are checked, automated suites and full
real-backend E2E pass, push CI is green, external projects/secrets/alerts are configured,
and the live physical-device/VPS canary is documented. Before that point, use the precise
status `implementation complete, rollout pending` or `partially implemented`; never call
the observability loop complete.
