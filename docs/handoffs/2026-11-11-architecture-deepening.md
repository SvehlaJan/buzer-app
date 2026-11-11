# Handoff: Backend and client architecture deepening (2026-11-11)

Plans:
- `docs/superpowers/plans/2026-11-11-backend-architecture-deepening.md` (Tasks 1-3)
- `docs/superpowers/plans/2026-11-11-client-architecture-deepening.md` (Tasks 1-4)

Specs: `docs/superpowers/specs/2026-11-11-backend-architecture-deepening-design.md`,
`docs/superpowers/specs/2026-11-11-client-architecture-deepening-design.md`. Decision:
`docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`.

Branches / worktrees:
- `feature/deepen-backend` (`.worktrees/deepen-backend`, tip `cdc008c`): backend Tasks 1-3.
- `feature/deepen-client` (`.worktrees/deepen-client`): stacked on `feature/deepen-backend`, so it
  contains all of the backend work. It also contains client Tasks 1-4, the review fixes, and a
  merge of `master` (`88a0dff`). **Merge only `feature/deepen-client`.** `feature/deepen-backend`
  is fully contained in it.

## Sessions

- Antigravity sessions (2026-11-11, before ~16:30): wrote the specs and plans, then did backend
  Tasks 1-3 and client Tasks 1-3 with subagent-driven development. Their ledger is
  `.superpowers/sdd/2026-11-11-client-architecture-deepening/progress.md`. It is workspace scratch
  and not committed. That session left client Task 4 unstarted, did not run a whole-branch review
  or the full e2e suite, and wrote no handoff.
- Claude Code session (2026-11-11 evening), the subject of this handoff: client Task 4, a
  whole-branch review, the review fixes, a merge of `master`, and full verification.

## Checkbox status

| Plan | Task | Status |
| --- | --- | --- |
| Backend | 1 `CommitmentPolicy` | all ticked (Antigravity) |
| Backend | 2 `TodoistRuntime` | all ticked (Antigravity) |
| Backend | 3 Retire routes, `createBackendCore` | all ticked (Antigravity) |
| Client | 1 `CommitmentEligibility`, Morning Review lifecycle | all ticked (Antigravity) |
| Client | 2 Session authority (3 Level 1 bugs) | all ticked (Antigravity) |
| Client | 3 `TaskEditorUpdateScreen` deepening | all ticked (Antigravity, plus fix `dd63f38`) |
| Client | 4 `BlockerHostController` and overlay handoff | all ticked (this session, `e87d9c3`) |

## Client Task 4 (`e87d9c3`)

- `blockers/BlockerHostController.kt` (new) holds the state and lifecycle that
  `StartBlockerActivity` and `DeadlineBlockerActivity` used to duplicate. That covers the bound
  target (`bindTarget`), the latest and current task, `snapshotConfirmed`, `checking`, the sticky
  `warningMessage`, the single in-flight `runMutation`, and `refresh(explicitUserRefresh)`.
  - A lifecycle refresh (`false`) keeps an existing warning, such as an overlay handoff. On
    timeout or failure it shows the generic stale warning only if no warning is already shown.
  - An explicit refresh (`true`) clears the warning on success. On failure it shows
    "Refresh failed" and reports it with `showNotification = false`, because the banner already
    tells the user.
  - It reaches the snapshot through `BlockerSnapshotSource`, adapted from `EnforcementCoordinator`
    by `asBlockerSnapshotSource()`, so JVM tests need no Android.
- Both activities support `onNewIntent` (rebind and refresh). Each still renders only after its
  first refresh finishes, so a resolved blocker never flashes on screen.
- `StartBlockerActivity.EXTRA_INITIAL_WARNING_MESSAGE` and `createIntent(...)` were added.
- `enforcement/overlay/OverlayActionOutcome.kt` (new) adds `resolveOverlayActionOutcome`. Success
  dismisses. A non-Success result or a timeout escalates to `StartBlockerActivity` with the
  message. An unexpected error also reports the defect (`showNotification = false`).
  `OverlayBlockerService` launches the blocker with `NEW_TASK | CLEAR_TOP | SINGLE_TOP`.
  **Behavior change:** before this, the overlay dismissed silently whatever the action's outcome.
- `BlockerScreens.kt`: deleted `BlockerActionState.Failed` and the dead `errorMessage`,
  `onDismissError` and `onRetryAction` parameters, along with their `ErrorAlertDialog`s.
- One small behavior change: a Start blocker now reloads the cached snapshot after a
  Warning/Conflict/LocalReconciliationFailed result, as the Deadline blocker already did. It only
  updates the displayed model.

## Whole-branch review (`c9f48d3..e87d9c3`) and fixes

