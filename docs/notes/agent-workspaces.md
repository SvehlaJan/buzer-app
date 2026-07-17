# Agent Workspaces

Date: 2026-07-17

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

Naming convention this doc recommends going forward, already followed by the most
directly related recent worktrees (`feature/recurring-checklists` and its `-task2`/
`-task3` splits) — other branches active during the same period used other prefixes
(`feat/android-vps-enforcement`, `codex/vps-deployment`, `codex/android-overlay-boot-
recovery`) that predate this convention:

- Branch: `feature/<topic>` for a standalone task, or `feature/<topic>-taskN` for a
  subtask split off a larger effort.
- Worktree path: `.worktrees/<topic>` or `.worktrees/<topic>-taskN`, matching the branch's
  topic segment (not any earlier prefix a branch might already carry).

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
reviews each child worktree once its task is done. What happens next depends on whether
the branch still has work worth folding in:

**Still-current work gets a real merge**, an ordinary merge commit carrying the actual
diff:

```text
f21f8a9 merge: consolidate recurring checklist implementation
```

**Superseded or already-redundant branches still get recorded, not silently dropped** —
via a history-only merge (`git merge -s ours <branch>`) that preserves the branch in
history without reintroducing obsolete code:

```text
merge: record recurring checklist planning branch
merge: record recurring checklist task2 branch
merge: record superseded VPS deployment branch
merge: record superseded overlay recovery branch
```

Both kinds of commit are the integration session's record that it reviewed that specific
child branch before deciding whether to fold it in or close it out. A closing
`docs: record master integration` (or equivalent) commit is a good place to note anything
that needed cross-branch reconciliation — see `docs/handoffs/2026-07-17-master-integration.md`
for the worked example this section is drawn from.

## Cleanup

Once a branch is merged, remove its worktree so `git worktree list` only shows what's
actually in flight:

```bash
git worktree remove .worktrees/<topic>
```

Stale registered worktrees make it harder for the next session to tell what's still
active — don't leave them behind after a merge.
