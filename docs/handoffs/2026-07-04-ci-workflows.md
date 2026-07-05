# Handoff: CI Workflows, Token-Note Update, And Emulator-Lane Repair

Date: 2026-07-05
Agent: Claude (Sonnet 5 workers under a Fable 5 controller, subagent-driven development)
Plan: `docs/superpowers/plans/2026-07-04-ci-workflows.md`

## Scope

- Execute the CI workflows plan: `ci.yml` (tier-1, push-triggered), `emulator-e2e.yml`
  (tier-2, nightly + manual), the `BUZER_GMD_GPU` hook in `scripts/e2e-fake-backend.sh`,
  and the android-cli update to the token-efficiency note.
- Extended scope (owner-approved mid-session): the first emulator CI runs exposed that the
  instrumentation suite had silently rotted for ~3 weeks (no CI existed to catch it), plus
  CI-environment flakiness. Both were fixed to get the lane green.

## Completed

Plan commits (Tasks 1-4, each spec-reviewed and quality-reviewed):
- `898dc38` ci: add push-triggered unit test and build workflow
- `aebfc59` test: allow forcing managed-device GPU mode via env
- `77d34f4` ci: add nightly and manual emulator e2e workflow
- `354f932` docs: prefer android-cli layout workflow for UI navigation

Emulator-lane repair commits (extended scope):
- `66a7825` test: migrate instrumentation tests to wheel picker dialogs
- `239b867` fix: show add task system failures in dialog only (app change, owner-approved)
- `eec4732` test: align blocker tests with loading-error behavior
- `b8fb796` test: wait for home before opening add task on relaunch
- `9befcc3` test: wait for async ui before instrumentation interactions
- `933cf4c` test: retry fake backend control calls through network blips

Both branches (`feature/ci-workflows`, `fix/wheel-picker-instrumentation-tests`) were
fast-forward merged to `master`, pushed, and deleted (local and remote).

## Verification

- Command: `ci.yml` run on branch push (run `28741572063`)
- Result: PASS
- Evidence: both jobs (`client`, `backend`) green on the first-ever CI run.

- Command: `ci.yml` runs on master pushes (latest run `28749094584`)
- Result: PASS
- Evidence: conclusion `success` on every master push through `933cf4c`.

- Command: `gh workflow run emulator-e2e.yml` (four dispatches)
- Result: FAIL, FAIL, FAIL, then PASS
- Evidence:
  - `28742472609` FAIL — 7+ tests: stale instrumentation suite (see below).
  - `28746369293` FAIL — 1 test: async-UI race (touch inject before node composed).
  - `28747011903` FAIL — 2 different tests: same race class at other boundaries.
  - `28748612364` FAIL — 4 tests in one 10-second burst: emulator guest network dropped;
    `ConnectException` from the tests' fake-backend control calls; backend healthy before/after.
  - `28749094720` PASS — all fixes aboard; KVM, swiftshader GMD boot, fake backend, 21/21.

- Command: `scripts/e2e-fake-backend.sh` (local, after each fix batch)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`, `Finished 21 tests on pixel8Api34`, 0 failed (final run 1m57s).

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` after the AddTaskScreen change.

- Task-level plan verifications (Tasks 1-4): actionlint not installed (skip path taken, both
  workflows); `bash -n` → `SYNTAX_OK`; GPU-arg expansion isolation checks produced
  `[-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect]` and `[]`;
  `rg -c "android layout"` → 3; both workflow files verified byte-identical to the plan by
  independent spec reviewers; the GMD property name and `%ct`-on-shallow-clone guard semantics
  verified by quality reviewers.

## Decisions And Assumptions

- `BUZER_GMD_GPU=swiftshader_indirect` is forced on CI from the start (the spec's contingency,
  adopted proactively). Local runs are unaffected (variable unset → flag omitted).
- No `pull_request` trigger by design; nightly guard skips scheduled runs when HEAD is older
  than 25 hours.
- The emulator-lane failures split three ways: (1) genuinely stale tests — the wheel-picker
  migration (single-dialog deadline, button-based duration defaulting to 30) and the
  loading/error rework (repair failure keeps the activity open; Home renders the raw error
  message) had never been reflected in the suite; (2) CI timing races — fixed by
  wait-before-interact everywhere plus 5s→15s wait-helper timeouts; (3) a transient emulator
  guest-network drop — fixed by a 20s idempotent retry window in `FakeBackendClient`.
- One deliberate app change: Add Task system failures now surface in the dialog only
  (`AddTaskScreen.kt` no longer duplicates the message into inline text); inline text remains
  the surface for form-validation errors, matching the product backlog's stated design.
- Duration-value assertions use the default 30 rather than driving wheel scrolls in tests
  (scroll-selection is flaky on CI; value logic is covered by JVM unit tests and was manually
  verified on-device on 2026-07-04).
- `gh` was installed via Homebrew and authenticated by the owner during this session.

## Open Issues

- The nightly schedule-skip guard has not been observed on a real scheduled run yet (dispatch
  runs always execute); verifiable after one idle night.
- The AOSP system image re-downloads on each emulator CI run (~1-2 min, accepted per spec).
- Reviewer polish notes, all non-blocking: an inline comment in `scripts/e2e-fake-backend.sh`
  explaining why the `${VAR:+"${VAR}"}` form must not be simplified; the token-note's
  "carried ... end to end" phrasing is slightly stronger than the recorded handoff evidence;
  `AddTaskProjectLoadFeedback.message()`'s error branch is now dead code from its only caller;
  a latent (currently unreachable) dialog-dismiss path could disable Save without visible
  explanation if project auto-refresh is ever added.
- CI emulator flakiness may still surface at app-network boundaries the retry window doesn't
  cover; if a nightly fails with a single odd test, check for the `ConnectException` signature
  before assuming a regression.

## Next Step

- Observe the first scheduled nightly run (and one idle-day skip). Then return to the parked
  follow-ups: picker-flow-specific Compose assertions if desired, the `manual-verify.sh`
  device wrapper, and the product backlog (Home in-progress polish, real-device reliability
  sweep).
