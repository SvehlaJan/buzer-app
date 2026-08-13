# Android E2E Test Foundation Design

Date: 2026-08-09

Status: accepted

## Purpose

Make the existing Android real-backend E2E lane deterministic to start, explicit to
reset, safe across concurrent worktrees, and cheap to diagnose when it fails. This is
the foundation phase of the broader overhaul described in
`docs/superpowers/specs/2026-08-02-e2e-test-harness-improvements-design.md`.

The work is intentionally narrower than the complete overhaul. It delivers a shared
instrumentation TestKit, an owned E2E server process with run/reset identity, compact
failure artifacts, enforceable test guardrails, and removal of obsolete Fake/Real
backend naming. It migrates the large blocker/Home suite deeply and leaves explicit,
linked follow-ups in the other backend suites.

## Prerequisite And Starting State

The foreground-enforcement-readiness implementation must be integrated before this
work starts. The TestKit reuses its instrumentation-only readiness device controller
to establish a healthy default rather than duplicating permission and app-op commands.

At the time of this design:

- `scripts/e2e-real-backend.sh` and `backend/test/e2eServer.ts` are the sole supported
  Android backend E2E authority;
- the last recorded unfiltered run passed 93 tests on managed API 34;
- `tools/fake-backend` and its CI lane have been removed and must not return;
- the runner still defaults to port 18433 and may reuse any compatible service already
  listening there;
- `FakeBackendClient` and `E2EServerClient` duplicate HTTP and retry behavior;
- the large blocker/Home suite mixes Home, blockers, Morning Review, error paths, and
  cross-flow isolation;
- blocking sleeps, raw substring selectors, and private wait helpers remain distributed
  across multiple instrumentation files;
- response authority and a one-purpose forced-sync hold already exist in the E2E server;
- Smart Home added stable semantic identity for important Home rows/actions;
- managed-device instrumentation remains manual; `ci.yml` does not run an emulator.

The implementation plan must re-audit these facts after readiness is integrated and
must treat the integrated repository, rather than this dated inventory, as authoritative.

## Goals

1. Every E2E invocation starts and owns exactly one E2E backend process.
2. Every test begins from an explicit typed reset state rather than inherited device or
   suite state.
3. Compose work is never starved by instrumentation polling.
4. Exact UI identity is the default, and matcher ambiguity produces useful evidence.
5. Backend assertions are scoped to the current run and reset and to an exact HTTP
   boundary.
6. Failures emit a compact, correlated diagnostic bundle without requiring broad log or
   screenshot inspection.
7. New sleeps, ambiguous selectors, and duplicate wait helpers cannot enter unnoticed.
8. Instrumentation naming reflects the one supported backend lane.
9. Existing behavioral coverage and the full-suite release gate remain intact.

## Non-Goals

This foundation does not:

- split the large blocker/Home suite into separate Home, blocker, Morning Review, and
  cross-flow classes;
- move visual or pure behavior assertions into Compose/JVM tests;
- generalize the existing forced-sync hold into arbitrary path/method barriers;
- add the complete joined Android request/decode/persist/coordinator lifecycle timeline;
- add managed-device CI, sharding, or flaky-test retries;
- set a runtime improvement or maximum-duration target;
- reintroduce a parallel backend implementation;
- exercise live Todoist or WhatsApp;
- add test-only branches to production Android or backend code.

## Chosen Architecture

Use explicit composition rather than a base test class or an all-owning JUnit rule.

There are three boundaries:

1. **Runner boundary.** The shell runner owns process creation, dynamic endpoint
   discovery, run identity, Gradle configuration, artifacts, and cleanup.
2. **TestKit boundary.** Focused instrumentation-only components own typed backend
   control, reset presets, Compose-aware waits, lifecycle tracking, readiness setup, and
   diagnostic formatting. `E2ETestSession` composes these components.
3. **Test boundary.** A test explicitly starts a session with a named preset, launches
   and tracks its Activity scenarios, performs behavior, and closes the session.

Setup remains visible in the test. No rule ordering or inheritance should hide which
backend scenario, clock, Morning Review state, or readiness state a test requested.

## Hermetic Runner

### Process And Port Ownership

Each invocation creates a private temporary run directory and a UUID `runId`, generated
through the repository's existing Node runtime so macOS and Linux use the same mechanism.

When `PORT` is not supplied, the runner starts the Node E2E server with port `0`. Node
binds an available port and atomically writes the actual bound port and startup metadata
to a file inside the private run directory. This avoids the time-of-check/time-of-use
race created by selecting a free port in the shell and binding it later.

