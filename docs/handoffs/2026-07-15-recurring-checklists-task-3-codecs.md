# Handoff: Recurring Checklists Task 3 Codecs

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 3 — Add Recurrence Presets And Managed Description Encoding
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- All Task 3 plan checkboxes are complete; Task 4 was not started.
- Added a pure recurrence translator that turns the six fixed presets into canonical English Todoist
  rules using the selected start interpreted in its IANA zone. It deliberately produces no future
  occurrence calculations.
- Added conservative recurrence classification: a non-recurring task is `none`, only exact
  Buzer-shaped English rules with a valid zone are `supported`, and all other recurring rules are
  `custom` with no destructive normalization.
- Added a lossless managed-description codec. A suffix is recognized only when the exact delimiter
  occupies a line and all nonblank suffix lines are valid activity entries; malformed content is
  retained as user text and a later extension appends a fresh managed section.
- Added extension entry creation with Todoist-zone formatting, UTC fallback, the required Unicode
  arrow format, and a pre-mutation `activity_log_full` rejection at 16,384 characters.

## Files Changed

- `backend/src/domain/taskRecurrence.ts`
- `backend/src/domain/taskDescriptionCodec.ts`
- `backend/test/domain/taskRecurrence.test.ts`
- `backend/test/domain/taskDescriptionCodec.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-3-codecs.md`

## TDD Evidence

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts`
- Result: FAIL (expected RED)
- Evidence: Vitest could not resolve `../../src/domain/taskRecurrence.js` because the module did not yet exist.

Verification:
- Command: `npm test -- test/domain/taskDescriptionCodec.test.ts`
- Result: FAIL (expected RED)
- Evidence: Vitest could not resolve `../../src/domain/taskDescriptionCodec.js` because the module did not yet exist.

## Final Verification

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts`
- Result: PASS
- Evidence: 1 test file and 26 tests passed.

Verification:
- Command: `npm test -- test/domain/taskDescriptionCodec.test.ts`
- Result: PASS
- Evidence: 1 test file and 10 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 with no diagnostics.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors after staging the Task 3 files.

## Manual Verification

- Self-reviewed the exact recurrence literals, including DST-local time formatting and the ordinal
  edge cases 11th–13th.
- Self-reviewed managed delimiter boundary handling, duplicate/corrupt preservation, lossless
  reconstruction, Unicode entry text, UTC fallback, and the overflow branch.
- No live Todoist, WhatsApp, VPS, Android, or production database mutation was performed.

## Assumptions And Known Limits

- Task 3 is intentionally pure-domain work. Later coordinator and snapshot tasks own the mutation
  policy that preserves full custom recurrence metadata for ordinary edits and rejects custom
  schedule/recurrence changes; this codec exposes the classification needed for that integration.
- No date library was added. Node/TypeScript `Intl` supplies IANA-zone formatting and lets Todoist
  remain authoritative for DST transitions and future occurrence calculation.

## Recovery Validation (2026-07-15)

- Re-audited the uncommitted Task 3 files against the approved design and Task 3 plan only. Task 4
  was not started.
- Found and fixed two conservative-codec edge cases through new RED/GREEN tests: a fresh managed
  section no longer introduces a blank line when user text already ends in a line ending, and an
  impossible yearly date (for example `February 31st`) remains a custom rule because Buzer could
  never generate it.
- Self-review confirmed the six canonical English rules, IANA-zone local-start interpretation, DST
  reliance on `Intl`/Todoist rather than Buzer occurrence math, exact rule recognition, delimiter
  boundary behavior, malformed/duplicate preservation, Unicode-arrow audit format, UTC fallback,
  and pre-mutation length rejection.

Verification:
- Command: `npm test -- test/domain/taskDescriptionCodec.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the new trailing-newline test first received an extra blank line, then passed 11/11
  after the codec preserved the existing line boundary.

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: `every year on February 31st at 14:05` was initially classified as supported, then
  passed 27/27 after calendar-valid yearly-date recognition was added.

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts test/domain/taskDescriptionCodec.test.ts`
- Result: PASS
- Evidence: 2 files and 38 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 with no diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 21 files and 146 tests passed; the inherited baseline was 19 files and 108 tests before
  Tasks 1–2 and this Task 3 work.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Quality-Review Follow-up (2026-07-15)

- Addressed two Important Task 3 review findings without beginning Task 4.
- `translateRecurrencePreset` and `appendExtension` now accept only strict ISO instants with an
  explicit `Z` or `±HH:mm` offset and a real calendar date; date-only, offset-less, arbitrary, and
  normalized-invalid dates are rejected before time-zone formatting.
- After a malformed user-owned marker causes the first extension to append a fresh delimiter,
  decoding now finds that valid trailing managed suffix. The earlier malformed content remains
  exactly user-owned, and subsequent extensions append entries to the recovered suffix rather than
  creating another delimiter.

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts test/domain/taskDescriptionCodec.test.ts`
- Result: FAIL (expected RED)
- Evidence: 9 intended failures: four lax timestamp inputs in each codec and the malformed-marker
  recovery assertion.

Verification:
- Command: `npm test -- test/domain/taskRecurrence.test.ts test/domain/taskDescriptionCodec.test.ts`
- Result: PASS (GREEN)
- Evidence: 2 files and 47 tests passed after strict parsing and trailing-suffix recovery.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 with no diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 21 files and 155 tests passed.

## Next Recommended Step

Proceed with Task 4 only in a separately assigned task; no Task 4 files or behavior were started
in this recovery session.

## Commit

- `feat: add recurrence and task description codecs`
