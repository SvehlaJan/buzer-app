# Agent Working Agreement

This repository is a personal Todoist commitment companion. It has two MVP deployables:

- Android-first client built with Kotlin Multiplatform / Compose Multiplatform and Android-native enforcement.
- Node.js/TypeScript backend on a VPS using SQLite, Todoist polling, and `whatsapp-web.js`.

The approved product spec is `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`. Treat that file as product truth unless a later decision document explicitly changes it.

## Required Start-Of-Session Routine

Before changing code, every agent must:

1. Read this `AGENTS.md`.
2. Read the approved spec.
3. Read the plan file for the task being executed.
4. Read recent handoffs in `docs/handoffs/`.
5. Run `git worktree list`. For independently-assignable subtasks, work in a dedicated
   worktree per `docs/notes/agent-workspaces.md` rather than the primary checkout.
6. Run `git status --short`.
7. Identify files already changed by others and avoid overwriting them.
8. State in the handoff which plan/task/steps were attempted.

## Documentation Culture

This project is intentionally multi-agent. Knowledge must not live only in chat transcripts.

Agents must write markdown notes when they learn something important:

- `docs/handoffs/`: session handoffs, one file per agent session.
- `docs/decisions/`: architecture and product decisions that affect future work.
- `docs/notes/`: technical research, debugging notes, API observations, and operational findings.
- `docs/superpowers/plans/`: implementation plans with checkbox progress.

Use descriptive filenames:

```text
docs/handoffs/2026-05-08-backend-core-agent-a.md
docs/decisions/2026-05-08-sqlite-wal-migrations.md
docs/notes/2026-05-08-todoist-duration-observations.md
```

## Handoff Requirements

Before stopping, every implementation agent must create or update a handoff file containing:

- task/plan name and current checkbox status
- files changed
- commands run and results
- tests run and results
- manual verification performed
- known failures or skipped checks
- assumptions made
- next recommended step

If no code changed, still write a handoff explaining what was investigated and what remains.

## Engineering Standards

- Keep changes scoped to the assigned plan/task.
- Do not mix backend, Android enforcement, WhatsApp, and documentation-only work unless the plan explicitly says so.
- Prefer small, focused files with clear interfaces.
- Use tests to lock behavior before implementation whenever practical.
- Do not claim work is complete until verification commands have passed or the skipped checks are documented.
- Do not hide broken behavior behind broad catch blocks.
- Do not leave placeholder code, unchecked boxes for completed work, or TODO comments without a linked plan item.
- Do not commit secrets, tokens, QR sessions, `.env` files, databases, generated build output, or WhatsApp auth folders.
- Do not overwrite or revert files changed by another agent unless explicitly instructed.
- Independently-assignable tasks run in a dedicated git worktree, not the primary
  checkout — see `docs/notes/agent-workspaces.md` for the manifest convention and
  integration/merge-back pattern.

## Documentation Lookup

Use the `ctx7` CLI to fetch current documentation whenever the task involves library, framework, SDK, API, CLI tool, or cloud service usage. This includes Kotlin Multiplatform, Compose Multiplatform, Android platform APIs, Node.js libraries, Fastify, SQLite libraries, Todoist API, Firebase, and `whatsapp-web.js`.

Required flow:

```bash
npx ctx7@latest library "<library name>" "<task-specific question>"
npx ctx7@latest docs "/resolved/library/id" "<task-specific question>"
```

Do not include secrets in Context7 queries.

## Git Discipline

- Commit only coherent, reviewed changes.
- Keep commits small enough to inspect.
- Use imperative commit messages, for example `feat: add backend task validation`.
- Do not commit unrelated untracked files.
- Leave unrelated dirty files untouched.
- If a task spans multiple commits, each commit must pass the relevant tests for its scope.

## Testing Expectations

Backend agents must run relevant unit/integration tests before handing off. Android agents must run shared JVM tests and, when possible, Android unit/instrumentation tests. If a device/emulator is required and unavailable, document the exact manual check that remains.

Any task that changes `client/` UI or user-visible behavior must run `scripts/e2e-real-backend.sh` as part of its final verification — the sole instrumentation-test authority; `tools/fake-backend` was removed 2026-07-25 (`docs/decisions/2026-07-25-remove-fake-backend-tool.md`). Scope with `ANDROID_TEST_CLASS` to the relevant class(es) while iterating (~1-2 minutes); run the full unfiltered suite before considering a UI-affecting change done. JVM unit tests alone are not sufficient verification for UI-affecting changes: between 2026-06-15 and 2026-07-05 two UI reworks shipped with green JVM suites while silently breaking 10 of 21 instrumentation tests, discovered only when CI first ran the lane (`docs/handoffs/2026-07-04-ci-workflows.md`).

