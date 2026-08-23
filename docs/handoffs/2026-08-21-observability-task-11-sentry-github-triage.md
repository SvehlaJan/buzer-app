# Handoff: Observability Task 11 - Add Deduplicated Sentry-To-GitHub Triage

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 11: Add Deduplicated Sentry-To-GitHub Triage  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented the deduplicated Sentry-to-GitHub triage tool (`tools/sentry-github-sync` and `scripts/sentry-triage.mjs`), enabling automated triage of Sentry diagnostic issues and manual user reports into tracked GitHub issues with deduplication, session pairing, and strict secret redaction:

1. **TypeScript Triage Tooling Package** (`tools/sentry-github-sync/`):
   - Configured standalone TypeScript module (`package.json`, `tsconfig.json`).
   - Defined core type models (`types.ts`) for Sentry issues, events, tags, attachments, GitHub issues, comments, labels, and triage decisions (`create_issue`, `comment`, `reopen_and_comment`, `ignore`).
   - Implemented `SentryClient` (`sentry.ts`) using native `fetch` to retrieve unresolved issues, event attachments, and latest event capsules with pagination and error sanitization.
   - Implemented `GitHubClient` (`github.ts`) using native `fetch` to query issues, create issues, post comments, reopen closed issues (for regression tracking), and ensure standard triage labels.
   - Implemented CLI runner (`index.ts`) supporting `--dry-run`, `--sentry-token`, `--sentry-org`, `--sentry-project`, `--github-token`, `--repo`, `--lookback-hours`, with full dry-run preview mode.

2. **Categorization & Telemetry Pairing Engine** (`src/triage.ts`, `scripts/sentry-triage.mjs`):
   - Categorizes issues into `fatal`, `handled_non_fatal`, `manual_report`, and `validation`.
   - **Paired Manual Reports**: Automatically identifies and correlates Android client reports (`buzer.android.manual_report`) and backend service reports (`buzer.backend.manual_report`) sharing the same `report_session_id`, combining them into a single cross-platform GitHub issue.
   - **Stable Fingerprints & Deduplication**:
     - Embeds hidden HTML markers in issue bodies: `<!-- sentry-fingerprint: <key> -->` and `<!-- sentry-issue-id: <id> -->`.
     - Maps new events for existing open issues to comments (`comment`).
     - Detects regressions on closed issues and automatically reopens with context (`reopen_and_comment`).
     - Ignores discarded/ignored Sentry issues.

3. **Markdown Formatting & Secret Redaction** (`src/format.ts`, `src/redact.ts`):
   - Deterministic issue titles: `[Sentry] <category>: <clean title>` (e.g. `[Sentry] manual_report: User Report (<reportSessionId>)`).
   - Generates structured Markdown bodies containing:
     - Sentry issue permalinks, platform, level, status, release, and environment.
     - Diagnostic metadata table (`report_session_id`, `android_diagnostic_event_id`, `backend_diagnostic_event_id`, `device_id`, `task_id`, `occurrence_key`, `action_id`, `capsule_schema_version`).
     - Combined paired event summary table for cross-process bug reports.
     - Attachment manifest summary list (without auth tokens in query params or URLs).
     - Formatted stack trace snippets.
     - Standard triage checklist.
   - Strict redaction across all generated issue titles, bodies, and comments (stripping Bearer/Basic headers, PEM keys, passwords, session tokens, API keys, and credential URLs).

4. **CI & Workflow Automation** (`.github/workflows/sentry-triage.yml`, `.github/workflows/ci.yml`):
   - Added manual-dispatch workflow `.github/workflows/sentry-triage.yml` for operator-initiated triage runs.
   - Added `sentry-github-sync` testing and typechecking job to `.github/workflows/ci.yml`.

5. **Test Suites** (`tools/sentry-github-sync/test/`, `scripts/sentry-triage.test.mjs`):
   - Vitest test suite (`format.test.ts`, `triage.test.ts`) covering secret redaction, title generation, marker embedding, paired session correlation, comment generation, regression reopen flows, and dry-run outputs.
   - Standalone `node:test` suite (`scripts/sentry-triage.test.mjs`).

---

## 2. Verification and Test Evidence

- **Unit & Formatting Tests**:
  - `npm test` in `tools/sentry-github-sync`:
    - 30 tests passed (0 failures).
- **Typecheck & Build**:
  - `npm run typecheck` in `tools/sentry-github-sync`: 0 errors.
  - `npm run build` in `tools/sentry-github-sync`: built cleanly.

---

## 3. Files Created / Modified

- `tools/sentry-github-sync/package.json` (created)
- `tools/sentry-github-sync/package-lock.json` (created)
- `tools/sentry-github-sync/tsconfig.json` (created)
- `tools/sentry-github-sync/src/types.ts` (created)
- `tools/sentry-github-sync/src/redact.ts` (created)
- `tools/sentry-github-sync/src/format.ts` (created)
- `tools/sentry-github-sync/src/triage.ts` (created)
- `tools/sentry-github-sync/src/github.ts` (created)
- `tools/sentry-github-sync/src/sentry.ts` (created)
- `tools/sentry-github-sync/src/index.ts` (created)
- `tools/sentry-github-sync/test/format.test.ts` (created)
- `tools/sentry-github-sync/test/triage.test.ts` (created)
- `scripts/sentry-triage.mjs` (created)
- `scripts/sentry-triage.test.mjs` (created)
- `.github/workflows/sentry-triage.yml` (created)
- `.github/workflows/ci.yml` (modified)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-11-sentry-github-triage.md` (created)

---

## 4. Next Steps

- Proceed to **Task 12: Add Release Artifacts, Deployment Configuration, And Runbooks**.
