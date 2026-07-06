# Buzer UX Brief

Date: 2026-07-06 (Phase 0 of the UI overhaul; see the backlog's "UI Overhaul (AI-Assisted)"
section for process and tooling decisions). Companion artifact: `docs/design/screens/`
(screenshot inventory of every current screen/state, fixture data).

## What Buzer Is

A personal Todoist commitment companion for one user. Its single job: make committed tasks
actually happen by demanding explicit commitment actions ("I'm starting", "Done",
"Postpone", "Extend") at the moments that matter, with WhatsApp escalation as the social
backstop. The UI is not a task manager — Todoist is. Buzer's screens exist for seconds-long,
high-stakes interactions.

## Design Principles (enforcement-first)

1. **Glanceable at arm's length.** Every screen's core message must land from a meter away:
   what task, what's expected of me, how much time. Large type for the one fact that
   matters; secondary detail visually quiet.
2. **One dominant action per blocker.** Blockers exist to extract a single commitment. The
   honest action ("I'm starting" / "Done") is the visually dominant, easiest-to-hit control;
   escape hatches (postpone, extend, reschedule) are present but visually subordinate —
   never hidden, never equal.
3. **Morning-proof.** Morning Review is used half-awake: maximum contrast, generous touch
   targets, minimal reading, zero ambiguity about what tapping a button commits to.
4. **Calm but firm tone.** Not gamified, not cute, not punishing. The app is a referee, not
   a cheerleader: neutral statements of commitments and consequences ("Escalates via
   WhatsApp after 2 postponements" is fine; confetti is not).
5. **Time is the primary dimension.** Blocks (start → end) are the mental model everywhere:
   agenda rows, blocker headers, pickers ("Start at" + duration + computed "Ends"). Render
   times humanely (Today 14:30, in 25 min) — never raw ISO strings (current Home does this;
   it must die in the redesign).
6. **State must be legible.** planned / in-progress / done / invalid / overdue each need an
   instantly distinguishable visual treatment (color role + icon, not color alone).
7. **Trust surfaces are part of UX.** Errors and degraded states (backend unreachable,
   permission lost) must be prominent, plain-language, and actionable — a discipline app
   that fails silently loses its authority (see the health-surface backlog item).

## Constraints (owner decisions, 2026-07-06)

- Standard Material 3 components only; no custom component library. M3 Expressive is
  optional late polish behind its experimental opt-in.
- Dark AND light themes, both first-class. Dynamic color: still open (decide in Phase 2).
- Jetpack Compose (Material3), single module `client/androidApp`, existing `ui/theme`
  package (`Spacing` tokens, `BuzerTheme` currently a passthrough to be replaced by real
  color/type/shape schemes from Material Theme Builder).
- Semantics: due = start of block; enforcement deadline = start + duration ("Start at"
  language everywhere; see `docs/decisions/2026-07-05-todoist-native-due-semantics.md`).

## Per-Screen Jobs

- **Home ("today's agenda")** — answer "what does my day look like and where am I right
  now?" in one glance: today's committed blocks in start order with state; the in-progress
  block visually loudest with remaining time; invalid-task count as a call to action
  (opens Morning Review); quiet aggregate footer. Also the future host of the health
  surface. Kills: raw ISO timestamps, unexplained counters, the reflowing refresh indicator
  (friction item 3).
- **Start blocker (activity + overlay)** — "your block started; commit." Task title + block
  window dominate; primary: "I'm starting"; subordinate: postpone (with visible escalation
  consequence count).
- **Deadline blocker (+ reschedule form)** — "the block ended; account for it." Primary:
  "Done"; subordinate: extend / reschedule. Reschedule form uses the same picker vocabulary
  as Add Task.
- **Morning Review (+ repair form)** — triage invalid tasks fast, half-awake: one card per
  task, why it's invalid in plain words, Repair (start + duration wheels) or Snooze. The
  owner has called the current screen the worst offender; it gets the deepest rework.
- **Add Task** — capture a commitment in under 15 seconds: title, project, start wheel,
  duration wheel, computed "Ends", one save button. Validation inline; system failures via
  dialog (established 2026-07-05).
- **Overlay variant** — same hierarchy as its blocker in a compact surface; visually
  consistent with the full-screen sibling.
- **Widget** (later phase) — today's next/current block + state at a glance.

## Non-Goals

- No onboarding/marketing surfaces (single user).
- No new features smuggled in via redesign (agenda list is the one co-developed feature).
- No custom design system beyond M3 theming tokens.
