# Handoff: Shared API Client Request Timeout

Date: 2026-07-01
Agent: Claude (Sonnet 5)
Plan: none (small standalone fix continuing the loading/error-states work)

## Scope

- The shared Ktor `HttpClient` used by `BuzerApiClient` had no timeout configured. A hung
  connection to the backend would leave the Android loading states added in
  `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md` spinning forever instead
  of surfacing the retryable error UI.
- Add a request/connect/socket timeout to the default client, with a real test proving it fires.

## Completed

- `defaultHttpClient` now installs the `HttpTimeout` plugin (5s connect/request/socket timeout by
  default) and accepts optional `timeoutMillis` and `engine` parameters so tests can inject a
  `MockEngine` and a short timeout.
- Added `ktor-client-mock` as a `commonTest` dependency (`client/gradle/libs.versions.toml`,
  `client/shared/build.gradle.kts`).
- Added `BuzerApiClientTimeoutTest.getSnapshotTimesOutWhenServerNeverResponds`, which uses a
  `MockEngine` that delays 300ms against a 50ms configured timeout and asserts
  `HttpRequestTimeoutException` is thrown.

Files changed:
- `client/gradle/libs.versions.toml`
- `client/shared/build.gradle.kts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientTimeoutTest.kt`

## Verification

- Command: `cd client && ./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientTimeoutTest' --console=plain`
- Result: RED then PASS
- Evidence: initial run failed to compile (`No parameter with name 'timeoutMillis'/'engine' found`)
  before implementation; after implementation it failed with `InvalidTestClassError: Method ...
  should be void` (expression-bodied test function inferred a non-Unit return type); after
  switching to a block body, `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

## Decisions And Assumptions

- Chose 5 seconds as the default timeout for connect/request/socket — long enough to tolerate a
  slow Tailscale/VPS round trip, short enough that the Home/Add Task/blocker loading states added
  in the prior plan resolve to a visible retryable error instead of spinning indefinitely.
- Made `defaultHttpClient` `internal` (was `private`) so the commonTest source set can call it
  directly with a `MockEngine`, reusing the exact production plugin configuration rather than
  duplicating it in a test-only client builder.
- Did not add a device/manual verification pass for this change: it is a client HTTP-layer timeout
  with no UI surface of its own: it relies on the loading/error UI already verified manually in the
  loading/error/insets plan.

## Open Issues

- None known. This was a small, fully tested, isolated change.

## Next Step

- Return to the product backlog (`docs/notes/2026-06-13-product-backlog.md`): Home in-progress
  task UI polish, or re-verifying the duplicate blocker surface fix, are the next open items.
