# Task editor redesign — implementation handoff

## Plan / task status

Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`.

- [x] Tasks 1–9 — collaborator persistence and projection, nullable schedule and
  membership mutations, shared DTOs, pure editor primitives, compact Compose
  scaffold, Add/Edit migration, and fake/real E2E parity coverage.
- [x] Task 10 step 1 — backend full gate.
- [ ] Task 10 steps 2–5 — final Android JVM/build, fake/real Android E2E, and device
  inspection remain externally blocked by Gradle-cache elevation limits.

## Delivered behavior

- Add and Edit share a compact, Schedule-first editor with draft-only Project,
  Assignee, and Repeat sheets; Project and Assignee are validated together in the
  single create/update mutation.
- Todoist collaborator profiles and active project membership are mirrored and
  exposed by `/projects`; personal projects visibly lock `Me` while sending a null
  assignee.
- Self tasks require a complete schedule; a non-self assignment may be unscheduled,
  which clears recurrence to `never`. Explicit null schedule patches clear both Todoist
  due and duration fields.
- The new date/time/duration wheels are independent pending-value dialogs. Legacy
  repair/reschedule keeps the original `CommitmentEditor` pickers.
- Add/Edit preserve full drafts and action IDs on retry, protect dirty Back/Cancel/
  system-Back exits, and retain custom recurrence until a deliberate replacement.
- Fake and real E2E fixture worlds now cover collaborator JSON, membership checks,
  schedule/recurrence parity, project/assignee root patches, and redesigned semantic
  instrumentation controls.

## Commits

Implementation spans `f673872` through `f87a28d` on
`feature/task-editor-redesign`. The final E2E parity follow-ups are:

- `c5a39be test: cover redesigned task editor contracts`
- `14d3168 fix: enforce fake task schedule validation`
- `0977cc6 fix: enforce fake recurrence schedule contract`
- `e5f183a fix: mirror Todoist recurrence display in fake backend`
- `f87a28d fix: align fake root update parity`

## Verification

- Command: `npm test` from `backend/`
  Result: PASS
  Evidence: 32 files, 386 tests passed.

- Command: `npm run typecheck` from `backend/`
  Result: PASS
  Evidence: TypeScript completed with no diagnostics.

- Command: `npm test` from `tools/fake-backend/`
  Result: PASS
  Evidence: 19 tests passed, including schedule, recurrence, membership, and root
  update parity cases.

- Command: `git diff --check 65bda28..HEAD`
  Result: PASS
  Evidence: no whitespace diagnostics.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin` from `client/`
  Result: BLOCKED
  Evidence: the sandbox cannot open the existing Gradle wrapper-cache lock under
  `~/.gradle/wrapper/dists/.../gradle-9.5.1-bin.zip.lck`. The required elevated retry
  was automatically rejected because the environment usage limit was exhausted. No
  workaround was attempted.

- Command: `scripts/e2e-fake-backend.sh`
  Result: SKIPPED
  Evidence: it needs the same unavailable Gradle/device lane.

- Command: `scripts/e2e-real-backend.sh`
  Result: SKIPPED
  Evidence: it needs the same unavailable Gradle/device lane. When it becomes
  available, accept only the documented fake-only `[partial-failure]` bucket-C
  discrepancy; investigate any other failure.

## Manual verification still required

Once Gradle cache access is available, install/run the current branch and inspect:

1. Schedule starts collapsed; one-tap quick choices and each Custom dialog preserve
   draft values until Apply, with Cancel discarding the pending value.
2. Personal locked `Me`; shared Me/Wife selection; Wife `No schedule`; self schedule
   validation; cross-midnight summary.
3. Project/Assignee stay local until Save; Add/Edit Back and Cancel prompt only for a
   dirty draft; retry preserves the request/draft.
4. Repair and blocker reschedule still use legacy combined Start/Duration pickers.

Then run, from the repository root with the documented Android SDK environment:

```text
scripts/e2e-fake-backend.sh
scripts/e2e-real-backend.sh
```

Record their exact outcomes here, update Task 10 checkboxes, and only then consider
the redesign fully verified for merge.

## Assumptions

- No time-zone selector was added; a device zone remains internal to scheduled
  recurrence serialization.
- The documented real-backend `[partial-failure]` difference remains intentional.

---

## Add-editor instrumentation regression follow-up (2026-07-22)

