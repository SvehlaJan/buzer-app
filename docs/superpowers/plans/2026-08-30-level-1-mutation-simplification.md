# Level 1 mutation simplification implementation plan

> **Execution requirement:** Use `superpowers:executing-plans` for implementation and
> `superpowers:test-driven-development` for every behavior change. Follow the repository
> worktree manifest and handoff protocol. Do not execute this plan in the primary
> checkout when a dedicated implementation worktree can be used.

**Goal:** Keep Buzer's custom Android task UI while deleting durable action
reconciliation, replacing it with one bounded process-local Todoist FIFO, visible
session-only uncertainty, one-minute polling, forced snapshot refresh, and searchable
component-owned logs.

**Architecture:** Android sends a fresh correlation-only request UUID and disables its
current action surface for at most 15 seconds. Backend top-level Todoist work enters one
non-durable FIFO with a 13-second deadline. A mutation validates the SQLite mirror,
sends commands sequentially with one same-UUID retry where eligible, then performs one
raw post-command sync while retaining its queue slot. Success means command
acknowledgement plus successful sync; expected uncertainty is a typed warning, never a
pending state or inferred success.

**Technology:** Node.js/TypeScript, Fastify, SQLite/better-sqlite3, Todoist Sync API,
`@sentry/node` 10.72.0, Kotlin Multiplatform, Ktor, Compose Multiplatform, Sentry Android
8.53.0, JUnit/Vitest, Android instrumentation.

**Product truth:**

- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`
- `docs/decisions/2026-08-30-level-1-mutation-simplification.md`

**Scope guardrails:**

- Do not implement Todoist webhooks, FCM, a durable queue, a compatibility shim, a
  retry button, effect comparison, command-count UX, or warning persistence.
- Do not close Sentry issues until the production gates in Task 16 pass.
- Never use a browser to bypass Sentry MCP/API authentication. Ask the owner to
  reauthenticate.
- Keep unrelated dirty files and worktrees untouched.

---

## Task 0: Prepare an isolated execution workspace

**Files:**

- Read: `AGENTS.md`
- Read: `docs/notes/agent-workspaces.md`
- Read: `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`
- Read: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Read: recent files in `docs/handoffs/`
- Create: the worktree manifest required by `docs/notes/agent-workspaces.md`

**Step 1: Inspect ownership before changing anything**

Run:

```bash
git worktree list
git status --short
git log -5 --oneline
```

Expected: identify the base commit, all existing worktrees, and the two untracked
planning handoffs. Do not move, delete, stage, or overwrite them.

**Step 2: Create a dedicated worktree**

Use branch `feature/level-1-mutation-simplification` and follow the exact directory and
manifest convention in `docs/notes/agent-workspaces.md`. Export `ANDROID_HOME` and
`ANDROID_SDK_ROOT` when testing from the worktree.

**Step 3: Re-run the clean-state checks in the worktree**

Expected: no unknown changes. If another agent owns an overlapping file, record it in
the manifest and stop before editing that file.

**Step 4: Fetch current SDK/API documentation before implementation**

Run the required Context7 flow for `@sentry/node`, Sentry Android Java/Kotlin logs,
Ktor request timeouts, and the Todoist Sync API command/retry contract. Record resolved
library IDs and relevant conclusions in the task handoff; never include secrets.

---

## Task 1: Retire the reconciliation protocol and align repository guidance

**Files:**

- Modify: `AGENTS.md`
- Modify: `docs/notes/action-reconciliation-protocol.md`
- Delete: `.codex/skills/buzer-action-reconciliation/SKILL.md`
- Delete: `.claude/skills/buzer-action-reconciliation/SKILL.md`
- Verify historical only: `docs/superpowers/specs/2026-07-16-atomic-mutation-reconciliation-design.md`
- Verify historical only: `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md`

**Step 1: Add a repository-level regression test by search**

Run:

```bash
rg -n "action journal|action-journal|reconciliation_status|five minutes|5 minutes|Todoist polling" AGENTS.md .codex/skills .claude/skills docs/notes/action-reconciliation-protocol.md
```

Expected before change: current guidance still mandates the architecture being removed.

**Step 2: Update current guidance**

- Point `AGENTS.md` to the new decision/design as the current mutation architecture.
- Change the MVP boundary to one-minute Todoist polling.
- Keep Todoist webhooks explicitly out of scope and link the backlog note.
- Mark `docs/notes/action-reconciliation-protocol.md` historical with a prominent link
  to the new decision. Do not rewrite its incident history.
- Delete both reconciliation skill copies so future agents cannot accidentally treat
  them as mandatory executable instructions.

**Step 3: Verify no active mandate remains**

Repeat the search. Expected: mentions survive only in explicitly historical documents,
the new supersession statement, and implementation/deletion notes.

**Step 4: Commit**

```bash
git add AGENTS.md docs/notes/action-reconciliation-protocol.md .codex/skills/buzer-action-reconciliation/SKILL.md .claude/skills/buzer-action-reconciliation/SKILL.md
git commit -m "docs: retire action reconciliation protocol"
```

---

## Task 2: Introduce typed Todoist failures and bounded same-UUID command retry

**Files:**

- Create: `backend/src/todoist/todoistErrors.ts`
- Create: `backend/src/services/todoistCommandExecutor.ts`
- Create: `backend/test/services/todoistCommandExecutor.test.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`

**Step 1: Write failing adapter classification tests**

Cover:

- transport error;
- `AbortSignal` timeout;
- 429 with parsed `Retry-After` seconds/date;
- Todoist 5xx;
- explicit non-`ok` `sync_status` with sanitized code/tag only;
- other Todoist 4xx;
- malformed successful JSON as an internal parse failure.

The typed shape should be small:

```ts
type TodoistFailureKind =
  | "transport"
  | "timeout"
  | "rate_limited"
  | "server"
  | "command_rejected"
  | "client"
  | "parse";

