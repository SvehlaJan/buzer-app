# Smart Home Design Handoff

Date: 2026-08-02

## Task And Status

Task: audit the Home screen, research gaps, brainstorm a smart continuous agenda, and
record the approved design.

Status: design complete. Implementation planning and code changes have not started;
the written specification requires user review first under the brainstorming workflow.

## Plan Steps Attempted

- Explored the approved product spec, current Home/Morning Review/readiness code, tests,
  recent handoffs, and repository workspace rules: complete.
- Inspected current Home behavior on the API 36 emulator with deterministic backend
  data: complete.
- Researched agenda, Android permission/alarm, and Material pull-to-refresh patterns:
  complete.
- Iterated product choices and visual proposal with the user: complete.
- Wrote and self-reviewed the accepted design: complete.
- Implementation plan: intentionally not started pending user review of the spec.

## Files Changed

- `docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md`
- `docs/handoffs/2026-08-02-smart-home-design.md`

No production code or tests changed.

## Findings And Assumptions

- The client already receives future tasks; Home drops them in client-side presentation
  filtering, so no backend endpoint is needed.
- Existing Morning Review repair is functional, but no initial daily 08:00 production
  alarm exists.
- The existing permission-health reader has no production Health screen consumer and
  misses full-screen-intent plus notification enablement checks.
- The generated visual was an exploration artifact. The final accepted design removes
  the calendar and overflow buttons shown in that earlier image.
- Device-local Morning Review acknowledgement is sufficient for this personal,
  Android-first MVP.

## Commands And Evidence

Verification:
- Command: `git worktree list`
- Result: PASS
- Evidence: existing primary and overdue-review worktrees were identified before a new
  isolated `feature/smart-home` worktree was created.

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: the primary checkout and new feature worktree were clean before edits.

Verification:
- Command: `git check-ignore -v .worktrees`
- Result: PASS
- Evidence: `.gitignore` line 2 ignores `.worktrees/`.

Verification:
- Command: documentation self-review searches and `git diff --check`
- Result: PASS
- Evidence: no placeholder markers, unresolved product decisions, or whitespace errors
  remain. Mentions of calendar/overflow consistently document their removal.

Verification:
- Command: Android/JVM/E2E tests
- Result: SKIPPED
- Evidence: this branch changes documentation only. The specification records the full
  required verification matrix for subsequent implementation.

## Manual Verification

- The existing Home was inspected on an Android 16/API 36 emulator with deterministic
  task data. It confirmed current-task duplication and lack of later future tasks.
- The final hierarchy was reviewed through browser mockups and a generated
  high-fidelity image. User approved it, then explicitly removed the calendar button,
  permanent Morning Review access, and Health/permissions menu.

## Known Failures Or Skipped Checks

- No implementation exists on this branch, so none of the proposed behavior has been
  exercised as product code.
- Full Android and real-backend E2E suites are deliberately deferred to the eventual
  UI implementation and may not be skipped at completion.

## Next Recommended Step

Have the user review the committed written specification. After approval, invoke the
writing-plans workflow and produce a task-by-task implementation plan that migrates
the named JVM and Android instrumentation suites in the same change sequence.
