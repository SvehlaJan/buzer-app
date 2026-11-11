# Handoff: Morning Review delivery investigation (2026-09-23)

## Task

Owner report: reminders (Morning Review in particular) never arrive on the phone.
Investigation only. There is no plan file and no code changes. Findings are in
`docs/notes/2026-09-23-morning-review-deferred-by-overdue-tasks.md`.

## Result

Root cause reproduced. `BlockerPriorityResolver` treats every cached overdue task as an active
Deadline blocker, so the 08:00 alarm is re-deferred every minute all day and never posts a
notification. The control run with a clean cache delivers correctly.

## Files changed

- `docs/notes/2026-09-23-morning-review-deferred-by-overdue-tasks.md` (new)
- `docs/handoffs/2026-09-23-morning-review-delivery-investigation.md` (new)

## Commands / verification

Verification:
- Command: `./gradlew :androidApp:assembleDebug -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PSENTRY_ANDROID_DSN=`
- Result: PASS
- Evidence: `androidApp-debug.apk` built and installed on emulator-5554 (Buzer_API_36)

Verification:
- Command: `curl -X POST :18433/__reset {"morningReviewScenario":"overdue"}`, acknowledge 09-23, `adb shell cmd alarm set-time <2026-09-24 07:59 CEST>`
- Result: PASS (bug reproduced)
- Evidence: `morning_review_local_state` went from `daily:2026-09-24` to `priorityretry` at 08:01, 08:02 and 08:03; no `app.buzer.android` notification in `dumpsys notification`

Verification:
- Command: `pm clear`, `__reset {"morningReviewScenario":"clean"}`, set-time 07:56, sync, HOME, wait for 08:00
- Result: PASS (control delivers)
- Evidence: notification `android.title=Morning review` on channel `blockers` (importance 4) with `fullscreenIntent`; next state `daily:2026-09-25`

Verification:
- Command: overnight real-clock capture (`today` scenario, screen off, battery unplugged, `deviceidle force-idle` at 07:50, samples 07:55–08:10)
- Result: PASS (bug reproduced on real clock)
- Evidence: verdict `2026-09-24 08:10:22 REPRODUCED: no Morning Review notification; alarm deferred as PriorityRetry`

## Overnight run

Setup on emulator-5554, real clock: the `today` scenario was synced at 22:18 with 09-23
acknowledged, then HOME, screen off, and `dumpsys battery unplug`. Capture script:
scratchpad `overnight-capture.sh`.

- 23:18:53: the Deadline blocker fired for `task-current-overlapping`
  (`deadline_blocker show …`). Nobody acted on it, so its "Task deadline" notification stayed
  posted all night.
- 07:55–07:59: state `morning-review:daily:2026-09-24:1790229600000`, 0 Morning Review
  notifications.
- 08:00:00: the alarm was delivered on time. The only log is `snapshot_store load hit`, and
  the state became `priorityretry` for 08:01.
- 08:01 to 08:10: one on-time delivery per minute, each re-deferred. 0 Morning Review
  notifications throughout. The next retry was armed for 08:11:00.
- `dumpsys deviceidle force-idle` reported `Unable to go deep idle; stopped at INACTIVE`, so
  deep Doze was not exercised. This does not matter for the finding: every alarm fired on
  time.

The deferral path produces no `AppLog` line of its own. Only the `snapshot_store load hit`
debug line marks each delivery.

## Assumptions

- The phone's cached snapshot contains at least one overdue owner task, or one finished outside
  Buzer since the last sync. This is not verified on the device. See the "Confirming on the
  physical phone" checklist in the note.
- Android 16 emulator behavior matches the phone for this logic. The defect is pure app logic,
  not platform scheduling.

## Known gaps / skipped

- Sentry Logs not inspected. The only local token is a `sntrys_` org (CI) token that cannot
  read events or logs.
- No JVM or instrumentation tests run. There were no code changes.

## Next recommended step

Get the owner's decision on the priority semantics (note, "Fix options"). Then fix test-first,
migrating `BlockerPriorityResolverTest`, `MorningReviewAlarmHandlerTest`,
`MorningReviewAlarmInstrumentationTest`, the `priorityDeadline` fixture, and plan line 180.
Finish with `scripts/e2e-real-backend.sh`.

## Sentry triage (2026-09-24)