class TodoistOperationError extends Error {
  constructor(
    readonly kind: TodoistFailureKind,
    readonly retryAfterMs?: number,
    readonly safeAttributes: Record<string, string | number> = {},
  ) { super(kind); }
}
```

Do not retain response bodies or command payloads in the error.

Run:

```bash
cd backend
npm test -- httpTodoistAdapter.test.ts
```

Expected: FAIL because classification and abort support do not exist.

**Step 2: Implement the minimum adapter changes**

Pass an `AbortSignal` into `fetch`, parse `Retry-After`, and translate failures at the
adapter boundary. Preserve documented Todoist error code/tag/http code only as
sanitized attributes.

**Step 3: Write failing command-executor tests**

Use a fake clock and injected `sleep`/UUID factory. Prove:

- success sends once;
- transport, timeout, 429, and 5xx send at most twice;
- both sends use the exact same UUID;
- explicit rejection and 4xx never retry;
- `Retry-After` is honored only when it and the second attempt fit the deadline;
- no sleep or second send occurs after deadline exhaustion;
- a terminal explicit HTTP/`sync_status` response maps to conservative rejection while
  a transport/timeout without a definitive response maps to delivery unknown;
- the executor never claims whether earlier batch commands applied.

Sketch:

```ts
const result = await executor.execute({
  command,
  commandUuid: "fixed-command-uuid",
  deadline,
  signal,
});
expect(adapter.calls.map(it => it.uuid)).toEqual([
  "fixed-command-uuid",
  "fixed-command-uuid",
]);
```

Run the new test; expected FAIL.

**Step 4: Implement one-retry execution**

Centralize `MAX_COMMAND_ATTEMPTS = 2` and compute every timeout from remaining budget.
Do not retry read syncs.

**Step 5: Verify and commit**

```bash
npm test -- httpTodoistAdapter.test.ts todoistCommandExecutor.test.ts
npm run typecheck
git add backend/src/todoist backend/src/services/todoistCommandExecutor.ts backend/test/todoist backend/test/services/todoistCommandExecutor.test.ts
git commit -m "feat: classify and retry Todoist commands"
```

---

## Task 3: Add the process-wide FIFO and deadline admission

**Files:**

- Create: `backend/src/services/todoistOperationQueue.ts`
- Create: `backend/test/services/todoistOperationQueue.test.ts`

**Step 1: Write failing queue tests**

Prove:

- strict FIFO start order;
- only one callback active;
- queue wait counts against the deadline;
- an expired waiting entry never executes and returns typed `backend_busy`;
- abort before execution removes the entry and sends nothing;
- callback failure releases the next entry;
- the queue has no persistence/recovery API;
- operation metadata is provided to logs but payloads are not.

Target API:

```ts
await queue.run({
  operation: "complete_task",
  requestId,
  deadlineMs,
  minimumExecutionBudgetMs,
  signal,
}, async ({ signal, remainingMs, queueWaitMs }) => {
  // Top-level exclusive work only.
});
```

Run the test; expected FAIL.

**Step 2: Implement the smallest in-memory queue**

Use a plain array plus one drain loop. Do not use SQLite, journal records, command
fingerprints, replay, root keys, or per-operation locks.

**Step 3: Prove queue re-entry is not part of the API**

The queue must be injected only into top-level server/poller runners. Document in code
that `TodoistSyncService.syncOnce` is raw and must not enqueue itself.

**Step 4: Verify and commit**

```bash
npm test -- todoistOperationQueue.test.ts
npm run typecheck
git add backend/src/services/todoistOperationQueue.ts backend/test/services/todoistOperationQueue.test.ts
git commit -m "feat: serialize Todoist operations in memory"
```

---

## Task 4: Simplify sync, recurrence rotation, and one-minute polling

**Files:**

- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Modify: `backend/src/services/todoistPoller.ts`
- Modify: `backend/test/services/todoistPoller.test.ts`
- Modify: `backend/test/services/todoistPollerDiagnostics.test.ts`
- Modify: `backend/src/server.ts`

**Step 1: Replace old sync-serialization tests with raw-sync tests**

Delete expectations for `syncTail`, `manualSyncInFlight`, hidden action journals, and
system reset commands. Add failing tests that:

- `syncOnce` performs exactly one fetch/apply cycle and never acquires a queue;
- external recurrence advancement rotates the occurrence immediately;
- checked child state observed from Todoist carries forward without a repair command;
- parsing/state-application failures remain distinct internal failures;
- the returned summary contains aggregate changed counts and bounded identifier lists.

Expected: FAIL while hidden repair/journal behavior remains.

**Step 2: Remove hidden recurrence repair**

Delete `ActionJournalRepository` use and `resetCheckedChildren` system actions from the
sync path. Keep Buzer-triggered `reset_subtasks:true` for Task 6.

**Step 3: Write failing poll cadence/queue/log tests**

Prove:

- default interval is 60,000 ms;
- every poll uses the global FIFO and raw `syncOnce` inside it;
- a busy/expired poll is skipped without overlapping work;
- every operational poll failure emits `todoist_poll_failed` warning and no Issue;
- first later success emits `todoist_poll_recovered` once;
- a changed sync emits one `todoist_poll_changed` info record with counts/bounded IDs;
- a successful empty poll emits no searchable log;
- parse/state/internal failures emit a backend Issue.

**Step 4: Implement poll integration**

Inject the singleton queue from `server.ts`. Remove the local running guard if the FIFO
makes it redundant; do not retain two concurrency mechanisms.

**Step 5: Verify and commit**

```bash
npm test -- todoistSyncService.test.ts todoistPoller.test.ts todoistPollerDiagnostics.test.ts
npm run typecheck
git add backend/src/services/todoistSyncService.ts backend/src/services/todoistPoller.ts backend/src/server.ts backend/test/services
git commit -m "refactor: simplify Todoist sync and polling"
```

---

## Task 5: Build a one-shot mutation runner

**Files:**

- Create: `backend/src/services/taskMutationRunner.ts`
- Create: `backend/test/services/taskMutationRunner.test.ts`
- Use: `backend/src/services/todoistOperationQueue.ts`
- Use: `backend/src/services/todoistCommandExecutor.ts`
- Use: `backend/src/services/todoistSyncService.ts`

**Step 1: Write the complete outcome matrix as failing tests**

Test with a fake queue/clock/adapter/sync:

| Scenario | Commands | Sync | Expected |
| --- | ---: | ---: | --- |
| queue budget exhausted | 0 | 0 | `backend_busy`, nothing sent |
| acknowledged batch | N | 1 success | success snapshot |
| acknowledged no-op | N | 1 success | success; no effect comparison |
| explicit rejection | stop at first | 1 best effort | `todoist_command_rejected` |
| first command rejection | 1 | 1 best effort | still “potentially partial” |
| later command rejection | stop | 1 best effort | no ack count, no rollback |
| transport after send | stop | 1 best effort | `todoist_delivery_unknown` |
| retry succeeds | max 2 same UUID | 1 | normal success |
| ack then sync fails | N | 1 failed | `todoist_accepted_snapshot_stale` |
| failure sync succeeds | stop | 1 | typed warning includes snapshot |
| failure sync fails | stop | 1 | typed warning without snapshot |

Also prove the FIFO slot remains held through the post-sync and that no sync enqueues
again.

Expected: FAIL because the runner does not exist.

**Step 2: Define typed runner outcomes**

```ts
type MutationRunResult =
  | { kind: "success"; snapshot: TaskSnapshot }
  | { kind: "rejected"; snapshot?: TaskSnapshot }
  | { kind: "delivery_unknown"; snapshot?: TaskSnapshot }
  | { kind: "accepted_stale" }
  | { kind: "busy" };
