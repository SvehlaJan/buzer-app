# Handoffs

Agents write one handoff per meaningful work session. Handoffs are part of the project memory and should be committed with the related work whenever possible.

Use this template:

```markdown
# Handoff: <Task Or Session Name>

Date: YYYY-MM-DD
Agent: <name/model if known>
Plan: `docs/superpowers/plans/<plan-file>.md`

## Scope

- What this session was supposed to do.

## Completed

- Files changed.
- Plan checkboxes completed.
- Commits created.

## Verification

- Command: `<exact command>`
- Result: PASS/FAIL/SKIPPED
- Evidence: <important output or reason>

## Decisions And Assumptions

- Any assumptions made.
- Any behavior clarified.

## Open Issues

- Bugs, failing tests, incomplete work, or environment blockers.

## Next Step

- The single most useful next action for the next agent.
```