### Scope / plan status

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`, Task 10 Android
  verification follow-up.
- Fixed the Add-only Compose and instrumentation regressions discovered when the
  managed-device lane became available. The wider Task 10 full-suite status remains
  owned by the coordinating agent.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
  - Applies the supplied title modifier to `BasicTextField`, not its wrapper, so the
    tagged title node can receive text input/focus actions.
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorWheelDialogs.kt`
  - Gives each wheel dialog's visible Apply/Cancel action a distinct semantic content
    description; the user-visible labels remain unchanged.
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
  - Clears the active editor focus before displaying a dirty-draft discard dialog, so
    the IME cannot obscure that confirmation.
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
  - Uses the redesigned control semantics, expects the valid default `Me` selection
    after a shared-project change, and verifies accordion collapse without rejecting
    the intended Notes preview.

### TDD / diagnosis evidence

- Initial focused Add managed-device run was RED: eight of eleven tests failed. The
  title node lacked `RequestFocus`; each custom dialog had two visible Cancel labels;
  and the old Assignee helper looked for `Choose assignee` even though a valid shared
  project selects `Me` by default.
- The strengthened dirty-exit reproduction first proved that the edited title was in
  the field. A temporary count-only diagnostic then recorded
  `ConfirmDiscard`, title lengths `0 -> 13`, and no project change; the confirmation
  was simply hidden while the title field/IME retained focus. The diagnostic was
  removed before the final code.

### Verification

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_scheduleUsesQuickChoicesAndSeparateCustomDialogs scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: one custom-wheel test passed after dialog-specific semantics were added.

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_dirtyCancelAndDeviceBackRequireDiscardConfirmation scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: the dirty Cancel and system-Back confirmation path passed after
  `LocalFocusManager.clearFocus(force = true)`.

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.AddTaskFakeBackendTest scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: all 11 Add fake-backend managed-device tests passed on `pixel8Api34`.

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: PASS
- Evidence: focused JVM draft-exit suite completed successfully.

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: debug APK assembled successfully.

### Manual verification

- The managed-device Add class exercised title entry, custom wheel Cancel/Apply,
  project/assignee sheets, optional schedule state, accordion behavior, save/retry,
  and dirty Cancel/system Back. No separate physical-device pass was performed in
  this follow-up.

### Next recommended step

- Continue the plan's remaining full Android fake/real E2E and device verification
  gates from the coordinating task.

---

## Edit-editor fixture and dirty-back regression follow-up (2026-07-22)

### Scope / plan status

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`, Task 10 Android
  verification follow-up.
- Repaired the time-rotted Edit fake-backend fixture and the Edit screen's missing
  system-Back handling. The temporary semantics diagnostic used during investigation was
  removed before this handoff.

### Files changed

- `tools/fake-backend/src/fixtures.ts`
  - Anchors `task-one-time-empty` at `legacyIsoAt(-31)`, leaving its scheduled,
    self-assigned, 30-minute commitment valid while making it an on-screen overdue row
    in Home's today agenda.
- `tools/fake-backend/src/server.test.ts`
  - Derives the expected daily recurrence time from the pre-update fixture schedule in
    `Europe/Warsaw`, rather than pinning the old static fixture hour.
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
  - Mirrors Add's dirty-exit behavior: clears focus before discard confirmation and
    handles system Back by closing that dialog or requesting a dirty exit.

### TDD / diagnosis evidence

- The original full Edit managed-device class was RED 2/2 before any repair: its source
  title never appeared because the fixture was statically dated 2026-06-01 while Home
  filters its agenda using device date (2026-07-22).
- A temporary semantics dump after a first `legacyIsoAt(120)` repair showed eight today
  agenda rows but the future target was uncomposed below the initial lazy-list viewport.
  `legacyIsoAt(-31)` makes it a visible overdue row while retaining the scheduled-self
  contract.
- With that fixture repair, the first Edit test became green and the second exposed the
  real system-Back regression: Edit had no Compose `BackHandler`, so Espresso reported
  `NoActivityResumedException`. The symmetric handler repair was then verified by the
  same existing E2E test.

### Verification

Verification:

- Command: `PORT=18437 ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.EditTaskFakeBackendTest scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: `pixel8Api34` completed `EditTaskFakeBackendTest` with `OK (2 tests)`,
  including project/assignee draft save, retry request identity, dirty Cancel, and dirty
  system-Back confirmation.

Verification:

- Command: `npm test` from `tools/fake-backend/`
- Result: PASS
- Evidence: 19 tests passed; the recurrence fixture assertion derives its expected time
  from `before.dueDatetime`.

### Manual verification

- No separate physical-device pass. The managed-device suite covered both sticky Cancel
  and system Back through the dirty-edit confirmation flow.

### Next recommended step

- Continue the coordinating task's remaining broad fake/real Android E2E and device
  verification gates.

---

## Real E2E Edit fixture and debug-call parity follow-up (2026-07-22)

