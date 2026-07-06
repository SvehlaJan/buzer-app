# Catch-Up Start Blocker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `planned` task whose committed block is already in progress emits an immediate START_BLOCKER on every reconcile (strict nag) instead of silently skipping the start-commitment flow.

**Architecture:** One pure-logic change in the shared `AlarmPlanCalculator` plus test coverage. Delivery, dedup-on-display, and stop conditions all fall out of existing machinery (setAlarmClock path, notification-id updates, and the planner condition ceasing to match once the task leaves `planned`).

**Tech Stack:** Kotlin Multiplatform (`kotlin.time`), kotlin.test.

**Source spec:** `docs/superpowers/specs/2026-07-06-catchup-start-blocker-design.md`

---

## Test-impact sweep (per AGENTS.md)

- `client/shared/src/commonTest/.../AlarmPlanCalculatorTest.kt` — directly asserts planner output; updated in Task 1 (including the existing `schedulesDeadlineForInProgressBlock`, whose planned in-progress fixture now additionally gets the catch-up alarm).
- `client/androidApp/src/test/.../EnforcementCoordinatorTest.kt` — asserts alarm lists via the planner. Its fixture's start (11:00/12:00) is in the future relative to its test-now, so no catch-up applies; Task 2 verifies this by running the suite unmodified. If it fails, the fixture's now/start relationship changed — migrate expectations per the spec, do not weaken.
- Instrumentation lane — fixture timestamps (2026-05-31) are entirely past relative to real device time (deadline included), so no catch-up fires; Task 2 runs the lane to prove it.

## Task 1: Planner change (TDD)

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`

- [ ] **Step 1: Create the branch**

```bash
git checkout master
git pull
git checkout -b feature/catchup-start-blocker
```

- [ ] **Step 2: Update/extend the planner tests (RED)**

In `AlarmPlanCalculatorTest.kt` (match the file's existing helper and assertion style — helpers `snapshot(...)`/`task(...)`):

(a) Update `schedulesDeadlineForInProgressBlock` (planned task, due=12:00Z, startAt=12:00Z, duration=60, now=12:30Z): it must now expect TWO alarms in order — START_BLOCKER at `2026-05-28T12:30:00Z` (the nowIso) and DEADLINE_BLOCKER at `2026-05-28T13:00:00Z`. Rename it `schedulesCatchUpStartAndDeadlineForInProgressPlannedBlock`.

(b) Add:

```kotlin
    @Test
    fun schedulesNoCatchUpForStartedInProgressBlock() {
        // started = the user already acted; only the deadline alarm remains.
        // Build the same in-progress fixture but with state = CommitmentState.started
        // (and a non-null startedAt if the task() helper requires one).
        // Expect exactly one alarm: DEADLINE_BLOCKER at 2026-05-28T13:00:00Z.
    }

    @Test
    fun schedulesNothingForPlannedBlockFullyInThePast() {
        // planned, due=12:00Z, startAt=12:00Z, duration=60, now=14:00Z.
        // Expect an empty alarm list (Morning Review territory, not a catch-up).
    }
