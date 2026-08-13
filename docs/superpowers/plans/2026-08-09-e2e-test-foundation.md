# Android E2E Test Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android real-backend E2E lane own its backend process, reset every test through typed state, synchronize without starving Compose, and emit compact diagnostics while preserving all existing behavior coverage.

**Architecture:** Keep `scripts/e2e-real-backend.sh` as the public entry point, backed by a testable Node runner that starts one port-0 Fastify server and validates run identity on both host and device. Consolidate Android test control into one typed `E2EBackendClient`, then compose explicit session, preset, scenario-registry, wait, and diagnostic components around the existing Compose rule. Migrate `BlockerActivityBackendTest` completely; rename but defer deep migration of the other backend suites behind enforced, exact guardrail fingerprints.

**Tech Stack:** Claude Code with Sonnet 5, Kotlin/Android instrumentation, Compose UI Test, AndroidX `ActivityScenario`, Node.js 22 ESM and `node:test`, Fastify 5/Vitest, Bash entry-point wrappers, Gradle managed devices, GitHub Actions, and the existing real-backend E2E fixture adapter.

---

## Claude Code / Sonnet 5 execution contract

Execute this plan in order and keep each task within one coherent commit. Use a fresh
context/subagent per task if using `superpowers:subagent-driven-development`; give that
worker only this plan, the accepted design, the required repository documents, and the
task's files. Review the diff and run the task's verification before starting the next
task. Do not parallelize Tasks 1–8: their backend protocol, runner, client, and TestKit
contracts are sequential. Task 9's scanner tests may run in parallel with documentation
review only after Task 8 is committed.

Keep command output compact (`--console=plain -q`, focused test filters, and artifact
paths). Redirect raw Gradle/backend output to files and inspect only summaries/tails.
Do not spend context on screenshots unless semantics cannot explain a failure.

This plan contains approved descriptive source TODOs for deferred suites. They are not
placeholders: each links to a named follow-up item in this document. Do not add any other
TODO, FIXME, bypass, test-only production branch, or unchecked placeholder.

## Hard prerequisite and scope

Do not implement this plan until foreground enforcement readiness is integrated into the
starting `master` and its final handoff records a green unfiltered real-backend suite.
The integrated tree must contain the instrumentation-only readiness controller created by
that work, expected at:

```text
client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt
```

Reuse that controller's `grantAll()` and notification-request-history reset behavior.
If the integrated controller has a slightly different public name, adapt one TestKit
adapter and record the difference in the handoff; do not copy its `pm`/`appops` commands.

This plan changes test infrastructure only. Do not change production backend routes,
Todoist behavior, WhatsApp behavior, product UI, readiness policy, Morning Review policy,
alarm identities, or snapshot authority. Debug endpoints remain structurally inside
`backend/test/e2eServer.ts`. `tools/fake-backend` must not return.

The accepted design is:

```text
docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md
```

The broader deferred direction remains:

```text
docs/superpowers/specs/2026-08-02-e2e-test-harness-improvements-design.md
```

## Required start-of-session routine

From the integrated primary checkout:

```bash
cat AGENTS.md
cat docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
cat docs/superpowers/specs/2026-08-09-e2e-test-foundation-design.md
cat docs/superpowers/plans/2026-08-09-e2e-test-foundation.md
cat docs/handoffs/2026-08-02-smart-home-and-true-refresh.md
cat docs/handoffs/2026-08-02-daily-morning-review.md
cat docs/handoffs/2026-08-09-e2e-test-foundation-design.md
cat docs/notes/2026-06-12-agent-token-efficient-android-testing.md
cat docs/notes/agent-workspaces.md
git worktree list
git status --short --branch
```

Find and read the foreground-readiness implementation handoff produced by its plan:

```bash
rg --files docs/handoffs | rg 'foreground-enforcement-readiness'
```

Confirm its implementation and unfiltered E2E verification are complete. If they are
not, stop: this plan deliberately has no temporary readiness adapter.

Create a dedicated implementation worktree from the latest integrated `master`; do not
implement in the primary checkout or this documentation worktree:

```bash
git worktree add .worktrees/e2e-test-foundation -b feature/e2e-test-foundation master
```

In that worktree:

```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
git status --short --branch
```

Install the locked backend dependencies before Node runner tests:

```bash
cd backend
npm ci
cd ..
```

Required current-documentation lookups before API edits:

```bash
npx ctx7@latest library "Fastify" "listen on port 0 inspect bound address close server and hooks request id"
npx ctx7@latest docs "/fastify/fastify" "Fastify v5 listen port 0 address discovery close lifecycle and request hooks"
npx ctx7@latest library "AndroidX Compose UI Test" "waitUntil semantics node counts and scheduler synchronization"
npx ctx7@latest docs "/websites/developer_android_develop_ui_compose_testing" "waitUntilExactlyOneExists waitUntil node count and semantics diagnostics"
npx ctx7@latest library "Node.js" "child_process detached process groups signals and atomic file rename"
npx ctx7@latest docs "/nodejs/node/v22.17.0" "spawn detached POSIX process groups terminate owned child fs rename randomUUID"
```

Record the starting commit and current test inventory in the implementation handoff. The
pre-foundation inventory was 93 instrumentation tests, including 44 in the historical
mixed blocker/Home class; readiness will increase that count, so do not hard-code 93 as
the final expected total.

Run a baseline focused compile and mixed-suite pass using the integrated current class
name (before Task 3 renames it):

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
cd ..
ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh
```

Expected: compilation succeeds and every test in the current blocker/Home class passes.
If readiness implementation already renamed the class, use the actual integrated name
and document it rather than renaming it twice.

## Locked protocol and behavior decisions

- Default runner port is server-selected port `0`; explicit `PORT` must bind or fail.
- A run is identified by UUID `runId`, canonical worktree path, full Git SHA, process
  PID/start time, fixture schema version `1`, and actual bound port.
- Host and Android both validate `/__meta` against the expected `runId`.
- `POST /__reset` increments `resetId` and returns `runId`, `resetId`, and the seeded
  snapshot generation.
- Recorded calls carry `runId`, `resetId`, Fastify `requestId`, exact method/path,
  received/completed timestamps, status, payload, and response authority.
- Backend waits always select exact `runId + resetId + method + path`; completion and
  response authority are explicit query requirements.
- `E2ETestSession` setup is visible at each test call site; do not hide it in a base class
  or all-owning JUnit rule.
- Default session preset is healthy Home: default fixture, system clock, Morning Review
  acknowledged today, cache/logs/notifications/alarms cleared, readiness healthy.
- Dedicated readiness tests select `ManagedByTest`; ordinary suites select `Healthy`.
- Exact text is the default. Substring and regex matching are explicit APIs.
- UI and lifecycle waits use Compose synchronization. Backend polling that may unblock a
  Compose coroutine runs on a TestKit executor while `composeRule.waitUntil` advances the
  scheduler.
- UI timeout is 15 seconds; backend/lifecycle/cold-device timeout is 45 seconds. Any
  override requires a nonblank reason included in failure output.
- No suite-runtime acceptance threshold is introduced.
- The canonical shell entry point remains `scripts/e2e-real-backend.sh`.
- Active Kotlin class/symbol names use `Backend`, never `FakeBackend` or `RealBackend`.

## File map

### Backend test protocol

- Create `backend/test/e2eRunIdentity.ts` — run metadata, reset token, environment
  parsing, bound-port resolution, and atomic metadata publication.
- Create `backend/test/e2eRunIdentity.test.ts` — pure metadata/environment/publication
  tests.
- Modify `backend/test/e2eServer.ts` — `/__meta`, run/reset identity, reset-scoped call
  records, graceful CLI startup/shutdown, and metadata publication.
- Modify `backend/test/e2eServer.test.ts` — debug-protocol and reset-isolation tests.

### Runner

- Create `scripts/e2e-runner.mjs` — owned server/Gradle/adb orchestration and compact
  failure artifacts.
- Create `scripts/e2e-runner.test.mjs` — server-session, port collision, identity,
  child-exit, cleanup, and simulated Gradle-failure artifact tests.
- Replace the body of `scripts/e2e-real-backend.sh` with a thin Node-runner entry point.
- Modify `scripts/e2e-connected-device.sh` — retain device selection and class parsing;
  delegate dynamic port and `adb reverse` ownership to the Node runner.

### Android instrumentation TestKit

- Delete `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`.
- Delete `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EServerClient.kt`.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EProtocol.kt` —
  serializable metadata, reset token, calls, authority, selectors, and fixtures.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EBackendClient.kt` —
  sole typed HTTP/polling authority.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestPreset.kt` —
  typed fixture, clock, Morning Review, cache/platform/readiness presets.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EScenarioRegistry.kt` —
  process-wide leak-safe ActivityScenario ownership.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestSession.kt` —
  ordered reset application, current token, fixture replacement, scenario tracking, and
  cleanup.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EComposeWaits.kt` —
  exact/substring/regex/absence/tag/content-description/lifecycle/boundary waits.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EDiagnostics.kt` —
  capped correlated timeout evidence.
- Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt` —
  focused TestKit contract tests.
- Modify `client/androidApp/build.gradle.kts` — remove obsolete fake endpoint argument.

### Renamed/migrated suites

- Rename `AddTaskFakeBackendTest.kt` → `AddTaskBackendTest.kt`.
- Rename `EditTaskFakeBackendTest.kt` → `EditTaskBackendTest.kt`.
- Rename `BlockerActivityFakeBackendTest.kt` → `BlockerActivityBackendTest.kt`.
- Rename `RealBackendScenarioTest.kt` → `BackendScenarioTest.kt`.
- Rename `UnifiedTaskEditorRealBackendTest.kt` → `UnifiedTaskEditorBackendTest.kt`.
- Modify `MorningReviewAlarmInstrumentationTest.kt` to use the canonical client name and
  carry its linked deferred-migration TODO.

### Guardrails, CI, and documentation

- Create `scripts/check-e2e-test-guardrails.mjs`.
- Create `scripts/check-e2e-test-guardrails.test.mjs`.
- Create `client/androidApp/src/androidTest/e2e-guardrail-baseline.json`.
- Modify `.github/workflows/ci.yml` to run scanner tests and the scanner.
- Modify `AGENTS.md` with the guardrail command and current backend test naming.
- Create `docs/handoffs/2026-08-09-e2e-test-foundation.md`.
- Update this plan's checkboxes only after each verification passes.

## Task 1: Add backend run/reset identity and exact call correlation

**Files:**
- Create: `backend/test/e2eRunIdentity.ts`
- Create: `backend/test/e2eRunIdentity.test.ts`
- Modify: `backend/test/e2eServer.ts`
- Modify: `backend/test/e2eServer.test.ts`

- [ ] **Step 1: Write RED run-identity unit tests**

Cover these exact behaviors in `e2eRunIdentity.test.ts`:

1. all required CLI environment values parse into a typed identity;
2. blank `E2E_RUN_ID`, `E2E_WORKTREE_IDENTITY`, `E2E_GIT_COMMIT`, or
   `E2E_META_FILE` fails with the variable name;
3. fixture schema version is exactly `1`;
4. a metadata JSON file is written through a sibling temporary file and renamed, with
   no temporary file left afterward;
5. metadata contains the actual port, PID, start instant, run ID, worktree identity,
   commit, and schema version;
6. bound-port resolution accepts Fastify addresses that all expose the same nonzero port
   and rejects no/multiple ports.

Use these production types exactly:

```ts
export const E2E_FIXTURE_SCHEMA_VERSION = 1;

export interface E2ERunIdentity {
  runId: string;
  worktreeIdentity: string;
  gitCommit: string;
  startedAt: string;
  pid: number;
  fixtureSchemaVersion: number;
}

export interface E2ERunMetadata extends E2ERunIdentity {
  port: number;
}

export interface E2EResetToken {
  runId: string;
  resetId: number;
  snapshotGeneration: number;
}
```

- [ ] **Step 2: Run the identity test RED**

```bash
cd backend
npm test -- test/e2eRunIdentity.test.ts
```

Expected: FAIL because `e2eRunIdentity.ts` does not exist.

- [ ] **Step 3: Implement identity parsing and atomic publication**

Implement these exported boundaries:

```ts
export function e2eRunIdentityFromEnvironment(
  env: NodeJS.ProcessEnv = process.env,
  now: () => Date = () => new Date(),
  pid: number = process.pid
): { identity: E2ERunIdentity; metadataFile: string };

export function resolveSingleBoundPort(
  addresses: ReadonlyArray<{ port: number }>
): number;

export async function publishE2ERunMetadata(
  metadataFile: string,
  metadata: E2ERunMetadata
): Promise<void>;
```

`publishE2ERunMetadata` must create the parent directory, write JSON plus a trailing
newline to `${metadataFile}.${randomUUID()}.tmp`, then rename that sibling to the final
path in `finally`-safe cleanup. Never write/truncate the final file in place.

- [ ] **Step 4: Write RED E2E server protocol tests**

Add tests to `e2eServer.test.ts` proving:

- `GET /__meta` returns the supplied run identity with port `0` under `app.inject`;
- first `/__reset` returns reset ID `1`; second returns `2` and a strictly newer or equal
  current snapshot generation consistent with existing monotonic authority;
- recorded POST calls contain the supplied run ID, current reset ID, request ID, exact
  method/path, received timestamp, completed timestamp/status, full payload, and response
  authority;
- a call from reset 1 cannot appear as reset 2;
- reset releases/clears the existing sync hold, calls, full calls, failure controls, and
  adapter scenario before seeding;
- `/__reset` rejects an unknown Morning Review fixture without incrementing reset ID.

Update the public `RecordedCall` contract to:

```ts
export interface RecordedCall {
  runId: string;
  resetId: number;
  requestId: string;
  method: string;
  path: string;
  body: Record<string, unknown>;
  at: string;
  responseCompletedAt: string | null;
  responseStatusCode: number | null;
  responseAuthority: RecordedResponseAuthority | null;
}
```

- [ ] **Step 5: Run the server protocol tests RED**

```bash
npm test -- test/e2eRunIdentity.test.ts test/e2eServer.test.ts
```

Expected: new `/__meta`, reset-token, and call-identity assertions fail.

- [ ] **Step 6: Implement metadata, reset ID, and call identity in the test server**

Change the constructor boundary to:

```ts
export interface E2EServerOptions {
  identity?: E2ERunIdentity;
}

export interface E2EServer {
  app: ReturnType<typeof buildServer>;
  adapter: E2ETodoistAdapter;
  db: Database.Database;
  metadata: E2ERunMetadata;
  reset: (morningReviewScenario?: E2EMorningReviewScenario) => Promise<E2EResetToken>;
}

export async function buildE2EServer(
  options: E2EServerOptions = {}
): Promise<E2EServer>;
```

For `app.inject` tests, use a deterministic fallback identity with run ID
`in-process-test`, current PID/time, worktree `in-process`, commit `in-process`, schema
version `1`, and port `0`. The CLI always supplies environment identity.

Keep `let resetId = 0` in the server instance. Increment only after request validation
and immediately before clearing/seeding. Capture `callResetId = resetId` in `preHandler`
so later reset changes cannot relabel an in-flight call. Return the token after seed via:

```ts
const snapshotGeneration = snapshots
  .getSnapshot(new Date().toISOString())
  .snapshotGeneration;