Second session, read-only. Sentry MCP verified with `whoami` (user id 4906078). No Sentry
issue was resolved, assigned or commented on. No GitHub issue was created and no code changed.
Window: 2026-09-22 09:00 to 2026-09-24 09:00 CEST. Logs were also checked back to 2026-09-10 for
the delivery question. Times are CEST (UTC+2) unless marked Z.

### Issues and feedback

- `buzer-android`: no issue had activity in the last 48 h. The newest unresolved issue is
  BUZER-ANDROID-D (below). The other seven unresolved issues (C, 9, B, A, 8, 7, 5) were last
  seen between 2026-08-25 and 2026-09-03 and were not re-triaged.
- `buzer-backend`: 0 issues in 90 days, any status.
- User feedback: none in the window. The last submission is BUZER-ANDROID-6
  (2026-08-25, "Date picker should show day of week"). Logs show `bug_report_form_opened` at
  2026-09-24 08:07:07 with no `bug_report_submitted` after it, so a report was started and
  not sent.

**BUZER-ANDROID-D: ApplicationNotResponding: Background ANR**

- 1 event. First and last seen 2026-09-20 07:05:27 (05:05:27Z). Release
  `app.buzer@0.1.0-dev+e859f1e21d4b`, environment `production-device`, mechanism
  `AppExitInfo`, level fatal.
- Correlation tags: none (no `request_id`, `task_id`, `action_id`, or `occurrence_key`).
- The main thread was idle in `nativePollOnce`. Top in-app frames are on
  `DefaultDispatcher-worker-2` (Runnable): `SnapshotStore.load(SnapshotStore.kt:71)` ←
  `EnforcementCoordinator.applyFreshSnapshot(EnforcementCoordinator.kt:294)` ←
  `EnforcementCoordinator.refreshAndReconcile(EnforcementCoordinator.kt:60)`, inside the
  kotlinx JSON decode of `TaskSnapshotItem`.
- Log timeline: `boot_reconcile received` 07:04:26 → cached snapshot load 07:04:40 (14 s) →
  `refresh start` 07:04:42 → `refresh fetched scheduleVersion=38114` 07:04:58 → no
  `snapshot_store save` and no `boot_reconcile complete`. The ANR came at +60.7 s, which is
  Android's background-broadcast timeout. The 8 s `withTimeout` in `BootReconcileRunner` did
  not bound the refresh: the fetch alone took 15.7 s, and the decode is blocking work that
  ignores cancellation.
- Effect: the fresh snapshot was never saved. The 08:00 check that day still used the
  2026-09-14 cache.

### Errors seen on the phone (Logs only, no issue)

At 2026-09-24 08:00:10, release e859f1e, right after the review opened:

- WARN `backend_request_failed request_id=26b043ed-10f5-4862-9fb6-5ac461f23a72
  route=/tasks/snapshot method=GET status_code=503 duration_ms=9112
  error_code=forced_refresh_failed`, with "Todoist operation failed: server".
- ERROR `morning_review forced sync failed`, from the forced sync that
  `MorningReviewActivity` runs on open.
- WARN `action=REFRESH_SNAPSHOT taskId= message=Todoist operation failed: server`. The same
  `BlockerErrorReporter.report` call posts the "Backend action failed. Buzer backend action
  failed. Check VPS/backend health." notification (`showNotification` defaults to true). This
  is most likely the error the owner saw.
- Why no issue exists: the phone runs a build from `feature/level-1-mutation-simplification`
  (e859f1e is on no other branch). That build never captures `BuzerApiException` in
  `isCaptureWorthyNonFatal`. The backend on that branch treats `forced_refresh_failed` as
  operational: it calls `obs.warn` and skips `captureRequestFailure`. So Sentry has no backend
  5xx for this `request_id`, and the backend detail exists only in VPS stdout.
- Transient: the 09:00 refresh succeeded (`scheduleVersion=43987`) and two Start blockers
  fired.

### Morning Review deliveries in Logs since 2026-09-10

`overlay_start skipped reason=invalid_request kind=MORNING_REVIEW` appears on **2026-09-24
only** (08:00:00, `morning-review:daily:2026-09-24`). Earlier occurrences are only on
2026-08-31 and 2026-09-02, as snooze/priorityretry bursts.

Telemetry was live throughout: release e859f1e has been on the phone since 2026-09-02 and has
sent 18,489 log lines. Every morning from 2026-09-10 to 2026-09-23 shows the deferral
signature: `snapshot_store load hit` at 08:00:00 and then once a minute (15 lines between 07:59
and 08:15), with no delivery line. On 2026-09-22 there were 958 of these lines from 08:00 to
midnight, and Buzer logged nothing else that day.