```

Write real bodies following the file's assertion style (the comments above state the required semantics; no assertion may be weaker than described).

- [ ] **Step 3: Run and verify RED**

Run: `cd client && ./gradlew :shared:testAndroidHostTest --tests '*AlarmPlanCalculator*' --console=plain`
Expected: FAIL — the renamed in-progress test and `schedulesNoCatchUpForStartedInProgressBlock` compile-or-assert failures (the fully-past test may already pass; that is fine).

- [ ] **Step 4: Implement**

In `AlarmPlanCalculator.kt`, replace the entire `desiredAlarmsFor` function with:

```kotlin
    private fun desiredAlarmsFor(task: TaskSnapshotItem, now: Instant): List<DesiredAlarm> {
        val startAt = task.startAt?.let(Instant::parse)
        val dueAt = task.dueDatetime?.let(Instant::parse)
        val durationMinutes = task.durationMinutes
        val deadlineAt = if (dueAt != null && durationMinutes != null) {
            dueAt.plus(durationMinutes.minutes)
        } else {
            null
        }
        val alarms = mutableListOf<DesiredAlarm>()

        if (startAt != null) {
            val preStartAt = startAt.minus(1.hours)
            if (preStartAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.PRE_START_WARNING, preStartAt.toString())
            }
            if (startAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, startAt.toString())
            } else if (
                task.state == CommitmentState.planned &&
                deadlineAt != null &&
                deadlineAt > now
            ) {
                // Catch-up (decision 2026-07-06, spec 2026-07-06-catchup-start-blocker):
                // a planned task whose block is already in progress still demands
                // "I'm starting". Re-armed by every reconcile until the user acts —
                // the strict nag policy chosen by the owner.
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, now.toString())
            }
        }

        if (deadlineAt != null && deadlineAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, deadlineAt.toString())
        }

        return alarms
    }
```

(`CommitmentState` is already imported in this file; no import changes needed. The refactor hoists the deadline computation so both branches share it — behavior of the existing deadline branch is unchanged.)

- [ ] **Step 5: Run and verify GREEN, then the full shared suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`
Expected: `BUILD SUCCESSFUL` (AlarmPlanCalculatorTest: 9 tests).

- [ ] **Step 6: Commit**

```bash
git add client/shared/src
git commit -m "feat: nag catch-up start blocker for in-progress planned blocks

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Cross-suite verification and push

- [ ] **Step 1: androidApp JVM suite (EnforcementCoordinatorTest sweep)**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL` with no changes. If EnforcementCoordinatorTest fails with an extra START_BLOCKER alarm, its fixture's start is not in the future relative to its test-now — update ONLY the affected expected alarm lists to include the catch-up alarm at the test's nowIso, and report exactly what changed.

- [ ] **Step 2: Emulator lane**

Run: `scripts/e2e-fake-backend.sh` (repo root, nothing else touching Gradle concurrently)
Expected: `BUILD SUCCESSFUL`, `Finished 21 tests on pixel8Api34`, 0 failed.

- [ ] **Step 3: Push and CI**

```bash
git push -u origin feature/catchup-start-blocker
```

Then: `gh run watch $(gh run list --workflow=ci.yml --branch=feature/catchup-start-blocker --limit 1 --json databaseId -q '.[0].databaseId')`
Expected: conclusion `success`.

- [ ] **Step 4: Report READY_FOR_DEVICE** — stop; Task 3 needs the owner.

---

## Task 3: On-device check, merge, closeout (OWNER REQUIRED)

- [ ] **Step 1:** Install on the Pixel 10 (`cd client && ./gradlew :androidApp:installDebug --console=plain`, real VPS config).
- [ ] **Step 2:** Owner creates a task with Start = now (picker default). Within one refresh cycle (reopen the app or wait for the next reconcile), the start blocker must appear despite the start being in the past. Tap "I'm starting" — no further start blockers for that task.
- [ ] **Step 3:** Merge to master, push, confirm CI green, delete the branch (local + origin).
- [ ] **Step 4:** Closeout docs in one commit: handoff `docs/handoffs/2026-07-06-catchup-start-blocker.md` (template per `docs/handoffs/README.md`, with the device evidence), move friction item 7 to Fixed in `docs/notes/2026-07-05-usage-friction.md`, tick this plan's checkboxes. Push.

---

## Plan Self-Review

Spec coverage: catch-up condition, strict-nag semantics, started/past exclusions (Task 1); cross-suite impact per AGENTS.md (sweep section + Task 2); on-device stop-condition check and friction closeout (Task 3). Placeholder check: Step 2(b) gives semantic contracts with explicit "write real bodies, no weaker assertions" instruction — deliberate, since the helper signatures live in the file; everything else is complete code. Type consistency: `deadlineAt` hoisting preserves Task-3-of-the-previous-plan's deadline behavior; `CommitmentState.planned`/`started` match the model enum already used in the file.
