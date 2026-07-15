# Handoff: Recurring Checklists Task 1 Persistence

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 1 — Add Guarded Persistence For Typed Mirrors, Occurrences, And Journaling
Base: `f572e0eb86ae00602c816cb5dbe1fde90780c7ec`
Branch: `feature/recurring-checklists`

## Status And Attempted Steps

- All Task 1 implementation and verification steps are complete locally.
- Plan checkboxes were intentionally left unchanged for the controller to update.
- Implemented guarded migration v2, typed task persistence and mirror revisions, occurrence and
  escalation backfill, action/command journaling, regression tests, review fixes, and this handoff.
- No Task 2+ Todoist command, recurrence codec, policy, reconciler, coordinator, HTTP, or client work
  was implemented.

## Files Changed

- `backend/src/db/schema.sql`
- `backend/src/db/migrations.ts`
- `backend/src/todoist/todoistTypes.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/test/db/migrations.test.ts`
- `backend/test/repositories/taskRepository.test.ts`
- `backend/test/repositories/actionJournalRepository.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-1-persistence.md`

## Implementation Notes

- Migration v2 runs in one `better-sqlite3` transaction, uses guarded `ALTER TABLE` operations,
  seeds missing occurrence UUIDs and due/recurrence anchors, replaces escalation uniqueness, creates
  durable journal tables, deletes the current sync token once, and records schema migration v2.
- `todoist_sync_state.mirror_contract_version` defaults to `1`. This task does not write `2`; Task 5
  must do so only after a successful authoritative `sync("*")`.
- Legacy recurring fingerprints are derived from retained Todoist `raw_json.due` facts. The typed
  product contract remains gated behind the forced full sync.
- A compatibility partial index preserves one unkeyed legacy escalation claim per task, and a
  migration trigger ignores a legacy unkeyed retry when migration already keyed an existing claim.
  Task 5 remains responsible for making the service itself occurrence-aware.
- Mirror upsert writes every typed column, stores recurrence as SQLite `0/1`, converts it back to a
  Boolean at repository reads, and increments only the changed task's mirror revision when typed
  authoritative fields differ.
- Journal action and command mutations validate row existence and legal transitions. Repeating the
  same terminal state and compact JSON payload is idempotent and does not rewrite timestamps;
  contradictory or missing transitions fail explicitly.

## Required Context And Documentation

Verification:
- Command: `git status --short && git rev-parse HEAD && git branch --show-current`
- Result: PASS
- Evidence: clean isolated worktree at `f572e0e` on `feature/recurring-checklists` before edits.

Verification:
- Command: read `AGENTS.md`, both approved product specs, the complete 17-task implementation plan,
  `docs/handoffs/2026-07-13-subtasks-recurring-tasks-research.md`, and
  `docs/handoffs/2026-07-15-backend-internet-exposure-rollout.md`
- Result: PASS
- Evidence: Task 1 boundaries and the existing unprefixed/authenticated deployment model were
  confirmed before code changes.

Verification:
- Command: `npx ctx7@latest library "better-sqlite3" "idempotent SQLite schema migrations guarded ALTER TABLE add column transaction PRAGMA table_info index_list foreign keys prepared statements"`
- Result: PASS
- Evidence: resolved `/wiselibs/better-sqlite3`.

Verification:
- Command: `npx ctx7@latest docs "/wiselibs/better-sqlite3" "transaction API atomic migration exec prepare get all SQLite pragma table_info index_list foreign_keys and safe idempotent schema changes"`
- Result: PASS
- Evidence: confirmed synchronous transaction/savepoint behavior, `exec`, prepared-query, and
  PRAGMA APIs used by the implementation.

## TDD RED/GREEN Evidence

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: FAIL (expected RED)
- Evidence: 2 failures showed missing v2 typed columns and missing journal tables.

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: PASS (GREEN)
- Evidence: initial migration implementation passed 3/3 tests; final migration suite passes 4/4.

Verification:
- Command: `npm test -- test/repositories/taskRepository.test.ts`
- Result: FAIL (expected RED)
- Evidence: 2 failures showed missing typed projections and missing mirror revision behavior.

Verification:
- Command: `npm test -- test/repositories/taskRepository.test.ts`
- Result: PASS (GREEN)
- Evidence: 4/4 tests pass, including identical replay and child-only revision advancement.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: FAIL (expected RED)
- Evidence: repository module was absent.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: PASS (GREEN)
- Evidence: initial journal implementation passed 3/3; final transition-safe suite passes 5/5.