return { runId: metadata.runId, resetId, snapshotGeneration };
```

Register `GET /__meta` only on the test debug surface. Add an `onClose` hook that closes
the in-memory SQLite database after Fastify has drained in-flight responses.

- [ ] **Step 7: Implement graceful CLI startup and metadata publication**

In the CLI-only branch:

1. parse identity/environment;
2. build server with that identity;
3. call `app.listen({ host: "0.0.0.0", port: Number(process.env.PORT ?? 0) })`;
4. resolve the one actual port from `app.addresses()`;
5. update `server.metadata.port`;
6. atomically publish the metadata file;
7. print one compact `runId/port/pid` readiness line;
8. on `SIGINT` or `SIGTERM`, call `app.close()` once and exit with the signal-appropriate
   status.

Do not publish metadata before `listen` succeeds. An occupied explicit port must make the
process exit nonzero without writing a valid final metadata file.

- [ ] **Step 8: Run focused backend tests GREEN**

```bash
npm test -- test/e2eRunIdentity.test.ts test/e2eServer.test.ts
npm run typecheck
```

Expected: both focused files and TypeScript checking pass.

- [ ] **Step 9: Commit backend protocol identity**

```bash
git add backend/test/e2eRunIdentity.ts backend/test/e2eRunIdentity.test.ts backend/test/e2eServer.ts backend/test/e2eServer.test.ts
git commit -m "test: identify E2E server runs"
```

## Task 2: Replace server reuse with an owned, testable runner

**Files:**
- Create: `scripts/e2e-runner.mjs`
- Create: `scripts/e2e-runner.test.mjs`
- Modify: `scripts/e2e-real-backend.sh`
- Modify: `scripts/e2e-connected-device.sh`

- [ ] **Step 1: Write RED Node runner tests**

Use `node:test`, `node:assert/strict`, temporary directories, and real child processes.
Do not add an npm dependency. Cover:

1. two concurrent `startOwnedBackend()` calls bind distinct nonzero ports and return
   distinct run IDs;
2. each returned `/__meta` exactly matches its expected worktree/commit/run identity;
3. an explicitly occupied port rejects and leaves the owner process alive;
4. a child command that exits before metadata publication reports exit code and backend
   log path promptly;
5. `validateMetadata` rejects the wrong run ID, commit, path, schema, or port;
6. `stopOwnedProcess` stops only the detached process group it owns;
7. a simulated Gradle exit `7` retains a compact artifact directory and preserves exit
   `7` as the primary status;
8. successful simulated Gradle execution removes temporary run state and reports PASS;
9. connected-device setup creates and removes only `tcp:${port}` for the selected
   serial using an injected adb command runner.

Expose these testable functions:

```js
export async function startOwnedBackend(options) {}
export async function validateMetadata(baseUrl, expected) {}
export async function stopOwnedProcess(session) {}
export async function runGradle(session, options) {}
export async function collectFailureArtifacts(session, gradleResult, options) {}
export async function runE2E(options = {}) {}
```

`options` accepts injected `spawn`, `fetch`, command paths, and clock only for Node unit
tests. Environment parsing remains in `main()`; no hidden production/test BuildConfig
switch is added.

- [ ] **Step 2: Run runner tests RED**

```bash
node --test scripts/e2e-runner.test.mjs
```

Expected: FAIL because the runner module does not exist.

- [ ] **Step 3: Implement owned backend startup**

In `startOwnedBackend`:

- create `runId` with `crypto.randomUUID()`;
- derive repository root with `git rev-parse --show-toplevel`, canonicalize with
  `realpath`, and read full `git rev-parse HEAD`;
- create a private `fs.mkdtemp(path.join(os.tmpdir(), "buzer-e2e-"))` directory;
- create `client/androidApp/build/e2e-artifacts/${runId}/` for logs and retained failure
  evidence;
- spawn `npm run e2e-server` in `backend/` with `detached: true`, output redirected to
  `backend.log`, and environment values `PORT` (explicit or `0`), `E2E_RUN_ID`,
  `E2E_WORKTREE_IDENTITY`, `E2E_GIT_COMMIT`, and `E2E_META_FILE`;
- wait at most 60 seconds for either the atomic metadata file or child exit, using async
  timers rather than blocking sleep;
- parse the file, validate `/__meta`, and return the owned PID/process group, port, URLs,
  paths, and metadata.

Never probe/reuse a pre-existing `/health` service. Never discover a PID from a port.
If an explicit port fails with `EADDRINUSE`, run a read-only, bounded
`lsof -nP -iTCP:${port} -sTCP:LISTEN` diagnostic when `lsof` is available and include its
capped output; otherwise state that owner details are unavailable. This diagnostic is
never a kill target.

Treat unset `PORT` as dynamic. If `PORT` is set, require a base-10 integer from 1 through
65535; reject blank/zero/out-of-range/non-numeric explicit values before spawning. Pass
`0` to the child only for the unset dynamic case.

- [ ] **Step 4: Implement device routing and Gradle invocation**

Use these rules exactly:

```text
ANDROID_SERIAL unset  -> pixel8Api34DebugAndroidTest, URL http://10.0.2.2:${port}
ANDROID_SERIAL set    -> :androidApp:connectedDebugAndroidTest,
                         adb -s ${serial} reverse tcp:${port} tcp:${port},
                         URL http://127.0.0.1:${port}
```

Build Gradle arguments as an array and include:

```text
--rerun-tasks
--console=plain
-q
-PBUZER_BACKEND_BASE_URL=${deviceBackendUrl}
-PBUZER_BACKEND_TOKEN=test-token
-PBUZER_ASSIGNEE_ME_ID=${assigneeMeId}
-PBUZER_ASSIGNEE_WIFE_ID=${assigneeWifeId}
-Pandroid.testInstrumentationRunnerArguments.E2E_RUN_ID=${runId}
```

Include the existing managed-device GPU property and `ANDROID_TEST_CLASS` filter when
set. For this task only, continue also passing the transitional
`-PFAKE_BACKEND_BASE_URL=${deviceBackendUrl}` Gradle property so the old client works;
Task 3 removes it after all callers move to `E2EBackendClient`.

Write complete Gradle stdout/stderr to `gradle.log`. On success, parse JUnit XML files
under Android's existing result directories and print only total tests, failures,
duration, and report paths. On failure, preserve the Gradle exit code.

- [ ] **Step 5: Implement cleanup and compact failure artifacts**

On failure, before stopping the backend:

- fetch `/__meta`, `/__calls`, and `/__calls/full` into capped JSON files;
- copy or reference JUnit XML/HTML and managed-device `testlog/test-results.log`;
- filter Buzer/correlation lines from managed testlog; for connected devices use a
  bounded `adb logcat -d -s Buzer BuzerE2ECorrelation` query;
- save the last 100 lines of backend and Gradle logs as separate tails;
- print artifact paths and a concise failed-test summary;
- do not capture a screenshot automatically.

Install `SIGINT`/`SIGTERM` handlers once. Cleanup removes the invocation's adb reverse,
sends `SIGTERM` to the negative owned PID/process group on POSIX, waits with a bounded
grace period, and uses `SIGKILL` only on that same owned group if it fails to exit. Do not
call `pkill`, `lsof -t | kill`, or kill a port owner.

Successful runs remove the private temp directory and artifact directory. Failed runs
retain the artifact directory but remove private metadata state after copying metadata.

- [ ] **Step 6: Make shell entry points thin and safe**

Replace `scripts/e2e-real-backend.sh` with argument-free delegation that preserves the
existing environment contract:

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec node "${ROOT_DIR}/scripts/e2e-runner.mjs"
```

Keep `scripts/e2e-connected-device.sh --class CLASS_OR_METHOD` parsing and exact-one-
device resolution. Remove its fixed port, `adb reverse`, and connected URL logic. It only
exports `ANDROID_SERIAL` and optional `ANDROID_TEST_CLASS`, then execs the canonical
runner.

- [ ] **Step 7: Run Node runner tests GREEN**

```bash
node --test scripts/e2e-runner.test.mjs
```

Expected: every ownership, collision, identity, cleanup, and artifact test passes with
no orphan `e2eServer.ts` process.

- [ ] **Step 8: Prove the new runner against the still-old Android client**

```bash
ANDROID_TEST_CLASS=app.buzer.android.blockers.RealBackendScenarioTest scripts/e2e-real-backend.sh
```

Expected: 2/2 tests pass through a dynamic port; output reports the owned run ID and does
not say “Using existing E2E backend.” Verify no server remains:

```bash
pgrep -fl 'backend.*e2eServer|tsx.*e2eServer' || true
```

Expected: no process belonging to this completed invocation. Do not terminate unrelated
processes found by this diagnostic; inspect run metadata if output exists.

- [ ] **Step 9: Commit the hermetic runner**

```bash
git add scripts/e2e-runner.mjs scripts/e2e-runner.test.mjs scripts/e2e-real-backend.sh scripts/e2e-connected-device.sh
git commit -m "test: own E2E backend processes"
```

## Task 3: Consolidate the Android backend client and remove obsolete naming

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EProtocol.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EBackendClient.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt`
- Delete: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EServerClient.kt`
- Delete: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Rename/modify: all five backend test files listed in the file map
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt`
- Modify: `client/androidApp/build.gradle.kts`
- Modify: `scripts/e2e-runner.mjs`

- [ ] **Step 1: Write the typed Android protocol models**

Create serializable test-only models with these exact public names and fields:

```kotlin
@Serializable
data class E2ERunMetadata(
    val runId: String,
    val pid: Int,
    val startedAt: String,
    val worktreeIdentity: String,
    val gitCommit: String,
    val fixtureSchemaVersion: Int,
    val port: Int
)

@Serializable
data class E2EResetToken(
    val runId: String,
    val resetId: Long,
    val snapshotGeneration: Long
)

@Serializable
data class E2EResponseAuthority(
    val serverTime: String,
    val snapshotGeneration: Long
)

@Serializable
data class E2ERecordedCall(
    val runId: String,
    val resetId: Long,
    val requestId: String,
    val method: String,
    val path: String,
    val body: JsonObject = JsonObject(emptyMap()),
    val payload: JsonObject = JsonObject(emptyMap()),
    val at: String,
    val responseCompletedAt: String? = null,
    val responseStatusCode: Int? = null,
    val responseAuthority: E2EResponseAuthority? = null
)

