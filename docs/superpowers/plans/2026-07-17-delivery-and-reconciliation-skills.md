# Delivery And Reconciliation Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship two dual-runtime skills per
`docs/superpowers/specs/2026-07-17-delivery-and-reconciliation-skills-design.md`:
`buzer-subagent-delivery` (P0-2) and `buzer-action-reconciliation` (P0-3).

**Architecture:** Nine documentation files, no code, no tests. Both skills exist
identically under `.codex/skills/` and `.claude/skills/` (content duplicated, each file
noting its twin's path). P0-3 additionally gets one shared, non-duplicated reference
note in `docs/notes/`.

**Tech Stack:** Markdown only.

---

## File Structure

- Create: `.codex/skills/buzer-subagent-delivery/SKILL.md`
- Create: `.claude/skills/buzer-subagent-delivery/SKILL.md` (identical content)
- Create: `.codex/skills/buzer-subagent-delivery/task-prompt-template.md`
- Create: `.claude/skills/buzer-subagent-delivery/task-prompt-template.md` (identical)
- Create: `.codex/skills/buzer-subagent-delivery/review-prompt-template.md`
- Create: `.claude/skills/buzer-subagent-delivery/review-prompt-template.md` (identical)
- Create: `.codex/skills/buzer-action-reconciliation/SKILL.md`
- Create: `.claude/skills/buzer-action-reconciliation/SKILL.md` (identical content)
- Create: `docs/notes/action-reconciliation-protocol.md` (single file, not duplicated)

Task 1 covers the first six files (P0-2); Task 2 covers the last three (P0-3). They're
independent of each other and could be done in either order.

---

### Task 1: Create the `buzer-subagent-delivery` skill

**Files:**
- Create: `.codex/skills/buzer-subagent-delivery/SKILL.md`
- Create: `.claude/skills/buzer-subagent-delivery/SKILL.md`
- Create: `.codex/skills/buzer-subagent-delivery/task-prompt-template.md`
- Create: `.claude/skills/buzer-subagent-delivery/task-prompt-template.md`
- Create: `.codex/skills/buzer-subagent-delivery/review-prompt-template.md`
- Create: `.claude/skills/buzer-subagent-delivery/review-prompt-template.md`

- [ ] **Step 1: Write `SKILL.md` to both `.codex/skills/buzer-subagent-delivery/` and
  `.claude/skills/buzer-subagent-delivery/`** (identical content at both paths)

```markdown
---
name: buzer-subagent-delivery
description: Executable delivery protocol for bounded implementation subtasks in this repo — file claiming, progress reporting, test discipline, and handoff format for a delivering agent plus a read-only reviewer.
---

# Buzer Subagent Delivery

This is the executable version of this repo's working agreement (`AGENTS.md`) for
bounded implementation subtasks — small, independently-assignable pieces of work handed
to a single agent, optionally checked by a read-only reviewer.

This file is duplicated at both `.codex/skills/buzer-subagent-delivery/SKILL.md` and
`.claude/skills/buzer-subagent-delivery/SKILL.md`. Keep both in sync when editing.

## Required Rules For The Delivering Agent

1. **Check workspace state before editing.** Run `git worktree list`. For
   independently-assignable subtasks, work in a dedicated worktree per
   `docs/notes/agent-workspaces.md` rather than the primary checkout. State the files
   you intend to touch before making any edit.
2. **Report progress early and after every blockage.** Report within five minutes of
   starting, and immediately after any command that blocks you — a failing test you
   didn't expect, a missing dependency, an ambiguous requirement.
3. **Prefer focused tests while editing.** Run the narrowest test that exercises your
   change as you iterate. Only run the full/broad suite once your patch is coherent and
   you believe it's ready to hand off.
4. **Never revert code outside a hunk you demonstrably authored.** If a file has changes
   you didn't make, leave them alone — don't "clean up" or revert surrounding code, even
   if it looks unrelated or wrong.
5. **Finish with the repository handoff format, even with no code changes.** Follow
   `AGENTS.md`'s Handoff Requirements. "I investigated and found nothing to change" is a
   valid, expected handoff — silence is not.
6. **Escalate an ownership conflict; don't fix around it.** If you find another agent's
   in-progress edits in a file you need, stop and report the conflict rather than
   working around it or guessing at intent.

## Task Prompt Template

See `task-prompt-template.md` in this same directory for the template to use when
dispatching a delivering agent.

## Review Prompt Template

See `review-prompt-template.md` in this same directory for the template to use when
dispatching a read-only final reviewer.

## Dry-Run Example

`docs/handoffs/2026-07-17-agent-workspaces-convention.md` is a real worked example of
this protocol in action: a delivering agent produced `docs/notes/agent-workspaces.md`,
a spec-compliance pass independently verified it byte-for-byte against its plan,
and a code-quality pass caught two real, substantive issues — an
overclaimed branch-naming convention, and a merge-history example that cited no-op
branch closures as "the" merge pattern while omitting the one commit that actually
merged real code — both fixed and re-verified before the work was considered done. A
follow-up review pass then caught a markdown line-wrap defect that the fix itself had
introduced. See that handoff plus the commit history on
`docs/superpowers/plans/2026-07-17-agent-workspaces.md` for the full sequence.
```

- [ ] **Step 2: Write `task-prompt-template.md` to both `.codex/skills/buzer-subagent-delivery/`
  and `.claude/skills/buzer-subagent-delivery/`** (identical content at both paths)

```markdown
# Task Prompt Template

Use this template when dispatching a delivering agent for a bounded implementation
subtask in this repo. This file is duplicated at both
`.codex/skills/buzer-subagent-delivery/task-prompt-template.md` and
`.claude/skills/buzer-subagent-delivery/task-prompt-template.md`. Keep both in sync.

```
Task tool (general-purpose):
  description: "Implement <task name>"
  prompt: |
    You are implementing <task name>.

    ## Task Description

    [FULL TEXT of the task — paste it here, don't make the agent read a plan file]

    ## Context

    [Scene-setting: where this fits, what depends on it, what it depends on]

    ## Repository Conventions

    - Read `AGENTS.md` before making any change.
    - Run `git worktree list` first. For an independently-assignable subtask, work in a
      dedicated worktree per `docs/notes/agent-workspaces.md` rather than the primary
      checkout.
    - State the files you intend to touch before editing.
    - Report progress within five minutes of starting, and immediately after any
      blocking command.
    - Prefer focused tests while editing; run the full suite only once your patch is
      coherent.
    - Never revert code outside a hunk you demonstrably authored.
    - Finish with `AGENTS.md`'s handoff format, even if no code changed.
    - If you find another agent's in-progress edits in a file you need, stop and report
      the conflict — don't work around it.

    ## Before You Begin

    If anything about the requirements, approach, or scope is unclear, ask now.

    ## Your Job

    1. Implement exactly what the task specifies.
    2. Verify your change (tests if applicable; otherwise the specific verification the
       task describes).
    3. Commit your work.
    4. Self-review (see below).
    5. Report back.

    Work from: [directory]

    ## When You're In Over Your Head

    It's always OK to stop and escalate. Report BLOCKED or NEEDS_CONTEXT rather than
    guessing.

    ## Before Reporting Back: Self-Review

    - Did you implement everything the task specifies, and nothing more?
    - Did you follow existing patterns in the code/docs you touched?
    - Did you avoid touching anything outside your assigned files?

    ## Report Format

    - **Status:** DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
    - What you did
    - What you verified, and the result
    - Files changed
    - Self-review findings (if any)
    - Any issues or concerns
```
```

- [ ] **Step 3: Write `review-prompt-template.md` to both `.codex/skills/buzer-subagent-delivery/`
  and `.claude/skills/buzer-subagent-delivery/`** (identical content at both paths)

```markdown
# Review Prompt Template

Use this template when dispatching a read-only final reviewer for a bounded
implementation subtask. Unlike a multi-stage plan review, this is a single combined
pass: does the work match what was asked, and is it well-built? This file is duplicated
at both `.codex/skills/buzer-subagent-delivery/review-prompt-template.md` and
`.claude/skills/buzer-subagent-delivery/review-prompt-template.md`. Keep both in sync.

```
Task tool (general-purpose):
  description: "Review <task name>"
  prompt: |
    You are reviewing completed work as a read-only final reviewer. You do not fix
    anything yourself — you report findings back to whoever dispatched you.

    ## What Was Requested

    [FULL TEXT of the original task]

    ## What The Delivering Agent Claims They Built

    [Their report, verbatim]

    ## CRITICAL: Do Not Trust The Report

    Verify everything independently by reading the actual changes and, where relevant,
    running the actual verification commands yourself. Don't take the delivering
    agent's word for completeness or correctness.

    ## Your Job

    Check:

    **Spec compliance:**
    - Was everything requested actually built?
    - Was anything built that wasn't requested (scope creep)?
    - Did the agent misinterpret any requirement?

    **Quality:**
    - Is it clean, maintainable, and consistent with existing patterns in this repo?
    - Are claims/examples inside the change actually accurate (verify against real
      files/commands, don't take prose at face value)?
    - Any regressions to files/behavior that existed before this change?

    **Process:**
    - Did the agent follow `docs/notes/agent-workspaces.md` (if relevant) and
      `AGENTS.md`'s handoff format?

    ## Output Format

    - ✅ / ❌ verdict per category above
    - Issues found, categorized Critical / Important / Minor, each with a file
      reference, what's wrong, and why it matters
    - **Ready to hand off?** Yes | No | With fixes
```
```

- [ ] **Step 4: Verify all six files exist and each runtime pair is identical**

Run:
```bash
diff .codex/skills/buzer-subagent-delivery/SKILL.md .claude/skills/buzer-subagent-delivery/SKILL.md
diff .codex/skills/buzer-subagent-delivery/task-prompt-template.md .claude/skills/buzer-subagent-delivery/task-prompt-template.md
diff .codex/skills/buzer-subagent-delivery/review-prompt-template.md .claude/skills/buzer-subagent-delivery/review-prompt-template.md
```
Expected: no output from any `diff` (files identical).

- [ ] **Step 5: Commit**

```bash
git add .codex/skills/buzer-subagent-delivery .claude/skills/buzer-subagent-delivery
git commit -m "docs: add buzer-subagent-delivery skill (Codex + Claude Code)"
```

---

### Task 2: Create the `buzer-action-reconciliation` skill

**Files:**
- Create: `.codex/skills/buzer-action-reconciliation/SKILL.md`
- Create: `.claude/skills/buzer-action-reconciliation/SKILL.md`
- Create: `docs/notes/action-reconciliation-protocol.md`

- [ ] **Step 1: Write `SKILL.md` to both `.codex/skills/buzer-action-reconciliation/`
  and `.claude/skills/buzer-action-reconciliation/`** (identical content at both paths)

```markdown
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
```

- [ ] **Step 2: Write `docs/notes/action-reconciliation-protocol.md`**

```markdown
# Action Reconciliation Protocol

Date: 2026-07-17

The full protocol referenced by the `buzer-action-reconciliation` skill
(`.codex/skills/buzer-action-reconciliation/SKILL.md` /
`.claude/skills/buzer-action-reconciliation/SKILL.md`). Read this file before
implementing a new mutation endpoint.

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
```

- [ ] **Step 3: Verify all three files exist and the SKILL.md pair is identical**

Run:
```bash
diff .codex/skills/buzer-action-reconciliation/SKILL.md .claude/skills/buzer-action-reconciliation/SKILL.md
grep -c "^## " docs/notes/action-reconciliation-protocol.md
```
Expected: no output from `diff`; `grep -c` returns `5` (Why This Exists, The 7-Item
Checklist, Hard Bans, Sources Of Truth, Worked Example: TodoistChecklistService.mutate()).

- [ ] **Step 4: Commit**

```bash
git add .codex/skills/buzer-action-reconciliation docs/notes/action-reconciliation-protocol.md
git commit -m "docs: add buzer-action-reconciliation skill (Codex + Claude Code)"
```

---

## Plan Self-Review Notes

- **Spec coverage:** Both skills' file lists, all 6 P0-2 rules, both templates, the
  dry-run citation, the 7-item P0-3 checklist, the hard ban, the sources of truth, and
  the `TodoistChecklistService.mutate()` walkthrough are all present verbatim above.
  Non-goals (no test-helper artifact, no preflight-script references) require no task —
  correctly absent.
- **Placeholder scan:** No TBD/TODO.
- **Type consistency:** N/A — no code.
- Task 2 Step 3's expected header count (5) was verified by counting the actual `## `
  headers in Step 2's content above, not asserted from memory.