```

Do not add `pending`, `actionId`, acknowledged counts, effect predicates, journal
handles, replay, or compensation.

**Step 3: Implement lifecycle logs**

Emit one start and one terminal record using request/task/occurrence IDs and counts.
Expected operational outcomes are warnings, not captured exceptions. Internal
validation invariants thrown by the runner are backend Issues.

**Step 4: Verify and commit**

```bash
npm test -- taskMutationRunner.test.ts todoistCommandExecutor.test.ts todoistOperationQueue.test.ts
npm run typecheck
git add backend/src/services/taskMutationRunner.ts backend/test/services/taskMutationRunner.test.ts
git commit -m "feat: run Todoist mutations without reconciliation"
```

---

## Task 6: Decouple task and checklist actions from the journal

**Files:**

- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/src/services/todoistChecklistService.ts`
- Modify: `backend/test/services/todoistChecklistService.test.ts`
- Modify: `backend/src/services/escalationService.ts`
- Modify: `backend/test/services/escalationService.test.ts`
- Modify: `backend/test/accountability/whatsappDiagnostics.test.ts`
- Modify: `backend/test/services/escalationServiceDiagnostics.test.ts`

**Step 1: Rewrite task-action tests before production code**

Remove coordinator/journal/replay/effect-predicate fixtures. Add tests that:

