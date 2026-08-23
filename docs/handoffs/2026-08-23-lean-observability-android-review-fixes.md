# Handoff: Lean Observability Android Review Fixes

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review scoped fixes)

Task: Android/shared review fixes assigned by the root agent

## Checkbox status

- [x] Resolve Android build configuration from Gradle properties, `local.properties`, and environment values without logging configuration values.
- [x] Use a local 12-character Git SHA when `GITHUB_SHA` is absent and derive release and SHA from the same value.
- [x] Preserve `X-Request-ID` through API failures, AppLog breadcrumbs, and blocker handled-error correlation.
- [x] Redact generic `auth` keys and keys ending in `cookie` in Kotlin and extend the corpus parity contract.
- [x] Run focused regressions, requested broad JVM/build verification, self-review, and whitespace checks.

## Files changed

- `client/androidApp/build.gradle.kts`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactorTest.kt`
- `test-fixtures/observability/redaction-corpus.json`
- `docs/handoffs/2026-08-23-lean-observability-android-review-fixes.md`

## Implementation summary

- Build configuration now checks Gradle property, local property, then environment for configuration values. It never logs resolved values. The canonical local Git SHA is resolved with `git rev-parse --short=12 HEAD` when `GITHUB_SHA` is absent; both BuildConfig fields use that single value.
- `BuzerApiException` now retains the request ID generated for an HTTP request. The error AppLog record includes only `request_id`, and `BlockerErrorReporter` copies that ID to `DiagnosticCorrelation` for handled server errors. No response body, request body, `sentry-trace`, or `baggage` observability capture was introduced.
- Kotlin redaction now treats exact `auth` and normalized suffix `cookie` keys as credentials, including in text assignments. The shared corpus adds corresponding string and JSON cases.
- Follow-up review fix: the Sentry Gradle extension now receives the already-resolved `SENTRY_AUTH_TOKEN` through `authToken.set(...)`, so Gradle-property and `local.properties` values can reach `sentry-cli` without being logged.
- Follow-up review fix: non-cancellation transport, response-body, and successful-response decode failures retain their original throwable type/message and receive a request-ID-only suppressed marker. `BlockerErrorReporter` reads that marker into `DiagnosticCorrelation`; the non-2xx decoder remains unchanged and `CancellationException` is rethrown without a marker.

## Verification

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientErrorTest*' --tests '*DiagnosticRedactorTest*' --tests '*RedactionCorpusParityTest*' --console=plain`
  Result: FAIL (expected RED)
  Evidence: `BuzerApiException.requestId` was unresolved before production changes, proving the request-ID contract was absent.

- Command: same focused shared test command after implementation
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; API error/request-ID breadcrumb and redactor/corpus regressions passed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*BlockerErrorReporterTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; blocker correlation regression passed.

- Command: `BUZER_BACKEND_BASE_URL=http://env.test ./gradlew :androidApp:generateDebugBuildConfig --console=plain`, then `rg -n 'BUZER_BACKEND_BASE_URL|BUZER_GIT_SHA|SENTRY_RELEASE' androidApp/build/generated/source/buildConfig/debug/app/buzer/android/BuildConfig.java`
  Result: PASS
  Evidence: environment value resolved; `BUZER_GIT_SHA` and `SENTRY_RELEASE` both contained `fb7cd1bea239`.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 74 actionable tasks completed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientErrorTest*' --tests '*BuzerApiClientTimeoutTest*' --console=plain`
  Result: FAIL (expected RED)
  Evidence: transport, timeout, and successful-decode regressions received the prior `BuzerApiException` wrapper rather than their original throwable types.

- Command: same focused shared API command after preserving original throwables
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; MockEngine transport, timeout, decode, and cancellation contracts passed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*BlockerErrorReporterTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; original API-failure marker reached blocker handled-error correlation.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 74 actionable tasks completed.

- Command: `jq empty test-fixtures/observability/redaction-corpus.json` and `git diff --check`
  Result: PASS
  Evidence: valid corpus JSON and no whitespace errors.

- Command: `./gradlew :androidApp:generateDebugBuildConfig -PSENTRY_AUTH_TOKEN=test-token --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; the Sentry extension accepted the Gradle-property token configuration and did not print the token.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 53 actionable tasks completed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientErrorTest*' --console=plain`
  Result: FAIL (expected RED)
  Evidence: `transportFailurePreservesRequestIdInExceptionAndErrorBreadcrumb` received the raw MockEngine `IllegalStateException` before the correlation boundary was added.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientErrorTest*' --tests '*BuzerApiClientTimeoutTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; MockEngine transport and timeout correlation regressions passed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 74 actionable tasks completed.

## Manual verification

SKIPPED. This review patch changes build configuration and error/redaction plumbing only; it makes no UI or user-visible behavior change and instrumentation was explicitly out of scope.

## Assumptions and known limitations

- Gradle property takes precedence over `local.properties`, which takes precedence over environment, preserving existing local configuration precedence while adding environment support.
- The broad Gradle output retains an existing configuration-time classpath warning; it is unrelated to this patch and did not fail verification.
- No secret values were used in verification output.

## Next recommended step

Root agent should review and integrate the single scoped commit with the rest of `feature/lean-observability` review fixes.
