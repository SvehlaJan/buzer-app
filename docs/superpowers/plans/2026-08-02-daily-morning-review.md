# Daily Morning Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one reliable, local-date Morning Review each day with early Home entry, an automated 08:00 blocker, repair-first review flow, non-blocking overdue/day summary, local acknowledgement, and five-minute Snooze.

**Architecture:** Android owns Morning Review identity and timing through a small SharedPreferences-backed state store, a pure time/zone calculator, and a focused scheduler that maintains exactly one Morning Review alarm. Home and app-open catch-up derive availability from that local state; the full-screen activity loads cached authority immediately, forces a fresh Todoist sync through Change 1, and renders a pure review model built from the same owner-agenda selector as Home. No new backend state or endpoint is introduced.

**Tech Stack:** Kotlin, Android AlarmManager, BroadcastReceiver, SharedPreferences, Java time, Kotlin Multiplatform snapshot models, Compose Material 3, AndroidX Activity Result API, JVM tests, Compose instrumentation, and the repository's real-backend E2E harness.

---

## Handoff instructions for the implementer

This plan is self-contained and deliberately scoped to Change 2 from the accepted 2026-08-02 design. Do not rely on the planning chat.

### Hard execution gate: accept Change 1 first

Do not start implementation until Smart Home and True Refresh (Change 1) has been reviewed, verified, and integrated into the branch used for this work. Change 2 intentionally depends on Change 1's:

- authenticated `POST /tasks/sync` client/coordinator path;
- complete owner snapshot including all future tasks and genuinely unassigned roots;
- pure Home state builder and owner Now/Overdue/Future classification;
- Home attention strip with no permanent Morning Review FAB/menu action;
- migrated `BlockerActivityFakeBackendTest` Home fixtures.

The accepted Change 1 plan is `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`. Reconcile this plan's exact file map against the accepted Change 1 implementation handoff before checking Task 1; if Change 1 did not create `HomeScreenState.kt`, first move its pure builder there as specified by the Change 1 plan. Do not copy code from the planning branch over a newer integrated implementation.

### Required reading and repository checks

From the integrated repository root:

```bash
cat AGENTS.md
cat docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
cat docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md
cat docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md
cat docs/handoffs/2026-08-02-smart-home-implementation-planning.md
cat docs/notes/agent-workspaces.md
git worktree list
git status --short
ls -1t docs/handoffs | head -10
```

Read the latest Change 1 implementation handoff returned by the final command. Create a new implementation worktree from the integrated Change 1 branch per `AGENTS.md`; do not implement in the primary checkout, this planning worktree, or Antigravity's Change 1 worktree.

An older unmerged branch, `feature/overdue-morning-review` at commit `e6675ab`, contains `2026-07-27-overdue-morning-review-design.md`. It is superseded by the accepted 2026-08-02 design. Do not cherry-pick it. In particular, do not carry forward its rules that overdue work blocks completion, disables Snooze, or must be resolved before leaving review.

For Android API uncertainty, use the repository-required Context7 flow before coding:

```bash
npx ctx7@latest library "Android AlarmManager" "setAlarmClock one-shot PendingIntent replacement boot package replaced time set timezone changed rescheduling"
npx ctx7@latest docs "/websites/developer_android" "AlarmManager setAlarmClock exact one-shot PendingIntent replacement boot completed package replaced time set timezone changed reschedule alarms"
npx ctx7@latest library "AndroidX Activity" "Activity Result API launch activity preserve underlying activity state OnBackPressedDispatcher"
npx ctx7@latest docs "/androidx/androidx" "Activity Result API StartActivityForResult OnBackPressedDispatcher preserving previous Activity instance and Compose state"
```

In a worktree, export SDK paths rather than adding `client/local.properties`:

```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
```

Run Gradle commands from `client/`. Run `scripts/e2e-real-backend.sh` from the repository root. The historically named `BlockerActivityFakeBackendTest` runs against `backend/test/e2eServer.ts`; do not recreate or use `tools/fake-backend`.

### Accepted product behavior

Morning Review has exactly two user-visible entry surfaces:

1. Home's attention banner;
2. the automated full-screen review, launched by the daily alarm or post-08:00 app-open catch-up.

There is no Morning Review FAB, navigation item, calendar action, overflow action, permanent button, notification-only alternative, or foreground gate for newly invalid tasks.

Review identity is the current device-local ISO date in the current `ZoneId`. The Home banner appears at local midnight if today's date is not acknowledged. Early `Start day` acknowledges today and replaces today's 08:00 alarm with tomorrow's. The daily blocker fires at local 08:00 on every calendar day, including weekends and task-free days. A timezone change recomputes the future alarm; an ISO date already acknowledged remains acknowledged whenever that date is current again.

The review flow is exact:

1. Render the cached snapshot immediately, then call Change 1's `forceSyncAndReconcile()`.
2. While owner invalid/reconciliation-pending tasks exist, show only the blocking section. `Start day` is visible but disabled. Invalid tasks open the existing Repair editor; pending tasks show `Sync pending` and Retry.
3. When blocking work is clear, show every owner-overdue task and today's chronological owner agenda.
4. Overdue work offers `Done` and `Edit`, but never disables `Start day` or Snooze.
5. Today's agenda is informational; it does not gain a second set of mutation controls in this change.
6. `Start day` records today's local acknowledgement, cancels the current review notification/alarm, reconciles the next alarm, and closes.
7. `Snooze 5 min` is a single global action available even while invalid tasks exist. It records pending snooze metadata locally, replaces the current review alarm with one one-shot retry, and closes.

