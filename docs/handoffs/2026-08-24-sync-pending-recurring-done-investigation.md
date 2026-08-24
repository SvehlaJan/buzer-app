# Sync-pending recurring Done investigation

Date: 2026-08-24

## Scope

- Investigated valid Sentry feedback `BUZER-ANDROID-4`: “i see sync pending on this task even though it is already done”.
- The user later clarified that the Todoist occurrence was not actually finished; the actionable defect is that Buzer no longer allowed another Done attempt.
- Diagnosis only. No application or production state was changed.

## Report correlation

- Android feedback event: `be2ae9a1b14b43dda10d3ac513af4710`
- Replay: `cb60122626864f04aabbe5fe479edfe1`
- Report time: `2026-08-24T21:04:50Z`
- Release: `app.buzer@0.1.0-dev+b1b0cd202dcf`
- The new structured-log markers were present and correlated on trace `487cad564f20484692756fda5282731d`.
- The report-time force sync and snapshot calls both returned HTTP 200. The returned snapshot still contained exactly one pending task.

## Affected task and production evidence

- Internal task ID: `f5a048fc-ed68-435d-88d5-2248aaa5702e`
- Todoist task ID: `683WhwVH7XGqxqrq`
- Title: `Výročie 👰‍♀️🤵‍♂️💍`
- Recurrence: `every year on August 14th at 08:00`
- Stored due time: `2026-08-14T06:00:00Z`
- Stored state at investigation time: `planned`, `completedAt = null`, `reconciliationStatus = pending`
- Occurrence key: `d031fca0-346a-41b8-b76b-28d20e60b5c4`

The production journal has one relevant action:

- Action ID: `3bac1a28-6868-4ce9-934d-917cbfe0282b`
- Type: `done`
- Created: `2026-08-20T06:37:51.937Z`
- Status: `reconciliation_pending`
- Error cause: `todoist_effect_not_confirmed`
- Command step: `item_update_date_complete`
- Command status: `confirmed`
- Todoist command confirmation time: `2026-08-20T06:37:53.486Z`

The mirror continued polling successfully through at least `2026-08-24T21:13:45.473Z` without advancing the occurrence.

A read-only full Todoist Sync API request (`sync_token = *`) returned the task as active and unchanged: `checked = false`, `completed_at = null`, `is_deleted = false`, and the original 2026-08-14 due value. A read-only query to `/api/v1/tasks/completed/by_completion_date` for 2026-08-19 through 2026-08-22 returned no completion record for the task. Therefore this is not merely a stale local mirror: Todoist returned `sync_status: ok` for the command, but no observable completion effect exists.

## Root cause in current behavior

The durable reconciliation protocol safely refuses to claim success without authoritative evidence, but it has no recovery path for a confirmed command that was an authoritative no-op:

1. `TaskActionService.done()` sends `item_update_date_complete` for recurring roots and considers the effect present only when both `occurrenceKey` and `occurrenceDueDatetime` advance.
2. The command returned `sync_status: ok`, so its journal command was marked `confirmed`.
3. The post-command sync saw the original occurrence unchanged, so `TaskActionCoordinator` marked the action and commitment `reconciliation_pending`.
4. Subsequent ordinary syncs only refresh Todoist state. They do not finalize, fail, or clear the pending journal/root.
5. Replaying the same action cannot recover: the confirmed command result is restored instead of sending a new command UUID, and the task returns to pending when the effect remains absent.
6. A distinct action is rejected while the root has `reconciliationStatus = pending`.
7. On Android, `blockingCard()` maps a pending task to `MorningReviewBlockingCard.SyncPending`; `MorningReviewScreen` then renders only `Retry`, wired to forced refresh. The normal overdue row and its Done button are not rendered.

The defect is therefore a permanent UI/action lockout after a Todoist-acknowledged no-op. The client is rendering the backend state as designed, but the end-to-end recovery design is incomplete.

## Recommended repair boundary

Do not simply make the client show a Done button while the root remains pending: the backend will reject the new action. Do not blindly resend a recurring completion either: if the first command took effect but the mirror was stale, a second completion could skip another recurrence.

A safe recovery should first obtain authoritative evidence, preferably a full Todoist sync, that the same task ID, occurrence due value, and active state still exist. Once the old effect is definitively absent, atomically terminalize the old pending journal as an acknowledged no-op, clear the root's pending reconciliation flag, and permit a fresh Done intent with a new action/command UUID. The UI can expose this as a retry/recover action rather than a refresh-only button. Add an age/attempt bound and explicit observability for this path.

For the immediate production task, the same atomic journal/root recovery can be applied manually after explicit user authorization; it was not performed during this investigation.

## Files inspected

- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/src/repositories/taskRepository.ts`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`

## Verification

- Command: read-only production SQLite query in `buzer-backend` using `better-sqlite3` with `readonly: true`
- Result: PASS
- Evidence: confirmed the task, pending Done journal, confirmed command, and current sync timestamp listed above.

- Command: read-only Todoist full Sync API request with `sync_token = *`
- Result: PASS
- Evidence: HTTP 200; the task remained active on its original occurrence.

- Command: read-only Todoist completed-task history request for 2026-08-19 through 2026-08-22
- Result: PASS
- Evidence: HTTP 200; nine completed tasks in the window, no matching task or title.

- Command: `npx ctx7@latest library "Todoist API" "API v1 Sync item_update_date_complete command exact args response sync_status recurring completion due date in past"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1`.

- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "item_update_date_complete exact command arguments due date recurrence is_forward reset_subtasks sync_status success semantics and examples"`
- Result: PASS
- Evidence: current docs confirm the command shape used by the adapter and that `sync_status[uuid] = "ok"` is the documented success response.

- Tests: SKIPPED
- Reason: diagnosis-only session; no code changed.

## Next recommended step

Write a focused implementation plan for authoritative no-op recovery covering backend journal transitions, a full-sync proof boundary, Android recovery UI, existing backend coordinator tests, and `MorningReviewRepairHostTest`/real-backend instrumentation coverage. Separately, recover this one production task only with explicit authorization.
