# Agent Workspaces Convention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document the isolated-worktree convention (backlog task P0-1, scoped to
documentation-only per `docs/superpowers/specs/2026-07-17-agent-workspaces-design.md`) in
a new `docs/notes/agent-workspaces.md`, and point to it from `AGENTS.md`.

**Architecture:** Two documentation edits, no code, no tooling, no tests. Every claim in
`docs/notes/agent-workspaces.md` is grounded in this repo's real history (the six
worktrees consolidated during brainstorming, the actual `merge: record <branch> branch`
commit pattern already on `master`) rather than a hypothetical process.

**Tech Stack:** Markdown only.

---

## File Structure

- Create: `docs/notes/agent-workspaces.md` — the convention doc (why, when, manifest
  shape, integration/merge pattern, cleanup).
- Modify: `AGENTS.md` — two short insertions pointing at the new doc; no other changes.

Both files are independent of each other in content, but Task 2 references the filename
created in Task 1, so do Task 1 first.

---

### Task 1: Create the convention doc

**Files:**
- Create: `docs/notes/agent-workspaces.md`

- [ ] **Step 1: Write the file**

```markdown
# Agent Workspaces

Convention for isolating independently-assignable tasks into separate git worktrees, and
how integration/merge-back works once a task is done.

## Why

Shared-worktree edits cause overlapping patches, accidental reverts, and ambiguous
ownership when more than one agent (or session) works in the same checkout at once.

This is not hypothetical: while brainstorming this convention on 2026-07-17, six git
worktrees existed concurrently for closely related recurring-checklist subtasks —
`recurring-checklists`, `recurring-checklists-task2`, `recurring-checklists-task3`,
`android-vps-enforcement`, `vps-deployment`, plus the primary checkout — and were
consolidated into `master` mid-session via a batch of merge commits. Isolating each
subtask's edits to its own worktree is what made that consolidation tractable.

## When To Create An Isolated Worktree

Create a dedicated worktree for any task or subtask that can be assigned independently —
i.e., its file changes don't need to interleave commit-by-commit with another in-flight
task. Use the primary checkout only for integration work: reviewing, merging, and
resolving cross-cutting concerns across finished child branches.

Naming convention already in use in this repo:

- Branch: `feature/<topic>` for a standalone task, or `feature/<topic>-taskN` for a
  subtask split off a larger effort.
- Worktree path: `.worktrees/<topic>` or `.worktrees/<topic>-taskN`, matching the branch
  name.

```bash
git worktree add .worktrees/my-topic -b feature/my-topic
```

## The Manifest Shape (Informal)

No tool parses or validates this today — it's a convention for a human or agent to fill in
by hand and keep locally in the worktree, gitignored, never committed. Its purpose is to
give a quick, shared vocabulary for "what am I touching" when another session (or a future
you) needs to reason about a worktree's scope without reading its full diff.

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
allows_shared_with: []
```

- `owns`: files/globs this task expects to modify.
- `may_read`: files/globs this task may read for context but shouldn't change.
- `must_not_touch`: an explicit call-out of paths that would be surprising or unsafe for
  this task to touch, even indirectly.
- `allows_shared_with`: other task IDs explicitly allowed to also own an overlapping path,
  for the rare case where two tasks legitimately share a file.

Keep it next to your working notes in the worktree (for example
`.agents/tasks/<task-id>.yaml`); add `.agents/` to your local git excludes if you want it
out of `git status` entirely.

## Integration/Merge Review

A parent/integration session — normally one running in the primary checkout on `master` —
reviews each child worktree once its task is done, merges it, and records the merge with a
descriptive commit before removing the worktree. This is the actual pattern already used
in this repo; from `master`'s own history:

```text
merge: record recurring checklist planning branch
merge: record recurring checklist task2 branch
merge: record superseded VPS deployment branch
merge: record superseded overlay recovery branch
docs: record master integration
```

Each `merge: record <branch> branch` commit is the integration session's record that it
reviewed that specific child branch's changes before folding them in. A closing
`docs: record master integration` (or equivalent) commit is a good place to note anything
that needed cross-branch reconciliation.

## Cleanup

Once a branch is merged, remove its worktree so `git worktree list` only shows what's
actually in flight:

```bash
git worktree remove .worktrees/<topic>
```

Stale registered worktrees make it harder for the next session to tell what's still
active — don't leave them behind after a merge.
```

- [ ] **Step 2: Verify all five sections are present**

Run: `grep -c "^## " docs/notes/agent-workspaces.md`
Expected: `5`

- [ ] **Step 3: Commit**

```bash
git add docs/notes/agent-workspaces.md
git commit -m "docs: add agent workspaces convention"
```

---

### Task 2: Point AGENTS.md at the new convention

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Add a worktree-check step to the Required Start-Of-Session Routine**

Current text (lines 10-20):

```markdown
Before changing code, every agent must:

1. Read this `AGENTS.md`.
2. Read the approved spec.
3. Read the plan file for the task being executed.
4. Read recent handoffs in `docs/handoffs/`.
5. Run `git status --short`.
6. Identify files already changed by others and avoid overwriting them.
7. State in the handoff which plan/task/steps were attempted.
```

Replace with:

```markdown
Before changing code, every agent must:

1. Read this `AGENTS.md`.
2. Read the approved spec.
3. Read the plan file for the task being executed.
4. Read recent handoffs in `docs/handoffs/`.
5. Run `git worktree list`. For independently-assignable subtasks, work in a dedicated
   worktree per `docs/notes/agent-workspaces.md` rather than the primary checkout.
6. Run `git status --short`.
7. Identify files already changed by others and avoid overwriting them.
8. State in the handoff which plan/task/steps were attempted.
```

- [ ] **Step 2: Add a worktree bullet to Engineering Standards**

Current text (within the `## Engineering Standards` section):

```markdown
- Do not overwrite or revert files changed by another agent unless explicitly instructed.
```

Replace with:

```markdown
- Do not overwrite or revert files changed by another agent unless explicitly instructed.
- Independently-assignable tasks run in a dedicated git worktree, not the primary
  checkout — see `docs/notes/agent-workspaces.md` for the manifest convention and
  integration/merge-back pattern.
```

- [ ] **Step 3: Verify the diff contains only these two additions**

Run: `git diff AGENTS.md`
Expected: two added lines/blocks matching Steps 1 and 2 above; nothing else changed.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "docs: point AGENTS.md at agent workspaces convention"
```

---

## Plan Self-Review Notes

- **Spec coverage:** Both spec deliverables (the note's five sections; both AGENTS.md
  insertions) map to Task 1 Step 1 and Task 2 Steps 1-2 respectively. Non-goals (no schema
  file, no preflight script, no retrofit) require no tasks, correctly.
- **Placeholder scan:** No TBD/TODO; both files' full text is written out above verbatim.
- **Type consistency:** N/A — no code, no function/type names to keep consistent across
  tasks.