Unassigned tasks never enter blocking work, overdue, or today's agenda. Tasks assigned to another person remain absent by the Change 1 snapshot boundary.

Cached data is sufficient to enable normal `Start day` when no cached blocking owner tasks exist, even while refresh is running or failed. When neither cache nor network data exists, show the error and an enabled `Start day without data`; this writes the same normal acknowledgement. Do not create an `unverified`, `provisional`, or server-confirmation state.

If today was acknowledged and a new invalid owner task later appears, Home shows the banner again. It does not schedule an immediate critical notification or launch review automatically. If the user opens that banner, repair rules apply; `Start day` may write the same date again harmlessly.

### Source-dependent Back and window behavior

Use an explicit entry source in the Morning Review intent:

```kotlin
enum class MorningReviewEntrySource { Home, Automated }
```

- `Home`: normal app surface, does not call `configureBlockerWindow`, shows Back, and system Back returns to the preserved Home/Add/Edit state without acknowledging.
- `Automated`: daily alarm or app-open catch-up, calls `configureBlockerWindow`, hides Back, and consumes system Back. It closes only through `Start day` or `Snooze 5 min`.

Launch Home/catch-up review from `MainActivity` with `ActivityResultContracts.StartActivityForResult`, not directly from a composable. This gives Main one place to refresh local availability after acknowledgement/Snooze and preserves the underlying Compose editor instance. The alarm receiver still launches the automated activity through the existing full-screen notification path.

### Local state and one-alarm invariant

Create the package `app.buzer.android.morningreview` and use these concrete models:

```kotlin
enum class MorningReviewAlarmReason { Daily, Snooze, PriorityRetry }

data class MorningReviewScheduledAlarm(
    val reviewDate: LocalDate,
    val reason: MorningReviewAlarmReason,
    val triggerAt: Instant,
    val occurrenceKey: String
)

data class MorningReviewLocalState(
    val lastAcknowledgedDate: LocalDate? = null,
    val pendingSnoozeDate: LocalDate? = null,
    val pendingSnoozeAt: Instant? = null,
    val scheduledAlarm: MorningReviewScheduledAlarm? = null
)
```

Persist the four logical values in one dedicated SharedPreferences file, using ISO date/instant strings and `commit()` so an acknowledgement is durable before the activity finishes. The store must expose atomic whole-state `read()` and `replace()` operations and have an in-memory storage for JVM tests.

At all times, Morning Review owns zero or one `AlarmKind.MORNING_REVIEW` PendingIntent. `MorningReviewScheduler.reconcile(now, zoneId)` calculates the desired daily/Snooze alarm, cancels a different stored identity, schedules the desired alarm, and persists the new identity. It must also cancel the pre-Change-2 legacy identity:

```kotlin
ScheduledAlarmIdentity(
    taskId = "morning-review",
    occurrenceKey = "morning-review",
    kind = AlarmKind.MORNING_REVIEW
)
```

Use occurrence keys containing reason, local review date, and trigger epoch, for example `morning-review:daily:2026-08-03:1785727200000`. They are opaque outside the Morning Review package; parse only stored typed state, not arbitrary string segments.

### Pure schedule rules

Production review time is `LocalTime.of(8, 0)`, but inject it into the calculator for tests.

Normalize stale Snooze metadata before selecting a desired alarm. A future Snooze for today's local date wins. Otherwise:

- if today is unacknowledged and local time is before 08:00, schedule today at 08:00;
- if local time is at/after 08:00, schedule the next unacknowledged date at 08:00; app-open catch-up handles today immediately;
- if today is acknowledged, schedule the next unacknowledged date at 08:00;
- skip a future candidate equal to `lastAcknowledgedDate` after a timezone/date change.

Resolve local 08:00 with `LocalDate.atTime(reviewTime).atZone(zoneId).toInstant()`. Java time shifts a gap forward and chooses the earlier overlap offset; lock this deterministic behavior with tests around zones/dates whose UTC offset changes. Do not add a fixed 24-hour duration to compute tomorrow.

Availability/catch-up is pure:

```kotlin
val snoozedNow = pendingSnoozeDate == today && pendingSnoozeAt?.isAfter(now) == true
val bannerVisible = !snoozedNow && (lastAcknowledgedDate != today || ownerBlockingCount > 0)
val catchUpRequired = !snoozedNow && lastAcknowledgedDate != today && localTime >= reviewTime
```

`MorningReviewAvailability` also exposes `nextReevaluationAt`: the earliest future instant among the next local midnight, today's 08:00 boundary when still ahead, and active Snooze expiry. MainActivity maintains one lifecycle coroutine for that instant so a foreground Home updates at midnight and an expired Snooze/catch-up does not require a background/resume cycle. Recompute this boundary from calendar/zone rules; do not use a fixed 24-hour delay.

### Alarm delivery and blocker priority

