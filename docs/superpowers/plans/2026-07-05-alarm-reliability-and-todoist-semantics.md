# Alarm Reliability And Todoist-Native Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make blocker alarms fire on time on a locked phone (`setAlarmClock`), adopt Todoist-native time semantics (due = start of block, enforcement deadline = due + duration), skip the keyguard-invisible overlay, and back off the overlay watcher.

**Architecture:** The semantic change is an interpretation shift, not a data migration: `startAt` becomes identical to `dueDatetime` (backend), and the shared alarm planner computes the deadline alarm at `due + duration`. The Android scheduler routes START_BLOCKER/DEADLINE_BLOCKER through `AlarmManager.setAlarmClock()`. UI copy moves from "deadline" to "start" language with a computed "Ends HH:MM" readout. Every consumer of the old semantics (backend tests, planner tests, fixtures, instrumentation tests, validator messages) is migrated in this plan per the AGENTS.md test-migration rule.

**Tech Stack:** TypeScript/vitest (backend), Kotlin Multiplatform (`kotlin.time`), Jetpack Compose, AlarmManager, Gradle managed devices.

**Source spec:** `docs/superpowers/specs/2026-07-05-alarm-reliability-and-todoist-semantics-design.md`

---

## Execution Notes (read before Task 1)

- Never modify `client/local.properties`. Never touch live Todoist/WhatsApp in tests.
- The JVM lanes: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`. The emulator lane: `scripts/e2e-fake-backend.sh` from the repo root (~3 min; required before hand-off per AGENTS.md because this changes client UI/behavior).
- Backend lane: `cd backend && npm test && npm run typecheck && npm run build`. Fake backend: `cd tools/fake-backend && npm test`.
- Task 9 (on-device verification) requires the owner and their Pixel 10; stop and report READY_FOR_DEVICE when you reach it — do not attempt it yourself.
- Where a step gives an exact old→new string pair, use the Edit tool with those exact strings; if an old string is not found verbatim, STOP and report NEEDS_CONTEXT with what you found instead.

## File Structure

- Create: `docs/decisions/2026-07-05-todoist-native-due-semantics.md`
- Modify: `backend/src/domain/time.ts`, `backend/src/domain/time.test.ts` (or wherever its tests live — locate with `rg -l "computeStartAt" backend/src`), `backend/src/services/taskSnapshotService.ts`, `backend/src/services/escalationService.ts` (+ their tests)
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt` (+ its test in `client/shared/src/commonTest/`)
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt` (+ test)
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt` (+ test)
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`, `BlockerScreens.kt`, `MorningReviewScreen.kt`, `HomeScreen.kt`
- Modify: `tools/fake-backend/src/fixtures.ts` (+ `src/server.test.ts` if it asserts fixture times)
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`, `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Create: `docs/handoffs/2026-07-05-alarm-reliability-and-todoist-semantics.md` (Task 9)

---

## Task 1: Branch and decision doc

**Files:**
- Create: `docs/decisions/2026-07-05-todoist-native-due-semantics.md`

- [ ] **Step 1: Create the branch**

```bash
git checkout master && git pull && git checkout -b feature/alarm-reliability-todoist-semantics
```

- [ ] **Step 2: Write the decision doc**

Create `docs/decisions/2026-07-05-todoist-native-due-semantics.md`:

```markdown
# Decision: Todoist-Native Due Semantics And Alarm-Clock Delivery

Date: 2026-07-05
Status: accepted (owner decision during go-live dogfooding)
Amends: docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md

## Semantics

`due.datetime` is the START of a committed block, matching Todoist's native
interpretation. The enforcement deadline is `due + durationMinutes`. Previously Buzer
treated due as the end (`startAt = due - duration`), which made Todoist's calendar render
Buzer tasks after their "deadline" and would have enforced Todoist-created tasks a full
duration early.

Consequences:
- Backend `startAt` equals `dueDatetime`; the snapshot contract shape is unchanged.
- The shared alarm planner schedules START_BLOCKER at due, DEADLINE_BLOCKER at
  due + duration, PRE_START_WARNING at due - 1h.
