# Real-Backend E2E Server Design (Phase 1)

Date: 2026-07-18
Status: approved design, pending implementation plan

## Context And Goal

`tools/fake-backend/src/server.ts` is a ~400-line hand-reimplementation of the real
backend's entire API surface (snapshot generation, checklist mutation semantics, replay/
fingerprint caching, reconciliation outcomes) built against in-memory fixtures, not the
real backend's actual service classes. Every time real backend logic changes, the fake
only matches it if someone remembers to update this parallel implementation by hand —
which is the direct cause of the discrepancies between fake and real backend behavior
that prompted this work.

An audit (this session, 2026-07-18) found the real backend already has the abstraction
boundary needed to fix this properly:

- `backend/src/todoist/todoistAdapter.ts` — a narrow `TodoistAdapter`/
  `TodoistMutationGateway` interface covering every Todoist interaction.
- `backend/src/accountability/accountabilitySender.ts` — an equally narrow interface for
  WhatsApp escalation.
- Both already have fake/dry-run implementations (`backend/test/fakes/
  fakeTodoistAdapter.ts`, `backend/src/accountability/dryRunSender.ts`) used in the
  backend's own test suite today.
- `backend/src/server.ts`'s `buildServer(options)` already accepts these as injected
  options — proven by `backend/test/http/routes.test.ts`, which boots the real Fastify
  app wired to `FakeTodoistAdapter` + `DryRunSender` today, via in-process `app.inject()`.

The goal: replace `tools/fake-backend` with the real backend, listening on a real port,
wired to mocked adapters instead of `app.inject()`-only — so Android E2E tests exercise
real business logic (`TaskActionService`, `TaskActionCoordinator`,
`TodoistChecklistService`, `TaskSnapshotService`, the reconciliation protocol) instead of
a parallel reimplementation. Nothing left to keep in sync, by construction.

## Decisions Made During Brainstorming

- **Phased rollout.** This spec covers Phase 1 only: build the new server and fault
  injection, and prove it against the real existing Android E2E suite. Phase 2
  (migrating `scripts/`/CI to it) and Phase 3 (deleting `tools/fake-backend`) are
  separate, later brainstorming/spec cycles — not urgent, not excluded.
- **Isolated worktree.** Real backend code, following the same pattern used for every
  real-code (non-docs) task this session.
- **Approach: extend `FakeTodoistAdapter` directly**, rather than a new sibling class.
  Accepted trade-off: wider blast radius (81 existing `new FakeTodoistAdapter()` call
  sites across 5 test files — `routes.test.ts` ×22, `taskActionService.test.ts` ×41,
  `todoistSyncService.test.ts` ×14, `rateLimit.test.ts` ×3,
  `todoistChecklistService.test.ts` ×1), mitigated by making all new fault-injection
  surface additive with safe defaults, verified by running the full existing backend
  suite (`cd backend && npm test`) after the change, not merely assumed.
- **Small proof-of-concept preset subset**, not all 7 backlog-named presets. Full
  stateful Todoist-side task tracking (needed for `stale_task_revision`,
  `duplicate_draft_titles`, etc.) is deferred; these two presets don't need it.
- **Fixture parity is required for Phase 1's proof to mean anything.** The existing 25
  Android `@Test` methods reference `tools/fake-backend/src/fixtures.ts`'s 14 named
  fixture tasks by ID/title. The new server must seed equivalent data via the real
  `TaskRepository`, not skip this and call the proof invalid by construction.

## The Fault-Injection Model

Two axes, grounded in reading `backend/src/services/taskActionCoordinator.ts`'s actual
catch logic (lines 188-219), not assumed:

- **Command-level failure axis.** `taskActionCoordinator.ts:200-203` treats any error
  thrown after a command was dispatched identically — straight to `reconciliation_
  pending`, regardless of the error's type or message. A generic thrown `Error` is
  therefore faithful; no need to fabricate specific HTTP status codes or timeout shapes.
- **Sync-visibility axis.** `taskActionCoordinator.ts:210-219` shows that even after a
  command succeeds *and* a subsequent sync succeeds, if the resulting local state
  doesn't show the effect as present, the action still goes pending. This is a
  genuinely different thing to simulate than a failed call — it's "the call succeeded
  but the next sync doesn't reflect it yet."

**Preset 1: `after_command_sent_timeout`** (command-failure axis) — every mutation
method on `FakeTodoistAdapter` checks the active scenario via one shared private helper
and throws a generic `Error` when this preset is active.

