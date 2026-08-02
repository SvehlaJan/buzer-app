# Android E2E Test Harness Improvements Design

Date: 2026-08-02

Status: accepted direction, deferred implementation

## Purpose

Make Android end-to-end verification faster to diagnose, cheaper to run, and resistant
to cross-test state, wall-clock edges, ambiguous Compose semantics, and stale local
servers. This is an incremental redesign of the existing real-backend E2E lane, not a
replacement for it.

The immediate motivation is the Smart Home verification work recorded in
`docs/handoffs/2026-08-02-smart-home-and-true-refresh.md`. A sequence-dependent failure
required multiple emulator runs before the failing boundary was observable. The work
eventually produced a green scoped class, but it also exposed systemic weaknesses that
will recur unless the shared harness changes.

This design is intentionally deferred. It records enough context, decisions, interfaces,
migration steps, and acceptance criteria for a later agent to create an implementation
plan without relying on the original chat or debugging transcript.

## Existing Authority And Constraints

- `scripts/e2e-real-backend.sh` and `backend/test/e2eServer.ts` are the sole supported
  deterministic Android E2E lane. `tools/fake-backend` was removed and must not return.
- Android UI or user-visible changes still require the repository's full unfiltered
  real-backend instrumentation suite before completion.
- JVM and backend tests remain the preferred authority for logic that does not require
  Android UI, lifecycle, networking, or platform integration.
- Device/emulator evidence should remain token-efficient: filtered logs and structured
  timelines before screenshots.
- Production backend and Android behavior must not gain test-only branches. All controls
  described here live in `backend/test`, Android instrumentation source, or scripts.
- Live Todoist and WhatsApp are outside this deterministic lane.

Related documents:

- `docs/superpowers/specs/2026-07-18-real-backend-e2e-server-design.md`
- `docs/notes/2026-07-18-e2e-real-backend-validation.md`
- `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`
- `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`

## Observed Failure Modes

The redesign must directly prevent or rapidly localize these failures.

### Compose scheduler starvation

Instrumentation helpers polled lifecycle and files using `SystemClock.sleep`. Actions
launched from a Compose coroutine could receive an HTTP response but not resume their
continuation while the instrumentation thread monopolized the Compose test scheduler.
The same action passed alone when its response happened to win the race.

### Ambiguous text matchers

A substring query for `Task update` matched both the exact dialog title and the longer
message `Task update failed...`. `onNode` therefore failed because it found multiple
nodes, but the helper converted that assertion error into a generic timeout.

### Stale server reuse

The runner accepted any healthy E2E server on the configured port. A server from another
worktree was therefore reused, so new fixtures were absent and unrelated tests failed.

### Incomplete cross-boundary evidence

Request recording proved that Android reached Fastify, but initially did not prove that
Fastify completed the response, Android decoded it, the coordinator applied its
authority, or the Activity finished. A snapshot-file change was also insufficient:
automatic foreground GET refreshes could change the same file before an explicit POST
sync completed.

### Wall-clock-fragile fixtures

`now + 365 days` did not guarantee the next calendar year in a leap year. A same-day
23:30 cross-midnight task was already past when the suite ran late at night.

### Oversized mixed-responsibility class

One 28-test class mixed Home, blocker, Morning Review, failure presentation, fixture
reset, and cross-flow isolation. Sequence failures required rerunning unrelated cases,
and test order obscured which state transition caused the failure.

## Chosen Approach

Adopt an incremental shared TestKit and split the suite by responsibility. Preserve the
real-backend lane and migrate tests in small green steps. Do not perform a ground-up
harness rewrite.

Alternatives rejected:

1. Continue fixing helpers inside each test class. This has the lowest immediate cost
   but preserves divergent polling, selector, lifecycle, and diagnostics behavior.
2. Replace the entire lane at once. This could produce a cleaner endpoint but creates a
   large verification gap and makes it difficult to distinguish harness regressions from
   product regressions.

## Target Test Architecture

### Layer 1: pure and JVM tests

Use backend, shared, and Android JVM tests for:

- task classification, sorting, grouping, and date labels;
- schedule and checklist eligibility;
- state reducers and action routing;
- fixture calendar invariants;
- backend action reconciliation and response authority;
- visibility predicates that can accept state directly.

These tests must use injected clocks and zones. Calendar categories use `LocalDate`
operations such as `today.plusDays(2)` or `year + 1`, never approximate duration
arithmetic.

### Layer 2: focused Compose host/instrumentation tests

Use a rendered screen with synthetic state for:

- sticky-header pinning and Back-to-top visibility;
- section ordering and expansion;
- enabled/disabled actions;
- stable task-ID routing for Done, Edit, and Assign;
- exact semantic labels and accessibility actions.

These tests should not boot the backend when they only need to prove rendering and
callback identity.

### Layer 3: minimal real-backend E2E

Reserve the real-backend emulator lane for:

- Android request serialization and backend contract compatibility;
- authoritative response persistence and reconciliation;
- lifecycle completion after successful or failed actions;
- cached UI during deliberately held network responses;
- a small number of cross-screen and cross-flow regressions.