- Android pickers mean "Start at" and default to now; screens show the computed end.
- No data migration: stored values keep their meaning at the Todoist boundary; existing
  planned tasks are enforced one duration later than under the old interpretation
  (one-time, accepted).

## Alarm delivery

START_BLOCKER and DEADLINE_BLOCKER alarms use `AlarmManager.setAlarmClock()` because
allow-while-idle exact alarms are doze-deferred minutes on a locked device (observed
2026-07-05: start alarm 9+ minutes late). The status-bar alarm icon is accepted.
PRE_START_WARNING and MORNING_REVIEW stay on `setExactAndAllowWhileIdle`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/decisions/2026-07-05-todoist-native-due-semantics.md
git commit -m "docs: decide todoist-native due semantics and alarm-clock delivery

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Backend semantics (startAt = due, deadline helper, escalation message)

**Files:**
- Modify: `backend/src/domain/time.ts`
- Modify: backend tests for it (locate: `rg -ln "computeStartAt" backend/src`)
- Modify: `backend/src/services/taskSnapshotService.ts` (call site, ~line 24-25)
- Modify: `backend/src/services/escalationService.ts` (lines ~29, 38, 58)

- [ ] **Step 1: Update the time-domain tests (RED)**

Locate the test file asserting `computeStartAt` (expected: a `*.test.ts` next to or naming `time`). Rewrite its `computeStartAt` cases to assert identity-of-due, and add `computeDeadlineAt` cases. Replace the existing computeStartAt test block with:

```typescript
describe("computeStartAt", () => {
  it("returns the due datetime unchanged (due = start of block)", () => {
    expect(computeStartAt("2026-07-05T09:30:00.000Z", 30)).toBe("2026-07-05T09:30:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeStartAt("2026-07-05T09:30:00.000Z", 0)).toThrow();
  });

  it("rejects invalid timestamps", () => {
    expect(() => computeStartAt("nope", 30)).toThrow();
  });
});

describe("computeDeadlineAt", () => {
  it("returns due plus duration", () => {
    expect(computeDeadlineAt("2026-07-05T09:30:00.000Z", 30)).toBe("2026-07-05T10:00:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeDeadlineAt("2026-07-05T09:30:00.000Z", -5)).toThrow();
  });
});
```

Add `computeDeadlineAt` to the file's import from `../domain/time` (adjust the relative path to match the file's existing import of `computeStartAt`).

- [ ] **Step 2: Run and verify RED**

Run: `cd backend && npm test -- time`
Expected: FAIL — identity case fails against the old subtraction; `computeDeadlineAt` unresolved.

- [ ] **Step 3: Implement in `backend/src/domain/time.ts`**

Replace the whole `computeStartAt` function with:

```typescript
export function computeStartAt(dueDatetime: string, durationMinutes: number): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  // Todoist-native semantics (decision 2026-07-05): due.datetime IS the start of the
  // committed block. Kept as a function so the semantic boundary stays explicit and
  // validated.
  return parseIsoTimestamp(dueDatetime, "dueDatetime").toISOString();
}

export function computeDeadlineAt(dueDatetime: string, durationMinutes: number): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  const due = parseIsoTimestamp(dueDatetime, "dueDatetime");
  return new Date(due.getTime() + durationMinutes * 60_000).toISOString();
}
```

(`parseIsoTimestamp` and `addMinutes` already exist in this file; leave them unchanged.)

- [ ] **Step 4: Point the escalation message at the real deadline**

In `backend/src/services/escalationService.ts`, both `deadlineAt: task.dueDatetime` occurrences (~lines 38 and 58) become the computed end of block. First read the file: the guard at ~line 29 already skips tasks with `dueDatetime === null`. Extend that guard to also skip `task.durationMinutes === null` (same early-return style), import `computeDeadlineAt` from `../domain/time`, and replace both occurrences:

```typescript
      deadlineAt: task.dueDatetime,
```

with:

```typescript
      deadlineAt: computeDeadlineAt(task.dueDatetime, task.durationMinutes),
```

If the surrounding types make `durationMinutes` non-null after the guard, no cast is needed; otherwise use the same narrowing pattern the file already uses for `dueDatetime`. If escalationService tests assert `deadlineAt`, update their expected values to due + duration.