### Scope / plan status

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`, Task 10 Android
  real-E2E verification follow-up.
- Repaired the real E2E fixture's offscreen Edit target and added the real test server's
  full-mutation debug surface required by the existing Edit instrumentation contract.

### Files changed

- `backend/test/fakes/e2eFixtures.ts`
  - Anchors `task-one-time-empty` at `iso(-31)`, making it a visible same-day overdue
    Home row while retaining its scheduled, self-assigned, 30-minute semantics.
- `backend/test/e2eServer.ts`
  - Retains a structured clone of each mutation payload in a separate full-call log;
    `/__calls` remains metadata-only and `/__calls/full` exposes the full request shape.
    Both logs clear on `/__reset`.
- `backend/test/e2eServer.test.ts`
  - Proves that the metadata view omits a task title while the full view contains that
    request payload, and confirms reset clears the full view too.

### TDD / diagnosis evidence

- The existing real Android Edit class was initially RED because the real fixture placed
  `task-one-time-empty` at `iso(120)`, below the initial lazy-list viewport. Moving it
  to `iso(-31)` let both flows reach their edit mutations.
- That then exposed the test-debug parity gap: `EditTaskFakeBackendTest` requests
  `/__calls/full` to compare full retry root patches, but the real E2E server only
  offered the intentionally metadata-only `/__calls`. The new backend test first failed
  because the full endpoint was absent, then passed after the isolated full-payload log
  was implemented.

### Verification

Verification:

- Command: `npm test -- test/fakes/e2eFixtures.test.ts` from `backend/`
- Result: PASS
- Evidence: 10 focused real-fixture tests passed.

Verification:

- Command: `npm test -- test/e2eServer.test.ts` from `backend/`
- Result: PASS
- Evidence: 20 real E2E-server tests passed, including metadata/full call separation
  and reset clearing.

Verification:

- Command: `PORT=18441 ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_TEST_CLASS=app.buzer.android.addtask.EditTaskFakeBackendTest scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: `pixel8Api34` completed the two Edit instrumentation tests with `OK (2
  tests)`, including full root-patch retry identity and dirty system-Back confirmation.

### Verification boundary

- Per the user, the TypeScript `tools/fake-backend` lane is deprecated. Do not modify or
  invoke it; it is not a verification gate for this redesign. Real E2E backend coverage
  is the authoritative lane.
- When the coordinating task reruns the complete real lane, the only accepted remaining
  failure is the documented fake-only `addTask_partialChildFailure...` bucket-C
  discrepancy. Investigate every other failure.

### Next recommended step

- Run the complete `scripts/e2e-real-backend.sh` lane and retain only the documented
  partial-child discrepancy, then complete the coordinating Task 10 verification.

---

## Final automated verification and connected-device install (2026-07-22)

### Scope / plan status

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`, Task 10.
- Automated verification is complete. The authoritative real-backend lane has exactly
  the single documented fake-only partial-child discrepancy; all redesigned-editor
  coverage passed.
- Per user direction, `tools/fake-backend` is deprecated. Do not invoke, modify, or
  count it as a verification gate. The real local E2E backend is authoritative.

### Verification

Verification:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` from `client/`
- Result: PASS
- Evidence: shared host tests, Android JVM tests, and debug APK assembly completed
  successfully.

Verification:

- Command: `npm test` and `npm run typecheck` from `backend/`
- Result: PASS
- Evidence: backend test suite passed (386 tests); TypeScript typecheck passed.

Verification:

- Command: `npm test -- test/fakes/e2eFixtures.test.ts` and `npm test -- test/e2eServer.test.ts` from `backend/`
- Result: PASS
- Evidence: real-fixture tests passed 10/10 and real E2E-server tests passed 20/20.

Verification:

- Command: `PORT=18442 scripts/e2e-real-backend.sh`
- Result: CLASSIFIED PASS
- Evidence: 31/32 managed-device tests passed. The sole failure was the explicitly
  accepted fake-only `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`
  discrepancy; all Add/Edit redesign and real-backend scenario tests passed.

Verification:

- Command: `android run --debug --device=56061FDCR005J0 --apks=/Users/svehla/playground/buzer-app/.worktrees/task-editor-redesign/client/androidApp/build/outputs/apk/debug/androidApp-debug.apk --activity=app.buzer.android.MainActivity --type=ACTIVITY`
- Result: PASS
- Evidence: verified debug APK installed and launched on connected Pixel 10
  (`56061FDCR005J0`).

### Manual verification status

- Blocked only by the connected Pixel 10 lock screen. Unlock it, then inspect the
  Schedule quick choices/custom dialogs, draft-only Project and Assignee selection,
  and the legacy repair/reschedule picker before marking Task 10 fully complete.
