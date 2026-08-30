# New Sentry issues and recurring Done recovery recommendations

Date: 2026-08-25

> **Historical diagnosis, superseded repair recommendation (2026-08-30).** The Sentry
> evidence and root-cause trace below remain useful. Do not implement its journal,
> replay, recovery-operation, retry-closure, or pending-state recommendations. The owner
> accepted the simpler replacement in
> `docs/decisions/2026-08-30-level-1-mutation-simplification.md` and
> `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`.

## Scope

- Diagnosis-only review of new hosted Sentry issues.
- Reconciled the findings with
  `docs/handoffs/2026-08-24-sync-pending-recurring-done-investigation.md`.
- No product code, production database state, Todoist state, deployment, or Sentry
  issue status was changed.
- No implementation plan existed for this review; the recommended implementation
  boundary is recorded below.

## Sentry inventory

Sentry MCP found these unresolved issues in `jans-gi`:

| Issue | Evidence | Diagnosis |
| --- | --- | --- |
| `BUZER-ANDROID-4` | Feedback at `2026-08-24T21:04:50Z` | Existing recurring Done acknowledged-no-op lockout from the prior handoff. |
| `BUZER-ANDROID-5` | One `POST /tasks/sync` client timeout at `2026-08-25T08:44:38Z`; request ID `519575f9-89c1-4833-bb28-ef6ad5d20b88` | The Android client applies one global 5-second request timeout to an endpoint that waits for serialized Todoist synchronization. The nearby Todoist degradation is a plausible trigger, but Sentry does not prove that this request reached the same 502 response path. |
| `BUZER-ANDROID-7` | Two Node events at `08:46:59Z` and `08:47:07Z`, request IDs `8154b100-4dda-40a4-a7b5-460e64b4253f` and `762e8591-2c76-4762-a5f8-28f9605441cd` | Todoist returned HTTP 502 during the coordinator's pre-command mirror sync for `POST /tasks/:id/update`. The untyped upstream error became backend HTTP 500. These backend events were incorrectly ingested into the Android Sentry project. |
| `BUZER-ANDROID-8` | Two Android events with the same timestamps and request IDs as issue 7 | Client-side copies of the two failed update attempts. `BlockerErrorReporter` captures all non-4xx/409/429 action failures as handled Sentry exceptions. |

No unresolved issue was found in `buzer-backend`. That is not evidence of a clean
backend: issue 7 has `environment=production-vps`, Node stack frames, and backend
release `app.buzer@0.1.0+0ab2f075d668`, but Sentry reports project ID
`4511950606172240` (`buzer-android`). The production `SENTRY_BACKEND_DSN` is therefore
pointing at the Android project, or the wrong DSN is otherwise being injected at
runtime. The canonical backend project ID is `4511950617641040`.

The new update failure concerns task `3c7c70e1-e9d2-4428-afea-00c9909ec029`; it is not
the recurring task `f5a048fc-ed68-435d-88d5-2248aaa5702e` from issue 4. The failures
are distinct, although both expose incomplete recovery behavior around Todoist.

## Root-cause trace

### Todoist 502 pair (`BUZER-ANDROID-7` and `BUZER-ANDROID-8`)

1. `TaskActionCoordinator.execute()` reserves the immutable action, then calls the
   initial `syncOnce()` before validation or external command execution.
2. `HttpTodoistAdapter.request()` throws `TodoistHttpError(502)` for the read-only Sync
   API call.
3. `TodoistHttpError` has no public backend `statusCode`, error code, retry metadata, or
   retry classification. Fastify therefore serializes it as HTTP 500 with code
   `internal_error` and captures it in Sentry.
4. Android parses the 500 as `BuzerApiException(todoist_http_502)` and
   `BlockerErrorReporter` captures it again.
5. The second attempt eight seconds later used another HTTP request ID and failed at the
   same pre-command sync boundary. No Todoist mutation command appears in either stack.

