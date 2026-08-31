# Level 1 mutation simplification implementation handoff

Date: 2026-08-31
Status: Task 6 completed; ready for Task 7

## Task and plan status

- Plan: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level-1-mutation-simplification`
- Branch: `feature/level-1-mutation-simplification`
- Task 0 (Workspace preparation & Context7 lookups): COMPLETED
- Task 1 (Retire reconciliation protocol and align repository guidance): COMPLETED (commit `295146a08053a34db7ef0cf6d45b3debb36d25d9`)
- Task 2 (Introduce typed Todoist failures and bounded same-UUID command retry): COMPLETED (commits `2acdc659`..`83cf59e`)
- Task 3 (Add the process-wide FIFO and deadline admission): COMPLETED (commit `1b6ce25829d0d654b1b65039e2996ff0308390c3`)
- Task 4 (Simplify sync, recurrence rotation, and one-minute polling): COMPLETED (commit `917e98094d3e81dd2dd3cd4ab1abbe5629bcb06e`)
- Task 5 (Build a one-shot mutation runner): COMPLETED (commit `df65a44a76617441cbc4d7e427e33a752a77bc20`)
- Task 6 (Decouple task and checklist actions from the journal): COMPLETED (commits `96719e4`..`207f978`)
- Next task: Task 7 (Replace the HTTP mutation/sync contract and add forced refresh)

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
- Evidence: Domain service unit tests passing (53 tests), TypeScript typecheck passed cleanly with zero errors.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` (in `.worktrees/level-1-mutation-simplification/client`)
- Result: PASS
- Evidence: 74 Gradle tasks executed, all JVM unit tests passed, debug APK assembled successfully.

## Next Steps

Execute Task 7 onwards sequentially in the dedicated worktree `.worktrees/level-1-mutation-simplification`:
- Task 7: Replace the HTTP mutation/sync contract and add forced refresh.
- Task 8: Upgrade backend Sentry structured logs and enforce ownership.
- Task 9: Remove journal persistence and migrate SQLite schema v7.
- Task 10: Delete the coordinator and root mutex, then prove backend convergence.
- Task 11: Simplify the shared Kotlin contract and add structured request logs.
- Task 12: Simplify Android enforcement outcomes and Sentry ownership.
- Task 13: Add session warnings, screen locks, forced Refresh, and Open Todoist.
- Task 14: Remove pending/retry UX from blockers, Morning Review, and editors.
- Task 15: Migrate and expand real-backend instrumentation.
- Task 16: Final verification, rollout tooling, production canary, and Sentry closure.
