# Agent Delivery Foundations Backlog

**Status:** Proposed engineering backlog

**Context:** This backlog converts the retrospective after recurring tasks,
checklists, snapshot generation, and atomic reconciliation into independently
implementable work. It is process/tooling work; it must not alter MVP product
behavior unless a task explicitly says so.

**Product safety invariants to preserve:**

- A Todoist command that was marked sent is never treated as a terminal failure
  until authoritative sync proves its effect absent; ambiguous outcomes are
  `reconciliation_pending`.
- Action replay identities contain immutable client intent and preconditions,
  never values derived from mutable server state.
- Android applies the authoritative snapshot returned by a mutation directly;
  it does not infer local success.
- Snapshot-local IDs, Todoist IDs, and journal command IDs remain distinct.
- Checklist children never use root scheduling/assignment mutation paths.

## Priority order and dependency graph

```text
P0-1 isolated worktrees ─┐
P0-2 agent delivery skill ├─> P1-1 scoped verification command
P0-3 reconciliation skill ┘

P1-2 executable contract ─> P1-3 fake-backend fault simulator
P1-4 replay test harness ─> P2-1 action-module split

P1-1 test supervisor ─> P2-2 device-test observability
P0-2 + P1-1 ─> P2-3 decision/plan lifecycle tooling
```

Tasks in the same row are safe to assign to separate agents when their file
ownership is respected.

---

## P0-1: Enforce isolated agent workspaces

**Why:** Shared worktree edits caused overlapping patches, accidental reverts,
and ambiguous ownership during the reconciliation work.

**Scope:** Create a worktree/task manifest convention and a preflight checker.

**Suggested files:**

- `tools/agent-workspaces/task-manifest.schema.json`
- `scripts/agent-task-preflight.sh`
- `docs/notes/agent-workspaces.md`
- `.agents/tasks/<task-id>.yaml` (new convention)

**Manifest shape:**

```yaml
task: atomic-reconciliation
branch: feature/atomic-reconciliation
owns:
  - backend/src/services/taskActionCoordinator.ts
  - backend/src/services/todoistChecklistService.ts
may_read:
  - client/**
must_not_touch:
  - client/androidApp/**
required_checks:
  - backend-focused
```

**Implementation notes:**

1. Preflight verifies the task is running outside the primary integration
   worktree, unless `integration: true` is explicitly set.
2. Compare `git diff --name-only` to `owns`; fail with a concise list of
   conflicting paths.
3. Support explicit shared files only with an `allows_shared_with` list.
4. Do not block read-only agents.

**Acceptance criteria:**

- A task that edits a non-owned path exits nonzero before tests run.
- An integration task can intentionally own cross-cutting files.
- Documentation explains how a parent agent merges/reviews child worktrees.
- Tests cover glob matching, allowed shared ownership, and dirty unrelated
  worktrees.

**Verification:** `scripts/agent-task-preflight.sh .agents/tasks/example.yaml`.

**Dependencies:** None.

**Status:**

- Brainstormed 2026-07-17 and scoped down to documentation-only: no manifest schema, no
  preflight script, no `.agents/tasks/` tooling — the user opted for manual worktree
  discipline instead of enforcement. See
  `docs/superpowers/specs/2026-07-17-agent-workspaces-design.md` for the full rationale.
- Shipped at that narrowed scope: `docs/notes/agent-workspaces.md` documents the
  convention (why, when to isolate, informal manifest shape, integration/merge review,
  cleanup), and `AGENTS.md` points to it from the start-of-session routine and engineering
  standards. Plan: `docs/superpowers/plans/2026-07-17-agent-workspaces.md` (complete).
- The suggested files below (schema, preflight script, `.agents/tasks/` convention) were
  explicitly rejected as non-goals, not merely deferred — re-attempting them should start
  with a new brainstorming pass, not a resumption of this one.

---

## P0-2: Create a `buzer-subagent-delivery` skill

**Why:** Agents need an executable delivery protocol, not only a prose working
agreement.

**Scope:** Add a local skill that governs bounded implementation subtasks.

**Required rules:**

- Claim files before editing and run P0-1 preflight.
- Report progress within five minutes of start and after every blocked command.
- Prefer focused tests while editing; only run broad suites once the patch is
  coherent.
- Never revert code outside a hunk demonstrably authored by the current agent.
- Finish with the repository handoff format even when no code was changed.
- Escalate an ownership conflict instead of “fixing around” it.

**Acceptance criteria:**

- Skill includes a task prompt template with objective, owned files, tests, and
  stop conditions.
- Skill includes a review prompt template for a read-only final reviewer.
- At least one dry-run example demonstrates a source agent plus review agent.

**Verification:** Run the skill’s scripted example or checklist validation.

**Dependencies:** P0-1 recommended, but not required.

---

## P0-3: Create a `buzer-action-reconciliation` skill

**Why:** The most expensive failures were distributed-action semantic mistakes:
partial public results, mutable replay fingerprints, and guessed effects.

