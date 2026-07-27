# Real-Backend Instrumentation Suite Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the complete Android instrumentation suite pass against `scripts/e2e-real-backend.sh`, using the real backend with its in-process Todoist adapter and without restoring `tools/fake-backend`.

**Architecture:** Start from clean `master` and treat every current failure as an observation problem first. Preserve production reconciliation and schedule semantics unless a focused red test proves a product defect; repair test selectors and timing through stable semantics and condition-based waits. Model backend failure modes through explicit E2E-server controls, never title-string hooks or unsafe casts.

**Tech Stack:** Kotlin/Compose AndroidX instrumentation, Gradle Managed Device `pixel8Api34`, Node.js/TypeScript Fastify E2E server, Vitest.

---

### Task 1: Establish a reproducible main-backend baseline

**Files:**
- Create: `docs/handoffs/2026-07-26-real-e2e-suite-repair.md`
- Read: `scripts/e2e-real-backend.sh`
- Read: `client/androidApp/build/outputs/androidTest-results/managedDevice/debug/pixel8Api34/`

- [ ] **Step 1: Run each currently failing class independently through the sole supported lane.**

  ```bash
  ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest' scripts/e2e-real-backend.sh
  ANDROID_TEST_CLASS='app.buzer.android.addtask.EditTaskFakeBackendTest' scripts/e2e-real-backend.sh
  ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh
  ANDROID_TEST_CLASS='app.buzer.android.ui.screens.MorningReviewRepairHostTest' scripts/e2e-real-backend.sh
  ```

  Record every failing test name, assertion, generated report path, backend call evidence, and whether it is a selector, state-transition, adapter, or production-behaviour failure. Do not modify sources during this step.

- [ ] **Step 2: Capture a semantics-tree diagnostic only for the failing interaction.**

  Add a temporary `printToLog("real-e2e", ...)` immediately before the failing selector in the affected instrumentation test, rerun that one class, inspect the managed-device report/log, and remove the diagnostic before the green run. The diagnostic must identify the exact node, enabled state, and scroll-parent relationship before any selector change.

- [ ] **Step 3: Record the baseline in the handoff.**

  Include the commands, failures, and the fact that each class uses the main backend's `backend/test/e2eServer.ts` through `scripts/e2e-real-backend.sh`.

### Task 2: Preserve production contracts while removing the untrusted patch's drift

**Files:**
- Keep unchanged: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorSchedule.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidation.kt`
- Keep unchanged: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorWheelDialogs.kt`
- Test: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`
- Test: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt`

- [ ] **Step 1: Lock the API-error contract before any parser change.**

  ```kotlin
  @Test
  fun malformedNonSnapshotErrorObjectStillProducesTypedError() = runBlocking {
      val client = clientResponding(HttpStatusCode.InternalServerError, """{\"code\":123}""")

      val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

      assertNull(error.code)
      assertNull(error.snapshot)
  }
  ```

  Run:

  ```bash
  ./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientErrorTest'
  ```

  Expected: PASS on clean `master`; no parser modification is needed for this E2E repair.

- [ ] **Step 2: Keep stale snapshots fail-closed.**

  Run:

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.screens.DeadlineBlockerUpdateStateTest'
  ```

  Expected: PASS, including `missingOrStaleSnapshotRequiresRefreshInsteadOfOpeningPartialForm`. Do not invent an occurrence key or remove the blocker occurrence-key guard to make an instrumentation test pass.

- [ ] **Step 3: Exclude diagnostic logging and unrelated schedule changes.**

  Verify the clean worktree has no `println("DEADLINE_BLOCKER_`, `println("MORNING_REVIEW_`, `null as unknown as string`, or altered time-wheel rounding. These changes are outside this repair unless Task 1 produces a focused red product test that requires a separately approved fix.

### Task 3: Give the real E2E adapter typed, explicit partial-child failure control

**Files:**
- Modify: `backend/test/e2eServer.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.ts`
- Modify: `backend/test/e2eServer.test.ts`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [ ] **Step 1: Add a red backend E2E test for an unconfirmed second child create.**

  Configure an explicit debug control before the create request, submit a root with two child-add mutations, then assert the first child is persisted, the second child reports `todoist_child_create_unconfirmed`, and the action journal records the correct sent/reconciliation status. The test must not infer failure from a title substring.

- [ ] **Step 2: Run the new backend test and verify RED.**

  ```bash
  npm test --prefix backend -- --runInBand test/e2eServer.test.ts
  ```

  Expected: FAIL because the real E2E adapter has no explicit partial-child failure mode.

- [ ] **Step 3: Add a typed adapter control.**

  Extend the adapter's scenario/control state with a one-shot child-create outcome and return the gateway's declared nullable `todoistTaskId` through `itemAdd`. The implementation shape is:

  ```ts
  type ChildAddOutcome = "created" | "unconfirmed";

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    if (input.kind === "child" && this.controls.consumeChildAddOutcome() === "unconfirmed") {
      return { todoistTaskId: null };
    }
    return { todoistTaskId: input.kind === "root" ? this.world.addRoot(input) : this.world.addChild(input) };
  }
  ```

  Expose that control via the existing authenticated `/__` test surface and add a test-only client method that enables it before the Add flow.

- [ ] **Step 4: Verify green at both boundaries.**

  ```bash
  npm test --prefix backend -- --runInBand test/e2eServer.test.ts
  ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest' scripts/e2e-real-backend.sh
  ```

  Expected: the partial-child test asserts a visible remaining failed draft and the first created child, without weakening visibility assertions.

### Task 4: Repair Compose interactions from inspected semantics, one failing test at a time

**Files:**
- Modify as proven by Task 1: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Modify as proven by Task 1: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- Modify as proven by Task 1: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Modify as proven by Task 1: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- Modify only if a stable semantic identifier is truly absent: the owning composable under `client/androidApp/src/main/kotlin/app/buzer/android/ui/`

- [ ] **Step 1: Fix one observed selector/state failure with its original assertion strength.**

  Use a stable test tag or explicit content description for a unique interaction. Require the expected control to be displayed and enabled before clicking. Use `performScrollTo()` only after the Task 1 semantics dump proves the node is within the scaffold's vertical-scroll container; never apply it to the fixed footer action.

- [ ] **Step 2: Run that class alone and require it to pass.**

  ```bash
  ANDROID_TEST_CLASS='<exact failing class>' scripts/e2e-real-backend.sh
  ```

  Expected: all tests in that class pass with no production logging, key fabrication, or time-picker change.

- [ ] **Step 3: Repeat for each remaining class.**

  Preserve each test's behavioural contract: draft discard confirmation, host-specific Repair return, pending retry with a reused action ID, activity remains open on failure, and full update payloads retain root and child mutations.

### Task 5: Verify the complete release gate and hand off safely

**Files:**
- Modify: `docs/handoffs/2026-07-26-real-e2e-suite-repair.md`

- [ ] **Step 1: Run backend and JVM gates.**

  ```bash
  npm test --prefix backend
  npm run typecheck --prefix backend
  cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
  ```

  Expected: all commands pass.

- [ ] **Step 2: Run the complete unfiltered instrumentation suite.**

  ```bash
  scripts/e2e-real-backend.sh
  ```

  Expected: every Android instrumentation test passes against `backend/test/e2eServer.ts`; no fake-backend process, endpoint, or script is used.

- [ ] **Step 3: Review and record the result.**

  Run `git diff --check`, inspect changed paths for accidental production drift, record exact commands/results and remaining manual verification in the handoff, then request code review before merging.