When `PORT` is explicitly supplied, the E2E server must bind that exact port. If the port
is occupied, the invocation fails immediately and reports the requested port and the
available owning-process diagnostic. It never reuses or terminates the listener.

The runner records the child PID it started. Cleanup may signal and wait for only that
PID. It must not use broad process matching or kill a service discovered by port.

### Server Metadata Handshake

The test-only server exposes `GET /__meta` with:

```json
{
  "runId": "uuid",
  "pid": 12345,
  "startedAt": "ISO-8601 instant",
  "worktreeIdentity": "canonical identity",
  "gitCommit": "full SHA",
  "fixtureSchemaVersion": 1,
  "port": 49152
}
```

The canonical worktree identity is derived from the repository top-level real path. It
is diagnostic identity, not authorization. The fixture schema version is one constant
owned by the E2E server fixture contract and changes only when reset/fixture semantics
change incompatibly.

Before Gradle starts, the runner reads the atomic metadata file, calls `/__meta`, and
requires every expected field to match. The selected backend URL and expected `runId`
are passed to Android. Before its first reset, `E2EBackendClient` calls `/__meta` through
the device route and verifies the same `runId`. This proves both host and device reach
the owned server.

### Emulator And Physical Device Routing

- Managed emulator: `http://10.0.2.2:<dynamic-port>`.
- Connected physical device: `http://127.0.0.1:<dynamic-port>` through a runner-owned
  `adb reverse` mapping.

Cleanup removes only the reverse mapping created for this invocation. Ambiguous attached
devices remain an error; the runner must not guess a serial.

### Failure And Cleanup Behavior

Missing/mismatched metadata, early child exit, device-side handshake mismatch, or an
occupied explicit port fails before product tests run. The diagnostic contains the
expected and observed identity without dumping environment variables or secrets.

Runner traps cover normal exit, Gradle failure, and interruption. Temporary files may be
retained only when they are referenced as failure artifacts; successful runs clean them.

## Run And Reset Identity

The server maintains a monotonic in-process `resetId`. `POST /__reset` increments it and
returns:

```json
{
  "runId": "uuid",
  "resetId": 7,
  "snapshotGeneration": 42
}
```

Every test-only recorded request inherits the current `runId` and `resetId` at receipt.
Reset clears mutable fixtures, calls, failure controls, sync holds, and diagnostic events,
then seeds the requested fixture. Snapshot generation remains monotonic under the
existing backend authority rules.

All backend waits use at least:

```text
runId + resetId + exact HTTP method + exact path + completion state
```

When response authority matters, the typed result also contains the authority captured
from the actual serialized successful response: `serverTime` and
`snapshotGeneration`. Therefore an automatic GET, an earlier test, a received-but-not-
completed POST, or an unrelated path cannot satisfy the assertion.

## Shared Instrumentation TestKit

The TestKit lives under
`client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/` and is unavailable to
production source sets.

### `E2EBackendClient`

This is the sole typed client for test-server controls. It replaces both
`FakeBackendClient` and `E2EServerClient` and owns:

- device-side `/__meta` verification;
- typed reset response;
- named scenario selection;
- failure controls;
- exact, reset-scoped recorded-call queries;
- completed response authority;
- current reconciliation-state queries;
- the existing forced-sync hold/status/release controls;
- bounded connectivity retry for the emulator guest-network startup window;
- JSON decoding and concise HTTP error messages.

Test classes do not inspect raw calls JSON with substring matching. The canonical client
is the only allow-listed location for blocking connectivity retry, and that retry is
used only for idempotent test-control requests.

### `E2ETestPreset`

A preset explicitly defines:

- backend fixture/scenario;
- Morning Review state: `AcknowledgedToday`, `Due`, or `Snoozed`;
- runtime clock: `System` or a fixed instant and zone;
- readiness: `Healthy` or `ManagedByTest`;
- cached snapshot authority: `Clear` or an explicitly seeded fixture;
- notifications and alarms: `Clear` or an explicitly preserved state.

The default preset is `HealthyHome`: reset backend, clear cached authority, restore the
system clock, acknowledge Morning Review for the current local date, clear app-owned
notifications and alarms, and grant every foreground-readiness capability.

Presets are data, not subclass hooks. Specialized tests construct or select a named
preset whose exceptional state is visible at the call site.

### `E2ETestSession`

`E2ETestSession` coordinates one test but delegates work to smaller components. Its
start sequence is fixed:

1. Ask the process-wide instrumentation-only `E2EScenarioRegistry` to close every
   scenario left registered by an earlier test/session.