**Scope:** Encode the action-journal and snapshot-authority protocol in a
repository-local skill.

**Required checklist for every new mutation endpoint:**

1. Declare request IDs, root/child ID domains, and immutable preconditions.
2. Distinguish deterministic pre-send validation from post-send uncertainty.
3. Reserve action and command identities before sending external commands.
4. Define authoritative effect predicates without title/text guessing.
5. Return only `completed` or `reconciliation_pending` after a sent command.
6. Return/apply an authoritative snapshot for completed, pending, and conflict
   states as required by the API contract.
7. Add tests for 4xx pre-send rejection, sent timeout, replay, stale revision,
   and duplicate user-visible child content.

**Suggested artifacts:**

- `.codex/skills/buzer-action-reconciliation/SKILL.md`
- `docs/notes/action-reconciliation-protocol.md`
- test-case template under `backend/test/helpers/`

**Acceptance criteria:**

- The skill explicitly bans deriving action fingerprints from `now`, current
  durations, current revisions, or mutable descriptions.
- It identifies `task_action_journal` and `task_action_commands` as recovery
  sources of truth.
- A sample endpoint walkthrough uses the existing checklist mutation flow.

**Dependencies:** None.

---

## P1-1: Add scoped verification orchestration

**Why:** Verification was correct but manually assembled and easy to partially
run from the wrong directory.

**Scope:** Add `scripts/verify-change.sh <profile>`.

**Profiles:**

| Profile | Required checks |
| --- | --- |
| `backend-action` | backend focused tests, full backend tests, typecheck, build |
| `shared-contract` | shared host tests, contract fixture validation |
| `android-ui` | shared + Android JVM tests, `assembleDebug`, fake-backend E2E |
| `connected-device` | all `android-ui` checks plus `e2e-connected-device.sh` |
| `atomic-reconciliation` | backend, fake backend, Android JVM/build, contract scan, connected device |

**Implementation notes:**

- Use separate commands internally; preserve each exit code and print a compact
  Markdown summary.
- Refuse an unknown profile.
- Do not automatically run the physical-device profile unless explicitly
  requested or an environment flag is present.
- Emit a handoff-ready verification block to stdout and an optional file.

**Acceptance criteria:**

- The atomic profile covers the exact suite that proved the current atomic
  reconciliation implementation.
- A deliberately failing subcommand produces a clear failed profile result.
- The script works from the repository root and documents any required device
  approval.

**Dependencies:** P0-2 for agent integration; otherwise none.

---

## P1-2: Establish one executable API contract source

**Why:** Fastify schemas, Kotlin serializers, fake backend payloads, docs, and
tests were manually kept in sync.

**Scope:** Introduce machine-readable schemas for snapshots, mutation responses,
action metadata, and API error bodies.

**Recommended minimal design:**

```text
contracts/
  tasks-snapshot.schema.json
  mutation-response.schema.json
  action-request.schema.json
  api-error.schema.json
```

Start with validation rather than code generation. Generation can follow once
the schemas prove stable.

**Required contract tests:**

- Every fake-backend success/pending/conflict response validates.
- Representative Fastify responses validate.
- Kotlin JSON fixtures decode every valid contract example.
- Invalid `partial`, `childOutcomes`, and `createdRoot` mutation success payloads
  are rejected.
- Missing positive `snapshotGeneration` is rejected where the current protocol
  requires it.

**Acceptance criteria:**

- The mutation response schema permits only `completed` and
  `reconciliation_pending`.
- Docs render or link their payload examples from the canonical contract files.
- CI fails if fake/backend/Kotlin contract fixtures drift.

**Dependencies:** P0-3 recommended.

---

## P1-3: Upgrade the fake backend into a fault simulator

**Why:** The fake backend is already useful; named, reproducible ambiguity
scenarios would make end-to-end safety testing much cheaper.

**Scope:** Add deterministic fault presets and a small debug state API.

**Fault presets:**

- `after_command_sent_timeout`
- `sync_lag_effect_unknown`
- `sync_lag_effect_present`
- `stale_task_revision`
- `stale_child_revision`
- `duplicate_draft_titles`
- `snapshot_generation_regression`

**Suggested endpoints:**

- `POST /__scenario` to install a named scenario.
- `GET /__state` for replay/journal-like observable fake state.
- `POST /__clock` to set/advance deterministic time.

**Acceptance criteria:**

- Android instrumentation can select a scenario through one instrumentation
  argument or wrapper option.
- Replaying one action ID under an ambiguity preset proves no duplicate external
  child effect.
- Fake responses validate under P1-2’s contract checks.

**Dependencies:** P1-2.

---

## P1-4: Build a reusable action-replay test harness

**Why:** Journal seed/replay tests are currently valuable but verbose and easy
to construct incorrectly.

**Scope:** Add TypeScript test helpers for action/journal/sync sequences.

**Target expressive shape:**