Change `AndroidAlarmScheduler` so Morning Review uses the same reliable `setAlarmClock` path as start/deadline blockers. Pre-start warning remains `setExactAndAllowWhileIdle`.

Before launching Morning Review from `AlarmReceiver`, a focused handler must:

1. ignore an occurrence that does not equal the store's current scheduled identity;
2. ignore/reconcile an alarm whose review date is no longer the current local date after a timezone/date change;
3. ignore/reconcile a daily alarm if today is already acknowledged;
4. clear matching pending Snooze metadata when a Snooze fires;
5. enforce blocker priority `Deadline > Start > Morning Review` from the latest cached snapshot;
6. when a higher blocker exists, replace Morning Review with an internal one-minute `PriorityRetry` alarm and do not launch;
7. otherwise consume the fired alarm, schedule the next daily occurrence, and allow the existing overlay/full-screen notification path to launch review.

Derive higher priority from unfinished, valid owner tasks using the same half-open interval as Home: deadline when `now >= start + duration`; start when `start <= now < end` and `startedAt == null`. Deadline wins across all tasks. Exclude invalid, pending, unassigned, completed, and deleted entries. If no cached snapshot exists, allow Morning Review rather than suppressing it on a guess.

### No new backend review state

Do not add a backend acknowledgement table, API field, sync token, or action-journal event. Stop calling `POST /review/dismiss` from the Android client and remove the now-unused client API/coordinator method and tests. Leave the backend route in place as a compatibility endpoint for an older installed APK; it is no longer product authority and is not called by Change 2 tests.

## File map

| File | Planned change |
| --- | --- |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewLocalState.kt` | Typed local state, storage abstraction, SharedPreferences and in-memory implementations. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt` | Pure desired alarm, availability, catch-up, DST/date calculations. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt` | One-alarm reconciliation, acknowledgement, Snooze, fire consumption, priority retry. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt` | Production construction for store/scheduler/handler. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt` | Validate fired identity/date/ack and enforce blocker priority. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileReceiver.kt` | Reconcile on boot, package replacement, manual time, and timezone changes. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewContract.kt` | Entry-source intent/result constants and factories. |
| `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt` | Pure Home-banner/automated-launch decision with duplicate-launch guard. |
| `client/androidApp/src/main/AndroidManifest.xml` | Register reconciliation broadcasts; retain existing blocker activity/receiver declarations. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt` | Use alarm-clock delivery for Morning Review. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt` | Delegate Morning Review validity/priority before existing blocker launch. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt` | Extract Change 1's owner time classification for Home and Morning Review. |
| Change 1 `HomeScreenState.kt` and tests | Delegate to the selector without changing Home behavior. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt` | Pure loading/blocking/summary/no-data presentation state. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt` | Render repair-first review, global actions, overdue/day summary, Back semantics. |
| `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt` | Cache/force-sync session orchestration and local acknowledge/Snooze/actions. |
| `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` | Reconcile/open catch-up, Activity Result launcher, Home availability. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt` | Receive banner model/callback; no direct activity launch. |
| `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt` | Remove client-side `dismissMorningReview`; retain snapshot/task authority paths. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt` | Remove the obsolete review-dismiss interface method. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt` | Remove the obsolete server-dismiss coordinator path. |
| JVM tests named in tasks below | Lock calculations, state, scheduling, priority, Home delegation, and orchestration. |
| `MorningReviewRepairHostTest.kt` | Migrate Repair transition/focus and global-action behavior. |
| `BlockerActivityFakeBackendTest.kt` | Migrate all Morning Review real-backend scenarios. |
| `docs/handoffs/2026-08-02-daily-morning-review.md` | Final implementation/verification handoff. |

## Task 1: Persist review identity and calculate local schedule/availability

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewLocalState.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewLocalStateTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculatorTest.kt`

- [ ] **Step 1: Write RED store round-trip tests.**

  Cover empty defaults; a complete state round trip; malformed persisted date/instant/reason falling back safely; whole-state replacement clearing Snooze; and a failing storage commit propagating rather than pretending acknowledgement succeeded.

- [ ] **Step 2: Write the RED pure schedule matrix.**

  Use fixed `Instant`, `ZoneId`, and injected review time. Cover:

  - unacknowledged 07:59 -> today 08:00;
  - exactly 08:00 and 08:01 -> tomorrow's daily alarm plus catch-up true;
  - acknowledged today -> tomorrow;
  - weekends are ordinary dates;
  - early acknowledgement removes today's alarm;
  - future current-date Snooze wins and hides banner/catch-up;
  - expired or wrong-date Snooze is ignored/normalized;
  - owner blocking count reopens the banner after same-day acknowledgement but does not make catch-up true;
  - `nextReevaluationAt` selects midnight, 08:00, or Snooze expiry as appropriate across DST;
  - date/zone changes recompute wall-clock 08:00 and do not repeat the same acknowledged date;
  - Europe/Bratislava spring/fall transitions use calendar dates and yield the expected 23/25-hour UTC separation;
  - production occurrence keys are stable and nonblank for the same spec.

