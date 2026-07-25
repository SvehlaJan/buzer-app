# Remove The `tools/fake-backend` Tool And Its CI Lane

Date: 2026-07-25

## Decision

Delete `tools/fake-backend` (the standalone Node.js fake-backend implementation),
`scripts/e2e-fake-backend.sh`, and the `emulator-e2e.yml` nightly CI workflow that ran
it. `backend/test/e2eServer.ts` (the real backend's own test/E2E mode) and
`scripts/e2e-real-backend.sh` are now the sole instrumentation-test authority, as the
unified-task-editor plan's guardrails already required for new work
(`docs/superpowers/plans/2026-07-22-unified-task-editor.md`: "Do not alter, start, or
use `tools/fake-backend`").

`tools/fake-backend` had not been kept in sync with the Android client's UI-structure
changes across the task-editor-redesign and unified-task-editor plans. Its scheduled CI
job (`emulator-e2e.yml`) was already failing on its last two actual (non-idle-skipped)
runs before this change, independent of anything in this branch — confirmed via
`gh run list --workflow=emulator-e2e.yml`. Keeping a second, drifted backend
implementation around was net-negative: it cost real maintenance to keep two Todoist
mirrors in sync, and its CI signal had already stopped being trustworthy.

## Consequences

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/{addtask,blockers}/*FakeBackendTest.kt`
  and `.../ui/screens/MorningReviewRepairHostTest.kt` still exist and still compile —
  their Kotlin-side `FakeBackendClient` just talks to whatever backend URL Gradle
  configures. Run against `scripts/e2e-real-backend.sh` (the only lane left), 12 of
  their tests currently fail; this predates and is unrelated to this decision — see
  `docs/handoffs/2026-07-23-unified-task-editor-task8-final-verification.md` for the
  full list and diagnostic leads. This decision does not fix those tests.
- `ci.yml` no longer installs or tests `tools/fake-backend`; its per-push signal is
  unchanged otherwise (client JVM tests/assemble, backend tests/typecheck/build).
- There is now no scheduled/nightly instrumentation-test CI lane at all.
  `scripts/e2e-real-backend.sh` must be run manually until a replacement is set up (see
  Follow-Up).
- `scripts/e2e-connected-device.sh` now execs `scripts/e2e-real-backend.sh` instead of
  the deleted fake-backend script; its adb-reverse/device-detection behavior is
  unchanged.
- `client/androidApp/build.gradle.kts`'s `FAKE_BACKEND_BASE_URL` build config field and
  the `*FakeBackendTest.kt` class names are left as-is. Renaming/removing them is
  in scope for the follow-up work below, not this change.

## Follow-Up (not done here)

Set up a CI-suitable instrumentation-test lane that doesn't depend on a real device or
a 45-60 minute managed-emulator run — most likely a lighter mock-adapter mode inside
the real backend (reusing `backend/test/e2eServer.ts`'s existing fixture/adapter
machinery rather than a second standalone service), wired into a scheduled or
per-push CI workflow. This should happen alongside — or after — fixing the 12
pre-existing instrumentation-test failures already documented in the Task 8 handoff,
since both involve the same four test files.