Because failure occurred before command dispatch, bounded retries of this read-only sync
are safe. The same conclusion must not be generalized to mutation commands: an ambiguous
command transport failure remains `reconciliation_pending` and must not be blindly resent.

### Force-sync timeout (`BUZER-ANDROID-5`)

`BuzerApiClient` configures connect, socket, and whole-request timeouts to 5 seconds for
every endpoint. `/tasks/sync` waits for a serialized backend sync and a Todoist HTTP call,
so its legitimate processing budget is larger. Ktor supports per-request timeout
overrides; a longer force-sync/mutation request timeout can coexist with a short connect
timeout.

The backend Todoist adapter currently gives Node `fetch` no explicit timeout. The client
and server therefore have incompatible bounds: Android can abandon a request after five
seconds while the server continues waiting indefinitely.

### Recurring Done lockout (`BUZER-ANDROID-4`)

The prior handoff remains correct. A confirmed `item_update_date_complete` command whose
effect is authoritatively absent stays `reconciliation_pending`; replay restores the
confirmed command instead of sending again, while a new action is rejected by the root's
pending flag. The morning-review UI exposes only force refresh, which cannot change the
journal state.

## Recommended repair boundary

### 1. Correct production Sentry routing first

- Replace the VPS `SENTRY_BACKEND_DSN` with the client key for `buzer-backend` project
  `4511950617641040` and redeploy/restart the backend.
- Run the documented backend canary and verify its project is `buzer-backend`, environment
  is `production-vps`, and release is the deployed SHA.
- This is an operations correction, not the functional 502 fix.

### 2. Add bounded, semantics-aware upstream failure handling

- Give `TodoistHttpError` a typed status, retryability classification, parsed
  `retry_after` when present, and a safe public backend error mapping.
- Add a short bounded retry with jitter for read-only `sync()` calls on transport errors,
  429, 502, 503, and 504, honoring `retry_after`. Keep the total server-side time budget
  explicit.
- Do not add blind retries to Todoist mutation commands. A command timeout or ambiguous
  response continues through the existing action-journal reconciliation protocol.
- When read retries are exhausted, return HTTP 503 with a stable code such as
  `todoist_temporarily_unavailable` and a bounded `Retry-After`, rather than reporting an
  upstream 502 as an internal 500.
- Teach `EnforcementCoordinator` to treat that stable transient response like its existing
  429 retry path, preserving the same immutable action request/action ID.
- Log the Android transient as a warning instead of creating a duplicate handled issue;
  keep one backend Sentry event after the bounded retry budget is exhausted.

### 3. Make timeout budgets consistent

- Keep a short Android connect timeout, but give `forceSync()` and journaled mutation
  requests a per-request timeout that exceeds the backend's complete bounded operation
  budget. Do not simply raise every endpoint timeout globally.
- Add an explicit timeout to backend Todoist `fetch` calls.
- The concrete numbers should be chosen together; for example, a 10-second bounded Todoist
  attempt/retry budget and a 20-30-second Android force-sync/mutation request budget.

### 4. Add explicit authoritative no-op recovery for recurring Done

Prefer a two-stage recovery rather than an automatic second completion:

1. Add a backend recovery operation for the existing pending action. Under the root mutex,
   run an authoritative full Todoist sync (`sync_token = *`) and verify the same Todoist
   task ID, occurrence key/due value, active state, and absent completion effect.
2. If the old effect is still definitively absent and the pending action is old enough,
   atomically mark the old journal terminal with an explicit acknowledged-no-op failure
   cause and clear the root reconciliation flag. If evidence is ambiguous, leave it pending.
3. Return an authoritative snapshot. Do not send a new Todoist command from the recovery
   operation.
4. Change the pending morning-review card from refresh-only `Retry` to an explicit recovery
   action. After recovery succeeds, render the normal overdue row and let the user press
   Done again, creating a fresh action ID and command UUID.