- [ ] **Step 3: Confirm the focused tests are RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'
  ```

  Expected: compilation failure because the new package/types do not exist.

- [ ] **Step 4: Implement the minimal typed store.**

  Follow the Local state contract above. Keep Android `SharedPreferences` behind `MorningReviewStateStorage`; pure tests use `InMemoryMorningReviewStateStorage`. Parse persisted values with `runCatching` per field and return null for malformed optional values. `replace()` must use synchronous `commit()` and `check` its result.

- [ ] **Step 5: Implement pure calculations.**

  Add these pure functions:

  ```kotlin
  fun desiredAlarm(state: MorningReviewLocalState, now: Instant, zoneId: ZoneId): MorningReviewScheduledAlarm
  fun availability(state: MorningReviewLocalState, ownerBlockingCount: Int, now: Instant, zoneId: ZoneId): MorningReviewAvailability
  fun normalize(state: MorningReviewLocalState, now: Instant, zoneId: ZoneId): MorningReviewLocalState
  ```

  `MorningReviewAvailability` contains `today`, `bannerVisible`, `catchUpRequired`, `snoozedNow`, and `nextReevaluationAt`. Do not call system clock/zone inside these functions.

- [ ] **Step 6: Run focused tests GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'
  ```

- [ ] **Step 7: Commit the local identity slice.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewLocalState.kt client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewLocalStateTest.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculatorTest.kt
  git commit -m "feat: model daily morning review"
  ```

## Task 2: Maintain exactly one reliable Morning Review alarm

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSchedulerTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- Create/Modify: focused scheduler dispatch test beside existing enforcement unit tests if Change 1 introduced one.

- [ ] **Step 1: Write RED scheduler tests with a fake `AlarmScheduler`.**

  Assert:

  - first reconcile schedules the pure desired alarm and persists it;
  - identical reconcile neither stacks nor changes identity;
  - changed date/zone cancels the old identity before scheduling one replacement;
  - every reconcile cancels the fixed legacy `morning-review` identity;
  - `acknowledge(now, zone)` persists today's date, clears Snooze, and schedules tomorrow;
  - `snooze(now, zone)` persists today/+5 minutes and replaces the daily alarm;
  - consuming a valid Snooze clears metadata and schedules the next daily alarm;
  - a stale fired occurrence is ignored and does not mutate state;
  - `deferForPriority` replaces the fired alarm with a one-minute `PriorityRetry`;
  - schedule/storage failure never records an alarm that was not successfully armed.

- [ ] **Step 2: Confirm scheduler tests RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest'
  ```

- [ ] **Step 3: Implement the focused scheduler.**

  Inject `MorningReviewStateStore`, `AlarmScheduler`, review time, Snooze duration (five minutes), and priority retry duration (one minute). Production defaults live only in `MorningReviewDependencies`; tests pass fixed values. Use task ID `morning-review`, `AlarmKind.MORNING_REVIEW`, and the occurrence key from `MorningReviewScheduledAlarm`.

  Reconcile transaction order is: normalize/read previous -> cancel legacy -> if identity differs cancel previous -> schedule desired -> persist desired. On failure, cancel a newly scheduled desired identity and retain enough previous state for the next reconciliation to recover.

- [ ] **Step 4: Make Morning Review an alarm clock.**

  In `AndroidAlarmScheduler.schedule`, group `MORNING_REVIEW` with `START_BLOCKER` and `DEADLINE_BLOCKER` under `setAlarmClock`. Leave `PRE_START_WARNING` alone. Do not introduce repeating alarms; every review alarm remains one-shot.

- [ ] **Step 5: Run scheduler/enforcement tests GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest' --tests '*Alarm*Test'
  ```

- [ ] **Step 6: Commit scheduling.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview client/androidApp/src/test/kotlin/app/buzer/android/morningreview client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt
  git commit -m "feat: schedule daily morning review"
  ```

## Task 3: Reconcile system changes and enforce blocker priority at alarm delivery

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileReceiver.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- Modify: `client/androidApp/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write RED priority tests.**

  Build complete `SnapshotViewState` fixtures. Assert Deadline beats Start, Start beats Morning Review, interval end is Deadline, started tasks do not produce Start, and invalid/pending/unassigned/completed/deleted tasks never suppress review.

- [ ] **Step 2: Write RED alarm-handler decision tests.**

  Cover matching valid daily/Snooze -> `Launch`; stale identity -> `Ignore`; acknowledged daily -> `Ignore` and reconcile; wrong local date after timezone change -> `Ignore` and reconcile; matching Snooze clears pending metadata; Start/Deadline presence -> `Deferred` with one-minute replacement; and no cached snapshot -> `Launch`.

- [ ] **Step 3: Confirm RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest'
  ```

- [ ] **Step 4: Implement handler and resolver.**

  Define:

  ```kotlin
  enum class MorningReviewAlarmDecision { Launch, Deferred, Ignore }
  enum class ActiveBlockerPriority { None, Start, Deadline }
  ```

  Inject store/scheduler, cached snapshot loader, clock, and zone provider. Keep all Android context construction in `MorningReviewDependencies`.

- [ ] **Step 5: Delegate from `AlarmReceiver`.**

  For `AlarmKind.MORNING_REVIEW`, call the handler before `openBlocker`. Return without notification/overlay for `Deferred` or `Ignore`. For `Launch`, continue through the existing full-screen notification and overlay logic. Do not alter pre-start/start/deadline dispatch.