When planning a change to existing behavior, the plan must list which existing test suites assert that behavior — including `client/androidApp/src/androidTest` — and migrate those tests within the same plan, not as a follow-up.

Android enforcement agents should prefer native Android instrumentation tests over host-side ADB scripts. For blocker activity/action changes, use `scripts/e2e-real-backend.sh`, which starts `backend/test/e2eServer.ts` locally and runs `pixel8Api34DebugAndroidTest` (or `connectedDebugAndroidTest` with `ANDROID_SERIAL` set) against it. Do not use live Todoist or WhatsApp for destructive action tests.

Toolchain notes (post 2026-06-08 dependency migration): the shared JVM unit-test task is `:shared:testAndroidHostTest` (the old `:shared:testDebugUnitTest` no longer exists). When building from a `git worktree`, export `ANDROID_HOME`/`ANDROID_SDK_ROOT` because `client/local.properties` is gitignored. An Android 16 / API 36 emulator (`Buzer_API_36`) is available for connected tests; scope multi-device runs with `ANDROID_SERIAL`. See `docs/notes/local-development.md` and `docs/notes/2026-06-08-android-16-emulator-setup.md`.

For device/emulator verification, prefer `scripts/adb-verify.sh` over raw `adb logcat`/`dumpsys`/`screencap`. It emits small, pre-filtered evidence (cleared + tag-filtered logcat, app-scoped `dumpsys alarm`, overlay/notification/exact-alarm/full-screen app-op state, and exit-checked downscaled screenshots) and refuses to guess between multiple attached devices. Use it to keep handoff evidence cheap and consistent. Rationale and the full token-efficiency checklist: `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`.

For every completed task, the handoff must include:

```text
Verification:
- Command: <exact command>
- Result: PASS/FAIL/SKIPPED
- Evidence: <important output or reason>
```

## Continuous Integration

GitHub Actions runs `ci.yml` (added 2026-07-05, details in `docs/handoffs/2026-07-04-ci-workflows.md`) on every push: shared + androidApp JVM tests, `assembleDebug`, and backend npm tests (~5 minutes). There is no scheduled instrumentation-test CI lane as of 2026-07-25 — `emulator-e2e.yml` was removed along with `tools/fake-backend` (`docs/decisions/2026-07-25-remove-fake-backend-tool.md`); `scripts/e2e-real-backend.sh` must be run manually until a replacement CI lane is set up.

Rules:

- After pushing, check the push-triggered run before claiming completion: `gh run list --workflow=ci.yml --limit 1` (`gh` is installed and authenticated on the dev machine).
- Do not hand off on top of a red CI run without documenting why in the handoff.
- If a nightly emulator run fails with `java.net.ConnectException ... 10.0.2.2:18433`, suspect a transient emulator network blip (see the 2026-07-04 handoff) before assuming a regression.

## Current MVP Boundaries

In scope:

- Kotlin Multiplatform / Compose Multiplatform Android-first client.
- Android-native alarms, blocker activities, widget, boot rescheduling, and permission health.
- Node.js/TypeScript backend.
- SQLite on the VPS.
- Todoist polling every 5 minutes.
- `whatsapp-web.js` escalation.

Out of scope until a later decision:

- macOS app.
- calendar blocking.
- Todoist webhooks.
- FCM push invalidation.
- Firebase/Supabase realtime layer.
- public multi-user product behavior.

## Conflict Resolution

If the spec, a plan, and existing code disagree:

1. Follow the spec for product behavior.
2. Follow the newest decision document for intentional changes.
3. Follow the plan for implementation sequencing.
4. Follow existing code patterns for style.
5. If still ambiguous, document the ambiguity in a handoff and stop before making irreversible changes.

## Other Notes

- Never chain commands with `&&` or `;` when each individual command is already allowed by `Bash(git:*)` or similar rules. Use separate parallel Bash tool calls instead — they run concurrently and don't trigger permission prompts.
- Always prefer to use `jq` for JSON parsing instead of `python`/`grep`/`sed`/`awk`. It is more robust and avoids brittle regexes.
