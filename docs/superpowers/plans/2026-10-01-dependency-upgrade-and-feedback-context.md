# Dependency Upgrade And Feedback Context Plan

Date: 2026-10-01
Branch: `feature/deps-and-feedback-context` (worktree `.worktrees/deps-and-feedback-context`)

## Goal

1. Make shake-to-report feedback self-describing: every feedback event carries the Main
   screen section (`screen`) and, on the task editor, the edited `task_id`.
2. Upgrade every client and backend dependency to its latest stable version, which also
   brings the Sentry Android feedback form's "Add screenshot" button (SDK 8.54.0+).

Origin: the 2026-09-30 feedback reports BUZER-ANDROID-G/H/J could not be tied to a screen
or task. Sentry feedback events copy scope tags but not breadcrumbs or scope attachments
(`SentryClient.captureFeedback` / `applyFeedbackScope`, sentry-java 8.58.0), and the replay
masks all text by default. Structured logs already share the feedback's `trace_id`.

## Decisions

- Screenshots: the owner chose the SDK picker only (2026-10-01). The 8.54.0 button opens the
  androidx photo picker; there is no automatic capture and no custom capture code. This keeps
  the 2026-08-22 lean observability decision unchanged.
- Screen context uses only the sanctioned correlation tags `screen` and `task_id` on the
  Sentry scope. Tags are set while `MainActivity` is started and cleared on `ON_STOP`, so a
  blocker activity on top does not inherit stale Main tags.
- Stable-only policy from `docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md`
  still applies: no alpha/beta/rc/dev/compat-line artifacts, exact pins.
- Backend runtime stays Node 22 (`Dockerfile`, CI, `engines`). `@types/node` stays on the
  22.x line. Moving the runtime to Node 24 is a separate decision.
- Sentry JavaScript SDK 11 widens default data collection. The backend keeps the v10 privacy
  baseline with an explicit restrictive `dataCollection` and `attachStacktrace: false`.

## Target Versions

### Client (`client/gradle/libs.versions.toml`, wrapper, settings)

| Dependency | Current | Target |
|---|---:|---:|
| Gradle wrapper | 9.5.1 | 9.8.0 |
| Android Gradle Plugin | 9.2.1 | 9.4.1 |
| Kotlin | 2.4.0 | 2.4.20 |
| Compose Multiplatform | 1.11.1 | 1.12.1 |
| AndroidX Compose UI test | 1.11.2 | 1.12.1 |
| Lifecycle runtime KTX | 2.10.0 | 2.11.0 |
| Ktor | 3.5.0 | 3.6.0 |
| Kermit | 2.1.0 | 2.2.0 |
| Sentry Android | 8.53.0 | 8.58.0 |
| Sentry Android Gradle plugin | 6.19.0 | 6.23.0 |
| foojay resolver | 0.10.0 | 1.0.0 |

Already latest stable: Activity Compose 1.13.0, CMP Material3 1.9.0, kotlinx-serialization
1.11.0, kotlinx-datetime 0.8.0 (0.8.0-0.6.x-compat excluded), AndroidX Test/Espresso,
JUnit 4.13.2, WheelPickerCompose 1.1.11.

### Backend (`backend/package.json`)

| Dependency | Current | Target |
|---|---:|---:|
| `@fastify/rate-limit` | 10.3.0 | 11.2.0 |
| `@fastify/sensible` | 6.0.4 | 6.0.6 |
| `@sentry/node` | 10.72.0 | 11.1.0 |
| `better-sqlite3` | 12.10.0 | 13.0.3 |
| `dotenv` | 17.4.2 | 18.0.5 |
| `fastify` | 5.8.5 | 5.12.5 |
| `zod` | 4.4.3 | 4.6.5 |
| `@sentry/cli` | 3.6.2 | 3.8.0 |
| `@types/better-sqlite3` | 7.6.13 | 9.6.0 |
| `@types/node` | 22.19.20 | 22.20.4 (22.x line) |
| `tsx` | 4.22.4 | 4.23.15 |
| `typescript` | 6.0.3 | 7.0.2 |
| `vitest` | 4.1.8 | 5.0.3 |

