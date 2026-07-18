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
