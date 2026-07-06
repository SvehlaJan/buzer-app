# Stitch Kit — Phase 1 Direction Exploration

Date: 2026-07-06. Inputs: `docs/design/screens/` (current-state screenshots) +
`docs/design/ux-brief.md`. Tool: Google Stitch (stitch.withgoogle.com, free via Google
Labs), **Redesign mode** with screenshot upload.

## How to run the session (~10 minutes per screen)

(Updated 2026-07-06 against the live Stitch UI — mode names below match what the app
actually shows; the earlier Standard/Experimental quota framing was stale.)

1. **Seed the project once:** "Start with your design" → paste `docs/design/DESIGN.md`
   (written for exactly this). This gives every generation the product's design DNA
   without re-pasting context.
2. Keep **App** selected and the **Redesign** mode (Nano Banana Pro — "Redesign existing
   apps or sites... Attach a screenshot"). Use **Thinking with 3.1 Pro** later to refine
   the winning direction; **3 Flash** is handy for fast iterations and HTML export toward
   coding agents.
3. For each screen below: attach ONLY that screen's screenshot(s), paste the screen
   prompt, append ONE direction suffix (A/B/C). Generate. Repeat with the other suffixes —
   3 directions per screen. Attachment rule: one SCREEN per generation, but multiple
   STATES of that screen are fine and helpful (12+13 for Morning Review; 03+07 for Add
   Task). If a multi-state generation comes back confused, retry with just the primary
   state and request the secondary state as a follow-up edit. Never attach the screenshots
   README, and keep `14-overlay` out unless prompting specifically for the overlay variant.
4. React, don't polish: you're choosing a direction, not approving pixels. Save anything
   you like into `docs/design/directions/` (any filenames), and note which direction won
   and why — one sentence is enough.

## Context block (only needed if you skip the DESIGN.md seeding)

> This is "Buzer", a personal Android app that enforces task commitments. It is NOT a todo
> app — tasks come from Todoist. Buzer's screens exist for seconds-long, high-stakes
> interactions: committing to start a task, accounting for a finished block, repairing
> invalid tasks. Design language: standard Material 3 components only (Android), designed
> for a dark theme first with a light variant. Tone: calm but firm — a referee, not a
> cheerleader; no gamification, no illustrations, no confetti. Everything must be glanceable
> at arm's length: one dominant fact and one dominant action per screen, generous touch
> targets, high contrast. Time is the core dimension: tasks are "blocks" with a start time
> and duration; render times humanely ("14:30", "in 25 min", "ends 15:00"), never as raw
> timestamps.

## Screen 1: Home — "Today's agenda" (upload `01-home.png`)

> Redesign this home screen. The current version shows raw counters and an ISO timestamp —
> replace that entirely with a "today's agenda" concept: a vertical list of today's
> committed blocks in start order. Each row: task title, block window (e.g. "14:30–15:00"),
> and a clear state (upcoming / in progress / done / overdue) distinguishable by icon and
> color role, not color alone. The IN-PROGRESS block is the hero: visually loudest element
> on screen, showing remaining time prominently (e.g. "23 min left"). Above or below the
> list: a compact attention strip for problems — "3 invalid tasks — review" as a tappable
> call-to-action, and space for future health warnings (backend unreachable, permission
> lost) in the same strip. Keep two persistent actions reachable with a thumb: "Add task"
> (primary) and "Morning review" (secondary). No bottom navigation bar — this app has one
> main screen. Material 3, dark theme.

## Screen 2: Morning Review (upload `12-morning-review.png` and `13-morning-review-repair.png`)

> Redesign this morning triage screen. It is used half-awake, first thing in the morning:
> maximum contrast, minimal reading, zero ambiguity. Content: a short headline stating the
> job ("3 tasks need attention"), then one card per invalid task: task title, the problem in
> plain words ("No start time set" instead of "missing_due_datetime"), and exactly two
> actions — "Repair" (primary) and "Snooze 5 min" (subordinate, visually quiet). The repair
> action expands the card (or opens a sheet) with two wheel pickers: start time and
> duration, plus a computed "Ends 15:00" line and a single "Save" button. Progress feedback:
> show how many remain as cards are resolved ("2 of 3 left"). When everything is repaired,
> a calm all-clear state ("All commitments are valid") with a single Done button. Material
> 3, dark theme, large touch targets.

## Screen 3 (optional third): Start blocker (upload `09-start-blocker.png`)

> Redesign this full-screen interruption. It appears over the lock screen when a committed
> block starts. It must communicate in one second: WHAT task is starting and WHAT is
> expected. Layout: task title very large, block window and duration beneath it ("14:30 —
> 30 min"), then one dominant button: "I'm starting" — the biggest, most inviting element
> on the screen. Below it, visually subordinate: "Postpone 5 min" with its consequence
> stated inline in small text ("escalates via WhatsApp after 2 postponements"). No other
> content, no navigation, no dismiss affordance. Material 3, dark theme, designed to be
> readable from a meter away.

## Direction suffixes (append exactly one per generation)

- **Direction A — utilitarian mono:** near-monochrome dark surface, one saturated accent
  color used ONLY for the primary action and in-progress state, dense information, sharp
  M3 shapes (small corner radii).
- **Direction B — calm slate:** deep blue-grey surfaces, soft large-radius M3 shapes,
  roomy spacing, muted state colors, the app equivalent of a quiet study.
- **Direction C — high-energy contrast:** near-black surface, bold display typography for
  times and titles, vivid warning colors for overdue/invalid, maximum glanceability at the
  cost of density.

## What to bring back

1. The winning direction per screen (screenshots into `docs/design/directions/`), plus one
   sentence on why.
2. The accent/seed color(s) of the winner if identifiable — otherwise just the screenshots;
   seed color gets extracted in Phase 2 and fed to Material Theme Builder
   (m3.material.io/theme-builder) for the Kotlin theme export.
3. Any layout ideas Stitch produced that you want carried into the implementation even from
   losing directions.

Phase 2 then converts the winner into `BuzerTheme` tokens (dark + light), and Phase 3
implements screen by screen, starting with Home/agenda.