The lane remains the final UI authority, but it should not duplicate every visual
assertion already proven at Layer 2.

## Shared Android E2E TestKit

Create a small instrumentation-only library, for example under
`client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/`.

Its public API should make the safe behavior the easiest behavior:

```kotlin
waitForExactText("Task update")
waitForTextContaining("failed")
waitForTextAbsent("Invalid assigned fixture")
waitForTag("home-row:task-123")
waitForLifecycle(scenario, Lifecycle.State.DESTROYED)
waitForBackendResponse(path, requestId)
waitForPersistedAuthority(serverTime, snapshotGeneration)
```

Rules:

- Compose-facing waits use `composeRule.waitUntil`; they never busy-sleep.
- Exact text is the default. Substring matching must be explicit and must use a
  multi-node-safe assertion when more than one match is valid.
- Timeout failures include the predicate, matching node count, Activity state, relevant
  backend timeline, and recent app log fragments.
- Network polling that cannot use Compose synchronization is isolated inside the E2E
  server client and must not be called while waiting for a Compose coroutine to advance.
- Test classes do not define private copies of shared wait helpers.

Add a lightweight repository check that rejects new `SystemClock.sleep` or
`Thread.sleep` calls in Compose test classes outside an explicitly allow-listed network
client. The check should also flag new `onNodeWithText(..., substring = true)` calls
unless the test intentionally proves uniqueness.

## Stable Semantic Identity

Visible copy is not a reliable identity boundary. Add stable instrumentation semantics
where task/action identity matters:

- `home-row:<task-id>`
- `home-done:<task-id>`
- `home-edit:<task-id>`
- `home-assign:<task-id>`
- `date-header:<local-date>`
- `editor-task:<task-id>`

Tests may still assert user-visible copy, but they route actions and verify the opened
task through stable identity. Tags must remain instrumentation semantics only; they must
not alter accessibility copy presented to users.

## Hermetic E2E Server Runner

Each invocation must own exactly one server instance.

The runner should:

1. Allocate an available host port, or fail immediately when an explicitly requested
   port is occupied.
2. Generate a unique `runId`.
3. Start `backend/test/e2eServer.ts` with that port and `runId`.
4. Require `/__meta` to return the expected `runId`, process start time, fixture schema
   version, and repository/worktree identity before starting Gradle.
5. Pass the selected port to the emulator and Android instrumentation arguments.
6. Stop only the process it started through a trap/finally path.
7. Refuse to reuse an arbitrary healthy E2E server.

Reusing a server may be offered later as an explicit developer optimization, but it must
require the expected `runId` and worktree identity. It must never be the default.

## Correlated Event Timeline

Generalize the response-authority correlation added during Smart Home verification.
Every relevant request should expose a compact test-only event timeline:

```json
{
  "runId": "...",
  "requestId": "...",
  "actionId": "...",
  "method": "POST",
  "path": "/tasks/sync",
  "receivedAt": "...",
  "serializedAt": "...",
  "completedAt": "...",
  "status": 200,
  "responseAuthority": {
    "serverTime": "...",
    "snapshotGeneration": 42
  }
}
```

Android-side test evidence should add the later boundaries when relevant:

```text
android_response_decoded
authority_persisted
coordinator_completed
activity_destroyed
```

Failure output prints one joined timeline rather than requiring broad logcat searches.
The timeline is diagnostic evidence, not a production analytics system.

## Deterministic Response Barriers

Add test-only controls that can hold and release the next matching response:

```text
POST /__barriers          configure path/method and one-shot hold
GET  /__barriers/:id      report waiting/released/completed state
POST /__barriers/:id/release
```

Properties:

- one-shot by default;
- identified by barrier ID and request ID;
- reset removes every barrier;
- a safety timeout releases or fails the request with explicit diagnostics;
- unavailable outside the E2E server;
- no arbitrary sleeps in tests.

This is the authority for proving that cached Home content remains visible while an
explicit sync is genuinely in flight.

## Fixture Builder

Centralize E2E fixture construction around an injected `Clock`/instant and `ZoneId`.
Provide calendar-aware helpers for:

- current and overlapping tasks;
- overdue tasks;
- tomorrow and later local dates;
- explicit next-calendar-year dates;
- cross-midnight intervals;
- DST gaps/overlaps where applicable;
- owner, implicit owner, unassigned, and assigned-other roots.

Every fixture category must have fast invariant tests using edge clocks such as leap
day, year end, DST transition, and 23:59 local time. Stable task IDs and titles remain
required.

## Suite Decomposition

Migrate the historical mixed class into focused suites:

- `HomeAgendaRealBackendTest`
- `BlockerActionsRealBackendTest`
- `MorningReviewRealBackendTest`
- `TaskEditorRealBackendTest`
- `CrossFlowIsolationRealBackendTest`

Each test owns and closes its `ActivityScenario`. Reset is explicit and returns a new
scenario generation/run token. No test relies on JUnit method order.

Keep exactly one regression that performs Home forced refresh followed by deadline
reschedule. Its purpose is to catch scheduler, client, and lifecycle interference across
flows; it should not become a second copy of the entire Home suite.

## Remaining Smart Home Coverage Placement