- [ ] **Step 6: Register reconciliation broadcasts.**

  Add one non-exported `MorningReviewReconcileReceiver` with filters for:

  ```xml
  <action android:name="android.intent.action.BOOT_COMPLETED" />
  <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
  <action android:name="android.intent.action.TIME_SET" />
  <action android:name="android.intent.action.TIMEZONE_CHANGED" />
  ```

  Its synchronous work is only SharedPreferences + AlarmManager reconciliation. Keep existing `BootReceiver` and boot snapshot reconciliation unchanged; both receivers intentionally handle boot for different authorities.

- [ ] **Step 7: Run focused tests and manifest compilation GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest'
  ./gradlew :androidApp:processDebugMainManifest :androidApp:compileDebugKotlin
  ```

- [ ] **Step 8: Commit delivery/reconciliation.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview client/androidApp/src/test/kotlin/app/buzer/android/morningreview client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt client/androidApp/src/main/AndroidManifest.xml
  git commit -m "feat: deliver prioritized morning review"
  ```

## Task 4: Share owner agenda classification and build the pure review model

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelectorTest.kt`
- Modify: integrated Change 1 `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- Modify: integrated Change 1 `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`

- [ ] **Step 1: Lock Change 1 Home behavior before refactoring.**

  Run the integrated Home state suite and record its count/result in the implementation handoff:

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest'
  ```

  Expected: PASS before extraction. Stop if it is red; do not build Morning Review on an unaccepted Home classifier.

- [ ] **Step 2: Write RED selector tests.**

  Extract only time/ownership classification, not Home labels or expansion state. Define these models:

  ```kotlin
  data class OwnerAgendaEntry(val task: TaskSnapshotItem, val start: Instant, val end: Instant)
  data class OwnerAgendaBuckets(
      val blocking: List<TaskSnapshotItem>,
      val overdue: List<OwnerAgendaEntry>,
      val current: List<OwnerAgendaEntry>,
      val future: List<OwnerAgendaEntry>
  )
  ```

  Re-run Change 1's owner/unassigned, half-open boundary, invalid/pending, completed/deleted, and deterministic-order cases against the selector.

- [ ] **Step 3: Refactor Home GREEN with no presentation changes.**

  Make `buildHomeScreenState` delegate classification to `OwnerAgendaSelector`. Keep Home grouping, hero selection, expansion, labels, and rendering in Home files. Run:

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest'
  ```

  Expected: both PASS with unchanged Home assertions.

- [ ] **Step 4: Replace Morning Review's invalid-only state tests with the accepted flow matrix.**

  Add RED cases for:

  - cached loading, cached refresh-in-flight, cached refresh warning, and no-data error;
  - no-data primary label `Start day without data` and normal acknowledgement eligibility;
  - invalid owner card -> Repair, pending owner card -> `Sync pending`/Retry;
  - unassigned and assigned-other exclusion;
  - blocking section hides overdue/day summary and disables `Start day`;
  - once clear, all overdue rows sort newest miss first and are non-blocking;
  - today's agenda contains every current entry plus owner tasks starting today, sorted start/end/ID;
  - future dates and completed tasks are absent from today's summary;
  - task-free day still enables `Start day`;
  - Snooze is one global action in every actionable phase, never per task;
  - action in flight prevents duplicate mutations while retaining cached content.

- [ ] **Step 5: Implement the pure screen state.**

  Use these explicit phases/models rather than branching over raw tasks in composables:

  ```kotlin
  sealed interface MorningReviewPhase {
      data object Loading : MorningReviewPhase
      data object Blocking : MorningReviewPhase
      data object Summary : MorningReviewPhase
      data object NoData : MorningReviewPhase
  }

  sealed interface MorningReviewBlockingCard {
      val taskId: String
      val title: String

      data class Repair(
          override val taskId: String,
          override val title: String,
          val problemText: String,
          val description: String?
      ) : MorningReviewBlockingCard

      data class SyncPending(
          override val taskId: String,
          override val title: String,
          val statusText: String = "Sync pending"
      ) : MorningReviewBlockingCard
  }

  data class MorningReviewAgendaRow(
      val taskId: String,
      val title: String,
      val timeLabel: String,
      val subline: String,
      val doneEnabled: Boolean
  )

  data class MorningReviewScreenState(
      val phase: MorningReviewPhase,
      val headerText: String,
      val absoluteDateLabel: String,
      val warning: String?,
      val refreshing: Boolean,
      val blockingCards: List<MorningReviewBlockingCard>,
      val overdueRows: List<MorningReviewAgendaRow>,
      val todayRows: List<MorningReviewAgendaRow>,
      val startDayLabel: String,
      val startDayEnabled: Boolean,
      val snoozeEnabled: Boolean,
      val showBack: Boolean,
      val actionInFlight: Boolean
  )
  ```

  The builder receives `SnapshotViewState?`, load/refresh/action flags, errors, injected `Instant`, and `ZoneId`. It must not read system time/zone or mutate navigation.

- [ ] **Step 6: Run Home and Morning state tests GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'
  ```

- [ ] **Step 7: Commit shared classification and review state.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt
  git commit -m "refactor: share owner agenda classification"
  ```

