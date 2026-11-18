# Voice Quick Add Implementation Plan

> **For agentic workers:** Use the `buzer-subagent-delivery` skill (or superpowers:executing-plans)
> task by task. Steps use checkbox (`- [ ]`) syntax. Run the AGENTS.md start-of-session routine first.

**Goal:** Repurpose the home-screen widget into a voice quick add: the owner dictates with Gboard
Rambler into `QuickAddActivity`, the backend drafts the task with Gemini (extraction) + Gemini
Decision 1 (project and duration with calibrated probabilities), and the client auto-creates it
after a 5 s undo window when confident, or asks otherwise.

**Spec:** `docs/superpowers/specs/2026-11-18-voice-quick-add-design.md`
**Decision:** `docs/decisions/2026-11-18-voice-quick-add.md`

**Tech stack:** Node.js/TypeScript, Fastify, Zod, Vitest, `fetch`; Kotlin Multiplatform shared
module, Compose, Android instrumentation via `scripts/e2e-real-backend.sh`.

## Global constraints

- Task creation goes only through the existing `POST /tasks` / `AddTaskDependencies.createTaskAction`.
  `POST /quick-add/draft` never writes to Todoist or SQLite.
- No dictated text in any log, Sentry event, breadcrumb, screenshot, view hierarchy or database.
  Reviewers must grep every new log/capture call for `text`, `title`, `utterance`.
- `GEMINI_API_KEY` only via `x-goog-api-key` header; never in URLs, never committed, never in the APK.
- No new SQLite tables or migrations. No changes to existing route request/response shapes.
- Do not start Task 2+ before Task 1's notes file exists: the Decisions API shape is unverified.
- Worktree: `.worktrees/voice-quick-add` on `feature/voice-quick-add` per `docs/notes/agent-workspaces.md`.
  Backend tasks (2–4) and client tasks (5–7) may run in parallel worktrees after Task 1.

## Existing tests that assert affected behaviour

- Widget → `MainActivity`: none (verified 2026-11-18 by grep over `client/**/test*`,
  `client/**/androidTest*`). `EXTRA_OPEN_ADD_TASK` handling stays; `AddTaskFakeBackendTest` must
  stay green unchanged.
- `backend/test/http/routes.test.ts`, `backend/test/e2eServer.test.ts`: new cases only.
- `backend/test/redaction.test.ts`: new cases only.

---

### Task 1: Verify Gemini APIs and pin models (gate)

**Files:** Create `docs/notes/2026-11-18-gemini-quick-add-api-notes.md`.

