# CI Workflows And Token-Note Update Design

Date: 2026-07-04
Status: approved design, pending implementation plan

## Context And Goal

The repo has no CI: every test suite runs only when an agent or human remembers to run it
locally. The 2026-07-04 verification retrospective identified this as the highest-leverage
automation gap. This design adds a two-tier GitHub Actions setup and one documentation update.

Decisions already made during brainstorming:

- CI is the primary safety net; agent-driven sessions remain only for real-device work.
- The emulator tier runs nightly + on-demand only (not per push) to conserve minutes.
- Explicit window-insets testing is dropped entirely (the inset bug class is closed by the
  shared `setBuzerContent` entry point; no regression suite wanted).
- Out of scope for this pass: picker-flow Compose instrumentation tests, a
  `manual-verify.sh` device wrapper, Maestro, Roborazzi or any screenshot testing,
  branch-protection/PR-required rules, and deploy workflows.

Budget: the repo is **private**, so GitHub's free tier applies: 2,000 Linux runner
minutes/month. Projected usage is ~5 min per push plus ≤15 min per nightly emulator run,
well under the cap. The nightly run skips itself when no commits landed, so idle periods
cost ~0 minutes.

## Workflow 1: `.github/workflows/ci.yml` (tier 1 — no device)

Trigger: `push` to any branch. No `pull_request` trigger for now (solo workflow, local
merges; avoids double runs — can be added later if PR flow is adopted).

Concurrency: group per ref with `cancel-in-progress: true`, so rapid consecutive pushes
don't stack queued runs.

Two parallel jobs, both on `ubuntu-latest`:

**Job `client`** (timeout 30 min):
1. Checkout.
2. Set up Temurin JDK 17 (matches the project's `jvmToolchain(17)`).
3. `gradle/actions/setup-gradle` for Gradle caching.
4. `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
5. On failure: upload `client/**/build/reports/tests` as an artifact.

Notes: the Android SDK is preinstalled on `ubuntu-latest` with `ANDROID_HOME` set, so no
`local.properties` is needed; AGP downloads any missing platform/build-tools itself. The
`BUZER_BACKEND_*` build-config values default to `""` when the Gradle properties are absent
(`backendConfigValue`), which compiles fine — the runtime "local configuration error" path
is irrelevant to CI.

**Job `backend`** (timeout 15 min):
1. Checkout.
2. Set up Node 22 (matches `engines` in both `package.json` files) with npm caching keyed
   on both lockfiles.
3. In `backend/`: `npm ci`, `npm test`, `npm run typecheck`, `npm run build`.
4. In `tools/fake-backend/`: `npm ci`, `npm test`.

No secrets are configured or used anywhere. CI has no access to Todoist, WhatsApp,
Tailscale, or the VPS, and never needs it; the only token involved is the fake backend's
hardcoded `test-token`.

## Workflow 2: `.github/workflows/emulator-e2e.yml` (tier 2 — headless emulator)

Triggers: `schedule` (cron `30 2 * * *` UTC) and `workflow_dispatch`.

Single job on `ubuntu-latest`, timeout 45 min:

1. Checkout.
2. **Skip guard** (first step): on `schedule` events, compare `HEAD` commit timestamp to
   now; if older than 25 hours, set a step output `run=false`. Every subsequent step is
   conditioned on `run == 'true'` (dispatch runs always set `run=true`). The job itself
   ends green when skipped.
3. Enable KVM: the standard one-step udev rule
   (`KERNEL=="kvm", GROUP="kvm", MODE="0666"` + udevadm reload/trigger).
4. Temurin JDK 17 + `gradle/actions/setup-gradle`.
5. Node 22 + `npm ci` in `tools/fake-backend/` (the e2e script starts the server but does
   not install dependencies).
6. Run `scripts/e2e-fake-backend.sh` — the identical lane used locally: it starts the fake
   backend on 18433 and runs `pixel8Api34DebugAndroidTest` via the already-configured
   Gradle Managed Device.
7. On failure: upload the managed-device test report and result directories under
   `client/androidApp/build/` as artifacts (exact GMD output paths pinned during
   implementation).

Contingencies accepted in this design:

- The AOSP system image downloads on each run (it lives in the SDK dir, outside the Gradle
  cache); ~1–2 min. Image caching can be added later if it becomes annoying.
- If the managed-device boot fails headless on CI, add the documented GMD GPU flag
  (`-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`) to the script
  invocation for CI. This is a known, one-line fix; the dispatch verification run will
  reveal whether it is needed.

## Documentation Update: Token-Efficiency Note

Amend `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`:

- In the UI-hierarchy guidance (section 2), replace the "`uiautomator dump` as last
  resort" framing with the `android` CLI workflow that superseded it in practice:
  `android layout --device <serial> -p` (flat JSON, `--diff` after actions),
  `android screen capture -a` for annotated screenshots when layout misses elements, and
  `android screen resolve` to turn annotations into tap coordinates. Keep `uiautomator
  dump` only as the fallback when the `android` CLI is unavailable.
- Cross-reference stays intact: `scripts/adb-verify.sh` remains the tool for
  logs/dumpsys/alarm checks; the `android` CLI addition is specifically for on-screen
  navigation.

## Verification

- Both workflow files lint clean (run `actionlint` locally if installed; otherwise rely on
  GitHub's parser on first push).
- Push to a branch: `ci.yml` runs and both jobs pass.
- Manually dispatch `emulator-e2e.yml` from the Actions tab (or `gh workflow run` once
  `gh` is authenticated): job passes end-to-end, including emulator boot under KVM.
- The schedule-skip guard is verified by code review plus observing one scheduled run
  after an idle day (it cannot be exercised via dispatch).

## Error Handling

- `timeout-minutes` on every job caps runaway minute burn (hung emulator, wedged Gradle).
- `concurrency` with cancel-in-progress on `ci.yml` prevents queued duplicate runs.
- The e2e script already traps EXIT and kills the fake backend it started.
