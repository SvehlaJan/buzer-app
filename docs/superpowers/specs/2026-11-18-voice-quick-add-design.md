# Voice Quick Add — Design

Date: 2026-11-18
Status: Approved (owner, 2026-11-18 planning session)
Decision: `docs/decisions/2026-11-18-voice-quick-add.md`
Plan: `docs/superpowers/plans/2026-11-18-voice-quick-add.md`

## Goal

Tap the home-screen widget, dictate one sentence ("Call the dentist tomorrow at nine"), and get a
Commitment in Todoist. The AI guesses project and duration; it creates the task by itself only when
it is confident, and asks otherwise. Keep it the smallest thing that works.

## Non-goals (backlog)

| Backlog item | Note |
| --- | --- |
| Automated time-slot suggestions ("tomorrow afternoon" → first free slot) | Owner asked to keep it. |
| Slovak dictation | Rambler already handles Slovak; needs prompt + test fixtures. |
| Continuous improvement from the owner's history | Send last ~50 tasks (title → project, duration) as examples from the VPS mirror; opt-in; must keep the minimization rules below. Threshold tuning uses the content-free `quick_add_outcome` log added in this MVP. |
| Assignee extraction ("Petra should…") | MVP always assigns shared-assignable projects to the owner, like `AddTaskScreen`'s default. |
| Prefilling the full `AddTaskScreen` from a draft | MVP fallback opens the empty full form. |
| One-tap recording via ML Kit GenAI Speech Recognition | Removes the Rambler tap. Alpha API today. |
| On-device extraction with Gemini Nano (Prompt API) | Offline mode. |
| Checklist items, description, labels, priority | Not dictated in the MVP. |

## User flow

```
Widget "🎤 Quick add" ──► QuickAddActivity (dialog-styled, keyboard open, text field focused)
   owner taps Rambler in Gboard, speaks, presses Send (IME action) or the Send button
        ▼
   POST /quick-add/draft { text, timeZone }
        ▼
   ┌ status = ready        → summary card + "Creating in 5…" + [Undo]
   │                          countdown ends → POST /tasks (existing) → "Created" → finish()
   ├ status = needs_input  → summary card; uncertain fields show top-3 chips (top preselected);
   │                          [Create] → POST /tasks.  Missing/ambiguous start or title →
   │                          message "Add a start time and send again", text stays editable.
   ├ status = not_a_task   → "Didn't catch a task — try again", text stays editable.
   └ error (503/timeout)   → "AI unavailable" + [Open full form] (MainActivity, EXTRA_OPEN_ADD_TASK)
```

Countdown rules: it runs only while the activity is resumed. `onPause` stops it; `onResume`
restarts a full 5 s. Undo returns to the editable text with the draft discarded. A task is never
created while the owner cannot see the screen.

### Examples (acceptance fixtures)

| Dictated | Extraction | Classification | Result |
| --- | --- | --- | --- |
| "Call the dentist tomorrow at nine" | title *Call the dentist*, start tomorrow 09:00 | Personal 0.91; 15 min 0.72 | ready → auto in 5 s |
| "Prepare the quarterly report Friday at 8, work" | start Fri 08:00 | Work 0.97; 120 0.41 / 180 0.33 | needs_input: duration chips [2h][3h][1.5h] |
| "Gym every Monday at seven for ninety minutes" | Mon 07:00, 90 min stated, weekly | Health 0.88 | ready (recurrence weekly) |
| "Buy a gift for mum" | no start | — | needs_input: "Add a start time and send again" |
| "Tomorrow morning do the taxes" | start ambiguous (no clock time) | — | needs_input: start |
| "Uh, the thing, you know" | `isTask=false` | — | not_a_task |

## Backend

### Endpoint `POST /quick-add/draft`

Authenticated like every other route (bearer `ANDROID_API_TOKEN`), registered inside the existing
rate-limited plugin scope. Read-only: no Todoist call, no SQLite write.

Request (Zod `strictObject`):

```json
{ "text": "Call the dentist tomorrow at nine", "timeZone": "Europe/Prague" }
```

`text`: trimmed, 1–1000 chars. `timeZone`: IANA, validated with the existing `isIanaTimeZone`.

Response `200`:

```json
{
  "status": "ready",
  "draft": {
    "title": "Call the dentist",
    "dueDatetime": "2026-11-19T08:00:00.000Z",
    "durationMinutes": 15,
    "projectId": "<todoist project id>",
    "assigneeId": null,
    "recurrencePreset": "never",
    "timeZone": "Europe/Prague"
  },
  "missing": [],
  "ask": [],
  "projectOptions": [{ "projectId": "…", "name": "Personal", "probability": 0.91 }],
  "durationOptions": [{ "minutes": 15, "probability": 0.72 }]
}
```

- `status`: `"ready" | "needs_input" | "not_a_task"`.
- `missing`: subset of `["title", "start"]` — the owner must re-dictate/edit text.
- `ask`: subset of `["project", "duration"]` — the owner picks a chip.
- `projectOptions` / `durationOptions`: top 3 by probability, descending. Always present (may be
  empty when `not_a_task`).