A read-only reviewer subagent checked every Review Focus item in both plans. All hold, except the
findings below. It found no Critical issues.

| # | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| 1 | Important | `createTask`: after a 2xx, a stale snapshot (`Conflict("stale_snapshot")`) or a local reconciliation failure kept Add Task open. The root create has no idempotency key, so a retry would create a duplicate Todoist task. | Fixed in `f080ea7`: `runBackendAction(landedMutationIsSuccess = true)` for create. Local failures are still reported. Tests: `createTaskReportsSuccessWhenTheCreateLandedBut…` (2). |
| 2 | Important | `BlockerHostController`: a refresh still in flight for the previous target after `onNewIntent` could show the old task, or `finish()` the new blocker. | Fixed in `f080ea7`: `bindTarget` and `refresh` cancel the previous refresh job, and results for a target that is no longer bound are ignored. Test `rebindDuringInFlightRefreshIgnoresTheOldTargetsResult` was RED (finish called once) and is now GREEN. |
| 3 | Minor | The overlay could escalate for a replaced request generation. | Fixed in `f080ea7`: escalation is gated on `requestGenerations.isCurrent(generation)`. |
| 4 | Minor | e2e server `/__block-queue` hung, and leaked an armed hold, if `pollOnce` settled before entering sync. | Fixed in `3d998cc`: it races `hold.entered` against `pollDone`, then releases and returns `{ blocked: false }`. No new test, because forcing it needs a 60 s queue wait. |
| 5 | Minor | `editTask` with a `rootPatch` and child mutations on an unsupported-hierarchy root now returns `unsupported_hierarchy` (`taskActionService.ts` `assertProjectedTree`). Before, it was allowed. | **Recorded only.** No user-visible effect, because the client sends no child mutations for unsupported tasks. This is intentional tightening, now documented here. |

Reviewer notes on intentional `CommitmentPolicy` changes:
- `isOwnedForReconciliation` treats a former child as not owned.
- `eligibleCheckedChildTodoistIds` excludes children from a different project and children that
  have their own children. This has no effect on valid roots.

Deferred minors from the Antigravity ledger (recorded, not changed):
1. No direct test of `MorningReviewScheduler.consume` with exact alarms denied.
2. `BootReconcileRunner` and `MorningReviewReconcileReceiver` use separate scheduler instances with
   no shared process lock.
3. A redundant terminal check in `AlarmPlanCalculator.kt:50-51`.
4. The `add_task projects …_failed` log is emitted twice.
5. `cachedProjectRepository` is unsynchronized and separate from `resetApiClientForTests()`.
6. `MainActivity.toggleCurrentChild` builds the pre-toggle item.
7. `ExistingChecklistEditor` lives in `TaskEditorUpdateModel.kt`.

## Merge with master (`88a0dff`)

Both branches were cut from `c9f48d3` (2026-09-30). The merge brought in the 2026-10-01 dependency
upgrade (Gradle 9.8, client and backend deps, Ktor held at 3.5.0), Sentry feedback screen context,
the VPS deploy changes, and docs. It merged cleanly. `MainActivity.kt` and `ui/App.kt`
auto-merged and were verified by the client suites and the full e2e run below.

## Files changed in this session

- New: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerHostController.kt`,
  `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayActionOutcome.kt`,
  `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerHostControllerTest.kt`,
  `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayActionOutcomeTest.kt`,
  this handoff.
- Modified: `StartBlockerActivity.kt`, `DeadlineBlockerActivity.kt`, `OverlayBlockerService.kt`,
  `BlockerScreens.kt`, `EnforcementCoordinator.kt`, `EnforcementCoordinatorTest.kt`,
  `backend/test/e2eServer.ts`, and the client plan's checkboxes.

## Verification

All results are on the merged tree (`88a0dff`) unless noted.

Verification:
- Command: `npm ci --ignore-scripts && npm rebuild better-sqlite3`, then `npm test`, `npx tsc --noEmit` and `npm run build` (in `backend/`)
- Result: PASS
- Evidence: `Test Files 40 passed (40)`, `Tests 507 passed (507)`, typecheck exit 0, build exit 0. Before the merge: 39 files, 503 tests. The worktree's `node_modules` was stale (Sentry 8.53 against a 10.72 lockfile), which failed typecheck until `npm ci` was run.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin` (from `client/`, with `ANDROID_HOME`/`ANDROID_SDK_ROOT` exported)
- Result: PASS
- Evidence: exit 0. Result XML shows androidApp 455 tests and shared 109 tests, 0 failures, 0 errors.