## Task 5: Orchestrate cache, forced sync, acknowledgement, Snooze, and task actions

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewContract.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt` only for obsolete dismiss removal in Task 8, not activity behavior.

- [ ] **Step 1: Add RED pure/session assertions for transitions.**

  Extend state/helper tests to prove: cached snapshot appears before refresh; force-sync success replaces it; force-sync failure retains it; repair success reloads the coordinator's complete authoritative cached snapshot and transitions to Summary rather than finishing; pending Retry calls force sync; overdue Done applies authority and remains optional; Start is allowed with cached clear data during refresh; no-data Start is allowed only after data is unavailable; and acknowledgement/Snooze result codes are distinct.

- [ ] **Step 2: Define the explicit activity contract.**

  `MorningReviewContract` must provide Home and Automated intent factories, parse a missing/unknown source as Automated for alarm safety, and define result extras for `Acknowledged`, `Snoozed`, and `Cancelled`. AlarmReceiver uses Automated; MainActivity uses Home or Automated catch-up.

- [ ] **Step 3: Replace invalid-list activity state with complete snapshot state.**

  In `MorningReviewActivity`, load `coordinator.loadLatestSnapshot()` synchronously and reduce it before first composition. Then launch `coordinator.forceSyncAndReconcile()` (from integrated Change 1), reduce/apply success, and preserve cached state on failure. Do not call ordinary GET for the review's requested fresh sync.

- [ ] **Step 4: Implement local Start/Snooze.**

  Start calls `MorningReviewScheduler.acknowledge(now, zone)`, cancels the current Morning Review notification if an alarm occurrence is present, sets Acknowledged result, and finishes. Snooze calls `scheduler.snooze(now, zone)`, cancels the notification, sets Snoozed result, and finishes. If scheduling/persistence throws, keep the activity open with Retry; do not call the backend.

- [ ] **Step 5: Preserve authoritative task mutation paths.**

  Repair continues through `EnforcementCoordinator.updateTask`. After success, reload the coordinator's stored snapshot rather than deleting one local ID. Overdue Done builds one `TaskActionRequest` with the current occurrence key and calls `completeTask`; Edit reuses the unified Update editor with an overdue-specific presentation. Disable duplicate row actions while one mutation is active. `Start day` remains enabled when overdue exists and no blocking owner task exists.

- [ ] **Step 6: Apply source-specific Back/window behavior.**

  Call `configureBlockerWindow()` and add an enabled no-op `OnBackPressedCallback` only for Automated. Home entry exposes/carries Cancelled result on Back. Do not finish automatically after the last repair; transition to Summary.

- [ ] **Step 7: Run focused JVM/compile verification.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReview*Test'
  ./gradlew :androidApp:compileDebugKotlin
  ```

- [ ] **Step 8: Commit orchestration.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewContract.kt client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt
  git commit -m "feat: orchestrate daily morning review"
  ```

## Task 6: Render the repair-first review and preserve editor state

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` only after focused host tests are green; full migration is Task 9.

- [ ] **Step 1: Add RED Compose host tests.**

  Cover visible ordering/focus and callbacks:

  - Review heading and absolute local date are accessibility headings;
  - invalid Repair and pending Retry cards precede global actions;
  - Start day is visible/disabled during Blocking;
  - one global `Snooze 5 min` exists and no per-card Snooze tag exists;
  - after repair removes the last blocker, focus moves to the Summary heading and activity does not close;
  - Summary shows Overdue before Today, overdue Done/Edit callbacks use the correct ID, and Start stays enabled;
  - no-data screen shows Retry, `Start day without data`, and Snooze;
  - Home entry shows Back; Automated entry does not;
  - large font scale can scroll to both global actions without clipped fixed-height containers.

- [ ] **Step 2: Confirm instrumentation source is RED.**

  ```bash
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ```

  Expected: failures for the new state/callback parameters and removed per-card Snooze contract.

- [ ] **Step 3: Render state, not raw task classification.**

  Replace `MorningReviewTaskList`'s raw invalid list with a single `LazyColumn` driven by `MorningReviewScreenState`. Keep the existing `TaskEditorUpdateScreen` Repair host, project retry status, full update request, checklist editing, and Activity footer. Generalize only enough to host overdue Edit; do not fork another editor.

- [ ] **Step 4: Make actions global and accessible.**

  Put `Start day`/`Start day without data` and `Snooze 5 min` after content in deterministic traversal order. Mark section headers with `heading()`, expose disabled primary semantics, use minimum Material touch targets, and request focus on the Blocking/Summary heading when phase changes. Do not make Snooze icon-only.

- [ ] **Step 5: Run focused host tests GREEN.**

  If a managed device is available:

  ```bash
  ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.buzer.android.ui.screens.MorningReviewRepairHostTest
  ```

  Always run:

  ```bash
  ./gradlew :androidApp:compileDebugAndroidTestKotlin :androidApp:testDebugUnitTest --tests '*MorningReview*Test'
  ```