| Dates | Cache used at 08:00 (`serverTime`) | Result |
| --- | --- | --- |
| 09-10 to 09-14 | 2026-09-02 23:09 (`scheduleVersion=13192`) | PriorityRetry loop, no delivery |
| 09-15 to 09-23 | 2026-09-14 10:09 (`scheduleVersion=29673`) | PriorityRetry loop, no delivery |
| 09-24 | 2026-09-23 22:00 (`scheduleVersion=43331`) | Delivered at 08:00:00; the forced sync then failed (503) |

This confirms the root cause on the physical phone, so the "not verified on the device"
assumption above no longer applies. The cache was refreshed only on 09-02, 09-14 and 09-23.
A fourth refresh, at boot on 09-20, was lost to the ANR.

### Other observations

- Backend Sentry routing: BUZER-ANDROID-7 (`todoist_http_503`, 2026-08-25) is a Node event
  from `production-vps` (release `app.buzer@0.1.0+0ab2f075d668`, marker
  `buzer.backend.todoist_poll_failure`), filed under `buzer-android`. The VPS was using the
  Android DSN at that release. The current VPS configuration was not checked.
- WARN and ERROR lines arrive twice in Sentry Logs; DEBUG and INFO lines arrive once. The
  likely cause is the logcat integration running alongside the AppLog sink. Not verified.
- On 2026-09-22 no Start or Deadline blocker fired all day. The likely cause is the 8-day-old
  cache (there is no background sync).

### Triage table

| Signal | Suspected cause | Relation to Morning Review finding | Recommended next step |
| --- | --- | --- | --- |
| No delivery 09-10 to 09-23 (Logs) | PriorityRetry loop on the stale cache | This is the finding, now confirmed on the phone | Owner decision on fix options, then the planned test-first fix |
| 503 `forced_refresh_failed` and "Backend action failed" notification, 09-24 08:00:10 | Todoist returned a server-class error during the forced refresh; transient (09:00 refresh succeeded) | Independent. It happened on the first delivery in 14+ days | Read VPS stdout for request 26b043ed…; consider softer copy than "Check VPS/backend health" for upstream Todoist failures |
| BUZER-ANDROID-D Background ANR, 09-20 07:05 | Boot `goAsync` work exceeded the 60 s broadcast timeout; the 8 s `withTimeout` does not stop the blocking decode | Independent defect that made things worse: the lost boot refresh kept the 09-14 cache | Separate task: move the boot refresh out of the broadcast window (for example, WorkManager) and make the refresh bound effective |
| `buzer-backend` empty for 90 d; BUZER-ANDROID-7 is a backend event | VPS `SENTRY_DSN` pointed at the Android project | Independent (observability) | Check the VPS DSN so backend 5xx and background failures land in `buzer-backend` |

### Verification (triage)

Verification:
- Command: Sentry MCP `whoami`
- Result: PASS
- Evidence: user id 4906078 in org `jans-gi`

Verification:
- Command: `search_issues` with `is:unresolved lastSeen:-48h` (both projects), `lastSeen:-7d` (org), `lastSeen:-90d` (`buzer-backend`), `issue.category:feedback` (30d)
- Result: PASS
- Evidence: 0 / 0 issues in 48 h; BUZER-ANDROID-D is the only issue in 7 d; 0 backend issues in 90 d; 1 feedback (BUZER-ANDROID-6, 2026-08-25)

Verification:
- Command: `search_events` dataset=logs, `message:"*overlay_start skipped*" message:"*MORNING_REVIEW*"`, 30d
- Result: PASS
- Evidence: 54 lines; since 2026-09-10 only 2026-09-24T06:00:00Z

Verification:
- Command: `search_events` dataset=logs, per-day aggregates for `timestamp:>=YYYY-MM-DDT05:59:00 timestamp:<YYYY-MM-DDT06:15:00`, 2026-09-10 to 2026-09-23; full-day aggregate for 2026-09-22
- Result: PASS
- Evidence: every day had exactly one message group, `snapshot_store load hit …`, with count 15; 2026-09-22 had 958 in 06:00Z–24:00Z

Skipped:
- VPS stdout for request 26b043ed… (this needs SSH, which was not authorized for this session).
- Replays and screenshots for BUZER-ANDROID-D (a background ANR has none).
- The current VPS `SENTRY_DSN` value.

## Update 2026-09-29: new data since 2026-09-24

