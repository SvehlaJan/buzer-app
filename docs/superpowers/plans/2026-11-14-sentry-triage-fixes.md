# Sentry Triage Fixes (2026-11-14)

Source: Sentry org `jans-gi`, last 30 days. Only two unresolved, non-ignored items exist:
one error (`BUZER-ANDROID-Q`) and one user-feedback report (`BUZER-ANDROID-R`). `buzer-backend`
has no unresolved issues in 90 days. Log search turned up two further problems that do not show
as issues (tasks 3 and 4).

Ranked by user impact.

---

## Task 1 — Stale start blocker reappears after the task is completed (BUZER-ANDROID-R)

**Feedback** (2026-11-13 08:15, Pixel 10, release `+25fbc0765e70`): "i already marked this as done".

**Timeline reconstructed from Sentry logs** (task `785db0c4…`, occurrence `bd80b13f…`):

| Time (UTC) | Event |
|---|---|
| 07:45:00 | START_BLOCKER alarm, keyguard locked → full-screen intent → `StartBlockerActivity` created (`start_blocker prepare`) |
| 08:00:00 | DEADLINE_BLOCKER alarm → `DeadlineBlockerActivity` launched on top of the still-alive start blocker |
| 08:07:09 | User taps Done → `COMPLETE_TASK` succeeds (`mutation applied`, visible 21→20, completed 215→216) → deadline blocker `finish()` |
| 08:07–08:15 | The 07:45 `StartBlockerActivity` is now on top again, still showing the completed task |
| 08:15:20 | User files the feedback |
| 08:15:24 | User taps Start → `POST /tasks/…/start` → **409 `stale_occurrence`** → blocker shows a warning banner and *still* stays open |

**Root cause.** `BlockerHostController.refresh()` runs only in `onCreate` and `onNewIntent`
(`client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt:42-57`,
`DeadlineBlockerActivity.kt:44-59`). A blocker that comes back to the foreground never re-checks
whether its occurrence was resolved meanwhile. Two contributing gaps:

1. `runMutation` treats `Conflict` (incl. `stale_occurrence`) as "show warning, stay open"
   (`BlockerHostController.kt:135-138`) and never re-evaluates `isResolved` on the reloaded snapshot.
2. A successful mutation in one blocker does not dismiss sibling blockers / notifications for the
   same task (start + deadline both pending for the same occurrence is a normal case when a task
   is ignored past its start).

**Fix steps**

- [x] Add an `onResume` refresh path to `BlockerHostController` (e.g. `onForeground()`): first
      apply the cached snapshot and `finish()` immediately if `BlockerTaskSnapshotGate.shouldHideAfterRefresh`
      says resolved; otherwise do the existing network lifecycle refresh. Skip on the very first
      resume (onCreate already refreshes). Wire it from both blocker activities' `onResume`.
- [x] After `Conflict` / `Warning` / `LocalReconciliationFailed`, reload the cached snapshot and
      `finish()` if the bound occurrence is now resolved, instead of only showing the banner.
- [x] ~~On `Success` of a terminal action, cancel sibling blocker notifications~~ — not needed:
      `EnforcementCoordinator` already calls `BlockerNotificationCleaner.cancelForTask`, which
      clears all blocker kinds for the occurrence, whenever an action's snapshot is applied.
- [x] Tests first (JVM): extend `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerHostControllerTest.kt`
      with (a) resume with a cached snapshot showing the occurrence resolved → finishes without a
      network call, (b) `Conflict` result whose reloaded snapshot is resolved → finishes.