Verification:
- Command: `ANDROID_SERIAL=emulator-5584 CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433 scripts/e2e-real-backend.sh` (full, unfiltered, pre-merge at `e87d9c3`)
- Result: PASS
- Evidence: `Finished 115 tests on Buzer_API_36(AVD) - 16`, `BUILD SUCCESSFUL in 6m 6s`, exit 0.

Verification:
- Command: `PORT=18434 ANDROID_SERIAL=emulator-5584 CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18434 scripts/e2e-real-backend.sh` (full, unfiltered, on the merged tree `88a0dff`)
- Result: PASS
- Evidence: `Starting E2E backend at http://127.0.0.1:18434` (a fresh backend from the merged code), `BUILD SUCCESSFUL in 6m 3s`, exit 0. The result XMLs show 116 tests in 14 classes, 0 failures, 0 errors, 0 skipped. The one test more than before the merge came in with master.

## Known issues / skipped

- `scripts/e2e-real-backend.sh` leaves its e2e backend running. The `cleanup` trap kills the
  `( cd backend; npm run e2e-server ) &` subshell, not the node process under it. The pre-merge
  run left node (pid 35303, cwd `.worktrees/deepen-client/backend`) listening on 18433. Killing it
  from this session was refused by the permission classifier, so the final run used port 18434.
  That run left a second one too (pid 39660, port 18434).
  Stop the leftover process by hand. A later run on 18433 would otherwise silently reuse that
  stale backend ("Using existing E2E backend").
- The headless emulator `Buzer_API_36` is running on port 5584 from this session.
- No physical-device check was done. No Sentry checks were done, because the Sentry MCP is not
  authorized in this session.
- Nothing was pushed. `feature/deepen-client` is not merged into `master`.

## Assumptions

- The two Antigravity worktrees were finished: the owner asked for them to be completed, and
  their last commits were from earlier the same day.
- The overlay escalation to `StartBlockerActivity` is the intended product change (spec Module 4,
  `Q4a-1`).

## Next recommended step

Owner go-ahead to merge `feature/deepen-client` into `master` (`--no-ff`), then push and check
`gh run list --workflow=ci.yml --limit 1`. The backend change needs a VPS deploy. The retired routes are
recorded as unused by the client in `docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`.
Confirm that against the APK currently on the phone before deploying. After that, delete
`feature/deepen-backend` and both `deepen-*` worktrees.

## Rollout (2026-11-11, owner go-ahead)

- Both leftover e2e backends (pids 35303 and 39660) were stopped at the owner's request, which
  freed ports 18433 and 18434.
- Retired routes against the installed app: pre-merge `master` defines
  `addChecklistItem`/`updateChecklistItem`/`deleteChecklistItem` in `BuzerApiClient`, but nothing
  calls them, and the client has no `/review/dismiss` call. The APK on the phone therefore never
  hits the removed routes.
- Merged into `master` with `--no-ff` as `c2cd9ca` and pushed (`1aa8f1f..c2cd9ca`). The push also
  published the earlier unpushed master commits: the recruiter presentation merges and the
  2026-09-23 investigation docs.
- Backend deployed by Claude with `ssh -A … BUZER_REPO_DIR=/home/skclaw/PROJECTS/buzer-app
  ~/PROJECTS/buzer-app/scripts/deploy-vps.sh`. The first SSH attempt failed in 1Password signing
  (`communication with agent failed`); the retry worked. There are no schema migrations in this
  change.
- The new APK was **not** installed on the phone.

Verification:
- Command: `gh run watch 43103493118 --exit-status`
- Result: PASS
- Evidence: `ci.yml` on `c2cd9ca`: `backend` success, `client` success.

Verification:
- Command: VPS pre-check, then `deploy-vps.sh`
- Result: PASS
- Evidence: before, the checkout was `447af22`, release `app.buzer@0.1.0+447af224f8d9`, healthy. The deploy printed `Buzer backend is healthy at http://127.0.0.1:18432/health` on attempt 8. Afterwards the checkout is `c2cd9ca`, release `app.buzer@0.1.0+c2cd9ca09350`, container started 21:33:49Z, Docker health `healthy`, 0 error-level log lines.

Verification:
- Command: authenticated `GET /tasks/snapshot` and `POST /review/dismiss` from inside the container (token read from the container env, not printed)
- Result: PASS
- Evidence: snapshot `200`, 240 tasks, 0 `hierarchyStatus: unsupported`, 0 offset-less `dueDatetime`; `/review/dismiss` returns `404`.

Remaining:
- Install a new APK built from `c2cd9ca` on the Pixel 10. Until then the phone runs the old
  client against the new backend, which the checks above show is compatible.
- Delete `feature/deepen-backend` and the two `deepen-*` worktrees.
- Stop the headless emulator (`adb -s emulator-5584 emu kill`).
