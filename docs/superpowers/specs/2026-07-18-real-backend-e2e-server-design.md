# Real-Backend E2E Server Design (Phase 1)

Date: 2026-07-18 (revised same day after a full codebase cross-check review)
Status: approved design, pending implementation plan

## Context And Goal

`tools/fake-backend/src/server.ts` is a ~240-line (dense) hand-reimplementation of the
real backend's entire API surface (snapshot generation, checklist mutation semantics,
replay/fingerprint caching, reconciliation outcomes) built against in-memory fixtures,
not the real backend's actual service classes. Every time real backend logic changes,
the fake only matches it if someone remembers to update this parallel implementation by
hand — which is the direct cause of the discrepancies between fake and real backend
behavior that prompted this work.

An audit (this session, 2026-07-18, re-verified during the revision review) found the
real backend already has the abstraction boundary needed to fix this properly:

- `backend/src/todoist/todoistAdapter.ts` — a narrow `TodoistAdapter`/
  `TodoistMutationGateway` interface (12 methods total) covering every Todoist
  interaction.
- `backend/src/accountability/accountabilitySender.ts` — an equally narrow interface for
  WhatsApp escalation, with `backend/src/accountability/dryRunSender.ts` as its no-op
  implementation.
- `backend/src/server.ts`'s `buildServer(options)` already accepts these as injected
  options — proven by `backend/test/http/routes.test.ts`, which boots the real Fastify
  app wired to `FakeTodoistAdapter` + `DryRunSender` today, via in-process `app.inject()`.

The goal: replace `tools/fake-backend` with the real backend, listening on a real port,
wired to a simulated Todoist instead of `app.inject()`-only — so Android E2E tests
exercise real business logic (`TaskActionService`, `TaskActionCoordinator`,
`TodoistChecklistService`, `TaskSnapshotService`, the reconciliation protocol) instead of
a parallel reimplementation. Nothing left to keep in sync, by construction.

## Decisions Made During Brainstorming

- **Phased rollout.** This spec covers Phase 1 only: build the new server, adapter, and
  fault injection, and prove them against the real existing Android E2E suite. Phase 2
  (migrating `scripts/`/CI to it) and Phase 3 (deleting `tools/fake-backend`) are
  separate, later brainstorming/spec cycles — not urgent, not excluded.
- **Isolated worktree.** Real backend code, following the same pattern used for every
  real-code (non-docs) task this session.