- Create/Edit/Extend/Done build a fully validated command list and delegate once to the
  mutation runner;
- recurring Done still includes `reset_subtasks:true`;
- one-time Done does not add recurrence-specific behavior;
- local Start and Postpone update SQLite directly without queue, Todoist sync, UUID, or
  journal;
- stale occurrence/task revisions fail before send and return the authoritative
  snapshot;
- Postpone persists successfully even if WhatsApp escalation later fails;
- no action API accepts `actionId` or mutation `deviceId`.

Expected: FAIL until the coordinator boundary is removed.

**Step 2: Rewrite checklist tests**

Cover deterministic local validation before send, sequential batch stop, no effect
verification, and the fact that `clientMutationId` is request-local only. Remove
coordinator and reconciliation assertions.

**Step 3: Implement thin command builders**

Keep domain validation in the service, pass command arrays to `TaskMutationRunner`, and
delete the `executeRootAction` abstraction. Avoid replacing the old coordinator with a
new generic framework.

**Step 4: Verify and commit**

```bash
npm test -- taskActionService.test.ts todoistChecklistService.test.ts escalationServiceDiagnostics.test.ts whatsappDiagnostics.test.ts
npm run typecheck
git add backend/src/services/taskActionService.ts backend/src/services/todoistChecklistService.ts backend/src/accountability backend/test/services backend/test/accountability
git commit -m "refactor: reduce task actions to one-shot commands"
```

---

## Task 7: Replace the HTTP mutation/sync contract and add forced refresh

**Files:**

- Modify: `backend/src/http/routes.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/test/e2eServer.ts`
- Modify: `backend/test/e2eServer.test.ts`

**Step 1: Write failing route contract tests**

Prove:

- `GET /tasks/snapshot` is cached and performs no Todoist call;
- `GET /tasks/snapshot?forceRefresh=true` enters the queue and performs one raw sync;
- invalid `forceRefresh` values get 400;
- failed force refresh returns typed non-2xx `forced_refresh_failed`;
- `POST /tasks/sync` returns 404;
- mutation schemas reject the removed `actionId` and `deviceId` under strict Zod mode;
- success returns snapshot only (plus existing action-specific data such as
  `snoozedUntil` if still required);
- busy/rejected/unknown/accepted-stale map to stable codes and never serialize command
  counts;
- an available best-effort snapshot is included in warning responses;
- every response echoes the accepted/generated `X-Request-ID`.

Expected: FAIL against current routes.

**Step 2: Implement typed operational HTTP errors**

Create one small mapper from runner results to status/body. Do not leak raw Todoist
errors. Clients must branch on `code`.

**Step 3: Exclude expected typed failures from Fastify Issue capture**

The `onError` hook should capture unexpected backend 5xx/internal defects but not
`backend_busy`, `todoist_command_rejected`, `todoist_delivery_unknown`,
`todoist_accepted_snapshot_stale`, or `forced_refresh_failed`.

**Step 4: Update the real-backend fixture**

Add deterministic fixture controls for rejection, transport/timeout uncertainty,
accepted-stale, queue busy-before-send, and failed/successful forced refresh. Preserve
the no-live-Todoist guarantee for destructive tests.

**Step 5: Verify and commit**

```bash
npm test -- routes.test.ts e2eServer.test.ts
npm run typecheck
npm run build
git add backend/src/http backend/src/server.ts backend/test/http backend/test/e2eServer.ts backend/test/e2eServer.test.ts
git commit -m "feat: expose bounded mutation and refresh outcomes"
```

---

## Task 8: Upgrade backend Sentry structured logs and enforce ownership

**Files:**

- Modify: `backend/package.json`
- Modify: `backend/package-lock.json`
- Modify: `backend/src/diagnostics/backendObservability.ts`
- Modify: `backend/test/backendObservability.test.ts`
- Modify: `backend/src/server.ts`
- Modify: diagnostics callers changed in Tasks 4-7

**Step 1: Write failing observability tests**

Prove:

- initialization sets `enableLogs:true`;
- `beforeSendLog` removes forbidden keys/content and retains approved scalar
  identifiers/counts;
- `info(marker, attributes)` and `warn(marker, attributes)` reach `Sentry.logger`;
- expected operational errors create logs but not Issues;
- parse/state/invariant/internal failures create Issues exactly once in the backend;
- no title, description, token, header, URL, request/response body, or payload survives;
- bounded identifier lists truncate and include a count/truncated flag.

Expected: FAIL on SDK 8.53.0/current interface.

**Step 2: Upgrade exactly to the reviewed version**

