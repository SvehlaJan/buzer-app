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