- **Approach (revised): a dedicated, self-contained E2E adapter in the test tree**, not
  an extension of `FakeTodoistAdapter`. The original plan ("extend `FakeTodoistAdapter`
  with additive presets") was overturned by the review, which found that the happy path
  itself requires a stateful Todoist-side world (see next section). Once a world is
  mandatory, the presets belong in the same component, and a dedicated class means
  `FakeTodoistAdapter` and its 81 existing call sites (`routes.test.ts` ×22,
  `taskActionService.test.ts` ×41, `todoistSyncService.test.ts` ×14, `rateLimit.test.ts`
  ×3, `todoistChecklistService.test.ts` ×1) are untouched by construction — no additive-
  safety engineering, no blast radius to verify.
- **Debug-endpoint parity is required.** The Android tests' `FakeBackendClient`
  (`client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/
  FakeBackendClient.kt`) depends on `GET /__calls` (via `assertRecorded`, used
  throughout both test classes), `POST /__fail-actions` (~6 tests),
  `POST /__fail-snapshot`, `POST /__fail-projects`, and `POST /__reset`. The original
  plan's `/__scenario` + `/__reset` surface alone cannot run the suite unchanged.
- **Fixture parity is required for Phase 1's proof to mean anything.** The existing 25
  Android `@Test` methods (8 in `AddTaskFakeBackendTest`, 17 in
  `BlockerActivityFakeBackendTest`) reference `tools/fake-backend/src/fixtures.ts`'s
  **15** named fixture tasks by ID/title, plus the two fixture projects served by
  `/projects`. The new server must seed equivalent data, not skip this and call the
  proof invalid by construction.
- **Basic preset coverage.** Two new instrumentation tests (one per preset) are in
  scope, since no existing test exercises `/__scenario`.
- **Drift protection is a hard requirement** (user decision): the new simulation must
  not silently drift from backend behavior over time the way `tools/fake-backend` did.
  See "Drift Protection" below.
- **Considered and rejected:** extending `FakeTodoistAdapter` (original approach — the
  inheritance buys nothing once every method routes through a world); an opt-in stateful
  mode inside `FakeTodoistAdapter` (couples e2e machinery to a class whose contract is
  dumb per-test queues); a fake Todoist HTTP server behind the real
  `HttpTodoistAdapter` (maximum fidelity, noted as a possible future phase, but
  substantially more work and the adapter interface is exactly the seam the codebase
  built for this); skipping effect verification in test mode (bypasses the
  reconciliation protocol this effort exists to exercise, and would touch
  `buildServer()` production behavior).

## Why A Stateful World Is Mandatory (Review Finding)

The review established that a stateless fake cannot serve the happy path, only the
pending path:

- Every Todoist-mutating action (`done`, `extend`, `reschedule`, `edit_task`, create,
  all checklist mutations) dispatches a command and then verifies its effect via
  `isEffectPresent` checks against **sync-updated mirror fields** — e.g. reschedule
  checks `tasks.getById(taskId)?.dueDatetime === input.dueDatetime`
  (`taskActionService.ts:545`).
- With an empty `sync()`, the effect is never confirmed and the coordinator returns
  202 `reconciliation_pending`. The backend's own suite asserts exactly this:
  `routes.test.ts:297-303` expects `extend` → 202 with a bare `FakeTodoistAdapter`,
  while `start`/`postpone-start` (local effects, no Todoist command) complete with 200.
- The unit tests pass today only because each test hand-queues `syncResults` per
  action — choreography a black-box E2E server cannot perform.

So the Todoist-side world the original spec deferred is a Phase 1 requirement: the
old fake's leniency was returning `completed` without ever proving effects; the real
backend proves effects through sync, and the simulation must give it something to prove
against.

## The E2E Todoist Adapter (New Core Component)

- **Location:** `backend/test/fakes/e2eTodoistAdapter.ts`. Implements `TodoistAdapter`
  directly; no inheritance from and no changes to `FakeTodoistAdapter`.
- **The world:** an in-memory map of `TodoistTaskMirror`s (keyed by `todoistTaskId`)
  plus fixture projects. Mutations apply real Todoist-side semantics:
  - `itemAdd` → new mirror (root, or child via `parentTodoistTaskId`), returns the
    assigned ID.
  - `itemUpdate` → applies title/description/duration/due/assignment fields.
  - `itemMove` → changes `projectId`.
  - `itemClose` → sets `completedAt`.
  - `itemUncomplete` → clears it.
  - `itemDelete` → sets `deletedAt`.
  - `itemUpdateDateComplete` → advances a recurring task's due date per its recurrence
    (daily/weekly/monthly/yearly increments, as the old fake's `nextDue` did).
  - The legacy `createTask`/`completeTask`/`rescheduleTask`/`updateTask` methods map to
    the equivalent world operations.
- **`sync()` returns the full world state every time** with a fresh token.
  `TodoistSyncService` upserts idempotently, so no delta/sync-token bookkeeping is
  needed. This is what closes the happy-path loop: command mutates the world, the
  coordinator's post-execute `syncOnce()` ingests it, `isEffectPresent` confirms, the
  action completes.
- **The world contains zero backend logic.** No snapshot shapes, no checklist
  semantics, no replay caching, no reconciliation outcomes — all of that runs in the
  real services. The world models only what Todoist itself would do, behind the
  12-method interface.

## The Fault-Injection Model

Two axes, grounded in `backend/src/services/taskActionCoordinator.ts`'s actual catch
logic (lines 188-219), plus a third HTTP-level axis for old-fake parity:

- **Command-level failure axis.** `taskActionCoordinator.ts:200-203` treats any error
  thrown after a command was dispatched identically — straight to
  `reconciliation_pending`, regardless of the error's type or message. A generic thrown
  `Error` is therefore faithful.
  **Preset `after_command_sent_timeout`:** every mutation method throws a generic
  `Error` and does **not** update the world (the command never reached Todoist).
- **Sync-visibility axis.** `taskActionCoordinator.ts:210-219`: even after a command
  succeeds and a subsequent sync succeeds, if local state doesn't show the effect, the
  action goes pending.
  **Preset `sync_lag_effect_unknown`:** mutations apply to the world normally, but
  `sync()` short-circuits to an empty result, suppressing the echo — "the call
  succeeded but the next sync doesn't reflect it yet."
- **HTTP-level axis (parity, not adapter-level).** The old fake's `__fail-actions` /
  `__fail-snapshot` / `__fail-projects` toggles return HTTP 500 before business logic
  runs — a different client-visible behavior (500) than adapter faults (202). They are
  reproduced as server-level hooks (below) because ~8 existing tests depend on them.

Scenario state lives on the adapter: `scenario: "none" | "after_command_sent_timeout" |
"sync_lag_effect_unknown"` with `setScenario(name)`, default `"none"`. The remaining
backlog presets (notably `sync_lag_effect_present` — apply the mutation but throw
anyway, i.e. "command landed, response lost") become cheap with a world but stay out of
Phase 1 scope.

## The New Test-Mode Server

- **Location:** `backend/test/e2eServer.ts`, exporting a `buildE2EServer()` used by both
  the CLI entry and the conformance suite; invoked via a new `backend/package.json`
  script, e.g. `"e2e-server": "tsx test/e2eServer.ts"`. Not shipped in the production
  build (`tsconfig.build.json` includes only `src/**`); `tsx` is already a backend
  devDependency. No new package, no new `node_modules`.
- Wires `buildServer()` (unmodified) with the E2E adapter and `new DryRunSender()`, a
  fresh `:memory:` SQLite database, migrations run at startup.
- **Configuration parity with the harness:** `currentUserId: "user-me"` (matching
  `BUZER_ASSIGNEE_ME_ID`'s default in `scripts/e2e-fake-backend.sh`, so
  `isAssignedToUser` derives true for seeded tasks), `androidApiToken: "test-token"`,
  port `18433` by default (`PORT` overridable), and a raised `rateLimit` option (e.g.
  `max: 100_000`) — `buildServer`'s production default of 60 req/min would produce
  flaky 429s under snapshot polling.
- **Seeding goes through the production ingestion path.** Seed the *world* with the 15
  fixture task mirrors and 2 fixture projects (`project-test` — shared, canAssignTasks;
  `project-personal`), then run one `TodoistSyncService.syncOnce()` at boot. The real
  sync sets `mirror_contract_version = 2` and initializes occurrence keys itself
  (`todoistSyncService.ts:139-145`, `applyOccurrenceDecision`), so no manual
  mirror-ready or occurrence poking is needed. Backend-owned commitment state is then
  applied via the real `TaskRepository`: `updateCommitmentState` for the started
  fixtures (`task-deadline`, `task-reschedule`, `task-deadline-fail`,
  `task-reschedule-fail`), `markOccurrenceReconciliationPending` for
  `task-reconciliation-pending`.
- **Fixture states are derived, not declared.** The old fixtures declare final snapshot
  states; the real backend derives them. Seeded mirrors must be shaped so the real
  services derive the same outcomes: null due/duration → `task-invalid`'s invalid
  state; a scheduled child mirror → `task-unsupported-hierarchy`'s violation; a due
  string outside the supported presets → `task-custom-recurrence`'s custom kind;
  preset-mappable due strings → the recurring fixtures. Due times are computed relative
  to boot/reset time (the old fake's `legacyIsoAt` semantics — blocker selection
  depends on it). Exact mirror shapes are implementation-plan work.
- **Debug routes,** registered on the built app outside `buildServer()` (keeping
  production code untouched, and unauthenticated — `FakeBackendClient` sends no auth
  header, matching the old fake's `/__` bypass):
  - `POST /__scenario` — calls the adapter's `setScenario`.
  - `POST /__reset` — fresh database + migrations + reseed, scenario back to `"none"`,
    recorded calls cleared. **Must carry `snapshotGeneration` forward monotonically**
    across resets: test clients retain their last authoritative snapshot, and a
    generation restarting at 1 would be rejected as stale (the old fake documents this
    at `tools/fake-backend/src/server.ts:85-99`).
  - `GET /__calls` — records method, path, and the same action-metadata subset the old
    fake records (`actionId`, `deviceId`, `expectedOccurrenceKey`,
    `expectedTaskRevision`, `expectedChildRevision` — `server.ts:36-40`). Recording
    exactly this subset, not full bodies, preserves `assertRecordedWithout` semantics.
  - `POST /__fail-actions`, `POST /__fail-snapshot`, `POST /__fail-projects` —
    HTTP-level 500 toggles implemented as hooks matching the old fake's response codes
    (`forced_failure`, `forced_snapshot_failure`, `forced_project_failure`).
- `app.listen({ host: "0.0.0.0", port })`.

## Drift Protection

Requirement: the simulation must not drift from real behavior over time, the way
`tools/fake-backend` did. Three layers, from structural to executable:

1. **Structural.** The world models only Todoist-side semantics behind the narrow
   `TodoistAdapter` interface. The drift class that killed the old fake — a parallel
   reimplementation of backend business logic — cannot recur, because the backend
   logic in the E2E path *is* the backend.
2. **Compile-time.** `implements TodoistAdapter` means any interface change (new
   method, changed signature) fails `cd backend && npm test` / `npm run typecheck`
   immediately.
3. **Conformance suite.** `backend/test/e2eServer.test.ts` (vitest, part of the
   default `npm test` run) boots `buildE2EServer()` in-process, seeds, and drives every
   mutation endpoint's happy path via `app.inject`, asserting `outcome: "completed"`
   and that the snapshot reflects the change; plus one pending-outcome assertion per
   preset. If any action's effect-confirmation logic evolves so the world no longer
   satisfies it, the backend suite fails at unit-test speed — drift is caught long
   before an emulator run.

## The New Script And Validation

- `scripts/e2e-real-backend.sh` parallels `scripts/e2e-fake-backend.sh` structurally:
  boots `cd backend && npm run e2e-server` instead of the fake, waits for readiness,
  then runs the same Gradle instrumentation invocation (managed emulator or connected
  device, matching the existing script's branching logic) against this server's port.
  `scripts/e2e-fake-backend.sh` itself is not modified.
- `FakeBackendClient` gains an additive `setScenario(name)` method posting to
  `/__scenario`.
- **Two new instrumentation tests**, one per preset: activate the scenario, drive a
  Todoist-mutating action, assert the client-observable reconciliation-pending outcome,
  reset the scenario in teardown. No existing test covers the pending UI path; the
  exact UI assertion is determined during planning from actual app behavior.
- **Validation:** run the existing 25 `@Test` methods, unchanged, plus the 2 new preset
  tests, via the new script. Triage any failure of the existing 25 as: a real bug in
  the new server, a fixture-seeding gap, or a test that only passed because the old
  fake was more lenient than the real backend — the last case is itself evidence
  supporting why this effort matters, not a blocker to treat as a regression.

## Non-Goals (Phase 1)

- No changes to `FakeTodoistAdapter` or any existing backend test — the dedicated
  adapter makes the 81 call sites structurally unaffected.
- No changes to `tools/fake-backend/`, `scripts/e2e-fake-backend.sh`,
  `scripts/e2e-connected-device.sh`, or any CI workflow — all explicitly Phase 2+.
  `tools/fake-backend` is not deleted in Phase 1.
- No fake Todoist HTTP server behind `HttpTodoistAdapter` (the considered
  maximum-fidelity option; a possible later phase).
- No presets beyond the two named ones; no `GET /__state`, no `POST /__clock` (backlog
  P1-3 extras).
- No changes to `buildServer()`'s production behavior or signature.

## Acceptance Criteria

- `backend/test/fakes/e2eTodoistAdapter.ts` exists, implements `TodoistAdapter` with
  the stateful world and the two presets; `git diff` shows `FakeTodoistAdapter` and all
  existing backend tests untouched.
- `backend/test/e2eServer.ts` exists with an exported `buildE2EServer()`, boots via the
  new npm script, serves on a real port with the configuration parity described above,
  and seeds the 15 fixture tasks plus 2 fixture projects.
- All six debug endpoints behave with old-fake parity, including monotonic
  `snapshotGeneration` across `/__reset`.
- The conformance suite passes as part of `cd backend && npm test` (which passes in
  full).
- `scripts/e2e-real-backend.sh` exists and successfully drives the existing 25 Android
  `@Test` methods unchanged plus the 2 new preset tests, with any failures triaged and
  understood (fixed, or documented as a genuine discrepancy the old fake was masking).