data class E2ECallSelector(
    val token: E2EResetToken,
    val method: String,
    val path: String,
    val actionId: String? = null
)
```

Add `enum class E2EBackendFixture(val wireName: String?)` with `Default(null)` and every
currently accepted Morning Review value: `Clean`, `Invalid`, `Overdue`, `Today`,
`BlockingMix`, `PendingOnly`, `Unassigned`, `PriorityStart`, and `PriorityDeadline`.

- [ ] **Step 2: Write RED client-focused instrumentation tests**

Create the first tests in `E2ETestKitInstrumentationTest.kt` for:

1. `backendClient_validatesRunnerIdentity` proves the default client validates the
   runner-provided run ID;
2. a client constructed with `expectedRunId = "wrong"` fails with expected/observed IDs;
3. two consecutive resets return the same run ID and the second reset ID is exactly one
   greater than the first (never assume the suite-wide server is still at reset ID 0);
4. a typed completed-call query rejects the wrong reset ID, method, or path;
5. a completed call exposes response status/authority and full payload;
6. connectivity retry is used only by initial identity verification; a mutating control
   request is sent once and propagates its error.

At this step, tests may reference the client directly without `E2ETestSession`. Exercise
retry counts with an injected fake `E2EHttpTransport`: return two IO failures then valid
metadata for identity, and one IO failure for reset; assert attempt counts are 3 and 1.

- [ ] **Step 3: Run client tests RED**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
```

Expected: compilation fails because the canonical client does not exist.

- [ ] **Step 4: Implement the canonical client**

Use this constructor and public control surface:

```kotlin
class E2EBackendClient(
    private val baseUrl: String = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/'),
    private val expectedRunId: String = InstrumentationRegistry.getArguments()
        .getString(E2E_RUN_ID_ARGUMENT)
        .orEmpty(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun verifyIdentity(): E2ERunMetadata
    fun reset(fixture: E2EBackendFixture = E2EBackendFixture.Default): E2EResetToken
    fun setScenario(name: String)
    fun setFailActions(enabled: Boolean)
    fun setFailSnapshot(enabled: Boolean)
    fun setFailProjects(enabled: Boolean)
    fun setFailNextChildAdd(enabled: Boolean)
    fun holdNextSync()
    fun syncHoldState(): String
    fun releaseSync(): String
    fun reconciliationStatus(taskId: String): String?
    fun calls(token: E2EResetToken): List<E2ERecordedCall>
    fun fullCalls(token: E2EResetToken): List<E2ERecordedCall>
    fun matchingCalls(selector: E2ECallSelector): List<E2ERecordedCall>
    fun updateCalls(token: E2EResetToken, taskId: String): List<E2ERecordedCall>
    fun awaitReceivedCall(selector: E2ECallSelector, timeoutMillis: Long): E2ERecordedCall
    fun awaitCompletedCall(selector: E2ECallSelector, timeoutMillis: Long): E2ERecordedCall
    fun awaitCompletedCallCount(
        selector: E2ECallSelector,
        expectedCount: Int,
        timeoutMillis: Long
    ): List<E2ERecordedCall>
}
```

Keep HTTP mechanics behind an instrumentation-only internal boundary:

```kotlin
internal fun interface E2EHttpTransport {
    fun request(method: String, url: String, body: String?): E2EHttpResponse
}

internal data class E2EHttpResponse(val status: Int, val body: String)
```

The public/default constructor uses `HttpURLConnection`; an internal constructor accepts
transport and sleeper functions for the focused instrumentation tests. This is test
infrastructure, not a production seam.

Rules:

- `verifyIdentity` rejects blank base URL/run ID and polls only `GET /__meta` for the
  45-second guest-connectivity window;
- after identity succeeds, every control request is one HTTP attempt; never replay
  `POST /__reset` after an ambiguous IOException;
- all polling flows through one private `poll` loop and exactly one documented
  `Thread.sleep(POLL_INTERVAL_MILLIS)` statement in this file;
- `calls(token)` filters exact run/reset identity even if the server already filters;
- selectors compare uppercase exact method and exact path; never use raw JSON substring;
- `awaitCompletedCall` requires non-null completion time/status and applies optional
  action ID against `payload["actionId"]`;
- timeout errors include selector and compact last-seen calls;
- close every stream and disconnect every `HttpURLConnection` in `finally`.

- [ ] **Step 5: Rename Kotlin files, classes, variables, and methods mechanically**

Use `mv` for the five files, then update package symbols:

```text
AddTaskFakeBackendTest              -> AddTaskBackendTest
EditTaskFakeBackendTest             -> EditTaskBackendTest
BlockerActivityFakeBackendTest      -> BlockerActivityBackendTest
RealBackendScenarioTest             -> BackendScenarioTest
UnifiedTaskEditorRealBackendTest    -> UnifiedTaskEditorBackendTest
fakeBackend                         -> backend
e2eServer                           -> backend
*CallsFakeBackend                   -> *CallsBackend
```

Move every caller to `app.buzer.android.e2e.E2EBackendClient`. Delete the two old client
files only after all imports compile.

Every deferred suite stores the token returned by its existing reset boundary in a
`lateinit var resetToken: E2EResetToken` (or nullable field initialized before use) and
passes that token to `calls`, `matchingCalls`, or `updateCalls`. This is the minimum
required run/reset isolation; the linked later migration still replaces its private waits
and reset code with `E2ETestSession`. Convert raw JSON-fragment assertions to typed
`JsonObject` field assertions while preserving their behavior.

Remove the obsolete self-skip in `BackendScenarioTest`: the only supported server always
has `/__scenario`. Replace its `@Before` with a normal canonical reset and scenario
`none`; a missing endpoint must fail, not skip.

- [ ] **Step 6: Add the approved linked deferred TODOs**

Add one class-level comment to each not-yet-deeply-migrated file, using its exact anchor:

```kotlin
// TODO(e2e-suite-migration): Adopt E2ETestSession and shared waits, then remove this
// suite's guardrail baseline entries.
// Plan: docs/superpowers/plans/2026-08-09-e2e-test-foundation.md#deferred-add-task-backend
```

Map anchors as follows:

```text
AddTaskBackendTest                     #deferred-add-task-backend
EditTaskBackendTest                    #deferred-edit-task-backend
BackendScenarioTest                    #deferred-backend-scenario
UnifiedTaskEditorBackendTest           #deferred-unified-editor-backend
MorningReviewAlarmInstrumentationTest  #deferred-morning-review-alarm
```

Do not add a TODO to `BlockerActivityBackendTest`; it is fully migrated in Tasks 6–8.

- [ ] **Step 7: Remove fake endpoint configuration**

Delete `FAKE_BACKEND_BASE_URL` from `androidApp/build.gradle.kts` and from runner Gradle
arguments. `BuildConfig.BUZER_BACKEND_BASE_URL` is the sole endpoint for production app
and instrumentation client. Keep the canonical script filename
`scripts/e2e-real-backend.sh`.

- [ ] **Step 8: Compile and scan active source names**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
cd ..
rg -n 'FakeBackend|RealBackend|FAKE_BACKEND_BASE_URL' client/androidApp/src/androidTest client/androidApp/build.gradle.kts scripts --glob '!**/build/**'
```

Expected: compilation succeeds and the scan has no matches. Historical docs are not part
of this scan; the canonical script filename contains lower-case `real-backend` by design.

- [ ] **Step 9: Run canonical client instrumentation GREEN**

```bash
ANDROID_TEST_CLASS=app.buzer.android.e2e.E2ETestKitInstrumentationTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.blockers.BackendScenarioTest scripts/e2e-real-backend.sh
```

Expected: client contract tests and 2/2 scenario tests pass through the owned server.

- [ ] **Step 10: Commit client consolidation and naming**

Stage the explicit created/deleted/renamed Android files, Gradle file, and runner; verify
`git status --short` contains no unrelated paths, then:

```bash
git commit -m "test: consolidate E2E backend client"
```

## Task 4: Build typed session presets and leak-safe scenario ownership

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestPreset.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EScenarioRegistry.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestSession.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt`
- Use prerequisite: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt`

- [ ] **Step 1: Write RED preset/session instrumentation tests**

Cover:

1. `HealthyHome` verifies server identity, resets backend, returns a token, deletes cached
   snapshot/error log, restores system Morning Review clock, acknowledges today, clears
   notifications/alarms, and establishes all readiness capabilities;
2. `MorningReview(Due, Invalid, FixedClock)` leaves local acknowledgement empty, seeds
   invalid Review fixture, and applies the exact instant/zone;
3. `Snoozed` writes matching local date and pending instant;
4. `ManagedByTest` does not alter a capability denied by the test;
5. starting a new session closes a scenario leaked in the process registry by a prior
   deliberately unclosed session;
6. `session.close()` closes its scenarios, stops boundary executor work, restores system
   runtime clock, and is idempotent;
7. `replaceBackendFixture` changes only backend fixture/token and preserves current
   Android local state;
8. if more than one internal cleanup step fails, the first cleanup error contains later
   cleanup errors as suppressed exceptions and every cleanup step was attempted.

- [ ] **Step 2: Run session tests RED**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
```

Expected: compilation fails because preset/session/registry types do not exist.

- [ ] **Step 3: Implement typed preset data**

Use these types:

