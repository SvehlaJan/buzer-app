# Decision: Use A Durable Global Snapshot Generation For Authoritative Client Reconciliation

Date: 2026-07-16
Status: accepted

## Context

Mutation responses and `409` errors return an authoritative task snapshot so
Android can reconcile alarms without an immediate follow-up GET. The existing
`scheduleVersion` is the maximum schedule value across roots, while checklist
mutations advance only a child revision. It therefore cannot order complete
snapshots from two devices: equal schedule values can represent distinct child
updates, deletion, or root lifecycle changes.

## Decision

The backend stores one `snapshot_generations(id = 1, generation)` row. SQLite
`AFTER INSERT`, `AFTER UPDATE`, and `AFTER DELETE` triggers on
`todoist_tasks`, `commitment_states`, and `todoist_projects` increment it.
Those trigger statements execute inside the originating SQLite transaction, so
the generation is committed atomically with the projected data and rolls back
with it. The migration creates the counter at `1` only after legacy backfills,
so installation establishes a baseline rather than manufacturing historical
generations.

`TasksSnapshotResponse.snapshotGeneration` is returned by GET snapshots,
successful `MutationResponse.snapshot` bodies, and `409` error snapshots. The
fake backend follows the same invariant for every snapshot-affecting fixture
mutation. It is a required wire property: API decoding rejects a missing value
on GET and success/202 mutations, and rejects a 409 that omits either the
authoritative snapshot or its generation. Only Android's `SnapshotStore`
may migrate a local pre-v3 cache by adding and persisting generation `0`; zero
is explicitly legacy and unorderable, and is replaced by the next positive
server authority.

`SnapshotStore` writes ordinary snapshots and this one-time cache migration
through a same-directory temporary file followed by rename, so it never
truncates the only cached authority in place. If the legacy rewrite cannot be
persisted, it logs the failure and still returns the decoded generation-`0`
snapshot for the next positive server authority to replace.

`TaskSnapshotService` reads task rows, active projects, and the generation in
one synchronous SQLite read transaction. This ensures one response describes
one committed projection; no network work occurs inside that transaction.

## Consequences

- Android applies whole authoritative snapshots in increasing
  `snapshotGeneration` order, removing the unsafe equal-`scheduleVersion`
  object-by-object merge workaround.
- Root and child revisions remain necessary request preconditions; the global
  generation does not replace same-object stale-write protection.
- Any future database table added to the task snapshot projection must receive
  all three generation triggers in the same migration, with a regression test.
- Generation numbers are durable ordering metadata, not authentication,
  timestamps, or a client-generated sequence.