- [ ] **Step 6: Commit UI.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt
  git commit -m "feat: render daily morning review flow"
  ```

## Task 7: Integrate Home banner, early acknowledgement, and app-open catch-up

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: integrated Change 1 `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- Modify: integrated Change 1 `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: integrated Change 1 `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewEntryDeciderTest.kt`

- [ ] **Step 1: Add RED availability/navigation tests.**

  Prove:

  - 00:00 unacknowledged -> Home Review banner, no automatic launch before 08:00;
  - early Home Start acknowledges/cancels today and hides banner;
  - 08:00 unacknowledged -> automated catch-up exactly once;
  - current-date active Snooze hides banner/catch-up until expiry;
  - same-day new owner invalid task reopens Home banner after acknowledgement but does not auto-launch;
  - activity result Acknowledged/Snoozed reloads availability;
  - Home Cancelled before 08:00 leaves banner visible;
  - automatic source has no cancel path;
  - `onCreate` plus `onResume` does not double-launch.
  - keeping Home foreground across midnight reveals the banner, and crossing 08:00 or Snooze expiry reevaluates catch-up once.

- [ ] **Step 2: Confirm RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'
  ```

- [ ] **Step 3: Move launch authority into MainActivity.**

  Add a pure `MorningReviewEntryDecider.decide(availability, launchInFlight)` returning `showHomeBanner` and `launchAutomated`. `launchAutomated` is true only when catch-up is required and no review launch is already in flight. Register `StartActivityForResult` before lifecycle use. Pass `onOpenMorningReview` into `BuzerApp`; remove `LocalContext.startActivity` from App. Maintain Compose-observable `MorningReviewAvailability`, recomputed whenever snapshot or local result changes.

  On every MainActivity foreground/resume, call scheduler reconciliation, then evaluate catch-up. Use one in-flight guard cleared by the Activity Result callback; since automated review cannot Cancel, acknowledgement/Snooze always clears the condition before returning. Maintain one cancellable lifecycle coroutine until `MorningReviewAvailability.nextReevaluationAt`; at that boundary recompute availability, reconcile, evaluate catch-up, and arm the following boundary. Cancel/replace this job on pause or any state change so midnight/08:00/Snooze expiry cannot create duplicate launch loops.

- [ ] **Step 4: Extend Home's pure input.**

  Pass local `bannerVisible` and blocking count into the Home state builder. Show `Morning review ready`/`Review` on a clean unacknowledged day and the existing repair count when owner blocking tasks exist. Keep the Change 1 refresh Retry strip separately visible. Do not restore the removed FAB/menu action.

- [ ] **Step 5: Run focused state/navigation compilation GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'
  ./gradlew :androidApp:compileDebugKotlin
  ```

- [ ] **Step 6: Commit entry integration.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewEntryDeciderTest.kt
  git commit -m "feat: open daily review from Home and catch-up"
  ```

## Task 8: Remove obsolete Android server-dismiss authority

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: relevant `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClient*Test.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`

- [ ] **Step 1: Prove production Morning Review no longer calls backend dismiss.**

  Add/adjust focused activity/E2E call-recording expectations so Start and Snooze do not record `/review/dismiss`; Snooze instead creates local scheduled-state evidence. Run the affected test RED before deleting the old API path.

- [ ] **Step 2: Delete client-only dismiss methods.**

  Remove `BuzerApiClient.dismissMorningReview`, `EnforcementApi.dismissMorningReview`, `BuzerEnforcementApi` override, `EnforcementCoordinator.dismissMorningReview`, its fixed Morning Review constants if no longer used, and their fakes/tests. Do not change backend `routes.ts` or backend route tests; the compatibility endpoint remains deployed but unused.

- [ ] **Step 3: Verify no client references and run shared/coordinator tests.**

  ```bash
  rg -n "dismissMorningReview|/review/dismiss" client --glob '!**/build/**'
  ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --tests '*EnforcementCoordinatorTest' --tests '*MorningReview*Test'
  ```

  Expected: `rg` exits 1 with no matches; Gradle passes.

- [ ] **Step 4: Commit cleanup.**

  ```bash
  git add client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt client/shared/src/commonTest client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt client/androidApp/src/test
  git commit -m "refactor: keep morning review state local"
  ```

