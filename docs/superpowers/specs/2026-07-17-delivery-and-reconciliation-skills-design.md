# Delivery And Reconciliation Skills Design

Date: 2026-07-17
Status: approved design, pending implementation plan

## Context And Goal

`docs/notes/2026-07-17-agent-delivery-backlog.md` proposes two related, parallel-safe
tasks now that P0-1 (isolated workspace convention) has shipped:

- **P0-2** — a `buzer-subagent-delivery` skill encoding an executable delivery protocol
  for bounded implementation subtasks (file claiming, progress reporting, test
  discipline, handoff format), replacing prose-only guidance with something invocable.
- **P0-3** — a `buzer-action-reconciliation` skill encoding the action-journal and
  snapshot-authority protocol, so every future mutation endpoint gets the same
  distributed-action safety checklist that the recently-completed atomic-reconciliation
  work proved out.

Both were cross-checked against the current repo before designing:

- `AGENTS.md` already covers most of P0-2's required rules as prose (start-of-session
  routine, handoff requirements, don't revert others' files, focused-then-broad testing).
  The gap is packaging as an invocable skill with task/review prompt templates, not
  inventing new rules.
- P0-3's protocol is not just designed but fully implemented and proven:
  `backend/src/repositories/actionJournalRepository.ts`, `task_action_journal` and
  `task_action_commands` tables (verified at `backend/src/db/schema.sql:88` and `:102`),
  and `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md` (5/5 tasks,
  20/20 steps complete).
- Neither runtime has a repo-local skills convention yet: `.claude/` contains only
  `settings.json`/`settings.local.json`, and `.codex/skills/` doesn't exist in this repo
  at all (only a user-level `~/.codex/skills/` with unrelated content). Inspecting an
  existing global Codex skill (`~/.codex/skills/android-cli/SKILL.md`) confirmed its
  format is structurally identical to a Claude Code skill: YAML frontmatter
  (`name`/`description`) plus a markdown body, with an optional `references/`-style
  sibling-file convention for supplementary material.

Decisions made during brainstorming:

- **Both runtimes, duplicated content.** Since this repo shows evidence of both Claude
  Code (this session) and Codex (`.codex/worktrees/`, `AGENTS.md`'s `ctx7` CLI
  convention) being used, and since both skills' actual content is tool-agnostic prose
  (no CLI-specific references in either), write the content once conceptually and place
  identical copies at `.codex/skills/<name>/...` and `.claude/skills/<name>/...`. Each
  file notes its twin's path so an editor notices if only one side gets updated. This is
  a documented-convention approach, not enforced by tooling — consistent with the
  manual-discipline choice already made for P0-1.
- **P0-3 excludes the test-helper artifact.** The backlog's suggested "test-case
  template under `backend/test/helpers/`" is dropped from this task — it duplicates the
  scope of backlog task P1-4 (a full reusable action-replay test harness, not yet
  picked up). P0-3 here covers protocol documentation only.
- **P0-2's dry-run example cites real, already-completed work** — the P0-1 execution
  (implementer, two rounds of independent review, both catching real issues) — rather
  than staging a synthetic scenario.
- **Verification is honest about its limits.** This session can verify file structure,
  frontmatter correctness, and content quality for both runtimes, and can verify a
  Claude Code project skill's presence/format, but cannot invoke a newly-created project
  skill from within itself (Claude Code discovers project skills at session start) and
  has no way to invoke Codex at all. No claim of live cross-runtime invocation will be
  made.

## P0-2: `buzer-subagent-delivery`

**Files** (6 total): `.codex/skills/buzer-subagent-delivery/SKILL.md`,
`.claude/skills/buzer-subagent-delivery/SKILL.md`, and matching pairs of
`task-prompt-template.md` and `review-prompt-template.md` alongside each `SKILL.md`.
Layout mirrors `superpowers:subagent-driven-development`'s own file structure (a main
skill file plus sibling prompt-template files).

**Required rules**, adapted from the backlog to match what actually exists after P0-1:

1. Check `git worktree list` before starting; for independently-assignable subtasks,
   work in a dedicated worktree per `docs/notes/agent-workspaces.md`. State the files
   you intend to touch before editing. (Replaces the backlog's original "run P0-1
   preflight" — no preflight script exists; P0-1 shipped documentation-only.)
2. Report progress within five minutes of starting and after every blocked command.
3. Prefer focused tests while editing; run broad suites only once the patch is coherent
   (already in `AGENTS.md`'s Testing Expectations; restated here as an actionable rule).
4. Never revert code outside a hunk demonstrably authored by the current agent (tightens
   `AGENTS.md`'s existing "don't overwrite others' files" bullet to hunk-level
   precision).
5. Finish with the repository handoff format even when no code changed (already required
   by `AGENTS.md`'s Handoff Requirements; made an explicit delivery-protocol step here).
6. Escalate an ownership conflict instead of quietly working around it.

**Task prompt template & review prompt template:** modeled on
`implementer-prompt.md` / `spec-reviewer-prompt.md` / `code-quality-reviewer-prompt.md`
from `subagent-driven-development`, adapted to reference this repo's actual conventions
(`AGENTS.md` handoff format, `docs/notes/agent-workspaces.md`) instead of generic ones.

**Dry-run example:** cites `docs/handoffs/2026-07-17-agent-workspaces-convention.md` and
the actual two-round review cycle from the P0-1 execution (caught a merge-history
mischaracterization, then a markdown line-wrap defect) as a real source-agent-plus-
reviewer example.

## P0-3: `buzer-action-reconciliation`

**Files:** `.codex/skills/buzer-action-reconciliation/SKILL.md`,
`.claude/skills/buzer-action-reconciliation/SKILL.md` (identical content, each noting its
twin), plus one shared `docs/notes/action-reconciliation-protocol.md` that both SKILL.md
files point to for full depth (not itself duplicated — it's reference material, not a
skill file).

**The 7-item checklist for new mutation endpoints** (unchanged from the backlog — it
matches the real implemented protocol):

1. Declare request IDs, root/child ID domains, and immutable preconditions.
2. Distinguish deterministic pre-send validation from post-send uncertainty.
3. Reserve action and command identities before sending external commands.
4. Define authoritative effect predicates without title/text guessing.
5. Return only `completed` or `reconciliation_pending` after a sent command.
6. Return/apply an authoritative snapshot for completed, pending, and conflict states.
7. Add tests for 4xx pre-send rejection, sent timeout, replay, stale revision, and
   duplicate user-visible child content.

**Explicit bans:** deriving action fingerprints from `now`, current durations, current
revisions, or mutable descriptions — matches what `actionJournalRepository.ts`'s
`requestFingerprint` model actually enforces.

**Sources of truth:** `task_action_journal` and `task_action_commands`, verified real at
`backend/src/db/schema.sql:88` and `:102`.

**Sample endpoint walkthrough:** `TodoistChecklistService.mutate()`
(`backend/src/services/todoistChecklistService.ts:56`) walked through against all 7
checklist items, replacing the backlog's vague "use the existing checklist mutation
flow" with a concrete, verified real function.

## Non-Goals

- No test-case template or test harness work for P0-3 — that's backlog task P1-4's job.
- No preflight-script references anywhere in P0-2's content — that tooling was rejected
  during P0-1's brainstorming, not merely deferred.
- No changes to any other backlog task (P1-1 through P2-4).
- No claim of having live-invoked either skill in Codex, or the Claude Code skill from a
  fresh session — verification is limited to structure, format, and content accuracy.

## Acceptance Criteria

- All 6 files for P0-2 exist, are pairwise identical (Codex copy == Claude Code copy for
  each of SKILL.md, task-prompt-template.md, review-prompt-template.md), and each file
  notes its twin's path.
- P0-2's SKILL.md contains all 6 adapted rules, with rule 1 reflecting P0-1's actual
  (docs-only) delivered scope rather than the backlog's original preflight-script text.
- P0-2's dry-run example cites the real P0-1 handoff and review cycle, not a synthetic
  scenario.
- All 3 files for P0-3 exist (2 SKILL.md copies + 1 shared protocol note), the two
  SKILL.md copies are identical and each notes its twin's path.
- P0-3's SKILL.md/protocol note contains the unmodified 7-item checklist, the fingerprint
  bans, correctly names `task_action_journal`/`task_action_commands`, and walks through
  `TodoistChecklistService.mutate()` specifically.
- Neither skill's content mentions the P0-1 preflight script or a test-helper artifact
  under `backend/test/helpers/`.
