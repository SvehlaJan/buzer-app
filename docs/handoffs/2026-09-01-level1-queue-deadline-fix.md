# Level 1 queue deadline and request-signal repair handoff

Date: 2026-09-01
Status: DONE_WITH_CONCERNS

## Task and plan status

- Parent plan: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Reviewed/repair scope: Tasks 2–5, 7, and 8 queue/sync/HTTP/observability behavior.
- Attempted steps: reproduce the Node 26 real-socket abort, add active total-deadline
  enforcement, propagate the queue execution signal through raw read sync, correct forced
  refresh issue ownership, and clarify `backend_busy` copy.
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/level1-fix-queue-deadline`
- Branch: `feature/level1-fix-queue-deadline`

## What changed

- `routes.ts` now uses Fastify's supported `request.signal`, not Node 26's
  `IncomingMessage.signal`. A real TCP POST regression proves a parsed mutation request
  reaches its successful typed outcome instead of returning HTTP 500.
- `TodoistOperationQueue` now gives dequeued callbacks a queue-owned signal that aborts at
  the remaining total deadline. The caller receives the active deadline promptly, but the
  queue retains exclusive ownership until the callback actually settles; later entries
  expire waiting instead of overlapping a stuck operation. The client signal is used only
  to remove waiting work.
- Pre-execution timeout errors are tagged `failure_stage=waiting` and `backend_busy`;
  active deadline errors are tagged `failure_stage=active` and
  `todoist_operation_deadline`. TaskMutationRunner preserves the authoritative phase:
  acknowledged commands map to `accepted_stale`, explicit rejection stays `rejected`,
  and only unresolved delivery maps to `delivery_unknown`; none map to busy/nothing-sent.
- `TodoistSyncService.syncOnce`/`syncNow` accept optional signal options and pass the signal
  to `TodoistAdapter.sync`. The poller and forced refresh route pass their queue execution
  signals to raw sync. The HTTP adapter already forwarded read-sync signals to `fetch`; a
  focused characterization test now locks that behavior.
- Forced refresh returns operational network/timeout failures as warning-only typed
  responses, while parse/state/SQLite/internal failures are captured through backend
  observability before the warning response. Active refresh deadline timeout maps to
  `forced_refresh_failed`, not pre-send `backend_busy`.
- Both forced-refresh and mutation `backend_busy` messages explicitly say that nothing was
  sent.

## Files changed

- `backend/src/http/routes.ts`
- `backend/src/services/todoistOperationQueue.ts`
- `backend/src/services/taskMutationRunner.ts`
- `backend/src/services/todoistPoller.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/services/todoistOperationQueue.test.ts`
- `backend/test/services/taskMutationRunner.test.ts`
- `backend/test/services/todoistPoller.test.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`
- `docs/handoffs/2026-09-01-level1-queue-deadline-fix.md`

## Red/green evidence

Verification:
- Command: `npm test -- test/services/todoistOperationQueue.test.ts test/services/todoistSyncService.test.ts test/services/todoistPoller.test.ts test/http/routes.test.ts`
- Result: FAIL (expected RED)
- Evidence: 10 focused failures: real TCP mutation returned 500; active queue work did not
  abort/release; client signal remained the execution signal; raw sync, poll, and forced
  refresh omitted the queue signal; forced-refresh internal failure was not captured;
  active timeout was reported as busy; both busy messages omitted “Nothing was sent.”

Verification:
- Command: `npm test -- test/services/todoistOperationQueue.test.ts test/services/todoistSyncService.test.ts test/services/todoistPoller.test.ts test/services/todoistPollerDiagnostics.test.ts test/todoist/httpTodoistAdapter.test.ts test/http/routes.test.ts`
- Result: PASS (GREEN)
- Evidence: 6 test files and 111 tests passed with no warnings or unhandled rejections.

## Spec-review follow-up red/green evidence

Verification:
- Command: `npm test -- test/services/todoistOperationQueue.test.ts test/services/taskMutationRunner.test.ts test/http/routes.test.ts`
- Result: FAIL (expected RED)
- Evidence: five focused failures proved active timeouts lacked a phase tag and mapped to
  busy, the FIFO released while the old callback was still running, synchronous callback
  prelude could exceed the total deadline, and fallback snapshot failure was swallowed.

Verification:
- Command: `npm test -- test/services/todoistOperationQueue.test.ts test/services/taskMutationRunner.test.ts test/services/todoistSyncService.test.ts test/services/todoistPoller.test.ts test/services/todoistPollerDiagnostics.test.ts test/todoist/httpTodoistAdapter.test.ts test/http/routes.test.ts`
- Result: PASS (GREEN)
- Evidence: 7 test files and 129 tests passed with no warnings or unhandled rejections.

## Outcome-preservation follow-up red/green evidence

Verification:
- Command: `npm test -- test/services/taskMutationRunner.test.ts`
- Result: FAIL (expected RED)
- Evidence: 2 focused failures showed acknowledged-command post-sync timeout and explicit
  rejection plus best-effort-sync timeout both incorrectly returned `delivery_unknown`.

Verification:
- Command: `npm test -- test/services/taskMutationRunner.test.ts`
- Result: PASS (GREEN)
- Evidence: 17 TaskMutationRunner tests passed; active deadlines now preserve accepted,
  rejected, and unresolved-delivery phases.

## Final verification

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 38 test files and 455 tests passed after all review repairs.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript completed with zero errors.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: production TypeScript compilation and schema asset copy completed with exit 0.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: `npx ctx7@latest library "fastify" "Fastify v5 request.signal behavior after body parsing and client disconnect"` followed by `npx ctx7@latest docs "/fastify/fastify" "Fastify v5 FastifyRequest signal AbortSignal client connection closes body parsing request.raw.signal"`
- Result: PASS
- Evidence: current Fastify request-abort guidance and local Fastify 5.8.5 source/types were reviewed; `FastifyRequest.signal` is the supported request signal.

Verification:
- Command: `npx ctx7@latest library "node.js" "AbortSignal.timeout and AbortSignal.any deadline cancellation semantics"` followed by `npx ctx7@latest docs "/nodejs/node" "AbortSignal.timeout AbortSignal.any compose external cancellation and total deadline timers cleanup"`
- Result: PASS
- Evidence: current Node AbortSignal deadline/composition semantics were reviewed.

## Manual verification

- The automated HTTP regression starts Fastify on an ephemeral `127.0.0.1` TCP port and
  uses real `fetch`, rather than `app.inject`, to POST `/tasks/:id/done` on Node v26.4.0.
- No live Todoist, production database, VPS, Android device, or Sentry project was touched.

## Known failures, skipped checks, and assumptions

- No test or build failure remains.
- CI/push verification was skipped because this delivery commits a child branch but does
  not push it.
- `npm ci` warns that the repository declares Node 22.x while the regression host is Node
  v26.4.0; the Node 26 host is intentional for reproducing the reviewed request-signal bug.
- TaskMutationRunner post-sync signal propagation remains assigned to the next task. This
  repair changes only its narrow deadline phase/outcome mapping. Until signal propagation
  lands, a stuck post-sync retains the FIFO after its caller receives the phase-correct
  `accepted_stale`, `rejected`, or `delivery_unknown`; later entries expire waiting and
  never overlap it.
- A callback that ignores cooperative cancellation deliberately retains queue ownership
  until it settles. This preserves the one-active-operation invariant at the cost of queue
  availability; owned poll and forced-refresh paths honor the execution signal.
- `.agents/tasks/level1-queue-deadline-fix.yaml` remains available locally but is ignored
  and removed from the tracked index, matching the workspace protocol.

## Self-review findings

- Confirmed active timeout metadata uses `todoist_operation_deadline` / `active`, while only
  `backend_busy` / `waiting` says nothing was sent.
- Confirmed active deadline handling preserves acknowledged commands as accepted-stale and
  explicit rejection as rejected; only unresolved command delivery becomes unknown.
- Confirmed client cancellation remains effective for queued entries but no longer cancels
  already-dequeued work.
- Confirmed operational forced-refresh failures create warning logs and zero Issues, while
  the non-operational path creates one backend exception and no Fastify duplicate.
- Confirmed no journal, replay, persistence, Android, task-action, or checklist-service
  files were modified. TaskMutationRunner received only the required narrow phase mapping.

## Next recommended step

Integrate these commits before or together with the separately owned TaskMutationRunner
repair that passes `ctx.signal` to both post-command sync paths. Active-vs-waiting deadline
classification and known outcome preservation are already repaired here.