- `draft` fields may be `null` when missing; `projectId`/`durationMinutes` hold the top option.
- `assigneeId` = owner's Todoist user id when the chosen project `canAssignTasks`, else `null`
  (mirrors `AddTaskScreen` default).
- `timeZone` is echoed so the client can pass it to `POST /tasks` when recurrence ≠ never.

Errors: `400` validation (existing handler), `503 { code: "quick_add_disabled" }` when
`GEMINI_API_KEY` is empty, `503 { code: "quick_add_unavailable" }` when extraction fails or the
overall 8 s deadline passes. Classifier failure alone is **not** an error (see degradation).

### Components (`backend/src/quickadd/`)

| File | Responsibility |
| --- | --- |
| `quickAddTypes.ts` | `TaskExtractor`, `TaskClassifier` interfaces and their input/output types. |
| `quickAddPolicy.ts` | Pure: thresholds, status/missing/ask computation, option ranking. |
| `geminiTaskExtractor.ts` | Gemini `generateContent` with JSON schema output. `fetch`, no SDK. |
| `geminiTaskClassifier.ts` | Gemini Decisions API (`gemini-decision-1`). `fetch`, no SDK. |
| `quickAddService.ts` | Orchestrates: builds prompt context, runs both calls in parallel with one deadline, maps options back to Todoist ids, resolves the start instant, applies the policy. |

`fetch` instead of an SDK: two small calls, explicit `AbortSignal` deadline, full control over
what is logged, and no dependency to audit. Plan Task 1 confirms the exact REST shapes.

### Extraction (Gemini Flash-class model)

Model id from `GEMINI_EXTRACT_MODEL` (default chosen and recorded in Plan Task 1 from the official
models page). `temperature: 0`. JSON schema output:

```ts
{
  isTask: boolean;
  title: string;                 // cleaned, imperative, ≤ 120 chars, no date/duration words
  startLocal: string | null;     // "YYYY-MM-DDTHH:mm" in the owner's time zone
  startStatus: "explicit" | "missing" | "ambiguous";
  statedDurationMinutes: number | null; // only if the owner said a duration
  recurrencePreset: "never" | "daily" | "weekdays" | "weekly" | "monthly" | "yearly";
}
```

Prompt context: current local date, weekday and time in `timeZone`, and the rules: a date without
a clock time is `ambiguous`; "morning/afternoon/evening/later" without a clock time is `ambiguous`;
a bare clock time means the next occurrence of that time today or tomorrow; never invent values.
The project list is **not** sent to the extractor.

### Classification (Gemini Decision 1)

One request, two multiple-choice questions over a text input containing the dictated text and
today's date:

1. **Project** — options = active projects from `ProjectRepository.listActive()` (cap 255),
   labelled by **name only**. Option keys are local indices (`p0`, `p1`, …) mapped back to Todoist
   ids in the service. Todoist ids never leave the VPS.
2. **Duration** — options `15, 30, 45, 60, 90, 120, 180, 240` minutes.

Output: probability per option. Skipped entirely when `isTask=false`.

### Policy (`quickAddPolicy.ts`)

```
PROJECT_THRESHOLD  = 0.80
DURATION_THRESHOLD = 0.60
START_PAST_TOLERANCE = 5 min

not_a_task   if !isTask or title blank
missing      += "title" if title blank; += "start" if startStatus != explicit
                or resolved start < now − tolerance
ask          += "project"  if classifier failed or top project p < 0.80
             += "duration" if statedDuration == null and (classifier failed or top p < 0.60)
ready        if missing == [] and ask == []
needs_input  otherwise
```

Stated durations win over the classifier. Thresholds are constants with tests, tuned later from the
`quick_add_outcome` log.

### Degradation

| Failure | Behaviour |
| --- | --- |
| `GEMINI_API_KEY` empty | `503 quick_add_disabled` (client offers the full form). |
| Extractor error / invalid JSON / deadline | `503 quick_add_unavailable`; `captureException` with marker `quick_add_extract_failed`, **no text**. |
| Classifier error / deadline | Draft still returned: `ask` includes project and duration; options = all projects in mirror order (top 3) with `probability: null`, durations `[30, 60, 90]`. Marker `quick_add_classify_failed`. |

### Configuration

New `AppConfig` keys: `GEMINI_API_KEY` (secret, default `""`), `GEMINI_EXTRACT_MODEL`,
`GEMINI_DECISION_MODEL` (default `gemini-decision-1`). Documented in
`docs/runbooks/production-rollout.md`. `.env` remains uncommitted.

### e2e server

`backend/test/e2eServer.ts` wires deterministic fake `TaskExtractor`/`TaskClassifier`
implementations (keyword-driven) so instrumentation tests never call Google.

## Android client

