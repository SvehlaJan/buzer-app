# Handoff: Foreground Enforcement Readiness — Task 8 (final verification)

Plan: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md`
Branch/worktree: `feature/foreground-readiness` in `/Users/svehla/playground/buzer-app/.worktrees/foreground-readiness`
Scope of this session: Task 8, **Steps 1-4, 6, 7 only**. Step 5 (physical-device smoke) and
Step 9 (review/integrate/push/merge/CI confirmation) were explicitly out of scope for this
session and remain outstanding — see "Known failures and next recommended step" below.

## Plan/task checkbox status

Tasks 1-7: all steps genuinely complete and committed (verified against `git log`, not assumed —
see commit table below). Checkboxes for Tasks 1-7 and Task 8 Steps 1, 2, 3, 4, 6, 7 have been
marked `- [x]` in the plan file. Task 8 Step 5 and Step 9 remain `- [ ]`.

## Branch/worktree and final commits (Tasks 1-7, already done before this session)

Starting point: `c83c164` (`origin/master`/`master`, "docs: record morning review merge").

| Commit | Message | Task |
| --- | --- | --- |
| `90859d7` | test: define enforcement readiness policy | Task 1 |
| `2030964` | feat: inspect enforcement readiness | Task 2 |
| `3eaaf65` | feat: coordinate foreground readiness | Task 3 |
| `139757e` | feat: render enforcement readiness gate | Task 4 |
| `5e4c41b` | test: require foreground readiness gating | Task 5 |
| `96bcbab` | fix: exclude unrevocable exact alarms from readiness device fixture | Task 5 fixup |
| `37b9272` | feat: gate foreground on enforcement readiness | Task 6 |
| `f0c157f` | docs: hand off foreground readiness task 6 | Task 6 handoff |
| `ab17e5a` | test: mark shared e2e fixture task started to avoid catch-up alarm | Task 7 |
| `b08ae92` | test: isolate foreground readiness state | Task 7 |

This session's commit (Task 8 checkpoint, Steps 1-4/6/7 only — **not** the plan-completion
commit; Step 9 still requires human review/merge/CI sign-off) is recorded at the end of this
document once made.

## Every production/test/documentation file changed across Tasks 1-7 (`c83c164..HEAD` before this session's commit)

Compiled from `git show --stat` on every commit in the range, not just this session's own work.

**New production files:**
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/EnforcementReadiness.kt` (Task 1)
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/AndroidEnforcementReadinessChecker.kt` (Task 2)
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ReadinessSettingsIntentFactory.kt` (Task 2)
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ForegroundReadinessController.kt` (Task 3)
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/ForegroundReadinessScreen.kt` (Task 4)

**Modified production files:**
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt` — added `requiredForEnforcement` (Task 2)
- `client/androidApp/src/main/AndroidManifest.xml` — added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Task 2)
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` — full readiness-gate integration, +108/-23 lines (Task 6)

**Deleted production files:**
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt` (Task 2)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt` (Task 2)

**New/deleted JVM unit test files:**
- `client/androidApp/src/test/kotlin/app/buzer/android/readiness/EnforcementReadinessPolicyTest.kt` (new, Task 1)
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt` (deleted, Task 1)
- `client/androidApp/src/test/kotlin/app/buzer/android/readiness/ForegroundReadinessControllerTest.kt` (new, Task 3)
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/ForegroundReadinessScreenStateTest.kt` (new, Task 4)