Already latest: `whatsapp-web.js` 1.34.7, `qrcode-terminal` 0.12.0. All targets support
Node 22 (`@sentry/node` 11 needs >= 22.12; `better-sqlite3` 13 needs >= 22).

### CI (`.github/workflows/ci.yml`)

`actions/checkout` v7, `actions/setup-java` v6, `actions/setup-node` v7,
`gradle/actions/setup-gradle` v6, `actions/upload-artifact` v7.

## Existing Tests That Assert Changed Behavior

- `client/androidApp/src/test/.../observability/AndroidObservabilityConfigTest.kt` — feedback
  options; extend with the screenshot-button default.
- `client/androidApp/src/test/.../observability/SentryAndroidObservabilityTest.kt` — Sentry
  adapter; extend with scope tag application.
- `client/androidApp/src/androidTest/.../observability/BugReportFlowInstrumentationTest.kt` —
  MainActivity feedback flow; extend with screen context on Home and Add.
- `client/androidApp/src/main/.../observability/AndroidObservability.kt`
  (`RecordingAndroidObservability`) — test double used by instrumentation tests.
- `backend/test/backendObservability.test.ts:341-342` — asserts `sendDefaultPii === false` and
  `enableLogs === true`; migrate to the v11 options.
- Whole suites for the dependency bump: `:shared:testAndroidHostTest`,
  `:androidApp:testDebugUnitTest`, `scripts/e2e-real-backend.sh` (full), backend `npm test`.

## Tasks

### Task 1: Feedback screen context

- [ ] Add `ScreenContext(screen, taskId)` and `AndroidObservability.setScreenContext(ScreenContext?)`
      (NoOp, Recording, Sentry). Sentry applies/removes `screen` and `task_id` scope tags.
- [ ] Write failing JVM tests: section-to-context mapping; scope tag apply/clear on a real
      `io.sentry.Scope`; Recording double records the latest context.
- [ ] Wire `BuzerApp`: lifecycle-aware effect sets context on start/section change, clears on
      stop/dispose; `MainActivity` forwards to `ObservabilityRuntime.instance`.
- [ ] Log `ui navigate section=Edit source=home` with `task_id` (Add already logs its navigation).
- [ ] Extend `BugReportFlowInstrumentationTest` (Home, then Add).
- [ ] JVM tests green; commit.

### Task 2: Client dependency upgrade

- [ ] Gradle wrapper 9.8.0; build green; commit.
- [ ] AGP 9.4.1, Kotlin 2.4.20, CMP 1.12.1, Compose UI test 1.12.1, Lifecycle 2.11.0, Ktor 3.6.0,
      Kermit 2.2.0, Sentry 8.58.0, Sentry Gradle plugin 6.23.0, foojay 1.0.0; fix breakages
      (compileSdk, API deprecations) using Context7 docs; JVM tests + `assembleDebug` +
      `assembleDebugAndroidTest` green; commit.
- [ ] Assert `feedbackOptions.isEnableAttachScreenshot` in `AndroidObservabilityConfigTest`.

### Task 3: Backend dependency upgrade

- [ ] Pin targets, `npm install`, refresh transitive deps within range, `npm audit` review.
- [ ] Sentry 11 migration: remove `enableLogs`, replace `sendDefaultPii` with the restrictive
      `dataCollection` baseline, set `attachStacktrace: false`; check whether the default
      integrations auto-capture Fastify errors (would duplicate `onError` capture).
- [ ] Fix TypeScript 7 / Vitest 5 / other breakages; migrate `backendObservability.test.ts`.
- [ ] `npm run typecheck`, `npm test`, `npm run build` green; commit.

### Task 4: CI actions

- [ ] Bump action majors in `ci.yml`; commit.

### Task 5: Verification and handoff

- [ ] Full `scripts/e2e-real-backend.sh` run (no class filter).
- [ ] Push branch; `gh run list --workflow=ci.yml --limit 1` green (CI runs Node 22, JDK 17).
- [ ] Handoff `docs/handoffs/2026-10-01-dependency-upgrade-and-feedback-context.md`.

## Out Of Scope

- Automatic screenshot capture, log attachments, replay unmasking.
- Node runtime / Docker base image changes; Docker build (Docker not installed locally).
- VPS deploy and phone install (owner-run after merge).