**Preset 2: `sync_lag_effect_unknown`** (sync-visibility axis) — `sync()` short-circuits
to an empty/unchanged result (`{ syncToken: ..., tasks: [], projects: [] }`) whenever
this preset is active, regardless of what mutations were attempted. This correctly
exercises the "stays pending because effect isn't confirmed" path without requiring the
adapter to track a real mutable Todoist-side task mirror.

**Implementation shape:**
- New field: `scenario: FakeTodoistScenario = "none"` (type
  `"none" | "after_command_sent_timeout" | "sync_lag_effect_unknown"`, extensible when
  later presets are added).
- New method: `setScenario(name: FakeTodoistScenario): void`.
- Both presets are opt-in and default to `"none"`. The existing `syncResults`/
  `createResults` queue-based mechanism and all 81 existing call sites are untouched
  when no scenario is set.

## The New Test-Mode Server

- **Location:** `backend/test/e2eServer.ts` — lives in the test tree (not shipped in the
  production build via `tsconfig.build.json`), invoked via a new `backend/package.json`
  script, e.g. `"e2e-server": "tsx test/e2eServer.ts"`. No new package, no new
  `node_modules` — everything it needs is already a `backend` dependency.
- Wires `buildServer()` (unmodified) with `new FakeTodoistAdapter()` and
  `new DryRunSender()`, a fresh `:memory:` SQLite database, migrations run at startup —
  the same pattern already proven in `backend/test/http/routes.test.ts`.
- **Seeds the same 14 fixture tasks** currently defined in
  `tools/fake-backend/src/fixtures.ts` (`task-start`, `task-deadline`,
  `task-reschedule`, `task-start-fail`, `task-deadline-fail`, `task-reschedule-fail`,
  `task-invalid`, `task-floating-time`, `task-one-time-empty`,
  `task-one-time-partial`, `task-recurring-all-checked`,
  `task-recurring-extend-disabled`, `task-custom-recurrence`,
  `task-unsupported-hierarchy`, `task-reconciliation-pending`), inserted via the real
  `TaskRepository`'s actual creation methods — necessary so the existing instrumentation
  tests, which reference these fixtures by ID/title, have something to find. This
  exercises the real repository's read path, which is more faithful than the old fake's
  bespoke JS transform, not less.
- Debug routes registered on the built app (not inside `buildServer()` itself, keeping
  production code untouched): `POST /__scenario` (calls the adapter's `setScenario`),
  `POST /__reset` (reseeds the database, resets scenario to `"none"`).
- `app.listen({ host: "0.0.0.0", port })`.

## The New Script And Validation

- `scripts/e2e-real-backend.sh` parallels `scripts/e2e-fake-backend.sh` structurally:
  boots `cd backend && npm run e2e-server` instead of the fake, waits for readiness,
  then runs the same Gradle instrumentation invocation (managed emulator or connected
  device, matching the existing script's branching logic) against this server's port.
  `scripts/e2e-fake-backend.sh` itself is not modified.
- **Validation:** run the existing 25 `@Test` methods against the new server,
  unchanged, via this new script. Triage any failure as: a real bug in the new server,
  a fixture-seeding gap, or a test that only passed because the old fake was more
  lenient than the real backend — the last case is itself evidence supporting why this
  effort matters, not a blocker to treat as a regression.

## Non-Goals (Phase 1)

- No changes to `tools/fake-backend/`, `scripts/e2e-fake-backend.sh`,
  `scripts/e2e-connected-device.sh`, or any CI workflow — all explicitly Phase 2+.
  `tools/fake-backend` is not deleted in Phase 1.
- No full stateful Todoist-side task-mirror simulation — only the two named presets
  above, chosen because they don't require it.
- No new sibling adapter class — extending `FakeTodoistAdapter` in place was the
  explicit approach choice, accepting its wider (but mitigated) blast radius.
- No changes to `buildServer()`'s production behavior or signature.

## Acceptance Criteria

- `FakeTodoistAdapter` gains `scenario`/`setScenario` with the two presets described
  above; all 81 existing call sites continue to pass with no changes required beyond
  what the full backend suite run reveals and fixes.
- `backend/test/e2eServer.ts` exists, boots via a new `backend` npm script, serves on a
  real port wired to the (extended) fake adapter and `DryRunSender`, and seeds the same
  14 fixture tasks `tools/fake-backend/src/fixtures.ts` defines today.
- `scripts/e2e-real-backend.sh` exists and successfully drives the existing 25 Android
  `@Test` methods against the new server, with any failures triaged and understood
  (fixed, or documented as a genuine discrepancy the old fake was masking).
- `cd backend && npm test` passes in full after the `FakeTodoistAdapter` change.