```bash
cd backend
npm install @sentry/node@10.72.0
```

Review the lockfile. Do not perform unrelated dependency upgrades.

**Step 3: Extend the abstraction**

```ts
interface BackendObservability {
  info(marker: string, attributes?: SafeLogAttributes): void;
  warn(marker: string, attributes?: SafeLogAttributes): void;
  captureException(error: unknown, context: InternalErrorContext): void;
}
```

Keep no-op and recording test implementations. Use stable markers, not interpolated
messages.

**Step 4: Verify and commit**

```bash
npm test -- backendObservability.test.ts todoistPollerDiagnostics.test.ts routes.test.ts taskMutationRunner.test.ts
npm run typecheck
npm run build
git add backend/package.json backend/package-lock.json backend/src/diagnostics backend/src/server.ts backend/test/backendObservability.test.ts
git commit -m "feat: add searchable backend operation logs"
```

---

## Task 9: Remove journal persistence and migrate SQLite schema v7

**Files:**

- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/test/repositories/taskRepository.test.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`
- Delete: `backend/src/repositories/actionJournalRepository.ts`
- Delete: `backend/test/repositories/actionJournalRepository.test.ts`

**Step 1: Write a failing v6-to-v7 migration test**

Seed an actual v6 database containing:

- commitment rows with every retained column populated;
- journal/command rows;
- unrelated escalation/device/task rows;
- relevant indexes/triggers.

After migration assert:

```sql
SELECT version FROM schema_migrations WHERE version = 7;
```

and prove journal tables and `reconciliation_status` are absent while all unrelated
data and constraints remain.

Also test a fresh database from `schema.sql`. Expected: FAIL before v7 exists.

**Step 2: Implement forward-only v7**

Drop commands before their parent journal. Remove/rebuild the reconciliation column in
the safest SQLite-compatible way used by the supported production version. Remove
unconditional action-journal guard installation so startup cannot query deleted tables.

**Step 3: Remove repository/snapshot projections**

Delete `ReconciliationStatus`, selects, inserts, updates, and snapshot mapping. Do not
touch occurrence revision or escalation claims.

**Step 4: Delete the journal repository and tests**

Run `rg` afterward and remove imports, fixtures, and constructors rather than leaving a
compatibility shell.

**Step 5: Verify and commit**

```bash
npm test -- migrations.test.ts taskRepository.test.ts taskSnapshotService.test.ts
npm run typecheck
git add backend/src/db backend/src/repositories backend/src/services/taskSnapshotService.ts backend/test/db backend/test/repositories backend/test/services/taskSnapshotService.test.ts
git commit -m "refactor: remove action journal persistence"
```

---

## Task 10: Delete the coordinator and root mutex, then prove backend convergence

**Files:**

- Delete: `backend/src/services/taskActionCoordinator.ts`
- Delete: `backend/test/services/taskActionCoordinator.test.ts`
- Delete: `backend/src/services/rootTaskMutex.ts`
- Delete: `backend/test/services/rootTaskMutex.test.ts`
- Modify: `backend/src/server.ts`
- Modify: remaining backend tests/fixtures importing them

**Step 1: Search the complete dependency surface**

```bash
rg -n "ActionJournal|TaskActionCoordinator|RootTaskMutex|reconciliationStatus|reconciliation_status|MutationOutcome|actionId|effectPredicate" backend/src backend/test
```

Classify every match: delete active architecture uses, retain only unrelated device
action IDs if semantically different, and avoid blind replacement.

**Step 2: Delete the units and simplify server construction**

Construct exactly one `TodoistOperationQueue`, one command executor, and one mutation
runner. Inject the same queue into poll/forced-refresh/mutation top-level entry points.

**Step 3: Run the full backend suite**

```bash
cd backend
npm test
npm run typecheck
npm run build
```

Expected: PASS. Record baseline versus final production/test line counts for the
removed architecture using a reproducible `wc -l` file list. Report gross deletion and
net change separately; do not present estimates as measured savings.

**Step 4: Commit**

```bash
git add backend
git commit -m "refactor: delete durable action reconciliation"
```

---

## Task 11: Simplify the shared Kotlin contract and add structured request logs

**Files:**

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotDiagnostics.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientTimeoutTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/AppLogTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotDiagnosticsTest.kt`

**Step 1: Write failing JSON/contract tests**

Remove expected `actionId`, mutation `deviceId`, `MutationOutcome`, and
`reconciliationStatus`. Prove:

- mutation requests omit removed fields;
- mutation success decodes snapshot-only responses;
- typed non-2xx errors decode stable code and optional snapshot;
- `getSnapshot()` uses cached GET;
- `getSnapshot(forceRefresh = true)` adds exactly `forceRefresh=true`;
- no client method calls `POST /tasks/sync`.

**Step 2: Write failing timeout tests**