Verification:
- Command: `npm test`
- Result: FAIL (diagnostic RED)
- Evidence: 2 existing TaskAction tests exposed SQLite `(task_id, NULL)` uniqueness allowing two
  legacy escalation claims.

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: FAIL (expected regression RED)
- Evidence: direct unkeyed compatibility assertion accepted a duplicate instead of rejecting it.

Verification:
- Command: `npm test -- test/db/migrations.test.ts test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: 18/18 tests pass with the unkeyed compatibility index.

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: FAIL (review RED)
- Evidence: a legacy unkeyed retry inserted alongside a migrated keyed claim (`changes = 1`).

Verification:
- Command: `npm test -- test/db/migrations.test.ts test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: 18/18 tests pass after the keyed-to-unkeyed migration trigger guard.

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: FAIL (review RED)
- Evidence: realistic recurring `raw_json.due` was incorrectly fingerprinted as non-recurring.

Verification:
- Command: `npm test -- test/db/migrations.test.ts`
- Result: PASS (GREEN)
- Evidence: 4/4 tests pass with normalized recurring rule/language/time-zone backfill.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: FAIL (review RED)
- Evidence: missing action IDs and illegal action transitions silently succeeded.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: PASS (GREEN)
- Evidence: action transition enforcement passed 4/4 tests at that cycle.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: FAIL (review RED)
- Evidence: missing command IDs and illegal command transitions silently succeeded.

Verification:
- Command: `npm test -- test/repositories/actionJournalRepository.test.ts`
- Result: PASS (GREEN)
- Evidence: final action/command state-machine suite passes 5/5.

## Final Verification

Verification:
- Command: `npm test -- test/db/migrations.test.ts test/repositories/taskRepository.test.ts test/repositories/actionJournalRepository.test.ts`
- Result: PASS
- Evidence: 3 files, 13 tests passed.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 19 files, 93 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript exited 0 with no diagnostics.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: TypeScript build and schema copy exited 0.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: scoped scans for `reset_children_on_recurrence`, unresolved markers, and forbidden
  production journal fields such as Authorization/bearer/Todoist token/raw HTTP request
- Result: PASS
- Evidence: no production matches.

Verification:
- Command: independent Task 1 code review, fixes, and re-review
- Result: PASS
- Evidence: initial review found three Important issues; each received a RED/GREEN fix. Re-review
  reported no Critical, Important, or new Minor findings and assessed the task ready to merge.

## Manual Verification

- Inspected the fresh and legacy SQLite schemas through real `better-sqlite3` test databases.
- Verified migration rerun preserves the post-migration sync token and occurrence key.
- Verified the legacy escalation insert shape returns `changes = 0` beside a migrated keyed claim.
- Verified stored JSON is compact and journal tables contain no header, bearer-token, Todoist-token,
  or raw-request columns.
- No live Todoist, WhatsApp, VPS, Android, or production database mutation was performed.

## Assumptions And Deferred Boundaries

- New typed producer fields remain optional only as a staged compatibility bridge. Task 2 must make
  every producer populate them and may then make them required; omission currently normalizes to
  `NULL`/`false` at the repository input boundary.
- Task 5 must use the migration's canonical recurrence fingerprint semantics so the required first
  full sync confirms, rather than falsely rotates, an unchanged legacy occurrence.
- Task 5 must remove reliance on the legacy unkeyed escalation compatibility path by querying and
  inserting `(task_id, occurrence_key)` explicitly.
- Task 6 owns domain-error integration and coordinator use of the journal. This task intentionally
  provides persistence-level transition errors only.

## Known Failures Or Skipped Checks

- Live production database migration: SKIPPED; not authorized and unnecessary for this isolated
  persistence task.
- Live Todoist/WhatsApp, VPS deployment, client JVM, and emulator checks: SKIPPED; no external,
  HTTP-contract, Android, or user-visible behavior changed in Task 1.
- No known failing backend check remains.

## Next Recommended Step

Execute Task 2: populate the typed mirror from Todoist hierarchy/recurrence data and introduce the
explicit command gateway, preserving the Task 1 optional-input bridge only until all producers are
updated.