```kotlin
sealed interface E2ERuntimeClock {
    data object System : E2ERuntimeClock
    data class Fixed(val instant: Instant, val zoneId: ZoneId) : E2ERuntimeClock
}

sealed interface E2EMorningReviewState {
    data object AcknowledgedToday : E2EMorningReviewState
    data object Due : E2EMorningReviewState
    data class Snoozed(val until: Instant) : E2EMorningReviewState
}

sealed interface E2ECachedAuthority {
    data object Clear : E2ECachedAuthority
    data class Seeded(val serializedSnapshot: String) : E2ECachedAuthority
}

enum class E2EPlatformState { Clear, Preserve }
enum class E2EReadinessState { Healthy, ManagedByTest }

data class E2ETestPreset(
    val fixture: E2EBackendFixture,
    val clock: E2ERuntimeClock,
    val morningReview: E2EMorningReviewState,
    val cachedAuthority: E2ECachedAuthority,
    val notificationsAndAlarms: E2EPlatformState,
    val readiness: E2EReadinessState
) {
    companion object {
        fun healthyHome(): E2ETestPreset
        fun morningReview(
            fixture: E2EBackendFixture,
            clock: E2ERuntimeClock = E2ERuntimeClock.System
        ): E2ETestPreset
    }
}
```

`morningReview` requires a non-default fixture and uses `Due`, cache clear, platform
clear, and healthy readiness. Do not add a generic map of flags.

- [ ] **Step 4: Implement the process-wide scenario registry**

Use synchronized snapshot-and-clear ownership:

```kotlin
object E2EScenarioRegistry {
    private val scenarios = linkedSetOf<ActivityScenario<out Activity>>()

    fun <T : Activity> register(scenario: ActivityScenario<T>): ActivityScenario<T>
    fun unregister(scenario: ActivityScenario<out Activity>)
    fun closeAll()
    fun trackedCount(): Int
}
```

Never call `scenario.close()` while holding the registry monitor: copy/clear under lock,
then close each scenario outside it. Attempt every close and throw one error with later
close errors suppressed.

- [ ] **Step 5: Implement ordered session reset**

Use this boundary:

```kotlin
class E2ETestSession(
    private val composeRule: ComposeContentTestRule,
    private val backend: E2EBackendClient = E2EBackendClient(),
    private val context: Context = ApplicationProvider.getApplicationContext(),
    private val readiness: ReadinessDeviceController = ReadinessDeviceController()
) : AutoCloseable {
    val token: E2EResetToken
        get() = checkNotNull(currentToken) { "E2E session has not been started" }

    fun start(preset: E2ETestPreset): E2EResetToken
    fun replaceBackendFixture(fixture: E2EBackendFixture): E2EResetToken
    fun setRuntimeClock(clock: E2ERuntimeClock)
    fun <T : Activity> track(scenario: ActivityScenario<T>): ActivityScenario<T>
    internal fun <T> submitBoundary(block: () -> T): Future<T>
    internal fun tokenOrNull(): E2EResetToken?
    internal fun scenarioDescriptions(): List<String>
    internal fun backendClient(): E2EBackendClient
    override fun close()
}
```

`start` executes exactly:

1. registry `closeAll()`;
2. `backend.verifyIdentity()` once per session object;
3. `backend.reset(preset.fixture)`;
4. delete `latest_snapshot.json` or atomically write `Seeded.serializedSnapshot`;
5. delete `blocker_action_errors.log`;
6. cancel stored Morning Review identity, clear local state, then apply clock and requested
   Morning Review state using one clock snapshot;
7. if platform state is Clear, cancel all app notifications; call
   `ScheduledAlarmRegistry.fromContext(context).reconcile(emptyList(),
   AndroidAlarmScheduler(context))` on every API, and additionally call
   `AlarmManager.cancelAll()` on API 34+ to remove any leaked unregistered alarm;
8. if readiness is Healthy, call the prerequisite device controller to grant all and
   clear notification-request history, then use `composeRule.waitUntil` to verify the
   real `AndroidEnforcementReadinessChecker` returns Ready; if ManagedByTest, do nothing;
9. store/return the reset token.

Own one single-thread boundary executor per session. `submitBoundary` rejects calls after
close; `close` cancels outstanding work and awaits executor termination without blocking
inside a Compose predicate. Do not call `pm clear`, reinstall the app, or mutate
production dependency singletons.
`replaceBackendFixture` performs only backend reset and token replacement. `close`
restores `MorningReviewRuntimeClock` to system, closes scenarios/executor resources, and
does not reset backend or capability state.

- [ ] **Step 6: Run focused session instrumentation GREEN**

```bash
cd ..
ANDROID_TEST_CLASS=app.buzer.android.e2e.E2ETestKitInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: all client and session contract cases pass; teardown leaves no ActivityScenario
or runtime-clock override.

- [ ] **Step 7: Commit typed session isolation**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestPreset.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EScenarioRegistry.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestSession.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt
git commit -m "test: centralize E2E session reset"
```

## Task 5: Add scheduler-aware waits and correlated diagnostics

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EComposeWaits.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EDiagnostics.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestSession.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt`

- [ ] **Step 1: Write RED matcher/diagnostic tests**

Use one `setContent` with mutable state per test. Prove:

- exact text succeeds for exactly one node;
- two exact nodes fail immediately with `expected exactly 1`, actual count `2`, and both
  matching semantic values;
- explicit substring-one has the same uniqueness contract;
- explicit substring-many succeeds with an expected minimum/count;
- absence succeeds only at zero exact matches;
- tag and content-description waits report their identity;
- regex text wait returns the exact matched string and rejects multiple matches;
- timeout output contains timeout profile/reason, `runId/resetId`, scenario state, capped
  semantics, compact calls, and blocker error-log fragments;
- a custom timeout with blank reason is rejected synchronously.

- [ ] **Step 2: Write RED scheduler-aware boundary test**

Start `HealthyHome`, render/launch Home, arm the existing next-sync hold, invoke the
Refresh tasks semantics action, and call the new boundary wait. While the response is
held, assert cached Home content remains visible and the hold reaches `waiting`; release
it, await the exact completed POST for the current token, await persisted response
authority, and assert refresh finishes. This test must fail or time out with the old raw
instrumentation-thread polling behavior.

- [ ] **Step 3: Run wait tests RED**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
```

Expected: compilation fails because waits/diagnostics do not exist.

- [ ] **Step 4: Implement diagnostic snapshots**

Use:

```kotlin
sealed interface E2ETimeout {
    val millis: Long
    val description: String

    data object Ui : E2ETimeout {
        override val millis = 15_000L
        override val description = "UI/semantics"
    }

    data object Boundary : E2ETimeout {
        override val millis = 45_000L
        override val description = "backend/lifecycle/cold-device boundary"
    }

    data class Custom(
        override val millis: Long,
        val reason: String
    ) : E2ETimeout {
        init {
            require(millis > 0)
            require(reason.isNotBlank())
        }
        override val description = "custom: $reason"
    }
}

data class E2EWaitContext(
    val predicate: String,
    val timeout: E2ETimeout,
    val token: E2EResetToken?,
    val observedCount: Int?,
    val observedValues: List<String>,
    val scenarioStates: List<String>
)
```

Use this constructor:

```kotlin
class E2EDiagnostics(
    private val composeRule: ComposeContentTestRule,
    private val session: E2ETestSession,
    private val context: Context = ApplicationProvider.getApplicationContext()
)
```

`E2EDiagnostics.failure(waitContext, cause?)` returns `AssertionError`. Cap observed values,
semantics text, calls JSON, and error log independently; append `…truncated` when capped.
Never include backend tokens, environment dumps, or arbitrary files. Add a cause only
when it is the actual failed boundary, not a swallowed intermediate assertion.

- [ ] **Step 5: Implement Compose wait APIs**

Construct with the existing rule/session:

```kotlin
class E2EComposeWaits(
    private val composeRule: ComposeContentTestRule,
    private val session: E2ETestSession,
    private val diagnostics: E2EDiagnostics
) {
    fun exactText(text: String, timeout: E2ETimeout = E2ETimeout.Ui)
    fun containingText(text: String, timeout: E2ETimeout = E2ETimeout.Ui)
    fun containingTextAtLeast(text: String, minimum: Int = 1)
    fun containingTextCount(text: String, expected: Int)
    fun textAbsent(text: String)
    fun tag(tag: String)
    fun contentDescription(value: String)
    fun matchingText(regex: Regex): String
    fun lifecycle(
        scenario: ActivityScenario<out Activity>,
        state: Lifecycle.State,
        timeout: E2ETimeout = E2ETimeout.Boundary
    )
    fun completedCall(selector: E2ECallSelector): E2ERecordedCall
    fun completedCallCount(selector: E2ECallSelector, expected: Int): List<E2ERecordedCall>
    fun syncHoldState(expected: String)
    fun persistedAuthority(file: File, authority: E2EResponseAuthority)
    fun fileContains(file: File, vararg fragments: String)
}
```