- [ ] **Step 5: Run the backend suite**

Run: `cd backend && npm test && npm run typecheck && npm run build`
Expected: all PASS. If any other test fails on start/deadline expectations, that test encodes the old semantics — update its expected values (start = due; deadline = due + duration) and say so in your report.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: adopt todoist-native due semantics in backend

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Shared alarm planner (deadline at due + duration)

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: its test (locate: `rg -ln "AlarmPlanCalculator" client/shared/src/commonTest`)

- [ ] **Step 1: Update the planner test (RED)**

In the calculator's test file, find the case(s) asserting a DEADLINE_BLOCKER trigger equal to the task's `dueDatetime`. Update them: the deadline trigger is now `dueDatetime + durationMinutes` minutes. Add one new case: a task with `dueDatetime` set but `durationMinutes = null` produces NO deadline alarm (it cannot be computed). Keep PRE_START (start − 1h) and START (at start) expectations as they are — `startAt` values in test fixtures should equal `dueDatetime` under the new semantics; where a fixture has `startAt != dueDatetime`, set `startAt = dueDatetime` and adjust expected triggers accordingly.

- [ ] **Step 2: Run and verify RED**

Run: `cd client && ./gradlew :shared:testAndroidHostTest --tests '*AlarmPlanCalculator*' --console=plain`
Expected: FAIL on the updated deadline expectations.

- [ ] **Step 3: Implement**

In `AlarmPlanCalculator.kt`, add to the imports:

```kotlin
import kotlin.time.Duration.Companion.minutes
```

Replace the deadline block inside `desiredAlarmsFor`:

```kotlin
        if (dueAt != null && dueAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, dueAt.toString())
        }
```

with:

```kotlin
        val durationMinutes = task.durationMinutes
        if (dueAt != null && durationMinutes != null) {
            val deadlineAt = dueAt.plus(durationMinutes.minutes)
            if (deadlineAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, deadlineAt.toString())
            }
        }
```

