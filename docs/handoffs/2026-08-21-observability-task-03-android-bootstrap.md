# Handoff: Observability Task 3 - Bootstrap Android Sentry Behind A Testable Boundary

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 3: Bootstrap Android Sentry Behind A Testable Boundary  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented Sentry Android SDK bootstrap, build-time configuration, and testable observability boundaries for the Android application:

1. **Gradle Catalog & Build Configuration** (`client/gradle/libs.versions.toml`, `client/build.gradle.kts`, `client/androidApp/build.gradle.kts`):
   - Added Sentry Android SDK `8.53.0` and Sentry Android Gradle Plugin `6.19.0` to version catalog.
   - Applied `io.sentry.android.gradle` plugin to root build and `androidApp`.
   - Configured `sentry { ... }` extension in `androidApp` with `autoInstallation.enabled = false`, `telemetry = false`, native symbols disabled, and Proguard mapping / source context auto-upload enabled only when `SENTRY_AUTH_TOKEN` is present in environment.
   - Added BuildConfig fields: `SENTRY_ANDROID_DSN`, `SENTRY_ENVIRONMENT`, `BUZER_GIT_SHA`, and `SENTRY_RELEASE`.
   - Guaranteed that NO `SENTRY_AUTH_TOKEN` is embedded into `BuildConfig` or generated APKs.

2. **Android Application & Manifest** (`client/androidApp/src/main/AndroidManifest.xml`, `client/androidApp/src/main/kotlin/app/buzer/android/BuzerApplication.kt`):
   - Created `BuzerApplication : Application` that installs `CurrentActivityTracker` and initializes `ObservabilityRuntime` at application start.
   - Updated manifest with `android:name=".BuzerApplication"` and disabled Sentry auto-init `<meta-data android:name="io.sentry.auto-init" android:value="false" />`.

3. **Android Observability Boundary** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt`):
   - Defined `DiagnosticAttachment(pathname, bytes, contentType)` data class with proper byte array equality and hashcode semantics.
   - Defined `AndroidObservability` interface with methods: `isEnabled()`, `setContext(context)`, `addLog(record, throwable)`, `captureHandled(marker, throwable, context)`, `newEventId()`, `captureDiagnosticEvent(...)`, `showFeedback(associatedEventId)`, and `flush(timeoutMillis)`.
   - Provided `NoOpAndroidObservability` object for disabled states / tests.
   - Provided `RecordingAndroidObservability` fake for deterministic testing of observability interactions without calling Sentry.

4. **Sentry Implementation & Sanitizer** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`):
   - Created `SentryEventSanitizer`:
     - Sanitizes event messages, exception types and values, tag keys and values, contexts (including nested diagnostic maps), breadcrumbs, fingerprints, extras, and attachment contents/pathnames using `DiagnosticRedactor`.
     - Masks credential keys (`password`, `token`, `authorization`, `api_key`, `cookie`, `todoist_token`, `sentry_auth_token`, etc.) with `[REDACTED]`.
     - Formats `SentryId` instances into standard 32-character lowercase hex strings without hyphens.
   - Implemented `SentryAndroidObservability : AndroidObservability`:
     - Interacts with `io.sentry.Sentry`, `SentryEvent`, `Breadcrumb`, `Attachment`, `Hint`, and `SentryId`.
     - Maps diagnostic correlation context into Sentry tags and structured `"diagnostic"` context.
     - Attaches sanitized attachments to diagnostic events.
     - Connects user feedback triggers to Sentry Feedback API.
   - Implemented `ObservabilityRuntime`:
     - Initializes `NoOpAndroidObservability` when DSN is blank.
     - Initializes `SentryAndroid` with full configuration (100% error sample rate, 100% trace sample rate, 100% error replay, 0% normal replay, ANR enabled, PII enabled, screenshot/view hierarchy capture, `beforeSend` and `beforeBreadcrumb` sanitizer callbacks, shake feedback enabled with user/branding/name/email disabled) when DSN is non-blank.
     - Automatically routes `AppLog` diagnostic records into the active observability instance.
     - Provides `resetForTests` for isolated testing.

5. **Foreground Activity Tracker** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/CurrentActivityTracker.kt`):
   - Tracks current foreground activity using `WeakReference<Activity>`.
   - Returns non-null `currentActivity` only when started/resumed and not finishing or destroyed, avoiding memory leaks.

6. **Unit Tests** (`client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`, `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt`):
   - `AndroidObservabilityConfigTest`: verified blank DSN disabled/no-op behavior, `RecordingAndroidObservability` recording functionality across all methods, and `CurrentActivityTracker` lifecycle tracking.
   - `SentryEventSanitizerTest`: verified comprehensive secret redaction across event messages, exceptions, tags, diagnostic contexts, breadcrumbs, fingerprints, extras, and text/JSON attachments, while verifying binary attachment preservation and 32-hex SentryId formatting.

---

## 2. Verification and Test Evidence

All unit and integration tests compile and pass:

- **Android Observability Unit Tests**:
  - `AndroidObservabilityConfigTest > blankDsnInitializesNoOpInstanceAndDisablesObservability`: PASSED
  - `AndroidObservabilityConfigTest > recordingAndroidObservabilityRecordsAllInvocations`: PASSED
  - `AndroidObservabilityConfigTest > currentActivityTrackerTracksActivityWeakly`: PASSED
  - `SentryEventSanitizerTest > sanitizesEventMessageExceptionsTagsContextsBreadcrumbsAndExtras`: PASSED
  - `SentryEventSanitizerTest > sanitizesStandaloneBreadcrumb`: PASSED
  - `SentryEventSanitizerTest > sanitizesTextAndJsonAttachmentsAndPreservesBinaryAttachments`: PASSED
  - `SentryEventSanitizerTest > formatsSentryIdAs32CharacterLowercaseHexStringWithoutHyphens`: PASSED

- **Full Client Test Suite & Build**:
  - `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`: BUILD SUCCESSFUL (74 tasks executed/up-to-date)
  - Verified generated `BuildConfig.java` contains only non-sensitive DSN/release/environment fields and NO `SENTRY_AUTH_TOKEN`.

---

## 3. Files Changed

- `client/gradle/libs.versions.toml` (modified)
- `client/build.gradle.kts` (modified)
- `client/androidApp/build.gradle.kts` (modified)
- `client/androidApp/src/main/AndroidManifest.xml` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/BuzerApplication.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/CurrentActivityTracker.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt` (created)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt` (modified)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt` (modified)
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt` (modified)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-03-android-bootstrap.md` (created)

---

## 4. Next Steps

- Proceed to **Task 4: Build Android Context, Capsule, And Attachment Collectors**.
