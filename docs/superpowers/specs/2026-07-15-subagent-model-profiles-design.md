# Subagent Model Profiles Design

## Goal

Use a lower-cost Codex model for bounded, mechanical implementation work without lowering the
quality bar for cross-cutting implementation, orchestration, or final review.

## Considered Approaches

1. Prompt-only model steering is easy but does not durably select a model and the current
   collaboration tool does not expose a model argument.
2. Lowering the repository or global default would reduce cost broadly, but would also weaken the
   root coordinator and demanding reviewers.
3. A project-scoped custom agent keeps the cheaper choice explicit and opt-in. This is the selected
   approach.

## Design

Register one `implementer_light` custom agent in `.codex/config.toml`, backed by
`.codex/agents/implementer-light.toml`. Pin it to `gpt-5.6-terra` with medium reasoning effort.

Use the profile only for tasks that are already fully specified, localized, low-risk, and protected
by deterministic tests. Examples include mechanical DTO mappings, fixtures, and narrow test-first
changes. Do not use it for schema design, migrations, cross-device correctness, concurrency,
security-sensitive changes, ambiguous API semantics, or final quality approval.

The profile must follow `AGENTS.md`, implement only the assigned scope, use test-driven development,
and return control to the root agent when it discovers architectural ambiguity or scope expansion.
The root agent remains responsible for deciding whether each task is viable for this profile.

## Validation

- Confirm the installed Codex model catalog contains `gpt-5.6-terra` and medium effort.
- Run Codex with strict configuration parsing from the worktree.
- Inspect the committed TOML and keep Task 2 product files untouched.

The new configuration is expected to load for spawned sessions in a new Codex task. The current
task does not claim that an already-running root session hot-reloads custom agent definitions.
