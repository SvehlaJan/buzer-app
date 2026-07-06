# DESIGN.md — Buzer

## Product

Buzer is a personal Android app that enforces task commitments. It is not a todo app —
tasks come from Todoist. Buzer's screens exist for seconds-long, high-stakes interactions:
committing to start a task ("I'm starting"), accounting for a finished block ("Done"),
and repairing invalid commitments each morning. Single user, Android-first, built with
Jetpack Compose and standard Material 3 components only.

## Personality & Tone

- A referee, not a cheerleader. Calm, firm, neutral.
- States commitments and consequences plainly ("Escalates via WhatsApp after 2
  postponements"). Never celebratory, never punishing. No gamification, no illustrations,
  no mascots, no confetti.
- Copy is short, verb-first, and unambiguous about what tapping a button commits to.

## Core Interaction Principles

1. Glanceable at arm's length: one dominant fact and one dominant action per screen.
2. The honest action ("I'm starting", "Done") is always the visually dominant control;
   escape hatches (postpone, extend, snooze) are present but visually subordinate — never
   hidden, never equal.
3. Morning-proof: the review screen is used half-awake — maximum contrast, generous touch
   targets, minimal reading.
4. Time is the core dimension: tasks are blocks with a start time and duration. Times are
   rendered humanely ("14:30", "in 25 min", "ends 15:00"), never as raw timestamps.
5. Trust surfaces are first-class: errors and degraded states (backend unreachable,
   permission lost) are prominent, plain-language, and actionable.

## Color

- Dark theme first; a light theme sibling of equal quality is required.
- Neutral, quiet surfaces; a SINGLE saturated accent reserved for the primary action and
  the in-progress state. Semantic colors for states: upcoming (neutral), in progress
  (accent), done (muted success), overdue/invalid (warning/error roles).
- State must never be encoded by color alone — always pair with an icon or label.
- Accent seed is being chosen between three directions: (A) utilitarian near-monochrome
  with one sharp accent; (B) calm deep blue-grey slate with muted accents; (C) near-black
  high-contrast with bold vivid warning colors. Generations should commit fully to
  whichever direction the prompt names.

## Typography

- Standard Material 3 type scale. Display/headline sizes are reserved for the one fact
  that matters (task title on blockers, remaining time on the in-progress block).
- Numbers and times deserve emphasis: block windows and countdowns are the most-read
  content in the app.

## Shape & Spacing

- Material 3 shape system; corner radius family may vary by direction (sharp for
  direction A, soft/large for direction B).
- Spacing scale: 4 / 8 / 16 / 24 / 32 dp. Roomy by default; density is acceptable only in
  the agenda list.

## Components

- Standard Material 3 components only: buttons, cards, list items, dialogs, sheets,
  banners, wheel-style pickers. No custom component library.
- Recurring patterns:
  - Agenda row: title + block window + state chip/icon; the in-progress row is the hero
    with remaining time.
  - Blocker layout: huge title, block window beneath, one dominant filled button, one
    quiet subordinate action with its consequence stated inline in small text.
  - Triage card (morning review): title, problem in plain words, Repair (primary) +
    Snooze (quiet), progress indicator ("2 of 3 left").
  - Attention strip: compact tappable banner for invalid-task count and health warnings.
- Forms use wheel pickers for start time and duration, with a computed "Ends 15:00" line.

## Motion

- Restrained. Standard Material motion for transitions; no decorative animation.
  Anything that moves must communicate state change.

## Accessibility

- Contrast comfortably above WCAG AA in both themes.
- Touch targets 48dp minimum; blocker primary actions substantially larger.
- All interactive elements labeled; state never conveyed by color alone.

## Don't

- No bottom navigation (one main screen plus modal/blocker surfaces).
- No raw ISO timestamps anywhere.
- No equal-weight action pairs on blockers.
- No feature additions during redesign — same jobs, better clothes.
