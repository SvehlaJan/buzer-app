# Todoist Commitment Companion

Personal Todoist companion that enforces strict task commitments on Android.

## MVP

- Android-first Kotlin Multiplatform / Compose Multiplatform client.
- Android-native exact alarms, blocker screens, widget, boot rescheduling, and permission health.
- Node.js/TypeScript backend on a VPS.
- SQLite database.
- Todoist polling every 5 minutes.
- `whatsapp-web.js` escalation after repeated start postponement.

## Source Of Truth

- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Implementation plan index: `docs/superpowers/plans/2026-05-08-implementation-plan-index.md`
- Agent rules: `AGENTS.md`

## Repository Layout

- `backend/`: Node.js/TypeScript backend.
- `client/`: Kotlin Multiplatform / Compose Multiplatform Android client.
- `docs/handoffs/`: agent handoff documents.
- `docs/decisions/`: durable architecture and product decisions.
- `docs/notes/`: technical notes and API contracts.
- `docs/superpowers/`: specs and implementation plans.

## Development

Follow `AGENTS.md` before changing files. Each implementation session must leave a handoff in `docs/handoffs/`.
