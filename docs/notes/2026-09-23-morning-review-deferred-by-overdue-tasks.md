# Morning Review never delivered while any overdue task is cached

Date: 2026-09-23
Status: resolved. The root cause was reproduced on the emulator and confirmed on the phone. The
fix shipped in `3150fc1` (merged 2026-09-30) per `docs/decisions/2026-09-29-reminder-reliability.md`:
Morning Review priority is now "active Start blocker only, capped at 08:15", close to fix option 2
below. Rollout evidence is in `docs/handoffs/2026-09-29-reminder-reliability-implementation.md`.
Line references below come from a checkout that was behind `origin/master` at the time and
predate the fix.

## Symptom

The owner reports that no automated reminders arrive on the phone, Morning Review in particular.
The review only appears after Buzer is opened manually, through the in-app catch-up.

## Root cause

The 08:00 alarm is scheduled and delivered correctly. The alarm handler then refuses to show it.

1. `BlockerPriorityResolver.resolve`
   (`client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt:33`)
   returns `Deadline` for **any** cached task that is unfinished, valid, assigned to the owner,
   and has `now >= start + duration`. That is exactly Home's definition of *overdue*, with no
   upper bound.
2. On `Deadline` or `Start`, the handler calls `deferForPriority` and returns `Deferred`
   (`MorningReviewAlarmHandler.kt:131-135`). No notification is posted, and a `PriorityRetry`
   alarm is armed one minute later (`MorningReviewDependencies.kt:19`).
3. Each retry evaluates the same cached snapshot, which only refreshes while Buzer is in the
   foreground (there is no background sync). So the review is re-deferred every minute for the
   rest of the day. At local midnight the stale `reviewDate` reconciles to the next day's
   08:00, and the cycle repeats.

A Deadline blocker is only ever armed for a **future** deadline
(`shared/.../enforcement/AlarmPlanCalculator.kt:117`, `deadlineAt > now`). An overdue task
therefore has no live blocker that Morning Review could be yielding to. The spec also states
that overdue work does not block the review (`2026-08-02-smart-home-morning-review-readiness-design.md:260`).

The defect is in the plan, not only the code. The plan defines Deadline priority as
`now >= start + duration` (`docs/superpowers/plans/2026-08-02-daily-morning-review.md:180`).
The e2e fixture `priorityDeadline` reuses `task-overdue-1`
(`backend/test/fakes/e2eFixtures.ts:45`), so
`MorningReviewAlarmInstrumentationTest.cachedStartAndDeadlinePriorityDeferReviewThenClearedCacheAllowsRetryDelivery`
locks in the faulty behavior.

A stale cache makes it worse. A task finished directly in Todoist stays unfinished in the
cached snapshot until the next foreground sync. If its interval ended before 08:00, it
suppresses that morning's review too.

## Why it looks like "never"

- The deferral path logs nothing: no `AppLog` line and no notification.
- In-app catch-up (`MorningReviewEntryDecider`) ignores blocker priority. Opening Buzer shows
  the review immediately, which hides the bug during manual testing.
- Side effect: a `setAlarmClock` wakeup every minute all day. The status-bar "next alarm" stays
  under a minute away, which costs battery.

## Reproduction (emulator `Buzer_API_36`, Android 16, Europe/Bratislava)

Debug APK built against the local e2e backend
(`-PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PSENTRY_ANDROID_DSN=`).
Readiness was granted via `appops`/`deviceidle`. The wall clock was moved with `adb shell cmd alarm set-time`.

| Run | Cached snapshot | Result at 08:00 |
| --- | --- | --- |
| A | `overdue` scenario (4 tasks past deadline), 09-23 acknowledged | Alarm fired. State became `morning-review:priorityretry:2026-09-24:…` and re-deferred at 08:01, 08:02, 08:03. **No notification posted.** |
| B (control) | `clean` scenario, app backgrounded | Alarm fired. "Morning review" heads-up notification posted (channel `blockers`, importance HIGH, full-screen intent). Next alarm `daily:2026-09-25`. |

Overnight real-clock run with the `today` scenario: a task in progress at the last sync hit
its deadline at 23:18. Its Deadline blocker fired and was ignored. At 08:00 the review
alarm was delivered on time and then re-deferred every minute until the capture ended at
08:10, with 0 Morning Review notifications. Details are in
`docs/handoffs/2026-09-23-morning-review-delivery-investigation.md`.

## Confirming on the physical phone

- Home shows a non-empty **Overdue** section (a precondition for the bug).
- After 08:00, the status bar / quick settings shows a Buzer alarm within the next minute,
  all day.
- `adb shell dumpsys alarm | grep -A2 MORNING_REVIEW`: after 08:00 the tag keeps being
  re-armed about one minute ahead.
- Sentry Logs: every successful delivery logs
  `overlay_start skipped reason=invalid_request kind=MORNING_REVIEW` (WARN). If that line is
  missing on mornings the phone was on, no delivery happened. Reading logs requires a
  personal token with `org:read`/`project:read`/`event:read`. The `sntrys_` org token in
  `~/.sentryclirc` is CI-only.

## Fix options (not implemented)

1. **Recommended.** Deadline priority applies only when the deadline passed within a short
   window, the moment the Deadline blocker actually fires. Start priority stays as it is
   (the planner actively nags in-progress, unstarted tasks). Overdue alone never suppresses
   the review. Add a hard cap as a safety net against stale-cache false positives: once
   about 15 minutes past the scheduled review time, launch regardless. Do not key
   priority on "Deadline notification still posted". In the overnight run, an ignored 23:18
   Deadline notification was still posted at 08:00, and that rule would still have
   suppressed the review.
2. Minimal. Drop the overdue→Deadline rule and keep only Start priority, plus the cap.
3. Cap only. This still delays the review every morning that any overdue task exists.

Any option must migrate `BlockerPriorityResolverTest`, `MorningReviewAlarmHandlerTest`,
`MorningReviewAlarmInstrumentationTest`, the `priorityDeadline` e2e fixture, and the plan
text at line 180.

## Secondary observations (not the cause)

- Any reconcile after 08:00 drops an unacknowledged same-day review. The trigger can be
  `TIME_SET`, a timezone change, or a package replace. `desiredAlarm` only preserves
  Snooze, so a pending `PriorityRetry` is replaced by tomorrow's Daily. Observed on the
  emulator on 2026-09-24: an automatic `TIME_SET` at 08:15:09 turned
  `priorityretry:2026-09-24` into `daily:2026-09-25`. Opening the app still shows the
  in-app catch-up.

- The overlay is Start-blocker-only, so an unlocked phone gets Morning Review as a heads-up
  notification rather than a full-screen blocker. Each delivery logs a WARN
  `overlay_start skipped reason=invalid_request`, which is noise.
- There is no background snapshot sync. Start/Deadline alarms for tasks created in Todoist
  after the last Buzer open are not armed until the app is opened. This is a known
  architecture limit (FCM invalidation is out of MVP scope).