The current Smart Home plan leaves four coverage items open. Implement them through the
new architecture rather than expanding the mixed 28-test class:

| Gap | Correct layer |
| --- | --- |
| Prove sticky-header pinning | Focused Compose test with synthetic groups and controlled `LazyListState` |
| Overdue Done/Edit routing | Focused Compose identity/callback test; one backend mutation contract already covers Done authority |
| Exact opened identity for Unassigned/future rows | Stable semantic task IDs plus focused navigation test |
| Cached content during in-flight forced refresh | One real-backend E2E using a deterministic `/tasks/sync` response barrier |

Completing this harness design does not itself complete those Smart Home plan items.

## Failure Artifacts And Token Efficiency

On failure, the runner should print paths to and a compact summary of:

- JUnit XML and HTML report;
- failed-test-only app logcat filtered to Buzer/correlation tags;
- correlated backend event timeline;
- Compose semantics tree for the failed root, capped in size;
- backend process log tail;
- screenshot only when the semantics tree cannot explain a visual failure.

Long Gradle output should be written to a file and summarized as test count, failures,
duration, and report paths. Successful runs should remain quiet.

## CI Strategy

Do not immediately place the entire managed-device suite on every push. First make the
runner hermetic and split the suites.

Then introduce:

1. a small PR smoke lane containing server handshake, one Home authority flow, one
   blocker action, and one failure presentation flow;
2. the full unfiltered suite as a scheduled or manually dispatched lane;
3. sharding by focused test class only after isolation is proven.

CI must upload the compact failure artifacts above. A flaky-test retry may be used only
to classify infrastructure reliability and must still fail or report the original
failure; retries must never silently turn a red product test green.

## Phased Backlog

### Phase 1: shared TestKit and guardrails

- Introduce exact/substring/absence, lifecycle, log, backend, and authority helpers.
- Migrate the current blocker/Home class first.
- Add the raw-sleep and ambiguous-selector repository check.
- Keep all existing scoped tests green.

### Phase 2: hermetic runner and run identity

- Add `/__meta`, dynamic port selection, run ID, worktree/fixture identity, and strict
  cleanup.
- Remove default reuse of an existing server.
- Prove two concurrent worktrees cannot accidentally share one server.

### Phase 3: timeline and barriers

- Generalize the correlated request/response authority timeline.
- Add one-shot hold/release controls.
- Implement the Smart Home in-flight refresh coverage gap.

### Phase 4: semantic IDs and suite split

- Add stable task/action semantics.
- Move visual/callback behavior down to focused Compose tests.
- Split real-backend tests by flow and retain one cross-flow regression.
- Complete the remaining Smart Home coverage gaps.

### Phase 5: CI smoke lane

- Add a small hermetic managed-device smoke lane.
- Upload compact artifacts.
- Add full scheduled/manual instrumentation only after measured stability.

Each phase gets its own implementation plan, worktree, verification evidence, and
handoff. Do not bundle all phases into one implementation change.

## Acceptance Criteria

The redesign is complete only when:

- Compose test classes contain no unapproved blocking sleeps;
- exact text is the default shared matcher and ambiguity failures report match counts;
- every E2E invocation proves it owns the server through a unique run identity;
- automatic GET refresh cannot satisfy an assertion intended for a POST mutation or
  forced sync;
- request, response, Android application, and lifecycle boundaries are visible in one
  failure timeline;
- tests can deterministically hold and release a response without time-based sleeps;
- fixture invariants pass at leap-year, year-end, DST, and late-night clocks;
- the mixed blocker/Home suite is split without reducing behavior coverage;
- one focused command can run each flow suite;
- the unfiltered real-backend suite passes after migration;
- CI or local failure output is compact enough to diagnose without reading full Gradle
  and logcat streams.

## Non-Goals

- Reintroducing a parallel fake backend.
- Exercising live Todoist or WhatsApp.
- Replacing backend unit/integration tests with emulator tests.
- Screenshot or visual-regression testing as the default UI authority.
- Retrying flaky product tests until green.
- Adding test controls to the production server.
- Completing Smart Home, Morning Review scheduling, or permission-health product work
  as part of the harness redesign.

## Risks And Mitigations

- **TestKit becomes a second framework.** Keep its API small and behavior-oriented;
  delete per-class duplicates as each helper migrates.
- **Debug endpoints gain production-like complexity.** Limit them to correlation,
  deterministic barriers, reset, and faults; protect them structurally inside the test
  server.
- **Suite split loses cross-flow bugs.** Retain one explicit Home-refresh-to-reschedule
  regression and a small PR smoke lane.
- **Dynamic ports complicate emulator configuration.** The runner remains the single
  owner of port selection and passes it through existing Gradle properties.
- **Migration creates a long red period.** Move one class/helper at a time, keeping the
  old command green until its replacement is proven.

## Next Step When Work Resumes

Start with a Phase 1 implementation plan. Before coding, audit all instrumentation
helpers and classify each sleep as Compose-facing, lifecycle-facing, file/log polling,
or network-client polling. The plan should enumerate every test class to migrate and
must preserve the existing real-backend suite as the comparison authority throughout
the transition.
