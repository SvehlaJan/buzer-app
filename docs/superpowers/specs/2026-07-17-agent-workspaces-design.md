# Agent Workspaces Convention Design

Date: 2026-07-17
Status: approved design, pending implementation plan

## Context And Goal

`docs/notes/2026-07-17-agent-delivery-backlog.md` proposes task P0-1, "Enforce isolated
agent workspaces," in response to shared-worktree edits causing overlapping patches,
accidental reverts, and ambiguous ownership during the recurring-checklists/atomic-
reconciliation work. The backlog's original scope was a manifest schema, a
`scripts/agent-task-preflight.sh` enforcement script, and a `.agents/tasks/<task-id>.yaml`
convention.

Cross-checking against the repo at brainstorming time confirmed none of that tooling
exists (no `.agents/`, no `tools/agent-workspaces/`, no preflight script). The same
brainstorming session also produced direct, real-time evidence for the "why": six git
worktrees (`recurring-checklists`, `-task2`, `-task3`, `android-vps-enforcement`,
`vps-deployment`, plus the primary checkout) existed concurrently for closely related
subtasks, and were consolidated into master mid-conversation via a sequence of
`merge: record <branch> branch` commits.

Decisions made during brainstorming that narrow this from the original backlog scope:

- **Drop all enforcement tooling.** No manifest schema, no preflight script, no
  `.agents/tasks/` directory. The user will apply worktree discipline manually. This task
  is documentation-only.
- **No retrofit.** Past/closed worktrees are not revisited; the convention applies to work
  started after this doc exists.
- **Written directly on master, no dedicated worktree.** The deliverable is one new doc
  file plus a small addition to an existing one — low collision risk, so the isolation
  concern this task itself describes doesn't meaningfully apply to producing it.
- **The manifest YAML shape is illustrative only.** It's documented as a template an agent
  fills in by hand and keeps locally in its own worktree (gitignored, never committed) as
  shared vocabulary for "what am I touching" — not a file any tooling parses or validates.
- **Scope is P0-1 only.** The backlog's other nine tasks (P0-2 through P2-4) are untouched
  and remain in the backlog for separate, later brainstorming/spec cycles.

## Deliverable 1: `docs/notes/agent-workspaces.md`

A new note, following this repo's existing `docs/notes/` conventions, with five sections:

1. **Why** — shared-worktree edits cause overlapping patches, accidental reverts, and
   ambiguous ownership; state the six-worktree example above as concrete, in-repo evidence
   rather than a hypothetical.
2. **When to create an isolated worktree** — one per independently-assignable task or
   subtask. Document the naming convention already in use: branch `feature/<topic>` or
   `feature/<topic>-taskN`, worktree at `.worktrees/<topic>[-taskN]`.
3. **The manifest shape (informal, not validated by any tool)** — reproduce the backlog's
   YAML template (`task`, `branch`, `owns`, `may_read`, `must_not_touch`,
   `allows_shared_with`) as an example to fill in by hand; state explicitly that it is kept
   locally and gitignored, never committed, and that no script reads it today.
4. **Integration/merge review** — document the pattern already visible in this repo's own
   history, using the real commit sequence from today's master log as the worked example
   (`merge: record recurring checklist planning branch`, `merge: record recurring
   checklist task2 branch`, `merge: record superseded VPS deployment branch`, `merge:
   record superseded overlay recovery branch`, `docs: record master integration`): a
   parent/integration session reviews each child branch, merges it with a
   `merge: record <branch> branch`-style commit, then removes the worktree.
5. **Cleanup** — `git worktree remove <path>` once merged; don't leave stale worktrees
   registered.

## Deliverable 2: `AGENTS.md` additions

Two small insertions into the existing 159-line file — no restructuring, no rewording of
unrelated sections.

- **Required Start-Of-Session Routine** — new step after "Read recent handoffs in
  `docs/handoffs/`": run `git worktree list`, and for independently-assignable subtasks,
  work in a dedicated worktree per `docs/notes/agent-workspaces.md` rather than the
  primary checkout.
- **Engineering Standards** — new bullet next to the existing "Do not overwrite or revert
  files changed by another agent" rule: independently-assignable tasks run in a dedicated
  git worktree, not the primary checkout, pointing to `docs/notes/agent-workspaces.md` for
  the manifest convention and integration/merge-back pattern.

## Non-Goals

- No `tools/agent-workspaces/task-manifest.schema.json` or any other schema file.
- No `scripts/agent-task-preflight.sh` or any executable enforcement of ownership.
- No retrofitting manifests onto existing or already-closed worktrees.
- No changes to backlog tasks P0-2 through P2-4 — each gets its own brainstorming pass
  when picked up.

## Acceptance Criteria

- `docs/notes/agent-workspaces.md` exists and covers all five sections above.
- `AGENTS.md` contains both additions described above and is otherwise unchanged.
- A new agent reading both files understands when to start an isolated worktree, what a
  manifest informally declares, and how integration/merge-back already works in this repo
  — grounded in this repo's real history, not a hypothetical process.
