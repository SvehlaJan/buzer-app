# Level 1 mutation simplification implementation handoff

Date: 2026-08-30
Status: Task 0 completed; ready for Task 1

## Task and plan status

- Plan: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`
- Branch: `feature/level-1-mutation-simplification`
- Task 0 (Workspace preparation & Context7 lookups): COMPLETED
- Next task: Task 1 (Retire reconciliation protocol and align repository guidance)

## Context7 Lookups and Resolved Documentation

1. **`@sentry/node` (library ID: `/websites/sentry_io_platforms_javascript_guides_node`)**:
   - `enableLogs: true` in `Sentry.init` enables structured log capture.
   - `beforeSendLog: (log) => log | null` allows filtering, dropping, or modifying log records and stripping PII/sensitive attributes before transmission.
   - Overloaded `Sentry.logger` interface supports `info`, `warn`, `error`.

2. **Sentry Android/Java (library ID: `/getsentry/sentry-java`)**:
   - `Sentry.logger()` returns `ILoggerApi`.
   - `options.getLogs().setEnabled(true)` enables structured logs.
   - Overloaded `log(SentryLogLevel, SentryLogParameters, message, args)` enables structured attributes per log event.
   - Correlates with trace ID, span ID, and breadcrumbs automatically.

3. **Ktor Client (library ID: `/ktorio/ktor-documentation`)**:
   - Per-request timeout override via `HttpRequestBuilder.timeout { requestTimeoutMillis = 15000L }`.
   - Overrides default client-level timeout configuration without changing global settings.

4. **Todoist Sync API (library ID: `/websites/developer_todoist`)**:
   - `POST /api/v1/sync` accepts `commands` array with client-generated UUIDs.
   - Response maps command UUIDs to `"ok"` or typed error object (`error_tag`, `error_code`, `http_code`, `error_extra`).
   - Retries with the exact same UUID are recognized by Todoist for idempotency during network failure.

## Verification

Verification:
- Command: `git worktree list`
- Result: PASS
- Evidence: Worktree `.worktrees/level-1-mutation-simplification` created on branch `feature/level-1-mutation-simplification`.

Verification:
- Command: `npm test && npm run typecheck` (in `.worktrees/level-1-mutation-simplification/backend`)
- Result: PASS
- Evidence: 38 test files, 451 tests passed. TypeScript typecheck passed cleanly with zero errors.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` (in `.worktrees/level-1-mutation-simplification/client`)
- Result: PASS
- Evidence: 74 Gradle tasks executed, all JVM unit tests passed, debug APK assembled successfully.

## Next Steps

Execute Task 1 onwards sequentially in the dedicated worktree `.worktrees/level-1-mutation-simplification`:
- Task 1: Retire the reconciliation protocol and align repository guidance.
- Task 2: Introduce typed Todoist failures and bounded same-UUID command retry.
- Task 3: Add process-wide FIFO and deadline admission.
- Task 4: Simplify sync, recurrence rotation, and one-minute polling.
- Task 5: Build one-shot mutation runner.