For UI matchers, fetch node collections, throw immediately when a uniqueness matcher sees
more than one node, and assert display after the count predicate succeeds. Do not wrap
Compose assertions in `runCatching` inside a wait predicate.

For backend waits, submit `E2EBackendClient.await*` to a session-owned single-thread
executor, keep the `Future`/atomic result, and use `composeRule.waitUntil` to observe its
completion. Cancel the future if the Compose-side timeout fires. Re-throw its original
failure with correlated diagnostics. Never perform
HTTP or blocking sleep directly in the Compose predicate.

`persistedAuthority` parses JSON and compares both exact `serverTime` and
`snapshotGeneration`; a file timestamp/change is insufficient.

- [ ] **Step 6: Run focused TestKit instrumentation GREEN**

```bash
cd ..
ANDROID_TEST_CLASS=app.buzer.android.e2e.E2ETestKitInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: matcher, ambiguity, session, identity, scheduler, held-refresh, lifecycle, and
diagnostic cases pass.

- [ ] **Step 7: Commit shared waits and diagnostics**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EComposeWaits.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EDiagnostics.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestSession.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2ETestKitInstrumentationTest.kt
git commit -m "test: add scheduler-aware E2E waits"
```

## Task 6: Move the blocker/Home suite onto explicit session isolation

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt`

- [ ] **Step 1: Replace class-owned backend/scenario/reset state**

Use:

```kotlin
private val backend = E2EBackendClient()
private val session = E2ETestSession(composeRule = composeRule, backend = backend)
private val waits by lazy {
    E2EComposeWaits(composeRule, session, E2EDiagnostics(composeRule, session))
}

@After
fun tearDownE2EState() {
    session.close()
}
```

Delete `trackedScenarios`, `track`, `resetTestState`, `clearMorningReviewLocalState`, and
the class's direct cache/log/notification/alarm cleanup. Route all Activity launches
through `session.track(scenario)`, including `MainActivity` and home-origin Morning Review
launches that currently call `ActivityScenario.launch` directly.

- [ ] **Step 2: Make every initial state explicit at the test call site**

Replace each old reset call with one of:

```kotlin
session.start(E2ETestPreset.healthyHome())

session.start(
    E2ETestPreset.morningReview(
        fixture = E2EBackendFixture.Invalid,
        clock = E2ERuntimeClock.Fixed(instant, zone)
    )
)
```

Map historical string fixtures to typed enum values exactly. Tests that previously
called `resetTestState("invalid")`, `"clean"`, `"overdue"`, `"today"`,
`"blockingMix"`, `"pendingOnly"`, or `"unassigned"` use the corresponding typed
fixture and Due local Review state. Ordinary Home/blocker tests use `healthyHome()`.

Tests that change time while an Activity is open call:

```kotlin
session.setRuntimeClock(E2ERuntimeClock.Fixed(instant, newZone))
```

Tests that change only the server fixture mid-flow call:

```kotlin
session.replaceBackendFixture(E2EBackendFixture.Clean)
```

Never call `backend.reset` directly from this suite after migration; session token must
always advance with fixture replacement.

- [ ] **Step 3: Preserve the intentional two-reset cross-flow regression**

`deadlineBlocker_rescheduleUsesSharedUpdateUi` must still execute Home forced refresh,
close Home, start a fresh healthy session/reset, and then reschedule the deadline
blocker. Do not simplify away `runHomeSwipeRefreshPrecondition`: it is the single
approved Home→deadline cross-flow starvation regression.

Rewrite the precondition to use session tracking and current token. At return, assert
the Home scenario reached `DESTROYED` through shared lifecycle waits before starting the
next session.

- [ ] **Step 4: Preserve tests that intentionally replace fixtures mid-Activity**

For these tests, replace only backend fixture/token and preserve Android local state:

```text
morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSync
morningReview_ownerBlockingMixDisablesStartButUnassignedRefreshEnablesIt
acknowledgedDayNewInvalidSyncRestoresHomeBannerWithoutAutomatedLaunch
```

Use `session.replaceBackendFixture(fixture)`; do not call full `session.start(preset)` while the
Activity/draft is intentionally retained.

- [ ] **Step 5: Compile the session-migrated suite**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
```

Expected: compilation succeeds with no old reset/scenario fields.

- [ ] **Step 6: Run representative isolation cases**

From repository root, run separately:

```bash
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#homeScreenShowsTimelineAgendaAndFabStack' scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#deadlineBlocker_rescheduleUsesSharedUpdateUi' scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#automatedReviewSnoozePreservesDirtyEditDraft' scripts/e2e-real-backend.sh
```

Expected: all three pass with explicit tokens/presets and session-owned scenario cleanup.

- [ ] **Step 7: Commit explicit suite isolation**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
git commit -m "test: isolate blocker E2E sessions"
```

## Task 7: Replace blocker/Home backend polling with exact boundary waits

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt`

- [ ] **Step 1: Replace raw call-string assertions with typed calls**

For each successful mutation, await a completed exact POST using the current token:

```kotlin
val call = waits.completedCall(
    E2ECallSelector(
        token = session.token,
        method = "POST",
        path = "/tasks/task-start/start"
    )
)
assertTrue(call.responseStatusCode in 200..299)
assertNotNull(call.payload["actionId"])
assertNotNull(call.payload["deviceId"])
assertNotNull(call.payload["expectedOccurrenceKey"])
```

Use exact paths for start, done, update, checklist, and sync actions. Delete
`backend.assertRecorded`/JSON-fragment assertions. When the same path is expected twice,
use `completedCallCount` scoped to the current reset rather than regex-counting raw JSON.

- [ ] **Step 2: Preserve received-vs-completed distinctions**

Use `awaitReceivedCall` only while a response is deliberately held. Use completed calls
for every success and forced 500 response. The held-refresh test must demonstrate:

1. exact current-reset POST `/tasks/sync` was received;
2. sync hold is `waiting`;
3. cached Home content is still visible;
4. release returns `released`;
5. the same request completes successfully;
6. its exact authority is persisted;
7. loading state clears.

Automatic GET `/tasks/snapshot` must not satisfy any step.

- [ ] **Step 3: Replace response and persistence helpers**

Delete:

```text
waitForRecordedCall
waitForBackendResponseCompleted
waitForBackendResponseAuthority
waitForPersistedSnapshotAuthority
waitForRecordedCallCount
waitForSyncHoldState
```

Use `waits.completedCall`, `waits.completedCallCount`, `waits.syncHoldState`, and
`waits.persistedAuthority`. Keep correlation log markers only if they add a distinct
Android boundary; update their wording from fake backend to backend.

- [ ] **Step 4: Replace error-log polling**

Use:

```kotlin
waits.fileContains(
    blockerErrorLog(),
    "action=UPDATE_TASK taskId=task-invalid",
    "forced_failure"
)
```

Every forced-action test must also await the exact completed POST with status 500 before
asserting its dialog/log. This distinguishes server completion from UI presentation.

- [ ] **Step 5: Make payload assertions structural**

For update/reschedule/repair calls, inspect `call.payload` as `JsonObject`. Assert exact
action metadata keys and the expected root patch/child mutation shape. Do not use
`String.contains`, regex, or raw serialized fragments for JSON behavior.

- [ ] **Step 6: Compile and scan blocker backend polling**

```bash
cd client
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain -q
cd ..
rg -n 'SystemClock\.sleep|Thread\.sleep|backend\.calls\(\)|assertRecorded|waitForRecordedCall|waitForBackendResponse' client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
```

Expected: compilation succeeds and the scan returns no matches.

- [ ] **Step 7: Run backend-boundary regression cases**

```bash
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#deadlineBlocker_rescheduleUsesSharedUpdateUi' scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#homeForcedRefresh_retainsCachedContentWhileSyncResponseIsHeld' scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#morningReview_repairBackendFailureShowsErrorAndStaysOpen' scripts/e2e-real-backend.sh
```

Expected: all pass and failure messages, if forced during TDD, distinguish received,
completed, persisted, and UI/lifecycle boundaries.

- [ ] **Step 8: Commit exact boundary migration**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
git commit -m "test: correlate blocker E2E boundaries"
```

## Task 8: Replace blocker/Home UI waits and prove the full migrated class

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt`

- [ ] **Step 1: Replace ambiguous text helpers intentionally**

Delete all suite-private functions named `waitFor*`. Replace them by intent:

```text
exact dialog/title/button copy           -> waits.exactText(exactCopy)
known partial copy ("min block")         -> waits.containingText(partialCopy)
unknown/at-least-one partial copy         -> waits.containingTextAtLeast(partialCopy)
known multi-match partial copy            -> waits.containingTextCount(partialCopy, count)
absence                                  -> waits.textAbsent(exactCopy)
content description                      -> waits.contentDescription(exactDescription)
computed Repair/Reschedule action label  -> waits.matchingText(exactRegex)
Activity destroyed                       -> waits.lifecycle(scenario, DESTROYED)
error-log fragments                      -> waits.fileContains(file, fragments)
```

Use exact text for `Task update` and its longer message separately. Never use substring
to locate the title. Keep direct `onNodeWithText` assertions/clicks when the node is
already synchronized and exact.

- [ ] **Step 2: Replace raw substring lookups in test bodies**

Every remaining `substring = true` in `BlockerActivityBackendTest` must move behind an
explicit shared API. For assertions allowing zero/multiple nodes, use the exact shared
count helper. For computed labels use a full regex such as:

```kotlin
Regex("""Reschedule \d{2}:\d{2}–\d{2}:\d{2}""")
Regex("""Repair \d{2}:\d{2}–\d{2}:\d{2}""")
```

Do not weaken exact task identity tags added by Smart Home.

- [ ] **Step 3: Migrate lifecycle and scenario assertions**

Use shared lifecycle wait for expected destruction. For “must stay open,” inspect the
tracked scenario state immediately after the synchronized UI assertion and produce
diagnostics through `E2EDiagnostics` if it is destroyed. Keep system-Back behavior
assertions unchanged.

- [ ] **Step 4: Preserve all 44 behavior cases**

Compare the post-migration `@Test` list with the pre-migration list in git. All 44 tests
must remain, with only approved method renames from `CallsFakeBackend` to `CallsBackend`.
Do not combine or delete tests during this foundation.

```bash
rg -c '@Test' client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
```

Expected: count is exactly 44, matching the audited pre-foundation inventory recorded in
this plan and the starting handoff.

- [ ] **Step 5: Run zero-exemption source scans**

```bash
rg -n 'SystemClock\.sleep|Thread\.sleep|substring\s*=\s*true|private fun waitFor|private fun waitUntil|runCatching \{[[:space:]]*$' client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
```

Expected: no sleep, raw substring, or private wait helper. Review any `runCatching`
manually; none may wrap a Compose assertion or convert assertion failure to “not ready.”

- [ ] **Step 6: Run the entire migrated blocker/Home class**

```bash
ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityBackendTest scripts/e2e-real-backend.sh
```

Expected: all 44 tests pass through one owned server invocation.

- [ ] **Step 7: Repeat the historical starvation regression twice more**

Run this exact command, wait for its result, then run it again as a separate invocation:

```bash
ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityBackendTest#deadlineBlocker_rescheduleUsesSharedUpdateUi' scripts/e2e-real-backend.sh
```

Expected: both passes. Do not add retry-on-failure behavior; two clean independent passes
are evidence of isolation, not a mechanism for turning red green.

- [ ] **Step 8: Commit the completed class migration**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
git commit -m "test: migrate blocker E2E waits"
```

## Task 9: Enforce migration guardrails and wire push CI

**Files:**
- Create: `scripts/check-e2e-test-guardrails.mjs`
- Create: `scripts/check-e2e-test-guardrails.test.mjs`
- Create: `client/androidApp/src/androidTest/e2e-guardrail-baseline.json`
- Modify: `.github/workflows/ci.yml`
- Modify: `AGENTS.md`
- Verify TODOs in the five deferred test files

- [ ] **Step 1: Write RED scanner unit tests**

Use `node:test` and temporary Kotlin fixture trees. Cover:

1. a new `SystemClock.sleep` or `Thread.sleep` fails;
2. a new raw text matcher call whose `substring` argument is `true` fails for
   `onNodeWithText`, `onAllNodesWithText`, and `hasText`,
   including multiline/nested calls;
3. a new private `waitFor*` or `waitUntil*` declaration fails;
4. line movement and whitespace-only formatting preserve a normalized fingerprint;
5. adding a second identical violation increments count and fails;
6. deleting a legacy violation without deleting its baseline entry fails as stale debt;
7. changing a legacy expression fails until reviewed/baselined;
8. the one canonical `E2EBackendClient` polling sleep is accepted only through its exact
   fingerprint/count, not by whole-file allowlist;
9. comments and string literals containing prohibited text are ignored;
10. a clean migrated blocker fixture passes with no entries.

- [ ] **Step 2: Run scanner tests RED**

```bash
node --test scripts/check-e2e-test-guardrails.test.mjs
```

Expected: FAIL because the scanner does not exist.

- [ ] **Step 3: Implement the dependency-free scanner**

Export:

```js
export function scanAndroidTestSources(rootDirectory) {}
export function normalizeFindingSource(source) {}
export function fingerprintFinding(ruleId, normalizedSource) {}
export function compareFindingsToBaseline(findings, baseline) {}
export async function main(argv = process.argv.slice(2)) {}
```

Each finding is:

```js
{
  file: "relative/posix/path.kt",
  rule: "blocking-sleep" | "raw-substring-selector" | "private-wait-helper",
  fingerprint: "sha256 hex",
  count: 1,
  preview: "capped normalized source"
}
```

Implement a small balanced-call scanner for selector invocations. It must understand
nested parentheses, escaped quoted strings, triple-quoted strings, character literals,
line comments, and block comments well enough to ignore non-code occurrences. Do not use
one cross-file greedy regex. Normalize matched code by removing comments and collapsing
whitespace; exclude line numbers from identity. Group identical
`file/rule/fingerprint` findings and store their count.

Default paths are repository-relative:

```text
source root: client/androidApp/src/androidTest
baseline:    client/androidApp/src/androidTest/e2e-guardrail-baseline.json
```

Support `--root` and `--baseline` only for unit tests. On mismatch, print separate
`NEW`, `CHANGED/COUNT`, and `STALE` sections with compact previews, then exit `1`.
On match, print one line with finding count and exit `0`.

- [ ] **Step 4: Generate and review the initial exact baseline**

Run the scanner with an absent/empty baseline to print the actual findings, then create:

```json
{
  "version": 1,
  "entries": []
}
```

Populate `entries` only from reviewed actual findings in:

```text
AddTaskBackendTest.kt
EditTaskBackendTest.kt
BackendScenarioTest.kt
UnifiedTaskEditorBackendTest.kt
MorningReviewAlarmInstrumentationTest.kt
E2EBackendClient.kt (one approved polling sleep fingerprint)
```

Each baseline entry has this exact schema (the example is the canonical client's
approved sleep):

```json
{
  "file": "relative/posix/path.kt",
  "rule": "blocking-sleep",
  "fingerprint": "64-character sha256 hex",
  "count": 1,
  "preview": "capped normalized matched source",
  "disposition": "approved-connectivity-poll"
}
```

For legacy entries, `rule` is the finding's actual rule and `disposition` is the matching
`deferred-*` anchor. Sort entries by file, rule, and fingerprint so diffs are
deterministic. There must be zero entry for
`BlockerActivityBackendTest.kt`, TestKit wait/diagnostic/session files, or production
source.

- [ ] **Step 5: Verify every deferred TODO and anchor**

```bash
rg -n 'TODO\(e2e-suite-migration\)|2026-08-09-e2e-test-foundation.md#deferred-' client/androidApp/src/androidTest --glob '*.kt'
```

Expected: exactly five descriptive TODO blocks, one in each deferred file, pointing to
the correct anchor below. No TODO appears in `BlockerActivityBackendTest`.

- [ ] **Step 6: Run scanner tests and repository scan GREEN**

```bash
node --test scripts/check-e2e-test-guardrails.test.mjs
node scripts/check-e2e-test-guardrails.mjs
```

Expected: unit tests pass and repository findings exactly equal the reviewed baseline.

- [ ] **Step 7: Wire the fast guardrail into current push CI**

In the `client` job, add explicit Node 22 setup before the Gradle step:

```yaml
      - uses: actions/setup-node@v4
        with:
          node-version: '22'

      - name: Check Android E2E guardrails
        run: |
          node --test scripts/check-e2e-test-guardrails.test.mjs
          node scripts/check-e2e-test-guardrails.mjs