5. Make recovery idempotent, age/attempt bounded, and observable through structured logs
   using action/task/occurrence identifiers. Do not introduce custom telemetry storage.

This preserves the action-reconciliation rule: uncertain sent commands are never guessed
away, while an authoritative proof of an acknowledged no-op creates a safe terminal state.

## Required tests and verification gates

Backend:

- Read-only Sync API 502/503/504/429 retries are bounded and honor retry metadata.
- Mutation command transport ambiguity is not blindly retried.
- Exhausted read retry maps to stable HTTP 503 plus `Retry-After`.
- A pre-command sync failure sends no Todoist mutation and replay with the same action ID
  remains safe.
- Full-sync recovery refuses ambiguous, changed-occurrence, too-young, wrong-command, and
  transport-failure states.
- Proven acknowledged no-op recovery atomically terminalizes the old journal and confirms
  the root; repeated recovery is idempotent and concurrent action attempts remain serialized.

Android/shared:

- Per-request force-sync/mutation timeouts exceed a slow fake backend response while normal
  snapshot requests retain their shorter budget.
- Stable transient 503 produces a retry result that reuses the original immutable request.
- Transient upstream responses produce a warning log rather than a duplicate handled issue.
- `MorningReviewScreenStateTest`, `MorningReviewRepairHostTest`, and relevant real-backend
  instrumentation cover pending -> recovered overdue row -> fresh Done intent.
- Run the full unfiltered `scripts/e2e-real-backend.sh` before completion because the change
  affects user-visible Android behavior.

Production/Sentry:

- Backend canary lands only in `buzer-backend`.
- A deliberately slow non-destructive sync completes without Android timeout.
- A controlled transient upstream failure shows bounded retry and one backend issue, not a
  paired Android duplicate.
- Recover the production issue-4 task only after explicit user authorization, then verify a
  fresh Done can complete the same occurrence safely.

## Sentry resolution policy

All four issues remain unresolved. Use Sentry MCP to resolve them only after the matching
fix is verified and deployed:

- `BUZER-ANDROID-4`: recovery feature deployed and the stuck occurrence safely unlocked.
- `BUZER-ANDROID-5`: timeout budgets deployed and slow force sync verified.
- `BUZER-ANDROID-7` and `BUZER-ANDROID-8`: upstream retry/error mapping and Android transient
  handling deployed; production Sentry routing corrected; task update verified.

## Files inspected

- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/src/diagnostics/backendObservability.ts`
- `backend/src/http/routes.ts`
- `backend/src/server.ts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `docker-compose.yml`
- `scripts/deploy-vps.sh`

## Verification

- Command: Sentry MCP issue search/details/event correlation for `BUZER-ANDROID-4`,
  `BUZER-ANDROID-5`, `BUZER-ANDROID-7`, and `BUZER-ANDROID-8`.
- Result: PASS
- Evidence: issue/event IDs, timestamps, request IDs, releases, project IDs, and stack
  boundaries recorded above; no unresolved issue exists in `buzer-backend`.

- Command: Context7 Todoist API lookup for 5xx/error and `retry_after` semantics.
- Result: PASS
- Evidence: current Todoist API docs classify 5xx as server failures and document
  `error_extra.retry_after`; 4xx command errors are terminal.

- Command: Context7 Ktor Client timeout lookup.
- Result: PASS
- Evidence: current Ktor docs confirm `HttpRequestBuilder.timeout` overrides global request
  timeout independently of connect/socket timeout configuration.

- Tests: SKIPPED
- Reason: diagnosis-only session; no product code changed.

- Manual production verification: SKIPPED
- Reason: no production mutation, deployment, canary, or task recovery was authorized.

## Next recommended step

Write and approve one implementation plan with three ordered workstreams: production Sentry
DSN correction/canary, bounded Todoist/HTTP timeout handling, and authoritative recurring
Done no-op recovery with Android UI and instrumentation migration. Close the four Sentry
issues only after their individual deployment gates above pass.