| File | Change |
| --- | --- |
| `client/shared/.../model/QuickAddModels.kt` | `QuickAddDraftRequest/Response` (kotlinx.serialization). |
| `client/shared/.../api/BuzerApiClient.kt` + `TaskBackendClient.kt` | `suspend fun quickAddDraft(request)`. |
| `client/androidApp/.../quickadd/QuickAddState.kt` | Pure state machine (JVM-tested): `Editing → Loading → Countdown / NeedsInput / NotATask / Unavailable → Submitting → Done`. |
| `client/androidApp/.../quickadd/QuickAddActivity.kt` | Compose UI, dialog theme, `windowSoftInputMode=stateVisible|adjustResize`, field auto-focused, IME action Send. Submits through `AddTaskDependencies.createTaskAction(context)` so creation reuses the coordinator path. |
| `client/androidApp/.../widget/AddTaskWidgetProvider.kt` | PendingIntent now targets `QuickAddActivity`. Label "🎤 Quick add". `EXTRA_OPEN_ADD_TASK` stays: the Unavailable fallback uses it. |
| `AndroidManifest.xml` | Declare `QuickAddActivity` (`exported=false`, `excludeFromRecents=true`, `taskAffinity=""`, `launchMode=singleTask`). |

Submission result mapping uses the existing `CoordinatorActionResult` handling: `Success` → "Created"
and finish after 1 s; any other result → show its message, keep the draft, offer [Retry] and
[Open full form]. No new retry/queue logic.

## Privacy and security

Data rules (all mandatory, all have plan steps and tests where testable):

1. **Key custody.** `GEMINI_API_KEY` exists only in the VPS environment. Never in the APK,
   `BuildConfig`, git, logs or Sentry. Sent as the `x-goog-api-key` header, never as a `?key=` URL
   parameter (URLs end up in logs and error messages). Add `x-goog-api-key` / `goog-api-key` to
   `backend/src/diagnostics/redaction.ts` exact credential keys, with a test.
2. **No training on our data.** The Gemini API project must have billing enabled (paid tier) before
   the key is put on the VPS. Free-tier content may be used by Google to improve products. Recorded
   as a rollout gate in the runbook.
3. **Data minimization to Google.** Sent: dictated text, current local date/time and time zone,
   project **names**, the fixed duration buckets. Not sent: Todoist ids, user ids, collaborator
   names/emails, other tasks, device ids.
4. **No persistence.** The backend stores neither the dictated text nor drafts. No new table. The
   client keeps the text only in memory for the activity's lifetime (no `SharedPreferences`, no
   saved-state bundle beyond what Compose `rememberSaveable` needs for rotation).
5. **No content in telemetry.** Backend logs and Sentry events for quick add carry only: request
   id, status, `textLength`, latency, top probabilities, error class/HTTP status. Gemini error
   bodies are not attached (they can echo input). Android `AppLog` lines carry the same; the
   existing `SentryEventSanitizer` stays in place.
6. **No screenshots of dictated text.** `isAttachScreenshot` and `isAttachViewHierarchy` are on in
   `SentryAndroidObservability`. Add `beforeScreenshotCaptureCallback` and
   `beforeViewHierarchyCaptureCallback` that return `false` while `QuickAddActivity` is resumed.
   Session Replay (on-error only) keeps its default text masking; the quick-add text field is also
   explicitly masked.
7. **Transport.** Device → VPS uses the existing HTTPS + bearer token; VPS → Google is HTTPS.
8. **Abuse limits.** Existing global rate limit (60/min) applies; text capped at 1000 chars; the
   Decision request caps project options at 255.
9. **Content-free outcome log.** Client logs `quick_add_outcome` with `status`, `autoSubmitted`,
   `undone`, `projectChanged`, `durationChanged` booleans — the input for later threshold tuning
   and for the continuous-improvement backlog item, without storing any content.

## Testing

- Backend unit: policy table tests (every row of the examples table + thresholds at the boundary),
  extractor/classifier adapters with stubbed `fetch` (request shape, header not URL key, timeout,
  malformed JSON), service tests (parallelism, deadline, classifier degradation, id mapping, past
  start), route tests (auth, validation, 503 codes), redaction test for the new header names.
- Client JVM: shared model serialization; `QuickAddState` transitions (countdown pause/resume,
  undo, chip selection, request building incl. `timeZone` only for recurring tasks).
- Instrumentation (`scripts/e2e-real-backend.sh`): new `QuickAddRealBackendTest` with the fake
  extractor/classifier — ready+countdown creates a task; undo creates nothing; needs_input chip
  flow creates the chosen project/duration; missing start shows the message. Full suite run before
  done.
- Manual on the Pixel 10: Rambler dictation of the six fixture sentences against the live VPS with
  a paid key; verify Todoist result and that Sentry/VPS logs contain no dictated text.

## Existing behaviour affected

- Widget tap target changes from `MainActivity` add-task screen to `QuickAddActivity`. No existing
  JVM or instrumentation test references the widget (`grep AddTaskWidget|add_task_widget` over
  `client/**/test*` is empty as of 2026-11-18). `MainActivity`'s `EXTRA_OPEN_ADD_TASK` handling is
  unchanged and still covered by `AddTaskFakeBackendTest` paths that open the form.
- `backend/test/e2eServer.test.ts` and `backend/test/http/routes.test.ts` gain cases for the new
  route; no existing assertion changes.