Keep fast cached reads at the existing 5,000 ms default. Prove mutations and forced
refresh install a per-request 15,000 ms timeout. Do not globally lengthen every API
call.

**Step 3: Write failing diagnostics tests**

Add structured scalar attributes to `DiagnosticLogRecord`/`AppLog`. Prove:

- every request has a fresh UUID and logs start/terminal markers with the same ID;
- the ID is sent as `X-Request-ID` but is never persisted or used for idempotency;
- operational typed/network failures are warning records;
- response parse failures are marked internal and eligible for Android Issue capture;
- redaction removes forbidden keys and values;
- Ktor header logging is gone.

Suggested API:

```kotlin
AppLog.info(
    marker = "api_request_succeeded",
    attributes = mapOf(
        "request_id" to requestId,
        "operation" to operation,
        "status_code" to status.value.toString(),
    ),
)
```

**Step 4: Implement and verify**

```bash
cd client
./gradlew :shared:testAndroidHostTest
```

Expected: PASS after the minimum model/client/log changes.

**Step 5: Commit**

```bash
git add client/shared
git commit -m "refactor: simplify the Android API contract"
```

---

## Task 12: Simplify Android enforcement outcomes and Sentry ownership

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryAndroidObservabilityTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt`

**Step 1: Replace the coordinator-result test matrix**

Delete tests for `Pending`, `RetryAfter` closures, `ChildActionInFlight`, durable
reconciliation, and auto retry. Add failing tests for:

```kotlin
sealed interface ActionResult {
    data class Success(val snapshot: TaskSnapshot) : ActionResult
    data class Warning(
        val kind: WarningKind,
        val snapshot: TaskSnapshot?,
        val message: String,
    ) : ActionResult
    data class Conflict(val snapshot: TaskSnapshot, val message: String) : ActionResult
    data class InternalFailure(val snapshot: TaskSnapshot?, val message: String) : ActionResult
}
```

Map rejected/unknown/accepted-stale/busy distinctly. Apply an optional warning snapshot
before exposing the warning. Busy copy must say nothing was sent; rejection and unknown
must tell the user to check Todoist; accepted-stale must not say success.

**Step 2: Add error-ownership tests**

Prove:

- network, timeout, backend expected typed errors, and backend 5xx create Android
  warning logs but no `captureException`;
- backend internal error creates a correlated Android warning only because backend owns
  the Issue;
- Android JSON parse, snapshot state apply, alarm state mutation, and local invariants
  create one Android Issue;
- raw response bodies and task content never enter logs or Issues.

**Step 3: Implement structured Sentry Android logs**

Forward sanitized attributes to both breadcrumbs and the current Sentry Java logger
API (`SentryLogParameters`/attributes per the Context7-verified SDK signature). Preserve
crashes/ANRs/error replay behavior.

**Step 4: Verify and commit**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
git add androidApp/src/main/kotlin/app/buzer/android/enforcement androidApp/src/main/kotlin/app/buzer/android/observability androidApp/src/test
git commit -m "refactor: surface mutation uncertainty on Android"
```

---

## Task 13: Add session warnings, screen locks, forced Refresh, and Open Todoist

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/TodoistLauncher.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/TodoistLauncherTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/ActionWarning.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/App.kt` if navigation wiring requires it
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFailurePresentationTest.kt`

**Step 1: Write failing warning-state tests**

Prove:

- warnings live only in screen/session state;
- original action completion, including a warning, immediately sets
  `interactionInFlight=false`;
- warning stays while ordinary actions remain enabled;
- Refresh sets the whole screen in flight and calls force-refresh GET;
- failed Refresh retains the warning and current snapshot;
- successful Refresh applies snapshot, reconciles alarms, and clears the warning;
- no app restart/snapshot-store restoration API exists for the warning;
- no Retry action or automatic timer exists.

**Step 2: Write Todoist launcher tests**

Prefer the installed `com.todoist` package and fall back to an HTTPS Todoist intent.
Handle absence/failure with a visible local warning and Android-owned Issue only for an
unexpected launcher defect.

**Step 3: Update Home behavior**

Remove `HomeDoneActionHandler` retry closure and pending result branches. Disable all
Home actions during Done or forced Refresh, then restore them immediately on any
terminal result.

**Step 4: Add accessible warning UI**

Use one shared warning presentation with concise message, `Refresh`, and `Open
Todoist`. It must not say “successful” for rejected, unknown, or accepted-stale
outcomes. Make semantics/test tags stable for instrumentation.

**Step 5: Verify and commit**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
git add androidApp/src/main/kotlin/app/buzer/android/ui androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt androidApp/src/main/kotlin/app/buzer/android/App.kt androidApp/src/test
git commit -m "feat: keep users in control of uncertain actions"
```

---

## Task 14: Remove pending/retry UX from blockers, Morning Review, and editors

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewSessionState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlow.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSessionStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFailurePresentationTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelectorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`

**Step 1: Make existing behavior fail explicitly in tests**