The owner authorized VPS access. SSH to `oc-vps.taila50381.ts.net` failed twice with
`sign_and_send_pubkey: signing failed … communication with agent failed`: the 1Password SSH
agent lists the keys but did not sign. No VPS data has been read yet. I did not retry again.

### Morning Review deliveries 2026-09-25 to 2026-09-29

The delivery line appears every day at 08:00:03–04. There was no deferral loop, because the
cache was fresh enough at each 08:00. The forced sync that runs when the review opens failed on
three of those days, each time for a different reason:

| Date | Forced sync result |
| --- | --- |
| 09-25, 09-26 | OK (no error logged) |
| 09-27 | `UnknownHostException` for `oc-vps.taila50381.ts.net` (phone offline or DNS just after wake); `/projects` failed the same way |
| 09-28 | 503 `forced_refresh_failed` "Todoist operation failed: server", request `bbf49391-b1a0-4810-9c47-a438ba1334b1`, 6.5 s |
| 09-29 | Snapshot fetched and **saved**, then `reconcile` threw `InstantFormatException` (BUZER-ANDROID-E) |

Both 503s (09-24 and 09-28) happened at about 06:00:05Z, the moment the review's forced
refresh runs. VPS logs are needed to tell whether Todoist really returned a 5xx at that time or
whether something else on the backend collides at 08:00.

### BUZER-ANDROID-E: InstantFormatException (new, high impact)

- 3 events, 2026-09-29 08:00:11 and 08:41:09/08:41:13. Release e859f1e, environment
  `production-device`, tags `marker=enforcement.refresh_snapshot`, `app_section=Enforcement`.
  One replay is attached (`c058693b10504f00ba567ddba1c00434`). No `request_id`, `task_id`, or
  `occurrence_key` tags.
- Top in-app frames: `AlarmPlanCalculator.desiredAlarmsFor(AlarmPlanCalculator.kt:82)` ←
  `AlarmPlanCalculator.calculate(:61)` ← `EnforcementCoordinator.reconcile(:182)` ←
  `applyFreshSnapshot(:297)` ← `forceSyncAndReconcile(:69)`.
- Cause: a task in the snapshot has `dueDatetime = "2026-10-11T09:00:00"`, a Todoist floating
  datetime with no offset. The backend copies Todoist's raw `due_datetime` into the snapshot.
  `AlarmPlanCalculator` calls `Instant.parse` on it, which requires an offset, so it throws.
  Every other client parser of `dueDatetime` tolerates the format (`HomeScreenState`,
  `OwnerAgendaSelector`, and `MorningReviewAlarmHandler`, which uses `runCatching`). Master
  has the same strict parse (`AlarmPlanCalculator.kt:84`).
- Why it persists: `applyFreshSnapshot` calls `snapshotStore.save` **before** `reconcile`. The
  poisoned snapshot (`scheduleVersion=51170`) is now the phone's cache, so every later
  reconcile throws: Home refresh, boot reconcile, and force sync. `scheduledAlarmRegistry` is
  never updated. Alarms armed by the last successful reconcile (cache from 2026-09-26 06:00Z)
  stay in place, but no new or changed task gets a Start or Deadline alarm until this task is
  changed in Todoist or the parser is fixed. This directly produces "reminders not arriving",
  independent of the Morning Review deferral bug.
- Backend side: `computeStartAt` parses the same value with `new Date(...)`, which reads it as
  container-local time (UTC). `startAt` therefore comes out as 09:00Z, 2 h later than a
  09:00 CEST reading.
- Probable origin: Buzer's own add/edit screens send an offset (`toIsoInstantString(zoneId)`),
  so the task was most likely created or edited in Todoist itself with a floating time. Not
  verified. The VPS DB would identify it (tasks whose `due_datetime` has no `Z` or offset).
- The owner opened the bug-report form at 08:04:30 and 08:41:10 but did not submit it.

### Other log items since 2026-09-24

- 2026-09-24 17:10:50: `POST /tasks/5723e14b…/done` timed out on the client after 15 s
  (request `0af60798-c1f5-44c7-a79d-d0da5b0f5ebd`, 17.8 s). The Done outcome is unknown from
  the phone side; VPS logs are needed.
- 2026-09-26 02:24:54: after a reboot, `boot_reconcile refresh=timeout afterMs=8000`. The 8 s
  bound worked this time and there was no ANR.
- No new backend events in any project (`platform:node`, 30 d: none).

### Updated triage table