2. Reset backend fixtures, calls, controls, holds, and diagnostic events.
3. Clear app-owned cached authority, test logs, Morning Review overrides,
   notifications, and alarms unless the preset preserves them.
4. Apply the requested clock and Morning Review state.
5. Apply the readiness state, reusing `ReadinessDeviceController` from the prerequisite
   readiness implementation.
6. Return an immutable token containing `runId`, `resetId`, and initial snapshot
   generation.

Every launched `ActivityScenario` is registered both with its session and with the
process-wide `E2EScenarioRegistry`. It is closed explicitly or by `session.close()` in
`finally`/test teardown. The registry is a leak-safety boundary for an earlier failed test;
it does not launch Activities or carry test data. Session close does not reset the whole
app or backend; it deterministically closes owned scenarios and background executor work.

`ManagedByTest` prevents the session from changing Android capabilities. Dedicated
readiness tests are responsible for restoration through their existing controller. No
production BuildConfig flag or Activity intent bypass is introduced.

### Compose And Lifecycle Waits

The shared API supplies behavior-oriented waits for:

- exact visible text;
- explicit substring text;
- text absence;
- exact test tag;
- exact content description;
- scroll-to-node followed by an assertion;
- Activity lifecycle state;
- completed backend request;
- persisted response authority.

Exact text is the default. Substring helpers state whether exactly one or multiple
matches are valid. Helpers inspect semantics-node collections and report counts and
matching values; they do not catch an assertion failure and reinterpret it as “not ready.”

Compose-facing and lifecycle waits use `composeRule.waitUntil`. They do not block with
`SystemClock.sleep` or `Thread.sleep`.

Backend-only preconditions may poll synchronously before UI work begins. When a backend
response is expected to resume a Compose coroutine, the session runs backend polling on
a dedicated executor and uses `composeRule.waitUntil` to observe the typed result. This
keeps the Compose test scheduler advancing.

Central timeout profiles are:

- UI/semantics: 15 seconds;
- backend, lifecycle, or cold-device boundary: 45 seconds.

A different timeout requires a non-empty reason, and that reason appears in failure
output. The design intentionally sets no suite-duration acceptance target.

### Diagnostics

A TestKit timeout reports:

- failed predicate and timeout profile;
- expected identity;
- actual semantics-node or backend-call count;
- matching texts/tags when safe and capped;
- tracked Activity lifecycle states;
- `runId`, `resetId`, and initial snapshot generation;
- relevant reset-scoped backend events;
- capped recent Buzer log fragments when available.

The runner writes a compact failure bundle containing:

- JUnit XML and HTML report paths;
- failed-test summary;
- Buzer-filtered logcat tail;
- backend process log tail;
- `/__meta` identity;
- reset-scoped request records/timeline available for the failure;
- a capped Compose semantics excerpt when a shared helper captured it.

Screenshots are opt-in and used only when semantics cannot explain a visual failure.
Successful runs remain quiet. Secrets and complete raw environment dumps are excluded.

## Guardrail Scanner

Add a dependency-free repository scanner for Android instrumentation Kotlin sources.
It enforces:

1. no blocking `SystemClock.sleep` or `Thread.sleep` outside the canonical backend
   connectivity retry;
2. no raw substring node lookup outside the explicit shared matcher implementation;
3. no private `waitFor*`/`waitUntil*` helper that duplicates TestKit behavior.

The scanner compares actual legacy violations with a checked-in baseline of exact
normalized fingerprints:

```text
relative file + rule ID + normalized source expression
```

Line numbers are not identity, so harmless line movement does not fail. A new violation,
changed violation, or removed violation without corresponding baseline cleanup fails.
This prevents new debt in an allow-listed file and forces later migrations to shrink the
baseline.

`BlockerActivityBackendTest`, the canonical client, and new TestKit sources must have no
baseline exemptions other than the one documented client connectivity retry. Existing
violations in explicitly deferred suites are baselined. The scanner runs in current
`ci.yml`; no emulator CI is introduced in this phase.

Scanner fixture tests prove additions fail, normalized line movement passes, and stale
baseline entries fail.

## Naming Cleanup

There is one supported backend test lane, so instrumentation names use `Backend`, not
`FakeBackend` or `RealBackend`:

- `FakeBackendClient` and `E2EServerClient` → `E2EBackendClient`;
- `AddTaskFakeBackendTest` → `AddTaskBackendTest`;
- `EditTaskFakeBackendTest` → `EditTaskBackendTest`;
- `BlockerActivityFakeBackendTest` → `BlockerActivityBackendTest`;
- `RealBackendScenarioTest` → `BackendScenarioTest`;
- `UnifiedTaskEditorRealBackendTest` → `UnifiedTaskEditorBackendTest`.