## Task 9: Migrate real-backend instrumentation and system-event coverage

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt`
- Modify: `backend/test/fakes/e2eFixtures.ts` and `backend/test/fakes/e2eFixtures.test.ts` to expose named deterministic clean, invalid, overdue, and today-review scenarios using the Change 1 fixture model.

- [ ] **Step 1: Replace contradictory old instrumentation expectations.**

  Migrate existing tests rather than leaving old names/behavior:

  - Home opens Review from the banner only;
  - Repairing the last invalid task transitions to Summary, not activity destruction;
  - per-card Snooze test becomes one global `Snooze 5 min` test;
  - Snooze asserts no `/review/dismiss` call and activity closes;
  - refresh uses Change 1's `/tasks/sync`, retains cache on failure, and Retry works;
  - clean/task-free review still appears and Start acknowledges;
  - overdue rows are visible/actionable while Start remains enabled;
  - no-data Start without data closes/acknowledges.

- [ ] **Step 2: Add full user-flow instrumentation.**

  Cover:

  - early Home acknowledgement removes banner and prevents today's alarm/catch-up;
  - automated source blocks Back; Home source permits Back;
  - Snooze schedules one alarm approximately five minutes ahead and suppresses catch-up until expiry;
  - app-open after 08:00 launches automated review once;
  - invalid/pending owner work blocks Start, unassigned does not;
  - after acknowledgement, injecting/discovering a new invalid owner task restores only the Home banner;
  - Start/Deadline priority defers Morning Review and later allows it after cached authority clears;
  - launching automated review over a dirty Add/Edit draft, then Start/Snooze, returns to the exact typed draft;
  - Task-free weekends use the same review flow.

- [ ] **Step 3: Add system/alarm instrumentation.**

  `MorningReviewAlarmInstrumentationTest` must verify:

  - manifest receiver handles BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, and TIMEZONE_CHANGED;
  - `dumpsys alarm`/AlarmManager evidence shows only one app-owned Morning Review identity after repeated reconcile;
  - stale occurrence delivery is ignored;
  - current occurrence produces the Morning Review notification/activity path;
  - `setAlarmClock` classification is observable through app-scoped alarm evidence.

  Prefer `scripts/adb-verify.sh alarms` for manual evidence; do not parse unrestricted dumpsys/logcat in the handoff.

- [ ] **Step 4: Run scoped real-backend E2E classes.**

  From repository root, run the relevant classes separately while iterating:

  ```bash
  ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh
  ANDROID_TEST_CLASS=app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest scripts/e2e-real-backend.sh
  ```

  Expected: both PASS. If the script supports only one class expression, run each command separately as shown.

- [ ] **Step 5: Capture concise manual alarm evidence if a device is used.**

  ```bash
  scripts/adb-verify.sh alarms
  ```

  Record the selected device serial, Morning Review alarm-clock trigger, and one-alarm identity in the handoff.

- [ ] **Step 6: Commit migrated instrumentation.**

  ```bash
  git add client/androidApp/src/androidTest backend/test/fakes/e2eFixtures.ts backend/test/fakes/e2eFixtures.test.ts
  git commit -m "test: verify daily morning review end to end"
  ```

## Task 10: Full regression verification and handoff

**Files:**
- Create: `docs/handoffs/2026-08-02-daily-morning-review.md`
- Modify: this plan's checkboxes as tasks complete

- [ ] **Step 1: Run complete client verification.**

  From `client/`:

  ```bash
  ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
  ```

  Expected: PASS.

- [ ] **Step 2: Run backend verification even though no backend product code changed.**

  From `backend/`:

  ```bash
  npm test
  npm run typecheck
  npm run build
  ```

  Expected: PASS. This protects the E2E authority and Change 1 sync endpoint used by review.

- [ ] **Step 3: Run the full unfiltered real-backend suite.**

  From repository root:

  ```bash
  scripts/e2e-real-backend.sh
  ```

  Expected: PASS. Scoped Morning Review runs are not sufficient completion evidence.

- [ ] **Step 4: Audit scope and repository hygiene.**

  ```bash
  git diff --check
  git status --short
  git log --oneline --decorate -12
  rg -n "dismissMorningReview|/review/dismiss" client --glob '!**/build/**'
  ```

  Expected: no whitespace errors; only intentional files; no client backend-dismiss reference. Inspect the diff for accidental Change 3 readiness gating, same-day critical invalid notifications, first-unlock catch-up, repeating alarms, server review state, completed history, or fake-backend resurrection.

- [ ] **Step 5: Write the required handoff.**

  Create `docs/handoffs/2026-08-02-daily-morning-review.md` with plan/task checkbox status, branch/worktree, files changed, decisions, assumptions, exact commands/results, manual alarm evidence, known failures/skips, and next step. Include:

  ```text
  Verification:
  - Command: <exact command>
  - Result: PASS/FAIL/SKIPPED
  - Evidence: <important output or reason>
  ```

  Explicitly confirm: one alarm invariant; early acknowledgement; 08:00/catch-up behavior; invalid blocking; overdue non-blocking; no-data acknowledgement; Snooze; system-event reconciliation; priority; preserved editor draft; no client `/review/dismiss`; and no Change 3 implementation.

- [ ] **Step 6: Commit documentation/progress.**

  ```bash
  git add docs/superpowers/plans/2026-08-02-daily-morning-review.md docs/handoffs/2026-08-02-daily-morning-review.md
  git commit -m "docs: hand off daily morning review"
  ```

- [ ] **Step 7: If pushed, check push-triggered CI.**

  ```bash
  gh run list --workflow=ci.yml --limit 1
  ```

  Do not report completion over a red run. If not pushed, record CI as not triggered.

## Completion criteria

Change 2 is complete only when:

- local acknowledgement/Snooze state survives process death and malformed storage fails safe;
- pure tests lock local-date, timezone, DST, early-acknowledgement, Snooze, and catch-up rules;
- exactly one Morning Review alarm is armed and Morning Review uses `setAlarmClock`;
- boot/package/time/timezone broadcasts reconcile it;
- Deadline and Start blockers defer Morning Review without losing the current day's review;
- Home banner, automated 08:00 delivery, and post-08:00 app-open catch-up are the only entry surfaces;
- cached and no-data acknowledgement behavior matches the accepted design;
- invalid/pending owner tasks block Start, while overdue and unassigned work do not;
- Snooze is one global action and never requires backend success;
- the underlying Add/Edit draft survives automated review;
- Home and Morning Review use one owner agenda classifier and existing Home tests remain green;
- focused and full real-backend instrumentation passes;
- the handoff contains fresh evidence and no Change 3 code is mixed into the branch.