| Signal | Suspected cause | Relation to Morning Review finding | Recommended next step |
| --- | --- | --- | --- |
| BUZER-ANDROID-E, 09-29 | Floating Todoist `dueDatetime` + strict `Instant.parse` in `AlarmPlanCalculator`; the snapshot is saved before reconcile, so the failure persists | Independent. It blocks all new Start/Deadline alarms from 09-29 on | Short term: re-save that task in Todoist with a timezone. Fix: normalize floating datetimes on the backend (owner timezone) and make the client planner skip or tolerate unparseable tasks instead of aborting the whole plan |
| 503 `forced_refresh_failed` 09-24 and 09-28 at ~08:00:05 | Todoist server error, or a backend collision at 08:00 | Independent | VPS logs for 26b043ed… and bbf49391… |
| Done timeout 09-24 17:10 | Slow backend or Todoist mutation (>15 s) | Independent | VPS logs for 0af60798…; confirm the action journal outcome |
| DNS failure 09-27 08:00 | Phone offline right after wake | Independent, transient | None |

## VPS findings (2026-09-29, read-only)

SSH worked on the third attempt, after the owner approved 1Password. Commands run: `git log`,
`git reflog`, `git remote`; `docker compose ps` and `docker inspect`; `docker exec printenv`
(DSN key masked); `docker logs` windows and greps; and one SQLite query through the
container's `better-sqlite3` opened with `readonly: true` (script in the session scratchpad,
piped to `node -`). Nothing was restarted, deployed, or written.

### Correction to earlier sections

GitHub `master` is `6e18826` (= `feature/level-1-mutation-simplification`). The primary local
checkout's `master` (`478adc9`) is simply 60 commits behind `origin/master` (0 ahead). The
earlier statements that e859f1e and `forced_refresh_failed` exist "only on the level-1 branch"
came from that stale checkout. The phone build (e859f1e), the VPS (6e18826) and GitHub master
are one line of history. The strict parse is at `AlarmPlanCalculator.kt:82` on current master,
and `BlockerPriorityResolver` is at `MorningReviewAlarmHandler.kt:25`. Line references
elsewhere in this handoff and in the note were taken from the stale checkout.

### Deployment

- Checkout `/home/skclaw/buzer-app` is on `master` at `6e18826` (= `origin/master`). The only
  dirty file is an untracked `backup.log`.
- Container `buzer-backend` has been up since 2026-09-02T19:57:11Z and is healthy. Release
  `app.buzer@0.1.0+6e18826fca36`, environment `production-vps`, `TZ` unset (UTC).
- `SENTRY_BACKEND_DSN` now points at project `4511950617641040` (`buzer-backend`), so the
  08-25 misrouting (BUZER-ANDROID-7) has since been fixed. The empty `buzer-backend` project
  means no non-operational failure has been captured since 09-02.

### Requests

| Request | Backend log | Result |
| --- | --- | --- |
| `26b043ed…` 09-24 | arrived 06:00:04.7Z (the phone sent it at 06:00:01.0; 3.7 s in transit) | 503 after 4.0 s |
| `bbf49391…` 09-28 | arrived 06:00:08.3Z | 503 after 2.6 s |
| `0af60798…` 09-24 Done | **never arrived** at the backend | phone timed out after 15 s; a retry `59a7597a…` arrived 16:12:24Z and returned 200 in 1.1 s, so the Done applied about 1 h late |

`obs.warn("forced_refresh_failed", …)` writes nothing to stdout, so the Todoist HTTP code for
the two 503s was not recorded anywhere. The adapter maps any Todoist 5xx or non-4xx response to
kind `server`, so Todoist itself returned a 5xx. These two are the only 5xx responses in the
entire container log.

### Todoist 5xx cluster at the top of the hour

The poller logged 22 `Todoist sync failed … server` errors (plus 1 `transport`) between
2026-09-15 and 2026-09-29. 10 of them fell at `hh:00:04–12`, 9 at `hh:05–09`, and 1 at
`hh:30`. The poller runs every 5 minutes, so the `:00` poll fails far more often than the
others. Both Morning Review forced refreshes at 08:00 CEST (06:00:05Z and 06:00:08Z) hit that
spike. The poller recovers silently; Sentry sees nothing, because `captureBackgroundFailure`
fires only after 3 consecutive failures.

### Floating Todoist datetimes are common

