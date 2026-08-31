# Action Reconciliation Protocol [HISTORICAL / SUPERSEDED]

> [!WARNING]
> **HISTORICAL / SUPERSEDED**: This protocol has been superseded by the Level 1 mutation simplification architecture documented in [`docs/decisions/2026-08-30-level-1-mutation-simplification.md`](../decisions/2026-08-30-level-1-mutation-simplification.md) and [`docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`](../superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md).
>
> The action journal, command journal, replay fingerprints, root mutex, and reconciliation statuses (`reconciliation_pending`, etc.) have been removed in favor of a process-local FIFO, 1-minute polling, forced refresh, and session-only user warnings.
>
> This document is retained solely as a historical record of previous distributed-action design iterations and incident history. Do not use for new endpoints.

Date: 2026-07-17 (Superseded: 2026-08-30)

The full protocol previously referenced by the retired `buzer-action-reconciliation` skill
(`.codex/skills/buzer-action-reconciliation/SKILL.md` /
`.claude/skills/buzer-action-reconciliation/SKILL.md`). Read this file only for historical
context on past incident history.

## Why This Exists

This repo's most expensive past failures were distributed-action semantic mistakes:
partial public results, mutable replay fingerprints, and guessed effects — not ordinary
CRUD bugs. `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md` (now
complete) fixed this class of bug once; this document exists so the next new mutation
endpoint doesn't have to rediscover the same lessons.

## The 7-Item Checklist

1. **Declare request IDs, root/child ID domains, and immutable preconditions.**
2. **Distinguish deterministic pre-send validation from post-send uncertainty.**
3. **Reserve action and command identities before sending external commands.**
4. **Define authoritative effect predicates without title/text guessing.**
5. **Return only `completed` or `reconciliation_pending` after a sent command.**
6. **Return/apply an authoritative snapshot for completed, pending, and conflict
   states.**
7. **Add tests for 4xx pre-send rejection, sent timeout, replay, stale revision, and
   duplicate user-visible child content.**

## Hard Bans

Never derive an action fingerprint from `now`, a current duration, a current revision,
or a mutable description — a fingerprint must be reconstructible from immutable client
intent and preconditions alone, or replay detection breaks. See
`backend/src/repositories/actionJournalRepository.ts`'s `requestFingerprint` model.

## Sources Of Truth

`task_action_journal` and `task_action_commands` (`backend/src/db/schema.sql:88` and
`:102`) are the authoritative recovery sources. Never reconstruct action/command state
by guessing from current task/child content — always read these tables.

## Worked Example: `TodoistChecklistService.mutate()`

`backend/src/services/todoistChecklistService.ts:56` is a real, currently-shipped
mutation endpoint. Here is how it satisfies each checklist item:

**1. Request IDs, ID domains, immutable preconditions.** `request.action.actionId` and
`request.action.deviceId` are the request identity. `rootTaskId` is the root ID domain
(a snapshot-local mirror ID). `mutation.childId` is the child ID domain, resolved by
`findChild()` (line 299), which accepts either the snapshot-local ID or a legacy
Todoist ID — the latter only for pre-v3 in-flight actions, per that method's own
comment. `expectedOccurrenceKey` and each mutation's `expectedChildRevision` are the
immutable preconditions, checked against `mirrorRevision` before anything executes.

**2. Deterministic pre-send validation vs. post-send uncertainty.** The `validate`
callback passed to the coordinator (`assertProjectedTree` + `validateMutations`, lines
81-84) runs before any Todoist command is sent. `validateMutations`'s own comment states
the invariant directly: "Every deterministic child error is found before the first
Todoist command. Once a command was sent, the coordinator must preserve the action as
pending."

**3. Reserve identities before sending.** For an "add" mutation, `tempId =
randomUUID()` (line 154) is reserved *before* `this.send(...)` is called. Every mutation
gets a `stepKey` (e.g. `` `child:${mutation.clientMutationId}:add` ``, line 155) passed
to `context.executeCommand(stepKey, send, { tempId })` (line 230), which reserves the
command row before the Todoist call actually happens.

**4. Authoritative effect predicates without title/text guessing.** `effectsPresent()`
(line 233) is the effect predicate. For add/delete/check/uncheck, it's purely
ID/boolean-based: add checks `created?.parentId === root.todoistTaskId &&
created.deletedAt === null` (never by title); delete/check/uncheck check
`deletedAt`/`completedAt` directly. The one nuance: for "edit," line 258 does compare
`child.title`/`child.description` against the requested values — but only *after* the
child has already been identified by its immutable `childId`. That's confirming a
known entity's field values, not using text to guess *which* entity a command affected
— the ban is on the latter, not the former.

**5. Only `completed` or `reconciliation_pending` after a sent command.** `mutate()`
(lines 90-91) throws `DomainError.reconciliationPending(this.snapshot())` if the
coordinator reports `"pending"`; otherwise it returns the completed shape. At the route
layer, `mutationResponse()` (`backend/src/http/routes.ts:259`) sets
`outcome: "completed"`.

**6. Authoritative snapshot for completed/pending/conflict states.** This is handled
centrally, not per call site: `backend/src/http/routes.ts`'s Fastify `setErrorHandler`
(line 120) attaches a fresh `services.snapshots.getSnapshot(...)` to *every* `202`
(reconciliation_pending) and every `409` (conflict — stale_task/stale_child/
stale_occurrence/action_id_reused) response, regardless of whether the `DomainError`
was constructed with a snapshot in its own `details`. Deterministic pre-send rejections
(404, 422, other 4xx) correctly carry no snapshot — they're request-level rejections,
not one of the three snapshot-bearing outcomes.

**7. Required test coverage.** See `backend/test/services/todoistChecklistService.test.ts`
for this endpoint's existing test suite; new mutation endpoints should cover the same
five scenarios (4xx pre-send rejection, sent timeout, replay, stale revision, duplicate
user-visible child content) there or in an equivalent test file.
