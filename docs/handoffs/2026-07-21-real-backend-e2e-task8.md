# Handoff: Real-Backend E2E Android Scenarios — Task 8

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 8 only. The parent controller owns plan progress, so no plan checkboxes
were changed.

Task 8 is complete:

- Extended the Android-test-only `FakeBackendClient` additively with scenario selection,
  authorized snapshot reads, and reconciliation-status lookup.
- Added a dedicated JUnit4/Compose instrumentation class for the
  `after_command_sent_timeout` and `sync_lag_effect_unknown` real-backend presets.
- The new class probes `/__scenario` in `@Before` and self-skips under the old fake
  backend; supported backends reset to `none`, clear the persisted snapshot authority,
  and clear recorded calls before each test.
- Each test launches the deadline blocker with the standard task/occurrence extras,
  clicks `Done`, waits for the recorded completion request, and verifies the authorized
  snapshot reaches `reconciliationStatus: pending`.

## Files changed

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/RealBackendScenarioTest.kt`
- `docs/handoffs/2026-07-21-real-backend-e2e-task8.md`

No production Android files, backend/server code, fake-backend files, runner scripts,
CI, or plan checkboxes were changed.

## Verification

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
  - Result: FAIL (expected RED)
  - Evidence: `RealBackendScenarioTest` could not resolve the test-first
    `FakeBackendClient.setScenario` and `taskReconciliationStatus` APIs (with consequent
    type-inference diagnostics) before the helper additions.

Verification:

- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
  - Result: PASS
  - Evidence: `BUILD SUCCESSFUL`; `:androidApp:compileDebugAndroidTestKotlin` completed.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## Manual verification

- Confirmed the only request-path change is an opt-in `authorized` parameter, leaving
  existing endpoints unauthenticated and retaining their retry/request bodies.
- Confirmed cleanup resets the scenario even if an assertion or activity launch fails,
  and closes every launched `ActivityScenario`.

## Assumptions

- The E2E server's authorized snapshot contract remains `Authorization: Bearer
  test-token`, as established by the Task 3 server conformance suite and Task 7 runner.
- Full fake- and real-backend emulator lanes are intentionally deferred to Task 9.

## Known failures or skipped checks

- No emulator instrumentation was run: Task 8 requires compilation only, and Task 9
  owns full runner validation.

## Next recommended step

Run Task 9's full backend gate plus old-fake control and real-backend Android E2E lanes,
then record any drift triage.