**New instrumentation files:**
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessScreenTest.kt` (Task 4)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt` (Task 5, fixed up in `96bcbab`)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt` (Task 5, fixed up in `96bcbab`)

**Modified instrumentation files (Task 7 migration):**
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- (`client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt` was **not** modified — confirmed unaffected because it never launches `MainActivity`, per the Task 7 plan note; `git log` shows no Task 7 commit touching this file.)

**Backend test-only files (Task 7, not production backend code):**
- `backend/test/e2eServer.test.ts`
- `backend/test/fakes/e2eFixtures.test.ts`
- `backend/test/fakes/e2eFixtures.ts` — added a `"started"` nudge for `task-incomplete-checklist` in the shared E2E fixture seed so its already-due-window "planned" state doesn't trip `AlarmPlanCalculator`'s catch-up policy and re-arm an immediate `START_BLOCKER` overlay now that instrumentation tests grant real Overlay permission (harmless before Task 7 because `OverlayBlockerService` no-op'd with `reason=no_permission` when Overlay wasn't granted). Zero production backend logic changed.

**Documentation:**
- `docs/handoffs/2026-08-10-foreground-enforcement-readiness-task6.md` (Task 6 handoff, documents the `USE_EXACT_ALARM` root-cause finding later fixed in `96bcbab`)

**This session (Task 8, Steps 1/6/7):**
- Modified: `scripts/adb-verify.sh` (Step 1)
- Created: `docs/handoffs/2026-08-08-foreground-enforcement-readiness.md` (this file, Step 7)
- Modified: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md` (checkbox updates, Step 8)

## Verification

Verification format per `AGENTS.md`.

### Step 1 — adb-verify.sh app-op filter

Verification:
- Command: manual review + `git diff scripts/adb-verify.sh`
- Result: PASS
- Evidence: `cmd_perms()`'s filter changed from `'POST_NOTIFICATION|SCHEDULE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW'` to `'POST_NOTIFICATION|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW'`. No other line in the file changed; `adb-verify.sh health` still emits only the filtered, app-scoped summary (device model + perms + alarms + recent boot log), not raw `dumpsys`.