Variables and method names such as `fakeBackend` and `callsFakeBackend` become `backend`
and `callsBackend`. `FAKE_BACKEND_BASE_URL` is removed from Gradle, instrumentation
arguments, and scripts. `BUZER_BACKEND_BASE_URL` is the single endpoint authority.

Historical documents are not rewritten merely to erase accurate old names. Active test
filters and repository guidance are updated to current class names. The authoritative
script remains named `scripts/e2e-real-backend.sh`: that established command distinguishes
the real backend's test mode from the removed standalone fake service and is not a Kotlin
test-class naming suffix.

## Migration Scope

All instrumentation callers move to `E2EBackendClient` and all active Fake/Real naming
is cleaned up. Migration depth then differs intentionally:

- `BlockerActivityBackendTest` fully adopts `E2ETestSession`, presets, shared waits,
  scenario tracking, reset tokens, and diagnostics;
- the other renamed suites retain their existing internal wait/reset implementation for
  this phase and receive guardrail baseline entries plus descriptive linked TODOs;
- the existing full behavior coverage remains; no assertion is deleted merely because
  it is hard to migrate.

The blocker/Home class is not split in this foundation. Its later decomposition is safer
after its waits and reset boundaries are shared and deterministic.

## Deferred Suite Migration Items

These are named follow-up plan items, not completion gates for this foundation. The
implementation plan at
`docs/superpowers/plans/2026-08-09-e2e-test-foundation.md` must preserve these stable
anchors so source TODOs link to an actual plan item.

### `deferred-add-task-backend`

Migrate `AddTaskBackendTest` to `E2ETestSession`, shared selectors/waits, scenario
tracking, and typed reset presets; then remove its guardrail baseline entries.

### `deferred-edit-task-backend`

Migrate `EditTaskBackendTest` to the shared session and waits; then remove its guardrail
baseline entries.

### `deferred-backend-scenario`

Migrate `BackendScenarioTest` to reset-scoped backend waits and shared lifecycle handling;
then remove its guardrail baseline entries.

### `deferred-unified-editor-backend`

Migrate `UnifiedTaskEditorBackendTest` to the shared session, scheduler-aware boundary
waits, and scenario tracking; then remove its guardrail baseline entries.

### `deferred-morning-review-alarm`

Migrate `MorningReviewAlarmInstrumentationTest` notification polling and reset behavior
to the appropriate shared platform wait/session components; then remove its guardrail
baseline entries.

Each deferred file receives a concise class-level TODO that states the remaining work and
links to its matching implementation-plan anchor, for example:

```kotlin
// TODO(e2e-suite-migration): Adopt E2ETestSession and shared waits, then remove this
// suite's guardrail baseline entries.
// Plan: docs/superpowers/plans/2026-08-09-e2e-test-foundation.md#deferred-add-task-backend
```

The fully migrated blocker/Home suite receives no migration TODO.

## Data Flow

For a normal test:

1. Runner starts the owned server and validates host metadata.
2. Gradle installs/starts instrumentation with backend URL and expected `runId`.
3. Test creates an explicit `E2ETestSession`.
4. Session validates device-side server identity.
5. Session resets server and receives `runId/resetId/snapshotGeneration`.
6. Session resets Android test state and applies its preset.
7. Test launches a tracked Activity and performs UI behavior.
8. UI action reaches the real backend.
9. A typed wait selects the exact completed request for the current run/reset/method/path.
10. When required, the test waits for exact authority persistence or lifecycle/UI state.
11. Test closes the session; runner completes Gradle and stops only its server.

No automatic foreground GET can satisfy a POST assertion, and no request from a prior
test or worktree can satisfy the current test.

## Error Handling

- Server startup/identity errors stop before Gradle.
- Reset failure stops the test before Activity launch and includes run metadata.
- Readiness setup failure identifies the exact capability and never bypasses production
  readiness behavior.
- Matcher ambiguity is an immediate descriptive assertion, not a timeout.
- Backend HTTP errors include method, exact path, status, and capped response body.
- Timeout diagnostics distinguish “request not received,” “response not completed,”
  “authority not persisted,” and “Activity/Compose state not reached.”
- Session cleanup attempts every owned cleanup step and reports suppressed cleanup
  failures without replacing the primary test failure.
- Runner cleanup never kills an unowned process.