- [ ] **Step 4: Run and verify GREEN, then the full shared suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add client/shared/src
git commit -m "feat: schedule deadline blocker at due plus duration

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: setAlarmClock for blocker alarms

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`

No JVM-testable seam exists for AlarmManager routing (the class is a thin system-service wrapper, consistent with the codebase's approach); verification is Task 9's on-device pass.

- [ ] **Step 1: Implement**

Replace the entire body of `AndroidAlarmScheduler.kt` with:

```kotlin
package app.buzer.android.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.buzer.android.MainActivity

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: ScheduledAlarm) {
        when (alarm.kind) {
            // Blocker alarms must fire on time on a locked, dozing device. setAlarmClock is
            // exempt from allow-while-idle throttling (observed 9+ min deferral on a real
            // Pixel, 2026-07-05); the status-bar alarm icon is an accepted tradeoff.
            AlarmKind.START_BLOCKER, AlarmKind.DEADLINE_BLOCKER -> alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.triggerAtEpochMillis, showIntent()),
                pendingIntent(alarm.taskId, alarm.kind)
            )

            AlarmKind.PRE_START_WARNING, AlarmKind.MORNING_REVIEW ->
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.triggerAtEpochMillis,
                    pendingIntent(alarm.taskId, alarm.kind)
                )
        }
    }

    override fun cancel(taskId: String, kind: AlarmKind) {
        alarmManager.cancel(pendingIntent(taskId, kind))
    }

    override fun cancelAllForTask(taskId: String) {
        AlarmKind.entries.forEach { cancel(taskId, it) }
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingIntent(taskId: String, kind: AlarmKind): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            AlarmIntentFactory.requestCode(taskId, kind),
            AlarmIntentFactory.receiverIntent(context, taskId, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

- [ ] **Step 2: Compile and run unit tests**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (If a unit test fakes `AlarmScheduler`, it is interface-based and unaffected; if one asserts on `setExactAndAllowWhileIdle` for blocker kinds, update it to the new routing and note it.)

- [ ] **Step 3: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt
git commit -m "feat: deliver blocker alarms via setAlarmClock

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: Keyguard-aware overlay and watcher backoff

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`

- [ ] **Step 1: Skip the overlay behind the keyguard**

In `AlarmReceiver.kt`, add imports:

```kotlin
import android.app.KeyguardManager
```

In `openBlocker`, replace:

```kotlin
        val request = OverlayBlockerRequest.fromExtras(taskId, kind.name)
        if (request != null) {
            val started = OverlayBlockerServiceStarter.tryStart(context, request)
            AppLog.info("overlay_start attempted result=$started kind=$kind taskId=$taskId")
        } else {
            AppLog.warn("overlay_start skipped reason=invalid_request kind=$kind taskId=$taskId")
        }
```

with:

```kotlin
        val request = OverlayBlockerRequest.fromExtras(taskId, kind.name)
        val keyguardLocked =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (request == null) {
            AppLog.warn("overlay_start skipped reason=invalid_request kind=$kind taskId=$taskId")
        } else if (keyguardLocked) {
            // TYPE_APPLICATION_OVERLAY does not render above the keyguard; the full-screen
            // notification below is the lock-screen surface (observed 2026-07-05).
            AppLog.info("overlay_start skipped reason=keyguard_locked kind=$kind taskId=$taskId")
        } else {
            val started = OverlayBlockerServiceStarter.tryStart(context, request)
            AppLog.info("overlay_start attempted result=$started kind=$kind taskId=$taskId")
        }
```

- [ ] **Step 2: Back off the watcher**

In `OverlayBlockerService.kt`, in the companion object, replace:

```kotlin
        private const val WATCH_POLL_INTERVAL_MS = 2_000L
        private const val WATCH_REFRESH_TIMEOUT_MS = 8_000L
        private const val MAX_WATCH_POLLS = 60
```

with:

```kotlin
        private const val WATCH_FAST_POLL_INTERVAL_MS = 2_000L
        private const val WATCH_SLOW_POLL_INTERVAL_MS = 10_000L
        private const val WATCH_FAST_POLLS = 15
        private const val WATCH_REFRESH_TIMEOUT_MS = 8_000L
        private const val MAX_WATCH_POLLS = 60
```

and in `startResolutionWatcher`, replace:

```kotlin
            repeat(MAX_WATCH_POLLS) { attempt ->
                delay(WATCH_POLL_INTERVAL_MS)
```

with:

```kotlin
            // ~30s of fast polling for the common quick resolution, then a calm 10s cadence;
            // total lifetime ~8 minutes. Each poll is a full backend refresh, so cadence is cost.
            repeat(MAX_WATCH_POLLS) { attempt ->
                delay(if (attempt < WATCH_FAST_POLLS) WATCH_FAST_POLL_INTERVAL_MS else WATCH_SLOW_POLL_INTERVAL_MS)
```

- [ ] **Step 3: Compile and unit-test**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt
git commit -m "fix: skip keyguard-invisible overlay and back off watcher

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: UI start-time language, default-now, Ends readout, capitalization

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`, `BlockerScreens.kt`, `MorningReviewScreen.kt`, `HomeScreen.kt`

- [ ] **Step 1: Default-now test change (RED)**

In `WheelDateTimeFieldTest.kt`, replace the test `defaultsToOneHourFromNowWhenNoSelection`:

```kotlin
    @Test
    fun defaultsToNowWhenNoSelection() {
        val now = LocalDateTime.of(2026, 6, 2, 9, 0)

        assertEquals(now, startDateTimeFor(selection = null, now = now))
    }
```

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests '*WheelDateTimeFieldTest*' --console=plain`
Expected: FAIL (still returns now+1h).

- [ ] **Step 2: Implement default-now and the empty label**

In `WheelDateTimeField.kt`:

- `internal fun startDateTimeFor(...)`: replace `?: now.plusHours(1)` with `?: now`.
- Replace the empty-state button text `"No deadline selected"` with `"No start time selected"`.

Re-run the test from Step 1. Expected: PASS.

- [ ] **Step 3: Validator message**

In `AddTaskFormValidator.kt`, replace exactly:

```kotlin
            return AddTaskFormResult.Invalid("Deadline is required")
```

with:

```kotlin
            return AddTaskFormResult.Invalid("Start time is required")
```

In `AddTaskFormValidatorTest.kt`, update the corresponding expected message in `rejectsMissingDeadline` (rename the test to `rejectsMissingStartTime`). Run the validator tests; expected PASS.

- [ ] **Step 4: Screen copy sweep — exact string pairs**

Apply with the Edit tool, one file at a time (each old string appears exactly once unless noted):

`AddTaskScreen.kt`:
- `label = "Deadline picker",` → `label = "Start time picker",`

`BlockerScreens.kt`:
- `label = "New deadline picker",` → `label = "New start time picker",`
- `errorText = "Deadline is required"` → `errorText = "Start time is required"`

`MorningReviewScreen.kt`:
- `label = "Repair deadline picker",` → `label = "Repair start time picker",`
- `errorText = "Deadline is required"` → `errorText = "Start time is required"`

`HomeScreen.kt`:
- `Text("Due: $dueDatetime")` → `Text("Starts: $dueDatetime")`

If any old string is absent or ambiguous, STOP and report what the file actually contains.

- [ ] **Step 5: "Ends HH:MM" readout and title capitalization in AddTaskScreen**

In `AddTaskScreen.kt`:

(a) The Title `OutlinedTextField` (contentDescription "Title input"): add sentence capitalization. Add imports if missing:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
```

and add to that text field's parameters (alongside its existing `singleLine`/`label` parameters):

```kotlin
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
```

(b) Directly AFTER the `WheelDurationField(...)` call block (the one with `label = "Duration minutes input"`), insert:

```kotlin
        deadline?.let { start ->
            Text("Ends ${DateTimeSelection(
                date = start.date,
                hour = start.hour,
                minute = start.minute
            ).let { s ->
                java.time.LocalDateTime.of(s.date, java.time.LocalTime.of(s.hour, s.minute))
                    .plusMinutes(durationMinutes.toLong())
                    .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"))
            }}")
        }
```

Simplify to a small private helper at the bottom of the file instead of the inline lambda if you prefer, but keep the exact display format `uuuu-MM-dd HH:mm` and the text prefix `Ends `. (`deadline` is the existing `DateTimeSelection?` state variable — its name is unchanged in this plan even though it now means the start.)

- [ ] **Step 6: Compile and full JVM suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add client/androidApp/src
git commit -m "feat: start-time picker language with computed end readout

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: Fake-backend fixtures and instrumentation tests

**Files:**
- Modify: `tools/fake-backend/src/fixtures.ts`
- Modify: `tools/fake-backend/src/server.test.ts` (only if it asserts fixture times)
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [ ] **Step 1: Fixture times under new semantics**

In `tools/fake-backend/src/fixtures.ts` (fixture `nowIso` is `2026-05-31T10:00:00.000Z`):

`task-start` (planned, start blocker in the future): block starts 10:30, 30 min →

```typescript
        dueDatetime: "2026-05-31T10:30:00.000Z",
        durationMinutes: 30,
        startAt: "2026-05-31T10:30:00.000Z",
```

`task-deadline` (started, deadline already passed): block 09:40–09:55 →

```typescript
        dueDatetime: "2026-05-31T09:40:00.000Z",
        durationMinutes: 15,
        startAt: "2026-05-31T09:40:00.000Z",
```

and its `startedAt: "2026-05-31T09:50:00.000Z"` → `startedAt: "2026-05-31T09:41:00.000Z"` (started shortly after the block began). `task-invalid` is unchanged (all nulls).

Run: `cd tools/fake-backend && npm test` — if any test asserts the old times, update it to these values. Expected: PASS.

- [ ] **Step 2: Instrumentation contentDescription sweep**

In BOTH androidTest files, apply exact replacements everywhere they occur:

- `onNodeWithContentDescription("Deadline picker")` → `onNodeWithContentDescription("Start time picker")`
- `onNodeWithContentDescription("New deadline picker")` → `onNodeWithContentDescription("New start time picker")`
- `onNodeWithContentDescription("Repair deadline picker")` → `onNodeWithContentDescription("Repair start time picker")`

Verify no stragglers: `rg -n "deadline picker" client/androidApp/src` (case-insensitive check: `rg -ni "deadline picker" client/androidApp/src`) — expected: no matches in main or androidTest sources.

- [ ] **Step 3: Home in-progress label**

If `BlockerActivityFakeBackendTest.kt` (or any androidTest) asserts the Home text `Due: ` (check: `rg -n '"Due:' client/androidApp/src/androidTest`), update those assertions to `Starts: `.

- [ ] **Step 4: Run the emulator lane**

Run: `scripts/e2e-fake-backend.sh` (repo root)
Expected: `BUILD SUCCESSFUL`, `Finished 21 tests on pixel8Api34`, 0 failed. Iterate ONLY within the two androidTest files and the fixtures file if something red remains; report NEEDS_CONTEXT if a failure implicates main-source code.

- [ ] **Step 5: Commit**

```bash
git add tools/fake-backend/src client/androidApp/src/androidTest
git commit -m "test: migrate fixtures and e2e tests to start semantics

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: Full verification and push

- [ ] **Step 1: Everything, once more, from clean**

```bash
cd backend && npm test && npm run typecheck && npm run build
cd ../tools/fake-backend && npm test
cd .. && scripts/e2e-fake-backend.sh
cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: all PASS / `BUILD SUCCESSFUL` / 21 of 21.

- [ ] **Step 2: Push the branch and check CI**

```bash
git push -u origin feature/alarm-reliability-todoist-semantics
gh run watch $(gh run list --workflow=ci.yml --branch=feature/alarm-reliability-todoist-semantics --limit 1 --json databaseId -q '.[0].databaseId')
```

Expected: conclusion `success`. Do not merge yet — Task 9 gates the merge on the device pass.

- [ ] **Step 3: Report READY_FOR_DEVICE**

Stop here and report status READY_FOR_DEVICE with the branch name and CI run link. Task 9 requires the owner.

---

## Task 9: On-device verification, merge, handoff (OWNER REQUIRED)

- [ ] **Step 1: Install the branch build on the Pixel 10** (`cd client && ./gradlew :androidApp:installDebug --console=plain` — uses the real VPS config from local.properties).
- [ ] **Step 2: Owner creates a task starting +3 minutes** (Start at = now+3min, duration 5m) and locks the phone.
- [ ] **Step 3: Assert on-time delivery**: the start blocker appears within ~30s of the scheduled minute. Evidence via `scripts/adb-verify.sh logcat`: `overlay_start skipped reason=keyguard_locked` followed by `start_blocker show`. The status-bar alarm icon is visible while the alarm is pending.
- [ ] **Step 4: Owner checks Todoist calendar**: the task renders from the chosen start for its duration (no post-deadline spill).
- [ ] **Step 5: Merge to master, push, verify CI green** (`git checkout master && git merge feature/alarm-reliability-todoist-semantics && git push`, then `gh run list --workflow=ci.yml --limit 1`). Delete the branch locally and on origin.
- [ ] **Step 6: Handoff** at `docs/handoffs/2026-07-05-alarm-reliability-and-todoist-semantics.md` per `docs/handoffs/README.md`, recording every verification (commands, PASS/FAIL, evidence incl. device timing observed), the friction-log items closed (1, 2, 4, 5, 6 — move them to Fixed in `docs/notes/2026-07-05-usage-friction.md` in the same commit), decisions, and next step (Home task list).
- [ ] **Step 7: Tick this plan's checkboxes, commit docs, push.**

---

## Plan Self-Review

Spec coverage: semantics (Tasks 2-3, 6-7 + decision doc Task 1); setAlarmClock (Task 4); keyguard skip + watcher backoff (Task 5); picker default-now + "Start at" language + Ends readout + capitalization rider (Task 6); fixture/test migration per AGENTS.md (Tasks 2, 3, 6, 7 enumerate every impacted suite); verification incl. on-device and Todoist calendar (Tasks 8-9). Placeholder scan: all code steps carry complete code or exact old→new pairs with a STOP-on-mismatch rule. Type consistency: `computeDeadlineAt(due, durationMinutes)` (Task 2) matches the planner's `dueAt.plus(durationMinutes.minutes)` (Task 3); `AlarmKind` routing in Task 4 matches the enum used in `AlarmReceiver`; label strings in Task 6 match the test sweep pairs in Task 7.