The query found 29 tasks whose `due_datetime` has no `Z` or offset and whose `due_timezone`
is null. That includes 11 of the 22 open timed tasks, and recurring ones ("every year 9:00",
"every 6th 9:00"). Until 2026-09-27 every floating task lacked a duration, so
`AlarmPlanCalculator` skipped it as invalid before parsing `dueDatetime`. The first floating
tasks **with** a duration are:

- `4e116293-7aae-4a5b-8f1b-9931174e33cf` (Todoist `6hf734q29PhgJj3q`), due `2026-10-11T09:00:00`, 15 min, unassigned, added 2026-09-27T08:50:18Z
- `3299499d-60cf-42a5-a710-4fd905944fc1` (Todoist `6hf72wrJCHvvH84q`), due `2026-10-11T09:00:00`, 15 min, unassigned, added 2026-09-27T08:49:33Z

Neither the phone nor the backend logged any add-task request in 08:45–08:55Z that day, so both
were created in Todoist directly. They reached the phone on 09-29 (the 09-27 and 09-28 syncs
failed), and that sync triggered BUZER-ANDROID-E. The crash persists while either task is open
and has a duration. Any future floating task with a duration will cause the same failure.

Timing error on the backend: `computeStartAt` parses a floating value with `new Date()` in a
UTC container. A floating 09:00 therefore becomes 09:00Z (11:00 CEST in summer, 10:00 CET in
winter), not 09:00 local.

### Updated recommendations

1. Immediate workaround (owner, in Todoist): for the two 11 Oct tasks, either set a fixed time
   zone on the date or remove the duration. Then open Buzer once so a clean snapshot replaces
   the poisoned cache.
2. Fix BUZER-ANDROID-E (separate task): the backend should normalize floating `due_datetime`
   to an instant using the owner's timezone (Todoist user `tz_info`, or explicit config) before
   computing `startAt`/deadline and before emitting `dueDatetime`. The client planner should
   skip or log a task with an unparseable datetime instead of aborting the whole plan.
   Consider reconciling before saving, or not persisting a snapshot the planner cannot process.
3. Forced refresh at 08:00: retry once after a short jittered delay on a Todoist 5xx, or
   start the review's forced sync a few seconds after the minute. Make the "Check VPS/backend
   health" notification say "Todoist temporarily unavailable" for upstream 5xx.
4. Backend observability: make `obs.warn` actually log (with `http_code`), so operational
   failures leave evidence in stdout.
5. The Morning Review deferral fix is unchanged and still awaits the owner's approval.

Verification:
- Command: `ssh oc-vps.taila50381.ts.net 'docker logs … | grep <request ids>'`
- Result: PASS
- Evidence: 26b043ed and bbf49391 → 503 at 06:00:04.7Z / 06:00:08.3Z; 0af60798 absent; 59a7597a → 200

Verification:
- Command: read-only SQLite query for `due_datetime` without an offset (`better-sqlite3`, `readonly: true`)
- Result: PASS
- Evidence: 29 floating tasks; exactly 2 open floating tasks with a duration (both due 2026-10-11T09:00:00)

## Correction and planning (2026-09-29)

- Correction: `obs.warn` does not write to stdout; it writes to **Sentry Logs** in `buzer-backend`.
  Both forced-refresh failures are there (`forced_refresh_failed`, `request_id` 26b043ed… at
  06:00:07Z and bbf49391… at 06:00:10Z), along with 21 `todoist_poll_failed` records. Only
  the Todoist `http_code` is missing. Recommendation 4 above is withdrawn.
- The owner decided the fixes in a grilling session. The decisions are in
  `docs/decisions/2026-09-29-reminder-reliability.md`, and the implementation plan is in
  `docs/superpowers/plans/2026-09-29-reminder-reliability.md`. Both are uncommitted, in the
  worktree `.worktrees/reminder-reliability` (branch `feature/reminder-reliability`,
  from `origin/master` 6e18826, upstream unset). No code has changed yet.
- The owner chose to leave the two floating 11 Oct tasks as they are until the fix ships. New
  reminders stay blocked until then.

## Closed (2026-11-11)

- The reminder reliability fixes were merged to `master` as `3150fc1` on 2026-09-30, the backend
  was deployed, and the APK was installed on the Pixel 10. The rollout and live checks are in
  `docs/handoffs/2026-09-29-reminder-reliability-implementation.md` ("Rollout (2026-09-30)").
- This handoff and the companion note were committed to `master` on 2026-11-11 so the links from
  the decision, the plan and the implementation handoff resolve. The only content change was the
  status update at the top of the note and this section.