- [ ] Fetch current docs with `npx ctx7@latest library "Gemini API" "generateContent JSON schema structured output REST"`
      and `"Gemini Decisions API gemini-decision-1 REST request"`; fall back to
      `https://ai.google.dev/gemini-api/docs` and `https://aistudio.google.com/docs/decisions-api`
      (the planning session's network could not reach them).
- [ ] Record: endpoint URLs, auth header, request/response JSON for (a) `generateContent` with
      `responseMimeType: application/json` + schema, (b) Decisions multiple-choice questions with
      per-option probabilities. Note option-key rules, max options, whether context text can be
      combined with the input, and error body shape.
- [ ] Pick `GEMINI_EXTRACT_MODEL` default (newest stable Flash or Flash-Lite with structured
      output, not a `-preview`) and record why.
- [ ] Confirm paid-tier data-use terms and record the link.
- [ ] With a real key on the dev machine (never committed, never pasted in notes), run one `curl`
      for each API using fixture "Call the dentist tomorrow at nine"; paste the **response shape**
      with values redacted to types. Record latency.
- [ ] If the Decisions API cannot do per-option probabilities for multiple choice, stop and write a
      handoff: the spec's confidence design must be revisited with the owner.

Verification: notes file reviewed; no secret in `git diff`.

### Task 2: Backend policy and types

**Files:** Create `backend/src/quickadd/quickAddTypes.ts`, `backend/src/quickadd/quickAddPolicy.ts`;
Test `backend/test/quickadd/quickAddPolicy.test.ts`.

**Interfaces:**
```ts
export const DURATION_BUCKETS = [15, 30, 45, 60, 90, 120, 180, 240] as const;
export interface Extraction { isTask: boolean; title: string; startLocal: string | null;
  startStatus: "explicit" | "missing" | "ambiguous"; statedDurationMinutes: number | null;
  recurrencePreset: RecurrencePreset; }
export interface ScoredOption<K> { key: K; probability: number | null }
export interface Classification { projects: ScoredOption<string>[]; durations: ScoredOption<number>[] }
export interface TaskExtractor { extract(i: { text: string; nowLocal: string; weekday: string; timeZone: string; signal: AbortSignal }): Promise<Extraction> }
export interface TaskClassifier { classify(i: { text: string; todayLocal: string; projectNames: string[]; signal: AbortSignal }): Promise<Classification> }
export function evaluateDraft(i: { extraction: Extraction; classification: Classification | null;
  startInstant: Date | null; now: Date }): { status: "ready" | "needs_input" | "not_a_task";
  missing: ("title" | "start")[]; ask: ("project" | "duration")[] };
```

- [ ] Write failing table tests: every row of the spec's examples table; project 0.80 → ready,
      0.79 → ask; duration 0.60 / 0.59; stated duration skips duration threshold; classifier `null`
      → ask both; start 4 min in the past ok, 6 min → missing start; blank title → not_a_task.
- [ ] Implement; `npm test -- quickAddPolicy` green; `npm run typecheck` green.
- [ ] Commit `feat: add quick add draft policy`.

### Task 3: Backend Gemini adapters

**Files:** Create `backend/src/quickadd/geminiTaskExtractor.ts`, `backend/src/quickadd/geminiTaskClassifier.ts`;
Modify `backend/src/diagnostics/redaction.ts`; Test `backend/test/quickadd/geminiTaskExtractor.test.ts`,
`backend/test/quickadd/geminiTaskClassifier.test.ts`, `backend/test/redaction.test.ts`.

- [ ] Failing tests with an injected `fetch` stub: key sent in `x-goog-api-key` and absent from the
      URL; request body matches Task 1 notes; `temperature: 0`; project options keyed `p0..pn` with
      names only; abort signal honoured; non-2xx → typed `QuickAddUpstreamError { status }` whose
      message contains **no** response body or input text; malformed JSON → same error class;
      Zod validates the extractor JSON (reject unknown `startStatus`, clamp title to 120 chars).
- [ ] Redaction tests: `x-goog-api-key: abc` and `{"goog_api_key":"abc"}` are redacted; add the
      exact keys `xgoogapikey`, `googapikey`, `geminiapikey`.
- [ ] Implement extractor prompt per spec (date rules, never invent). Implement classifier per
      Task 1 shape.
- [ ] `npm test` and `npm run typecheck` green. Commit `feat: add Gemini quick add adapters`.

### Task 4: Backend service, config, route, e2e fakes

**Files:** Create `backend/src/quickadd/quickAddService.ts`; Modify `backend/src/config.ts`,
`backend/src/server.ts` (`createBackendCore` builds the service when the key is set),
`backend/src/http/routes.ts` (`RouteServices.quickAdd?: QuickAddService`), `backend/test/e2eServer.ts`
(keyword-driven fakes: "dentist" → Personal 0.91 / 15 min 0.72; "report" → Work 0.97 / 120 0.41;
no clock time → `missing`), `.env.example` if present, `docs/runbooks/production-rollout.md`;
Test `backend/test/quickadd/quickAddService.test.ts`, `backend/test/http/routes.test.ts`,
`backend/test/e2eServer.test.ts`.

- [ ] Failing service tests: extractor and classifier start in parallel (classifier not awaited
      after extractor); 8 s overall deadline aborts both; classifier failure → `ask` both with
      fallback options; option keys mapped back to Todoist ids; `assigneeId` = owner when
      `canAssignTasks`; start resolved with `resolveDueInstant(startLocal, timeZone)` (check its
      local-time contract first); classifier skipped when `isTask=false`; top-3 options sorted.
- [ ] Failing route tests: 401 without bearer; 400 for empty/1001-char text and bad time zone;
      `503 quick_add_disabled` without service; `503 quick_add_unavailable` on extractor error;
      200 shape matches spec exactly.
- [ ] Observability: on failures `captureException` with markers `quick_add_extract_failed` /
      `quick_add_classify_failed` and extras `{ textLength, status, latencyMs }` only. Add a test
      that spies the observability sink and asserts the fixture sentence never appears in any
      captured payload or log line.
- [ ] Implement; config keys per spec; runbook: set `GEMINI_API_KEY` in the VPS env file, **gate:
      billing enabled on the Gemini project**, restart, smoke test with `curl` to `/quick-add/draft`.
- [ ] `cd backend && npm test && npm run typecheck && npm run build` green.
      Commit `feat: add quick add draft endpoint`.

### Task 5: Shared client model and API

**Files:** Create `client/shared/src/commonMain/kotlin/app/buzer/shared/model/QuickAddModels.kt`;
Modify `client/shared/.../api/BuzerApiClient.kt`, `TaskBackendClient.kt`, and every
`TaskBackendClient` fake (`grep -rn ": TaskBackendClient" client`); Test in the shared test source
set next to existing API tests.

- [ ] Failing tests: deserialize the spec's 200 example (incl. `probability: null`); 503 body maps to
      a typed failure (`Disabled` / `Unavailable`) via the existing error mapping style.
- [ ] Implement `quickAddDraft(request)`; fakes return a configurable response.
- [ ] `./gradlew :shared:testAndroidHostTest` green (from `client/`). Commit `feat: add quick add client API`.

### Task 6: QuickAddActivity, state machine, widget, Sentry exclusions

**Files:** Create `client/androidApp/src/main/kotlin/app/buzer/android/quickadd/QuickAddState.kt`,
`QuickAddActivity.kt`, `QuickAddScreen.kt`; Modify `widget/AddTaskWidgetProvider.kt`,
`res/layout/add_task_widget.xml`, `res/values/strings.xml` (`add_task_widget_title` → "🎤 Quick add",
description), `AndroidManifest.xml`, `observability/SentryAndroidObservability.kt`;
Test `client/androidApp/src/test/kotlin/app/buzer/android/quickadd/QuickAddStateTest.kt`,
existing Sentry options test if present.

- [ ] Failing `QuickAddStateTest`: ready → Countdown(5); tick to 0 → Submitting with the exact
      `CreateTaskRequest` (`timeZone` only when recurrence ≠ never, matching `AddTaskScreen`);
      pause stops ticks, resume restarts at 5; undo → Editing with text kept and no request;
      needs_input preselects top chips, changing a chip updates the request and sets the
      `projectChanged`/`durationChanged` outcome flags; missing start → Editing with message;
      `not_a_task`, `Disabled`, `Unavailable` states; non-Success `CoordinatorActionResult` →
      error state with Retry.
- [ ] Implement state (pure Kotlin, injected clock/ticker) and Compose screen: dialog theme,
      auto-focus + `SoftwareKeyboardController.show()`, IME action Send, summary card, chips,
      countdown + Undo, [Open full form] → `MainActivity` with `EXTRA_OPEN_ADD_TASK`.
      Test tags: `quick_add_text`, `quick_add_send`, `quick_add_undo`, `quick_add_create`,
      `quick_add_chip_project_<i>`, `quick_add_chip_duration_<i>`, `quick_add_message`.
- [ ] Logging: `AppLog` lines with `textLength`, status, probabilities, and the `quick_add_outcome`
      fields only. Grep the new files for `AppLog`/`Sentry` calls and confirm no text/title.
- [ ] Sentry: `beforeScreenshotCaptureCallback` and `beforeViewHierarchyCaptureCallback` return
      `false` while `QuickAddActivity` is resumed (track with a process-wide flag set in
      `onResume`/`onPause`); mask the text field for replay. Unit-test the callbacks.
- [ ] Widget PendingIntent → `QuickAddActivity` (`FLAG_IMMUTABLE`). Manifest per spec.
- [ ] `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
      green. Commit `feat: add voice quick add activity and repurpose widget`.

### Task 7: Instrumentation tests and full verification

**Files:** Create `client/androidApp/src/androidTest/kotlin/app/buzer/android/quickadd/QuickAddRealBackendTest.kt`.

- [ ] Tests against `e2eServer` fakes, launching `QuickAddActivity` directly:
      (1) "Call the dentist tomorrow at nine" → countdown → task appears in e2e mirror with project
      Personal, 15 min; (2) same, tap Undo → no `POST /tasks` recorded; (3) "Prepare the quarterly
      report Friday at 8" → duration chips shown, pick 3 h → created with 180; (4) "Buy a gift for
      mum" → `quick_add_message` mentions start time, nothing created.
- [ ] Iterate: `ANDROID_TEST_CLASS=app.buzer.android.quickadd.QuickAddRealBackendTest scripts/e2e-real-backend.sh`.
- [ ] Full unfiltered `scripts/e2e-real-backend.sh` green (AGENTS.md: UI change).
- [ ] Commit `test: cover voice quick add end to end`. Push; check `gh run list --workflow=ci.yml --limit 1`.

### Task 8: Live rollout and manual verification (owner + agent)

- [ ] Owner enables billing on the Gemini API project, creates a key restricted to the Generative
      Language API, puts it in the VPS env file (runbook), restarts the backend.
- [ ] Smoke test `/quick-add/draft` with `curl` from the dev machine (bearer token from the local
      env, not pasted into notes).
- [ ] Install on the Pixel 10 (`docs/notes/local-development.md`), add the widget, dictate the six
      spec fixtures with Rambler. Record status, latency, and Todoist result per fixture
      (no dictated text beyond the fixture sentences).
- [ ] Privacy check: in Sentry (`buzer-android`, `buzer-backend`) search the last hour's events,
      breadcrumbs and logs for a fixture word ("dentist") → expect zero hits. Check VPS stdout logs
      the same way.
- [ ] Handoff with the AGENTS.md verification block; backlog items stay listed in the spec.
