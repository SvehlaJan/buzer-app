# Todoist Commitment Companion Implementation Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement these plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Coordinate implementation of the Todoist commitment companion MVP across multiple agents without overlapping ownership.

**Architecture:** The MVP is split into a Node.js/TypeScript SQLite backend and a Kotlin Multiplatform / Compose Multiplatform Android client with Android-native enforcement. Todoist remains the visible task source; the backend owns enforcement state; Android owns local alarm timing and blocker presentation.

**Tech Stack:** Node.js/TypeScript, SQLite, Todoist Sync API, `whatsapp-web.js`, Kotlin Multiplatform, Compose Multiplatform, Android platform APIs.

---

## Source Documents

- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Agent rules: `AGENTS.md`
- Handoffs: `docs/handoffs/README.md`
- Decisions: `docs/decisions/README.md`
- Notes: `docs/notes/README.md`

## Plan Dependency Graph

```text
00 repo foundation
  -> 01 backend core
      -> 02 backend Todoist/actions API
      -> 03 WhatsApp escalation worker
  -> 04 KMP shared Android shell
      -> 05 Android enforcement

02 and 03 can run in parallel after 01.
04 can run in parallel with 01 after 00.
05 starts after 04 and needs API contracts from 01/02.
```

## Plan Files

1. `docs/superpowers/plans/2026-05-08-00-repo-foundation-and-contracts.md`
   - Creates repo scaffolding, ignore rules, root docs, shared API contract document, and project conventions.

2. `docs/superpowers/plans/2026-05-08-01-backend-core-sqlite-state-machine.md`
   - Creates backend project, SQLite migrations, repositories, task validation, lifecycle state machine, and tests.

3. `docs/superpowers/plans/2026-05-08-02-backend-todoist-sync-and-rest-api.md`
   - Implements Todoist adapter, polling sync worker, REST API endpoints, pending action handling, and mocked integration tests.

4. `docs/superpowers/plans/2026-05-08-03-whatsapp-escalation-worker.md`
   - Implements `whatsapp-web.js` provider, escalation worker, health surfaces, and sender tests.

5. `docs/superpowers/plans/2026-05-08-04-kmp-shared-and-android-shell.md`
   - Creates Kotlin Multiplatform project, shared models/API client/state logic, Compose Android shell, and task/review UI.

6. `docs/superpowers/plans/2026-05-08-05-android-native-enforcement.md`
   - Implements exact alarms, boot rescheduling, full-screen blockers, notifications, permission health, and widget entry.

## Parallel Work Rules

- Backend agents own `backend/**` and backend documentation.
- Android/KMP agents own `client/**` and Android documentation.
- Contract-changing work must update `docs/notes/api-contract.md` in the same commit.
- Cross-cutting decisions must be written to `docs/decisions/`.
- Every agent writes a handoff in `docs/handoffs/`.
- Do not edit another active agent's files unless the handoff says the files are free.

## Completion Definition

The MVP implementation is ready for device testing when:

- backend tests pass
- KMP shared tests pass
- Android unit tests pass
- backend can start locally with SQLite
- Android app can fetch snapshot from local backend
- a manually created strict task can be shown, started, extended, rescheduled, and completed against a mocked Todoist adapter
- WhatsApp provider can be tested in dry-run mode without sending a real message