- [x] Instrumentation: add a scenario to
      `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
      (or `RealBackendScenarioTest.kt`): open start blocker, open deadline blocker for the same
      occurrence, complete from deadline → start blocker must not remain visible. Existing tests in
      both files assert current blocker lifecycle and must be re-run/migrated.
- [ ] Verify: `:androidApp:testDebugUnitTest`, then `scripts/e2e-real-backend.sh` scoped with
      `ANDROID_TEST_CLASS`, then the full unfiltered suite (UI-affecting change).
- [ ] Commit with `Fixes BUZER-ANDROID-R`.

---

## Task 2 — Snapshot refresh timeouts reported as errors (BUZER-ANDROID-Q)

**Event** (2026-11-01 08:00, release `+11f4e37603ea`): `RuntimeException: Blocker action error:
REFRESH_SNAPSHOT - snapshot refresh timed out` from `BlockerHostController.kt:181`.

**What the breadcrumbs show.** It is not a one-off: between 07:09 and 08:00 every `GET
/tasks/snapshot` and `GET /projects` from the phone hit the 5 000 ms Ktor request timeout
(`duration_ms` 5 780–6 207), while one parallel `force_sync` took ~11.6 s to succeed. Snapshot
payload is ~177 KB (241 tasks, 215 of them completed). The blocker behaved correctly (showed
cached state + stale banner); the problem is the slow backend path plus the alert noise.

**Fix steps**

- [ ] Investigate backend latency for `/tasks/snapshot` on the VPS around 2026-11-01 07:00–08:00
      UTC (stdout logs; check whether a Todoist poll or WhatsApp work held the event loop, and
      whether the Tailscale path was degraded). Record findings in `docs/notes/`.
- [ ] Measure snapshot build time vs. size; if the 215 completed tasks dominate, consider
      trimming completed tasks older than N days from the snapshot (needs a decision doc — it
      touches the snapshot contract).
- [ ] Downgrade a pure refresh *timeout* (no throwable, cached snapshot available) from a Sentry
      error event to a structured warning log, keeping error events for real failures. Update
      `BlockerHostControllerTest` assertions on `errorReporter.report` for the timeout path.
- [ ] Resolve `BUZER-ANDROID-Q` in Sentry once the downgrade ships (or after the backend fix if
      latency is the real defect).

---

## Task 3 — Instrumentation tests report to Sentry as `production-device`

**Evidence.** On 2026-11-11 21:03–21:06 Sentry received dozens of logs with fixture IDs
(`task-deadline`, `task-one-time-empty`, `child-incomplete-open`, HTTP 500/502/504 from the e2e
server) tagged `environment=production-device`, release `+88a0dff10661`, project `buzer-android`.
`client/androidApp/build.gradle.kts:55-56` bakes `SENTRY_ANDROID_DSN` / `SENTRY_ENVIRONMENT` from
`local.properties` into every build, and `scripts/e2e-real-backend.sh` does not override them.
This pollutes production triage and could create false issues.

**Fix steps**

- [ ] In `scripts/e2e-real-backend.sh`, pass `-PSENTRY_ANDROID_DSN=` (empty → Sentry disabled) or
      `-PSENTRY_ENVIRONMENT=e2e`; confirm `configValue` precedence lets a Gradle property override
      `local.properties`. Prefer empty DSN.
- [ ] Alternatively/additionally, have the instrumentation runner disable Sentry at startup.
- [ ] Verify: run one scoped e2e class, then `search_logs` in Sentry for the run window returns
      nothing in `production-device`.

---

## Task 4 — Misleading `overlay_start skipped reason=invalid_request` warnings

**Evidence.** Every DEADLINE_BLOCKER and MORNING_REVIEW alarm logs a `warn` with
`reason=invalid_request` (`enforcement/AlarmReceiver.kt:49-50`), because
`OverlayBlockerRequest.fromExtras` intentionally returns `null` for every kind except
START_BLOCKER (`overlay/OverlayBlockerRequest.kt:117-120`). It is by design, yet reads as a bug
during triage (it showed up in both investigations above).

**Fix steps**

- [ ] In `AlarmReceiver.openBlocker`, check for overlay-supported kinds before building the request;
      log `overlay_start skipped reason=unsupported_kind` at `info`, keep `invalid_request` `warn`
      only for a START_BLOCKER with missing identity.
- [ ] Small JVM test if `openBlocker` decision logic is extracted; otherwise covered by existing
      tests (log-only change).

---

## Not actionable

- 2026-11-12 07:00 `GET /tasks/snapshot` 503 `forced_refresh_failed` — "Todoist operation failed:
  server"; upstream Todoist outage, handled correctly.
- `BUZER-ANDROID-P` — test feedback, already archived.
- Replay `52b26785…` attached to the feedback returned 404 (not retained); timeline above is
  from logs.

## Suggested order

1 (user-visible bug) → 3 (one-line, stops triage pollution) → 4 (trivial) → 2 (needs backend
investigation before deciding the fix).
