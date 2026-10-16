# Handoff: Dependency Upgrade And Feedback Screen Context

Date: 2026-10-01, follow-up 2026-10-16
Branch: `feature/deps-and-feedback-context` (worktree `.worktrees/deps-and-feedback-context`), pushed, not merged
Plan: `docs/superpowers/plans/2026-10-01-dependency-upgrade-and-feedback-context.md`

## Follow-up 2026-10-16: Audit Fix, Phone Install, Backend Deploy

Owner request: fix the npm audit finding, install on the Pixel 10, deploy the backend; the owner
tests next.

- `11f4e37` fix: override whatsapp-web.js's puppeteer to 25 to clear npm audit. By 2026-10-16
  the audit also flagged `basic-ftp` (via `proxy-agent`); `extract-zip` has no patched release.
  puppeteer 25 drops both. Details: `docs/notes/2026-06-08-remaining-dependency-warnings.md`.
- `ae5daee` fix: install backend image dependencies without lifecycle scripts. The first VPS
  deploy failed in the image build: npm runs an implicit `node-gyp rebuild` for better-sqlite3
  13 on a clean install (npm 10 and 11 both did, reproduced locally), and node-gyp needs Python,
  which `node:22-bookworm-slim` lacks. The library loads its bundled prebuild regardless. New
  `backend/test/dockerImage.test.ts` pins the flag and its assumptions.
- The Pixel 10 build used a copy of the primary checkout's gitignored `client/local.properties`
  in this worktree.

Verification:
- Command: `npm audit` (backend)
- Result: PASS
- Evidence: `found 0 vulnerabilities`; tree has `puppeteer@25.14.0 overridden`, `@puppeteer/browsers@3.2.5`.
- Command: temporary `backend/wwebjs-puppeteer25-smoke.cjs` (deleted): headless whatsapp-web.js `Client` with a throwaway `LocalAuth` dir
- Result: PASS
- Evidence: reached the `qr` event on Node 26.11.1 and Node 24.14.0.
- Command: backend `npm run typecheck`, `npm test`, `npm run build`
- Result: PASS
- Evidence: typecheck exit 0; 40 files, 511 tests; build exit 0.
- Command: clean-directory replay of the image build stage (`npm ci --ignore-scripts`, `npm run build`, `npm prune --omit=dev`)
- Result: PASS
- Evidence: no `better-sqlite3/build`; SQLite 3.53.4 loads from the prebuild; whatsapp-web.js loads.
- Command: `gh run list --workflow=ci.yml` for `11f4e37`
- Result: PASS
- Evidence: run 38859341163, client success, backend success (Node 22).
- Command: `ANDROID_SERIAL=56061FDCR005J0 ./gradlew :androidApp:installDebug`
- Result: PASS
- Evidence: `Installed on 1 device` (Pixel 10, Android 17); BuildConfig: production backend URL,
  `production-device`, release `app.buzer@0.1.0-dev+11f4e37603ea`, token and DSN non-empty.
