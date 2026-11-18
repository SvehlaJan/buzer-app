# Handoff: Voice Quick Add research and planning (2026-11-18)

Plan: `docs/superpowers/plans/2026-11-18-voice-quick-add.md` — no tasks started (all boxes open).
Spec: `docs/superpowers/specs/2026-11-18-voice-quick-add-design.md`.
Decision: `docs/decisions/2026-11-18-voice-quick-add.md`.

## What this session did

Research and brainstorming with the owner (Claude Code cloud session, 2026-11-15 → 2026-11-18),
then wrote the decision, spec and plan. No code changed.

Owner decisions captured:
- English only for the MVP; Slovak later.
- Auto-submit only on high confidence, with a 5-second Undo.
- Backend on the VPS does the AI work (key stays on the VPS).
- Dictation via Gboard Rambler in a plain text field (no public API to start Rambler).
- AI guesses duration and project; asks back when confidence is low. Thresholds: project ≥ 0.80,
  duration ≥ 0.60 (Gemini Decision 1 calibrated probabilities).
- Repurpose the unused `AddTaskWidgetProvider`.
- Backlog: time-slot suggestions, Slovak, learning from history (continuous improvement),
  assignee extraction, full-form prefill, ML Kit one-tap recording, Gemini Nano on device.
- Privacy and security are hard requirements (spec section "Privacy and security").

## Files changed

- `docs/decisions/2026-11-18-voice-quick-add.md` (new)
- `docs/superpowers/specs/2026-11-18-voice-quick-add-design.md` (new)
- `docs/superpowers/plans/2026-11-18-voice-quick-add.md` (new)
- `docs/handoffs/2026-11-18-voice-quick-add-planning.md` (new)
- `AGENTS.md` (one "In scope" bullet)

## Verification

- Command: `grep -rn "AddTaskWidget\|add_task_widget\|OPEN_ADD_TASK" client --include=*.kt --include=*.xml`
- Result: PASS
- Evidence: only `MainActivity.kt`, the widget provider and its resources reference the widget; no
  test does. Recorded in the plan's "Existing tests" section.

- Command: tests
- Result: SKIPPED
- Evidence: documentation-only change.

## Known gaps and assumptions

- The session's network could not reach `ai.google.dev`, `developers.google.com` or
  `aistudio.google.com`. Facts about Gemini Decision 1 (multiple choice ≤ 255 options, calibrated
  per-option probabilities, `v1beta`, ~$0.10 / 1M input tokens) come from Google's announcement
  blog and secondary coverage. Plan Task 1 is a gate that verifies them before any code.
- The Gemini extraction model id is deliberately not pinned; Task 1 picks it.
- Assumed `resolveDueInstant(dueDatetime, timeZone)` accepts a local `YYYY-MM-DDTHH:mm`; Task 4
  checks this before use.
- Sentry Android has `isAttachScreenshot` and `isAttachViewHierarchy` on; the plan adds callbacks
  to skip them while `QuickAddActivity` is visible.

## Next recommended step

Execute plan Task 1 on the dev machine (needs a Gemini API key with billing enabled and network
access to Google's docs), then backend Tasks 2–4 and client Tasks 5–7 in parallel worktrees.
