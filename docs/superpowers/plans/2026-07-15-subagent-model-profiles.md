# Subagent Model Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in, lower-cost implementation agent for bounded mechanical work.

**Architecture:** Register a project-scoped custom agent rather than changing the repository-wide
or user-wide default model. Keep selection explicit so the root coordinator retains responsibility
for task/model fit.

**Tech Stack:** Codex project configuration in TOML.

---

### Task 1: Register And Validate The Light Implementer

**Files:**
- Create: `.codex/config.toml`
- Create: `.codex/agents/implementer-light.toml`
- Create: `docs/handoffs/2026-07-15-subagent-model-profiles.md`
- Modify: `docs/superpowers/plans/2026-07-15-subagent-model-profiles.md`

- [ ] Create `.codex/config.toml` with an `agents.implementer_light` registration pointing at
  `agents/implementer-light.toml`.
- [ ] Create the agent file with `name = "implementer_light"`, `model = "gpt-5.6-terra"`,
  `model_reasoning_effort = "medium"`, narrow applicability, TDD instructions, and an explicit
  ambiguity hand-back rule.
- [ ] Run `codex debug models | jq ...` and assert the selected model and effort are available.
- [ ] Run `codex --strict-config doctor --summary --no-color` from the worktree. Expected: the
  project configuration parses without an unknown-field or TOML error.
- [ ] Confirm `git diff --name-only` contains only the configuration design, plan, handoff, and
  `.codex` files.
- [ ] Write the handoff with exact verification evidence and mark every completed checkbox.
- [ ] Commit with `git commit -m "chore: add light implementation agent"`.
