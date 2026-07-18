---
name: buzer-action-reconciliation
description: The action-journal and snapshot-authority protocol every new mutation endpoint in this backend must follow — immutable fingerprints, pre-send vs post-send uncertainty, and completed/reconciliation_pending outcomes only.
---

# Buzer Action Reconciliation

This repo's most expensive past failures were distributed-action semantic mistakes:
partial public results, mutable replay fingerprints, and guessed effects. This skill
encodes the protocol that fixed that class of bug, proven complete in
`docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md` and implemented in
`backend/src/repositories/actionJournalRepository.ts` and
`backend/src/services/taskActionCoordinator.ts`.

This file is duplicated at both `.codex/skills/buzer-action-reconciliation/SKILL.md` and
`.claude/skills/buzer-action-reconciliation/SKILL.md`. Keep both in sync when editing.
Full protocol detail and the worked walkthrough live in
`docs/notes/action-reconciliation-protocol.md` — read that file, not just this summary,
before implementing a new mutation endpoint.

## Required Checklist For Every New Mutation Endpoint

1. Declare request IDs, root/child ID domains, and immutable preconditions.
2. Distinguish deterministic pre-send validation from post-send uncertainty.
3. Reserve action and command identities before sending external commands.
4. Define authoritative effect predicates without title/text guessing.
5. Return only `completed` or `reconciliation_pending` after a sent command.
6. Return/apply an authoritative snapshot for completed, pending, and conflict states.
7. Add tests for 4xx pre-send rejection, sent timeout, replay, stale revision, and
   duplicate user-visible child content.

## Hard Bans

Never derive an action fingerprint from `now`, a current duration, a current revision,
or a mutable description. Fingerprints must contain only immutable client intent and
preconditions — see `actionJournalRepository.ts`'s `requestFingerprint` model.

## Sources Of Truth For Recovery

`task_action_journal` and `task_action_commands` (`backend/src/db/schema.sql:88` and
`:102`) are authoritative. Never reconstruct action/command state by guessing from
current task/child content.

## Worked Example

See `docs/notes/action-reconciliation-protocol.md` for a full walkthrough of
`TodoistChecklistService.mutate()`
(`backend/src/services/todoistChecklistService.ts:56`) against all 7 checklist items
above.