```

The workflow working directory for this step remains repository root. Do not add an
emulator job, retry, scheduled workflow, or new dependency install.

- [ ] **Step 8: Update repository agent guidance**

Add the two local guardrail commands and explain:

- new instrumentation sleeps/raw substring/private wait helpers are prohibited;
- the baseline is exact debt, not a file allowlist;
- removing debt requires removing its baseline entry;
- backend instrumentation classes use `*BackendTest`;
- `scripts/e2e-real-backend.sh` remains the sole E2E authority.

Keep existing full-unfiltered-suite requirements unchanged.

- [ ] **Step 9: Commit guardrails and CI**

```bash
git add scripts/check-e2e-test-guardrails.mjs scripts/check-e2e-test-guardrails.test.mjs client/androidApp/src/androidTest/e2e-guardrail-baseline.json .github/workflows/ci.yml AGENTS.md
git commit -m "test: enforce Android E2E guardrails"
```

## Deferred suite migration items

The following items are intentionally outside this foundation's implementation and are
not completion gates. Their checked-in source TODOs link here. Do not mark them complete
or remove the TODO/baseline entry unless that suite is actually migrated in a later plan.

### deferred-add-task-backend

Migrate `AddTaskBackendTest` to `E2ETestSession`, shared selectors/waits, scenario
tracking, and typed reset presets; then remove its exact baseline findings and TODO.

### deferred-edit-task-backend

Migrate `EditTaskBackendTest` to the shared session and waits; then remove its exact
baseline findings and TODO.

### deferred-backend-scenario

Migrate `BackendScenarioTest` to session reset, scheduler-aware backend waits, and shared
lifecycle handling; then remove its exact baseline findings and TODO.

### deferred-unified-editor-backend

Migrate `UnifiedTaskEditorBackendTest` to the shared session, scheduler-aware boundary
waits, and scenario tracking; then remove its exact baseline findings and TODO.

### deferred-morning-review-alarm

Migrate `MorningReviewAlarmInstrumentationTest` notification polling and reset behavior
to appropriate shared platform wait/session components; then remove its exact baseline
findings and TODO.

## Task 10: Complete full regression, connected-device proof, and handoff

**Files:**
- Create: `docs/handoffs/2026-08-09-e2e-test-foundation.md`
- Modify: `docs/superpowers/plans/2026-08-09-e2e-test-foundation.md`
- Verify all files in this plan

- [ ] **Step 1: Run complete fast/static verification**

From repository root:

```bash
node --test scripts/e2e-runner.test.mjs scripts/check-e2e-test-guardrails.test.mjs
node scripts/check-e2e-test-guardrails.mjs
```

From `backend/`:

```bash
npm test
npm run typecheck
npm run build
```

From `client/`:

```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin --console=plain -q
```

Expected: every command passes. Record exact backend/JVM counts.

- [ ] **Step 2: Run every renamed backend suite in focused groups**

Run separately from repository root:

```bash
ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskBackendTest,app.buzer.android.addtask.EditTaskBackendTest' scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityBackendTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.blockers.BackendScenarioTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.taskeditor.UnifiedTaskEditorBackendTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.e2e.E2ETestKitInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: every focused group passes. Each invocation reports a unique run ID/port and
leaves no owned backend process.

- [ ] **Step 3: Run the mandatory unfiltered real-backend suite**

```bash
env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh
```

Expected: all instrumentation tests pass on managed API 34. Record the exact total; it
must be at least the integrated readiness baseline plus the new TestKit tests. A scoped
pass is not a substitute.

- [ ] **Step 4: Verify connected-device routing**

With exactly one approved connected device, run the smallest non-live identity test:

```bash
scripts/e2e-connected-device.sh --class 'app.buzer.android.e2e.E2ETestKitInstrumentationTest#backendClient_validatesRunnerIdentity'
```

Expected: runner chooses a dynamic port, creates/removes its exact adb reverse mapping,
and the method passes against the local test backend. Then verify:

```bash
adb reverse --list
```

Expected: no mapping owned by the completed run remains. If no device is available,
record this check as SKIPPED with exact reason; managed-device verification remains
mandatory.

- [ ] **Step 5: Exercise failure artifacts without a product failure**

Run the Node runner test case that injects a simulated failing Gradle command and verify
its retained bundle contains metadata, backend/Gradle tails, calls JSON, and preserved
exit status. Do not intentionally fail the five-minute emulator suite merely to collect
artifacts.

```bash
node --test --test-name-pattern='retains compact artifacts and primary Gradle status' scripts/e2e-runner.test.mjs
```

- [ ] **Step 6: Audit names, zero-exemption migration, scope, and hygiene**

```bash
git diff --check
git status --short
rg -n 'FakeBackend|RealBackend|FAKE_BACKEND_BASE_URL' client/androidApp/src/androidTest client/androidApp/build.gradle.kts scripts --glob '!**/build/**'
rg -n 'SystemClock\.sleep|Thread\.sleep|substring\s*=\s*true|private fun waitFor|private fun waitUntil' client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityBackendTest.kt
rg -n 'TODO\(e2e-suite-migration\)' client/androidApp/src/androidTest --glob '*.kt'
rg -n '/__meta|resetId|runId|E2ETestSession|E2EBackendClient' backend/test scripts client/androidApp/src/androidTest --glob '!**/build/**'
git log --oneline --decorate -15
```

Expected:

- no whitespace errors or unrelated files;
- no obsolete active Kotlin/Gradle names;
- zero prohibited construct in migrated blocker/Home suite;
- exactly five linked deferred TODOs;
- one canonical Android backend client;
- no production backend route/control change;
- no fake backend, retry-until-green behavior, automatic screenshot, or emulator CI job.

- [ ] **Step 7: Write the required implementation handoff**

Create `docs/handoffs/2026-08-09-e2e-test-foundation.md` containing:

- plan path and exact checkbox status;
- starting/final commits, branch, and worktree;
- all created/modified/deleted/renamed files;
- run metadata/reset protocol and fixture schema version;
- runner ownership, dynamic port, device routing, and cleanup evidence;
- canonical client/session/preset/wait/diagnostic contracts;
- blocker/Home migration and preserved 44-test count;
- exact baseline findings by deferred file and their plan anchors;
- focused/full test counts and durations (informational only);
- connected-device result or exact skip;
- compact artifact example path/contents;
- known failures/skips, assumptions, and next recommended deferred suite;
- confirmation that readiness was a prerequisite and its controller was reused.

Use this exact format for every verification:

```text
Verification:
- Command: exact command that was run
- Result: one of PASS, FAIL, or SKIPPED
- Evidence: exact count, boundary, artifact path, or skip reason
```

- [ ] **Step 8: Mark completed checkboxes and commit final documentation**

Mark only steps backed by recorded evidence. Do not check connected-device verification
if it was skipped; record the skip in the handoff while leaving product completion based
on mandatory managed verification.

```bash
git add docs/handoffs/2026-08-09-e2e-test-foundation.md docs/superpowers/plans/2026-08-09-e2e-test-foundation.md
git commit -m "docs: hand off E2E test foundation"
```

- [ ] **Step 9: Request code review before integration**

Use `superpowers:requesting-code-review` or an equivalent fresh Claude Code review
context. Require review against the accepted design and this plan, with special attention
to process ownership, reset ambiguity, scheduler starvation, production/test boundary,
and baseline loopholes. Resolve findings and rerun the affected focused tests before
integration.

- [ ] **Step 10: Integrate, push, and confirm push CI**

After review, merge the implementation branch into current `master`, push, and run:

```bash
gh run list --workflow=ci.yml --limit 1
E2E_CI_RUN_ID="$(gh run list --workflow=ci.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "${E2E_CI_RUN_ID}" --exit-status
```

Expected: push-triggered client guardrail/JVM/build and backend checks pass. GitHub CI
does not run managed instrumentation; the local unfiltered E2E result remains mandatory
release evidence.

## Completion criteria

The foundation is complete only when all of the following are true:

- foreground readiness was integrated first and its instrumentation controller is reused;
- the default runner never probes/reuses an existing server;
- port `0` yields an owned dynamic endpoint, while occupied explicit ports fail safely;
- host and Android validate exact run metadata;
- reset IDs are monotonic per run and every recorded call carries run/reset/request
  identity;
- POST assertions cannot be satisfied by GETs, prior resets, received-only calls, or
  unrelated paths;
- one typed `E2EBackendClient` replaces both legacy clients;
- active Kotlin tests/classes/properties use `Backend`, not Fake/Real backend suffixes;
- every blocker/Home test starts from an explicit typed session preset;
- the blocker/Home class retains all 44 behavior cases and its historical cross-flow
  regression passes repeatedly;
- the migrated class contains no blocking sleep, raw substring selector, duplicate
  private wait helper, or guardrail baseline entry;
- Compose-blocking backend work executes off the Compose predicate while the test rule
  advances the scheduler;
- timeout evidence identifies semantics/call counts, lifecycle, run/reset, and relevant
  logs without default screenshots;
- the scanner rejects new, changed-count, and stale legacy findings and runs in push CI;
- exactly five deferred suites carry descriptive TODOs linked to the plan items above;
- runner/scanner/backend/client tests, every focused renamed suite, and the full
  unfiltered managed-device suite pass;
- connected-device routing is proven or explicitly skipped without weakening managed
  verification;
- compact failure artifacts preserve the primary exit status;
- no product behavior, parallel fake backend, emulator CI lane, sharding, or runtime
  performance gate is mixed into the branch.
