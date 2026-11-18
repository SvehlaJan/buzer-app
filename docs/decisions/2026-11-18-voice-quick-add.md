# Voice Quick Add: dictate a task from the home-screen widget, AI fills the gaps

Date: 2026-11-18
Status: Accepted

## Context

The owner wants to create Commitments by voice from the home screen without filling the full
add-task form. The existing `AddTaskWidgetProvider` (a single "Add strict task" button that opens
`MainActivity` on the add-task screen) is unused and can be repurposed.

Research done in the planning session (2026-11-15 → 2026-11-18):

- **Gboard Rambler** (Pixel 10, November 2026 Pixel Drop) gives high-quality, filler-free
  dictation inside any text field. It is a keyboard (IME) feature; there is no public API to start
  it from an app. It works in any app text field, which is all we need.
- **ML Kit GenAI Speech Recognition** (advanced mode on Gemini Nano, Pixel 10) is alpha; Slovak
  coverage unconfirmed. **ML Kit Prompt API** (Gemini Nano) is foreground-only, quota-limited,
  beta, and weaker than cloud Gemini at relative-date resolution. Neither is needed for the MVP.
- **Gemini Decision 1** (`gemini-decision-1`, Gemini Decisions API, launched 2026-11-11, v1beta)
  scores closed-set questions (multiple choice up to 255 options) and returns **calibrated**
  probabilities. It cannot extract free text (titles, dates).
- **Jev** is a TypeSafe AI product (not Google). It would add a second vendor and key for no
  benefit over Decision 1. Rejected.

## Decision

1. **Scope change.** Voice Quick Add joins the MVP "In scope" list (`AGENTS.md`). Spec:
   `docs/superpowers/specs/2026-11-18-voice-quick-add-design.md`. Plan:
   `docs/superpowers/plans/2026-11-18-voice-quick-add.md`.
2. **Speech-to-text is the keyboard's job.** The repurposed widget opens a small
   `QuickAddActivity` with a focused text field; the owner dictates with Gboard Rambler (or types).
   The app records no audio and needs no microphone permission. English only for the MVP.
3. **AI runs on the VPS backend, never on the device, with the key only on the VPS.** New read-only
   endpoint `POST /quick-add/draft`. Two Gemini calls run in parallel:
   - a Gemini Flash-class model with structured JSON output extracts title, start time, an
     explicitly stated duration and recurrence;
   - Gemini Decision 1 picks the **project** (from the owner's active projects) and the
     **duration** (from fixed buckets), with calibrated probabilities.
4. **Confidence is decided by code, not by the model's self-report.** Auto-submit only when title
   and an unambiguous future start are present, the top project probability is ≥ 0.80, and the
   duration is either stated or its top bucket probability is ≥ 0.60. Otherwise the owner is asked
   (chips with the top options), or asked to add a missing start time.
5. **Undo means "not sent yet".** On a ready draft the client shows a 5-second countdown with
   Undo and only then calls the existing `POST /tasks`. Nothing is deleted on undo. Task creation
   keeps using the single Level-1 mutation path.
6. **Privacy and security are requirements, not polish.** Data minimization (only the dictated
   text, project names and today's date/time zone leave the VPS; no Todoist IDs, no collaborators,
   no task history), no persistence of dictated text, no dictated text in logs, Sentry events,
   breadcrumbs, screenshots or view hierarchies, the API key sent in a header (never a URL) and
   redacted, and **a paid (billing-enabled) Gemini API project** so Google does not use the content
   to improve its products. Details in the spec's "Privacy and security" section.

## Consequences

- One new external dependency (Gemini API) and one new secret (`GEMINI_API_KEY`) on the VPS. With
  the key unset, the endpoint answers `503 quick_add_disabled` and the widget falls back to the full
  form.
- Gemini Decision 1 is days old and on `v1beta`. Any classifier failure degrades to "ask the owner"
  (never to a silent guess). Plan Task 1 verifies the real request/response shapes before code is
  written, because the planning session could not reach the official docs.
- Backlog (explicitly out of the MVP): automated time-slot suggestions, Slovak dictation,
  learning from the owner's task history (continuous improvement), assignee extraction, prefilling
  the full form, one-tap ML Kit recording, on-device Gemini Nano extraction.