For each surface, remove or invert tests that expect:

- pending confirmation cards;
- `SyncPending` Morning Review rows;
- automatic `LaunchedEffect` retries;
- stored action IDs;
- action-specific retry closures/dialogs;
- pending-root alarm/agenda exclusion;
- success navigation on accepted-stale/rejected/unknown.

Add tests for whole-surface disablement, immediate re-enable, session warning actions,
and editor draft preservation.

**Step 2: Simplify blockers**

While a blocker action is in flight, disable every blocker control. On success,
reconcile/dismiss as today. On warning, keep the blocker visible, restore normal actions,
and show Refresh/Open Todoist. Ensure overlay/activity duplicate surfaces consume the
same authoritative snapshot without adding a new durable dedupe protocol.

**Step 3: Simplify Morning Review**

Delete `SyncPending` state and repair card. Remove pending roots from special alarm and
agenda selection rules. Preserve genuine stale conflicts and ordinary validation.

**Step 4: Simplify Add/Edit/task editor**

Remove retained action IDs and auto-resubmit. Create uncertainty must explicitly warn
that retry could duplicate the task. Keep drafts on every warning/conflict/internal
failure. A successful forced Refresh clears the warning but must not erase the draft.

**Step 5: Search for architectural residue**

```bash
rg -n "Pending|SyncPending|pendingConfirmation|retryCurrentAction|retryCompletion|actionId|reconciliationStatus|MutationOutcome" client/shared/src client/androidApp/src
```

Review each surviving match; do not blindly remove unrelated UI loading states.

**Step 6: Verify and commit**

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
git add androidApp shared
git commit -m "refactor: remove pending action UX"
```

---

## Task 15: Migrate and expand real-backend instrumentation

**Files:**

- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/RealBackendScenarioTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/HomeScreenTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateHostTest.kt`
- Modify: `backend/test/e2eServer.ts`

**Step 1: Update fixture contracts first**

Remove action journal/action ID/pending payloads. Add deterministic scenario controls
without real Todoist or WhatsApp destructive calls. Keep old `FakeBackendTest` class
names if renaming would add noise; document that `scripts/e2e-real-backend.sh` remains
the authority.

**Step 2: Add scoped end-to-end cases**

At minimum assert:

- success for Create, Edit, checklist, Extend, one-time Done, recurring Done with child
  reset, Start, and Postpone;
- rejection/unknown/accepted-stale never navigate or label success;
- every warning shows Refresh/Open Todoist and normal action controls are immediately
  enabled;
- busy-before-send says nothing was sent;
- included failure snapshot is applied without closing the editor/blocker;
- failed forced refresh preserves snapshot/warning;
- successful forced refresh clears warning;
- no pending repair card appears after relaunch;
- recurring completion outside Buzer may retain checked children;
- mutation/refresh locks the complete current surface.

**Step 3: Run scoped instrumentation while iterating**

Example:

```bash
ANDROID_TEST_CLASS=app.buzer.android.blockers.RealBackendScenarioTest scripts/e2e-real-backend.sh
ANDROID_TEST_CLASS=app.buzer.android.taskeditor.UnifiedTaskEditorRealBackendTest scripts/e2e-real-backend.sh
```

Expected: PASS. Use exact class filters for each changed surface and record them in the
handoff.

**Step 4: Run the full unfiltered authority**

```bash
scripts/e2e-real-backend.sh
```

Expected: PASS. JVM tests are not a substitute.

**Step 5: Commit**

```bash
git add backend/test/e2eServer.ts client/androidApp/src/androidTest
git commit -m "test: cover simplified mutation uncertainty"
```

---

## Task 16: Final verification, rollout tooling, production canary, and Sentry closure

**Files:**

- Modify: `scripts/deploy-vps.sh` only if needed to enforce/record the backup gate
- Verify: `scripts/backup-sqlite.sh`
- Create/update: `docs/handoffs/2026-08-30-level-1-mutation-simplification-implementation.md`
- Update: this plan's checkboxes/status during execution

**Step 1: Run clean local verification**

```bash
cd backend
npm test
npm run typecheck
npm run build
```

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

```bash
scripts/e2e-real-backend.sh
git diff --check
git status --short
```

Expected: every command PASS; status contains only intentional changes. Record exact
test counts and any benign warnings.

**Step 2: Review simplification rather than only correctness**

Search for the removed architecture across the repository:

```bash
rg -n "task_action_journal|task_action_commands|reconciliation_status|TaskActionCoordinator|RootTaskMutex|SyncPending|POST /tasks/sync|/tasks/sync" .
```

Expected: active code has no matches. Historical docs and migration-removal tests are
allowed. Confirm the replacement did not recreate journal concepts under new names.

Measure and report:

- production lines deleted/added/net;
- test lines deleted/added/net;
- files deleted/created;
- user-visible behavior sacrifices actually implemented.

**Step 3: Request code review before rollout**