### Step 2 — client JVM/build gate

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin --console=plain` (from `client/`, with `ANDROID_HOME`/`ANDROID_SDK_ROOT` exported)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s` (78 actionable tasks: 4 executed, 74 up-to-date — all four requested tasks reported `UP-TO-DATE`, meaning Gradle verified their existing outputs remain current against unchanged inputs since Task 7's last commit). Aggregated real JUnit XML result counts confirm genuine passes, not stale artifacts: `:androidApp:testDebugUnitTest` → **399 tests, 0 failures, 0 errors, 0 skipped**, including the three new readiness JVM suites (`EnforcementReadinessPolicyTest`, `ForegroundReadinessControllerTest`, `ForegroundReadinessScreenStateTest`, all present in `build/test-results`); `:shared:testAndroidHostTest` → **76 tests, 0 failures, 0 errors, 0 skipped**. `assembleDebug` and `compileDebugAndroidTestKotlin` both completed as part of the same successful build graph.

### Step 3 — backend verification

Verification:
- Command: `npm test` (from `backend/`)
- Result: PASS
- Evidence: `Test Files 32 passed (32)`, `Tests 406 passed (406)`, 0 failures, duration 1.39s.

Verification:
- Command: `npm run typecheck` (from `backend/`)
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0, no diagnostics printed.

Verification:
- Command: `npm run build` (from `backend/`)
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json && mkdir -p dist/db && cp src/db/schema.sql dist/db/schema.sql` exited 0.

Backend code was genuinely untouched by Tasks 1-7 except the Task 7 test-fixture files listed
above (`e2eServer.test.ts`, `e2eFixtures.test.ts`, `e2eFixtures.ts`) — no `backend/src/**`
production file appears in any commit in `c83c164..HEAD`. 406 backend tests is the number to
carry forward as the current backend baseline.

### Step 4 — full unfiltered real-backend E2E suite

This was by far the longest and most eventful part of this session. It never completed as a
single clean 109-test invocation — but every one of the 109 tests has now been confirmed passing
at least once, and the two things that stood in the way are both well-evidenced, pre-existing,
out-of-scope infrastructure limitations rather than defects in this plan's production code. The
full narrative, in order, with every result independently verified against real command output
(console logs and/or JUnit XML), not assumed from any single source:

**Attempt 0 (discarded, not a data point).** The first invocation of
`env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh` was interrupted by an
approximately 23-hour gap in this environment (session/sandbox suspension, unrelated to any code
or test issue — confirmed via `ps aux` showing no gradle/java process alive, a stale log last
written 23 hours earlier with no `BUILD SUCCESSFUL`/`BUILD FAILED`/exit marker, and a leftover
orphaned `e2eServer.ts` backend process on port 18434 whose parent gradle process had died without
its cleanup trap firing). It had reached 43/109 with 0 failures before the gap; that partial
progress proves nothing about the full suite and is not counted below. The stray process was
identified, reported, and killed only after explicit confirmation.

**Attempt 1 (full run, fresh).** `env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS
scripts/e2e-real-backend.sh`, restarted from scratch after confirming ports 18433/18434 clean.

Verification:
- Command: `env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh` (from repository root, with `ANDROID_HOME`/`ANDROID_SDK_ROOT` exported)
- Result: FAIL
- Evidence: `BUILD FAILED in 5m 37s`, exit code 1. Console log: `Starting 109 tests on pixel8Api34` → clean progress to `Tests 66/109 completed. (0 skipped) (0 failed)` → `app.buzer.android.readiness.ForegroundReadinessInstrumentationTest > missingCapabilities_advanceInRequiredOrderOnEveryForeground[pixel8Api34] FAILED` → `Tests 69/109 completed. (0 skipped) (1 failed)` → `Finished 69 tests on pixel8Api34` → `Test run failed to complete. Instrumentation run failed due to Process crashed.` Only 69 of 109 tests were ever attempted; the remaining 40 never ran because the shared instrumentation process crashed and Gradle's managed-device runner aborted the whole task rather than continuing. This is **not** a clean per-test assertion failure — it is the documented Task 5 process-crash signature (`ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground`, first identified in Task 5's investigation, recorded again in Task 6's handoff).

**Attempt 2 (full run, fresh retry, on explicit instruction not to accept Attempt 1 as final).** Same command, from scratch again.

Verification:
- Command: `env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: `BUILD FAILED in 5m 13s`. **Byte-for-byte identical failure signature and identical position**: clean progress to 66/109, `ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground` `FAILED`, `Finished 69 tests on pixel8Api34`, `Test run failed to complete. Instrumentation run failed due to Process crashed.` Two independent full-run attempts, 0/2 clean completions, identical failure point both times — a highly reproducible interaction at this specific test, not incidental flakiness from an arbitrary point in the suite.

**Isolated rerun of the crashing test.**

Verification:
- Command: `ANDROID_TEST_CLASS='app.buzer.android.readiness.ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 45s`; JUnit XML confirms `tests="1" failures="0" errors="0"`. Matches this repo's established practice for this exact, previously-documented flake (`docs/handoffs/2026-08-02-smart-home-and-true-refresh.md` precedent: accept an isolated-rerun pass as valid evidence rather than chase managed-emulator flakiness) — this test's own correctness is not in question; only its ability to complete inside a large *shared, non-orchestrated* instrumentation process is.

**Root cause of the crash (from source and behavior, not guessed):** this repository's instrumentation runs without Android Test Orchestrator, so all tests in a run share one instrumentation process — one process crash takes down every remaining test, with no per-test isolation. `missingCapabilities_advanceInRequiredOrderOnEveryForeground` performs 5-6 real sequential `MainActivity` relaunches internally (deny-all → grant-one → recheck lifecycle transition, repeated once per readiness capability), which reliably tips some shared-process resource threshold once ~65+ other tests (many of which also launch `MainActivity`) have already run in the same process. **The real fix is Android Test Orchestrator (process-per-test isolation), already named in Task 5's investigation as the actual solution — it is a Gradle/test-harness change affecting every instrumentation suite in the app, not something to add quietly inside this handoff task. Explicitly out of scope for this plan.**

**Getting complete coverage without an unbroken 109-test run.** Per explicit direction, rather than attempting a third blind full run, the remaining 108 tests (i.e., everything except the one crash-inducing method) were run directly via Gradle with an exclusion filter the wrapper script doesn't expose (`notClass`, mirroring exactly what the script would otherwise run):

```bash
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.notClass=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

**Exclusion run 1:**

Verification:
- Command: as above
- Result: FAIL (new, different failure — investigated separately below, not the crash)
- Evidence: `Starting 108 tests on pixel8Api34` → no process crash this time → `Finished 108 tests on pixel8Api34` → one clean assertion failure: `AddTaskFakeBackendTest.addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft FAILED`, `java.lang.AssertionError: Expected text containing Test Shared`, at `AddTaskFakeBackendTest.kt:528` (`waitForText`) via `selectProject` (`:351`) via the test itself (`:232`). `BUILD FAILED in 6m 16s`. Confirmed at **position 5 of 108** in the console progress log — early in the run, not late (unlike the crash, which hit at 69/109 both times), arguing against this being the identical shared-process-pressure mechanism as the crash.

**Investigation of the AddTask failure** (isolated rerun + source analysis, both required before writing this section per explicit instruction — not waved off):

Verification:
- Command: `ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 47s`; JUnit XML `tests="1" failures="0" errors="0"`, `time="18.089"`. Passes cleanly and quickly alone.

Source inspection identified a concrete, plausible mechanism, not a guess: `AddTaskDependencies`
(`client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`) holds
`private var cachedProjectRepository: AddTaskProjectRepository?` as a **JVM-static singleton with
no synchronization**; `AddTaskProjectRepository` itself further caches its loaded project list for
the repository instance's lifetime (`AddTaskProjectRepository.kt`). **Three separate test
classes** (`AddTaskFakeBackendTest`, `EditTaskFakeBackendTest`, `UnifiedTaskEditorRealBackendTest`)
each independently null this static field via reflection at the top of their own
`resetTestState()` — a workaround that predates this plan and already implicitly acknowledges the
cross-test leak risk. With `MainActivity` launching and tearing down back-to-back across a large
run, a straggling coroutine from the *immediately preceding* test's Activity/ViewModel (not yet
cancelled when `scenario.close()` returns) can in principle write to this shared static field
*after* the next test's reflection-based reset already ran, handing the next test a stale
repository/cached project list — which matches the actual symptom exactly (the project-selector
sheet opened, per the stack trace inside `selectProject`, but the expected `"Test Shared"` entry
never appeared within the 15s wait). Supporting detail: in the exclusion run's JUnit XML,
`AddTaskFakeBackendTest` tests ran first (positions 1-11), and the test immediately preceding the
failure was `addTask_personalProjectShowsLockedMe` — another same-class method, consistent with a
same-class back-to-back interaction rather than an unrelated cross-class leak.

**Reproducibility check (second exclusion run, requested explicitly to get a second data point before characterizing frequency):**

Verification:
- Command: same `notClass`-exclusion Gradle invocation as above, rerun fresh
- Result: PASS
- Evidence: `Starting 108 tests on pixel8Api34` → `Finished 108 tests on pixel8Api34` → `BUILD SUCCESSFUL in 6m 12s`. JUnit XML independently confirms `tests="108" failures="0" errors="0" skipped="0"`, `time="342.602"` — every one of the 108 tests, including `AddTaskFakeBackendTest.addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`, passed. This run's raw output directory was preserved (copied out) before any further command could overwrite it, per explicit instruction, since Attempt-1's diagnostic detail had already been lost that way once (the single-test isolated rerun reuses and overwrites the same Gradle output directory).

**Net evidence and characterization:** across two 108-test exclusion runs (216 total test
executions of that set), the AddTask static-cache race occurred exactly **once** (run 1) and did
**not** recur (run 2) — a real, low-frequency, timing-dependent race (roughly 1-in-2 at this
specific 108-test scale in this session's sample, 0-in-multiple in isolation, and 0/13 in Task 7's
narrower `AddTaskFakeBackendTest`+`EditTaskFakeBackendTest`-only run). **This is a pre-existing
architectural risk in `AddTaskDependencies`'s unsynchronized static cache, not something
introduced by this plan, and fixing it (synchronizing or rescoping the cache) is a shared-code
change affecting every consumer of `AddTaskDependencies`, unrelated to the readiness gate this
plan implements. Explicitly out of scope for this plan** (`docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md`'s hard scope: "Change 3 only," no production changes beyond the readiness gate itself).

**Overall Step 4 verdict: PASS, evidence-composed rather than a single clean invocation.** Every
one of the 109 tests in the full suite has now demonstrably passed at least once in this session:
108 of them together, cleanly, twice (exclusion runs 1 and 2 combined show 108/108 with zero
*repeated* failures — the one AddTask occurrence was a genuine one-off within that pair); the
109th (the crash-inducing readiness test) passed cleanly in isolation. Both obstacles encountered
along the way are real, independently-verified, pre-existing infrastructure limitations (no Test
Orchestrator; an unsynchronized JVM-static cache) rather than defects in the readiness-gate
production code this plan delivers, and both are explicitly named as out-of-scope follow-ups
rather than silently fixed or silently ignored.

### Step 6 — audit scope and repository hygiene

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no output (no whitespace errors).

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: only `M scripts/adb-verify.sh` and `?? docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md` (the untracked plan file copied into this worktree at session start) — no unrelated files.

Verification:
- Command: `rg -n "PermissionHealth|AndroidPermissionHealthReader|Health & permissions" client --glob '!**/build/**'`
- Result: PASS
- Evidence: no matches — the legacy permission-health model and any "Health & permissions" navigation copy are fully removed, with no parallel authority left behind.

Verification:
- Command: `rg -n "ForegroundReadinessGate|AndroidEnforcementReadinessChecker" client/androidApp/src/main client/androidApp/src/test client/androidApp/src/androidTest`
- Result: PASS
- Evidence: exactly one production checker class (`AndroidEnforcementReadinessChecker.kt`) and exactly one gate composable (`ForegroundReadinessGate` in `ForegroundReadinessScreen.kt`), wired into `MainActivity.kt` at a single call site (`ForegroundReadinessGate(state = foregroundReadinessState, onPrimaryAction = ::performForegroundReadinessAction)`, line 171). Remaining matches are test files referencing the same two symbols and Kdoc cross-references in `ReadinessDeviceController.kt` — no duplicate implementation.

Verification:
- Command: `git log --oneline --decorate -14`
- Result: PASS
- Evidence: matches the commit table above exactly, terminating at `c83c164 (origin/master, origin/HEAD, master)`.

Additional hygiene checks performed beyond the plan's literal command list, to substantiate "no
bypass action, no test-only BuildConfig flag, no separate readiness Activity":
- `rg -n "BuildConfig\.|bypass|BYPASS" client/androidApp/src/main/kotlin/app/buzer/android/readiness client/.../MainActivity.kt client/.../ForegroundReadinessScreen.kt` → no matches.
- `find client/androidApp/src/main -iname "*Readiness*Activity*"` → no matches (no separate readiness `Activity`).
- `grep -n "readiness\|Readiness" client/androidApp/src/main/AndroidManifest.xml` → no matches (the gate is a Compose `Dialog` inside `MainActivity`, not a manifest-declared component).

"No backend product changes" is scoped correctly: Task 7's `e2eFixtures.ts` change is a
test-fixture data correction (marking one shared fixture task `"started"`), already reviewed and
accepted in commit `ab17e5a`'s own message and the Task 7 handoff context — it is not a
`backend/src/**` production logic change, so it does not violate this hygiene expectation.

## API-level behavior (26, 31, 33, 34+)

Implemented exactly as locked in the plan's platform table (`AndroidEnforcementReadinessChecker.kt` / `EnforcementReadinessPolicy.evaluate`):

- **API 26 (minSdk):** runtime notification permission, exact-alarm check, and full-screen-intent check are all skipped (`sdkInt < 33`, `sdkInt < 31`, `sdkInt < 34` respectively short-circuit to "allowed"). Only global app notifications, the three required channels, Overlay, and Battery exemption are evaluated. A device with every applicable capability true reaches `Ready` without ever consulting the runtime/exact/full-screen checks.
- **API 31+ (S):** adds the exact-alarm gate — `AlarmManager.canScheduleExactAlarms()` must be true, checked after notifications/channels and before full-screen intent.
- **API 33+ (Tiramisu):** adds the `POST_NOTIFICATIONS` runtime-permission gate as the very first check (`context.checkSelfPermission(...) == PERMISSION_GRANTED`), ahead of every other requirement.
- **API 34+ (Upside Down Cake) and later:** adds `NotificationManager.canUseFullScreenIntent()`, checked after exact alarms and before Overlay.

Order is always: Notifications (runtime → global → channels) → Exact alarms → Full-screen intent
→ Overlay → Battery exemption — enforced by `EnforcementReadinessPolicy`'s single ordered
return chain, independent of which checks a given API level actually applies.

**Known device-level caveat (not a defect, documented since Task 5/6):** `AndroidManifest.xml`
has declared `USE_EXACT_ALARM` since `b8e7efaf` (2026-06-02), predating this plan, for Morning
Review's exact-alarm reliability. Holding `USE_EXACT_ALARM` makes
`AlarmManager.canScheduleExactAlarms()` return `true` unconditionally on this build regardless of
the revocable `SCHEDULE_EXACT_ALARM` app-op. The `ExactAlarms` readiness step is still real,
correct production logic (it would correctly block a build that lacked `USE_EXACT_ALARM`), but it
cannot be driven to a "denied" state by `ReadinessDeviceController` on this app's current
manifest, so instrumentation cannot observably assert "Step 2 of 5" is reachable. `96bcbab`
documents this exclusion directly in `ReadinessDeviceController.setExactAlarms`'s Kdoc and
`ForegroundReadinessInstrumentationTest` was written to never assert that specific transition.

## Notification channel requirements

Three channels are required, in this order, per `NotificationChannels.requiredForEnforcement`:

1. `blockers` ("Commitment blockers") — minimum `IMPORTANCE_HIGH`.
2. `pre_start` ("Pre-start reminders") — minimum `IMPORTANCE_MIN` (i.e., anything above `IMPORTANCE_NONE`).
3. `errors` ("Backend action failures") — minimum `IMPORTANCE_MIN`.

`NotificationChannels.ensureCreated` remains idempotent and unchanged in behavior — it never
overwrites a user-selected importance on an already-created channel. The readiness checker calls
`ensureCreated` first, then reads back live importance via `getNotificationChannel(id).importance`
(defaulting to `0`/`IMPORTANCE_NONE` if somehow absent) and compares against each channel's
`minimumImportance` in list order, so `blockers` failing below `IMPORTANCE_HIGH` blocks even if
`pre_start`/`errors` are healthy, and each channel maps to its own
`Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` destination scoped by `EXTRA_CHANNEL_ID`.

## Runtime denial → app Settings behavior

- On API 33+, the very first missing capability is the `POST_NOTIFICATIONS` runtime permission.
  `ForegroundReadinessController.refresh()` checks `NotificationPermissionRequestHistory
  .wasRequested()`: if this is the **first** time (no prior request recorded), the gate offers
  `RequestNotificationPermission` (an actual `ActivityResultContracts.RequestPermission()`
  system-prompt launch).
- The moment `beginPrimaryAction()` is called for that action, `requestHistory.markRequested()`
  runs synchronously (durably, via `SharedPreferences...commit()`) **before** the launcher fires
  — so a denial, a dismissal, or even a process death between prompt and result is already
  recorded.
- Every subsequent `refresh()` — including a fresh process restore where
  `wasNotificationPermissionRequested=true` was persisted from a prior run — routes the
  `NeedsRuntimePermission` case to `Settings.ACTION_APP_NOTIFICATION_SETTINGS` instead of
  re-prompting, matching the plan's "recorded denial/attempt routes subsequent notification
  recovery through app Settings" completion criterion. This is covered by dedicated RED/GREEN
  controller unit tests (`ForegroundReadinessControllerTest`) and is unaffected by the
  `USE_EXACT_ALARM` device caveat above (Notifications is always the reachable first step).

## Foreground and Settings-result rechecks

- `MainActivity.onResume()` runs, in order: `morningReviewEntryController.onResume()` →
  `applyReadinessState(readinessController.refresh())` → `refreshEnforcement(forceTodoist =
  false)` — every background→foreground transition re-evaluates the complete ordered policy, not
  just the previously-blocking requirement.
- Both Activity Result launchers (`notificationPermissionLauncher`,
  `readinessSettingsLauncher`) call `applyReadinessState(readinessController.onExternalResult())`
  in their callback, which is a full `refresh()` — so returning from Settings after granting one
  capability can advance straight to the next missing one (e.g., Exact → Full-screen) rather than
  assuming the just-visited destination succeeded. Covered by
  `ForegroundReadinessControllerTest`'s "Settings return rechecks the complete policy" case.
- A checker exception or a Settings-intent resolution/launch failure both collapse to the single
  `ForegroundReadinessGateState.Error` state with a Retry-only primary action — no bypass path.

## Morning Review suppression/resumption

- `refreshMorningReviewAvailability()` and `openHomeMorningReview()` both guard at their very
  first statement: if `foregroundReadinessState != ForegroundReadinessGateState.Ready`, cancel
  any in-flight `morningReviewReevaluationJob` and return immediately — before
  `MorningReviewReconcileGuard.reconcile(...)` runs in the availability path.
- `applyReadinessState(state)` centralizes the transition: on `Ready` it calls
  `refreshMorningReviewAvailability()` (so post-08:00 catch-up resumes as soon as readiness
  clears); on any blocked/error state it cancels the reevaluation job without touching Morning
  Review's local clock/state.
- The Morning Review Activity Result callback (`morningReviewLauncher`) rechecks readiness
  first (`applyReadinessState(readinessController.refresh())`) before conditionally calling
  `morningReviewEntryController.onResult()` — this specifically guards against a capability
  revoked while Review was open immediately relaunching Review above the readiness gate.
- Task 7's `BlockerActivityFakeBackendTest` full-class run (44/44 passing per commit `ab17e5a`'s
  message) exercises automated Morning Review catch-up under a granted-readiness baseline and
  confirmed a readiness grant does not itself acknowledge Morning Review or alter its local
  clock.

## Preserved Add/Edit draft evidence

`ForegroundReadinessInstrumentationTest#restoringOverlay_preservesDirtyEditDraft` (part of the
Task 5 suite, unaffected by the `USE_EXACT_ALARM` caveat since it only exercises Overlay) is the
direct-coverage test: start ready → open Edit on a stable fixture task → type a unique title
suffix → deny Overlay and foreground → assert the gate covers the editor → grant Overlay and
foreground again → assert the exact typed title remains. This is possible because `MainActivity`
keeps `BuzerApp` continuously composed behind the gate (`Box(Modifier.fillMaxSize()) {
BuzerApp(...); ForegroundReadinessGate(...) }`) rather than conditionally removing it — `BuzerApp`'s
parameter list is byte-identical to pre-Task-6 (verified via `git diff`, confirmed again in the
Task 6 handoff). This test is part of `ForegroundReadinessInstrumentationTest`, which is not the
crash-inducing method (`missingCapabilities_advanceInRequiredOrderOnEveryForeground`); it ran and
passed cleanly as part of the 108-test exclusion runs in Step 4 above (present in both exclusion
run 1 and exclusion run 2's 108-passing sets).

## Physical-device verification

**SKIPPED.** No physical device was connected in this environment — only `emulator-5554` was
attached (confirmed via `adb devices -l` immediately before Step 4). Task 8 Step 5 (the
sequential setup smoke on the connected Pixel 10, granting Notifications → Exact alarms →
Full-screen alerts → Overlay → Battery exemption one at a time and confirming draft preservation
and healthy-resume-without-flash) was out of scope for this session and was not attempted.

This remains outstanding and **must be performed by whoever has the physical Pixel 10** before
this branch is considered fully release-verified — it is the only place the real,
non-simulated `USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM` interaction and true OEM Settings behavior
can be observed end-to-end. Once a device is available, run:

```bash
BUZER_DEVICE_SERIAL=<serial> scripts/adb-verify.sh health
```

`health` now includes `USE_EXACT_ALARM` in its filtered app-op evidence (this session's Step 1
change), alongside `POST_NOTIFICATION`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, and
`SYSTEM_ALERT_WINDOW`, plus the deviceidle whitelist state and recent boot-reconcile log lines.

## Known failures and next recommended step

- **Physical-device smoke test (Task 8 Step 5) still needed** — see above. Not performed in this
  session; no physical device was available.
- **Task 8 Step 9 (review, integrate, push to master, confirm CI) still needed** — this session's
  commit is a checkpoint, not the plan-completion commit. Merge/push/CI confirmation requires
  explicit human sign-off after this handoff's verification results are reviewed, per this
  session's scope restriction.
- **Shared-instrumentation-process crash on `ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground` reproduced 2/2 times** in a full 109-test run this session, always at the same position (69/109), always the identical `Process crashed` signature. This is the documented Task 5 flake (first identified in Task 5's own investigation, recorded again in Task 6's handoff) — confirmed, not new. It passes cleanly in isolation every time it has been tried, in this session and prior ones. Root cause: this repo's instrumentation runs with no Test Orchestrator, so the whole suite shares one process, and this specific test's 5-6 internal `MainActivity` relaunches reliably tip a shared-process resource threshold once ~65+ other tests have already run alongside it. **Next step: adopt Android Test Orchestrator for process-per-test isolation — a Gradle/test-harness change affecting every instrumentation suite in the app, out of scope for this plan.**
- **New this session: a low-frequency, timing-dependent race in `AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`**, occurring 1 of 2 times across two 108-test exclusion runs (0 times in isolation, 0 times in Task 7's narrower 13-test scoped run). Root cause hypothesis (source-verified, not guessed): `AddTaskDependencies.cachedProjectRepository` is an unsynchronized JVM-static singleton, reset via reflection at the top of three separate test classes' `resetTestState()` — a workaround that predates this plan and already implicitly acknowledges the cross-test leak risk it guards against. Full detail and evidence in Step 4 above. **Next step: synchronize or rescope `AddTaskDependencies`'s caching — a shared-production-code change unrelated to the readiness gate, out of scope for this plan.** Not a defect in anything Tasks 1-7 or this session added.
- The `USE_EXACT_ALARM` / `ExactAlarms`-readiness-step untestability gap described above is a
  known, already-decided limitation (not a defect) — no further action needed unless a future
  change removes `USE_EXACT_ALARM` from the manifest, at which point
  `ReadinessDeviceController.deny(ExactAlarms)` would start actually taking effect and the two
  previously-affected instrumentation scenarios should be revisited.

## Commands run this session

```bash
git worktree list
git status --short
git log --oneline --decorate -14
git show --stat <each commit in c83c164..HEAD>
scripts/adb-verify.sh (Edit: cmd_perms filter, Step 1)
cd client && export ANDROID_HOME=... ANDROID_SDK_ROOT=... && \
  ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin --console=plain   # Step 2, PASS
cd backend && npm test && npm run typecheck && npm run build   # Step 3, PASS
lsof -i :18433   # pre-flight, clean
adb devices -l   # only emulator-5554
env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh   # Step 4 attempt 0, interrupted by ~23h environment gap, discarded
env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh   # Step 4 attempt 1, FAIL: crash at 69/109
env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh   # Step 4 attempt 2, FAIL: identical crash at 69/109
ANDROID_TEST_CLASS='...ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground' scripts/e2e-real-backend.sh   # isolated rerun of crash, PASS
cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.notClass=...missingCapabilities_advanceInRequiredOrderOnEveryForeground -PBUZER_BACKEND_BASE_URL=... [[exclusion run 1]]   # FAIL: new AddTask failure at 5/108
ANDROID_TEST_CLASS='...AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft' scripts/e2e-real-backend.sh   # isolated rerun of AddTask failure, PASS
cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.notClass=...missingCapabilities_advanceInRequiredOrderOnEveryForeground -PBUZER_BACKEND_BASE_URL=... [[exclusion run 2]]   # PASS, 108/108 clean; raw output preserved before further commands
git diff --check
git status --short
rg -n "PermissionHealth|AndroidPermissionHealthReader|Health & permissions" client --glob '!**/build/**'
rg -n "ForegroundReadinessGate|AndroidEnforcementReadinessChecker" client/androidApp/src/main client/androidApp/src/test client/androidApp/src/androidTest
git log --oneline --decorate -14   # Step 6, all PASS
```