```ts
await actionHarness(db)
  .action("checklist_mutation", request)
  .command("child:draft-1:add")
  .sendThrows(timeout)
  .syncShows("unknown")
  .retry()
  .expectPending()
  .expectSingleCommand();
```

**Acceptance criteria:**

- Helpers can seed reserved, sent, confirmed, and failed command states.
- Helpers expose command UUID and temporary ID assertions.
- Existing checklist/root action tests migrate at least two representative
  ambiguous-action cases to the harness.
- The harness cannot silently use text/title matching as effect proof.

**Dependencies:** P0-3.

---

## P2-1: Split root action implementation modules

**Why:** `TaskActionService` is a broad coordination point for creation,
lifecycle, root edits, recurrence, postpone/extend, and checklist delegation.

**Scope:** Refactor without changing public API behavior.

**Target layout:**

```text
backend/src/services/actions/
  actionCoordinator.ts
  createTaskActions.ts
  rootLifecycleActions.ts
  rootEditActions.ts
  checklistActions.ts
  actionJournal.ts
```

**Rules:**

- Each module owns its immutable input, effect predicate, command plan, and
  authoritative reconciliation response.
- Root scheduling/assignment paths cannot accept child task models at the type
  level.
- Move in small behavior-preserving commits with tests green after each move.

**Acceptance criteria:**

- Existing public routes and action-journal schema remain compatible.
- Checklist child mutations compile without importing root scheduling helpers.
- Existing action service tests remain green, plus an architectural import-boundary test or lint rule.

**Dependencies:** P1-4 recommended.

---

## P2-2: Add device-test observability and a supervisor

**Why:** A long instrumentation run can look stuck even while it is healthy.

**Scope:** Make connected-device E2E status inspectable without raw logcat.

**Suggested files:**

- `scripts/e2e-connected-device.sh` (write a state file)
- `scripts/e2e-status.sh`
- `docs/notes/local-development.md`

**State file fields:**

```json
{
  "phase": "instrumentation",
  "device": "Pixel 10 - 16",
  "current": 12,
  "total": 25,
  "failed": 0,
  "lastTest": "…",
  "startedAt": "…"
}
```

**Acceptance criteria:**

- Status command reports `building`, `installing`, `running`, `passed`, or
  `failed` with the last completed test.
- The wrapper cleans stale status safely on normal completion and retains a
  failed result for debugging.
- No raw ADB process parsing is needed for routine progress checks.

**Dependencies:** P1-1 recommended.

---

## P2-3: Formalize plan/decision lifecycle management

**Why:** Superseded plans retained outdated partial-response requirements and
increased cognitive load.

**Scope:** Introduce active/completed/superseded states for plans and enforce
explicit links from decisions that change behavior.

**Implementation:**

1. Add a metadata header to every plan:

   ```md
   Status: active | completed | superseded
   Superseded-by: <path or null>
   Decision-links: <paths>
   ```

2. Add `scripts/check-plan-lifecycle.sh` that fails active plans containing a
   `Superseded-by` value and detects links to missing files.
3. Update historical plans incrementally when they are next touched; do not do
   a noisy repository-wide rewrite.

**Acceptance criteria:**

- The older recurring/checklist plan declares atomic reconciliation as its
  behavioral successor for mutation-result semantics.
- A new agent can find active product truth without reading historical plans.
- CI or `verify-change.sh` runs lifecycle validation for documentation changes.

**Dependencies:** P1-1.

---

## P2-4: Add a compact architecture map

**Why:** The core complexity is authority and recovery flow, not ordinary CRUD.

**Scope:** Create one maintained architecture document with a small diagram and
an ownership table.

**Must include:**

```text
Android UI → shared transport → Fastify route → action coordinator/journal
→ Todoist command → authoritative sync → mutation snapshot
→ Android snapshot store → alarm reconciliation
```

For each boundary, state ID domain, retry owner, authority source, and expected
failure result.

**Acceptance criteria:**

- The diagram distinguishes mirror task IDs, Todoist IDs, action IDs, command
  UUIDs, and temporary IDs.
- It explicitly states why no UI layer may infer mutation success.
- P0-3 links to this document rather than duplicating all architecture prose.

**Dependencies:** P0-3.

---

## Suggested implementation sequence

1. P0-1 and P0-2: eliminate coordination hazards first.
2. P0-3 and P1-4: make reconciliation work safer and easier to test.
3. P1-1 and P2-2: make verification predictable and observable.
4. P1-2 and P1-3: make contracts and end-to-end fault simulation executable.
5. P2-1, P2-3, P2-4: reduce long-term architectural/documentation drag.

## Non-goals

- Do not add live Todoist/WhatsApp calls to automated tests.
- Do not change production user behavior merely to make the harness simpler.
- Do not replace the Android-native enforcement lane with host-only checks.
- Do not use these tasks as authorization for deployment or data migrations
  beyond the task’s explicit scope.