## Verification Strategy

### Backend

Backend tests cover:

- `/__meta` contents;
- dynamic port metadata publication;
- monotonic `resetId`;
- reset-scoped request recording;
- reset clearing calls, controls, holds, and events;
- serialized response authority remaining attached to the correct completed call.

### Runner

Runner-level tests cover:

- two concurrent server invocations receive distinct ports and run IDs;
- an occupied explicit port fails without terminating its owner;
- metadata mismatch fails before Gradle;
- early server exit fails promptly;
- cleanup stops only the owned child and removes only its reverse mapping/temp state.

These tests exercise the server/runner handshake without booting an emulator whenever
possible.

### Scanner

Fixture-based scanner tests cover new violations, harmless line movement, stale baseline
entries, and the canonical connectivity-retry exception.

### Android TestKit

Focused instrumentation covers:

- exact and substring match-count diagnostics;
- absence and semantic-tag waits;
- scheduler-aware backend-to-Compose completion;
- lifecycle waits;
- tracked scenario cleanup;
- default healthy reset;
- explicit Morning Review and clock presets;
- healthy readiness and `ManagedByTest` behavior;
- reset-token scoping and server identity mismatch.

### Migration And Regression

Verification then runs:

1. the fully migrated `BlockerActivityBackendTest` alone;
2. repeat/order-sensitive blocker/Home sequences that previously exposed starvation or
   state leakage;
3. each renamed backend suite through its focused command;
4. Android JVM/shared tests and APK/instrumentation compilation;
5. backend tests, typecheck, and build;
6. the complete unfiltered `scripts/e2e-real-backend.sh` suite;
7. push-triggered `ci.yml`, including the new static guardrail.

The plan records durations only as context. There is no performance pass/fail threshold.

## Acceptance Criteria

The foundation is complete only when:

- a default invocation never reuses an existing server;
- dynamic runs in two worktrees cannot share run identity or reset-scoped evidence;
- explicit occupied ports fail without killing their owner;
- Android validates the same owned server identity observed by the host;
- every migrated test starts from an explicit reset token and typed preset;
- ordinary migrated tests establish healthy readiness, while readiness tests can own
  capability state explicitly;
- the blocker/Home suite contains no prohibited sleep, raw substring lookup, duplicate
  private wait helper, or guardrail baseline exemption;
- exact HTTP method/path/run/reset/completion identity backs backend assertions;
- Compose continuations can advance while a backend boundary is awaited;
- failure output identifies the failed boundary and provides compact artifacts;
- one canonical `E2EBackendClient` remains;
- no active Kotlin instrumentation symbol, test-class name, or Gradle property uses
  FakeBackend or RealBackend naming; the canonical runner filename remains unchanged;
- every deferred-suite TODO is descriptive and links to its named plan item;
- the scanner runs in `ci.yml` and rejects new/stale violations;
- all renamed focused suites and the unfiltered real-backend suite pass;
- no production behavior or parallel fake backend is added.

## Risks And Mitigations

- **The TestKit becomes a framework.** Keep components small, explicit, and
  instrumentation-only. Prefer typed results over generic callback/polling DSLs.
- **Session reset erases state a test needs.** Every exceptional state is a visible typed
  preset; preserve options are explicit and narrow.
- **Dynamic endpoint plumbing breaks physical devices.** The runner owns both URL
  construction and `adb reverse`, with runner tests and a connected-device smoke.
- **Fingerprint scanning is brittle.** Normalize source expressions and exclude line
  numbers; fixture tests lock behavior.
- **Repository-wide rename creates noisy history.** Keep it mechanical, verify references
  exhaustively, and do not combine unrelated assertion redesign outside the migrated
  suite.
- **The migrated mixed class remains large.** This is accepted temporarily. The linked
  later suite-decomposition phase retains one cross-flow regression while moving tests
  to focused suites/layers.
- **Failure artifact collection hides the primary failure.** Artifact collection is
  best-effort and reports secondary errors without replacing the original exit status.

## Later Overhaul Sequence

After this foundation is stable:

1. migrate the deferred backend suites and shrink the guardrail baseline to zero;
2. generalize response barriers and the joined Android/backend boundary timeline;
3. split the mixed blocker/Home suite and move pure/rendering coverage to JVM or focused
   Compose tests while retaining essential cross-boundary E2E flows;
4. add a small managed-device PR smoke lane, compact artifact upload, and a scheduled or
   manually dispatched full suite;
5. shard only after suite isolation is measured and proven.

Each later unit receives its own design amendment or implementation plan and worktree.