Use `superpowers:requesting-code-review`. Address findings with
`superpowers:receiving-code-review`, rerun affected tests, and keep commits coherent.

**Step 4: Prepare the production rollback boundary**

Before the v7 binary starts:

- run `scripts/backup-sqlite.sh` using the established production procedure;
- record the backup path, timestamp, size, and successful integrity/read check;
- record the prior backend image/commit and Android build;
- do not deploy if the backup cannot be verified;
- rollback means restoring the backup and prior binaries together, not running a
  down-migration.

Do not place secrets, database contents, or auth material in the handoff.

**Step 5: Deploy the coordinated release**

Deploy backend first, confirm startup/migration v7, cached snapshot GET, forced-refresh
GET, one-minute polls, and backend Sentry routing. Install the matching Android build
immediately afterward. Treat the mismatch interval as maintenance; there is no
compatibility shim.

**Step 6: Run manual production canaries**

Without deliberately duplicating uncertain writes, verify:

- cached Refresh and explicit forced Refresh;
- Create, Edit, checklist, Extend, Start, Postpone, one-time Done, recurring Done;
- recurring Done sends child reset;
- every screen locks while active and restores controls on return;
- warning copy/actions and Open Todoist fallback;
- a previously stuck pending task is actionable;
- warning state disappears after app restart;
- logs correlate Android/backend by `request_id` and contain no task content.

Exercise operational warning families through safe deterministic/test hooks where
production Todoist mutation risk would be unacceptable. Separately verify an internal
backend test error creates only a backend Issue and an Android-local parse/state test
error creates only an Android Issue.

**Step 7: Resolve Sentry issues individually through MCP**

Only after the matching evidence exists:

- `BUZER-ANDROID-4`: recurring Done works, journal/pending are absent, stuck task is
  actionable.
- `BUZER-ANDROID-5`: `/tasks/sync` is absent; `forceRefresh=true` and 15-second UI
  behavior pass.
- `BUZER-ANDROID-7`: pre-command sync is absent; retry/typed rejection behavior and a
  production mutation pass.
- `BUZER-ANDROID-8`: operational failures produce searchable warnings without Android
  handled Issues; internal Android failures still report.

Use Sentry MCP. If authentication fails or expires, stop and ask the owner to
reauthenticate. Never open/use the browser as an API substitute. Record issue status,
release, environment, and evidence in the handoff. Do not resolve unrelated issues.

**Step 8: Check push-triggered CI**

After push:

```bash
gh run list --workflow=ci.yml --limit 1
```

Wait for the run and document the conclusion. Do not claim completion on red CI.

**Step 9: Write the required handoff**

Include:

- plan/task checkbox status;
- files and commits;
- exact commands and PASS/FAIL/SKIPPED evidence;
- manual canary evidence;
- database backup/rollback reference;
- measured code reduction;
- Sentry issue statuses;
- assumptions, known failures, and next step.

Use the mandated format for every verification item:

```text
Verification:
- Command: <exact command>
- Result: PASS/FAIL/SKIPPED
- Evidence: <important output or reason>
```

**Step 10: Finish the branch**

Use `superpowers:verification-before-completion`, then
`superpowers:finishing-a-development-branch` to present integration choices. Do not
silently merge, push, or delete the worktree.

---

## Execution checkpoints

- **Checkpoint A — backend foundation:** Tasks 0-5. Review deadline/FIFO/error semantics
  before deleting old architecture.
- **Checkpoint B — backend cutover:** Tasks 6-10. Full backend suite must pass and schema
  backup/rollback procedure must be demonstrable.
- **Checkpoint C — client cutover:** Tasks 11-14. Shared and Android JVM/build gates must
  pass; no pending state remains.
- **Checkpoint D — end-to-end:** Task 15. Full real-backend instrumentation must pass.
- **Checkpoint E — production:** Task 16. Deployment/canary precedes Sentry closure.

## Definition of done

- Custom Android action UI remains available and lower-friction.
- Normal controls reappear immediately after any terminal response.
- Uncertain/rejected/accepted-stale writes are never shown as successful.
- Session warnings offer Refresh/Open Todoist and are not persisted.
- One global non-durable FIFO serializes Todoist work within a 13-second backend budget.
- Commands retry at most once with the same UUID only for approved transient failures.
- Explicit rejection is always described as potentially partial, without ack counts.
- Success requires acknowledgement plus one successful post-sync, not effect comparison.
- Cached snapshot GET, forced-refresh GET, and one-minute polling replace sync POST.
- Durable journal/reconciliation/root mutex/pending UI and automatic external recurrence
  repair are deleted.
- Structured info/warning logs are searchable in both components and respect privacy.
- Expected operational failures do not create Issues; component-owned internal defects
  do.
- Backend, shared, Android, build, full instrumentation, production canary, and CI gates
  pass.
- Only then are the four named Sentry issues resolved through MCP.