- Command: `ssh -A oc-vps.taila50381.ts.net 'BUZER_DEPLOY_BRANCH=feature/deps-and-feedback-context /home/skclaw/buzer-app/scripts/deploy-vps.sh'` (first attempt, `11f4e37`)
- Result: FAIL
- Evidence: image build `npm ci` failed in `node-gyp rebuild` for better-sqlite3 ("Could not
  find any Python installation"); the old container (`3150fc1`) kept running, healthy, WhatsApp ready.
- Command: same deploy for `ae5daee`
- Result: PASS
- Evidence: `RUN npm ci --ignore-scripts`; container recreated; healthy on the first check;
  source-map upload skipped (`SENTRY_AUTH_TOKEN` not set on the VPS).
- Command: VPS read-only checks (`printenv SENTRY_RELEASE`, package versions, authenticated `/health`, `/tasks/snapshot`, logs)
- Result: PASS
- Evidence: release `app.buzer@0.1.0+ae5daee8246b`; Node v22.23.3, puppeteer 25.14.0,
  better-sqlite3 13.0.3, @sentry/node 11.1.0; `database: ok`, WhatsApp `connected: true,
  detail: ready` (session restored, no QR); snapshot 200 with 239 tasks, 0 offset-less times,
  0 health warnings; no warn/error log lines in the first 10 minutes.
- Command: `gh run list --workflow=ci.yml` for `ae5daee`
- Result: PASS
- Evidence: run 38860416905, client success, backend success.
- Command: `adb -s 56061FDCR005J0 shell dumpsys package app.buzer.android`
- Result: PASS
- Evidence: `lastUpdateTime=2026-10-16 08:07:33`, `firstInstallTime` unchanged (data kept); no
  `client/` changes between the installed build (`11f4e37`) and `ae5daee`.

State left behind: the VPS checkout is on `feature/deps-and-feedback-context` at `ae5daee`. The
next normal `deploy-vps.sh` run (default branch `master`) switches it back.

Next: the owner tests on the Pixel 10 (shake on the task editor -> Sentry feedback should carry
`screen=Edit` and `task_id`; the form has an "Add screenshot" button). Then merge to `master`,
push, and redeploy from `master` so the VPS tracks `master` again.

## Task / Plan Status

Owner request (2026-09-30/10-01): make shake-to-report feedback self-describing, and upgrade all
client and backend dependencies to latest stable. Plan Tasks 1-4 are done; Task 5 is done except
the push + CI check, which waits for the owner (pushing was not requested).

- [x] Task 1: feedback screen context (`screen` / `task_id` scope tags)
- [x] Task 2: client dependency upgrade (Ktor held at 3.5.0, see below)
- [x] Task 3: backend dependency upgrade + Sentry 11 migration
- [x] Task 4: CI action bumps
- [x] Task 5: full e2e run, handoff
- [ ] Task 5: push branch and check `ci.yml` (owner decision)

Context from the investigation that led here (Sentry, 2026-09-30): feedback BUZER-ANDROID-G/H/J
could not be tied to a screen or task. Sentry feedback events copy scope tags and contexts but not
breadcrumbs or scope attachments (`SentryClient.captureFeedback` / `applyFeedbackScope`,
sentry-java 8.58.0). The attached replay masks all text by default. Structured logs share the
feedback's `trace_id`, so `trace:<id>` in Sentry Logs already finds the logs before a report.

## Commits

- `87787b4` docs: plan dependency upgrade and feedback screen context
- `cbd4d1e` feat: tag Sentry feedback with the Main screen and edited task
- `e28d2cf` build: upgrade backend dependencies to latest stable
- `41f0999` ci: bump GitHub Actions to current majors
- `2cd96bb` build: upgrade Gradle wrapper to 9.8.0
- `21bbaa6` build: upgrade client dependencies to latest stable
- `b64db17` fix: hold Ktor at 3.5.0 to avoid closing responses on the main thread
- this handoff commit

## Files Changed

- Feedback context: `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt`,
  `.../observability/SentryAndroidObservability.kt`, `.../ui/App.kt`, `.../MainActivity.kt`;
  tests `AppScreenContextTest.kt` (new), `SentryAndroidObservabilityTest.kt`,
  `AndroidObservabilityConfigTest.kt`, `BugReportFlowInstrumentationTest.kt`.
- Client build: `client/gradle/libs.versions.toml`, `client/settings.gradle.kts`,
  `client/androidApp/build.gradle.kts`, `client/shared/build.gradle.kts`, Gradle wrapper files.
- Backend: `backend/package.json`, `backend/package-lock.json`,
  `backend/src/diagnostics/backendObservability.ts`, `backend/test/backendObservability.test.ts`.
- CI: `.github/workflows/ci.yml`.
- Docs: plan, this handoff, `docs/notes/2026-10-01-ktor-cancel-close-on-main-thread.md` (new),
  `docs/notes/2026-06-08-remaining-dependency-warnings.md`.

## What Changed

Feedback screen context:
- `AndroidObservability.setScreenContext(ScreenContext?)`; the Sentry adapter sets `screen`
  (`Home` / `Add` / `Edit`) and `task_id` (Edit only) scope tags, or removes them on `null`.
- `BuzerApp` uses `LifecycleStartEffect`: sets the context while `MainActivity` is started and on
  section changes, clears it on stop/dispose, so a blocker activity on top does not inherit it.
  `androidx.lifecycle:lifecycle-runtime-compose` is now a direct dependency (was transitive).
- Opening the editor now logs `ui navigate section=Edit source=home` with `task_id`.
- Screenshots: owner chose the SDK picker only. Sentry 8.54+ adds an "Add screenshot" button that
  opens the photo picker (not automatic capture); `AndroidObservabilityConfigTest` pins it on.

Client versions: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose Multiplatform 1.12.1, Compose UI
test 1.12.1, Lifecycle 2.11.0, Kermit 2.2.0, Sentry Android 8.58.0, Sentry Gradle plugin 6.23.0,
foojay 1.0.0. `compileSdk` 36 -> 37 (Compose 1.12 requires it); `targetSdk` stays 35.
Ktor stays 3.5.0: 3.5.1+ closes cancelled Android-engine responses on the cancelling (main)
thread and throws `NetworkOnMainThreadException`. Details and revisit criteria in
`docs/notes/2026-10-01-ktor-cancel-close-on-main-thread.md`.

Backend versions: TypeScript 7.0.2, Vitest 5.0.3, @sentry/node 11.1.0, better-sqlite3 13.0.3,
dotenv 18.0.5, @fastify/rate-limit 11.2.0, fastify 5.12.5, zod 4.6.5, @fastify/sensible 6.0.6,
@sentry/cli 3.8.0, tsx 4.23.15, @types/better-sqlite3 9.6.0, @types/node 22.20.4 (Node 22 line);
transitive deps refreshed with `npm update` and `npm audit fix`.

Sentry 11 migration (keeps v10 behavior):
- `sendDefaultPii: false` -> explicit restrictive `dataCollection` (the guide's v10 baseline).
  Leaving it unset in v11 would collect user info, cookies, bodies and DB query data.
- `enableLogs` removed (logs are captured by usage); `attachStacktrace: false` (v11 default true
  would regroup `captureMessage` issues).
- `fastifyIntegration({ shouldHandleError: () => false })`: v11 enables the Fastify integration
  by default and it captures route errors itself. Verified with a local transport: its 500 event
  won the dedupe and carried no `request_id`, so the `onError` hook's tags were lost. With the
  override, the hook's event is the only one and keeps its tags.

CI: checkout v7, setup-java v6, setup-node v7, setup-gradle v6, upload-artifact v7.

## Verification

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` (baseline, before changes)
- Result: PASS
- Evidence: 524 tests, 0 failures.

- Command: `npm test --prefix backend` (baseline)
- Result: PASS
- Evidence: 39 files, 507 tests.

- Command: `ANDROID_TEST_CLASS=app.buzer.android.observability.BugReportFlowInstrumentationTest scripts/e2e-real-backend.sh` (after Task 1, old deps)
- Result: PASS
- Evidence: 2/2 on pixel8Api34, including the new screen-context test.

- Command: backend `npm run typecheck`, `npm test`, `npm run build` (final deps)
- Result: PASS
- Evidence: tsc 7.0.2 clean; 39 files, 508 tests (507 + 1 new); build exit 0; `better-sqlite3`
  13.0.3 loads (SQLite 3.53.4). Local Node is 26.4.0; Node 22 is covered only by CI.

- Command: temporary script `backend/sentry11-fastify-check.mjs` (deleted) with an in-memory transport
- Result: PASS after fix
- Evidence: before the override, the 500 was captured by `auto.function.fastify` with no
  `request_id`; after it, only the `onError` hook's event, with its tag.

- Command: `scripts/e2e-real-backend.sh` (full, Ktor 3.6.0)
- Result: FAIL
- Evidence: 3/116 failed (`AddTaskFakeBackendTest` x2, `EditTaskFakeBackendTest` x1) with
  `NetworkOnMainThreadException` from Ktor `RawSourceChannel.closeSource` during composition
  disposal. Fixed by holding Ktor at 3.5.0.

- Command: `scripts/e2e-real-backend.sh` (full, Ktor 3.5.0, first run)
- Result: FAIL (flaky)
- Evidence: 1/116, `BlockerActivityFakeBackendTest.automatedReviewStartPreservesDirtyAddDraft`:
  "Activity never becomes requested state [RESUMED] (last ... STOPPED)". It passed in the
  previous full run and twice in isolation, and is a race in the test: resuming MainActivity at
  07:00 launches the automated Morning Review, which can stop MainActivity before
  `moveToState(RESUMED)` observes it.

- Command: `scripts/e2e-real-backend.sh` (full, final state)
- Result: PASS
- Evidence: 116/116 on pixel8Api34, `BUILD SUCCESSFUL in 7m 3s`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --rerun-tasks` (final state)
- Result: PASS
- Evidence: 79 tasks executed; 530 tests (524 + 6 new), 0 failures.

- Command: push + `gh run list --workflow=ci.yml --limit 1`
- Result: SKIPPED
- Evidence: branch not pushed; pushing was not requested.

## Known Failures / Skipped Checks

- Not pushed, so CI (Node 22, JDK 17) has not run. The backend has only been verified on Node 26.
- Docker image build not run (Docker not installed). The runtime stays `node:22-bookworm-slim`.
- `npm audit`: 5 high findings remain, all through `whatsapp-web.js` 1.34.7 (latest) pinning
  `puppeteer` 24.38.0. Production skips the browser download that uses the vulnerable code. See
  `docs/notes/2026-06-08-remaining-dependency-warnings.md`.
- `automatedReviewStartPreservesDirtyAddDraft` is flaky (above); not fixed here.
- `scripts/e2e-real-backend.sh` leaves the tsx e2e server running on port 18433 after it exits
  (killed manually after each run). Offered to the owner as a separate task.
- While the Foreground Readiness gate covers Main, `screen` still reports the section underneath.
- AGP itself triggers a Gradle 9.8 deprecation (`Configuration.setVisible`, removal in Gradle 11);
  not from project scripts.
- The Mac went into maintenance sleep mid-session and stalled downloads; long commands were
  wrapped in `caffeinate -is`.

## Assumptions

- Node 22 remains the backend runtime; `@types/node` stays on 22.x.
- Stable-only policy from the 2026-06-07 dependency design still applies.
- Holding one library (Ktor) below latest is acceptable when the latest breaks the app.

## Next Recommended Step

1. Push `feature/deps-and-feedback-context` and confirm `ci.yml` is green (Node 22 backend run).
2. Merge to `master`, deploy the backend (Sentry 11) and install the new APK.
3. After deploy: shake on the task editor and confirm the Sentry feedback has `screen=Edit` and
   `task_id`; trigger or wait for a backend 5xx and confirm it still carries `request_id`.
